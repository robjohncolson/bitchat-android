package com.bitchat.android.features.dogecoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DogecoinSpvSettlementEvidenceTest {
    private val expected = "a".repeat(64)
    private val previous = "b".repeat(64)
    private val other = "c".repeat(64)
    private val tip = "d".repeat(64)

    private fun status(synced: Boolean = true) = DogecoinSpvStatus(
        network = DogecoinNetwork.MAINNET,
        running = true,
        peerCount = 4,
        chainHeight = 6_000_000,
        bestPeerHeight = 6_000_001,
        synced = synced
    )

    private fun fact(
        txid: String,
        confidence: DogecoinSpvWalletConfidence,
        depth: Int = 0,
        spends: List<Pair<String, Int>> = emptyList()
    ) = DogecoinSpvWalletTransactionFact(txid, confidence, depth, spends)

    @Test
    fun `network pending exact tx is observed at zero but local pending is not evidence`() {
        val peer = DogecoinSpvSettlementEvidencePolicy.derive(
            status(), expected, listOf(previous to 1), tip,
            listOf(fact(expected, DogecoinSpvWalletConfidence.NETWORK_PENDING))
        )!!
        assertEquals(0, peer.exactTransactionDepth)
        assertEquals(DogecoinSpvEvidenceProvenance.PEER, peer.exactTransactionProvenance)

        val local = DogecoinSpvSettlementEvidencePolicy.derive(
            status(), expected, listOf(previous to 1), tip,
            listOf(fact(expected, DogecoinSpvWalletConfidence.LOCAL_PENDING))
        )!!
        assertNull(local.exactTransactionDepth)
        assertEquals(DogecoinSpvEvidenceProvenance.NONE, local.exactTransactionProvenance)
    }

    @Test
    fun `validated chain exact tx exposes depth and strict sync context`() {
        val evidence = DogecoinSpvSettlementEvidencePolicy.derive(
            status(synced = true), expected, listOf(previous to 1), tip,
            listOf(fact(expected, DogecoinSpvWalletConfidence.CHAIN_BUILDING, depth = 6))
        )!!
        assertEquals(6, evidence.exactTransactionDepth)
        assertEquals(DogecoinSpvEvidenceProvenance.CHAIN, evidence.exactTransactionProvenance)
        assertTrue(evidence.fullySynced)
        assertTrue(evidence.peerFloorMet)
        assertEquals(tip, evidence.chainTipHash)
    }

    @Test
    fun `peer or chain conflicting spend is positive evidence but local and dead are ignored`() {
        val evidence = DogecoinSpvSettlementEvidencePolicy.derive(
            status(), expected, listOf(previous to 1, "1".repeat(64) to 2), tip,
            listOf(
                fact(other, DogecoinSpvWalletConfidence.CHAIN_BUILDING, 6, listOf(previous to 1)),
                fact("2".repeat(64), DogecoinSpvWalletConfidence.NETWORK_PENDING,
                    spends = listOf("1".repeat(64) to 2)),
                fact("e".repeat(64), DogecoinSpvWalletConfidence.LOCAL_PENDING, spends = listOf(previous to 1)),
                fact("f".repeat(64), DogecoinSpvWalletConfidence.DEAD_OR_CONFLICTING, spends = listOf(previous to 1))
            )
        )!!
        assertEquals(2, evidence.conflictingSpends.size)
        with(evidence.conflictingSpends.first { it.spendingTxid == other }) {
            assertEquals(previous, reservedTxid)
            assertEquals(1, reservedVout)
            assertEquals(other, spendingTxid)
            assertEquals(6, depth)
            assertEquals(DogecoinSpvEvidenceProvenance.CHAIN, provenance)
        }
        with(evidence.conflictingSpends.first { it.spendingTxid == "2".repeat(64) }) {
            assertEquals(0, depth)
            assertEquals(DogecoinSpvEvidenceProvenance.PEER, provenance)
        }
    }

    @Test
    fun `exact tx is never also reported as conflicting spend`() {
        val evidence = DogecoinSpvSettlementEvidencePolicy.derive(
            status(), expected, listOf(previous to 1), tip,
            listOf(fact(expected, DogecoinSpvWalletConfidence.CHAIN_BUILDING, 3, listOf(previous to 1)))
        )!!
        assertTrue(evidence.conflictingSpends.isEmpty())
    }

    @Test
    fun `testnet invalid identifiers and duplicate reservations fail closed`() {
        assertNull(
            DogecoinSpvSettlementEvidencePolicy.derive(
                status().copy(network = DogecoinNetwork.TESTNET), expected, emptyList(), tip, emptyList()
            )
        )
        assertNull(
            DogecoinSpvSettlementEvidencePolicy.derive(status(), "not-a-txid", emptyList(), tip, emptyList())
        )
        assertNull(
            DogecoinSpvSettlementEvidencePolicy.derive(status(), expected, emptyList(), tip, emptyList())
        )
        assertNull(
            DogecoinSpvSettlementEvidencePolicy.derive(
                status(), expected, listOf(previous to 1, previous.uppercase() to 1), tip, emptyList()
            )
        )
    }

    @Test
    fun `unsynced observation remains observed context but cannot imply confirmation`() {
        val evidence = DogecoinSpvSettlementEvidencePolicy.derive(
            status(synced = false), expected, listOf(previous to 1), tip,
            listOf(fact(expected, DogecoinSpvWalletConfidence.CHAIN_BUILDING, 10))
        )!!
        assertEquals(10, evidence.exactTransactionDepth)
        assertFalse(evidence.fullySynced)
    }
}
