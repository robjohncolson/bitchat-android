package com.bitchat.android.features.dogecoin

import android.content.Context
import android.util.Log
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.model.PaymentBroadcastRejectCode
import com.bitchat.android.model.PaymentBroadcastRequest
import com.bitchat.android.model.PaymentBroadcastResult
import com.bitchat.android.model.PaymentBroadcastStatus
import java.util.concurrent.atomic.AtomicInteger

/**
 * Helper side of broadcast-over-mesh (Milestone 3b). When this device opts in, it broadcasts a peer's
 * already-SIGNED Dogecoin transaction through its own node and returns the node-verified txid.
 *
 * Security model: the transaction is fully signed before it leaves the sender, so this service holds
 * no keys, signs nothing, and custodies nothing — it can only drop/delay/refuse, or learn a tx that is
 * about to be public on-chain anyway. It NEVER persists the tx or the requester, and never logs the raw
 * tx hex and the peer identity together.
 *
 * Inbound requests are gated cheapest-and-most-protective-first so a hostile sender cannot turn a
 * helper into an attack tool:
 *
 *   1. decode (bounded; PaymentBroadcastRequest.decode caps size before any large allocation)
 *   2. per-network opt-in (mainnet defaults OFF, independently of testnet/regtest)
 *   3. network match (only the network this device is configured for)
 *   4. favorites-only (default on)
 *   5. dedup / replay (idempotent resend of a cached terminal result; drop if still in-flight)
 *   6. rate limits: per stable identity, per expected-txid, and global hourly + concurrent ceilings
 *   7. structural re-validation (the EXPENSIVE hex-decode + shape walk — only now, behind the gates)
 *   8. txid cross-check (refuse a sender-claimed txid that does not match the bytes)
 *   9. node readiness
 *  10. broadcast (the node's testmempoolaccept/sendrawtransaction is the real arbiter)
 *
 * The Sybil-resistant ceilings are the per-txid and global limits (a sender can mint many noise
 * identities to defeat the per-peer bucket, but not the per-tx or global caps).
 */
class BroadcastHelperService private constructor(appContext: Context) {

    private val context = appContext.applicationContext
    private val repository = DogecoinWalletRepository(context)
    private val rpcClient = DogecoinRpcClient()

    private class CacheEntry(val resultBytes: ByteArray?, val atMs: Long) // resultBytes == null => in-flight
    private val dedup = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean = size > DEDUP_MAX
    }
    private val peerHits = HashMap<String, ArrayDeque<Long>>()
    private val txidHits = HashMap<String, ArrayDeque<Long>>()
    private val globalHits = ArrayDeque<Long>()
    private val inFlight = AtomicInteger(0)
    private val lock = Any()

    /** True if this device currently advertises itself as a helper for [network] (UI uses this for capability TLV). */
    fun isHelperEnabled(network: DogecoinNetwork): Boolean = repository.loadHelperEnabled(network)

    /**
     * Handle an inbound PAYMENT_BROADCAST_REQUEST. Returns the encoded [PaymentBroadcastResult] bytes to
     * send back to [fromPeerID], or null to drop silently (malformed payload, or a duplicate that is
     * still in-flight). The caller routes the returned bytes back over the same transport.
     *
     * @param fromNoiseKeyHex the requester's STABLE identity (noise public key hex). Used for the
     *   favorites check and rate limiting so a rotating ephemeral peerID cannot evade limits. When null,
     *   the requester is unverified and is rate-limited under its ephemeral peerID and refused if the
     *   favorites-only gate is on.
     */
    suspend fun handleRequest(
        fromPeerID: String,
        fromNoiseKeyHex: String?,
        requestPayload: ByteArray,
        nowMs: Long
    ): ByteArray? {
        // 1. Decode (bounded; oversize/malformed -> drop, no UUID to reply to).
        val request = PaymentBroadcastRequest.decode(requestPayload) ?: run {
            Log.d(TAG, "Dropping malformed payment-broadcast request from ${fromPeerID.take(8)}…")
            return null
        }
        val uuidHex = request.requestUuid.toHexLower()
        val network = DogecoinNetwork.values().firstOrNull { it.id == request.networkId }

        // 2. Per-network opt-in gate.
        if (network == null || !repository.loadHelperEnabled(network)) {
            return decline(request, "Helper is not enabled for ${request.networkId}.")
        }
        // 3. Network match.
        if (network != repository.loadSelectedNetwork()) {
            return decline(request, "Helper is on a different Dogecoin network.")
        }
        // 4. Favorites-only gate.
        val mutualFavorite = fromNoiseKeyHex
            ?.let { runCatching { FavoritesPersistenceService.shared.getFavoriteStatus(hexToBytes(it)) }.getOrNull() }
            ?.isMutual == true
        if (repository.loadHelperFavoritesOnly() && !mutualFavorite) {
            return decline(request, "Helper only serves mutual favorites.")
        }

        // 5 + 6. Dedup, rate limits, and in-flight reservation under the lock.
        val rateKey = fromNoiseKeyHex ?: fromPeerID
        synchronized(lock) {
            pruneDedup(nowMs)
            dedup[uuidHex]?.let { return it.resultBytes } // cached terminal result -> resend; null -> in-flight -> drop

            if (!underWindowLimit(peerHits, rateKey, nowMs, if (mutualFavorite) PEER_LIMIT_FAVORITE else PEER_LIMIT, PEER_WINDOW_MS)) {
                return decline(request, "Helper rate limit reached for this peer.")
            }
            if (!underWindowLimit(txidHits, request.expectedTxid, nowMs, TXID_LIMIT, TXID_WINDOW_MS)) {
                return decline(request, "Helper rate limit reached for this transaction.")
            }
            if (!underGlobalLimit(nowMs)) {
                return decline(request, "Helper is busy right now; try again shortly.")
            }
            if (inFlight.get() >= MAX_CONCURRENT) {
                return decline(request, "Helper is busy right now; try again shortly.")
            }

            // Reserve: mark in-flight and count this served request against the limits.
            dedup[uuidHex] = CacheEntry(null, nowMs)
            recordHit(peerHits, rateKey, nowMs)
            recordHit(txidHits, request.expectedTxid, nowMs)
            globalHits.addLast(nowMs)
            inFlight.incrementAndGet()
        }

        // 7-10. Expensive validation + broadcast OUTSIDE the lock.
        val result = try {
            broadcast(request, network)
        } catch (e: Exception) {
            Log.w(TAG, "Helper broadcast failed for ${uuidHex.take(8)}…: ${e.message}")
            reject(request, PaymentBroadcastRejectCode.OTHER, e.message)
        } finally {
            inFlight.decrementAndGet()
        }
        val resultBytes = result.encode()
        synchronized(lock) { dedup[uuidHex] = CacheEntry(resultBytes, nowMs) } // cache terminal result for idempotent resend
        return resultBytes
    }

    private suspend fun broadcast(request: PaymentBroadcastRequest, network: DogecoinNetwork): PaymentBroadcastResult {
        // 7. Structural re-validation (hex-decode + shape) — the expensive step, now behind the gates.
        val normalizedHex = try {
            DogecoinRawTxValidator.normalize(request.rawTransactionHex)
        } catch (e: Exception) {
            return reject(request, PaymentBroadcastRejectCode.SHAPE_INVALID, e.message)
        }
        // 8. Txid cross-check (refuse a confused-deputy on an attacker-claimed txid).
        val computedTxid = runCatching { DogecoinTransactionBuilder.transactionId(normalizedHex) }.getOrNull()
        if (computedTxid == null || computedTxid != request.expectedTxid) {
            return reject(request, PaymentBroadcastRejectCode.SHAPE_INVALID, "Transaction id mismatch.")
        }
        // 9. Node readiness.
        val rpcConfig = repository.loadRpcConfig(network)
        val status = runCatching { rpcClient.getBlockchainStatus(rpcConfig, network) }.getOrNull()
        if (status?.canBroadcastFor(network) != true) {
            return reject(request, PaymentBroadcastRejectCode.NODE_NOT_READY, "Helper node cannot broadcast right now.")
        }
        // 10. Broadcast. sendRawTransaction re-runs requireNetworkReady/requireRelayReady/dust/
        //     testmempoolaccept/sendrawtransaction and returns the node-verified txid (defense in depth).
        return try {
            val txid = rpcClient.sendRawTransaction(rpcConfig, normalizedHex, network)
            PaymentBroadcastResult(request.requestUuid, PaymentBroadcastStatus.ACCEPTED, txid = txid)
        } catch (e: Exception) {
            reject(request, PaymentBroadcastRejectCode.classify(e.message ?: ""), e.message)
        }
    }

    private fun decline(request: PaymentBroadcastRequest, detail: String): ByteArray? =
        PaymentBroadcastResult(request.requestUuid, PaymentBroadcastStatus.DECLINED, rejectDetail = detail).encode()

    private fun reject(request: PaymentBroadcastRequest, code: PaymentBroadcastRejectCode, detail: String?): PaymentBroadcastResult =
        PaymentBroadcastResult(request.requestUuid, PaymentBroadcastStatus.REJECTED, rejectCode = code, rejectDetail = detail?.take(255))

    // --- dedup + rate-limit bookkeeping (all called under `lock`) ---

    private fun pruneDedup(nowMs: Long) {
        val it = dedup.entries.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().value.atMs > DEDUP_TTL_MS) it.remove()
        }
    }

    private fun recordHit(map: HashMap<String, ArrayDeque<Long>>, key: String, nowMs: Long) {
        map.getOrPut(key) { ArrayDeque() }.addLast(nowMs)
    }

    private fun underWindowLimit(
        map: HashMap<String, ArrayDeque<Long>>,
        key: String,
        nowMs: Long,
        limit: Int,
        windowMs: Long
    ): Boolean {
        val hits = map[key] ?: return true
        while (hits.isNotEmpty() && nowMs - hits.first() > windowMs) hits.removeFirst()
        if (hits.isEmpty()) map.remove(key)
        return (map[key]?.size ?: 0) < limit
    }

    private fun underGlobalLimit(nowMs: Long): Boolean {
        while (globalHits.isNotEmpty() && nowMs - globalHits.first() > GLOBAL_WINDOW_MS) globalHits.removeFirst()
        return globalHits.size < GLOBAL_LIMIT
    }

    private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        return ByteArray(clean.length / 2) { ((Character.digit(clean[it * 2], 16) shl 4) + Character.digit(clean[it * 2 + 1], 16)).toByte() }
    }

    companion object {
        private const val TAG = "BroadcastHelperService"

        private const val DEDUP_MAX = 256
        // Dedup TTL must be >= the sender's 10-min signed-tx window so retries within a tx's own
        // lifetime always hit the cache rather than triggering a second node broadcast.
        private const val DEDUP_TTL_MS = 15L * 60L * 1000L

        private const val PEER_LIMIT = 3
        private const val PEER_LIMIT_FAVORITE = 10
        private const val PEER_WINDOW_MS = 5L * 60L * 1000L
        private const val TXID_LIMIT = 3
        private const val TXID_WINDOW_MS = 15L * 60L * 1000L
        private const val GLOBAL_LIMIT = 20
        private const val GLOBAL_WINDOW_MS = 60L * 60L * 1000L
        private const val MAX_CONCURRENT = 5

        @Volatile private var INSTANCE: BroadcastHelperService? = null

        fun getInstance(context: Context): BroadcastHelperService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BroadcastHelperService(context).also { INSTANCE = it }
            }
        }
    }
}
