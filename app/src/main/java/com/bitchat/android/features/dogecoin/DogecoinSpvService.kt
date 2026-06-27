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
                org.bitcoinj.core.CheckpointManager.checkpoint(params, stream, store, birthdateSecs)
                Log.i(TAG, "Loaded SPV checkpoints from $assetName for birthdate ${Date(birthdateSecs * 1000L)}")
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
        const val MIN_PEERS = 4 // eclipse-resistance floor for "synced"; broadcast (later) needs >=this too
        const val SYNCED_WITHIN_BLOCKS = 2L
    }
}
