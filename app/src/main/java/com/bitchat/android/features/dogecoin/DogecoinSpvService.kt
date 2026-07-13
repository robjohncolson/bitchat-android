package com.bitchat.android.features.dogecoin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.bitcoinj.core.BlockChain
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Peer
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.VersionMessage
import org.bitcoinj.core.listeners.DownloadProgressTracker
import org.bitcoinj.net.BlockingClientManager
import org.bitcoinj.net.ClientConnectionManager
import org.bitcoinj.net.discovery.DnsDiscovery
import org.bitcoinj.store.SPVBlockStore
import org.bitcoinj.wallet.Wallet
import org.libdohj.params.DogecoinMainNetParams
import org.libdohj.params.DogecoinTestNet3Params
import java.io.File
import java.net.InetSocketAddress
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

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
    val synced: Boolean = false,
    /** True when the PeerGroup was built to route every peer connection through the embedded Arti SOCKS proxy
     *  (i.e. Tor was ON at [start] time). Reflects the ACTUAL transport, so the UI/console can show it. */
    val overTor: Boolean = false,
    /** Diagnostic only: connected and behind, but the live chain height has not advanced within the bounded
     *  progress window. Never participates in read or broadcast readiness. */
    val stalled: Boolean = false
) {
    val blocksBehind: Int get() = (bestPeerHeight - chainHeight).coerceAtLeast(0L).toInt()
}

/**
 * A single wallet transaction for the activity / pending-confirmation UI. READ-ONLY presentation data —
 * it never feeds signing or broadcast. [amountKoinu] is the net value RECEIVED (incoming) or the net value
 * that LEFT the wallet excluding change (outgoing), always non-negative; [confirmations] is 0 while pending.
 */
data class DogecoinSpvTx(
    val txid: String,
    val incoming: Boolean,
    val amountKoinu: Long,
    val confirmations: Int,
    val timeSeconds: Long?
)

/**
 * On-device SPV light client (bitcoinj 0.14.7 + libdohj) for ONE Dogecoin network at a time.
 *
 * READ-ONLY: it imports the wallet's EXISTING key (watch + spend the same address), syncs headers + a
 * BIP37 bloom-filtered view of the wallet's transactions, and reports balance/UTXOs. It NEVER signs
 * (Option B — [DogecoinTransactionBuilder] stays the sole signer) and does NOT broadcast in this phase.
 *
 * Lifecycle: SYNC-ON-DEMAND, not a foreground service. [start] when the wallet sheet selects the SPV
 * backend / opens; keep it process-scoped across sheet dismissal so sync and mmap state survive rapid reopen;
 * [stop] on an explicit backend/network teardown. REGTEST is unsupported (no peers). bitcoinj's crypto is
 * spongycastle, isolated from the app's bcprov 1.70. See docs/dogecoin-spv-integration-plan.md.
 */
class DogecoinSpvService private constructor(
    private val appContext: Context,
    private val repository: DogecoinWalletRepository
) {
    private val lock = Any()
    private val statusPublicationGate = DogecoinSpvStatusPublicationGate()
    // Orders synchronous starts/stops with process-owned asynchronous stop requests. A later request wins,
    // so an RPC switch survives sheet dismissal while a subsequent SPV reopen cancels that pending teardown.
    private val lifecycleRequestGeneration = AtomicInteger(0)

    private var bitcoinjContext: org.bitcoinj.core.Context? = null
    private var wallet: Wallet? = null
    private var blockStore: SPVBlockStore? = null
    private var blockChain: BlockChain? = null
    private var peerGroup: PeerGroup? = null
    private var activeNetwork: DogecoinNetwork? = null

    /** The Arti SOCKS endpoint the live PeerGroup was built against (null ⇒ clearnet). The transport is fixed
     *  at [start] time; the observer below rebuilds when this no longer matches the user's current endpoint. */
    @Volatile private var builtSocksAddress: InetSocketAddress? = null
    /** True only between a [broadcast]'s under-lock prepare and its off-lock propagation await, so the Tor
     *  observer never tears the PeerGroup down mid-broadcast (the tx is already reserved + handed to peers). */
    @Volatile private var broadcasting = false
    private val torScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /** Periodic live-chain progress check and bounded managed-download-peer recovery. */
    private var catchUpJob: Job? = null
    private val headerProgressWatchdog = DogecoinSpvHeaderProgressWatchdog(
        stallTimeoutMillis = HEADER_STALL_TIMEOUT_MS,
        recoveryCooldownMillis = HEADER_STALL_RECOVERY_COOLDOWN_MS,
        maxRecoveryAttempts = MAX_HEADER_STALL_RECOVERIES,
        caughtUpWithinBlocks = SYNCED_WITHIN_BLOCKS
    )

    private val _status = MutableStateFlow(DogecoinSpvStatus(network = DogecoinNetwork.TESTNET))
    val status: StateFlow<DogecoinSpvStatus> = _status.asStateFlow()

    init {
        // Rebuild the SPV transport whenever the user's Tor SOCKS endpoint changes: OFF⇄ON (route over Tor vs
        // clearnet) or an Arti bind-retry port bump (9060→9061…). The connection manager is fixed at start()
        // time, so without this the long-lived singleton would either ride clearnet after Tor is enabled or
        // strand itself on a now-dead proxy port. distinctUntilChanged collapses the hundreds of Arti log-line
        // emissions to just real endpoint flips; the decision re-reads live state under the lock so a toggle
        // deferred during a broadcast is re-applied afterwards (see broadcast()'s finally), never lost.
        torScope.launch {
            com.bitchat.android.net.ArtiTorManager.getInstance().statusFlow
                .map { com.bitchat.android.net.ArtiTorManager.getInstance().currentSocksAddress() }
                .distinctUntilChanged()
                .collect { synchronized(lock) { maybeRebuildTransportLocked() } }
        }
    }

    /** Whether SPV is even possible for [network] (REGTEST has no public peers / params). */
    fun isSupported(network: DogecoinNetwork): Boolean = paramsFor(network) != null

    /**
     * Start (or switch to) the SPV client for [network], importing the wallet's existing key. Idempotent
     * for an already-running network. Heavy work (peer connect + header download) runs on bitcoinj threads.
     */
    fun start(network: DogecoinNetwork) {
        startForRequest(network, reserveStartRequest())
    }

    /** Reserve desired-SPV ownership before a caller waits on any outer lifecycle serialization. */
    internal fun reserveStartRequest(): Int = lifecycleRequestGeneration.incrementAndGet()

    /** Execute a previously reserved start only if no newer stop/start request superseded it. */
    internal fun startForRequest(network: DogecoinNetwork, requestGeneration: Int): Boolean =
        synchronized(lock) {
            if (lifecycleRequestGeneration.get() != requestGeneration) return@synchronized false
            val params = paramsFor(network) ?: run {
                Log.i(TAG, "SPV not supported for $network; ignoring start")
                return@synchronized false
            }
            if (activeNetwork == network && peerGroup != null) return@synchronized true // already running
            // Tear down any other-network instance first and immediately project the requested chain as idle.
            // Old PeerGroup callbacks are invalidated before their asynchronous stop can report stale status.
            stopLocked(statusNetworkAfterStop = network)

            val snapshot = repository.loadWalletIfPresent(network) ?: run {
                Log.i(TAG, "No $network wallet key yet; not starting SPV")
                return@synchronized false
            }

            val ctx = org.bitcoinj.core.Context(params)
            org.bitcoinj.core.Context.propagate(ctx)

            // Import the EXISTING on-device key (watch + spend the same P2PKH address). NEVER createDeterministic.
            val key = ECKey.fromPrivate(DogecoinHex.decode(snapshot.key.privateKeyHex), snapshot.key.isCompressed)
            val birthdateSecs = repository.loadSpvBirthdateMillis(network) / 1000L
            key.creationTimeSeconds = birthdateSecs
            // Persisted wallet: the UTXO set survives stop/start. Without it the in-memory wallet is empty on
            // restart and, since the header store has advanced past the funding block, never re-derives balance.
            val w = loadOrCreateSpvWallet(params, network, key)

            val store = SPVBlockStore(params, blockStoreFile(network))
            // Seed the header store near the key birthdate from a shipped checkpoints asset (if present), so
            // a recent key syncs near-instantly instead of from genesis (testnet is ~65.7M blocks).
            maybeLoadCheckpoints(network, params, store, birthdateSecs)

            val chain = BlockChain(params, w, store)

            // Transport decision (mirrors OkHttpProvider's intent-not-readiness policy): if the user has Tor ON,
            // currentSocksAddress() is non-null the instant the mode flips — BEFORE bootstrap completes — so we
            // build a PeerGroup whose every peer socket is a SOCKS5 CONNECT through Arti and a direct clearnet
            // socket is NEVER opened. While Tor is still bootstrapping those connects just retry/back off against
            // the not-yet-ready SOCKS port (fail-closed, no leak); once circuits are up they start succeeding.
            // Tor OFF keeps the default NioClientManager clearnet path byte-for-byte. DnsDiscovery is used in BOTH
            // modes: it resolves seed hostnames to NUMERIC PeerAddresses (a hostname-only PeerAddress would crash
            // PeerAddress.equals, which dereferences the argument's null addr), and under Tor only the resulting
            // peer CONNECTION rides the proxy — the one-time seed DNS lookup stays on the local resolver (disclosed
            // in the UI). bitcoinj still never signs; this changes nothing but the transport.
            val socks = com.bitchat.android.net.ArtiTorManager.getInstance().currentSocksAddress()
            val connMgr = torConnectionManager(socks) // non-null ⇒ Tor; null ⇒ clearnet (the no-silent-fallback decision)
            val pg = if (connMgr != null) {
                HighestHeightDownloadPeerGroup(params, chain, connMgr).also {
                    it.setConnectTimeoutMillis(TOR_CONNECT_TIMEOUT_MILLIS)  // relax the version-handshake deadline too
                }
            } else {
                HighestHeightDownloadPeerGroup(params, chain)
            }
            pg.setUserAgent(USER_AGENT, USER_AGENT_VERSION)
            pg.maxConnections = MAX_PEERS
            pg.addWallet(w)
            pg.setBloomFilteringEnabled(true) // serve our address to peers so they return our merkleblocks
            pg.addPeerDiscovery(DnsDiscovery(params))
            this.builtSocksAddress = socks    // remember the endpoint so the Tor observer rebuilds only on a real change

            val statusOwner = Any()
            pg.addConnectedEventListener { _, _ -> publishStatus(statusOwner, network, chain, pg) }
            pg.addDisconnectedEventListener { _, _ -> publishStatus(statusOwner, network, chain, pg) }

            this.bitcoinjContext = ctx
            this.wallet = w
            this.blockStore = store
            this.blockChain = chain
            this.peerGroup = pg
            this.activeNetwork = network
            statusPublicationGate.activate(statusOwner)
            publishStatus(statusOwner, network, chain, pg)

            pg.startAsync()
            pg.startBlockChainDownload(object : DownloadProgressTracker() {
                override fun progress(pct: Double, blocksSoFar: Int, date: Date?) {
                    publishStatus(statusOwner, network, chain, pg)
                }
                override fun doneDownload() {
                    publishStatus(statusOwner, network, chain, pg)
                }
            })
            Log.i(TAG, "SPV started for $network (birthdate=${Date(birthdateSecs * 1000L)})")

            // bitcoinj keeps one managed download peer. Directly calling startBlockChainDownload() on a later,
            // higher peer bypasses that ownership/filter/listener setup and duplicate-suppresses retries at an
            // unchanged head. Instead, sample the LIVE chain (also repairing stale status), then after a generous
            // no-progress window close only the managed download peer. PeerGroup's normal death path selects the
            // highest remaining peer and reapplies the same connection manager, bloom filter, and listener.
            catchUpJob?.cancel()
            catchUpJob = torScope.launch {
                while (true) {
                    delay(SYNC_CATCHUP_INTERVAL_MS)
                    synchronized(lock) {
                        val livePg = peerGroup ?: return@launch          // stopped → end the coroutine
                        val liveChain = blockChain ?: return@launch
                        if (activeNetwork != network) return@launch       // switched network → end

                        // DownloadProgressTracker follows PeerGroup.downloadPeer only. Read the chain directly so
                        // status cannot remain frozen if another bitcoinj callback advanced the shared chain.
                        publishStatus(statusOwner, network, liveChain, livePg)
                        val st = _status.value

                        val nowMillis = android.os.SystemClock.elapsedRealtime()
                        val recovery = headerProgressWatchdog.observe(
                            nowMillis = nowMillis,
                            running = st.running,
                            peerCount = st.peerCount,
                            chainHeight = st.chainHeight,
                            bestPeerHeight = st.bestPeerHeight
                        )
                        if (st.stalled != headerProgressWatchdog.stalled) {
                            publishStatus(statusOwner, network, liveChain, livePg)
                        }

                        // Continue status/progress maintenance during a broadcast, but never rotate its peers.
                        if (recovery == DogecoinSpvHeaderRecovery.ROTATE_DOWNLOAD_PEER && !broadcasting) {
                            val stuckPeer = livePg.downloadPeer
                            if (stuckPeer == null) {
                                Log.w(TAG, "SPV header sync stalled but PeerGroup has no managed download peer")
                                headerProgressWatchdog.deferRecovery(nowMillis)
                            } else if (!hasHigherDogecoinSpvDownloadPeerReplacement(
                                    downloadPeerHeight = stuckPeer.bestHeight,
                                    bestPeerHeight = st.bestPeerHeight
                                )
                            ) {
                                // Never lower bestPeerHeight through our own recovery: doing so could make the
                                // unchanged synced/broadcast gate see a false zero-behind state. With no strictly
                                // higher live replacement, fail closed and leave the explicit stalled flag set.
                                Log.w(
                                    TAG,
                                    "SPV header sync stalled at ${st.chainHeight}/${st.bestPeerHeight}; " +
                                        "no higher managed-peer replacement, leaving peer connected"
                                )
                                headerProgressWatchdog.deferRecovery(nowMillis)
                            } else {
                                val attempt = headerProgressWatchdog.recoveryAttempts + 1
                                Log.w(
                                    TAG,
                                    "SPV header sync stalled at ${st.chainHeight}/${st.bestPeerHeight}; " +
                                        "rotating managed download peer attempt " +
                                        "$attempt/$MAX_HEADER_STALL_RECOVERIES"
                                )
                                runCatching { stuckPeer.close() }
                                    .onSuccess { headerProgressWatchdog.recordRecoveryAttempt(nowMillis) }
                                    .onFailure {
                                        headerProgressWatchdog.deferRecovery(nowMillis)
                                        Log.w(TAG, "Failed to rotate stalled SPV download peer", it)
                                    }
                            }
                        }
                    }
                }
            }
            true
        }

    /** Stop the SPV client and release resources. Caller must already be off Main. */
    fun stop() {
        val requestGeneration = lifecycleRequestGeneration.incrementAndGet()
        synchronized(lock) {
            if (lifecycleRequestGeneration.get() == requestGeneration) stopLocked()
        }
    }

    /**
     * Request process-owned teardown without making a UI lifecycle callback wait on the bitcoinj monitor.
     * A later [start] supersedes a queued stop; a stop requested after a running start waits and then tears it down.
     * [statusNetworkAfterStop] lets the owning sheet keep the selected-chain identity honest while SPV is idle.
     */
    fun requestStop(statusNetworkAfterStop: DogecoinNetwork? = null) {
        val requestGeneration = lifecycleRequestGeneration.incrementAndGet()
        torScope.launch {
            synchronized(lock) {
                if (lifecycleRequestGeneration.get() == requestGeneration) {
                    stopLocked(statusNetworkAfterStop = statusNetworkAfterStop)
                }
            }
        }
    }

    private fun stopLocked(statusNetworkAfterStop: DogecoinNetwork? = null) {
        val stoppedNetwork = statusNetworkAfterStop ?: activeNetwork
        // Invalidate the live callback owner BEFORE stopAsync(). A disconnect/progress callback from that group
        // may still arrive, but publishIfCurrent rejects it. Publishing the requested next network as idle also
        // keeps console/sheet identity honest while its wallet and chain are being opened.
        statusPublicationGate.deactivate {
            if (stoppedNetwork != null) {
                _status.value = DogecoinSpvStatus(network = stoppedNetwork, running = false)
            }
        }
        catchUpJob?.cancel()
        catchUpJob = null
        headerProgressWatchdog.reset()
        // Final flush + stop the autosave thread so the latest UTXO set is persisted before teardown.
        wallet?.let { w -> runCatching { w.shutdownAutosaveAndWait() } }
        peerGroup?.let { pg ->
            runCatching { pg.stopAsync() }
        }
        // The block store is memory-mapped; closing is best-effort (never on Android does WindowsMMapHack run).
        blockStore?.let { runCatching { it.close() } }
        peerGroup = null
        blockChain = null
        blockStore = null
        wallet = null
        bitcoinjContext = null
        activeNetwork = null
        builtSocksAddress = null
    }

    /**
     * Rebuild the SPV transport if the user's CURRENT Tor SOCKS endpoint no longer matches the one the live
     * PeerGroup was built against (OFF⇄ON or an Arti port bump). MUST be called under [lock]. No-op while SPV
     * is idle (the next [start] picks up the endpoint) or while a broadcast is in flight — [broadcast]'s
     * finally calls this again once the in-flight tx is done, so a toggle that arrived mid-broadcast is applied
     * rather than silently lost (which would strand SPV on clearnet after the user enabled Tor).
     */
    private fun maybeRebuildTransportLocked() {
        val net = activeNetwork ?: return
        if (broadcasting) return
        val socksNow = com.bitchat.android.net.ArtiTorManager.getInstance().currentSocksAddress()
        if (socksNow != builtSocksAddress) {
            Log.i(TAG, "Tor SOCKS endpoint changed ($builtSocksAddress → $socksNow); rebuilding SPV transport for $net")
            // Non-throwing: a transient rebuild failure must NOT (a) unwind out of broadcast()'s finally — that
            // would mask an already-CLAIMED txid and invite a double-spend retry — nor (b) kill the status-flow
            // observer coroutine, which would freeze every future rebuild (clearnet-after-enable hole). On
            // failure SPV is left stopped (fail-closed, never a clearnet leak); the sheet lifecycle or the next
            // endpoint change re-starts it.
            runCatching {
                val requestGeneration = lifecycleRequestGeneration.get()
                stopLocked()
                // An internal transport rebuild must not supersede a later explicit stop request.
                startForRequest(net, requestGeneration)
            }.onFailure { Log.w(TAG, "SPV transport rebuild failed; left stopped (fail-closed)", it) }
        }
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

    /**
     * Positive-only independent veto for DES-1-D. `true` means the SPV wallet actually contains a
     * non-dead peer/chain transaction spending one of the exact outpoints. `false` is never treated as
     * proof of unspentness (the behind/birthdate-limited wallet may simply not know it), and `null`
     * means this network has no active SPV wallet.
     */
    fun hasObservedSpend(
        network: DogecoinNetwork,
        outpoints: List<Pair<String, Int>>
    ): Boolean? = synchronized(lock) {
        val w = wallet?.takeIf { activeNetwork == network } ?: return null
        if (outpoints.isEmpty()) return false
        val exact = outpoints.map { (txid, vout) -> "${txid.lowercase()}:$vout" }.toSet()
        org.bitcoinj.core.Context.propagate(bitcoinjContext)
        w.getTransactions(false).any { transaction ->
            transaction.inputs.any { input ->
                val outpoint = input.outpoint
                "${outpoint.hash.toString().lowercase()}:${outpoint.index}" in exact
            }
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
    fun broadcast(network: DogecoinNetwork, normalizedHex: String, expectedTxid: String, mainnetAuthorized: Boolean = false): String? {
        val prepared = synchronized(lock) {
            // Phase 4: MAINNET is refused UNLESS the caller passes explicit per-spend authorization, set true
            // ONLY by the confirmation-gated console mainnet-send path. The DataSource/UI broadcast path never
            // sets it, so the 4-layer app block stays intact; this is the single deliberate, per-call channel.
            if (network == DogecoinNetwork.MAINNET && !mainnetAuthorized) return null
            val params = paramsFor(network) ?: return null               // REGTEST has no SPV
            val pg = peerGroup?.takeIf { activeNetwork == network } ?: return null
            val w = wallet ?: return null
            val st = _status.value
            if (!st.synced || st.peerCount < minPeersFor(network)) return null  // not caught up / eclipse floor (strict on mainnet)
            org.bitcoinj.core.Context.propagate(bitcoinjContext)
            // Fail closed: bitcoinj must round-trip the signed bytes byte-for-byte AND agree on the txid.
            val tx = DogecoinSpvBroadcastVerifier.verifiedTransaction(params, normalizedHex, expectedTxid)
            // Reserve the spent inputs NOW so reads stop showing them as spendable (anti double-spend).
            // receivePending does not alter the verified serialization, so the wire bytes stay canonical.
            w.receivePending(tx, null)
            pg.minBroadcastConnections = minPeersFor(network)            // avoid single-peer broadcast deadlock (strict on mainnet)
            broadcasting = true                                          // freeze the Tor observer until the await ends
            try {
                pg.broadcastTransaction(tx) to tx                         // sends immediately; returns now
            } catch (t: Throwable) {
                broadcasting = false                                     // never leave the observer frozen if the send itself throws
                throw t
            }
        }
        val (broadcast, tx) = prepared
        // Completion = MIN_PEERS re-announced; timeout = still in flight. Either way the tx is CLAIMED.
        try {
            runCatching {
                broadcast.future().get(BROADCAST_TIMEOUT_SECS, java.util.concurrent.TimeUnit.SECONDS)
            }
        } finally {
            synchronized(lock) {
                broadcasting = false
                maybeRebuildTransportLocked() // apply a Tor toggle that arrived (and was deferred) mid-broadcast
            }
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

    /**
     * READ-ONLY snapshot of every wallet transaction (sent AND received) for the activity / pending UI,
     * newest first. Direction comes from the net value to/from the wallet; INCOMING txs are tracked here
     * automatically because the bloom filter matches our address, so the receiving phone sees an incoming
     * tx's confirmations climb without any extra plumbing. Never touches the money path. Null if SPV isn't
     * the active backend for [network]. A confirmation depth of 0 means pending (in mempool, unmined).
     */
    fun snapshotTransactions(network: DogecoinNetwork): List<DogecoinSpvTx>? = synchronized(lock) {
        val w = wallet?.takeIf { activeNetwork == network } ?: return null
        org.bitcoinj.core.Context.propagate(bitcoinjContext)
        w.getTransactions(false).mapNotNull { tx ->
            val sentToMe = runCatching { tx.getValueSentToMe(w).value }.getOrDefault(0L)
            val sentFromMe = runCatching { tx.getValueSentFromMe(w).value }.getOrDefault(0L)
            val net = sentToMe - sentFromMe                 // >0 received, <0 spent (net of change returning to us)
            val incoming = net >= 0L
            val amount = (if (incoming) net else -net).coerceAtLeast(0L)
            if (amount == 0L) return@mapNotNull null        // self-transfer noise / fee-only artifacts
            val depth = runCatching { tx.confidence?.depthInBlocks ?: 0 }.getOrDefault(0)
            val time = runCatching { tx.updateTime?.time?.div(1000L) }.getOrNull()
            DogecoinSpvTx(
                txid = tx.hashAsString,
                incoming = incoming,
                amountKoinu = amount,
                confirmations = depth.coerceAtLeast(0),
                timeSeconds = time
            )
        }.sortedByDescending { it.timeSeconds ?: Long.MAX_VALUE }   // newest first; just-created (no time) on top
    }

    private fun walletAddress(network: DogecoinNetwork): String =
        repository.loadWalletIfPresent(network)?.key?.address ?: ""

    private fun publishStatus(statusOwner: Any, network: DogecoinNetwork, chain: BlockChain, pg: PeerGroup) {
        // Drop callbacks already known stale before sampling their old PeerGroup. If teardown races this first
        // check, publishIfCurrent still performs the decisive atomic check after the fields are sampled.
        if (!statusPublicationGate.isCurrent(statusOwner)) return
        val height = runCatching { chain.bestChainHeight }.getOrDefault(0)
        val peers = pg.connectedPeers
        val peerCount = peers.size
        val bestPeerHeight = peers.maxOfOrNull { it.bestHeight } ?: 0L
        val headTimeSecs = runCatching { chain.chainHead.header.timeSeconds }.getOrDefault(0L)
        val overTor = builtSocksAddress != null
        val stalled = headerProgressWatchdog.stalled
        // Schmitt trigger on tip-freshness: reach within SYNCED_WITHIN_BLOCKS of the tip to FIRST go synced, then
        // HOLD synced until we fall more than STALE_BEHIND_BLOCKS behind — a fresh block (a block or two of
        // download lag) can no longer flap the flag. The peer floor stays STRICT per network (mainnet eclipse
        // floor unchanged; testnet relaxed). `prevSynced` is scoped to THIS live network so a switch can't carry
        // sticky state. The owner gate serializes this read/write with rebind/stop, so an obsolete callback can
        // neither inherit nor overwrite the replacement chain's state.
        statusPublicationGate.publishIfCurrent(statusOwner) {
            val behind = if (bestPeerHeight > 0L) bestPeerHeight - height else Long.MAX_VALUE
            val previous = _status.value
            val prevSynced = previous.running && previous.synced && previous.network == network
            // Falling-edge hysteresis is NON-MAINNET only: mainnet stays strict on BOTH axes (freshness AND the
            // peer floor), so mainnet "synced"/broadcast-readiness is byte-for-byte unchanged. Testnet holds synced
            // up to STALE_BEHIND_BLOCKS behind to stop the flag flapping as its tip advances ~1 block/30-40s.
            val staleThreshold =
                if (network == DogecoinNetwork.MAINNET) SYNCED_WITHIN_BLOCKS else STALE_BEHIND_BLOCKS
            val freshEnough = behind <= if (prevSynced) staleThreshold else SYNCED_WITHIN_BLOCKS
            val synced = bestPeerHeight > 0L && peerCount >= minPeersFor(network) && freshEnough
            _status.value = DogecoinSpvStatus(
                network = network,
                running = true,
                peerCount = peerCount,
                chainHeight = height,
                bestPeerHeight = bestPeerHeight,
                syncedToDateMillis = headTimeSecs * 1000L,
                synced = synced,
                overTor = overTor,
                stalled = stalled
            )
        }
    }

    private fun maybeLoadCheckpoints(network: DogecoinNetwork, params: NetworkParameters, store: SPVBlockStore, birthdateSecs: Long) {
        // Key the asset off the [network] being started — the authoritative source of truth. (Earlier this
        // read activeNetwork, assigned AFTER this point, or params.id; the latter is the BITCOINJ id and does
        // NOT equal NetworkParameters.ID_MAINNET for libdohj's Dogecoin params, so mainnet silently loaded the
        // testnet checkpoint and seeded the wrong chain.)
        val assetName = "dogecoin-checkpoints-${if (network == DogecoinNetwork.MAINNET) "mainnet" else "testnet"}.txt"
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

    private fun walletFile(network: DogecoinNetwork): File =
        File(appContext.filesDir, "dogecoin-spv-${network.id}.wallet")

    /**
     * Load the persisted bitcoinj wallet for [network] if present and it still holds the current [key];
     * otherwise create a fresh wallet importing [key]. Autosave keeps the UTXO set on disk so balance/UTXOs
     * survive stop/start (sheet close, app background, backend switch) without a full rescan.
     *
     * Loaded via the EXPLICIT-params serializer because bitcoinj's NetworkParameters.fromID (used by the
     * convenience Wallet.loadFromFile) does not recognise the libdohj Dogecoin networks and would throw.
     */
    private fun loadOrCreateSpvWallet(params: NetworkParameters, network: DogecoinNetwork, key: ECKey): Wallet {
        val file = walletFile(network)
        val loaded = if (file.exists()) runCatching {
            file.inputStream().use { ins ->
                val proto = org.bitcoinj.wallet.WalletProtobufSerializer.parseToProto(ins)
                org.bitcoinj.wallet.WalletProtobufSerializer().readWallet(params, null, proto)
            }
        }.getOrNull() else null

        val wallet = if (loaded != null && loaded.findKeyFromPubHash(key.pubKeyHash) != null) {
            Log.i(TAG, "Loaded persisted SPV wallet for $network (UTXO set survives restart)")
            loaded
        } else {
            if (loaded != null) {
                Log.i(TAG, "Persisted SPV wallet does not hold the current key; recreating fresh")
                runCatching { file.delete() }
            }
            Wallet(params).also { it.importKey(key) }
        }
        // Persist on change (Schildbach/Langerhans pattern); shutdownAutosaveAndWait() in stop flushes the last save.
        runCatching { wallet.autosaveToFile(file, 1000L, java.util.concurrent.TimeUnit.MILLISECONDS, null) }
        return wallet
    }

    /**
     * Force a full re-scan: stop and delete BOTH the persisted wallet and the block store for [network]
     * (KEEPS the on-device key). The next [start] reseeds at the birthdate checkpoint and re-derives the
     * UTXO set from the chain. Needed when the wallet was never persisted (legacy state) or to recover from
     * a store/wallet drift. REGTEST/no-op safe.
     */
    fun clearPersistedState(network: DogecoinNetwork) = synchronized(lock) {
        // Destructive reset supersedes every previously reserved sheet start. A genuinely later open may
        // reserve a new generation and rebuild only after these files have been removed.
        lifecycleRequestGeneration.incrementAndGet()
        stopLocked()
        runCatching { blockStoreFile(network).delete() }
        runCatching { walletFile(network).delete() }
        Log.i(TAG, "Cleared SPV store + wallet for $network; next start() will rescan from the checkpoint")
    }

    private fun paramsFor(network: DogecoinNetwork): NetworkParameters? = when (network) {
        DogecoinNetwork.MAINNET -> DogecoinMainNetParams.get()
        DogecoinNetwork.TESTNET -> DogecoinTestNet3Params.get()
        DogecoinNetwork.REGTEST -> null // no public peers / params for regtest SPV
    }

    /**
     * bitcoinj's default download-peer selection prefers a witness-capable peer; Dogecoin has no SegWit, so
     * we pick the highest-height peer that has a chain (mirrors the spike + the langerhans NonWitnessPeerGroup).
     */
    private class HighestHeightDownloadPeerGroup : PeerGroup {
        /** Clearnet path: default NioClientManager. */
        constructor(params: NetworkParameters, chain: BlockChain) : super(params, chain)
        /** Tor path: a caller-supplied SOCKS-routing connection manager owns every peer socket. */
        constructor(params: NetworkParameters, chain: BlockChain, connMgr: ClientConnectionManager) :
            super(params, chain, connMgr)

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

    /**
     * A [javax.net.SocketFactory] that hands bitcoinj UNCONNECTED sockets bound to a SOCKS5 proxy (the embedded
     * Arti Tor at 127.0.0.1:<port>), so every peer socket [org.bitcoinj.net.BlockingClient] opens is a SOCKS5
     * CONNECT through Tor rather than a direct clearnet socket. BlockingClient calls ONLY the no-arg
     * [createSocket] (it connects the returned socket itself with the already-resolved peer address), so that
     * override is load-bearing — the javax base implementation throws. The four address-taking overloads are
     * abstract on the base and must exist; bitcoinj never invokes them here, but they too route via the proxy
     * (and keep any hostname target UNRESOLVED so the proxy, never the local resolver, would resolve it).
     */
    internal class SocksProxySocketFactory(private val host: String, private val port: Int) : javax.net.SocketFactory() {
        private fun proxy() = java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress(host, port))
        override fun createSocket(): java.net.Socket = java.net.Socket(proxy())
        override fun createSocket(h: String, p: Int): java.net.Socket =
            java.net.Socket(proxy()).apply { connect(InetSocketAddress.createUnresolved(h, p)) }
        override fun createSocket(h: String, p: Int, lh: java.net.InetAddress, lp: Int): java.net.Socket =
            createSocket(h, p)
        override fun createSocket(h: java.net.InetAddress, p: Int): java.net.Socket =
            java.net.Socket(proxy()).apply { connect(InetSocketAddress(h, p)) }
        override fun createSocket(h: java.net.InetAddress, p: Int, lh: java.net.InetAddress, lp: Int): java.net.Socket =
            createSocket(h, p)
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
        const val MIN_PEERS = 4 // MAINNET eclipse-resistance floor for "synced"; broadcast needs >=this too
        // Test networks carry no value (no eclipse/double-spend incentive) and a thin testnet rarely sustains 4
        // peers, so requiring MIN_PEERS there needlessly blocks "synced"/sends. Relax the floor for non-mainnet
        // ONLY — mainnet's eclipse floor is untouched. Used identically by the "synced" calc AND the broadcast gate.
        const val MIN_PEERS_TESTNET = 1
        const val SYNCED_WITHIN_BLOCKS = 2L
        // Falling-edge hysteresis (NON-MAINNET only; mainnet stays strict at SYNCED_WITHIN_BLOCKS): once synced,
        // HOLD synced until we fall MORE than this many blocks behind the best peer. Stops the flag flapping as a
        // thin testnet's tip advances ~1 block/30-40s (a block or two of header-download lag on the SAME chain).
        const val STALE_BEHIND_BLOCKS = 6L

        /** Eclipse-resistance peer floor for "synced" and for broadcasting. STRICT on mainnet (real money);
         *  relaxed on testnet/regtest, which carry no value and rarely sustain [MIN_PEERS] peers. */
        fun minPeersFor(network: DogecoinNetwork): Int =
            if (network == DogecoinNetwork.MAINNET) MIN_PEERS else MIN_PEERS_TESTNET
        const val BROADCAST_TIMEOUT_SECS = 25L // best-effort re-announce wait; timeout != failure (Claimed)
        const val SYNC_CATCHUP_INTERVAL_MS = 10_000L // live-chain progress/status sampling interval
        const val HEADER_STALL_TIMEOUT_MS = 90_000L
        const val HEADER_STALL_RECOVERY_COOLDOWN_MS = 90_000L
        const val MAX_HEADER_STALL_RECOVERIES = 2
        const val TOR_CONNECT_TIMEOUT_MILLIS = 60_000 // Tor circuit + peer handshake is slow; BlockingClientManager defaults to 1s

        /**
         * The single no-silent-fallback transport decision, isolated so a unit test can pin it: a non-null Arti
         * SOCKS endpoint ⇒ a SOCKS-routing [BlockingClientManager] (every peer socket rides Tor, a clearnet
         * socket is NEVER opened); null ⇒ null ⇒ the caller falls back to the default clearnet NioClientManager.
         * Keyed on the endpoint being PRESENT (set the instant Tor mode flips ON, before bootstrap), mirroring
         * OkHttpProvider — not on Tor being fully ready — so nothing leaks clearnet during bootstrap.
         */
        internal fun torConnectionManager(socks: InetSocketAddress?): BlockingClientManager? =
            socks?.let {
                BlockingClientManager(SocksProxySocketFactory(it.hostString, it.port)).apply {
                    setConnectTimeoutMillis(TOR_CONNECT_TIMEOUT_MILLIS) // default 1s never finishes a Tor circuit
                }
            }
    }
}
