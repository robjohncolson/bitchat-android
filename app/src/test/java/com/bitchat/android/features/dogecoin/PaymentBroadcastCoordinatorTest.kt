package com.bitchat.android.features.dogecoin

import com.bitchat.android.model.PaymentBroadcastRejectCode
import com.bitchat.android.model.PaymentBroadcastRequest
import com.bitchat.android.model.PaymentBroadcastResult
import com.bitchat.android.model.PaymentBroadcastStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sender-side broadcast-over-mesh corroboration logic (Milestone 3b / 3b.1).
 *
 * The core money-safety property under test: a SINGLE helper's ACCEPTED is only a CLAIM
 * ([PaymentBroadcastCoordinator.Outcome.Claimed]) — a node-less sender cannot verify chain inclusion and
 * one helper could lie — so [PaymentBroadcastCoordinator.Outcome.Confirmed] requires two distinct helpers
 * to independently accept (ACCEPTED with the matching txid, or ALREADY_KNOWN) the same transaction.
 *
 * Tests drive the coordinator deterministically: helper replies are emitted synchronously from inside the
 * injected `sendRequestToPeer` callback (which decodes the request payload for its UUID), and the
 * [UnconfinedTestDispatcher] guarantees the result collector is subscribed before any reply is emitted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentBroadcastCoordinatorTest {

    private val txid = "a".repeat(64)
    private val rawHex = "0100000001" + "ab".repeat(100) // even-length lowercase hex
    private val network = DogecoinNetwork.TESTNET

    private fun acceptedReply(uuid: ByteArray, acceptedTxid: String = txid): ByteArray =
        PaymentBroadcastResult(uuid, PaymentBroadcastStatus.ACCEPTED, txid = acceptedTxid).encode()!!

    private fun rejectReply(uuid: ByteArray, code: PaymentBroadcastRejectCode): ByteArray =
        PaymentBroadcastResult(uuid, PaymentBroadcastStatus.REJECTED, rejectCode = code).encode()!!

    private fun declinedReply(uuid: ByteArray): ByteArray =
        PaymentBroadcastResult(uuid, PaymentBroadcastStatus.DECLINED).encode()!!

    /**
     * Build a coordinator whose `sendRequestToPeer` delegates to [send]. The [send] lambda receives the
     * target peerID, the request UUID (decoded from the wire payload, as a real helper would echo it), and
     * an `emit` callback to feed a reply back into the coordinator. It returns whether the request was
     * dispatched (false models a routing failure with no reply).
     */
    private fun coordinatorWith(
        candidates: List<String>,
        isScarceHelper: (peerID: String) -> Boolean = { true },
        send: (peerID: String, uuid: ByteArray, emit: (ByteArray) -> Unit) -> Boolean
    ): PaymentBroadcastCoordinator {
        lateinit var coordinator: PaymentBroadcastCoordinator
        coordinator = PaymentBroadcastCoordinator(
            listCandidateHelpers = { candidates },
            sendRequestToPeer = { peerID, payload ->
                val uuid = PaymentBroadcastRequest.decode(payload)!!.requestUuid
                send(peerID, uuid) { reply -> coordinator.onResult(peerID, reply) }
            },
            isScarceHelper = isScarceHelper
        )
        return coordinator
    }

    @Test
    fun `single helper ACCEPTED is only a Claim, never Confirmed`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinatorWith(listOf("A")) { _, uuid, emit ->
            emit(acceptedReply(uuid)); true
        }
        val outcome = coordinator.broadcast(rawHex, txid, network)
        assertTrue("expected Claimed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Claimed)
        assertEquals(txid, (outcome as PaymentBroadcastCoordinator.Outcome.Claimed).txid)
    }

    @Test
    fun `one source id accepting twice counts once and stays Claimed (canonical-identity invariant)`() =
        runTest(UnconfinedTestDispatcher()) {
            // This is the invariant the 3b.1 fix relies on: the adapter canonicalizes every reply to the
            // helper's stable Noise key, so a single physical helper presents ONE source id regardless of
            // how many replies/transports/minted Nostr keys it uses. With one distinct id, two ACCEPTEDs are
            // one corroboration -> Claimed, never Confirmed.
            val coordinator = coordinatorWith(listOf("A")) { _, uuid, emit ->
                emit(acceptedReply(uuid))
                emit(acceptedReply(uuid))
                true
            }
            val outcome = coordinator.broadcast(rawHex, txid, network)
            assertTrue("expected Claimed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Claimed)
        }

    @Test
    fun `two helpers accepting the same txid yields Confirmed`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinatorWith(listOf("A", "B")) { _, uuid, emit ->
            emit(acceptedReply(uuid)); true
        }
        val outcome = coordinator.broadcast(rawHex, txid, network)
        assertTrue("expected Confirmed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Confirmed)
        assertEquals(txid, (outcome as PaymentBroadcastCoordinator.Outcome.Confirmed).txid)
    }

    @Test
    fun `ACCEPTED plus ALREADY_KNOWN corroborate to Confirmed`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinatorWith(listOf("A", "B")) { peer, uuid, emit ->
            emit(if (peer == "A") acceptedReply(uuid) else rejectReply(uuid, PaymentBroadcastRejectCode.ALREADY_KNOWN))
            true
        }
        val outcome = coordinator.broadcast(rawHex, txid, network)
        assertTrue("expected Confirmed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Confirmed)
    }

    @Test
    fun `single ALREADY_KNOWN is only a Claim`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinatorWith(listOf("A")) { _, uuid, emit ->
            emit(rejectReply(uuid, PaymentBroadcastRejectCode.ALREADY_KNOWN)); true
        }
        val outcome = coordinator.broadcast(rawHex, txid, network)
        assertTrue("expected Claimed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Claimed)
    }

    @Test
    fun `one ACCEPTED and one DECLINED settles to Claim once corroboration is unreachable`() =
        runTest(UnconfinedTestDispatcher()) {
            val coordinator = coordinatorWith(listOf("A", "B")) { peer, uuid, emit ->
                emit(if (peer == "A") acceptedReply(uuid) else declinedReply(uuid)); true
            }
            val outcome = coordinator.broadcast(rawHex, txid, network)
            assertTrue("expected Claimed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Claimed)
        }

    @Test
    fun `acceptance corroborated by a helper reached on a later attempt yields Confirmed`() =
        runTest(UnconfinedTestDispatcher()) {
            // FANOUT=2: attempt 1 reaches A,B; attempt 2 reaches C. A accepts, B declines, C accepts -> Confirmed.
            val coordinator = coordinatorWith(listOf("A", "B", "C")) { peer, uuid, emit ->
                emit(if (peer == "B") declinedReply(uuid) else acceptedReply(uuid)); true
            }
            val outcome = coordinator.broadcast(rawHex, txid, network)
            assertTrue("expected Confirmed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Confirmed)
        }

    @Test
    fun `two NON-scarce helpers accepting stays Claimed, never Confirmed (sybil cannot self-confirm)`() =
        runTest(UnconfinedTestDispatcher()) {
            // The #2 fix: a single sybil running two free-to-mint helper identities (neither a mutual favorite)
            // must NOT reach Confirmed. Both accept the real txid, but with isScarceHelper=false for everyone
            // the two positives are only a CLAIM — the node-less broadcast still works (relay happened), it
            // just can't be falsely presented as settled.
            val coordinator = coordinatorWith(listOf("A", "B"), isScarceHelper = { false }) { _, uuid, emit ->
                emit(acceptedReply(uuid)); true
            }
            val outcome = coordinator.broadcast(rawHex, txid, network)
            assertTrue("expected Claimed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Claimed)
        }

    @Test
    fun `one scarce plus one non-scarce acceptance is only a Claim`() = runTest(UnconfinedTestDispatcher()) {
        // Confirmed needs TWO scarce (mutual-favorite) corroborations. A scarce A + non-scarce B = one scarce
        // positive -> Claimed, not Confirmed.
        val coordinator = coordinatorWith(listOf("A", "B"), isScarceHelper = { it == "A" }) { _, uuid, emit ->
            emit(acceptedReply(uuid)); true
        }
        val outcome = coordinator.broadcast(rawHex, txid, network)
        assertTrue("expected Claimed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Claimed)
    }

    @Test
    fun `two scarce helpers accepting yields Confirmed (mutual-favorite corroboration still works)`() =
        runTest(UnconfinedTestDispatcher()) {
            // The validated offline flow uses mutual favorites; they remain scarce, so Confirmed is reachable.
            val coordinator = coordinatorWith(listOf("A", "B"), isScarceHelper = { true }) { _, uuid, emit ->
                emit(acceptedReply(uuid)); true
            }
            val outcome = coordinator.broadcast(rawHex, txid, network)
            assertTrue("expected Confirmed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Confirmed)
        }

    @Test
    fun `a forged ACCEPTED with the wrong txid never confirms`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinatorWith(listOf("A")) { _, uuid, emit ->
            emit(acceptedReply(uuid, acceptedTxid = "b".repeat(64))); true
        }
        val outcome = coordinator.broadcast(rawHex, txid, network)
        assertTrue("expected Failed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Failed)
    }

    @Test
    fun `a single terminal reject does not abort and is not corroborated to failure`() =
        runTest(UnconfinedTestDispatcher()) {
            // One helper says INSUFFICIENT_FEE; no positive, no second reproduction -> generic failure, not Confirmed/Claimed.
            val coordinator = coordinatorWith(listOf("A")) { _, uuid, emit ->
                emit(rejectReply(uuid, PaymentBroadcastRejectCode.INSUFFICIENT_FEE)); true
            }
            val outcome = coordinator.broadcast(rawHex, txid, network)
            assertTrue("expected Failed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Failed)
            assertTrue(
                "failure should surface the mapped fee reason",
                (outcome as PaymentBroadcastCoordinator.Outcome.Failed).reason.contains("fee", ignoreCase = true)
            )
        }

    @Test
    fun `the same helper accepting twice is still only a Claim, never Confirmed`() =
        runTest(UnconfinedTestDispatcher()) {
            // Core money-safety property: corroboration counts DISTINCT helpers. One peer's repeated
            // ACCEPTED must not reach the 2-helper Confirmed threshold. Pins positives being a peerID-keyed
            // Set (a regression to a counter/List would make this Confirmed and the suite would stay green).
            val coordinator = coordinatorWith(listOf("A", "B")) { peer, uuid, emit ->
                if (peer == "A") {
                    emit(acceptedReply(uuid)); emit(acceptedReply(uuid))
                } else {
                    emit(declinedReply(uuid))
                }
                true
            }
            val outcome = coordinator.broadcast(rawHex, txid, network)
            assertTrue("expected Claimed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Claimed)
        }

    @Test
    fun `the same helper accepting then reporting ALREADY_KNOWN is still only a Claim`() =
        runTest(UnconfinedTestDispatcher()) {
            val coordinator = coordinatorWith(listOf("A", "B")) { peer, uuid, emit ->
                if (peer == "A") {
                    emit(acceptedReply(uuid)); emit(rejectReply(uuid, PaymentBroadcastRejectCode.ALREADY_KNOWN))
                } else {
                    emit(declinedReply(uuid))
                }
                true
            }
            val outcome = coordinator.broadcast(rawHex, txid, network)
            assertTrue("expected Claimed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Claimed)
        }

    @Test
    fun `one helper's terminal reject does not abort another helper's acceptance`() =
        runTest(UnconfinedTestDispatcher()) {
            // Anti-censorship: a single (possibly lying) helper's terminal REJECTED must not suppress a
            // payment another helper accepts. A=reject, B=accept -> B's lone positive becomes a Claim.
            val coordinator = coordinatorWith(listOf("A", "B")) { peer, uuid, emit ->
                emit(if (peer == "A") rejectReply(uuid, PaymentBroadcastRejectCode.INSUFFICIENT_FEE) else acceptedReply(uuid))
                true
            }
            val outcome = coordinator.broadcast(rawHex, txid, network)
            assertTrue("expected Claimed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Claimed)
        }

    @Test
    fun `a lone positive followed by a terminal reject settles to Claim without stalling the timeout`() =
        runTest(UnconfinedTestDispatcher()) {
            // Regression pin: the positive-then-terminal ordering must short-circuit to Claimed the moment
            // no further helper can reply, NOT wait out ATTEMPT_TIMEOUT_MS. An outcome-only assertion can't
            // catch this (virtual time hides the stall), so we assert the virtual clock never advanced.
            val coordinator = coordinatorWith(listOf("A", "B")) { peer, uuid, emit ->
                emit(if (peer == "A") acceptedReply(uuid) else rejectReply(uuid, PaymentBroadcastRejectCode.INSUFFICIENT_FEE))
                true
            }
            val outcome = coordinator.broadcast(rawHex, txid, network)
            assertTrue("expected Claimed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Claimed)
            assertEquals("should settle immediately, not wait out the attempt timeout", 0L, testScheduler.currentTime)
        }

    @Test
    fun `a lone positive on the final attempt settles to Claim even with more candidates than the dispatch budget`() =
        runTest(UnconfinedTestDispatcher()) {
            // 7 candidates exceeds MAX_ATTEMPTS*FANOUT = 6, so the 7th is never dispatched. The first six
            // all decline except the last-dispatched, which accepts. The undispatchable 7th must NOT keep
            // the coordinator waiting: a lone positive with the dispatch budget exhausted is a Claim.
            val candidates = (1..7).map { "p$it" }
            val coordinator = coordinatorWith(candidates) { peer, uuid, emit ->
                emit(if (peer == "p6") acceptedReply(uuid) else declinedReply(uuid)); true
            }
            val outcome = coordinator.broadcast(rawHex, txid, network)
            assertTrue("expected Claimed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Claimed)
        }

    @Test
    fun `a terminal reject reproduced by two helpers surfaces the mapped failure`() =
        runTest(UnconfinedTestDispatcher()) {
            val coordinator = coordinatorWith(listOf("A", "B")) { _, uuid, emit ->
                emit(rejectReply(uuid, PaymentBroadcastRejectCode.INSUFFICIENT_FEE)); true
            }
            val outcome = coordinator.broadcast(rawHex, txid, network)
            assertTrue("expected Failed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Failed)
            assertTrue(
                "failure should mention the fee",
                (outcome as PaymentBroadcastCoordinator.Outcome.Failed).reason.contains("fee", ignoreCase = true)
            )
        }

    @Test
    fun `no candidate helpers fails fast`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinatorWith(emptyList()) { _, _, _ -> true }
        val outcome = coordinator.broadcast(rawHex, txid, network)
        assertTrue("expected Failed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Failed)
        assertTrue((outcome as PaymentBroadcastCoordinator.Outcome.Failed).reason.contains("No reachable peer"))
    }

    @Test
    fun `generic mainnet peer broadcast fails before bytes reach helper discovery`() =
        runTest(UnconfinedTestDispatcher()) {
            var listedCandidates = false
            var dispatched = false
            val coordinator = PaymentBroadcastCoordinator(
                listCandidateHelpers = {
                    listedCandidates = true
                    listOf("A")
                },
                sendRequestToPeer = { _, _ ->
                    dispatched = true
                    true
                }
            )

            val outcome = coordinator.broadcast(rawHex, txid, DogecoinNetwork.MAINNET)

            assertTrue(outcome is PaymentBroadcastCoordinator.Outcome.Failed)
            assertTrue((outcome as PaymentBroadcastCoordinator.Outcome.Failed).reason.contains("unavailable on mainnet"))
            assertTrue("mainnet helper discovery must not run", !listedCandidates)
            assertTrue("signed bytes must not be dispatched", !dispatched)
        }

    @Test
    fun `when no request can be dispatched it reports no reachable peer`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinatorWith(listOf("A")) { _, _, _ -> false } // routing fails, never dispatched
        val outcome = coordinator.broadcast(rawHex, txid, network)
        assertTrue("expected Failed, was $outcome", outcome is PaymentBroadcastCoordinator.Outcome.Failed)
        assertTrue((outcome as PaymentBroadcastCoordinator.Outcome.Failed).reason.contains("No reachable peer"))
    }
}
