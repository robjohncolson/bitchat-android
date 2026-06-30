package com.bitchat.android.features.dogecoin

import com.bitchat.android.net.OkHttpProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Which public explorer API shape to speak. */
enum class DogecoinExplorerProvider { BLOCKBOOK, BLOCKCHAIR }

/**
 * Public block-explorer–backed Dogecoin data source — lets the wallet read UTXOs/balance and broadcast a
 * signed transaction through an explorer's HTTP API instead of a personal Dogecoin Core RPC node ("no-node"
 * mode). Build+sign stays fully on-device; only [listUtxos] (to fund a send) and [broadcast] (to publish it)
 * hit the network.
 *
 * Two provider shapes are supported:
 *  - [DogecoinExplorerProvider.BLOCKBOOK] (default) — Trezor's Blockbook v2 API, FREE + keyless; built-in
 *    mainnet base is a public Trezor Dogecoin instance. Gives confirmations directly; no per-UTXO script
 *    (derived from the P2PKH address).
 *  - [DogecoinExplorerProvider.BLOCKCHAIR] — Blockchair dashboards/push; the free tier is IP-rate-limited
 *    and effectively needs an `?key=` API key, so it's an opt-in alternative.
 *
 * Blockbook/Blockchair have NO Dogecoin testnet, so testnet/regtest are user-configurable via
 * [baseUrlProvider] and otherwise unsupported. Requests go through [OkHttpProvider] (honor Tor when on);
 * the URL policy mirrors [ExplorerTxConfirmationChecker] (HTTPS to public hosts; plaintext only to private).
 */
class DogecoinExplorerClient(
    private val provider: DogecoinExplorerProvider = DogecoinExplorerProvider.BLOCKBOOK,
    private val baseUrlProvider: (DogecoinNetwork) -> String? = { defaultBaseUrl(provider, it) },
    private val httpClient: OkHttpClient = OkHttpProvider.httpClient(),
    /** Blockchair API key (its free tier is IP-rate-limited without one). Appended as `?key=`; never logged. */
    private val apiKey: String? = null
) {
    /** `?key=<apiKey>` for Blockchair when a key is set (Blockbook is keyless), else empty. */
    private fun keySuffix(): String =
        if (provider == DogecoinExplorerProvider.BLOCKCHAIR && !apiKey.isNullOrBlank()) "?key=$apiKey" else ""
    /** UTXOs for [address] via the explorer, shaped exactly like the RPC `listunspent` result. */
    suspend fun listUtxos(address: String, network: DogecoinNetwork): List<DogecoinUtxo> = withContext(Dispatchers.IO) {
        val base = requireBaseUrl(network)
        when (provider) {
            DogecoinExplorerProvider.BLOCKBOOK -> {
                val body = httpGet("$base/api/v2/utxo/$address")
                parseBlockbookUtxos(body, address, network)
            }
            DogecoinExplorerProvider.BLOCKCHAIR -> {
                val body = httpGet("$base/dashboards/address/$address${keySuffix()}")
                parseBlockchairAddress(body, address, network).first
            }
        }
    }

    /** Confirmed/unconfirmed balance + UTXOs for [address] via the explorer. */
    suspend fun getBalance(address: String, network: DogecoinNetwork): DogecoinWalletBalance = withContext(Dispatchers.IO) {
        when (provider) {
            DogecoinExplorerProvider.BLOCKBOOK -> {
                val utxos = listUtxos(address, network)
                DogecoinWalletBalance(
                    confirmedKoinu = utxos.filter { it.confirmations > 0 }.sumOf { it.amountKoinu },
                    unconfirmedKoinu = utxos.filter { it.confirmations <= 0 }.sumOf { it.amountKoinu },
                    utxoCount = utxos.size,
                    utxos = utxos
                )
            }
            DogecoinExplorerProvider.BLOCKCHAIR -> {
                val base = requireBaseUrl(network)
                val (utxos, balance) = parseBlockchairAddress(httpGet("$base/dashboards/address/$address${keySuffix()}"), address, network)
                balance ?: DogecoinWalletBalance(
                    confirmedKoinu = utxos.filter { it.confirmations > 0 }.sumOf { it.amountKoinu },
                    unconfirmedKoinu = utxos.filter { it.confirmations <= 0 }.sumOf { it.amountKoinu },
                    utxoCount = utxos.size, utxos = utxos
                )
            }
        }
    }

    /** Broadcast a signed raw transaction via the explorer; returns the explorer's txid. */
    suspend fun broadcast(rawTransactionHex: String, network: DogecoinNetwork): String = withContext(Dispatchers.IO) {
        val base = requireBaseUrl(network)
        val raw = rawTransactionHex.trim().lowercase()
        when (provider) {
            DogecoinExplorerProvider.BLOCKBOOK -> {
                // Blockbook accepts the raw hex in the URL (GET) or as the POST body; POST avoids URL length limits.
                val url = "$base/api/v2/sendtx/"
                requireAllowed(url)
                val text = exec(Request.Builder().url(url).post(raw.toRequestBody()).build())
                parseBlockbookSendResult(text) ?: throw IllegalStateException("Explorer broadcast returned no txid: ${text.take(200)}")
            }
            DogecoinExplorerProvider.BLOCKCHAIR -> {
                val url = "$base/push/transaction${keySuffix()}"
                requireAllowed(url)
                val form = FormBody.Builder().add("data", raw).build()
                val text = exec(Request.Builder().url(url).post(form).build())
                parseBlockchairBroadcastTxid(text) ?: throw IllegalStateException("Explorer broadcast returned no txid: ${text.take(200)}")
            }
        }
    }

    private fun httpGet(url: String): String {
        requireAllowed(url)
        return exec(Request.Builder().url(url).get().build())
    }

    private fun exec(request: Request): String {
        // Public explorers commonly sit behind anti-bot/CDN layers that reject clients with no/odd headers.
        // Send a realistic browser UA + Accept so plain API requests are not summarily 403'd.
        val withHeaders = request.newBuilder()
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json, text/plain, */*")
            .build()
        return httpClient.newCall(withHeaders).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            check(resp.isSuccessful) { "Explorer HTTP ${resp.code}: ${text.take(200)}" }
            text
        }
    }

    private fun requireAllowed(url: String) = require(ExplorerTxConfirmationChecker.isAllowedExplorerUrl(url)) {
        "Explorer URL must be HTTPS (or a private host)."
    }

    private fun requireBaseUrl(network: DogecoinNetwork): String =
        baseUrlProvider(network)?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "No explorer configured for ${network.displayName} (no public Dogecoin testnet explorer; set a custom one)."
            )

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 12; Pixel 3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        /** Public Trezor Blockbook Dogecoin MAINNET instance (free, keyless). */
        const val BLOCKBOOK_MAINNET_BASE = "https://doge1.trezor.io"
        /** Blockchair Dogecoin MAINNET base (free tier IP-limited; effectively needs ?key=). */
        const val BLOCKCHAIR_MAINNET_BASE = "https://api.blockchair.com/dogecoin"

        fun defaultBaseUrl(provider: DogecoinExplorerProvider, network: DogecoinNetwork): String? =
            if (network != DogecoinNetwork.MAINNET) null else when (provider) {
                DogecoinExplorerProvider.BLOCKBOOK -> BLOCKBOOK_MAINNET_BASE
                DogecoinExplorerProvider.BLOCKCHAIR -> BLOCKCHAIR_MAINNET_BASE
            }

        // ---- Blockbook v2 parsers (pure) ----

        /**
         * Parse a Blockbook `/api/v2/utxo/{addr}` response (a JSON array) into UTXOs. Blockbook gives
         * `value` as a koinu STRING, `confirmations` directly, and no per-UTXO script — so the P2PKH script
         * is derived from [address]. Entries failing validation are skipped.
         */
        fun parseBlockbookUtxos(body: String, address: String, network: DogecoinNetwork): List<DogecoinUtxo> {
            val arr = runCatching { JsonParser.parseString(body) }.getOrNull()?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return emptyList()
            val script = runCatching { DogecoinHex.encode(DogecoinAddress.p2pkhScript(address, network)) }.getOrNull()
                ?: return emptyList()
            return arr.mapNotNull { el ->
                val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val txid = o.get("txid")?.asString?.takeIf { it.length == 64 } ?: return@mapNotNull null
                val vout = runCatching { o.get("vout").asInt }.getOrNull() ?: return@mapNotNull null
                val value = runCatching { o.get("value").asString.toLong() }.getOrNull()?.takeIf { it > 0L } ?: return@mapNotNull null
                val confs = runCatching { o.get("confirmations").asInt }.getOrDefault(0).coerceAtLeast(0)
                DogecoinUtxo(txid = txid, vout = vout, amountKoinu = value, scriptPubKeyHex = script, confirmations = confs)
            }
        }

        /** Parse a Blockbook `/api/v2/sendtx` response -> the broadcast txid (64-hex), or null. */
        fun parseBlockbookSendResult(body: String): String? {
            val root = runCatching { JsonParser.parseString(body) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return null
            // Success: { "result": "<txid>" }. Error: { "error": { "message": "..." } }.
            root.get("result")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase()
                ?.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }
                ?.let { return it }
            return null
        }

        // ---- Blockchair parsers (pure) ----

        /**
         * Parse a Blockchair address-dashboard response into (UTXOs, balance). Blockchair shape:
         * { "data": { "<addr>": { "address": {...}, "utxo": [ {block_id, transaction_hash, index, value,
         * script_hex}, ... ] } }, "context": { "state": <currentHeight> } }. value is koinu; confirmations
         * derived from (currentHeight - block_id + 1); block_id <= 0 is mempool (0 conf).
         */
        fun parseBlockchairAddress(
            body: String,
            address: String,
            network: DogecoinNetwork
        ): Pair<List<DogecoinUtxo>, DogecoinWalletBalance?> {
            val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                ?: return emptyList<DogecoinUtxo>() to null
            val state = runCatching { root.getAsJsonObject("context").get("state").asLong }.getOrDefault(0L)
            val data = root.getAsJsonObject("data") ?: return emptyList<DogecoinUtxo>() to null
            val entry = (data.getAsJsonObject(address)
                ?: data.entrySet().firstOrNull()?.value?.takeIf { it.isJsonObject }?.asJsonObject)
                ?: return emptyList<DogecoinUtxo>() to null
            val derivedScript = runCatching { DogecoinHex.encode(DogecoinAddress.p2pkhScript(address, network)) }.getOrNull()

            val utxos = entry.getAsJsonArray("utxo")?.mapNotNull { el ->
                val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val txid = o.get("transaction_hash")?.asString?.takeIf { it.length == 64 } ?: return@mapNotNull null
                val vout = runCatching { o.get("index").asInt }.getOrNull() ?: return@mapNotNull null
                val value = runCatching { o.get("value").asLong }.getOrNull()?.takeIf { it > 0L } ?: return@mapNotNull null
                val script = o.get("script_hex")?.asString?.takeIf { it.isNotBlank() } ?: derivedScript ?: return@mapNotNull null
                val blockId = runCatching { o.get("block_id").asLong }.getOrDefault(-1L)
                val confs = if (blockId <= 0L || state <= 0L) 0 else (state - blockId + 1L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                DogecoinUtxo(txid = txid, vout = vout, amountKoinu = value, scriptPubKeyHex = script, confirmations = confs)
            } ?: emptyList()

            val addrObj = entry.getAsJsonObject("address")
            val balance = if (addrObj != null) DogecoinWalletBalance(
                confirmedKoinu = runCatching { addrObj.get("balance").asLong }.getOrNull()?.coerceAtLeast(0L) ?: utxos.sumOf { it.amountKoinu },
                unconfirmedKoinu = runCatching { addrObj.get("unconfirmed_balance").asLong }.getOrDefault(0L).coerceAtLeast(0L),
                utxoCount = runCatching { addrObj.get("utxo_count").asInt }.getOrNull() ?: utxos.size,
                utxos = utxos
            ) else null
            return utxos to balance
        }

        /** Parse a Blockchair push/transaction response -> the broadcast txid (64-hex), or null. */
        fun parseBlockchairBroadcastTxid(body: String): String? {
            val root = runCatching { JsonParser.parseString(body) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val data = root.get("data") ?: return null
            if (data.isJsonObject) {
                data.asJsonObject.get("transaction_hash")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.lowercase()?.takeIf { it.length == 64 }?.let { return it }
            }
            if (data.isJsonPrimitive) {
                data.asString.lowercase().takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }?.let { return it }
            }
            return null
        }
    }
}
