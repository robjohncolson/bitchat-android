package com.bitchat.android.features.dogecoin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Independent on-chain corroboration for a peer-broadcast txid (Milestone 3b.1).
 *
 * When only ONE helper claims to have broadcast a node-less sender's transaction, the sender cannot
 * verify chain inclusion from its own (unreachable / relay-down) node, so the coordinator surfaces a
 * [PaymentBroadcastCoordinator.Outcome.Claimed]. An external block explorer is a *second, independent*
 * witness: if it can see the txid (in its mempool or a block), that is real evidence the helper actually
 * broadcast it, which upgrades Claimed -> Confirmed.
 *
 * Privacy: querying a public explorer reveals to that third party that this device is interested in this
 * txid. The feature is therefore opt-in / default-off (see [DogecoinWalletRepository]).
 */
interface DogecoinTxConfirmationChecker {
    /**
     * Whether [txid] is observable on a public block explorer for [network].
     *  - true  -> seen on-chain (mempool or block): independent corroboration.
     *  - false -> explorer reachable and authoritatively reports the tx is absent.
     *  - null  -> no explorer configured for this network, unreachable, or an unrecognized/ambiguous
     *             response. The caller MUST NOT treat null as corroboration.
     */
    suspend fun isOnChain(txid: String, network: DogecoinNetwork): Boolean?
}

/**
 * [DogecoinTxConfirmationChecker] backed by an HTTP block explorer. The per-network URL template comes
 * from [urlTemplateProvider] (mainnet defaults to Blockchair; other networks are user-configurable and
 * default to none). The template must contain the [TXID_PLACEHOLDER] token, which is substituted with the
 * lowercase txid.
 */
class ExplorerTxConfirmationChecker(
    private val urlTemplateProvider: (DogecoinNetwork) -> String?,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
) : DogecoinTxConfirmationChecker {

    override suspend fun isOnChain(txid: String, network: DogecoinNetwork): Boolean? = withContext(Dispatchers.IO) {
        val normalizedTxid = txid.trim().lowercase()
        if (!TXID_REGEX.matches(normalizedTxid)) return@withContext null

        val template = urlTemplateProvider(network)?.trim()?.takeIf { it.isNotBlank() } ?: return@withContext null
        if (!template.contains(TXID_PLACEHOLDER)) return@withContext null
        val url = template.replace(TXID_PLACEHOLDER, normalizedTxid)
        // Mirror the wallet's URL policy: HTTPS to public hosts, plaintext HTTP only to local/private
        // addresses, so corroboration never leaks a txid interest over cleartext to a public explorer.
        if (!isAllowedExplorerUrl(url)) return@withContext null

        try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 404) return@use false // explorer authoritatively: not found
                if (!response.isSuccessful) return@use null
                val body = response.body?.string().orEmpty()
                parsePresence(body, normalizedTxid)
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val TXID_PLACEHOLDER = "{txid}"

        /** Built-in mainnet explorer (Blockchair transaction dashboard). */
        const val DEFAULT_MAINNET_URL_TEMPLATE =
            "https://api.blockchair.com/dogecoin/dashboards/transaction/{txid}"

        private val TXID_REGEX = Regex("^[0-9a-f]{64}$")

        /**
         * Decide whether an explorer response [body] indicates [txid] is on-chain.
         *  - true  -> a definitive sighting (be conservative: only return true when genuinely present).
         *  - false -> a recognized "not found" response (e.g. Blockchair with empty data).
         *  - null  -> unrecognized shape / parse error -> unknown, do not corroborate.
         *
         * Recognizes the Blockchair dashboards shape (`{ "data": { "<txid>": {...} }, "context": {...} }`)
         * and a generic tx object that echoes the requested txid.
         */
        fun parsePresence(body: String, txid: String): Boolean? {
            val want = txid.trim().lowercase()
            val root = runCatching { JsonParser.parseString(body) }.getOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: return null

            // Blockchair: presence iff the "data" object has an entry keyed by the txid.
            val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            if (data != null) {
                val match = data.entrySet().firstOrNull { it.key.trim().lowercase() == want }
                if (match != null) return match.value != null && !match.value.isJsonNull
                // A Blockchair-shaped envelope (has "context") with no matching entry is "not found".
                if (root.has("context")) return false
                return null
            }

            // Generic explorer: a tx object that echoes the requested txid.
            val echoed = listOf("txid", "hash", "tx_hash", "txhash").any { key ->
                root.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.lowercase() == want
            }
            if (echoed) return true

            return null
        }

        /**
         * HTTPS anywhere; plaintext HTTP only to "localhost", the IPv6 loopback, or a private/loopback/
         * link-local IPv4 LITERAL. Crucially this matches IP literals, NOT hostname prefixes: a routable
         * name like "10.attacker.com" or "172.16.evil.com" is NOT local and must use HTTPS, so corroboration
         * can never leak a txid over cleartext to a public host that merely starts with "10.".
         */
        internal fun isAllowedExplorerUrl(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            if (parsed.isHttps) return true
            val host = parsed.host.lowercase()
            if (host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1") return true
            // Require a syntactically valid 4-octet IPv4 literal (rejects any hostname), then range-check it.
            val octets = host.split(".")
            if (octets.size != 4) return false
            val nums = octets.map { it.toIntOrNull() }
            if (nums.any { it == null || it !in 0..255 }) return false
            val a = nums[0]!!
            val b = nums[1]!!
            return a == 127 ||                         // 127.0.0.0/8 loopback
                a == 10 ||                             // 10.0.0.0/8
                (a == 192 && b == 168) ||              // 192.168.0.0/16
                (a == 172 && b in 16..31) ||           // 172.16.0.0/12
                (a == 169 && b == 254)                 // 169.254.0.0/16 link-local
        }
    }
}
