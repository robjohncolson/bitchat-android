package com.bitchat.android.features.dogecoin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns the Dogecoin-only debug-console surface while delegating lifecycle-sensitive work back to
 * [com.bitchat.android.ui.ChatViewModel].
 */
internal class DogecoinDebugConsoleHandler(
    private val walletRepository: DogecoinWalletRepository,
    private val coroutineScope: CoroutineScope,
    private val spv: () -> DogecoinSpvService,
    private val requestPeerBroadcast: (DogecoinSignedTransaction) -> Unit,
    private val peerBroadcastState: StateFlow<PeerBroadcastUiState>,
    private val reannounceIdentity: () -> Unit,
) {
    private fun currentNetwork(): DogecoinNetwork = walletRepository.loadSelectedNetwork()

    /** Debug-only: build + sign a REAL Dogecoin tx for the console money-path commands (suspend; lists UTXOs). */
    private suspend fun debugBuildSignedDogeTx(
        net: DogecoinNetwork, to: String, amount: String, feePerKbKoinu: Long
    ): DogecoinSignedTransaction {
        val cfg = walletRepository.loadRpcConfig(net)
        val snap = walletRepository.loadOrCreateWallet(net)
        val rpc = DogecoinRpcClient()
        val utxos = rpc.listUnspent(cfg, snap.key.address, net)
        return DogecoinTransactionBuilder.createSignedTransaction(
            wallet = snap.key, utxos = utxos, recipientAddress = to, amount = amount,
            network = net, feePerKbKoinu = feePerKbKoinu,
            minimumOutputKoinu = DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU
        )
    }

    /** Debug-only: explorer client built from the saved provider + API key (see the doge-explorer-config command). */
    private fun debugExplorerClient() = DogecoinExplorerClient(
        provider = walletRepository.loadExplorerProvider(),
        apiKey = walletRepository.loadExplorerApiKey()
    )

    fun handleOrNull(cmd: String, args: List<String>): String? = when (cmd) {
        // ---- Dogecoin wallet (P0: drive a send / peer-broadcast from the console) ----
        "doge-network" -> {
            val net = DogecoinNetwork.values().firstOrNull { it.id == args.firstOrNull()?.lowercase() }
            if (net == null) "usage: doge-network mainnet|testnet|regtest" else {
                walletRepository.saveSelectedNetwork(net)
                "net=${walletRepository.loadSelectedNetwork().id}"
            }
        }
        "doge-rpc-set" -> {
            if (args.isEmpty()) "usage: doge-rpc-set <url> [user] [pass] [walletName]" else {
                val net = currentNetwork()
                val cfg = com.bitchat.android.features.dogecoin.DogecoinRpcConfig(
                    url = args.getOrElse(0) { "" }, username = args.getOrElse(1) { "" },
                    password = args.getOrElse(2) { "" }, walletName = args.getOrElse(3) { "" })
                walletRepository.saveRpcConfig(net, cfg)
                "saved rpc net=${net.id} url=${cfg.url} wallet=${cfg.walletName}"
            }
        }
        "doge-rpc-show" -> {
            val net = currentNetwork()
            val cfg = walletRepository.loadRpcConfig(net)
            coroutineScope.launch {
                runCatching {
                    val s = com.bitchat.android.features.dogecoin.DogecoinRpcClient().getBlockchainStatus(cfg, net)
                    android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-rpc-show ready=${s.isReadyFor(net)} canBroadcast=${s.canBroadcastFor(net)}")
                }.getOrElse { android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-rpc-show node ERR ${it.message}") }
            }
            "net=${net.id} url=${cfg.url} user=${cfg.username} wallet=${cfg.walletName} (node status -> DbgConsole)"
        }
        "doge-address" -> {
            val net = currentNetwork()
            "addr=${walletRepository.loadOrCreateWallet(net).key.address} net=${net.id}"
        }
        "doge-import-wif" -> {
            val wif = args.firstOrNull()
            val net = currentNetwork()
            when {
                wif == null -> "usage: doge-import-wif <wif>"
                net == DogecoinNetwork.MAINNET -> "refused: mainnet key import is console-blocked"
                else -> runCatching {
                    val snap = walletRepository.importWalletFromWif(net, wif)
                    "imported addr=${snap.key.address} net=${net.id}"   // never logs the WIF
                }.getOrElse { "import ERR ${it.message}" }
            }
        }
        "doge-balance" -> {
            val net = currentNetwork()
            val cfg = walletRepository.loadRpcConfig(net)
            val addr = walletRepository.loadOrCreateWallet(net).key.address
            coroutineScope.launch {
                runCatching {
                    val bal = com.bitchat.android.features.dogecoin.DogecoinRpcClient().getWalletBalance(cfg, addr, net)
                    android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-balance conf=${bal.confirmedKoinu} uncon=${bal.unconfirmedKoinu} utxos=${bal.utxoCount}")
                }.getOrElse { android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-balance ERR ${it.message}") }
            }
            "balance for ${addr.take(12)} net=${net.id} -> DbgConsole"
        }
        "doge-reset" -> {
            val net = currentNetwork()
            if (net == DogecoinNetwork.MAINNET) "refused: mainnet wallet reset is console-blocked (use doge-reset-mainnet <currentAddress> to confirm)"
            else {
                spv().clearPersistedState(net)   // stop + delete SPV store + stale wallet file
                val snap = walletRepository.resetWallet(net)
                "wallet reset net=${net.id} new addr=${snap.key.address} (fresh birthdate=now; run doge-spv-start)"
            }
        }
        "doge-reset-mainnet" -> {
            // Acknowledgment-gated mainnet key regeneration: the Phase 4 read-only soak needs a FRESH
            // mainnet key (birthdate=now) so SPV seeds at the recent checkpoint instead of genesis. This
            // DISCARDS the current mainnet key (irreversible — any funds at it become inaccessible from
            // this app), so it requires the caller to pass the EXACT current mainnet address as
            // confirmation. Debug-console only; never signs, exports a key, or broadcasts.
            val net = currentNetwork()
            val confirmAddr = args.getOrNull(0)?.trim()
            val tag = com.bitchat.android.debug.DebugConsole.TAG
            if (net != DogecoinNetwork.MAINNET) "refused: not on mainnet (use doge-reset for ${net.id})"
            else {
                val cur = walletRepository.loadOrCreateWallet(net).key.address
                when (confirmAddr) {
                    null -> "DANGER: discards the current mainnet key (irreversible; funds at it become inaccessible from this app). Current mainnet address = $cur — re-run: doge-reset-mainnet $cur"
                    cur -> {
                        android.util.Log.w(tag, "doge-reset-mainnet: DISCARDING the current mainnet key and generating a fresh one (confirmed)")
                        spv().clearPersistedState(net)
                        val snap = walletRepository.resetWallet(net)
                        "MAINNET wallet reset: new addr=${snap.key.address} (fresh birthdate=now). Fund THIS address, then doge-spv-start."
                    }
                    else -> "refused: confirmation address did not match the current mainnet address ($cur)"
                }
            }
        }
        "doge-spv-start" -> {
            val net = currentNetwork()
            if (!spv().isSupported(net)) "spv-start refused: not supported for ${net.id} (regtest has no peers)"
            else {
                val addr = walletRepository.loadOrCreateWallet(net).key.address
                spv().start(net)
                "spv starting net=${net.id} watch=${addr.take(12)} (sync-on-demand) -> doge-spv-status"
            }
        }
        "doge-spv-stop" -> { spv().stop(); "spv stopped" }
        "doge-spv-rescan" -> {
            val net = currentNetwork()
            spv().clearPersistedState(net)
            "spv state cleared for ${net.id} (keeps key); run doge-spv-start to rescan from the birthdate checkpoint"
        }
        "doge-spv-status" -> {
            val s = spv().status.value
            "spv net=${s.network.id} running=${s.running} synced=${s.synced} overTor=${s.overTor} height=${s.chainHeight} " +
                "peers=${s.peerCount} bestPeer=${s.bestPeerHeight} behind=${s.blocksBehind} stalled=${s.stalled}"
        }
        "doge-spv-balance" -> {
            val net = currentNetwork()
            val addr = walletRepository.loadOrCreateWallet(net).key.address
            coroutineScope.launch {
                runCatching {
                    val bal = spv().snapshotBalance(net)
                        ?: error("spv not active for ${net.id} (run doge-spv-start first)")
                    android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-spv-balance avail=${bal.confirmedKoinu} uncon=${bal.unconfirmedKoinu} utxos=${bal.utxoCount}")
                }.getOrElse { android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-spv-balance ERR ${it.message}") }
            }
            "spv balance for ${addr.take(12)} net=${net.id} -> DbgConsole"
        }
        "doge-spv-unspents" -> {
            val net = currentNetwork()
            coroutineScope.launch {
                runCatching {
                    val utxos = spv().snapshotUnspents(net)
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
            val net = currentNetwork()
            val addr = walletRepository.loadOrCreateWallet(net).key.address
            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val tag = com.bitchat.android.debug.DebugConsole.TAG
                runCatching {
                    val spvUtxos = spv().snapshotUnspents(net)
                        ?: error("spv not active for ${net.id} (run doge-spv-start first)")
                    if (spvUtxos.isEmpty()) {
                        android.util.Log.d(tag, "doge-spv-crosscheck net=${net.id}: SPV has 0 UTXOs (nothing to check)")
                    } else {
                        val rpcConfig = walletRepository.loadRpcConfig(net)
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
            val net = currentNetwork()
            when {
                to == null || amt == null -> "usage: doge-spv-broadcast <addr> <amountDoge> [feePerKbKoinu]"
                net == DogecoinNetwork.MAINNET -> "refused: mainnet broadcast is console-blocked"
                else -> {
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val snap = walletRepository.loadOrCreateWallet(net)
                            // Node-free: build + sign from the SPV UTXO view, then relay via SPV peers.
                            val utxos = spv().snapshotUnspents(net)
                                ?: error("spv not active for ${net.id} (run doge-spv-start first)")
                            val signed = com.bitchat.android.features.dogecoin.DogecoinTransactionBuilder.createSignedTransaction(
                                wallet = snap.key, utxos = utxos, recipientAddress = to, amount = amt,
                                network = net, feePerKbKoinu = feeKb,
                                minimumOutputKoinu = com.bitchat.android.features.dogecoin.DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU
                            )
                            android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-spv-broadcast signed txid=${signed.txid} fee=${signed.feeKoinu} change=${signed.changeKoinu}")
                            val txid = com.bitchat.android.features.dogecoin.DogecoinSpvDataSource(spv())
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
            val net = currentNetwork()
            val tag = com.bitchat.android.debug.DebugConsole.TAG
            when {
                net != DogecoinNetwork.MAINNET -> "refused: doge-spv-mainnet-send is mainnet-only (use doge-spv-broadcast on ${net.id})"
                to == null || amt == null || (mode != "DRYRUN" && mode != "CONFIRM") ->
                    "usage: doge-spv-mainnet-send <addr> <amountDoge> <DRYRUN|CONFIRM> [feeKb] — DRYRUN shows the built tx, CONFIRM broadcasts (IRREVERSIBLE real money)"
                else -> {
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val snap = walletRepository.loadOrCreateWallet(net)
                            val utxos = spv().snapshotUnspents(net) ?: error("spv not active (run doge-spv-start)")
                            val signed = com.bitchat.android.features.dogecoin.DogecoinTransactionBuilder.createSignedTransaction(
                                wallet = snap.key, utxos = utxos, recipientAddress = to, amount = amt,
                                network = net, feePerKbKoinu = feeKb,
                                minimumOutputKoinu = com.bitchat.android.features.dogecoin.DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU
                            )
                            android.util.Log.d(tag, "doge-spv-mainnet-send[$mode] BUILT txid=${signed.txid} send=${amt} to=${to} fee=${signed.feeKoinu}k change=${signed.changeKoinu}k")
                            if (mode == "CONFIRM") {
                                val normalized = com.bitchat.android.features.dogecoin.DogecoinRawTxValidator.normalize(signed.rawTransactionHex)
                                val expectedTxid = com.bitchat.android.features.dogecoin.DogecoinTransactionBuilder.transactionId(normalized)
                                val txid = spv().broadcast(net, normalized, expectedTxid, mainnetAuthorized = true)
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
            val net = currentNetwork()
            when {
                to == null || amt == null -> "usage: doge-self-broadcast <addr> <amountDoge> [feePerKbKoinu]"
                net == DogecoinNetwork.MAINNET -> "refused: mainnet broadcast is console-blocked"
                else -> {
                    coroutineScope.launch {
                        runCatching {
                            val cfg = walletRepository.loadRpcConfig(net)
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
            val net = currentNetwork()
            when {
                to == null || amt == null -> "usage: doge-peer-broadcast <addr> <amountDoge> [feePerKbKoinu]"
                net == DogecoinNetwork.MAINNET -> "refused: mainnet broadcast is console-blocked"
                else -> {
                    coroutineScope.launch {
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
            val net = currentNetwork()
            when {
                to == null || amt == null -> "usage: doge-spv-peer-broadcast <addr> <amountDoge> [feePerKbKoinu]"
                net == DogecoinNetwork.MAINNET -> "refused: mainnet broadcast is console-blocked"
                else -> {
                    coroutineScope.launch {
                        val signed = runCatching {
                            // OFFLINE build: sign from the PERSISTED SPV UTXO set (no internet needed on this
                            // phone); only the mesh helper needs connectivity to actually broadcast.
                            val snap = walletRepository.loadOrCreateWallet(net)
                            val utxos = spv().snapshotUnspents(net) ?: error("spv not active (run doge-spv-start)")
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
                val net = currentNetwork()
                walletRepository.saveHelperEnabled(net, on)
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
                walletRepository.saveExplorerProvider(p)
                args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { walletRepository.saveExplorerApiKey(it) }
                "explorer provider=$p keySet=${walletRepository.loadExplorerApiKey() != null}"  // key never echoed
            }
        }
        "doge-explorer-balance" -> {
            val net = currentNetwork()
            val addr = args.firstOrNull() ?: walletRepository.loadOrCreateWallet(net).key.address
            coroutineScope.launch {
                runCatching {
                    val bal = debugExplorerClient().getBalance(addr, net)
                    android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-explorer-balance conf=${bal.confirmedKoinu} uncon=${bal.unconfirmedKoinu} utxos=${bal.utxoCount}")
                }.getOrElse { android.util.Log.d(com.bitchat.android.debug.DebugConsole.TAG, "doge-explorer-balance ERR ${it.message}") }
            }
            "explorer-balance ${addr.take(12)} net=${net.id} -> DbgConsole"
        }
        "doge-explorer-utxos" -> {
            val net = currentNetwork()
            val addr = args.firstOrNull() ?: walletRepository.loadOrCreateWallet(net).key.address
            coroutineScope.launch {
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
            val net = currentNetwork()
            when {
                raw == null -> "usage: doge-explorer-broadcast <rawTxHex>"
                net == DogecoinNetwork.MAINNET -> "refused: mainnet broadcast is console-blocked"
                else -> {
                    coroutineScope.launch {
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
            val net = currentNetwork()
            when {
                to == null || amt == null -> "usage: doge-explorer-send <addr> <amountDoge> [feePerKbKoinu]"
                net == DogecoinNetwork.MAINNET -> "refused: mainnet broadcast is console-blocked"
                else -> {
                    coroutineScope.launch {
                        runCatching {
                            val explorer = debugExplorerClient()
                            val snap = walletRepository.loadOrCreateWallet(net)
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
        else -> null
    }
}
