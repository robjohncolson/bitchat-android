package com.bitchat.android.ui

import android.app.Application
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.features.dogecoin.DogecoinAddress
import com.bitchat.android.features.dogecoin.DogecoinNetwork
import com.bitchat.android.features.dogecoin.DogecoinProtocol
import com.bitchat.android.features.dogecoin.DogecoinWalletRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.protocol.BitchatPacket


import kotlinx.coroutines.launch
import com.bitchat.android.util.NotificationIntervalManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.random.Random
import com.bitchat.android.services.VerificationService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.util.dataFromHexString
import com.bitchat.android.util.hexEncodedString
import java.security.MessageDigest

/**
 * Refactored ChatViewModel - Main coordinator for bitchat functionality
 * Delegates specific responsibilities to specialized managers while maintaining 100% iOS compatibility
 */
class ChatViewModel(
    application: Application,
    initialMeshService: BluetoothMeshService,
    initialUnifiedMeshService: MeshService
) : AndroidViewModel(application), BluetoothMeshDelegate {

    // Made var to support mesh service replacement after panic clear
    var meshService: BluetoothMeshService = initialMeshService
        private set
    private var unifiedMeshService: MeshService = initialUnifiedMeshService
    private val mesh: MeshService
        get() = unifiedMeshService
    private val debugManager by lazy { try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }

    companion object {
        private const val TAG = "ChatViewModel"

        // 3b.1 explorer corroboration poll for a single-helper Claimed broadcast. Bounded so the wallet UI
        // never hangs: a few short attempts (a freshly broadcast tx may take a moment to index) within a
        // total budget well inside the signed-tx validity window.
        private const val EXPLORER_POLL_ATTEMPTS = 3
        private const val EXPLORER_POLL_INTERVAL_MS = 4_000L
        private const val EXPLORER_POLL_TOTAL_BUDGET_MS = 14_000L

        // Bounds for warming up a Noise session with a connected, session-less helper before a peer broadcast,
        // so the relay can ride BLE instead of falling back to Nostr. Returns early once sessions establish;
        // only incurred when such a helper is connected. The BLE handshake can take ~30s on a flaky link, so the
        // send-time window is a compromise (long enough for a near-ready session, short enough not to strand an
        // ONLINE sender from the fast Nostr path), while the PREWARM window (fired in the background when the
        // wallet opens) is long enough to fully complete the handshake before the user sends. A single handshake
        // packet can be dropped, so we RE-INITIATE periodically within the window rather than trying once.
        // See docs/dogecoin-offline-mesh-relay-findings.md.
        private const val PEER_BROADCAST_SESSION_WARMUP_MS = 8_000L
        private const val PEER_BROADCAST_SESSION_PREWARM_MS = 30_000L
        private const val PEER_BROADCAST_SESSION_REINITIATE_MS = 4_000L
    }

    fun sendVoiceNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendVoiceNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    fun sendFileNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendFileNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    fun sendImageNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendImageNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    fun getCurrentNpub(): String? {
        return try {
            NostrIdentityBridge
                .getCurrentNostrIdentity(getApplication())
                ?.npub
        } catch (_: Exception) {
            null
        }
    }

    fun buildMyQRString(nickname: String, npub: String?): String {
        return VerificationService.buildMyQRString(nickname, npub) ?: ""
    }

    // MARK: - State management
    private val state = ChatState(
        scope = viewModelScope,
    )

    // Transfer progress tracking
    private val transferMessageMap = mutableMapOf<String, String>()
    private val messageTransferMap = mutableMapOf<String, String>()

    // Specialized managers
    private val dataManager = DataManager(application.applicationContext)
    private val identityManager by lazy { SecureIdentityStateManager(getApplication()) }
    private val dogecoinWalletRepository by lazy { DogecoinWalletRepository(getApplication()) }
    // On-device SPV light client (read-only, sync-on-demand). Created lazily on first SPV use; stopped in
    // onCleared. Currently driven only via the debug console (doge-spv-*). See docs/dogecoin-spv-integration-plan.md.
    private var dogecoinSpvService: com.bitchat.android.features.dogecoin.DogecoinSpvService? = null
    private fun dogecoinSpv(): com.bitchat.android.features.dogecoin.DogecoinSpvService =
        com.bitchat.android.features.dogecoin.DogecoinSpvService.getInstance(getApplication(), dogecoinWalletRepository)
            .also { dogecoinSpvService = it }

    // --- Milestone 3b: broadcast-over-mesh (node-optional sender + opt-in helper) ---
    private val broadcastHelper by lazy {
        com.bitchat.android.features.dogecoin.BroadcastHelperService.getInstance(getApplication())
    }
    private val paymentBroadcastCoordinator by lazy {
        com.bitchat.android.features.dogecoin.PaymentBroadcastCoordinator(
            listCandidateHelpers = { network -> listBroadcastHelperCandidates(network) },
            sendRequestToPeer = { candidateNoiseKeyHex, payload ->
                // candidateNoiseKeyHex is a canonical Noise key. Prefer the connected mesh peer holding it;
                // otherwise hand MessageRouter the Noise key, which routes it over Nostr (favorites -> npub).
                val target = resolveConnectedMeshPeerId(candidateNoiseKeyHex) ?: candidateNoiseKeyHex
                com.bitchat.android.services.MessageRouter.getInstance(getApplication(), mesh)
                    .sendPaymentBroadcastRequest(payload, target)
            },
            // Only a MUTUAL favorite (a scarce identity the user themselves vetted) may count toward the
            // two-helper "Confirmed". Opt-in/NODE_HELPER advertisers are admitted as relay candidates but are
            // free to mint, so without this a single sybil running two helper identities could fake a settled
            // payment. onResult is fed the helper's canonical Noise-key hex (mesh + Nostr share one id space).
            isScarceHelper = { noiseKeyHex -> isMutualFavoriteNoiseKeyHex(noiseKeyHex) }
        )
    }

    /**
     * True if [noiseKeyHex] (a canonical 32-byte Noise static key, lowercase hex) belongs to a MUTUAL
     * favorite. Used to decide whether a helper's corroboration may count toward a Confirmed peer-broadcast
     * (free-to-mint helper identities must not). Fail-closed: any decode/lookup error => not scarce.
     */
    private fun isMutualFavoriteNoiseKeyHex(noiseKeyHex: String): Boolean = runCatching {
        val bytes = ByteArray(noiseKeyHex.length / 2) {
            noiseKeyHex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
        com.bitchat.android.favorites.FavoritesPersistenceService.shared
            .getFavoriteStatus(bytes)?.isMutual == true
    }.getOrDefault(false)

    /** Debug-only: build + sign a REAL Dogecoin tx for the console money-path commands (suspend; lists UTXOs). */
    private suspend fun debugBuildSignedDogeTx(
        net: DogecoinNetwork, to: String, amount: String, feePerKbKoinu: Long
    ): com.bitchat.android.features.dogecoin.DogecoinSignedTransaction {
        val cfg = dogecoinWalletRepository.loadRpcConfig(net)
        val snap = dogecoinWalletRepository.loadOrCreateWallet(net)
        val rpc = com.bitchat.android.features.dogecoin.DogecoinRpcClient()
        val utxos = rpc.listUnspent(cfg, snap.key.address, net)
        return com.bitchat.android.features.dogecoin.DogecoinTransactionBuilder.createSignedTransaction(
            wallet = snap.key, utxos = utxos, recipientAddress = to, amount = amount,
            network = net, feePerKbKoinu = feePerKbKoinu,
            minimumOutputKoinu = com.bitchat.android.features.dogecoin.DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU
        )
    }

    /** Debug-only: explorer client built from the saved provider + API key (see the doge-explorer-config command). */
    private fun debugExplorerClient() = com.bitchat.android.features.dogecoin.DogecoinExplorerClient(
        provider = dogecoinWalletRepository.loadExplorerProvider(),
        apiKey = dogecoinWalletRepository.loadExplorerApiKey()
    )

    /**
     * Debug-only adb console host. Registered in [init] when BuildConfig.DEBUG and released in [onCleared].
     * Drive it from a host machine:
     *   adb shell am broadcast -n com.bitchat.droid.debug/com.bitchat.android.debug.DebugCommandReceiver --es cmd "candidates"
     * Output lands in logcat under tag "DbgConsole". Money-path commands (broadcast-test) honor the
     * selected network's existing guards and use a dummy tx that helpers reject — they exercise dispatch only.
     */
    private val debugConsoleHost = object : com.bitchat.android.debug.DebugConsole.Host {
        override fun handle(cmd: String, args: List<String>): String = when (cmd) {
            "help" -> "cmds: help myid favorites candidates cansend forcemutual sendfav broadcast-test nostr tor | " +
                "doge-network <net> | doge-rpc-set <url> [user] [pass] [wallet] | doge-rpc-show | doge-address | " +
                "doge-import-wif <wif> | doge-balance | doge-self-broadcast <addr> <amt> [feeKb] | " +
                "doge-peer-broadcast <addr> <amt> [feeKb] | doge-helper-enable <0|1> | " +
                "doge-reset | doge-reset-mainnet <currentAddr> | doge-spv-start | doge-spv-stop | doge-spv-rescan | doge-spv-status | doge-spv-balance | doge-spv-unspents | doge-spv-crosscheck | doge-spv-broadcast <addr> <amt> [feeKb] | doge-spv-mainnet-send <addr> <amt> <DRYRUN|CONFIRM> [feeKb] | doge-spv-peer-broadcast <addr> <amt> [feeKb] | " +
                "doge-explorer-config <blockbook|blockchair> [apiKey] | doge-explorer-balance [addr] | doge-explorer-utxos [addr] | doge-explorer-broadcast <rawHex> | doge-explorer-send <addr> <amt> [feeKb] | " +
                "peers | reannounce | tor-set <on|off> | nostr-connect | nostr-disconnect"
            "myid" -> "myPeerID=${mesh.myPeerID} net=${currentDogecoinNetwork().id} connectedPeers=${state.getConnectedPeersValue().size}"
            "favorites" -> {
                val all = com.bitchat.android.favorites.FavoritesPersistenceService.shared.debugAllRelationships()
                buildString {
                    appendLine("favorites count=${all.size}")
                    all.forEach { r ->
                        val nk = r.peerNoisePublicKey.joinToString("") { "%02x".format(it) }
                        appendLine("  noise=$nk nick='${r.peerNickname}' fav=${r.isFavorite} theyFav=${r.theyFavoritedUs} mutual=${r.isMutual} npub=${r.peerNostrPublicKey ?: "null"}")
                    }
                }
            }
            "candidates" -> {
                val net = currentDogecoinNetwork()
                val list = listBroadcastHelperCandidates(net)
                buildString {
                    appendLine("candidates net=${net.id} count=${list.size}")
                    list.forEachIndexed { i, c -> appendLine("  [$i] ${c.take(20)} len=${c.length}") }
                }
            }
            "cansend" -> {
                val hex = args.firstOrNull()
                if (hex == null) "usage: cansend <noiseHex>" else {
                    val fav = runCatching {
                        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(bytes)
                    }.getOrNull()
                    "cansend hex=${hex.take(16)} len=${hex.length} favFound=${fav != null} mutual=${fav?.isMutual} npubPresent=${fav?.peerNostrPublicKey != null}"
                }
            }
            "forcemutual" -> {
                // Debug-only: directly set theyFavoritedUs (matched by Noise-key prefix) WITHOUT the wire path,
                // e.g. to reset a relationship to non-mutual before testing the real [FAVORITED] propagation.
                val prefix = args.firstOrNull()?.lowercase()
                val flag = args.getOrNull(1)?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: true
                if (prefix == null) "usage: forcemutual <noiseHexPrefix> [0|1]" else {
                    val svc = com.bitchat.android.favorites.FavoritesPersistenceService.shared
                    val rel = svc.debugAllRelationships().firstOrNull {
                        it.peerNoisePublicKey.joinToString("") { b -> "%02x".format(b) }.startsWith(prefix)
                    }
                    if (rel == null) "forcemutual: no relationship matching '$prefix'" else {
                        svc.updatePeerFavoritedUs(rel.peerNoisePublicKey, flag)
                        val after = svc.getFavoriteStatus(rel.peerNoisePublicKey)
                        "forcemutual set theyFav=$flag for '${rel.peerNickname}'; now mutual=${after?.isMutual}"
                    }
                }
            }
            "sendfav" -> {
                // Debug-only: send a REAL [FAVORITED]/[UNFAVORITED] to a stored peer (matched by Noise-key
                // prefix) over the live wire (mesh if connected, else Nostr) — exercises the actual
                // favorite-notification propagation + receive path under test.
                val prefix = args.firstOrNull()?.lowercase()
                val flag = args.getOrNull(1)?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: true
                if (prefix == null) "usage: sendfav <noiseHexPrefix> [0|1]" else {
                    val svc = com.bitchat.android.favorites.FavoritesPersistenceService.shared
                    val rel = svc.debugAllRelationships().firstOrNull {
                        it.peerNoisePublicKey.joinToString("") { b -> "%02x".format(b) }.startsWith(prefix)
                    }
                    if (rel == null) "sendfav: no relationship matching '$prefix'" else {
                        val noiseHex = rel.peerNoisePublicKey.joinToString("") { b -> "%02x".format(b) }
                        com.bitchat.android.services.MessageRouter.getInstance(getApplication(), mesh)
                            .sendFavoriteNotification(noiseHex, flag)
                        "sendfav ${if (flag) "[FAVORITED]" else "[UNFAVORITED]"} -> '${rel.peerNickname}' ${noiseHex.take(16)} (mesh if connected, else Nostr)"
                    }
                }
            }
            "broadcast-test" -> {
                val net = currentDogecoinNetwork()
                viewModelScope.launch {
                    android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "broadcast-test start net=${net.id} (dummy tx; helpers reject — exercises dispatch only)")
                    val outcome = runCatching {
                        paymentBroadcastCoordinator.broadcast("00".repeat(120), "11".repeat(32), net)
                    }.fold({ it.toString() }, { "EX ${it.message}" })
                    android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "broadcast-test outcome=$outcome")
                }
                "broadcast-test launched on net=${net.id} (watch DbgConsole)"
            }
            "nostr" -> {
                val relays = com.bitchat.android.nostr.NostrRelayManager.shared.relays.value
                "nostr " + relays.joinToString(" ") { "${it.url.substringAfter("://")}=${if (it.isConnected) "UP" else "down"}" }
            }
            "tor" -> {
                val s = com.bitchat.android.net.ArtiTorManager.getInstance().statusFlow.value
                "tor mode=${s.mode} running=${s.running} bootstrap=${s.bootstrapPercent}% state=${s.state}"
            }
            // ---- Dogecoin wallet (P0: drive a send / peer-broadcast from the console) ----
            "doge-network" -> {
                val net = DogecoinNetwork.values().firstOrNull { it.id == args.firstOrNull()?.lowercase() }
                if (net == null) "usage: doge-network mainnet|testnet|regtest" else {
                    dogecoinWalletRepository.saveSelectedNetwork(net)
                    "net=${dogecoinWalletRepository.loadSelectedNetwork().id}"
                }
            }
            "doge-rpc-set" -> {
                if (args.isEmpty()) "usage: doge-rpc-set <url> [user] [pass] [walletName]" else {
                    val net = currentDogecoinNetwork()
                    val cfg = com.bitchat.android.features.dogecoin.DogecoinRpcConfig(
                        url = args.getOrElse(0) { "" }, username = args.getOrElse(1) { "" },
                        password = args.getOrElse(2) { "" }, walletName = args.getOrElse(3) { "" })
                    dogecoinWalletRepository.saveRpcConfig(net, cfg)
                    "saved rpc net=${net.id} url=${cfg.url} wallet=${cfg.walletName}"
                }
            }
            "doge-rpc-show" -> {
                val net = currentDogecoinNetwork()
                val cfg = dogecoinWalletRepository.loadRpcConfig(net)
                viewModelScope.launch {
                    runCatching {
                        val s = com.bitchat.android.features.dogecoin.DogecoinRpcClient().getBlockchainStatus(cfg, net)
                        android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-rpc-show ready=${s.isReadyFor(net)} canBroadcast=${s.canBroadcastFor(net)}")
                    }.getOrElse { android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-rpc-show node ERR ${it.message}") }
                }
                "net=${net.id} url=${cfg.url} user=${cfg.username} wallet=${cfg.walletName} (node status -> DbgConsole)"
            }
            "doge-address" -> {
                val net = currentDogecoinNetwork()
                "addr=${dogecoinWalletRepository.loadOrCreateWallet(net).key.address} net=${net.id}"
            }
            "doge-import-wif" -> {
                val wif = args.firstOrNull()
                val net = currentDogecoinNetwork()
                when {
                    wif == null -> "usage: doge-import-wif <wif>"
                    net == DogecoinNetwork.MAINNET -> "refused: mainnet key import is console-blocked"
                    else -> runCatching {
                        val snap = dogecoinWalletRepository.importWalletFromWif(net, wif)
                        "imported addr=${snap.key.address} net=${net.id}"   // never logs the WIF
                    }.getOrElse { "import ERR ${it.message}" }
                }
            }
            "doge-balance" -> {
                val net = currentDogecoinNetwork()
                val cfg = dogecoinWalletRepository.loadRpcConfig(net)
                val addr = dogecoinWalletRepository.loadOrCreateWallet(net).key.address
                viewModelScope.launch {
                    runCatching {
                        val bal = com.bitchat.android.features.dogecoin.DogecoinRpcClient().getWalletBalance(cfg, addr, net)
                        android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-balance conf=${bal.confirmedKoinu} uncon=${bal.unconfirmedKoinu} utxos=${bal.utxoCount}")
                    }.getOrElse { android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-balance ERR ${it.message}") }
                }
                "balance for ${addr.take(12)} net=${net.id} -> DbgConsole"
            }
            "doge-reset" -> {
                val net = currentDogecoinNetwork()
                if (net == DogecoinNetwork.MAINNET) "refused: mainnet wallet reset is console-blocked (use doge-reset-mainnet <currentAddress> to confirm)"
                else {
                    dogecoinSpv().clearPersistedState(net)   // stop + delete SPV store + stale wallet file
                    val snap = dogecoinWalletRepository.resetWallet(net)
                    "wallet reset net=${net.id} new addr=${snap.key.address} (fresh birthdate=now; run doge-spv-start)"
                }
            }
            "doge-reset-mainnet" -> {
                // Acknowledgment-gated mainnet key regeneration: the Phase 4 read-only soak needs a FRESH
                // mainnet key (birthdate=now) so SPV seeds at the recent checkpoint instead of genesis. This
                // DISCARDS the current mainnet key (irreversible — any funds at it become inaccessible from
                // this app), so it requires the caller to pass the EXACT current mainnet address as
                // confirmation. Debug-console only; never signs, exports a key, or broadcasts.
                val net = currentDogecoinNetwork()
                val confirmAddr = args.getOrNull(0)?.trim()
                val tag = com.bitchat.android.debug.DebugConsole.TAG
                if (net != DogecoinNetwork.MAINNET) "refused: not on mainnet (use doge-reset for ${net.id})"
                else {
                    val cur = dogecoinWalletRepository.loadOrCreateWallet(net).key.address
                    when (confirmAddr) {
                        null -> "DANGER: discards the current mainnet key (irreversible; funds at it become inaccessible from this app). Current mainnet address = $cur — re-run: doge-reset-mainnet $cur"
                        cur -> {
                            android.util.Log.w(tag, "doge-reset-mainnet: DISCARDING the current mainnet key and generating a fresh one (confirmed)")
                            dogecoinSpv().clearPersistedState(net)
                            val snap = dogecoinWalletRepository.resetWallet(net)
                            "MAINNET wallet reset: new addr=${snap.key.address} (fresh birthdate=now). Fund THIS address, then doge-spv-start."
                        }
                        else -> "refused: confirmation address did not match the current mainnet address ($cur)"
                    }
                }
            }
            "doge-spv-start" -> {
                val net = currentDogecoinNetwork()
                if (!dogecoinSpv().isSupported(net)) "spv-start refused: not supported for ${net.id} (regtest has no peers)"
                else {
                    val addr = dogecoinWalletRepository.loadOrCreateWallet(net).key.address
                    dogecoinSpv().start(net)
                    "spv starting net=${net.id} watch=${addr.take(12)} (sync-on-demand) -> doge-spv-status"
                }
            }
            "doge-spv-stop" -> { dogecoinSpv().stop(); "spv stopped" }
            "doge-spv-rescan" -> {
                val net = currentDogecoinNetwork()
                dogecoinSpv().clearPersistedState(net)
                "spv state cleared for ${net.id} (keeps key); run doge-spv-start to rescan from the birthdate checkpoint"
            }
            "doge-spv-status" -> {
                val s = dogecoinSpv().status.value
                "spv net=${s.network.id} running=${s.running} synced=${s.synced} overTor=${s.overTor} height=${s.chainHeight} " +
                    "peers=${s.peerCount} bestPeer=${s.bestPeerHeight} behind=${s.blocksBehind}"
            }
            "doge-spv-balance" -> {
                val net = currentDogecoinNetwork()
                val addr = dogecoinWalletRepository.loadOrCreateWallet(net).key.address
                viewModelScope.launch {
                    runCatching {
                        val bal = dogecoinSpv().snapshotBalance(net)
                            ?: error("spv not active for ${net.id} (run doge-spv-start first)")
                        android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-spv-balance avail=${bal.confirmedKoinu} uncon=${bal.unconfirmedKoinu} utxos=${bal.utxoCount}")
                    }.getOrElse { android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-spv-balance ERR ${it.message}") }
                }
                "spv balance for ${addr.take(12)} net=${net.id} -> DbgConsole"
            }
            "doge-spv-unspents" -> {
                val net = currentDogecoinNetwork()
                viewModelScope.launch {
                    runCatching {
                        val utxos = dogecoinSpv().snapshotUnspents(net)
                            ?: error("spv not active for ${net.id} (run doge-spv-start first)")
                        android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-spv-unspents count=${utxos.size} total=${utxos.sumOf { it.amountKoinu }}k")
                        utxos.take(5).forEachIndexed { i, u -> android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "  [$i] ${u.txid}:${u.vout} ${u.amountKoinu}k conf=${u.confirmations}") }
                    }.getOrElse { android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-spv-unspents ERR ${it.message}") }
                }
                "spv unspents net=${net.id} -> DbgConsole"
            }
            "doge-spv-crosscheck" -> {
                // Phase 4 soak surface: validate every SPV-spendable UTXO against the node's UTXO set
                // (gettxout) — the safety-critical direction (no phantom/spent UTXO is treated as spendable).
                // Read-only; touches no money path.
                val net = currentDogecoinNetwork()
                val addr = dogecoinWalletRepository.loadOrCreateWallet(net).key.address
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val tag = com.bitchat.android.debug.DebugConsole.TAG
                    runCatching {
                        val spvUtxos = dogecoinSpv().snapshotUnspents(net)
                            ?: error("spv not active for ${net.id} (run doge-spv-start first)")
                        if (spvUtxos.isEmpty()) {
                            android.util.Log.d(tag, "doge-spv-crosscheck net=${net.id}: SPV has 0 UTXOs (nothing to check)")
                        } else {
                            val rpcConfig = dogecoinWalletRepository.loadRpcConfig(net)
                            val rpc = com.bitchat.android.features.dogecoin.DogecoinRpcClient()
                            val oracle = HashMap<String, com.bitchat.android.features.dogecoin.DogecoinTxOut?>()
                            spvUtxos.forEach { u ->
                                oracle[com.bitchat.android.features.dogecoin.DogecoinSpvCrossCheck.outpoint(u.txid, u.vout)] =
                                    runCatching { rpc.getTxOut(rpcConfig, u.txid, u.vout, net) }
                                        .getOrElse { android.util.Log.d(tag, "  gettxout ERR ${u.txid.take(12)}:${u.vout} ${it.message}"); null }
                            }
                            val report = com.bitchat.android.features.dogecoin.DogecoinSpvCrossCheck.compare(spvUtxos, oracle)
                            android.util.Log.d(tag, "doge-spv-crosscheck net=${net.id} addr=${addr.take(12)} spvUtxos=${spvUtxos.size} " +
                                "spvTotal=${report.spvTotalKoinu}k nodeConfirmed=${report.nodeConfirmedKoinu}k result=${if (report.allMatch) "PASS" else "FAIL"}")
                            report.mismatches.take(10).forEach { e ->
                                android.util.Log.d(tag, "  MISMATCH ${e.txid.take(16)}:${e.vout} ${e.status} spv=${e.spvKoinu}k node=${e.oracleKoinu}k conf=${e.oracleConfirmations}")
                            }
                        }
                    }.getOrElse { android.util.Log.d(tag, "doge-spv-crosscheck ERR ${it.message}") }
                }
                "spv-vs-node cross-check net=${net.id} -> DbgConsole (read-only)"
            }
            "doge-spv-broadcast" -> {
                val to = args.getOrNull(0); val amt = args.getOrNull(1)
                val feeKb = args.getOrNull(2)?.toLongOrNull() ?: com.bitchat.android.features.dogecoin.DogecoinProtocol.DEFAULT_FEE_PER_KB_KOINU
                val net = currentDogecoinNetwork()
                when {
                    to == null || amt == null -> "usage: doge-spv-broadcast <addr> <amountDoge> [feePerKbKoinu]"
                    net == DogecoinNetwork.MAINNET -> "refused: mainnet broadcast is console-blocked"
                    else -> {
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                val snap = dogecoinWalletRepository.loadOrCreateWallet(net)
                                // Node-free: build + sign from the SPV UTXO view, then relay via SPV peers.
                                val utxos = dogecoinSpv().snapshotUnspents(net)
                                    ?: error("spv not active for ${net.id} (run doge-spv-start first)")
                                val signed = com.bitchat.android.features.dogecoin.DogecoinTransactionBuilder.createSignedTransaction(
                                    wallet = snap.key, utxos = utxos, recipientAddress = to, amount = amt,
                                    network = net, feePerKbKoinu = feeKb,
                                    minimumOutputKoinu = com.bitchat.android.features.dogecoin.DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU
                                )
                                android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-spv-broadcast signed txid=${signed.txid} fee=${signed.feeKoinu} change=${signed.changeKoinu}")
                                val txid = com.bitchat.android.features.dogecoin.DogecoinSpvDataSource(dogecoinSpv())
                                    .broadcast(signed.rawTransactionHex, net)
                                android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-spv-broadcast CLAIMED txid=$txid (poll doge-spv-status for depth)")
                            }.getOrElse { android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-spv-broadcast ERR ${it.message}") }
                        }
                        "spv-broadcast launched net=${net.id} (on-device SPV peers; Claimed only) -> DbgConsole"
                    }
                }
            }
            "doge-spv-mainnet-send" -> {
                // Phase 4: the ONE deliberate, confirmation-gated MAINNET SPV broadcast channel (irreversible
                // real money). Build+sign stays on-device (DogecoinTransactionBuilder); this calls the service
                // broadcast DIRECTLY with mainnetAuthorized=true (the DataSource/UI path stays mainnet-blocked).
                // DRYRUN builds + shows the tx (txid/fee/change) WITHOUT broadcasting; CONFIRM broadcasts.
                val to = args.getOrNull(0)
                val amt = args.getOrNull(1)
                val mode = args.getOrNull(2)?.uppercase()
                val feeKb = args.getOrNull(3)?.toLongOrNull() ?: com.bitchat.android.features.dogecoin.DogecoinProtocol.DEFAULT_FEE_PER_KB_KOINU
                val net = currentDogecoinNetwork()
                val tag = com.bitchat.android.debug.DebugConsole.TAG
                when {
                    net != DogecoinNetwork.MAINNET -> "refused: doge-spv-mainnet-send is mainnet-only (use doge-spv-broadcast on ${net.id})"
                    to == null || amt == null || (mode != "DRYRUN" && mode != "CONFIRM") ->
                        "usage: doge-spv-mainnet-send <addr> <amountDoge> <DRYRUN|CONFIRM> [feeKb] — DRYRUN shows the built tx, CONFIRM broadcasts (IRREVERSIBLE real money)"
                    else -> {
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                val snap = dogecoinWalletRepository.loadOrCreateWallet(net)
                                val utxos = dogecoinSpv().snapshotUnspents(net) ?: error("spv not active (run doge-spv-start)")
                                val signed = com.bitchat.android.features.dogecoin.DogecoinTransactionBuilder.createSignedTransaction(
                                    wallet = snap.key, utxos = utxos, recipientAddress = to, amount = amt,
                                    network = net, feePerKbKoinu = feeKb,
                                    minimumOutputKoinu = com.bitchat.android.features.dogecoin.DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU
                                )
                                android.util.Log.d(tag, "doge-spv-mainnet-send[$mode] BUILT txid=${signed.txid} send=${amt} to=${to} fee=${signed.feeKoinu}k change=${signed.changeKoinu}k")
                                if (mode == "CONFIRM") {
                                    val normalized = com.bitchat.android.features.dogecoin.DogecoinRawTxValidator.normalize(signed.rawTransactionHex)
                                    val expectedTxid = com.bitchat.android.features.dogecoin.DogecoinTransactionBuilder.transactionId(normalized)
                                    val txid = dogecoinSpv().broadcast(net, normalized, expectedTxid, mainnetAuthorized = true)
                                        ?: error("broadcast returned null (not synced / below peer floor)")
                                    android.util.Log.d(tag, "doge-spv-mainnet-send BROADCAST CLAIMED txid=$txid (poll doge-spv-status for depth)")
                                } else {
                                    android.util.Log.d(tag, "doge-spv-mainnet-send DRYRUN only — NOT broadcast. Re-run with CONFIRM to send.")
                                }
                            }.getOrElse { android.util.Log.d(tag, "doge-spv-mainnet-send ERR ${it.message}") }
                        }
                        "MAINNET spv-send[$mode] ${amt} -> ${to} launched -> DbgConsole${if (mode == "CONFIRM") " (IRREVERSIBLE)" else " (dry-run)"}"
                    }
                }
            }
            "doge-self-broadcast" -> {
                val to = args.getOrNull(0); val amt = args.getOrNull(1)
                val feeKb = args.getOrNull(2)?.toLongOrNull() ?: com.bitchat.android.features.dogecoin.DogecoinProtocol.DEFAULT_FEE_PER_KB_KOINU
                val net = currentDogecoinNetwork()
                when {
                    to == null || amt == null -> "usage: doge-self-broadcast <addr> <amountDoge> [feePerKbKoinu]"
                    net == DogecoinNetwork.MAINNET -> "refused: mainnet broadcast is console-blocked"
                    else -> {
                        viewModelScope.launch {
                            runCatching {
                                val cfg = dogecoinWalletRepository.loadRpcConfig(net)
                                val signed = debugBuildSignedDogeTx(net, to, amt, feeKb)
                                android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-self-broadcast signed txid=${signed.txid} fee=${signed.feeKoinu} change=${signed.changeKoinu}")
                                val txid = com.bitchat.android.features.dogecoin.DogecoinRpcClient().sendRawTransaction(cfg, signed.rawTransactionHex, net)
                                android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-self-broadcast BROADCAST OK txid=$txid")
                            }.getOrElse { android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-self-broadcast ERR ${it.message}") }
                        }
                        "self-broadcast launched net=${net.id} -> DbgConsole"
                    }
                }
            }
            "doge-peer-broadcast" -> {
                val to = args.getOrNull(0); val amt = args.getOrNull(1)
                val feeKb = args.getOrNull(2)?.toLongOrNull() ?: com.bitchat.android.features.dogecoin.DogecoinProtocol.DEFAULT_FEE_PER_KB_KOINU
                val net = currentDogecoinNetwork()
                when {
                    to == null || amt == null -> "usage: doge-peer-broadcast <addr> <amountDoge> [feePerKbKoinu]"
                    net == DogecoinNetwork.MAINNET -> "refused: mainnet broadcast is console-blocked"
                    else -> {
                        viewModelScope.launch {
                            val signed = runCatching { debugBuildSignedDogeTx(net, to, amt, feeKb) }
                                .getOrElse { android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-peer-broadcast build ERR ${it.message}"); return@launch }
                            android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-peer-broadcast signed txid=${signed.txid} fee=${signed.feeKoinu} -> requestPeerBroadcast")
                            requestPeerBroadcast(signed)
                            var waited = 0
                            while (waited < 95000) {
                                val st = peerBroadcastState.value
                                if (st is com.bitchat.android.features.dogecoin.PeerBroadcastUiState.Confirmed ||
                                    st is com.bitchat.android.features.dogecoin.PeerBroadcastUiState.Claimed ||
                                    st is com.bitchat.android.features.dogecoin.PeerBroadcastUiState.Failed) {
                                    android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-peer-broadcast TERMINAL=$st"); break
                                }
                                kotlinx.coroutines.delay(500); waited += 500
                            }
                        }
                        "peer-broadcast launched net=${net.id} (signs real tx -> requestPeerBroadcast) -> DbgConsole"
                    }
                }
            }
            "doge-spv-peer-broadcast" -> {
                val to = args.getOrNull(0); val amt = args.getOrNull(1)
                val feeKb = args.getOrNull(2)?.toLongOrNull() ?: com.bitchat.android.features.dogecoin.DogecoinProtocol.DEFAULT_FEE_PER_KB_KOINU
                val net = currentDogecoinNetwork()
                when {
                    to == null || amt == null -> "usage: doge-spv-peer-broadcast <addr> <amountDoge> [feePerKbKoinu]"
                    net == DogecoinNetwork.MAINNET -> "refused: mainnet broadcast is console-blocked"
                    else -> {
                        viewModelScope.launch {
                            val signed = runCatching {
                                // OFFLINE build: sign from the PERSISTED SPV UTXO set (no internet needed on this
                                // phone); only the mesh helper needs connectivity to actually broadcast.
                                val snap = dogecoinWalletRepository.loadOrCreateWallet(net)
                                val utxos = dogecoinSpv().snapshotUnspents(net) ?: error("spv not active (run doge-spv-start)")
                                com.bitchat.android.features.dogecoin.DogecoinTransactionBuilder.createSignedTransaction(
                                    wallet = snap.key, utxos = utxos, recipientAddress = to, amount = amt,
                                    network = net, feePerKbKoinu = feeKb,
                                    minimumOutputKoinu = com.bitchat.android.features.dogecoin.DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU)
                            }.getOrElse { android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-spv-peer-broadcast build ERR ${it.message}"); return@launch }
                            android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-spv-peer-broadcast signed txid=${signed.txid} fee=${signed.feeKoinu} (offline SPV build) -> requestPeerBroadcast")
                            requestPeerBroadcast(signed)
                            var waited = 0
                            while (waited < 95000) {
                                val st = peerBroadcastState.value
                                if (st is com.bitchat.android.features.dogecoin.PeerBroadcastUiState.Confirmed ||
                                    st is com.bitchat.android.features.dogecoin.PeerBroadcastUiState.Claimed ||
                                    st is com.bitchat.android.features.dogecoin.PeerBroadcastUiState.Failed) {
                                    android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-spv-peer-broadcast TERMINAL=$st"); break
                                }
                                kotlinx.coroutines.delay(500); waited += 500
                            }
                        }
                        "spv-peer-broadcast launched net=${net.id} (offline SPV-built, relayed over mesh) -> DbgConsole"
                    }
                }
            }
            "doge-helper-enable" -> {
                val on = args.firstOrNull()?.let { it == "1" || it.equals("true", ignoreCase = true) }
                if (on == null) "usage: doge-helper-enable <0|1>" else {
                    val net = currentDogecoinNetwork()
                    dogecoinWalletRepository.saveHelperEnabled(net, on)
                    reannounceIdentity()
                    "helper-enable=$on net=${net.id} (re-announced)"
                }
            }
            // ---- explorer-backed "no-node" mode (public block explorer; Blockbook keyless default) ----
            "doge-explorer-config" -> {
                val p = when (args.firstOrNull()?.lowercase()) {
                    "blockbook" -> com.bitchat.android.features.dogecoin.DogecoinExplorerProvider.BLOCKBOOK
                    "blockchair" -> com.bitchat.android.features.dogecoin.DogecoinExplorerProvider.BLOCKCHAIR
                    else -> null
                }
                if (p == null) "usage: doge-explorer-config <blockbook|blockchair> [apiKey]" else {
                    dogecoinWalletRepository.saveExplorerProvider(p)
                    args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { dogecoinWalletRepository.saveExplorerApiKey(it) }
                    "explorer provider=$p keySet=${dogecoinWalletRepository.loadExplorerApiKey() != null}"  // key never echoed
                }
            }
            "doge-explorer-balance" -> {
                val net = currentDogecoinNetwork()
                val addr = args.firstOrNull() ?: dogecoinWalletRepository.loadOrCreateWallet(net).key.address
                viewModelScope.launch {
                    runCatching {
                        val bal = debugExplorerClient().getBalance(addr, net)
                        android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-explorer-balance conf=${bal.confirmedKoinu} uncon=${bal.unconfirmedKoinu} utxos=${bal.utxoCount}")
                    }.getOrElse { android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-explorer-balance ERR ${it.message}") }
                }
                "explorer-balance ${addr.take(12)} net=${net.id} -> DbgConsole"
            }
            "doge-explorer-utxos" -> {
                val net = currentDogecoinNetwork()
                val addr = args.firstOrNull() ?: dogecoinWalletRepository.loadOrCreateWallet(net).key.address
                viewModelScope.launch {
                    runCatching {
                        val utxos = debugExplorerClient().listUtxos(addr, net)
                        android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-explorer-utxos count=${utxos.size} total=${utxos.sumOf { it.amountKoinu }}k")
                        utxos.take(5).forEachIndexed { i, u -> android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "  [$i] ${u.txid}:${u.vout} ${u.amountKoinu}k conf=${u.confirmations}") }
                    }.getOrElse { android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-explorer-utxos ERR ${it.message}") }
                }
                "explorer-utxos ${addr.take(12)} net=${net.id} -> DbgConsole"
            }
            "doge-explorer-broadcast" -> {
                val raw = args.firstOrNull()
                val net = currentDogecoinNetwork()
                when {
                    raw == null -> "usage: doge-explorer-broadcast <rawTxHex>"
                    net == DogecoinNetwork.MAINNET -> "refused: mainnet broadcast is console-blocked"
                    else -> {
                        viewModelScope.launch {
                            runCatching {
                                val txid = debugExplorerClient().broadcast(raw, net)
                                android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-explorer-broadcast OK txid=$txid")
                            }.getOrElse { android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-explorer-broadcast ERR ${it.message}") }
                        }
                        "explorer-broadcast launched net=${net.id} -> DbgConsole"
                    }
                }
            }
            "doge-explorer-send" -> {
                val to = args.getOrNull(0); val amt = args.getOrNull(1)
                val feeKb = args.getOrNull(2)?.toLongOrNull() ?: com.bitchat.android.features.dogecoin.DogecoinProtocol.DEFAULT_FEE_PER_KB_KOINU
                val net = currentDogecoinNetwork()
                when {
                    to == null || amt == null -> "usage: doge-explorer-send <addr> <amountDoge> [feePerKbKoinu]"
                    net == DogecoinNetwork.MAINNET -> "refused: mainnet broadcast is console-blocked"
                    else -> {
                        viewModelScope.launch {
                            runCatching {
                                val explorer = debugExplorerClient()
                                val snap = dogecoinWalletRepository.loadOrCreateWallet(net)
                                val utxos = explorer.listUtxos(snap.key.address, net)
                                val signed = com.bitchat.android.features.dogecoin.DogecoinTransactionBuilder.createSignedTransaction(
                                    wallet = snap.key, utxos = utxos, recipientAddress = to, amount = amt,
                                    network = net, feePerKbKoinu = feeKb,
                                    minimumOutputKoinu = com.bitchat.android.features.dogecoin.DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU)
                                android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-explorer-send signed txid=${signed.txid} fee=${signed.feeKoinu} utxos=${utxos.size}")
                                val txid = explorer.broadcast(signed.rawTransactionHex, net)
                                android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-explorer-send BROADCAST OK txid=$txid")
                            }.getOrElse { android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-explorer-send ERR ${it.message}") }
                        }
                        "explorer-send launched net=${net.id} (no-node: explorer UTXOs + explorer broadcast) -> DbgConsole"
                    }
                }
            }
            // ---- mesh / connectivity ----
            "peers" -> {
                val nicks = mesh.getPeerNicknames()
                val conn = state.getConnectedPeersValue()
                buildString {
                    appendLine("peers connected=${conn.size}")
                    conn.forEach { pid -> appendLine("  $pid nick='${nicks[pid] ?: "?"}' session=${mesh.hasEstablishedSession(pid)}") }
                }
            }
            "reannounce" -> { reannounceIdentity(); "re-announced identity" }
            "tor-set" -> {
                val mode = when (args.firstOrNull()?.lowercase()) {
                    "on" -> com.bitchat.android.net.TorMode.ON
                    "off" -> com.bitchat.android.net.TorMode.OFF
                    else -> null
                }
                if (mode == null) "usage: tor-set on|off" else {
                    com.bitchat.android.net.TorPreferenceManager.set(getApplication(), mode)
                    viewModelScope.launch { com.bitchat.android.net.ArtiTorManager.getInstance().applyMode(getApplication(), mode) }
                    "tor-set=$mode (applying -> watch 'tor')"
                }
            }
            "nostr-connect" -> { viewModelScope.launch { com.bitchat.android.nostr.NostrRelayManager.shared.connect() }; "nostr connect requested" }
            "nostr-disconnect" -> { com.bitchat.android.nostr.NostrRelayManager.shared.disconnect(); "nostr disconnected" }
            else -> "unknown cmd '$cmd' (try: help)"
        }
    }
    private val _peerBroadcastState =
        kotlinx.coroutines.flow.MutableStateFlow<com.bitchat.android.features.dogecoin.PeerBroadcastUiState>(
            com.bitchat.android.features.dogecoin.PeerBroadcastUiState.Idle
        )
    val peerBroadcastState: StateFlow<com.bitchat.android.features.dogecoin.PeerBroadcastUiState> = _peerBroadcastState
    // 3b.1 Nostr fallback: stable reference so the sink can be compare-and-cleared on teardown.
    private val broadcastResultSink: (String, ByteArray) -> Unit = { fromId, payload ->
        paymentBroadcastCoordinator.onResult(fromId, payload)
    }
    // 3b.1: independent on-chain corroboration for a single-helper Claimed peer broadcast (opt-in/off).
    private val txConfirmationChecker: com.bitchat.android.features.dogecoin.DogecoinTxConfirmationChecker by lazy {
        com.bitchat.android.features.dogecoin.ExplorerTxConfirmationChecker(
            urlTemplateProvider = { network -> dogecoinWalletRepository.loadExplorerUrlTemplate(network) }
        )
    }
    private val messageManager = MessageManager(state)
    private val channelManager = ChannelManager(state, messageManager, dataManager, viewModelScope)

    // Create Noise session delegate for clean dependency injection
    private val noiseSessionDelegate = object : NoiseSessionDelegate {
        override fun hasEstablishedSession(peerID: String): Boolean = hasEstablishedSessionOnAnyLocalTransport(peerID)
        override fun initiateHandshake(peerID: String) = initiateNoiseHandshakeOnBestLocalTransport(peerID)
        override fun getMyPeerID(): String = mesh.myPeerID
    }

    val privateChatManager = PrivateChatManager(state, messageManager, dataManager, noiseSessionDelegate)
    private val commandProcessor = CommandProcessor(state, messageManager, channelManager, privateChatManager)
    private val notificationManager = NotificationManager(
      application.applicationContext,
      NotificationManagerCompat.from(application.applicationContext),
      NotificationIntervalManager()
    )

    private val verificationHandler = VerificationHandler(
        context = application.applicationContext,
        scope = viewModelScope,
        getMeshService = { mesh },
        identityManager = identityManager,
        state = state,
        notificationManager = notificationManager,
        messageManager = messageManager
    )
    val verifiedFingerprints = verificationHandler.verifiedFingerprints

    // Media file sending manager
    private val mediaSendingManager = MediaSendingManager(state, messageManager, channelManager) { mesh }
    
    // Delegate handler for mesh callbacks
    private val meshDelegateHandler = MeshDelegateHandler(
        state = state,
        messageManager = messageManager,
        channelManager = channelManager,
        privateChatManager = privateChatManager,
        notificationManager = notificationManager,
        coroutineScope = viewModelScope,
        onHapticFeedback = { ChatViewModelUtils.triggerHapticFeedback(application.applicationContext) },
        getMyPeerID = { mesh.myPeerID },
        getMeshService = { mesh }
    )
    
    // New Geohash architecture ViewModel (replaces God object service usage in UI path)
    val geohashViewModel = GeohashViewModel(
        application = application,
        state = state,
        messageManager = messageManager,
        privateChatManager = privateChatManager,
        meshDelegateHandler = meshDelegateHandler,
        dataManager = dataManager,
        notificationManager = notificationManager
    )





    val messages: StateFlow<List<BitchatMessage>> = state.messages
    val connectedPeers: StateFlow<List<String>> = state.connectedPeers
    val nickname: StateFlow<String> = state.nickname
    val isConnected: StateFlow<Boolean> = state.isConnected
    val privateChats: StateFlow<Map<String, List<BitchatMessage>>> = state.privateChats
    val selectedPrivateChatPeer: StateFlow<String?> = state.selectedPrivateChatPeer
    val unreadPrivateMessages: StateFlow<Set<String>> = state.unreadPrivateMessages
    val joinedChannels: StateFlow<Set<String>> = state.joinedChannels
    val currentChannel: StateFlow<String?> = state.currentChannel
    val channelMessages: StateFlow<Map<String, List<BitchatMessage>>> = state.channelMessages
    val unreadChannelMessages: StateFlow<Map<String, Int>> = state.unreadChannelMessages
    val passwordProtectedChannels: StateFlow<Set<String>> = state.passwordProtectedChannels
    val showPasswordPrompt: StateFlow<Boolean> = state.showPasswordPrompt
    val passwordPromptChannel: StateFlow<String?> = state.passwordPromptChannel
    val hasUnreadChannels = state.hasUnreadChannels
    val hasUnreadPrivateMessages = state.hasUnreadPrivateMessages
    val showCommandSuggestions: StateFlow<Boolean> = state.showCommandSuggestions
    val commandSuggestions: StateFlow<List<CommandSuggestion>> = state.commandSuggestions
    val showMentionSuggestions: StateFlow<Boolean> = state.showMentionSuggestions
    val mentionSuggestions: StateFlow<List<String>> = state.mentionSuggestions
    val favoritePeers: StateFlow<Set<String>> = state.favoritePeers
    val peerSessionStates: StateFlow<Map<String, String>> = state.peerSessionStates
    val peerFingerprints: StateFlow<Map<String, String>> = state.peerFingerprints
    val peerDogecoinAddresses: StateFlow<Map<String, Map<String, String>>> = state.peerDogecoinAddresses
    val peerNicknames: StateFlow<Map<String, String>> = state.peerNicknames
    val peerRSSI: StateFlow<Map<String, Int>> = state.peerRSSI
    val peerDirect: StateFlow<Map<String, Boolean>> = state.peerDirect
    val showAppInfo: StateFlow<Boolean> = state.showAppInfo
    val showMeshPeerList: StateFlow<Boolean> = state.showMeshPeerList
    val privateChatSheetPeer: StateFlow<String?> = state.privateChatSheetPeer
    val showVerificationSheet: StateFlow<Boolean> = state.showVerificationSheet
    val showSecurityVerificationSheet: StateFlow<Boolean> = state.showSecurityVerificationSheet
    val selectedLocationChannel: StateFlow<com.bitchat.android.geohash.ChannelID?> = state.selectedLocationChannel
    val isTeleported: StateFlow<Boolean> = state.isTeleported
    val geohashPeople: StateFlow<List<GeoPerson>> = state.geohashPeople
    val teleportedGeo: StateFlow<Set<String>> = state.teleportedGeo
    val geohashParticipantCounts: StateFlow<Map<String, Int>> = state.geohashParticipantCounts
    val meshServiceFacade: MeshService
        get() = mesh
    val myPeerID: String
        get() = mesh.myPeerID

    fun getMeshPeerFingerprint(peerID: String): String? = mesh.getPeerFingerprint(peerID)

    fun getMeshPeerInfo(peerID: String): com.bitchat.android.mesh.PeerInfo? = mesh.getPeerInfo(peerID)

    fun initiateMeshHandshake(peerID: String) {
        mesh.initiateNoiseHandshake(peerID)
    }

    fun currentDogecoinNetwork(): DogecoinNetwork {
        return dogecoinWalletRepository.loadSelectedNetwork()
    }

    fun getPeerDogecoinAddress(
        peerIDOrFingerprint: String,
        network: DogecoinNetwork = currentDogecoinNetwork()
    ): String? {
        val activeAddress = runCatching {
            mesh.getPeerInfo(peerIDOrFingerprint)?.dogecoinAddresses?.get(network.id)
        }.getOrNull()
        val cachedAddress = activeAddress ?: identityManager.getPeerDogecoinAddress(peerIDOrFingerprint, network.id)
        return cachedAddress
            ?.trim()
            ?.takeIf { DogecoinAddress.isValidAddress(it, network) }
    }

    fun getPeerDogecoinPaymentUri(peerIDOrFingerprint: String): String? {
        val network = currentDogecoinNetwork()
        val address = getPeerDogecoinAddress(peerIDOrFingerprint, network) ?: return null
        return runCatching {
            DogecoinProtocol.createPaymentUri(network, address)
        }.getOrNull()
    }

    /**
     * Re-broadcast the signed IdentityAnnouncement immediately. The announce is rebuilt from current
     * state, so this propagates any just-changed advertised fields (Dogecoin receive address AND the
     * NODE_HELPER broadcast-helper networks) without waiting for the ~30s periodic announce.
     */
    fun reannounceIdentity() {
        mesh.sendBroadcastAnnounce()
    }

    init {
        // Note: Mesh service delegate is now set by MainActivity
        loadAndInitialize()
        // 3b.1 Nostr fallback: let the Nostr direct-message handler (built in GeohashViewModel) deliver a
        // PAYMENT_BROADCAST_RESULT received off-mesh to this ViewModel's coordinator. Last writer wins; a
        // result with no in-flight broadcast is harmlessly ignored by the coordinator's replay-0 flow.
        // Cleared in onCleared so the singleton does not retain a dead ViewModel.
        com.bitchat.android.features.dogecoin.PaymentBroadcastResultRouter.setSink(broadcastResultSink)
        // Debug-only adb console (see DebugConsole). No-op in release; receiver only exists in debug builds.
        if (com.bitchat.android.BuildConfig.DEBUG) com.bitchat.android.debug.DebugConsole.setHost(debugConsoleHost)
        // Hydrate UI state from process-wide AppStateStore to survive Activity recreation
        viewModelScope.launch {
            try { com.bitchat.android.services.AppStateStore.peers.collect { peers ->
                state.setConnectedPeers(peers)
                state.setIsConnected(peers.isNotEmpty())
            } } catch (_: Exception) { }
        }
        viewModelScope.launch {
            try { com.bitchat.android.services.AppStateStore.publicMessages.collect { msgs ->
                // Source of truth is AppStateStore; replace to avoid duplicate keys in LazyColumn
                state.setMessages(msgs)
            } } catch (_: Exception) { }
        }
        viewModelScope.launch {
            try { com.bitchat.android.services.AppStateStore.privateMessages.collect { byPeer ->
                // Replace with store snapshot
                state.setPrivateChats(byPeer)
                // Recompute unread set using SeenMessageStore for robustness across Activity recreation
                try {
                    val seen = com.bitchat.android.services.SeenMessageStore.getInstance(getApplication())
                    val myNick = state.getNicknameValue() ?: mesh.myPeerID
                    val unread = mutableSetOf<String>()
                    byPeer.forEach { (peer, list) ->
                        if (list.any { msg -> msg.sender != myNick && !seen.hasRead(msg.id) }) unread.add(peer)
                    }
                    state.setUnreadPrivateMessages(unread)
                } catch (_: Exception) { }
            } } catch (_: Exception) { }
        }
        viewModelScope.launch {
            try { com.bitchat.android.services.AppStateStore.channelMessages.collect { byChannel ->
                // Replace with store snapshot
                state.setChannelMessages(byChannel)
            } } catch (_: Exception) { }
        }
        // Subscribe to BLE transfer progress and reflect in message deliveryStatus
        viewModelScope.launch {
            com.bitchat.android.mesh.TransferProgressManager.events.collect { evt ->
                mediaSendingManager.handleTransferProgressEvent(evt)
            }
        }
        
        // Removed background location notes subscription. Notes now load only when sheet opens.
    }

    fun cancelMediaSend(messageId: String) {
        // Delegate to MediaSendingManager which tracks transfer IDs and cleans up UI state
        mediaSendingManager.cancelMediaSend(messageId)
    }
    
    private fun loadAndInitialize() {
        // Load nickname
        val nickname = dataManager.loadNickname()
        state.setNickname(nickname)
        
        // Load data
        val (joinedChannels, protectedChannels) = channelManager.loadChannelData()
        state.setJoinedChannels(joinedChannels)
        state.setPasswordProtectedChannels(protectedChannels)
        
        // Initialize channel messages
        joinedChannels.forEach { channel ->
            if (!state.getChannelMessagesValue().containsKey(channel)) {
                val updatedChannelMessages = state.getChannelMessagesValue().toMutableMap()
                updatedChannelMessages[channel] = emptyList()
                state.setChannelMessages(updatedChannelMessages)
            }
        }
        
        // Load other data
        dataManager.loadFavorites()
        state.setFavoritePeers(dataManager.favoritePeers.toSet())
        dataManager.loadBlockedUsers()
        dataManager.loadGeohashBlockedUsers()

        // Log all favorites at startup
        dataManager.logAllFavorites()
        logCurrentFavoriteState()
        
        // Initialize session state monitoring
        initializeSessionStateMonitoring()

        // Bridge DebugSettingsManager -> Chat messages when verbose logging is on
        viewModelScope.launch {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().debugMessages.collect { msgs ->
                if (com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().verboseLoggingEnabled.value) {
                    // Only show debug logs in the Mesh chat timeline to avoid leaking into geohash chats
                    val selectedLocation = state.selectedLocationChannel.value
                    if (selectedLocation is com.bitchat.android.geohash.ChannelID.Mesh) {
                        // Append only latest debug message as system message to avoid flooding
                        msgs.lastOrNull()?.let { dm ->
                            messageManager.addSystemMessage(dm.content)
                        }
                    }
                }
            }
        }
        
        // Initialize new geohash architecture
        geohashViewModel.initialize()

        // Initialize favorites persistence service
        com.bitchat.android.favorites.FavoritesPersistenceService.initialize(getApplication())

        // Load verified fingerprints from secure storage
        verificationHandler.loadVerifiedFingerprints()


        // Ensure NostrTransport knows our mesh peer ID for embedded packets
        try {
            val nostrTransport = com.bitchat.android.nostr.NostrTransport.getInstance(getApplication())
            nostrTransport.senderPeerID = mesh.myPeerID
        } catch (_: Exception) { }

        // Note: Mesh service is now started by MainActivity

        // BLE receives are inserted by MessageHandler path; no VoiceNoteBus for Tor in this branch.
    }
    
    override fun onCleared() {
        super.onCleared()
        // 3b.1: release the broadcast-result sink so the process-wide router does not retain this dead
        // ViewModel. Compare-and-clear so a newer ViewModel that already re-registered is not clobbered.
        com.bitchat.android.features.dogecoin.PaymentBroadcastResultRouter.clearSinkIfCurrent(broadcastResultSink)
        com.bitchat.android.debug.DebugConsole.clearHostIfCurrent(debugConsoleHost)
        dogecoinSpvService?.stop()  // release the SPV PeerGroup/store if it was started this session
        // Note: Mesh service lifecycle is now managed by MainActivity
    }
    
    // MARK: - Nickname Management
    
    fun setNickname(newNickname: String) {
        state.setNickname(newNickname)
        dataManager.saveNickname(newNickname)
        mesh.sendBroadcastAnnounce()
    }
    
    /**
     * Ensure Nostr DM subscription for a geohash conversation key if known
     * Minimal-change approach: reflectively access GeohashViewModel internals to reuse pipeline
     */
    private fun ensureGeohashDMSubscriptionIfNeeded(convKey: String) {
        try {
            val repoField = GeohashViewModel::class.java.getDeclaredField("repo")
            repoField.isAccessible = true
            val repo = repoField.get(geohashViewModel) as com.bitchat.android.nostr.GeohashRepository
            val gh = repo.getConversationGeohash(convKey)
            if (!gh.isNullOrEmpty()) {
                val subMgrField = GeohashViewModel::class.java.getDeclaredField("subscriptionManager")
                subMgrField.isAccessible = true
                val subMgr = subMgrField.get(geohashViewModel) as com.bitchat.android.nostr.NostrSubscriptionManager
                val identity = com.bitchat.android.nostr.NostrIdentityBridge.deriveIdentity(gh, getApplication())
                val subId = "geo-dm-$gh"
                val currentDmSubField = GeohashViewModel::class.java.getDeclaredField("currentDmSubId")
                currentDmSubField.isAccessible = true
                val currentId = currentDmSubField.get(geohashViewModel) as String?
                if (currentId != subId) {
                    (currentId)?.let { subMgr.unsubscribe(it) }
                    currentDmSubField.set(geohashViewModel, subId)
                    subMgr.subscribeGiftWraps(
                        pubkey = identity.publicKeyHex,
                        sinceMs = System.currentTimeMillis() - 172800000L,
                        id = subId,
                        handler = { event ->
                            val dmHandlerField = GeohashViewModel::class.java.getDeclaredField("dmHandler")
                            dmHandlerField.isAccessible = true
                            val dmHandler = dmHandlerField.get(geohashViewModel) as com.bitchat.android.nostr.NostrDirectMessageHandler
                            dmHandler.onGiftWrap(event, gh, identity)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureGeohashDMSubscriptionIfNeeded failed: ${e.message}")
        }
    }

    // MARK: - Channel Management (delegated)
    
    fun joinChannel(channel: String, password: String? = null): Boolean {
        return channelManager.joinChannel(channel, password, mesh.myPeerID)
    }
    
    fun switchToChannel(channel: String?) {
        channelManager.switchToChannel(channel)
    }
    
    fun leaveChannel(channel: String) {
        channelManager.leaveChannel(channel)
        mesh.sendMessage("left $channel", emptyList(), null)
    }
    
    // MARK: - Private Chat Management (delegated)
    
    fun startPrivateChat(peerID: String) {
        // For geohash conversation keys, ensure DM subscription is active
        if (peerID.startsWith("nostr_")) {
            ensureGeohashDMSubscriptionIfNeeded(peerID)
        }
        
        val success = privateChatManager.startPrivateChat(peerID, mesh)
        if (success) {
            // Notify notification manager about current private chat
            setCurrentPrivateChatPeer(peerID)
            // Clear notifications for this sender since user is now viewing the chat
            clearNotificationsForSender(peerID)

            // Persistently mark all messages in this conversation as read so Nostr fetches
            // after app restarts won't re-mark them as unread.
            try {
                val seen = com.bitchat.android.services.SeenMessageStore.getInstance(getApplication())
                val chats = state.getPrivateChatsValue()
                val messages = chats[peerID] ?: emptyList()
                messages.forEach { msg ->
                    try { seen.markRead(msg.id) } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }
    }
    
    fun endPrivateChat() {
        privateChatManager.endPrivateChat()
        // Notify notification manager that no private chat is active
        setCurrentPrivateChatPeer(null)
        // Clear mesh mention notifications since user is now back in mesh chat
        clearMeshMentionNotifications()
        // Ensure sheet is hidden
        hidePrivateChatSheet()
    }

    // MARK: - Open Latest Unread Private Chat

    fun openLatestUnreadPrivateChat() {
        try {
            val unreadKeys = state.getUnreadPrivateMessagesValue()
            if (unreadKeys.isEmpty()) return

            val me = state.getNicknameValue() ?: mesh.myPeerID
            val chats = state.getPrivateChatsValue()

            // Pick the latest incoming message among unread conversations
            var bestKey: String? = null
            var bestTime: Long = Long.MIN_VALUE

            unreadKeys.forEach { key ->
                val list = chats[key]
                if (!list.isNullOrEmpty()) {
                    // Prefer the latest incoming message (sender != me), fallback to last message
                    val latestIncoming = list.lastOrNull { it.sender != me }
                    val candidateTime = (latestIncoming ?: list.last()).timestamp.time
                    if (candidateTime > bestTime) {
                        bestTime = candidateTime
                        bestKey = key
                    }
                }
            }

            val targetKey = bestKey ?: unreadKeys.firstOrNull() ?: return

            val openPeer: String = if (targetKey.startsWith("nostr_")) {
                // Use the exact conversation key for geohash DMs and ensure DM subscription
                ensureGeohashDMSubscriptionIfNeeded(targetKey)
                targetKey
            } else {
                // Resolve to a canonical mesh peer if needed
                val canonical = com.bitchat.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                    selectedPeerID = targetKey,
                    connectedPeers = state.getConnectedPeersValue(),
                    meshNoiseKeyForPeer = { pid -> mesh.getPeerInfo(pid)?.noisePublicKey },
                    meshHasPeer = { pid -> mesh.getPeerInfo(pid)?.isConnected == true },
                    nostrPubHexForAlias = { alias -> com.bitchat.android.nostr.GeohashAliasRegistry.get(alias) },
                    findNoiseKeyForNostr = { key -> com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(key) }
                )
                canonical ?: targetKey
            }

            showPrivateChatSheet(openPeer)
        } catch (e: Exception) {
            Log.w(TAG, "openLatestUnreadPrivateChat failed: ${e.message}")
        }
    }

    // END - Open Latest Unread Private Chat

    
    // MARK: - Message Sending
    
    fun sendMessage(content: String) {
        if (content.isEmpty()) return
        
        // Check for commands
        if (content.startsWith("/")) {
            val selectedLocationForCommand = state.selectedLocationChannel.value
            commandProcessor.processCommand(content, mesh, mesh.myPeerID, { messageContent, mentions, channel ->
                if (selectedLocationForCommand is com.bitchat.android.geohash.ChannelID.Location) {
                    // Route command-generated public messages via Nostr in geohash channels
                    geohashViewModel.sendGeohashMessage(
                        messageContent,
                        selectedLocationForCommand.channel,
                        mesh.myPeerID,
                        state.getNicknameValue()
                    )
                } else {
                    mesh.sendMessage(messageContent, mentions, channel)
                }
            }, this)
            return
        }
        
        val mentions = messageManager.parseMentions(content, mesh.getPeerNicknames().values.toSet(), state.getNicknameValue())
        // REMOVED: Auto-join mentioned channels feature that was incorrectly parsing hashtags from @mentions
        // This was causing messages like "test @jack#1234 test" to auto-join channel "#1234"
        
        var selectedPeer = state.getSelectedPrivateChatPeerValue()
        val currentChannelValue = state.getCurrentChannelValue()
        
        if (selectedPeer != null) {
            // If the selected peer is a temporary Nostr alias or a noise-hex identity, resolve to a canonical target
            selectedPeer = com.bitchat.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                selectedPeerID = selectedPeer,
                connectedPeers = state.getConnectedPeersValue(),
                meshNoiseKeyForPeer = { pid -> mesh.getPeerInfo(pid)?.noisePublicKey },
                meshHasPeer = { pid -> mesh.getPeerInfo(pid)?.isConnected == true },
                nostrPubHexForAlias = { alias -> com.bitchat.android.nostr.GeohashAliasRegistry.get(alias) },
                findNoiseKeyForNostr = { key -> com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(key) }
            ).also { canonical ->
                if (canonical != state.getSelectedPrivateChatPeerValue()) {
                    privateChatManager.startPrivateChat(canonical, mesh)
                    // If we're in the private chat sheet, update its active peer too
                    if (state.getPrivateChatSheetPeerValue() != null) {
                        showPrivateChatSheet(canonical)
                    }
                }
            }
            // Send private message
            val recipientNickname = nicknameForPeer(selectedPeer)
            privateChatManager.sendPrivateMessage(
                content, 
                selectedPeer, 
                recipientNickname,
                state.getNicknameValue(),
                mesh.myPeerID
            ) { messageContent, peerID, recipientNicknameParam, messageId ->
                // Route via MessageRouter (mesh when connected+established, else Nostr)
                val router = com.bitchat.android.services.MessageRouter.getInstance(getApplication(), mesh)
                router.sendPrivate(messageContent, peerID, recipientNicknameParam, messageId)
            }
        } else {
            // Check if we're in a location channel
            val selectedLocationChannel = state.selectedLocationChannel.value
            if (selectedLocationChannel is com.bitchat.android.geohash.ChannelID.Location) {
                // Send to geohash channel via Nostr ephemeral event
                geohashViewModel.sendGeohashMessage(content, selectedLocationChannel.channel, mesh.myPeerID, state.getNicknameValue())
            } else {
                // Send public/channel message via mesh
                val message = BitchatMessage(
                    sender = state.getNicknameValue() ?: mesh.myPeerID,
                    content = content,
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = mesh.myPeerID,
                    mentions = if (mentions.isNotEmpty()) mentions else null,
                    channel = currentChannelValue
                )

                if (currentChannelValue != null) {
                    if (
                        channelManager.isChannelPasswordProtected(currentChannelValue) &&
                        !channelManager.hasChannelKey(currentChannelValue)
                    ) {
                        channelManager.addChannelMessage(
                            currentChannelValue,
                            BitchatMessage(
                                sender = "system",
                                content = "enter the channel password before sending to $currentChannelValue.",
                                timestamp = Date(),
                                isRelay = false
                            ),
                            null
                        )
                        return
                    }

                    channelManager.addChannelMessage(currentChannelValue, message, mesh.myPeerID)

                    // Check if encrypted channel
                    if (channelManager.hasChannelKey(currentChannelValue)) {
                        channelManager.sendEncryptedChannelMessage(
                            content,
                            mentions,
                            currentChannelValue,
                            state.getNicknameValue(),
                            mesh.myPeerID,
                            onEncryptedPayload = { encryptedData ->
                                mesh.sendChannelMessage(
                                    message.copy(
                                        content = "",
                                        encryptedContent = encryptedData,
                                        isEncrypted = true
                                    )
                                )
                            },
                            onFallback = {
                                channelManager.addChannelMessage(
                                    currentChannelValue,
                                    BitchatMessage(
                                        sender = "system",
                                        content = "failed to encrypt message for $currentChannelValue.",
                                        timestamp = Date(),
                                        isRelay = false
                                    ),
                                    null
                                )
                            }
                        )
                    } else {
                        mesh.sendMessage(content, mentions, currentChannelValue)
                    }
                } else {
                    messageManager.addMessage(message)
                    mesh.sendMessage(content, mentions, null)
                }
            }
        }
    }

    // MARK: - Utility Functions
    
    fun getPeerIDForNickname(nickname: String): String? {
        return mesh.getPeerNicknames().entries.find { it.value == nickname }?.key
    }
    
    fun toggleFavorite(peerID: String) {
        Log.d("ChatViewModel", "toggleFavorite called for peerID: $peerID")
        privateChatManager.toggleFavorite(peerID)

        // Persist relationship in FavoritesPersistenceService
        try {
            var noiseKey: ByteArray? = null
            var nickname: String = mesh.getPeerNicknames()[peerID] ?: peerID

            // Case 1: Live mesh peer with known info
            val peerInfo = mesh.getPeerInfo(peerID)
            if (peerInfo?.noisePublicKey != null) {
                noiseKey = peerInfo.noisePublicKey
                nickname = peerInfo.nickname
            } else {
                // Case 2: Offline favorite entry using 64-hex noise public key as peerID
                if (peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                    try {
                        noiseKey = peerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        // Prefer nickname from favorites store if available
                        val rel = com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey!!)
                        if (rel != null) nickname = rel.peerNickname
                    } catch (_: Exception) { }
                }
            }

            if (noiseKey != null) {
                // Determine current favorite state from DataManager using fingerprint
                val identityManager = com.bitchat.android.identity.SecureIdentityStateManager(getApplication())
                val fingerprint = identityManager.generateFingerprint(noiseKey!!)
                val isNowFavorite = dataManager.favoritePeers.contains(fingerprint)

                com.bitchat.android.favorites.FavoritesPersistenceService.shared.updateFavoriteStatus(
                    noisePublicKey = noiseKey!!,
                    nickname = nickname,
                    isFavorite = isNowFavorite
                )

                // Send favorite notification via mesh or Nostr with our npub if available
                try {
                    com.bitchat.android.services.MessageRouter
                        .getInstance(getApplication(), mesh)
                        .sendFavoriteNotification(peerID, isNowFavorite)
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        // Log current state after toggle
        logCurrentFavoriteState()
    }
    
    private fun logCurrentFavoriteState() {
        Log.i("ChatViewModel", "=== CURRENT FAVORITE STATE ===")
        Log.i("ChatViewModel", "StateFlow favorite peers: ${favoritePeers.value}")
        Log.i("ChatViewModel", "DataManager favorite peers: ${dataManager.favoritePeers}")
        Log.i("ChatViewModel", "Peer fingerprints: ${privateChatManager.getAllPeerFingerprints()}")
        Log.i("ChatViewModel", "==============================")
    }

    private fun isConnectedOnMesh(peerID: String): Boolean {
        return try {
            mesh.getPeerInfo(peerID)?.isConnected == true
        } catch (_: Exception) {
            false
        }
    }

    private fun hasEstablishedSessionOnMesh(peerID: String): Boolean {
        return try {
            mesh.getPeerInfo(peerID)?.isConnected == true &&
                mesh.hasEstablishedSession(peerID)
        } catch (_: Exception) {
            false
        }
    }

    private fun hasEstablishedSessionOnAnyLocalTransport(peerID: String): Boolean {
        return hasEstablishedSessionOnMesh(peerID)
    }

    private fun initiateNoiseHandshakeOnBestLocalTransport(peerID: String) {
        mesh.initiateNoiseHandshake(peerID)
    }

    private fun nicknameForPeer(peerID: String): String? {
        return state.peerNicknames.value[peerID]
            ?: try { mesh.getPeerNicknames()[peerID] } catch (_: Exception) { null }
    }

    private fun sessionStateForPeer(peerID: String): NoiseSession.NoiseSessionState {
        return try { mesh.getSessionState(peerID) } catch (_: Exception) { NoiseSession.NoiseSessionState.Uninitialized }
    }
    
    /**
     * Initialize session state monitoring for reactive UI updates
     */
    private fun initializeSessionStateMonitoring() {
        viewModelScope.launch {
            while (true) {
                delay(1000) // Check session states every second
                updateReactiveStates()
            }
        }
    }
    
    // Location notes subscription management moved to LocationNotesViewModelExtensions.kt
    
    /**
     * Update reactive states for all connected peers (session states, fingerprints, nicknames, RSSI)
     */
    private fun updateReactiveStates() {
        val currentPeers = state.getConnectedPeersValue()
        
        // Update session states
        val prevStates = state.getPeerSessionStatesValue()
        val sessionStates = currentPeers.associateWith { peerID ->
            sessionStateForPeer(peerID).toString()
        }
        state.setPeerSessionStates(sessionStates)
        // Detect new established sessions and flush router outbox for them and their noiseHex aliases
        sessionStates.forEach { (peerID, newState) ->
            val old = prevStates[peerID]
            if (old != "established" && newState == "established") {
                com.bitchat.android.services.MessageRouter
                    .getInstance(getApplication(), mesh)
                    .onSessionEstablished(peerID)
            }
        }
        // Update fingerprint mappings from centralized manager
        val fingerprints = privateChatManager.getAllPeerFingerprints()
        state.setPeerFingerprints(fingerprints)
        fingerprints.forEach { (peerID, fingerprint) ->
            identityManager.cachePeerFingerprint(peerID, fingerprint)
            val info = try { mesh.getPeerInfo(peerID) } catch (_: Exception) { null }
            val noiseKeyHex = info?.noisePublicKey?.hexEncodedString()
            if (noiseKeyHex != null) {
                identityManager.cachePeerNoiseKey(peerID, noiseKeyHex)
                identityManager.cacheNoiseFingerprint(noiseKeyHex, fingerprint)
            }
            info?.nickname?.takeIf { it.isNotBlank() }?.let { nickname ->
                identityManager.cacheFingerprintNickname(fingerprint, nickname)
            }
            info?.dogecoinAddresses?.forEach { (networkId, address) ->
                identityManager.cachePeerDogecoinAddress(fingerprint, networkId, address)
            }
        }

        val dogecoinAddresses = currentPeers.mapNotNull { peerID ->
            val addresses = try { mesh.getPeerInfo(peerID)?.dogecoinAddresses.orEmpty() } catch (_: Exception) { emptyMap() }
            if (addresses.isEmpty()) null else peerID to addresses
        }.toMap()
        state.setPeerDogecoinAddresses(dogecoinAddresses)

        state.setPeerNicknames(mesh.getPeerNicknames())

        state.setPeerRSSI(mesh.getPeerRSSI())

        // Update directness per peer (driven by PeerManager state)
        try {
            val directMap = state.getConnectedPeersValue().associateWith { pid ->
                mesh.getPeerInfo(pid)?.isDirectConnection == true
            }
            state.setPeerDirect(directMap)
        } catch (_: Exception) { }

        // Flush any pending QR verification once a Noise session is established
        currentPeers.forEach { peerID ->
            if (sessionStateForPeer(peerID) is NoiseSession.NoiseSessionState.Established) {
                verificationHandler.sendPendingVerificationIfNeeded(peerID)
            }
        }
    }

    // MARK: - QR Verification
    
    fun isPeerVerified(peerID: String, verifiedFingerprints: Set<String>): Boolean {
        if (peerID.startsWith("nostr_") || peerID.startsWith("nostr:")) return false
        val fingerprint = verificationHandler.getPeerFingerprintForDisplay(peerID)
        return fingerprint != null && verifiedFingerprints.contains(fingerprint)
    }

    fun isNoisePublicKeyVerified(noisePublicKey: ByteArray, verifiedFingerprints: Set<String>): Boolean {
        val fingerprint = verificationHandler.fingerprintFromNoiseBytes(noisePublicKey)
        return verifiedFingerprints.contains(fingerprint)
    }

    fun unverifyFingerprint(peerID: String) {
        verificationHandler.unverifyFingerprint(peerID)
    }

    fun beginQRVerification(qr: VerificationService.VerificationQR): Boolean {
        return verificationHandler.beginQRVerification(qr)
    }

    // MARK: - Debug and Troubleshooting
    
    fun getDebugStatus(): String {
        return mesh.getDebugStatus()
    }
    
    fun setCurrentPrivateChatPeer(peerID: String?) {
        notificationManager.setCurrentPrivateChatPeer(peerID)
    }
    
    fun setCurrentGeohash(geohash: String?) {
        notificationManager.setCurrentGeohash(geohash)
    }

    fun clearNotificationsForSender(peerID: String) {
        notificationManager.clearNotificationsForSender(peerID)
    }
    
    fun clearNotificationsForGeohash(geohash: String) {
        notificationManager.clearNotificationsForGeohash(geohash)
    }

    fun clearMeshMentionNotifications() {
        notificationManager.clearMeshMentionNotifications()
    }

    private var reopenSidebarAfterVerification = false

    fun showVerificationSheet(fromSidebar: Boolean = false) {
        if (fromSidebar) {
            reopenSidebarAfterVerification = true
        }
        state.setShowVerificationSheet(true)
    }

    fun hideVerificationSheet() {
        state.setShowVerificationSheet(false)
        if (reopenSidebarAfterVerification) {
            reopenSidebarAfterVerification = false
            state.setShowMeshPeerList(true)
        }
    }

    fun showSecurityVerificationSheet() {
        state.setShowSecurityVerificationSheet(true)
    }

    fun hideSecurityVerificationSheet() {
        state.setShowSecurityVerificationSheet(false)
    }

    fun showMeshPeerList() {
        state.setShowMeshPeerList(true)
    }

    fun hideMeshPeerList() {
        state.setShowMeshPeerList(false)
    }

    fun showPrivateChatSheet(peerID: String) {
        state.setPrivateChatSheetPeer(peerID)
    }

    fun hidePrivateChatSheet() {
        state.setPrivateChatSheetPeer(null)
    }

    fun getPeerFingerprintForDisplay(peerID: String): String? {
        return verificationHandler.getPeerFingerprintForDisplay(peerID)
    }

    fun getMyFingerprint(): String {
        return verificationHandler.getMyFingerprint()
    }

    fun resolvePeerDisplayNameForFingerprint(peerID: String): String {
        return verificationHandler.resolvePeerDisplayNameForFingerprint(peerID)
    }

    fun verifyFingerprintValue(fingerprint: String) {
        verificationHandler.verifyFingerprintValue(fingerprint)
    }

    fun unverifyFingerprintValue(fingerprint: String) {
        verificationHandler.unverifyFingerprintValue(fingerprint)
    }

    // MARK: - Command Autocomplete (delegated)
    
    fun updateCommandSuggestions(input: String) {
        commandProcessor.updateCommandSuggestions(input)
    }
    
    fun selectCommandSuggestion(suggestion: CommandSuggestion): String {
        return commandProcessor.selectCommandSuggestion(suggestion)
    }
    
    // MARK: - Mention Autocomplete
    
    fun updateMentionSuggestions(input: String) {
        commandProcessor.updateMentionSuggestions(input, mesh, this)
    }
    
    fun selectMentionSuggestion(nickname: String, currentText: String): String {
        return commandProcessor.selectMentionSuggestion(nickname, currentText)
    }
    
    // MARK: - BluetoothMeshDelegate Implementation (delegated)
    
    override fun didReceiveMessage(message: BitchatMessage) {
        meshDelegateHandler.didReceiveMessage(message)
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        meshDelegateHandler.didUpdatePeerList(peers)
    }

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        meshDelegateHandler.didReceiveChannelLeave(channel, fromPeer)
    }
    
    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveDeliveryAck(messageID, recipientPeerID)
    }
    
    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveReadReceipt(messageID, recipientPeerID)
    }

    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {
        verificationHandler.didReceiveVerifyChallenge(peerID, payload)
    }

    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {
        verificationHandler.didReceiveVerifyResponse(peerID, payload)
    }

    override fun didReceivePaymentBroadcastRequest(peerID: String, payload: ByteArray, timestampMs: Long) {
        viewModelScope.launch {
            try {
                val noiseKeyHex = mesh.getPeerInfo(peerID)?.noisePublicKey?.joinToString("") { "%02x".format(it) }
                val resultBytes = broadcastHelper.handleRequest(peerID, noiseKeyHex, payload, System.currentTimeMillis())
                if (resultBytes != null) {
                    com.bitchat.android.services.MessageRouter.getInstance(getApplication(), mesh)
                        .sendPaymentBroadcastResult(resultBytes, peerID)
                }
            } catch (e: Exception) {
                android.util.Log.w("ChatViewModel", "Broadcast helper failed for ${peerID.take(8)}…: ${e.message}")
            }
        }
    }

    override fun didReceivePaymentBroadcastResult(peerID: String, payload: ByteArray, timestampMs: Long) {
        // Canonicalize the corroboration source id to the helper's stable Noise key so mesh and the Nostr
        // fallback share ONE identity space: a single physical helper that replies on both transports
        // collapses to one entry and can never fake the two-helper Confirmed. DROP if the Noise key can't be
        // resolved (rather than falling back to the 16-hex peerID, which lives in a different id space) —
        // symmetric with the Nostr arm's drop-if-unresolved policy, so the canonical-identity invariant has
        // no seam. A result over an established session always resolves; an unresolvable one merely doesn't
        // corroborate (the safe direction).
        val canonicalId = mesh.getPeerInfo(peerID)?.noisePublicKey?.joinToString("") { "%02x".format(it) }
        if (canonicalId == null) {
            android.util.Log.d("ChatViewModel", "Dropping payment-broadcast RESULT from unresolved peer ${peerID.take(8)}…")
            return
        }
        paymentBroadcastCoordinator.onResult(canonicalId, payload)
    }

    /**
     * Sender side: relay a locally-signed transaction to opt-in helper peers when the local node
     * cannot broadcast. Drives [peerBroadcastState] (Pending -> Confirmed/Claimed/Failed) for the wallet UI.
     */
    fun requestPeerBroadcast(signedTransaction: com.bitchat.android.features.dogecoin.DogecoinSignedTransaction) {
        _peerBroadcastState.value = com.bitchat.android.features.dogecoin.PeerBroadcastUiState.Pending
        viewModelScope.launch {
            val network = currentDogecoinNetwork()
            // Warm up BLE Noise sessions with connected session-less helpers so the relay prefers mesh over
            // Nostr (the broadcast send path never initiates a handshake itself). No-op + no delay when a
            // helper session already exists or none is connected. Best-effort: failure just leaves the existing
            // Nostr-fallback behavior. See docs/dogecoin-offline-mesh-relay-findings.md.
            runCatching { warmUpMeshHelperSessions(network) }
            val outcome = try {
                paymentBroadcastCoordinator.broadcast(
                    rawTransactionHex = signedTransaction.rawTransactionHex,
                    expectedTxid = signedTransaction.txid,
                    network = network
                )
            } catch (e: Exception) {
                com.bitchat.android.features.dogecoin.PaymentBroadcastCoordinator.Outcome.Failed(e.message ?: "Broadcast failed.")
            }
            _peerBroadcastState.value = when (outcome) {
                is com.bitchat.android.features.dogecoin.PaymentBroadcastCoordinator.Outcome.Confirmed ->
                    com.bitchat.android.features.dogecoin.PeerBroadcastUiState.Confirmed(outcome.txid)
                is com.bitchat.android.features.dogecoin.PaymentBroadcastCoordinator.Outcome.Claimed ->
                    resolveClaimedPeerBroadcast(outcome.txid, network)
                is com.bitchat.android.features.dogecoin.PaymentBroadcastCoordinator.Outcome.Failed ->
                    com.bitchat.android.features.dogecoin.PeerBroadcastUiState.Failed(outcome.reason)
            }
        }
    }

    /**
     * 3b.1: a single helper's uncorroborated Claimed broadcast. If on-chain corroboration is enabled for
     * [network], independently confirm [txid] via a public block explorer; a positive sighting (mempool or
     * block) is a second, independent witness that upgrades Claimed -> Confirmed. Bounded and best-effort:
     * disabled, unreachable, not-found, or timed-out all leave it Claimed (the honest "verify before
     * settled" receipt). Decided BEFORE emitting so the UI receipt is built exactly once.
     */
    private suspend fun resolveClaimedPeerBroadcast(
        txid: String,
        network: DogecoinNetwork
    ): com.bitchat.android.features.dogecoin.PeerBroadcastUiState {
        val enabled = runCatching {
            dogecoinWalletRepository.loadOnChainCorroborationEnabled(network)
        }.getOrDefault(false)
        if (!enabled) return com.bitchat.android.features.dogecoin.PeerBroadcastUiState.Claimed(txid)

        val seen = kotlinx.coroutines.withTimeoutOrNull(EXPLORER_POLL_TOTAL_BUDGET_MS) {
            repeat(EXPLORER_POLL_ATTEMPTS) { attempt ->
                val result = runCatching { txConfirmationChecker.isOnChain(txid, network) }.getOrNull()
                if (result == true) return@withTimeoutOrNull true
                if (attempt < EXPLORER_POLL_ATTEMPTS - 1) kotlinx.coroutines.delay(EXPLORER_POLL_INTERVAL_MS)
            }
            false
        }
        return if (seen == true) {
            com.bitchat.android.features.dogecoin.PeerBroadcastUiState.Confirmed(txid)
        } else {
            com.bitchat.android.features.dogecoin.PeerBroadcastUiState.Claimed(txid)
        }
    }

    fun clearPeerBroadcastState() {
        _peerBroadcastState.value = com.bitchat.android.features.dogecoin.PeerBroadcastUiState.Idle
    }

    /** True if any connected peer could plausibly broadcast for us right now (drives the CTA). */
    fun hasBroadcastHelperCandidate(): Boolean = listBroadcastHelperCandidates(currentDogecoinNetwork()).isNotEmpty()

    private fun listBroadcastHelperCandidates(network: DogecoinNetwork): List<String> {
        return try {
            // Candidates are identified by the helper's stable 64-hex Noise key (the canonical per-helper
            // identity used for dispatch AND for counting corroborations — see mergeBroadcastHelperCandidates
            // and resolveConnectedMeshPeerId). Mesh tier (fast path): connected, session-established peers
            // that either advertise (signature-verified) NODE_HELPER for this network or are mutual favorites
            // (a favorites-only helper may not have advertised). Ranked advertised-first, then mutual.
            val meshNoiseKeysOrdered = state.getConnectedPeersValue()
                .asSequence()
                .filter { it != mesh.myPeerID }
                .filter { mesh.hasEstablishedSession(it) }
                .mapNotNull { peerID ->
                    val info = mesh.getPeerInfo(peerID) ?: return@mapNotNull null
                    val key = info.noisePublicKey ?: return@mapNotNull null
                    val noiseHex = key.joinToString("") { "%02x".format(it) }
                    val advertisesHelp = network.id in info.helperNetworks
                    val mutual = runCatching {
                        com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(key)?.isMutual == true
                    }.getOrDefault(false)
                    if (advertisesHelp || mutual) Triple(noiseHex, advertisesHelp, mutual) else null
                }
                .sortedWith(
                    compareByDescending<Triple<String, Boolean, Boolean>> { it.second }
                        .thenByDescending { it.third }
                )
                .map { it.first }
                .toList()

            // Nostr fallback tier (3b.1): mutual favorites with a stored Nostr key, by 64-hex Noise key.
            // Appended AFTER all mesh candidates and de-duped against them by Noise key in
            // mergeBroadcastHelperCandidates so one helper is dispatched on only one transport.
            val offMeshFavoriteNoiseHex = runCatching {
                com.bitchat.android.favorites.FavoritesPersistenceService.shared.getMutualFavorites()
                    .asSequence()
                    .filter { !it.peerNostrPublicKey.isNullOrBlank() }
                    .map { it.peerNoisePublicKey.joinToString("") { b -> "%02x".format(b) } }
                    .toList()
            }.getOrDefault(emptyList())

            com.bitchat.android.features.dogecoin.mergeBroadcastHelperCandidates(meshNoiseKeysOrdered, offMeshFavoriteNoiseHex)
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Resolve a canonical 64-hex Noise key to a currently connected, session-established mesh peerID that
     * holds that key, or null if no such mesh peer (the helper is off-mesh -> dispatch over Nostr). Used so
     * the coordinator can address candidates by stable Noise identity while the wire send still targets the
     * peer's live (rotating) mesh peerID.
     */
    private fun resolveConnectedMeshPeerId(noiseKeyHex: String): String? {
        val want = noiseKeyHex.lowercase()
        return runCatching {
            state.getConnectedPeersValue().firstOrNull { peerID ->
                mesh.hasEstablishedSession(peerID) &&
                    mesh.getPeerInfo(peerID)?.noisePublicKey?.joinToString("") { "%02x".format(it) } == want
            }
        }.getOrNull()
    }

    /**
     * Proactive entry (call when the wallet opens): start Noise handshakes with connected helper peers so a
     * BLE session is READY before the user sends. The BLE handshake can take many seconds — far longer than
     * the send-time warm-up window — so doing it early lets an offline sender's relay take the mesh path
     * instead of falling back to Nostr. Fire-and-forget + best-effort; no-op when no session-less helper is
     * connected. See docs/dogecoin-offline-mesh-relay-findings.md.
     */
    fun prewarmBroadcastHelperSessions() {
        viewModelScope.launch {
            runCatching { warmUpMeshHelperSessions(currentDogecoinNetwork(), PEER_BROADCAST_SESSION_PREWARM_MS) }
        }
    }

    /**
     * Warm up Noise sessions with connected, session-LESS helper peers (mutual favorites or NODE_HELPER
     * advertisers) so a peer broadcast can ride BLE instead of diverting to Nostr. Noise sessions are lazy and
     * the broadcast send path (BluetoothMeshService.sendNoisePayloadToPeer) never initiates a handshake (unlike
     * sendPrivateMessage), so two announce-only peers stay session-less and the relay falls back to Nostr
     * (which needs internet). This initiates those handshakes up front and awaits them, bounded, returning
     * early once they establish; only incurred when such a peer is connected. Transport-only — no effect on tx
     * build/sign or corroboration-by-Noise-key. See docs/dogecoin-offline-mesh-relay-findings.md.
     */
    private suspend fun warmUpMeshHelperSessions(
        network: DogecoinNetwork,
        timeoutMs: Long = PEER_BROADCAST_SESSION_WARMUP_MS
    ) {
        val candidates = state.getConnectedPeersValue().filter { peerID ->
            peerID != mesh.myPeerID &&
                !mesh.hasEstablishedSession(peerID) &&
                isPotentialMeshHelper(peerID, network)
        }
        if (candidates.isEmpty()) return
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastInitiate = 0L
        while (System.currentTimeMillis() < deadline && candidates.any { !mesh.hasEstablishedSession(it) }) {
            val now = System.currentTimeMillis()
            // (Re)initiate for every peer still without a session. The first handshake packet can be dropped on a
            // flaky BLE link, so retry periodically within the window instead of relying on a single attempt;
            // initiating again on an in-progress/established session is a harmless no-op.
            if (now - lastInitiate >= PEER_BROADCAST_SESSION_REINITIATE_MS) {
                candidates.filter { !mesh.hasEstablishedSession(it) }
                    .forEach { peerID -> runCatching { mesh.initiateNoiseHandshake(peerID) } }
                lastInitiate = now
            }
            kotlinx.coroutines.delay(150)
        }
    }

    /** A connected peer that could broadcast for us: advertises NODE_HELPER for [network] or is a mutual
     *  favorite (mirrors the mesh-tier test in listBroadcastHelperCandidates, minus the session filter). */
    private fun isPotentialMeshHelper(peerID: String, network: DogecoinNetwork): Boolean {
        val info = mesh.getPeerInfo(peerID) ?: return false
        val key = info.noisePublicKey ?: return false
        val advertisesHelp = network.id in info.helperNetworks
        val mutual = runCatching {
            com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(key)?.isMutual == true
        }.getOrDefault(false)
        return advertisesHelp || mutual
    }
    
    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return meshDelegateHandler.decryptChannelMessage(encryptedContent, channel)
    }
    
    override fun getNickname(): String? {
        return meshDelegateHandler.getNickname()
    }
    
    override fun isFavorite(peerID: String): Boolean {
        return meshDelegateHandler.isFavorite(peerID)
    }
    
    // registerPeerPublicKey REMOVED - fingerprints now handled centrally in PeerManager
    
    // MARK: - Emergency Clear
    
    fun panicClearAllData() {
        Log.w(TAG, "🚨 PANIC MODE ACTIVATED - Clearing all sensitive data")
        
        // Clear all UI managers
        messageManager.clearAllMessages()
        channelManager.clearAllChannels()
        privateChatManager.clearAllPrivateChats()
        dataManager.clearAllData()
        
        // Clear seen message store
        try {
            com.bitchat.android.services.SeenMessageStore.getInstance(getApplication()).clear()
        } catch (_: Exception) { }
        
        // Clear all mesh service data
        clearAllMeshServiceData()
        
        // Clear all cryptographic data
        clearAllCryptographicData()
        
        // Clear all notifications
        notificationManager.clearAllNotifications()

        // Clear all media files
        com.bitchat.android.features.file.FileUtils.clearAllMedia(getApplication())
        
        // Clear Nostr/geohash state, keys, connections, bookmarks, and reinitialize from scratch
        try {
            // Clear geohash bookmarks too (panic should remove everything)
            try {
                val store = com.bitchat.android.geohash.GeohashBookmarksStore.getInstance(getApplication())
                store.clearAll()
            } catch (_: Exception) { }

            try {
                val locationManager = com.bitchat.android.geohash.LocationChannelManager.getInstance(getApplication())
                locationManager.clearPersistedChannel()
            } catch (_: Exception) { }

            geohashViewModel.panicReset()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset Nostr/geohash: ${e.message}")
        }

        // Reset nickname
        val newNickname = "anon${Random.nextInt(1000, 9999)}"
        state.setNickname(newNickname)
        dataManager.saveNickname(newNickname)
        
        // Recreate mesh service with fresh identity
        recreateMeshServiceAfterPanic()

        Log.w(TAG, "🚨 PANIC MODE COMPLETED - New identity: ${mesh.myPeerID}")
    }

    /**
     * Recreate the mesh service with a fresh identity after panic clear.
     * This ensures the new cryptographic keys are used for a new peer ID.
     */
    private fun recreateMeshServiceAfterPanic() {
        val oldPeerID = mesh.myPeerID

        // Clear the holder so getOrCreate() returns a fresh instance
        MeshServiceHolder.clear()

        // Create fresh mesh service with new identity (keys were regenerated in clearAllCryptographicData)
        val freshMeshService = MeshServiceHolder.getOrCreate(getApplication())
        val freshUnifiedMeshService = MeshServiceHolder.getUnifiedOrCreate(getApplication())

        // Replace our reference and set up the new service
        meshService = freshMeshService
        unifiedMeshService = freshUnifiedMeshService
        mesh.delegate = this

        // Restart mesh operations with new identity
        mesh.startServices()
        mesh.sendBroadcastAnnounce()

        Log.d(
            TAG,
            "✅ Mesh service recreated. Old peerID: $oldPeerID, New peerID: ${mesh.myPeerID}"
        )
    }
    
    /**
     * Clear all mesh service related data
     */
    private fun clearAllMeshServiceData() {
        try {
            // Request mesh service to clear all its internal data
            mesh.clearAllInternalData()
            
            Log.d(TAG, "✅ Cleared all mesh service data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing mesh service data: ${e.message}")
        }
    }
    
    /**
     * Clear all cryptographic data including persistent identity
     */
    private fun clearAllCryptographicData() {
        try {
            // Clear encryption service persistent identity (Ed25519 signing keys)
            mesh.clearAllEncryptionData()
            
            // Clear secure identity state (if used)
            try {
                val identityManager = SecureIdentityStateManager(getApplication())
                identityManager.clearIdentityData()
                // Also clear secure values used by FavoritesPersistenceService (favorites + peerID index)
                try {
                    identityManager.clearSecureValues("favorite_relationships", "favorite_peerid_index")
                } catch (_: Exception) { }
                Log.d(TAG, "✅ Cleared secure identity state and secure favorites store")
            } catch (e: Exception) {
                Log.d(TAG, "SecureIdentityStateManager not available or already cleared: ${e.message}")
            }

            // Clear FavoritesPersistenceService persistent relationships
            try {
                FavoritesPersistenceService.shared.clearAllFavorites()
                Log.d(TAG, "✅ Cleared FavoritesPersistenceService relationships")
            } catch (_: Exception) { }
            
            Log.d(TAG, "✅ Cleared all cryptographic data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing cryptographic data: ${e.message}")
        }
    }

    /**
     * Get participant count for a specific geohash (5-minute activity window)
     */
    fun geohashParticipantCount(geohash: String): Int {
        return geohashViewModel.geohashParticipantCount(geohash)
    }

    /**
     * Begin sampling multiple geohashes for participant activity
     */
    fun beginGeohashSampling(geohashes: List<String>) {
        geohashViewModel.beginGeohashSampling(geohashes)
    }

    /**
     * End geohash sampling
     */
    fun endGeohashSampling() {
        geohashViewModel.endGeohashSampling()
    }

    /**
     * Check if a geohash person is teleported (iOS-compatible)
     */
    fun isPersonTeleported(pubkeyHex: String): Boolean {
        return geohashViewModel.isPersonTeleported(pubkeyHex)
    }

    /**
     * Start geohash DM with pubkey hex (iOS-compatible)
     */
    fun startGeohashDM(pubkeyHex: String) {
        geohashViewModel.startGeohashDM(pubkeyHex) { convKey ->
            showPrivateChatSheet(convKey)
        }
    }

    fun startGeohashDMByNickname(nickname: String) {
        geohashViewModel.startGeohashDMByNickname(nickname) { convKey ->
            showPrivateChatSheet(convKey)
        }
    }

    fun startGeohashDMByShortId(shortId: String) {
        geohashViewModel.startGeohashDMByShortId(shortId) { convKey ->
            showPrivateChatSheet(convKey)
        }
    }

    fun selectLocationChannel(channel: com.bitchat.android.geohash.ChannelID) {
        geohashViewModel.selectLocationChannel(channel)
    }

    /**
     * Block a user in geohash channels by their nickname
     */
    fun blockUserInGeohash(targetNickname: String) {
        geohashViewModel.blockUserInGeohash(targetNickname)
    }

    // MARK: - Navigation Management
    
    fun showAppInfo() {
        state.setShowAppInfo(true)
    }
    
    fun hideAppInfo() {
        state.setShowAppInfo(false)
    }

    /**
     * Handle Android back navigation
     * Returns true if the back press was handled, false if it should be passed to the system
     */
    fun handleBackPressed(): Boolean {
        return when {
            // Close app info dialog
            state.getShowAppInfoValue() -> {
                hideAppInfo()
                true
            }
            // Close password dialog
            state.getShowPasswordPromptValue() -> {
                state.setShowPasswordPrompt(false)
                state.setPasswordPromptChannel(null)
                true
            }
            // Exit private chat
            state.getSelectedPrivateChatPeerValue() != null || state.getPrivateChatSheetPeerValue() != null -> {
                endPrivateChat()
                true
            }
            // Exit channel view
            state.getCurrentChannelValue() != null -> {
                switchToChannel(null)
                true
            }
            // No special navigation state - let system handle (usually exits app)
            else -> false
        }
    }

    // MARK: - iOS-Compatible Color System

    /**
     * Get consistent color for a mesh peer by ID (iOS-compatible)
     */
    fun colorForMeshPeer(peerID: String, isDark: Boolean): androidx.compose.ui.graphics.Color {
        // Try to get stable Noise key, fallback to peer ID
        val seed = "noise:${peerID.lowercase()}"
        return colorForPeerSeed(seed, isDark).copy()
    }

    /**
     * Get consistent color for a Nostr pubkey (iOS-compatible)
     */
    fun colorForNostrPubkey(pubkeyHex: String, isDark: Boolean): androidx.compose.ui.graphics.Color {
        return geohashViewModel.colorForNostrPubkey(pubkeyHex, isDark)
}

}
