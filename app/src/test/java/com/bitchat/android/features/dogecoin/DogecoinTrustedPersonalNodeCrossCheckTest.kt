package com.bitchat.android.features.dogecoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DogecoinTrustedPersonalNodeCrossCheckTest {
    private val address = DogecoinKeyGenerator.fromPrivateKeyHex(
        "0000000000000000000000000000000000000000000000000000000000000011",
        DogecoinNetwork.MAINNET
    ).address
    private val script = DogecoinHex.encode(
        DogecoinAddress.p2pkhScript(address, DogecoinNetwork.MAINNET)
    )
    private val expectedTxid = "11".repeat(32)
    private val previousTxid = "22".repeat(32)
    private val spvTip = "33".repeat(32)
    private val nodeTip = spvTip

    @Test
    fun `fully synced exact SPV depth and node-spent inputs agree`() {
        val evidence = compare(
            spv = spv(exactDepth = 6),
            snapshot = snapshot(nodeTxOut = null)
        )

        assertEquals(DogecoinTrustedPersonalNodeCrossCheckResult.AGREEMENT, evidence.result)
        assertEquals("$spvTip:$nodeTip", evidence.comparisonId)
        assertTrue(evidence.fullySyncedMainnet)
        assertEquals(6, evidence.confirmationContextDepth)
        assertFalse(evidence.hasConflictingSpend)
    }

    @Test
    fun `node claiming input unspent after exact SPV settlement conflicts`() {
        val evidence = compare(spv(exactDepth = 7), snapshot(nodeTxOut = matchingTxOut()))

        assertEquals(DogecoinTrustedPersonalNodeCrossCheckResult.CONFLICT, evidence.result)
        assertEquals(7, evidence.confirmationContextDepth)
    }

    @Test
    fun `node amount or script lie conflicts with frozen proof reference`() {
        val amountLie = compare(
            spv(),
            snapshot(nodeTxOut = matchingTxOut().copy(amountKoinu = 499_999_999L))
        )
        val scriptLie = compare(
            spv(),
            snapshot(nodeTxOut = matchingTxOut().copy(scriptPubKeyHex = "76a914" + "55".repeat(20) + "88ac"))
        )

        assertEquals(DogecoinTrustedPersonalNodeCrossCheckResult.CONFLICT, amountLie.result)
        assertEquals(DogecoinTrustedPersonalNodeCrossCheckResult.CONFLICT, scriptLie.result)
    }

    @Test
    fun `absence without exact SPV history is inconclusive`() {
        val evidence = compare(spv(exactDepth = null), snapshot(nodeTxOut = null))

        assertEquals(DogecoinTrustedPersonalNodeCrossCheckResult.INCONCLUSIVE, evidence.result)
        assertEquals(0, evidence.confirmationContextDepth)
    }

    @Test
    fun `stable different SPV spender alone does not accuse the profile`() {
        val conflict = DogecoinSpvConflictingSpendEvidence(
            reservedTxid = previousTxid,
            reservedVout = 0,
            spendingTxid = "55".repeat(32),
            depth = 6,
            provenance = DogecoinSpvEvidenceProvenance.CHAIN
        )
        val evidence = compare(
            spv(exactDepth = 6, conflicts = listOf(conflict)),
            snapshot(nodeTxOut = null)
        )

        assertEquals(DogecoinTrustedPersonalNodeCrossCheckResult.INCONCLUSIVE, evidence.result)
        assertTrue(evidence.hasConflictingSpend)
    }

    @Test
    fun `node claiming reserved input unspent against stable conflicting spend conflicts`() {
        val conflict = DogecoinSpvConflictingSpendEvidence(
            reservedTxid = previousTxid,
            reservedVout = 0,
            spendingTxid = "55".repeat(32),
            depth = 6,
            provenance = DogecoinSpvEvidenceProvenance.CHAIN
        )

        val evidence = compare(
            spv(exactDepth = null, conflicts = listOf(conflict)),
            snapshot(nodeTxOut = matchingTxOut())
        )

        assertEquals(DogecoinTrustedPersonalNodeCrossCheckResult.CONFLICT, evidence.result)
        assertTrue(evidence.hasConflictingSpend)
    }

    @Test
    fun `unsynced or shallow SPV cannot produce agreement`() {
        val unsynced = compare(
            spv(exactDepth = 6, fullySynced = false),
            snapshot(nodeTxOut = null)
        )
        val shallow = compare(spv(exactDepth = 5), snapshot(nodeTxOut = null))

        assertEquals(DogecoinTrustedPersonalNodeCrossCheckResult.INCONCLUSIVE, unsynced.result)
        assertFalse(unsynced.fullySyncedMainnet)
        assertEquals(DogecoinTrustedPersonalNodeCrossCheckResult.INCONCLUSIVE, shallow.result)
    }

    @Test
    fun `foreign SPV transaction cannot settle the bound attempt`() {
        val foreign = spv(exactDepth = 6).copy(expectedTxid = "aa".repeat(32))

        val evidence = compare(foreign, snapshot(nodeTxOut = null))

        assertEquals(DogecoinTrustedPersonalNodeCrossCheckResult.INCONCLUSIVE, evidence.result)
    }

    @Test
    fun `different or stale node tip is inconclusive`() {
        val differentHash = compare(
            spv(exactDepth = 6),
            snapshot(nodeTxOut = null).copy(
                tip = DogecoinTrustedPersonalNodeBlockTip(100, "44".repeat(32))
            )
        )
        val staleHeight = compare(
            spv(exactDepth = 6),
            snapshot(nodeTxOut = matchingTxOut()).copy(
                tip = DogecoinTrustedPersonalNodeBlockTip(99, "44".repeat(32))
            )
        )

        assertEquals(DogecoinTrustedPersonalNodeCrossCheckResult.INCONCLUSIVE, differentHash.result)
        assertEquals(DogecoinTrustedPersonalNodeCrossCheckResult.INCONCLUSIVE, staleHeight.result)
    }

    private fun compare(
        spv: DogecoinSpvSettlementEvidence,
        snapshot: DogecoinTrustedPersonalNodeCrossCheckSnapshot
    ): DogecoinTrustedPersonalNodeCrossCheckEvidence =
        DogecoinTrustedPersonalNodeCrossCheck.compare(spv, snapshot)

    private fun spv(
        exactDepth: Int? = null,
        fullySynced: Boolean = true,
        conflicts: List<DogecoinSpvConflictingSpendEvidence> = emptyList()
    ) = DogecoinSpvSettlementEvidence(
        network = DogecoinNetwork.MAINNET,
        expectedTxid = expectedTxid,
        exactTransactionDepth = exactDepth,
        exactTransactionProvenance = if (exactDepth == null) {
            DogecoinSpvEvidenceProvenance.NONE
        } else {
            DogecoinSpvEvidenceProvenance.CHAIN
        },
        fullySynced = fullySynced,
        peerCount = 8,
        peerFloorMet = true,
        chainHeight = 100,
        bestPeerHeight = 100,
        chainTipHash = spvTip,
        conflictingSpends = conflicts
    )

    private fun snapshot(
        nodeTxOut: DogecoinTrustedPersonalNodeCrossCheckTxOut?
    ) = DogecoinTrustedPersonalNodeCrossCheckSnapshot(
        binding = DogecoinTrustedPersonalNodeSessionBinding(
            origin = "https://phone.tailnet.ts.net",
            network = DogecoinNetwork.MAINNET,
            androidAddress = address,
            coreWalletId = "watch.dat",
            policyVersion = DOGECOIN_TRUSTED_PERSONAL_NODE_POLICY_VERSION,
            profileRevision = 1L
        ),
        expectedTxid = expectedTxid,
        tip = DogecoinTrustedPersonalNodeBlockTip(100, nodeTip),
        outpoints = listOf(
            DogecoinTrustedPersonalNodeCrossCheckOutpoint(
                txid = previousTxid,
                vout = 0,
                expectedAmountKoinu = 500_000_000L,
                expectedScriptPubKeyHex = script,
                nodeTxOut = nodeTxOut
            )
        ),
        capturedAtMillis = 123_456L
    )

    private fun matchingTxOut() = DogecoinTrustedPersonalNodeCrossCheckTxOut(
        amountKoinu = 500_000_000L,
        scriptPubKeyHex = script,
        confirmations = 12
    )
}
