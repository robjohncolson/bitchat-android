package com.bitchat.android.features.dogecoin

import com.bitchat.android.model.PaymentBroadcastRejectCode
import com.bitchat.android.model.PaymentBroadcastRequest
import com.bitchat.android.model.PaymentBroadcastResult
import com.bitchat.android.model.PaymentBroadcastStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
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
 *  - a hostile helper cannot forge a different txid (no key) and the txid is re-checked here, but a
 *    single ACCEPTED is only the helper's CLAIM: a lying helper could echo [expectedTxid] without ever
 *    broadcasting, and a node-less sender can't verify chain inclusion. So [REQUIRED_CORROBORATIONS]
 *    distinct positive acceptances (ACCEPTED with the matching txid, or ALREADY_KNOWN — the node already
 *    had the tx, which is independent evidence it propagated) are required for [Outcome.Confirmed]; a
 *    lone positive is surfaced as [Outcome.Claimed], an uncorroborated claim the UI must not present as
 *    settled. (A stronger on-chain txid poll for the single-helper case is the 3b.1 follow-up.)
 *  - corroboration toward [Outcome.Confirmed] counts ONLY SCARCE identities ([isScarceHelper] — mutual
 *    favorites the user vetted), NOT bare opt-in/NODE_HELPER advertisers (which are free to mint). Without
 *    this a single sybil running two self-minted helper identities could forge a Confirmed. Non-scarce
 *    helpers still relay and yield [Outcome.Claimed]; they just cannot manufacture corroboration.
 *  - a SINGLE helper's terminal REJECTED does NOT abort — a lying helper could fabricate it — so a
 *    terminal reason must reproduce from two distinct helpers before we surface failure;
 *  - REJECT_DETAIL is attacker-controlled and is never shown; failure text is a fixed app string mapped
 *    from the reject code.
 */
/** UI-facing state of a peer-broadcast attempt, observed by the wallet sheet. */
sealed interface PeerBroadcastUiState {
    data object Idle : PeerBroadcastUiState
    data object Pending : PeerBroadcastUiState
    /** Corroborated by two or more helpers' nodes. */
    data class Confirmed(val txid: String) : PeerBroadcastUiState
    /** A single helper's uncorroborated claim — the UI must not present it as settled. */
    data class Claimed(val txid: String) : PeerBroadcastUiState
    data class Failed(val reason: String) : PeerBroadcastUiState
}

class PaymentBroadcastCoordinator(
    private val listCandidateHelpers: (DogecoinNetwork) -> List<String>,
    private val sendRequestToPeer: (peerID: String, payload: ByteArray) -> Boolean,
    /**
     * Whether a corroborating helper — identified by the canonical Noise-key id fed to [onResult] — is a
     * SCARCE identity (a mutual favorite the user themselves vetted) and may therefore count toward the
     * two-helper [Outcome.Confirmed] upgrade. A bare opt-in / NODE_HELPER advertiser is FREE to mint (one
     * actor can run many Noise identities), so counting it toward Confirmed would let a single sybil fake a
     * "settled" send with two self-minted identities. Non-scarce helpers still RELAY and yield
     * [Outcome.Claimed] (the node-less broadcast path is unchanged) — they simply cannot manufacture
     * corroboration. Defaults to treating every helper as scarce, so existing callers/tests keep their
     * behavior; production wiring injects a mutual-favorite check.
     */
    private val isScarceHelper: (peerID: String) -> Boolean = { true }
) {
    sealed interface Outcome {
        /** [REQUIRED_CORROBORATIONS] or more distinct helpers independently accepted (or already had) the tx. */
        data class Confirmed(val txid: String) : Outcome
        /**
         * Exactly one helper accepted. Its node returned this txid, but no second helper corroborated and a
         * node-less sender can't verify chain inclusion — so this is the helper's claim, NOT a settled send.
         */
        data class Claimed(val txid: String) : Outcome
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
    suspend fun broadcast(rawTransactionHex: String, expectedTxid: String, network: DogecoinNetwork): Outcome = coroutineScope {
        val uuid = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val request = PaymentBroadcastRequest(uuid, network.id, rawTransactionHex, expectedTxid)
        val payload = request.encode() ?: return@coroutineScope Outcome.Failed("Could not encode the broadcast request.")

        val candidates = listCandidateHelpers(network)
        if (candidates.isEmpty()) {
            return@coroutineScope Outcome.Failed("No reachable peer can broadcast this transaction right now.")
        }

        // One long-lived collector for the whole broadcast: `results` is a replay=0 SharedFlow, so a
        // reply emitted while no collector is subscribed is dropped. Subscribing once (before any
        // request is dispatched) and keeping it alive across all attempts means a reply matching this
        // uuid is never observed with zero subscribers — fixes the inter-attempt gap that could
        // surface a false Outcome.Failed for a payment a helper actually accepted.
        val incoming = Channel<Pair<String, PaymentBroadcastResult>>(Channel.UNLIMITED)
        val collector = launch {
            results.filter { it.second.requestUuid.contentEquals(uuid) }.collect { incoming.trySend(it) }
        }

        try {
            val used = LinkedHashSet<String>()
            val responded = HashSet<String>()
            // Distinct helpers that returned a POSITIVE acceptance for THIS txid (ACCEPTED with the
            // matching txid, or ALREADY_KNOWN). REQUIRED_CORROBORATIONS of them => Confirmed; a single
            // one => Claimed (see the class doc — one helper's word isn't chain proof).
            val positives = LinkedHashSet<String>()
            // Subset of [positives] from SCARCE (mutual-favorite) helpers — ONLY these count toward the
            // two-helper Confirmed (see [isScarceHelper]). A non-scarce positive still yields Claimed, so the
            // node-less relay path is unaffected; it just can't manufacture a forged "settled" send.
            val scarcePositives = LinkedHashSet<String>()
            val terminalSeen = HashMap<PaymentBroadcastRejectCode, MutableSet<String>>()
            var lastReason = "No connected peer accepted the transaction."
            var everDispatched = false

            repeat(MAX_ATTEMPTS) { attemptIndex ->
                val batch = candidates.filterNot { it in used }.take(FANOUT)
                if (batch.isEmpty()) return@repeat
                used.addAll(batch)
                val dispatched = batch.count { sendRequestToPeer(it, payload) }
                if (dispatched == 0) return@repeat
                everDispatched = true

                // Helpers later attempts could still dispatch (this loop sends at most FANOUT per attempt,
                // MAX_ATTEMPTS*FANOUT total) — caps "fresh" so surplus candidates that will never be
                // contacted don't keep us waiting.
                val futureDispatchBudget = (MAX_ATTEMPTS - 1 - attemptIndex) * FANOUT

                val decisive: Outcome? = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
                    while (true) {
                        val (peerID, res) = incoming.receive()
                        responded.add(peerID)
                        when {
                            isPositive(res, expectedTxid) -> {
                                positives.add(peerID)
                                if (isScarceHelper(peerID)) scarcePositives.add(peerID)
                                if (scarcePositives.size >= REQUIRED_CORROBORATIONS)
                                    return@withTimeoutOrNull Outcome.Confirmed(expectedTxid)
                            }
                            res.status == PaymentBroadcastStatus.REJECTED &&
                                res.rejectCode?.isTerminalForTransaction == true -> {
                                lastReason = humanReason(res)
                                val peers = terminalSeen.getOrPut(res.rejectCode!!) { mutableSetOf() }
                                peers.add(peerID)
                                if (peers.size >= TERMINAL_REPRODUCTIONS)
                                    return@withTimeoutOrNull Outcome.Failed(humanReason(res))
                            }
                            // DECLINED / NODE_NOT_READY / non-terminal rejects / forged txid: try another helper.
                            else -> lastReason = humanReason(res)
                        }
                        // Single short-circuit covering every branch: the only ways to finish EARLY are a
                        // second positive (Confirmed) or a reproduced terminal reject (Failed), both handled
                        // above. Otherwise, the moment no helper can still reply — none outstanding from this
                        // or a prior batch AND none left to dispatch within the attempt budget — stop waiting
                        // instead of stalling out the timeout: a lone positive becomes a Claim, else the best
                        // failure reason. This keeps the single-helper and exhausted-pool cases instant.
                        if (!anyHelperMayStillReply(used, responded, candidates, futureDispatchBudget)) {
                            return@withTimeoutOrNull if (positives.isNotEmpty())
                                Outcome.Claimed(expectedTxid)
                            else
                                Outcome.Failed(lastReason)
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }

                if (decisive != null) return@coroutineScope decisive
            }

            when {
                scarcePositives.size >= REQUIRED_CORROBORATIONS -> Outcome.Confirmed(expectedTxid)
                positives.isNotEmpty() -> Outcome.Claimed(expectedTxid)
                !everDispatched -> Outcome.Failed("No reachable peer can broadcast this transaction right now.")
                else -> Outcome.Failed(lastReason)
            }
        } finally {
            collector.cancel()
        }
    }

    /**
     * A positive acceptance of THIS transaction: the helper's node took it (ACCEPTED, with the txid we
     * expect — a hostile helper can't forge a different one) or already had it (ALREADY_KNOWN, evidence
     * it propagated). Either way it counts as one corroboration toward [REQUIRED_CORROBORATIONS].
     */
    private fun isPositive(res: PaymentBroadcastResult, expectedTxid: String): Boolean =
        (res.status == PaymentBroadcastStatus.ACCEPTED && res.txid == expectedTxid) ||
            (res.status == PaymentBroadcastStatus.REJECTED &&
                res.rejectCode == PaymentBroadcastRejectCode.ALREADY_KNOWN)

    /**
     * Whether any helper could still send a reply that might change the outcome: a helper dispatched but
     * not yet heard from (this or a prior batch), or a candidate still dispatchable within the remaining
     * attempt budget. When false, the outcome can no longer change, so the caller settles immediately
     * instead of waiting out the attempt timeout. [futureDispatchBudget] caps fresh candidates by the
     * helpers later attempts can actually reach, so surplus candidates beyond MAX_ATTEMPTS*FANOUT that
     * will never be contacted don't keep us waiting.
     */
    private fun anyHelperMayStillReply(
        used: Set<String>,
        responded: Set<String>,
        candidates: List<String>,
        futureDispatchBudget: Int
    ): Boolean {
        val outstanding = used.count { it !in responded }
        val freshReachable = minOf(candidates.count { it !in used }, futureDispatchBudget)
        return outstanding > 0 || freshReachable > 0
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
        /** Distinct positive acceptances required to treat a peer broadcast as Confirmed (vs Claimed). */
        private const val REQUIRED_CORROBORATIONS = 2
    }
}
