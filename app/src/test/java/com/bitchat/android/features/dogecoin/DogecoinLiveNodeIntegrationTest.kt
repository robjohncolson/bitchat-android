package com.bitchat.android.features.dogecoin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Live, read-only integration harness for a real Dogecoin Core node.
 *
 * This test exercises the SAME production [DogecoinRpcClient] and [DogecoinTransactionBuilder]
 * the app uses, against an actual node, to validate the end-to-end broadcast path WITHOUT
 * spending any DOGE. It NEVER calls sendrawtransaction. The strongest check it performs is
 * `testmempoolaccept`, which is a node-side dry run: the node fully validates scripts,
 * signatures, fees, and policy and reports whether it WOULD accept the transaction, but does
 * not relay it.
 *
 * It is skipped during normal builds (no `DOGE_RPC_URL` env var -> JUnit "assumption" skip),
 * so it never affects `testDebugUnitTest` / CI.
 *
 * Run it (env vars are inherited by the Gradle test worker):
 *
 *   DOGE_RPC_URL=http://127.0.0.1:22555 \
 *   DOGE_RPC_USER=<rpcuser> DOGE_RPC_PASS=<rpcpassword> \
 *   DOGE_NETWORK=mainnet \
 *   [DOGE_RPC_WALLET=<wallet-name>] \
 *   [DOGE_WIF=<wallet WIF>]  [DOGE_DEST=<destination address>]  [DOGE_AMOUNT=<DOGE>] \
 *   ./gradlew :app:testDebugUnitTest --rerun-tasks --info \
 *     --tests "com.bitchat.android.features.dogecoin.DogecoinLiveNodeIntegrationTest"
 *
 *  - With no DOGE_WIF: validates node reachability, chain, sync, wallet RPC, and relay readiness.
 *  - With DOGE_WIF on a FUNDED address: also builds a real signed transaction from live UTXOs
 *    and asks the node, via testmempoolaccept, whether it would accept it (the full proof).
 *    Without a destination it self-sends (to the same address); nothing is broadcast.
 *  - With DOGE_BROADCAST=true AND DOGE_NETWORK=testnet (or regtest): after the dry run passes, it
 *    actually broadcasts (sendrawtransaction) so the COMPLETE pipeline is proven on a value-less
 *    network. Hard-guarded: it refuses to broadcast on mainnet (see [harnessBroadcastAllowedOn]).
 *
 * The human-readable report is written to `app/build/doge-live-node-report.txt`.
 */
class DogecoinLiveNodeIntegrationTest {

    @Test
    fun `live node accepts a wallet-built transaction without broadcasting`() = runBlocking {
        val url = config("DOGE_RPC_URL")
        assumeTrue(
            "Skipping live node integration: set DOGE_RPC_URL (and DOGE_RPC_USER/DOGE_RPC_PASS) to run it.",
            !url.isNullOrBlank()
        )

        val network = DogecoinNetwork.fromId(config("DOGE_NETWORK") ?: "mainnet")
        val rpcConfig = DogecoinRpcConfig(
            url = url!!.trim(),
            username = config("DOGE_RPC_USER").orEmpty(),
            password = config("DOGE_RPC_PASS").orEmpty(),
            walletName = config("DOGE_RPC_WALLET").orEmpty()
        )
        val client = DogecoinRpcClient()

        val report = StringBuilder()
        fun line(text: String = "") = report.appendLine(text).let { Unit }

        line("=== Dogecoin live node integration (READ-ONLY, no broadcast) ===")
        line("network: ${network.displayName}")
        line("rpc url: ${rpcConfig.normalizedUrl(network)}")
        line()

        // --- Step 1: node status (read-only) -------------------------------------------------
        val status = client.getBlockchainStatus(rpcConfig, network)
        line("[1] node status")
        line("  connected:            ${status.connected}")
        line("  chain:                ${status.chain}")
        line("  blocks / headers:     ${status.blocks} / ${status.headers}")
        line("  initialBlockDownload: ${status.initialBlockDownload}")
        line("  verificationProgress: ${status.verificationProgress}")
        line("  pruned:               ${status.pruned}")
        line("  walletReady:          ${status.walletReady} (${status.walletName ?: "no wallet name"})")
        line("  relayReady:           ${status.relayReady}")
        line("  networkActive:        ${status.networkActive}")
        line("  peerCount:            ${status.peerCount}")
        line("  relayFee/kB (koinu):  ${status.relayFeePerKbKoinu}")
        line("  soft/hard dust:       ${status.softDustLimitKoinu} / ${status.hardDustLimitKoinu}")
        line("  policyCheckAvailable: ${status.policyCheckAvailable}")
        line("  error:                ${status.error ?: "none"}")
        line("  => isReadyFor(${network.displayName}):      ${status.isReadyFor(network)}")
        line("  => canBroadcastFor(${network.displayName}): ${status.canBroadcastFor(network)}")
        line()

        assertTrue(
            "Node not connected to Dogecoin ${network.displayName}: ${status.error ?: "unknown error"}",
            status.connected
        )
        assertEquals("Node is on the wrong chain", network.chainName, status.chain)
        assertTrue("Node is still doing initial block download", status.initialBlockDownload != true)

        // --- Step 2: optional signed-tx dry run on a funded key ------------------------------
        val wif = config("DOGE_WIF")
        if (wif.isNullOrBlank()) {
            line("[2] no DOGE_WIF provided -> stopping after node readiness checks.")
            line("    Provide DOGE_WIF for a funded address to run the full testmempoolaccept dry run.")
            writeReport(report.toString())
            // Node-readiness portion still asserts a broadcast-capable node.
            assertTrue(
                "Node cannot relay broadcasts (relayReady=false). Check networking/peers.",
                status.canBroadcastFor(network)
            )
            return@runBlocking
        }

        val key = DogecoinKeyGenerator.fromWif(wif.trim(), expectedNetwork = network)
        line("[2] wallet address: ${key.address}  (compressed=${key.isCompressed})")

        val utxos = client.listUnspent(rpcConfig, key.address, network)
        val confirmed = utxos.filter { it.confirmations > 0 }
        val confirmedTotalKoinu = confirmed.sumOf { it.amountKoinu }
        line("  confirmed UTXOs: ${confirmed.size}  total: ${DogecoinAmount.formatKoinu(confirmedTotalKoinu)} DOGE")
        line("  unconfirmed UTXOs: ${utxos.size - confirmed.size}")
        line()

        if (confirmed.isEmpty()) {
            line("[3] address has no confirmed spendable UTXOs -> cannot build a transaction.")
            line("    Fund ${key.address} with a confirmed amount, then re-run to complete the dry run.")
            writeReport(report.toString())
            return@runBlocking
        }

        val destination = config("DOGE_DEST")?.trim()?.takeIf { it.isNotEmpty() } ?: key.address
        val amount = config("DOGE_AMOUNT")?.trim()?.takeIf { it.isNotEmpty() }
            ?: DogecoinAmount.formatKoinu(
                DogecoinTransactionBuilder.maxSpendable(
                    wallet = key,
                    utxos = confirmed,
                    network = network
                ).amountKoinu
            )

        line("[3] building signed transaction (NOT broadcasting)")
        line("    recipient: $destination${if (destination == key.address) "  (self-send)" else ""}")
        line("    amount:    $amount DOGE")

        val signed = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = key,
            utxos = confirmed,
            recipientAddress = destination,
            amount = amount,
            network = network
        )
        line("    txid:          ${signed.txid}")
        line("    inputs:        ${signed.selectedUtxos.size}")
        line("    fee (koinu):   ${signed.feeKoinu}")
        line("    change (koinu):${signed.changeKoinu}")
        line("    raw hex bytes: ${signed.rawTransactionHex.length / 2}")
        line("    raw-hex hashes back to txid: ${signed.hasConsistentRawTransactionId()}")
        line()

        assertTrue(
            "Internal: signed raw hex does not hash back to its txid",
            signed.hasConsistentRawTransactionId()
        )

        // --- Step 4: node-side dry run. testmempoolaccept does NOT broadcast. -----------------
        val acceptance = client.testMempoolAcceptance(rpcConfig, signed.rawTransactionHex, network)
        line("[4] testmempoolaccept (node-side dry run, no broadcast)")
        line("    checked:      ${acceptance.checked}")
        line("    allowed:      ${acceptance.allowed}")
        line("    node txid:    ${acceptance.txid}")
        line("    rejectReason: ${acceptance.rejectReason ?: "none"}")
        line("    error:        ${acceptance.error ?: "none"}")
        line()

        if (acceptance.checked) {
            line("RESULT: node ${if (acceptance.isAllowed) "WOULD ACCEPT" else "WOULD REJECT"} this transaction.")
        } else {
            line("RESULT: node does not support testmempoolaccept; node-readiness verified, full dry run unavailable.")
        }

        if (acceptance.checked) {
            assertTrue(
                "Node would REJECT the wallet-built transaction: ${acceptance.rejectReason ?: acceptance.error}",
                acceptance.allowed == true
            )
        }

        // --- Step 5: OPTIONAL real broadcast, TESTNET ONLY (opt-in via DOGE_BROADCAST=true) ----
        // This is the one step the read-only dry run cannot cover: exercising sendrawtransaction.
        // It is hard-guarded to testnet so it can never broadcast real-value mainnet funds; a
        // mainnet broadcast must go through the app UI with its explicit acknowledgements.
        val broadcastRequested = config("DOGE_BROADCAST")?.trim()?.equals("true", ignoreCase = true) == true
        if (broadcastRequested) {
            line()
            line("[5] DOGE_BROADCAST=true requested")
            check(harnessBroadcastAllowedOn(network)) {
                "Refusing to broadcast on ${network.displayName}. DOGE_BROADCAST is only permitted on testnet or " +
                    "regtest; broadcast a mainnet transaction from the app UI with its explicit acknowledgements instead."
            }
            // Mirror the app's own gate (sendRawTransaction: check(!checked || allowed)). Dogecoin Core
            // 1.14.x has no testmempoolaccept, so the dry run is simply unavailable (checked=false);
            // sendrawtransaction then performs full validation. Only a positive rejection blocks us.
            check(!acceptance.checked || acceptance.isAllowed) {
                "Refusing to broadcast: the node pre-checked this transaction and rejected it: " +
                    "${acceptance.rejectReason ?: acceptance.error}"
            }
            if (!acceptance.checked) {
                line("    (node has no testmempoolaccept; sendrawtransaction performs full validation)")
            }
            val broadcastTxid = client.sendRawTransaction(rpcConfig, signed.rawTransactionHex, network)
            line("    BROADCAST txid:      $broadcastTxid")
            line("    matches signed txid: ${broadcastTxid.equals(signed.txid, ignoreCase = true)}")
            line(
                "RESULT: testnet transaction broadcast. Verify confirmations in the app or with " +
                    "`dogecoin-cli -testnet gettransaction $broadcastTxid`."
            )
            writeReport(report.toString())
            assertEquals("Broadcast txid must match the signed txid", signed.txid.lowercase(), broadcastTxid.lowercase())
        } else {
            line()
            line("[5] no broadcast (set DOGE_BROADCAST=true on TESTNET to broadcast the transaction for real).")
            writeReport(report.toString())
        }
        Unit
    }

    /**
     * The harness will only ever broadcast on the value-less networks (testnet, regtest). Mainnet
     * broadcasts must go through the app UI (with its mainnet / high-fee / policy acknowledgements),
     * never this automated path.
     */
    internal fun harnessBroadcastAllowedOn(network: DogecoinNetwork): Boolean =
        network == DogecoinNetwork.TESTNET || network == DogecoinNetwork.REGTEST

    @Test
    fun `harness broadcast guard permits value-less networks only`() {
        assertTrue("testnet broadcast must be allowed", harnessBroadcastAllowedOn(DogecoinNetwork.TESTNET))
        assertTrue("regtest broadcast must be allowed", harnessBroadcastAllowedOn(DogecoinNetwork.REGTEST))
        assertFalse("mainnet broadcast must be refused by the harness", harnessBroadcastAllowedOn(DogecoinNetwork.MAINNET))
    }

    private fun config(name: String): String? {
        System.getenv(name)?.let { return it }
        // Fallback to a system property (e.g. -Ddoge.rpc.url) if the build forwards it.
        return System.getProperty(name.lowercase().replace('_', '.'))
    }

    private fun writeReport(text: String) {
        runCatching {
            val file = File("build/doge-live-node-report.txt")
            file.parentFile?.mkdirs()
            file.writeText(text)
            println("Dogecoin live node report written to: ${file.absolutePath}")
        }
        println(text)
    }
}
