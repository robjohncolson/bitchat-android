package com.bitchat.android.features.dogecoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DogecoinTrustedPersonalNodeSendTest {

    private val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
        "0000000000000000000000000000000000000000000000000000000000000001"
    )
    private val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
        "0000000000000000000000000000000000000000000000000000000000000002"
    )
    private val foreignRecipient = DogecoinKeyGenerator.fromPrivateKeyHex(
        "0000000000000000000000000000000000000000000000000000000000000003"
    )

    @Test
    fun `durable TPN attempt cannot be presented confirmed by node or unsynced SPV depth`() {
        DogecoinTrustedPersonalNodeAttemptState.values().forEach { state ->
            assertEquals(0, dogecoinPresentedConfirmationDepth(99, state))
            assertFalse(dogecoinPresentationIsConfirmed(99, 6, state))
        }
        assertEquals(6, dogecoinPresentedConfirmationDepth(6, null))
        assertTrue(dogecoinPresentationIsConfirmed(6, 6, null))
    }

    @Test
    fun `only durable independent SPV settlement depth advances TPN presentation`() {
        assertEquals(
            3,
            dogecoinPresentedConfirmationDepth(
                observedDepth = 99,
                trustedPersonalNodeAttemptState = DogecoinTrustedPersonalNodeAttemptState.OBSERVED,
                independentSpvDepth = 3
            )
        )
        assertFalse(
            dogecoinPresentationIsConfirmed(
                observedDepth = 99,
                confirmationTarget = 6,
                trustedPersonalNodeAttemptState = DogecoinTrustedPersonalNodeAttemptState.OBSERVED,
                independentSpvDepth = 3
            )
        )
        assertEquals(
            6,
            dogecoinPresentedConfirmationDepth(
                observedDepth = 0,
                trustedPersonalNodeAttemptState = DogecoinTrustedPersonalNodeAttemptState.CONFIRMED,
                independentSpvDepth = 6
            )
        )
        assertTrue(
            dogecoinPresentationIsConfirmed(
                observedDepth = 0,
                confirmationTarget = 6,
                trustedPersonalNodeAttemptState = DogecoinTrustedPersonalNodeAttemptState.CONFIRMED,
                independentSpvDepth = 6
            )
        )
        listOf(
            DogecoinTrustedPersonalNodeAttemptState.CLAIMED,
            DogecoinTrustedPersonalNodeAttemptState.CONFLICTED
        ).forEach { state ->
            assertEquals(0, dogecoinPresentedConfirmationDepth(99, state, 99))
            assertFalse(dogecoinPresentationIsConfirmed(99, 6, state, 99))
        }
    }

    @Test
    fun `typed route unlocks only current mainnet active session with a nonempty fresh proof`() {
        val fixture = activeFixture()
        val route = assertNotNullValue(fixture.holder.beginSpendRoute(1_004L))

        assertTrue(
            dogecoinSpendRouteAllowed(
                DogecoinNetwork.MAINNET,
                route,
                fixture.holder,
                1_004L
            )
        )
        assertFalse(
            dogecoinSpendRouteAllowed(
                DogecoinNetwork.TESTNET,
                route,
                fixture.holder,
                1_004L
            )
        )
        assertTrue(dogecoinSpendRouteAllowed(DogecoinNetwork.MAINNET, DogecoinSpendRoute.SPV))
        assertFalse(dogecoinSpendRouteAllowed(DogecoinNetwork.MAINNET, DogecoinSpendRoute.GENERIC_RPC))
        assertFalse(dogecoinSpendRouteAllowed(DogecoinNetwork.MAINNET, DogecoinSpendRoute.EXPLORER))
        assertTrue(dogecoinSpendRouteAllowed(DogecoinNetwork.TESTNET, DogecoinSpendRoute.GENERIC_RPC))

        assertTrue(fixture.holder.cancelSpendAuthorization(route.authorization))
        assertFalse(
            dogecoinSpendRouteAllowed(
                DogecoinNetwork.MAINNET,
                route,
                fixture.holder,
                1_005L
            )
        )
    }

    @Test
    fun `empty incomplete and expired proofs cannot authorize or sign`() {
        val empty = activeFixture(withCandidate = false)
        assertNull(empty.holder.beginSpendAuthorization(1_004L))

        val fixture = activeFixture()
        val authorization = assertNotNullValue(fixture.holder.beginSpendAuthorization(1_004L))
        assertThrows(IllegalStateException::class.java) {
            DogecoinTrustedPersonalNodeTransactionBuilder.createFrozenReview(
                wallet = wallet,
                sessionHolder = fixture.holder,
                authorization = authorization,
                nowMonotonicMillis = 121_003L,
                recipientAddress = recipient.address,
                amount = "2.0"
            )
        }
        assertFalse(fixture.holder.isSpendAuthorizationCurrent(authorization, 121_003L))
    }

    @Test
    fun `profile refresh and replacement authorization revoke old review lease`() {
        val fixture = activeFixture()
        val first = assertNotNullValue(fixture.holder.beginSpendAuthorization(1_004L))
        val second = assertNotNullValue(fixture.holder.beginSpendAuthorization(1_005L))
        assertFalse(fixture.holder.isSpendAuthorizationCurrent(first, 1_005L))
        assertTrue(fixture.holder.isSpendAuthorizationCurrent(second, 1_005L))

        assertNotNull(fixture.holder.refreshActiveReadSnapshot(1_006L))
        assertFalse(fixture.holder.isSpendAuthorizationCurrent(second, 1_006L))
        assertNull(fixture.holder.freshProofSnapshot(1_006L))
    }

    @Test
    fun `independent cross-check freezes existing spend authorization and proof`() {
        val fixture = activeFixture()
        val authorization = assertNotNullValue(fixture.holder.beginSpendAuthorization(1_004L))
        assertTrue(fixture.holder.isSpendAuthorizationCurrent(authorization, 1_004L))

        fixture.holder.freezeSpendForIndependentCrossCheck()

        assertFalse(fixture.holder.isSpendAuthorizationCurrent(authorization, 1_005L))
        assertNull(fixture.holder.freshProofSnapshot(1_005L))
        assertNull(fixture.holder.beginSpendAuthorization(1_005L))
        assertEquals(DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED, fixture.holder.state)
    }

    @Test
    fun `proof backed builder freezes exact input output fee and txid`() {
        val fixture = activeFixture()
        val authorization = assertNotNullValue(fixture.holder.beginSpendAuthorization(1_004L))
        val review = DogecoinTrustedPersonalNodeTransactionBuilder.createFrozenReview(
            wallet = wallet,
            sessionHolder = fixture.holder,
            authorization = authorization,
            nowMonotonicMillis = 1_004L,
            recipientAddress = recipient.address,
            amount = "2.0"
        )

        assertSame(fixture.proof, review.proofSnapshot)
        assertEquals(fixture.profile.toSessionBinding(), review.binding)
        assertEquals(1, review.selectedPrevouts.size)
        assertSame(fixture.candidate.verifiedPrevout, review.selectedPrevouts.single())
        assertEquals(10L * DogecoinProtocol.KOINU_PER_DOGE, review.inputTotalKoinu)
        assertEquals(2L * DogecoinProtocol.KOINU_PER_DOGE, review.sendAmountKoinu)
        assertEquals(DogecoinProtocol.MIN_TX_FEE_KOINU, review.feeKoinu)
        assertEquals(review.txid, DogecoinTransactionBuilder.transactionId(review.rawTransactionHex))
        assertFalse(review.isExpired(review.createdAtMillis, DOGECOIN_SIGNED_TX_MAX_AGE_MILLIS))
        review.requireRevalidated()

        // A TPN review is not the generic copyable transaction type used by raw export/helper routes.
        assertFalse(
            DogecoinTrustedPersonalNodeFrozenReview::class.java.declaredMethods.any {
                it.name == "copy" || it.name.startsWith("component")
            }
        )
    }

    @Test
    fun `same byte recovery requires a newly fresh matching proof and revision`() {
        val original = activeFixture()
        val originalAuthorization = assertNotNullValue(
            original.holder.beginSpendAuthorization(1_004L)
        )
        val originalReview = DogecoinTrustedPersonalNodeTransactionBuilder.createFrozenReview(
            wallet = wallet,
            sessionHolder = original.holder,
            authorization = originalAuthorization,
            nowMonotonicMillis = 1_004L,
            recipientAddress = recipient.address,
            amount = "2.0"
        )
        val persisted = DogecoinTrustedPersonalNodeAttemptReviewRecord.fromFrozenReview(
            review = originalReview,
            mainnetAcknowledged = true,
            personalNodeOracleAcknowledged = true
        )

        val restarted = activeFixture()
        val restartedAuthorization = assertNotNullValue(
            restarted.holder.beginSpendAuthorization(1_004L)
        )
        val recovered = DogecoinTrustedPersonalNodeFrozenReview.recover(
            sessionHolder = restarted.holder,
            authorization = restartedAuthorization,
            nowMonotonicMillis = 1_004L,
            persisted = persisted
        )
        assertEquals(originalReview.rawTransactionHex, recovered.rawTransactionHex)
        assertEquals(originalReview.txid, recovered.txid)
        assertEquals(originalReview.selectedPrevouts.single().txid, recovered.selectedPrevouts.single().txid)
        recovered.requireRevalidated()

        restarted.holder.cancelSpendAuthorization(restartedAuthorization)
        assertThrows(IllegalStateException::class.java) {
            DogecoinTrustedPersonalNodeFrozenReview.recover(
                sessionHolder = restarted.holder,
                authorization = restartedAuthorization,
                nowMonotonicMillis = 1_005L,
                persisted = persisted
            )
        }
    }

    @Test
    fun `scalar amount lie is rejected even when signed bytes are otherwise unchanged`() {
        val fixture = activeFixture()
        val authorization = assertNotNullValue(fixture.holder.beginSpendAuthorization(1_004L))
        val signed = genericSignedTransaction(fixture)

        assertFailureContains("input total differs") {
            DogecoinTrustedPersonalNodeFrozenReview.freeze(
                authorization,
                signed.copy(inputTotalKoinu = signed.inputTotalKoinu + 1L),
                listOf(fixture.candidate)
            )
        }
        assertFailureContains("actual fee differs") {
            DogecoinTrustedPersonalNodeFrozenReview.freeze(
                authorization,
                signed.copy(feeKoinu = signed.feeKoinu + 1L),
                listOf(fixture.candidate)
            )
        }
    }

    @Test
    fun `wrong recipient script and fabricated previous outpoint fail local reparse`() {
        val fixture = activeFixture()
        val authorization = assertNotNullValue(fixture.holder.beginSpendAuthorization(1_004L))
        val signed = genericSignedTransaction(fixture)

        val expectedRecipientScript = DogecoinHex.encode(
            DogecoinAddress.scriptPubKey(recipient.address, DogecoinNetwork.MAINNET)
        )
        val wrongRecipientScript = DogecoinHex.encode(
            DogecoinAddress.scriptPubKey(foreignRecipient.address, DogecoinNetwork.MAINNET)
        )
        val wrongOutputHex = signed.rawTransactionHex.replaceFirst(
            expectedRecipientScript,
            wrongRecipientScript
        )
        assertFailureContains("outputs differ") {
            DogecoinTrustedPersonalNodeFrozenReview.freeze(
                authorization,
                signed.copy(
                    rawTransactionHex = wrongOutputHex,
                    txid = DogecoinTransactionBuilder.transactionId(wrongOutputHex)
                ),
                listOf(fixture.candidate)
            )
        }

        val previousTxidLittleEndian = fixture.candidate.verifiedPrevout.txid
            .chunked(2)
            .reversed()
            .joinToString("")
        val fabricatedInputHex = signed.rawTransactionHex.replaceFirst(
            previousTxidLittleEndian,
            "ff".repeat(32)
        )
        assertFailureContains("inputs do not exactly match") {
            DogecoinTrustedPersonalNodeFrozenReview.freeze(
                authorization,
                signed.copy(
                    rawTransactionHex = fabricatedInputHex,
                    txid = DogecoinTransactionBuilder.transactionId(fabricatedInputHex)
                ),
                listOf(fixture.candidate)
            )
        }
    }

    @Test
    fun `absolute and ceil relative fee boundaries are hard blocks without override`() {
        assertNull(
            dogecoinTrustedPersonalNodeFeeHardBlock(
                DogecoinProtocol.HIGH_FEE_ABSOLUTE_KOINU - 1L,
                20L * DogecoinProtocol.KOINU_PER_DOGE
            )
        )
        assertEquals(
            DogecoinTrustedPersonalNodeFeeHardBlock.ABSOLUTE,
            dogecoinTrustedPersonalNodeFeeHardBlock(
                DogecoinProtocol.HIGH_FEE_ABSOLUTE_KOINU,
                20L * DogecoinProtocol.KOINU_PER_DOGE
            )
        )

        val sendAmount = 500_000_001L
        val ceilTenPercent = 50_000_001L
        assertNull(dogecoinTrustedPersonalNodeFeeHardBlock(ceilTenPercent - 1L, sendAmount))
        assertEquals(
            DogecoinTrustedPersonalNodeFeeHardBlock.RELATIVE,
            dogecoinTrustedPersonalNodeFeeHardBlock(ceilTenPercent, sendAmount)
        )
        assertThrows(IllegalArgumentException::class.java) {
            requireDogecoinTrustedPersonalNodeFeeAllowed(ceilTenPercent, sendAmount)
        }
    }

    private fun genericSignedTransaction(fixture: Fixture): DogecoinSignedTransaction =
        DogecoinTransactionBuilder.createSignedTransactionFromVerifiedPrevouts(
            wallet = wallet,
            proofCandidates = fixture.proof.proofCandidates,
            recipientAddress = recipient.address,
            amount = "2.0"
        )

    private fun activeFixture(withCandidate: Boolean = true): Fixture {
        val profile = profile()
        val holder = DogecoinTrustedPersonalNodeSessionHolder(
            DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE,
            profile
        )
        val activation = assertNotNullValue(holder.beginActivation(1_000L))
        assertTrue(holder.recordSuccessfulReadSnapshot(activation, displaySnapshot(profile), 1_001L))
        val proofToken = assertNotNullValue(holder.beginProofSnapshot(1_002L))
        val candidate = proofCandidate()
        val candidates = if (withCandidate) listOf(candidate) else emptyList()
        val proof = DogecoinTrustedPersonalNodeProofSnapshot.complete(
            binding = profile.toSessionBinding(),
            capturedAtMonotonicMillis = proofToken.startedAtMonotonicMillis,
            startTip = DogecoinTrustedPersonalNodeBlockTip(100, "11".repeat(32)),
            endTip = DogecoinTrustedPersonalNodeBlockTip(100, "11".repeat(32)),
            proofCandidates = candidates,
            totalProofBytes = candidates.sumOf { it.verifiedPrevout.previousTransactionByteCount }
        )
        assertTrue(holder.recordSuccessfulProofSnapshot(proofToken, proof, 1_003L))
        return Fixture(profile, holder, candidate, proof)
    }

    private fun profile() = DogecoinTrustedPersonalNodeProfile(
        origin = "https://athena.tailnet.ts.net",
        network = DogecoinNetwork.MAINNET,
        androidAddress = wallet.address,
        coreWalletId = "bitchat-watch.dat",
        policyVersion = DOGECOIN_TRUSTED_PERSONAL_NODE_POLICY_VERSION,
        revision = 7L,
        authorizedAtMillis = 3_000L,
        rescanAttested = true,
        rescanAttestedAtMillis = 2_000L
    )

    private fun displaySnapshot(profile: DogecoinTrustedPersonalNodeProfile) =
        DogecoinTrustedPersonalNodeDisplaySnapshot(
            profileRevision = profile.revision,
            origin = profile.origin,
            androidAddress = profile.androidAddress,
            coreWalletId = profile.coreWalletId,
            blocks = 100,
            headers = 100,
            verificationProgress = 1.0,
            peerCount = DogecoinRpcClient.DOGECOIN_TPN_MIN_MAINNET_PEERS,
            balance = DogecoinTrustedPersonalNodeDisplayBalance(
                confirmedKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
                unconfirmedKoinu = 0L,
                utxoCount = 1
            ),
            activity = emptyList()
        )

    private fun proofCandidate(): DogecoinTrustedPersonalNodeProofCandidate {
        val expectedScript = DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
        val raw = previousTransaction(
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKey = expectedScript
        )
        val rawHex = DogecoinHex.encode(raw)
        val txid = DogecoinTransactionBuilder.transactionId(rawHex)
        val verified = DogecoinVerifiedPrevout.verify(
            rawPreviousTransactionHex = rawHex,
            expectedTxid = txid,
            vout = 0,
            expectedP2pkhScript = expectedScript,
            source = DogecoinTrustedPersonalNodePreviousTransactionSource.WALLET_GETTRANSACTION
        )
        return DogecoinTrustedPersonalNodeProofCandidate.verifiedAtTip(
            verifiedPrevout = verified,
            finalConfirmations = DOGECOIN_TPN_MIN_INPUT_CONFIRMATIONS,
            finalBestBlockHash = "11".repeat(32)
        )
    }

    private fun previousTransaction(amountKoinu: Long, scriptPubKey: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(uint32Le(1L))
            write(1)
            write(ByteArray(32))
            write(uint32Le(0L))
            write(1)
            write(0x51)
            write(uint32Le(0xffffffffL))
            write(1)
            write(int64Le(amountKoinu))
            write(scriptPubKey.size)
            write(scriptPubKey)
            write(uint32Le(0L))
        }.toByteArray()

    private fun uint32Le(value: Long) = ByteArray(4) { index ->
        ((value ushr (index * 8)) and 0xffL).toByte()
    }

    private fun int64Le(value: Long) = ByteArray(8) { index ->
        ((value ushr (index * 8)) and 0xffL).toByte()
    }

    private fun assertFailureContains(fragment: String, block: () -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java, block)
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(fragment, ignoreCase = true))
    }

    private fun <T : Any> assertNotNullValue(value: T?): T {
        assertNotNull(value)
        return requireNotNull(value)
    }

    private data class Fixture(
        val profile: DogecoinTrustedPersonalNodeProfile,
        val holder: DogecoinTrustedPersonalNodeSessionHolder,
        val candidate: DogecoinTrustedPersonalNodeProofCandidate,
        val proof: DogecoinTrustedPersonalNodeProofSnapshot
    )
}
