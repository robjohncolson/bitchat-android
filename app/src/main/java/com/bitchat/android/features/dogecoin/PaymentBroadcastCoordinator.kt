package com.bitchat.android.features.dogecoin

import com.bitchat.android.model.PaymentBroadcastRejectCode
import com.bitchat.android.model.PaymentBroadcastRequest
import com.bitchat.android.model.PaymentBroadcastResult
import com.bitchat.android.model.PaymentBroadcastStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/**
 * Sender side of broadcast-over-mesh (Milestone 3b). When the local node cannot broadcast, the sender
 * relays its already-signed transaction to opt-in helper peers and waits for the node-verified txid.
 *
 * Decoupled from the mesh/UI: callers inject [listCandidateHelpers] (ranked peerIDs: mutual-favorite
 * mesh > verified mesh > favorite Nostr) and [sendRequestToPeer] (route an encoded request, returning
 * whether it was dispatched). The inbound PAYMENT_BROADCAST_RESULT callback feeds [onResult].
 *
 * Censorship/abuse hardening:
 *  - fan out to at most [FANOUT] helpers per attempt with the SAME request UUID (not all peers — that
 *    would be needless amplification), up to [MAX_ATTEMPTS] attempts to fresh helpers;
 *  - first ACCEPTED (txid == expected) or ALREADY_KNOWN wins; a hostile helper cannot forge a different
 *    txid (no key) and the txid is re-checked here;
 *  - a SINGLE helper's terminal REJECTED does NOT abort — a lying helper could fabricate it — so a
 *    terminal reason must reproduce from two distinct helpers before we surface failure;
 *  - REJECT_DETAIL is attacker-controlled and is never shown; failure text is a fixed app string mapped
 *    from the reject code.
 */
/** UI-facing state of a peer-broadcast attempt, observed by the wallet sheet. */
sealed interface PeerBroadcastUiState {
    data object Idle : PeerBroadcastUiState
    data object Pending : PeerBroadcastUiState
    data class Confirmed(val txid: String) : PeerBroadcastUiState
    data class Failed(val reason: String) : PeerBroadcastUiState
}

class PaymentBroadcastCoordinator(
    private val listCandidateHelpers: (DogecoinNetwork) -> List<String>,
    private val sendRequestToPeer: (peerID: String, payload: ByteArray) -> Boolean
) {
    sealed interface Outcome {
        data class Confirmed(val txid: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private val results = MutableSharedFlow<Pair<String, PaymentBroadcastResult>>(extraBufferCapacity = 64)

    /** Feed an inbound PAYMENT_BROADCAST_RESULT (raw payload) received from [fromPeerID]. */
    fun onResult(fromPeerID: String, resultPayload: ByteArray) {
        val result = PaymentBroadcastResult.decode(resultPayload) ?: return
        results.tryEmit(fromPeerID to result)
    }

    /**
     * Relay [rawTransactionHex] (whose locally-computed txid is [expectedTxid]) to helper peers and
     * await acceptance. Must be called within the signed transaction's validity window; the total wall
     * time is bounded by [MAX_ATTEMPTS] * [ATTEMPT_TIMEOUT_MS] (90s), well under the 10-minute window.
     */
    suspend fun broadcast(rawTransactionHex: String, expectedTxid: String, network: DogecoinNetwork): Outcome {
        val uuid = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val request = PaymentBroadcastRequest(uuid, network.id, rawTransactionHex, expectedTxid)
        val payload = request.encode() ?: return Outcome.Failed("Could not encode the broadcast request.")

        val candidates = listCandidateHelpers(network)
        if (candidates.isEmpty()) {
            return Outcome.Failed("No reachable peer can broadcast this transaction right now.")
        }

        val used = LinkedHashSet<String>()
        val terminalSeen = HashMap<PaymentBroadcastRejectCode, MutableSet<String>>()
        var lastReason = "No connected peer accepted the transaction."

        repeat(MAX_ATTEMPTS) {
            val batch = candidates.filterNot { it in used }.take(FANOUT)
            if (batch.isEmpty()) return@repeat
            used.addAll(batch)
            val dispatched = batch.count { sendRequestToPeer(it, payload) }
            if (dispatched == 0) return@repeat

            val decisive: Pair<String, PaymentBroadcastResult>? = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
                results
                    .filter { it.second.requestUuid.contentEquals(uuid) }
                    .firstOrNull { (peerID, res) ->
                        when {
                            res.status == PaymentBroadcastStatus.ACCEPTED && res.txid == expectedTxid -> true
                            res.status == PaymentBroadcastStatus.REJECTED &&
                                res.rejectCode == PaymentBroadcastRejectCode.ALREADY_KNOWN -> true
                            res.status == PaymentBroadcastStatus.REJECTED &&
                                res.rejectCode?.isTerminalForTransaction == true -> {
                                lastReason = humanReason(res)
                                val peers = terminalSeen.getOrPut(res.rejectCode!!) { mutableSetOf() }
                                peers.add(peerID)
                                peers.size >= TERMINAL_REPRODUCTIONS // require a second independent helper
                            }
                            else -> { lastReason = humanReason(res); false }
                        }
                    }
            }

            if (decisive != null) {
                val res = decisive.second
                return when {
                    res.status == PaymentBroadcastStatus.ACCEPTED -> Outcome.Confirmed(res.txid ?: expectedTxid)
                    res.rejectCode == PaymentBroadcastRejectCode.ALREADY_KNOWN -> Outcome.Confirmed(expectedTxid)
                    else -> Outcome.Failed(humanReason(res)) // terminal reason reproduced by two helpers
                }
            }
        }
        return Outcome.Failed(lastReason)
    }

    /** Fixed, app-controlled failure text. Never renders the attacker-controlled REJECT_DETAIL. */
    private fun humanReason(res: PaymentBroadcastResult): String = when (res.status) {
        PaymentBroadcastStatus.DECLINED -> "A peer declined to broadcast (not a helper, busy, or rate-limited)."
        PaymentBroadcastStatus.REJECTED -> when (res.rejectCode) {
            PaymentBroadcastRejectCode.MISSING_INPUTS -> "A peer's node rejected the transaction: inputs missing or already spent."
            PaymentBroadcastRejectCode.INSUFFICIENT_FEE -> "A peer's node rejected the transaction: fee too low."
            PaymentBroadcastRejectCode.DUST -> "A peer's node rejected the transaction: an output is below the dust limit."
            PaymentBroadcastRejectCode.SCRIPT_VERIFY -> "A peer's node rejected the transaction: script verification failed."
            PaymentBroadcastRejectCode.NODE_NOT_READY -> "A peer's node was not ready to broadcast."
            PaymentBroadcastRejectCode.SHAPE_INVALID -> "A peer's node rejected the transaction as malformed."
            else -> "A peer's node rejected the transaction."
        }
        else -> "The broadcast could not be completed."
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val FANOUT = 2
        private const val ATTEMPT_TIMEOUT_MS = 30_000L
        private const val TERMINAL_REPRODUCTIONS = 2
    }
}
