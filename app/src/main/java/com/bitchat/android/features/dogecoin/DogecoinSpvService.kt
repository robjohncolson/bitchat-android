package com.bitchat.android.features.dogecoin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bitcoinj.core.BlockChain
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Peer
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.VersionMessage
import org.bitcoinj.core.listeners.DownloadProgressTracker
import org.bitcoinj.net.discovery.DnsDiscovery
import org.bitcoinj.store.SPVBlockStore
import org.bitcoinj.wallet.Wallet
import org.libdohj.params.DogecoinMainNetParams
import org.libdohj.params.DogecoinTestNet3Params
import java.io.File
import java.util.Date

/** Sync status for the on-device SPV light client, observed by the wallet UI. */
data class DogecoinSpvStatus(
    val network: DogecoinNetwork,
    val running: Boolean = false,
    val peerCount: Int = 0,
    val chainHeight: Int = 0,
    val bestPeerHeight: Long = 0L,
    val syncedToDateMillis: Long = 0L,
    /** True once the chain has caught up to within a small window of the best-known peer height AND a peer
     *  floor is met — only then are reads trustworthy enough to display. */
    val synced: Boolean = false
) {
    val blocksBehind: Int get() = (bestPeerHeight - chainHeight).coerceAtLeast(0L).toInt()
}

/**
 * On-device SPV light client (bitcoinj 0.14.7 + libdohj) for ONE Dogecoin network at a time.
 *
 * READ-ONLY: it imports the wallet's EXISTING key (watch + spend the same address), syncs headers + a
 * BIP37 bloom-filtered view of the wallet's transactions, and reports balance/UTXOs. It NEVER signs
 * (Option B — [DogecoinTransactionBuilder] stays the sole signer) and does NOT broadcast in this phase.
 *
 * Lifecycle: SYNC-ON-DEMAND, not a foreground service. [start] when the wallet sheet selects the SPV
 * backend / opens; [stop] on close or backend switch. Owned at app/ChatViewModel scope so it survives a
 * sheet re-open within a session. REGTEST is unsupported (no peers). bitcoinj's crypto is spongycastle,
 * isolated from the app's bcprov 1.70. See docs/dogecoin-spv-integration-plan.md.
 */
class DogecoinSpvService private constructor(
    private val appContext: Context,
    private val repository: DogecoinWalletRepository
) {
    private val lock = Any()

    private var bitcoinjContext: org.bitcoinj.core.Context? = null
    private var wallet: Wallet? = null
    private var blockStore: SPVBlockStore? = null
    private var blockChain: BlockChain? = null
    private var peerGroup: PeerGroup? = null
    private var activeNetwork: DogecoinNetwork? = null

    private val _status = MutableStateFlow(DogecoinSpvStatus(network = DogecoinNetwork.TESTNET))
    val status: StateFlow<DogecoinSpvStatus> = _status.asStateFlow()

    /** Whether SPV is even possible for [network] (REGTEST has no public peers / params). */
    fun isSupported(network: DogecoinNetwork): Boolean = paramsFor(network) != null

    /**
     * Start (or switch to) the SPV client for [network], importing the wallet's existing key. Idempotent
     * for an already-running network. Heavy work (peer connect + header download) runs on bitcoinj threads.
     */
    fun start(network: DogecoinNetwork) {
        synchronized(lock) {
            val params = paramsFor(network) ?: run {
                Log.i(TAG, "SPV not supported for $network; ignoring start")
                return
            }
            if (activeNetwork == network && peerGroup != null) return // already running
            stopLocked() // tear down any other-network instance first

            val snapshot = repository.loadWalletIfPresent(network) ?: run {
                Log.i(TAG, "No $network wallet key yet; not starting SPV")
                return
            }

            val ctx = org.bitcoinj.core.Context(params)
            org.bitcoinj.core.Context.propagate(ctx)

            // Import the EXISTING on-device key (watch + spend the same P2PKH address). NEVER createDeterministic.
            val key = ECKey.fromPrivate(DogecoinHex.decode(snapshot.key.privateKeyHex), snapshot.key.isCompressed)
            val birthdateSecs = repository.loadSpvBirthdateMillis(network) / 1000L
            key.creationTimeSeconds = birthdateSecs
            val w = Wallet(params)
            w.importKey(key)

            val store = SPVBlockStore(params, blockStoreFile(network))
            // Seed the header store near the key birthdate from a shipped checkpoints asset (if present), so
            // a recent key syncs near-instantly instead of from genesis (testnet is ~65.7M blocks).
            maybeLoadCheckpoints(params, store, birthdateSecs)

            val chain = BlockChain(params, w, store)
            val pg = HighestHeightDownloadPeerGroup(params, chain)
            pg.setUserAgent(USER_AGENT, USER_AGENT_VERSION)
            pg.maxConnections = MAX_PEERS
            pg.addWallet(w)
            pg.setBloomFilteringEnabled(true) // serve our address to peers so they return our merkleblocks
            pg.addPeerDiscovery(DnsDiscovery(params))
            // TODO Phase 2.x: route over the embedded Arti SOCKS once verified; clearnet for now (disclosed in UI).

            pg.addConnectedEventListener { _, _ -> publishStatus(network, chain, pg) }
            pg.addDisconnectedEventListener { _, _ -> publishStatus(network, chain, pg) }

            this.bitcoinjContext = ctx
            this.wallet = w
            this.blockStore = store
            this.blockChain = chain
            this.peerGroup = pg
            this.activeNetwork = network
            publishStatus(network, chain, pg)

            pg.startAsync()
            pg.startBlockChainDownload(object : DownloadProgressTracker() {
                override fun progress(pct: Double, blocksSoFar: Int, date: Date?) {
                    publishStatus(network, chain, pg)
                }
                override fun doneDownload() {
                    publishStatus(network, chain, pg)
                }
            })
            Log.i(TAG, "SPV started for $network (birthdate=${Date(birthdateSecs * 1000L)})")
        }
    }

    /** Stop the SPV client and release resources. Safe to call when not running. */
    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        peerGroup?.let { pg ->
            runCatching { pg.stopAsync() }
        }
        // The block store is memory-mapped; closing is best-effort (never on Android does WindowsMMapHack run).
        blockStore?.let { runCatching { it.close() } }
        val net = activeNetwork
        peerGroup = null
        blockChain = null
        blockStore = null
        wallet = null
        bitcoinjContext = null
        activeNetwork = null
        if (net != null) _status.value = DogecoinSpvStatus(network = net, running = false)
    }

    /**
     * Read the wallet's confirmed/estimated balance + UTXOs, with the bitcoinj Context propagated onto the
     * calling thread. Returns null if SPV is not the active backend for [network]. Caller decides whether
     * the data is trustworthy via [status] (nodeReady).
     */
    fun snapshotUnspents(network: DogecoinNetwork): List<DogecoinUtxo>? = synchronized(lock) {
        val w = wallet?.takeIf { activeNetwork == network } ?: return null
        org.bitcoinj.core.Context.propagate(bitcoinjContext)
        val ownScriptHex = DogecoinHex.encode(DogecoinAddress.p2pkhScript(walletAddress(network), network))
        w.unspents.mapNotNull { out ->
            val scriptHex = runCatching { DogecoinHex.encode(out.scriptBytes) }.getOrNull() ?: return@mapNotNull null
            // Only our own P2PKH outputs (the wallet should only hold these, but be defensive).
            if (!scriptHex.equals(ownScriptHex, ignoreCase = true)) return@mapNotNull null
            val parentHash = out.parentTransactionHash?.toString() ?: return@mapNotNull null
            val depth = runCatching { out.parentTransaction?.confidence?.depthInBlocks ?: 0 }.getOrDefault(0)
            DogecoinUtxo(
                txid = parentHash,
                vout = out.index,
                amountKoinu = out.value.value,
                scriptPubKeyHex = scriptHex,
                confirmations = depth.coerceAtLeast(0)
            )
        }
    }

    /** Confirmed/unconfirmed balance shaped like the RPC/explorer result. Null if SPV isn't active for [network]. */
    fun snapshotBalance(network: DogecoinNetwork): DogecoinWalletBalance? = synchronized(lock) {
        val w = wallet?.takeIf { activeNetwork == network } ?: return null
        org.bitcoinj.core.Context.propagate(bitcoinjContext)
        val available = w.getBalance(Wallet.BalanceType.AVAILABLE).value
        val estimated = w.getBalance(Wallet.BalanceType.ESTIMATED).value
        val unspents = snapshotUnspents(network) ?: emptyList()
        DogecoinWalletBalance(
            confirmedKoinu = available,
            unconfirmedKoinu = (estimated - available).coerceAtLeast(0L),
            utxoCount = unspents.size,
            utxos = unspents
        )
    }

    /**
     * Phase 3: FAIL-CLOSED SPV broadcast (Option B — bitcoinj only relays; the bytes were signed by
     * [DogecoinTransactionBuilder]). Returns the txid as CLAIMED (inputs reserved, tx handed to peers),
     * or null if SPV cannot broadcast here (mainnet/regtest/unsynced/too-few-peers). THROWS only via
     * [DogecoinSpvBroadcastVerifier] when the bytes are wrong (then nothing is ever broadcast).
     *
     * All fail-closed checks (network, sync, peer floor, verifier) run UNDER the lock, before the tx
     * reaches the wire. [normalizedHex] MUST already be [DogecoinRawTxValidator.normalize]d, and
     * [expectedTxid] MUST be [DogecoinTransactionBuilder.transactionId] of it.
     *
     * The blocking future await is a BEST-EFFORT propagation confirmation done OFF-lock — a timeout is
     * NOT a failure (thin testnet peer sets often don't re-announce to the originator), so we never
     * throw on timeout (that would invite a same-input double-spend retry). On-chain depth via
     * [confirmationDepth] is the real proof of acceptance.
     *
     * If [PeerGroup.broadcastTransaction] itself throws after [Wallet.receivePending] (effectively
     * unreachable behind the synced + peer-floor guards), the inputs stay locally reserved with nothing on
     * the wire; a [stop]/[start] cycle rebuilds the wallet from the chain and clears the stale reservation.
     */
    fun broadcast(network: DogecoinNetwork, normalizedHex: String, expectedTxid: String): String? {
        val prepared = synchronized(lock) {
            if (network == DogecoinNetwork.MAINNET) return null          // Phase-3 hard block (Phase 4 lifts)
            val params = paramsFor(network) ?: return null               // REGTEST has no SPV
            val pg = peerGroup?.takeIf { activeNetwork == network } ?: return null
            val w = wallet ?: return null
            val st = _status.value
            if (!st.synced || st.peerCount < MIN_PEERS) return null       // not caught up / eclipse floor
            org.bitcoinj.core.Context.propagate(bitcoinjContext)
            // Fail closed: bitcoinj must round-trip the signed bytes byte-for-byte AND agree on the txid.
            val tx = DogecoinSpvBroadcastVerifier.verifiedTransaction(params, normalizedHex, expectedTxid)
            // Reserve the spent inputs NOW so reads stop showing them as spendable (anti double-spend).
            // receivePending does not alter the verified serialization, so the wire bytes stay canonical.
            w.receivePending(tx, null)
            pg.minBroadcastConnections = MIN_PEERS                        // avoid single-peer broadcast deadlock
            pg.broadcastTransaction(tx) to tx                             // sends immediately; returns now
        }
        val (broadcast, tx) = prepared
        // Completion = MIN_PEERS re-announced; timeout = still in flight. Either way the tx is CLAIMED.
        runCatching {
            broadcast.future().get(BROADCAST_TIMEOUT_SECS, java.util.concurrent.TimeUnit.SECONDS)
        }
        return tx.hashAsString
    }

    /**
     * Phase 3 corroboration: confirmation depth of OUR broadcast [txid] as the SPV chain catches up —
     * no third party, no API key. Returns null if SPV is not the active backend for [network], the txid is
     * malformed, or the tx is not (yet) known to the wallet; 0 if known but unconfirmed; the block depth
     * (>=1) once it is mined. Callers keep polling on null/0 and treat >=1 as corroborated.
     */
    fun confirmationDepth(network: DogecoinNetwork, txid: String): Int? = synchronized(lock) {
        val w = wallet?.takeIf { activeNetwork == network } ?: return null
        org.bitcoinj.core.Context.propagate(bitcoinjContext)
        val hash = runCatching { org.bitcoinj.core.Sha256Hash.wrap(txid.trim()) }.getOrNull() ?: return null
        val tx = w.getTransaction(hash) ?: return null
        runCatching { tx.confidence?.depthInBlocks }.getOrNull()
    }

    private fun walletAddress(network: DogecoinNetwork): String =
        repository.loadWalletIfPresent(network)?.key?.address ?: ""

    private fun publishStatus(network: DogecoinNetwork, chain: BlockChain, pg: PeerGroup) {
        val height = runCatching { chain.bestChainHeight }.getOrDefault(0)
        val peers = pg.connectedPeers
        val peerCount = peers.size
        val bestPeerHeight = peers.maxOfOrNull { it.bestHeight } ?: 0L
        val headTimeSecs = runCatching { chain.chainHead.header.timeSeconds }.getOrDefault(0L)
        val caughtUp = bestPeerHeight > 0L && (bestPeerHeight - height) <= SYNCED_WITHIN_BLOCKS
        _status.value = DogecoinSpvStatus(
            network = network,
            running = true,
            peerCount = peerCount,
            chainHeight = height,
            bestPeerHeight = bestPeerHeight,
            syncedToDateMillis = headTimeSecs * 1000L,
            synced = caughtUp && peerCount >= MIN_PEERS
        )
    }

    private fun maybeLoadCheckpoints(params: NetworkParameters, store: SPVBlockStore, birthdateSecs: Long) {
        val assetName = "dogecoin-checkpoints-${networkAssetId(activeNetworkOr(params))}.txt"
        runCatching {
            appContext.assets.open(assetName).use { stream ->
                // Seed at the LATEST checkpoint at-or-before the birthdate via getCheckpointBefore (= floorEntry,
                // NO extra margin). The static CheckpointManager.checkpoint(...,time) pushes the seed ~a week
                // earlier, which on testnet is ~1M extra headers (~50 min on-device) — getCheckpointBefore is the
                // spike-proven fast path. SAFE: a freshly-generated key's birthdate IS its creation time and no
                // funds can predate it, so seeding at-or-before the birthdate never skips funds; an imported key's
                // conservative floor (e.g. 2021) still yields genesis when no checkpoint precedes it.
                val cp = org.bitcoinj.core.CheckpointManager(params, stream).getCheckpointBefore(birthdateSecs)
                val headHeight = runCatching { store.chainHead.height }.getOrDefault(0)
                if (cp.height > headHeight) {
                    // Forward-only: never regress a store already synced past the checkpoint (preserves progress
                    // across stop/start; on a fresh store headHeight=0 so the first seed always applies).
                    store.put(cp)
                    store.setChainHead(cp)
                    Log.i(TAG, "Seeded SPV store from $assetName at checkpoint height=${cp.height} (birthdate ${Date(birthdateSecs * 1000L)})")
                } else {
                    Log.i(TAG, "SPV store head $headHeight already at/after checkpoint ${cp.height}; not reseeding")
                }
            }
        }.onFailure {
            Log.i(TAG, "No checkpoints asset $assetName (continuing without; first sync may be slow): ${it.message}")
        }
    }

    private fun blockStoreFile(network: DogecoinNetwork): File =
        File(appContext.filesDir, "dogecoin-spv-${network.id}.chain")

    private fun paramsFor(network: DogecoinNetwork): NetworkParameters? = when (network) {
        DogecoinNetwork.MAINNET -> DogecoinMainNetParams.get()
        DogecoinNetwork.TESTNET -> DogecoinTestNet3Params.get()
        DogecoinNetwork.REGTEST -> null // no public peers / params for regtest SPV
    }

    private fun activeNetworkOr(params: NetworkParameters): DogecoinNetwork =
        activeNetwork ?: if (params.id == NetworkParameters.ID_MAINNET) DogecoinNetwork.MAINNET else DogecoinNetwork.TESTNET

    private fun networkAssetId(network: DogecoinNetwork): String =
        if (network == DogecoinNetwork.MAINNET) "mainnet" else "testnet"

    /**
     * bitcoinj's default download-peer selection prefers a witness-capable peer; Dogecoin has no SegWit, so
     * we pick the highest-height peer that has a chain (mirrors the spike + the langerhans NonWitnessPeerGroup).
     */
    private class HighestHeightDownloadPeerGroup(params: NetworkParameters, chain: BlockChain) :
        PeerGroup(params, chain) {
        override fun selectDownloadPeer(peers: MutableList<Peer>): Peer? {
            var best: Peer? = null
            var bestHeight = -1L
            for (peer in peers) {
                val vm: VersionMessage = peer.peerVersionMessage ?: continue
                if (!vm.hasBlockChain()) continue
                val h = peer.bestHeight
                if (h > bestHeight) { bestHeight = h; best = peer }
            }
            return best
        }
    }

    companion object {
        @Volatile private var INSTANCE: DogecoinSpvService? = null

        /** Process-wide singleton so the wallet sheet and the debug console share ONE PeerGroup/SPVBlockStore
         *  (the store is a single-writer mmap file — two services would corrupt it). */
        fun getInstance(context: Context, repository: DogecoinWalletRepository): DogecoinSpvService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DogecoinSpvService(context.applicationContext, repository).also { INSTANCE = it }
            }

        const val TAG = "DogecoinSpv"
        const val USER_AGENT = "bitchat-dogecoin-spv"
        const val USER_AGENT_VERSION = "0.1"
        const val MAX_PEERS = 6
        const val MIN_PEERS = 4 // eclipse-resistance floor for "synced"; broadcast needs >=this too
        const val SYNCED_WITHIN_BLOCKS = 2L
        const val BROADCAST_TIMEOUT_SECS = 25L // best-effort re-announce wait; timeout != failure (Claimed)
    }
}
