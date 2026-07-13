package com.bitchat.android.features.dogecoin

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DogecoinTrustedPersonalNodeAttemptStoreTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var store: DogecoinTrustedPersonalNodeAttemptStore

    private val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
        "0000000000000000000000000000000000000000000000000000000000000001",
        DogecoinNetwork.MAINNET
    )
    private val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
        "0000000000000000000000000000000000000000000000000000000000000002",
        DogecoinNetwork.MAINNET
    ).address
    private val binding by lazy {
        DogecoinTrustedPersonalNodeSessionBinding(
            origin = "https://dogebox.tail1234.ts.net",
            network = DogecoinNetwork.MAINNET,
            androidAddress = wallet.address,
            coreWalletId = "bitchat-watch.dat",
            policyVersion = DOGECOIN_TRUSTED_PERSONAL_NODE_POLICY_VERSION,
            profileRevision = 7L
        )
    }

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("dogecoin_tpn_attempt_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = DogecoinTrustedPersonalNodeAttemptStore(
            prefs = prefs,
            correlationIdFactory = { "ab".repeat(16) }
        )
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `attempt and reservations persist atomically across store instances`() {
        val review = frozenReview()
        val attempt = store.persistBeforeDisclosure(review)
        assertNotNull(attempt)
        assertEquals(DogecoinTrustedPersonalNodeAttemptState.READY_UNDISCLOSED, attempt!!.state)
        assertEquals(1, attempt.review.proofReferences.size)

        val restarted = DogecoinTrustedPersonalNodeAttemptStore(prefs)
        val loaded = restarted.load() as DogecoinTrustedPersonalNodeAttemptLoadResult.Available
        assertEquals(attempt.correlationId, loaded.attempt.correlationId)
        assertTrue(loaded.attempt.review.sameAs(review))
        assertEquals(
            review.proofReferences.single().txid,
            loaded.attempt.review.proofReferences.single().txid
        )
    }

    @Test
    fun `same frozen bytes are idempotent but a different unresolved send is rejected`() {
        val review = frozenReview()
        val first = store.persistBeforeDisclosure(review)!!
        val second = store.persistBeforeDisclosure(review)!!
        assertEquals(first.correlationId, second.correlationId)

        val different = frozenReview(recipientAddress = wallet.address)
        assertNull(store.persistBeforeDisclosure(different))
        val loaded = store.load() as DogecoinTrustedPersonalNodeAttemptLoadResult.Available
        assertEquals(first.review.localTxid, loaded.attempt.review.localTxid)
    }

    @Test
    fun `unknown same-byte retry accepts a fresh proof tip but rejects raw or prevout metadata changes`() {
        val fixture = signedFixture()
        val originalReview = canonicalReview(
            fixture.transaction,
            proofSnapshot(fixture.prevout, height = 5_000_000, hash = "11".repeat(32))
        )
        val first = store.persistBeforeDisclosure(
            originalReview,
            mainnetAcknowledged = true,
            personalNodeOracleAcknowledged = true
        )!!
        assertTrue(store.markSubmissionUnknown(first.correlationId, first.review.localTxid))

        val freshTipReview = canonicalReview(
            fixture.transaction,
            proofSnapshot(fixture.prevout, height = 5_000_001, hash = "22".repeat(32))
        )
        val retry = store.persistBeforeDisclosure(
            freshTipReview,
            mainnetAcknowledged = true,
            personalNodeOracleAcknowledged = true
        )
        assertEquals(first.correlationId, retry?.correlationId)
        // Recovery reuses the original idempotency record; it never rewrites the durable historical tip.
        assertEquals(first.review.endTip, retry?.review?.endTip)

        val changedRawFixture = signedFixture(recipientAddress = wallet.address)
        assertNull(
            store.persistBeforeDisclosure(
                canonicalReview(
                    changedRawFixture.transaction,
                    proofSnapshot(changedRawFixture.prevout, 5_000_001, "22".repeat(32))
                ),
                mainnetAcknowledged = true,
                personalNodeOracleAcknowledged = true
            )
        )

        val changedSourcePrevout = DogecoinVerifiedPrevout.verify(
            rawPreviousTransactionHex = fixture.prevout.rawPreviousTransactionHex,
            expectedTxid = fixture.prevout.txid,
            vout = fixture.prevout.vout,
            expectedP2pkhScript = DogecoinAddress.p2pkhScript(
                wallet.address,
                DogecoinNetwork.MAINNET
            ),
            source = DogecoinTrustedPersonalNodePreviousTransactionSource.TXINDEX_GETRAWTRANSACTION
        )
        assertNull(
            store.persistBeforeDisclosure(
                canonicalReview(
                    fixture.transaction,
                    proofSnapshot(changedSourcePrevout, 5_000_001, "22".repeat(32))
                ),
                mainnetAcknowledged = true,
                personalNodeOracleAcknowledged = true
            )
        )
    }

    @Test
    fun `disclosure and claim transitions are monotonic and never release reservations`() {
        val attempt = store.persistBeforeDisclosure(frozenReview())!!
        assertFalse(store.markClaimed(attempt.correlationId, attempt.review.localTxid))
        assertTrue(store.markSubmissionUnknown(attempt.correlationId, attempt.review.localTxid))
        assertTrue(store.markSubmissionUnknown(attempt.correlationId, attempt.review.localTxid))
        assertFalse(store.markSubmissionUnknown(attempt.correlationId, "00".repeat(32)))
        assertTrue(store.markClaimed(attempt.correlationId, attempt.review.localTxid))
        assertTrue(store.markClaimed(attempt.correlationId, attempt.review.localTxid))
        assertFalse(store.markSubmissionUnknown(attempt.correlationId, attempt.review.localTxid))

        val claimed = (store.load() as DogecoinTrustedPersonalNodeAttemptLoadResult.Available).attempt
        assertEquals(DogecoinTrustedPersonalNodeAttemptState.CLAIMED, claimed.state)
        assertEquals(attempt.review.proofReferences.single().txid, claimed.review.proofReferences.single().txid)
        assertNull(store.persistBeforeDisclosure(attempt.review))
        assertNull(store.persistBeforeDisclosure(frozenReview(recipientAddress = wallet.address)))
    }

    @Test
    fun `retry requires same binding and unexpired non-claimed bytes`() {
        val attempt = store.persistBeforeDisclosure(frozenReview())!!
        val createdAt = attempt.review.createdAtMillis
        assertNotNull(store.retryableAttempt(binding, createdAt))
        assertNotNull(store.retryableAttempt(binding, attempt.review.expiresAtMillis))
        assertNull(store.retryableAttempt(binding, createdAt - 1L))
        assertNull(store.retryableAttempt(binding, attempt.review.expiresAtMillis + 1L))
        assertNull(store.retryableAttempt(binding.copy(profileRevision = 8L), createdAt))
        assertNull(store.retryableAttempt(binding.copy(origin = "https://sibling.tail1234.ts.net"), createdAt))

        assertTrue(store.markSubmissionUnknown(attempt.correlationId, attempt.review.localTxid))
        assertNotNull(store.retryableAttempt(binding, createdAt))
        assertTrue(store.markClaimed(attempt.correlationId, attempt.review.localTxid))
        assertNull(store.retryableAttempt(binding, createdAt))
    }

    @Test
    fun `only fully synced independent SPV advances claim and persists exact provenance`() {
        val attempt = claimedAttempt()

        assertFalse(
            store.applySpvSettlementEvidence(
                attempt.correlationId,
                settlementEvidence(
                    attempt,
                    depth = 1,
                    provenance = DogecoinSpvEvidenceProvenance.CHAIN,
                    fullySynced = false
                )
            )
        )
        assertEquals(
            DogecoinTrustedPersonalNodeAttemptState.CLAIMED,
            availableAttempt().state
        )
        assertFalse(
            store.applySpvSettlementEvidence(
                attempt.correlationId,
                settlementEvidence(
                    attempt,
                    depth = 6,
                    provenance = DogecoinSpvEvidenceProvenance.NONE
                )
            )
        )

        assertTrue(
            store.applySpvSettlementEvidence(
                attempt.correlationId,
                settlementEvidence(
                    attempt,
                    depth = 0,
                    provenance = DogecoinSpvEvidenceProvenance.PEER
                )
            )
        )
        var observed = availableAttempt()
        assertEquals(DogecoinTrustedPersonalNodeAttemptState.OBSERVED, observed.state)
        assertEquals(0, observed.settlementSpvDepth)
        assertEquals(DogecoinSpvEvidenceProvenance.PEER, observed.settlementSpvProvenance)
        assertTrue(observed.hasReservedInputs)

        assertTrue(
            store.applySpvSettlementEvidence(
                attempt.correlationId,
                settlementEvidence(
                    attempt,
                    depth = 5,
                    provenance = DogecoinSpvEvidenceProvenance.CHAIN,
                    tipHash = "44".repeat(32)
                )
            )
        )
        observed = availableAttempt()
        assertEquals(DogecoinTrustedPersonalNodeAttemptState.OBSERVED, observed.state)
        assertEquals(5, observed.settlementSpvDepth)
        assertEquals(DogecoinSpvEvidenceProvenance.CHAIN, observed.settlementSpvProvenance)
        assertTrue(observed.hasReservedInputs)
        // A late exact Core response is weaker than SPV observation and succeeds idempotently.
        assertTrue(store.markClaimed(attempt.correlationId, attempt.review.localTxid))
        assertEquals(DogecoinTrustedPersonalNodeAttemptState.OBSERVED, availableAttempt().state)

        assertTrue(
            store.applySpvSettlementEvidence(
                attempt.correlationId,
                settlementEvidence(
                    attempt,
                    depth = 2,
                    provenance = DogecoinSpvEvidenceProvenance.CHAIN,
                    tipHash = "45".repeat(32)
                )
            )
        )
        observed = availableAttempt()
        assertEquals(2, observed.settlementSpvDepth)

        assertTrue(
            store.applySpvSettlementEvidence(
                attempt.correlationId,
                settlementEvidence(
                    attempt,
                    depth = DOGECOIN_TPN_SETTLEMENT_CONFIRMATIONS,
                    provenance = DogecoinSpvEvidenceProvenance.CHAIN,
                    tipHash = "55".repeat(32)
                )
            )
        )
        val confirmed = DogecoinTrustedPersonalNodeAttemptStore(prefs).load()
            as DogecoinTrustedPersonalNodeAttemptLoadResult.Available
        assertEquals(DogecoinTrustedPersonalNodeAttemptState.CONFIRMED, confirmed.attempt.state)
        assertEquals(DOGECOIN_TPN_SETTLEMENT_CONFIRMATIONS, confirmed.attempt.settlementSpvDepth)
        assertEquals(DogecoinSpvEvidenceProvenance.CHAIN, confirmed.attempt.settlementSpvProvenance)
        assertEquals("55".repeat(32), confirmed.attempt.settlementSpvTipHash)
        assertEquals(
            DogecoinTrustedPersonalNodeReservationState.RELEASED_EXACT_SPV_CONFIRMATION,
            confirmed.attempt.reservationState
        )
        assertFalse(confirmed.attempt.hasReservedInputs)
        assertTrue(store.markClaimed(attempt.correlationId, attempt.review.localTxid))
        assertEquals(DogecoinTrustedPersonalNodeAttemptState.CONFIRMED, availableAttempt().state)
    }

    @Test
    fun `only stable SPV conflicting spend releases reservation and records terminal conflict`() {
        val attempt = claimedAttempt()
        val reserved = attempt.review.proofReferences.single()
        val conflictingTxid = "66".repeat(32)

        assertTrue(
            store.applySpvSettlementEvidence(
                attempt.correlationId,
                settlementEvidence(
                    attempt,
                    depth = null,
                    provenance = DogecoinSpvEvidenceProvenance.NONE,
                    conflicts = listOf(
                        DogecoinSpvConflictingSpendEvidence(
                            reservedTxid = reserved.txid,
                            reservedVout = reserved.vout,
                            spendingTxid = conflictingTxid,
                            depth = 5,
                            provenance = DogecoinSpvEvidenceProvenance.CHAIN
                        )
                    )
                )
            )
        )
        assertEquals(DogecoinTrustedPersonalNodeAttemptState.CLAIMED, availableAttempt().state)
        assertTrue(availableAttempt().hasReservedInputs)

        assertTrue(
            store.applySpvSettlementEvidence(
                attempt.correlationId,
                settlementEvidence(
                    attempt,
                    depth = null,
                    provenance = DogecoinSpvEvidenceProvenance.NONE,
                    tipHash = "77".repeat(32),
                    conflicts = listOf(
                        DogecoinSpvConflictingSpendEvidence(
                            reservedTxid = reserved.txid,
                            reservedVout = reserved.vout,
                            spendingTxid = conflictingTxid,
                            depth = DOGECOIN_TPN_SETTLEMENT_CONFIRMATIONS,
                            provenance = DogecoinSpvEvidenceProvenance.CHAIN
                        )
                    )
                )
            )
        )
        val conflicted = availableAttempt()
        assertEquals(DogecoinTrustedPersonalNodeAttemptState.CONFLICTED, conflicted.state)
        assertEquals(
            DogecoinTrustedPersonalNodeReservationState.RELEASED_CONFLICTING_SPEND,
            conflicted.reservationState
        )
        assertEquals(conflictingTxid, conflicted.conflictingSpendTxid)
        assertEquals(DOGECOIN_TPN_SETTLEMENT_CONFIRMATIONS, conflicted.conflictingSpendDepth)
        assertEquals("77".repeat(32), conflicted.settlementSpvTipHash)
        assertFalse(conflicted.hasReservedInputs)
        assertTrue(
            store.applySpvSettlementEvidence(
                attempt.correlationId,
                settlementEvidence(
                    attempt,
                    depth = DOGECOIN_TPN_SETTLEMENT_CONFIRMATIONS,
                    provenance = DogecoinSpvEvidenceProvenance.CHAIN,
                    tipHash = "88".repeat(32)
                )
            )
        )
        assertEquals(DogecoinTrustedPersonalNodeAttemptState.CONFLICTED, availableAttempt().state)
    }

    @Test
    fun `recovery keeps disclosed bytes reserved but explicitly abandons proven undisclosed bytes`() {
        val ready = store.persistBeforeDisclosure(frozenReview())!!
        assertEquals(
            DogecoinTrustedPersonalNodeRecoveryDisposition.RESERVED_EXPIRED_UNDISCLOSED,
            store.recoveryDisposition(
                currentBinding = binding,
                profileRevoked = false,
                nowMillis = ready.review.expiresAtMillis + 1L
            )
        )
        assertTrue(
            store.abandonNeverDisclosed(
                correlationId = ready.correlationId,
                localTxid = ready.review.localTxid,
                currentBinding = binding,
                profileRevoked = false,
                nowMillis = ready.review.expiresAtMillis + 1L
            )
        )
        assertEquals(
            DogecoinTrustedPersonalNodeReservationState.RELEASED_NEVER_DISCLOSED,
            availableAttempt().reservationState
        )

        prefs.edit().clear().commit()
        val disclosed = store.persistBeforeDisclosure(frozenReview())!!
        assertTrue(store.markSubmissionUnknown(disclosed.correlationId, disclosed.review.localTxid))
        assertEquals(
            DogecoinTrustedPersonalNodeRecoveryDisposition.RESERVED_REVOKED_PROFILE,
            store.recoveryDisposition(
                currentBinding = null,
                profileRevoked = true,
                nowMillis = disclosed.review.expiresAtMillis + 1L
            )
        )
        assertFalse(
            store.abandonNeverDisclosed(
                correlationId = disclosed.correlationId,
                localTxid = disclosed.review.localTxid,
                currentBinding = null,
                profileRevoked = true,
                nowMillis = disclosed.review.expiresAtMillis + 1L
            )
        )
        assertTrue(availableAttempt().hasReservedInputs)
    }

    @Test
    fun `D schema migrates fail closed and next transition writes settlement schema`() {
        val attempt = store.persistBeforeDisclosure(frozenReview())!!
        val ledger = JSONObject(prefs.getString("attempt_ledger", null)!!)
        val legacyAttempt = ledger.getJSONObject("attempt")
        listOf(
            "reservation_state",
            "settlement_spv_depth",
            "settlement_spv_provenance",
            "settlement_spv_tip_hash",
            "conflicting_spend_txid",
            "conflicting_spend_depth"
        ).forEach(legacyAttempt::remove)
        ledger.put("schema_version", 1)
        prefs.edit().putString("attempt_ledger", ledger.toString()).commit()

        val migrated = availableAttempt()
        assertEquals(DogecoinTrustedPersonalNodeAttemptState.READY_UNDISCLOSED, migrated.state)
        assertEquals(DogecoinTrustedPersonalNodeReservationState.RESERVED, migrated.reservationState)
        assertNull(migrated.settlementSpvDepth)
        assertEquals(DogecoinSpvEvidenceProvenance.NONE, migrated.settlementSpvProvenance)
        assertTrue(store.markSubmissionUnknown(attempt.correlationId, attempt.review.localTxid))
        assertEquals(2, JSONObject(prefs.getString("attempt_ledger", null)!!).getInt("schema_version"))
    }

    @Test
    fun `corrupt or unknown ledger is unavailable rather than empty`() {
        assertSame(DogecoinTrustedPersonalNodeAttemptLoadResult.Empty, store.load())
        prefs.edit().putString("attempt_ledger", "not-json").commit()
        assertSame(DogecoinTrustedPersonalNodeAttemptLoadResult.Unavailable, store.load())
        assertNull(store.persistBeforeDisclosure(frozenReview()))

        prefs.edit().putString(
            "attempt_ledger",
            """{"schema_version":2,"attempt":{}}"""
        ).commit()
        assertSame(DogecoinTrustedPersonalNodeAttemptLoadResult.Unavailable, store.load())
    }

    @Test
    fun `signed bytes txid scalar and selected proof must agree`() {
        val fixture = signedFixture()
        val snapshot = proofSnapshot(fixture.prevout)

        assertThrowsIllegalArgument {
            DogecoinTrustedPersonalNodeAttemptReviewRecord.fromFrozenReview(
                review = canonicalReview(
                    fixture.transaction.copy(txid = "00".repeat(32)),
                    snapshot
                ),
                mainnetAcknowledged = true,
                personalNodeOracleAcknowledged = true
            )
        }
        assertThrowsIllegalArgument {
            DogecoinTrustedPersonalNodeAttemptReviewRecord.fromFrozenReview(
                review = canonicalReview(
                    fixture.transaction.copy(sendAmountKoinu = fixture.transaction.sendAmountKoinu + 1L),
                    snapshot
                ),
                mainnetAcknowledged = true,
                personalNodeOracleAcknowledged = true
            )
        }
        assertThrowsIllegalArgument {
            DogecoinTrustedPersonalNodeAttemptReviewRecord.fromFrozenReview(
                review = canonicalReview(fixture.transaction, emptyProofSnapshot()),
                mainnetAcknowledged = true,
                personalNodeOracleAcknowledged = true
            )
        }
    }

    @Test
    fun `both per-spend acknowledgements are required before persistence material exists`() {
        val fixture = signedFixture()
        val snapshot = proofSnapshot(fixture.prevout)
        val canonical = canonicalReview(fixture.transaction, snapshot)
        assertNull(
            store.persistBeforeDisclosure(
                canonical,
                mainnetAcknowledged = true,
                personalNodeOracleAcknowledged = false
            )
        )
        assertSame(DogecoinTrustedPersonalNodeAttemptLoadResult.Empty, store.load())
        assertThrowsIllegalArgument {
            DogecoinTrustedPersonalNodeAttemptReviewRecord.fromFrozenReview(
                canonical,
                mainnetAcknowledged = true,
                personalNodeOracleAcknowledged = false
            )
        }
        assertThrowsIllegalArgument {
            DogecoinTrustedPersonalNodeAttemptReviewRecord.fromFrozenReview(
                canonical,
                mainnetAcknowledged = false,
                personalNodeOracleAcknowledged = true
            )
        }
        assertNotNull(
            store.persistBeforeDisclosure(
                canonical,
                mainnetAcknowledged = true,
                personalNodeOracleAcknowledged = true
            )
        )
    }

    @Test
    fun `printable models redact signed bytes binding and outpoints`() {
        val attempt = store.persistBeforeDisclosure(frozenReview())!!
        val rendered = attempt.toString() + attempt.review + attempt.review.proofReferences.single()
        assertFalse(rendered.contains(attempt.review.signedRawTransactionHex))
        assertFalse(rendered.contains(attempt.review.binding.origin))
        assertFalse(rendered.contains(attempt.review.proofReferences.single().txid))
        assertTrue(rendered.contains("<redacted>"))
    }

    @Test
    fun `attempt preferences are excluded from cloud backup and device transfer`() {
        assertEquals(1, countAttemptExclusions("backup_rules.xml"))
        assertEquals(2, countAttemptExclusions("data_extraction_rules.xml"))
    }

    private fun frozenReview(recipientAddress: String = recipient): DogecoinTrustedPersonalNodeAttemptReviewRecord {
        val fixture = signedFixture(recipientAddress)
        return DogecoinTrustedPersonalNodeAttemptReviewRecord.fromFrozenReview(
            review = canonicalReview(fixture.transaction, proofSnapshot(fixture.prevout)),
            mainnetAcknowledged = true,
            personalNodeOracleAcknowledged = true
        )
    }

    private fun claimedAttempt(): DogecoinTrustedPersonalNodeAttempt {
        val attempt = store.persistBeforeDisclosure(frozenReview())!!
        assertTrue(store.markSubmissionUnknown(attempt.correlationId, attempt.review.localTxid))
        assertTrue(store.markClaimed(attempt.correlationId, attempt.review.localTxid))
        return availableAttempt()
    }

    private fun availableAttempt(): DogecoinTrustedPersonalNodeAttempt =
        (store.load() as DogecoinTrustedPersonalNodeAttemptLoadResult.Available).attempt

    private fun settlementEvidence(
        attempt: DogecoinTrustedPersonalNodeAttempt,
        depth: Int?,
        provenance: DogecoinSpvEvidenceProvenance,
        fullySynced: Boolean = true,
        tipHash: String = "33".repeat(32),
        conflicts: List<DogecoinSpvConflictingSpendEvidence> = emptyList()
    ): DogecoinSpvSettlementEvidence = DogecoinSpvSettlementEvidence(
        network = DogecoinNetwork.MAINNET,
        expectedTxid = attempt.review.localTxid,
        exactTransactionDepth = depth,
        exactTransactionProvenance = provenance,
        fullySynced = fullySynced,
        peerCount = DogecoinSpvService.MIN_PEERS,
        peerFloorMet = true,
        chainHeight = 5_000_000,
        bestPeerHeight = 5_000_001,
        chainTipHash = tipHash,
        conflictingSpends = conflicts
    )

    private fun canonicalReview(
        transaction: DogecoinSignedTransaction,
        snapshot: DogecoinTrustedPersonalNodeProofSnapshot
    ): DogecoinTrustedPersonalNodeFrozenReview =
        DogecoinTrustedPersonalNodeFrozenReview.freeze(
            authorization = DogecoinTrustedPersonalNodeSpendAuthorization(
                nonce = 1L,
                binding = binding,
                proofSnapshot = snapshot
            ),
            signed = transaction,
            selectedProofCandidates = snapshot.proofCandidates
        )

    private fun signedFixture(recipientAddress: String = recipient): SignedFixture {
        val amountKoinu = 1_000_000_000L
        val previousRaw = previousTransactionHex(
            amountKoinu,
            DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
        )
        val txid = DogecoinTransactionBuilder.transactionId(previousRaw)
        val prevout = DogecoinVerifiedPrevout.verify(
            rawPreviousTransactionHex = previousRaw,
            expectedTxid = txid,
            vout = 0,
            expectedP2pkhScript = DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET),
            source = DogecoinTrustedPersonalNodePreviousTransactionSource.WALLET_GETTRANSACTION
        )
        val transaction = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(
                DogecoinUtxo(
                    txid = prevout.txid,
                    vout = prevout.vout,
                    amountKoinu = prevout.amountKoinu,
                    scriptPubKeyHex = prevout.scriptPubKeyHex,
                    confirmations = DOGECOIN_TPN_MIN_INPUT_CONFIRMATIONS
                )
            ),
            recipientAddress = recipientAddress,
            amount = "2.0",
            network = DogecoinNetwork.MAINNET
        ).copy(createdAtMillis = 1_000_000L)
        return SignedFixture(prevout, transaction)
    }

    private fun proofSnapshot(
        prevout: DogecoinVerifiedPrevout,
        height: Int = 5_000_000,
        hash: String = "11".repeat(32)
    ): DogecoinTrustedPersonalNodeProofSnapshot {
        val tip = DogecoinTrustedPersonalNodeBlockTip(height, hash)
        return DogecoinTrustedPersonalNodeProofSnapshot.complete(
            binding = binding,
            capturedAtMonotonicMillis = 5_000L,
            startTip = tip,
            endTip = tip,
            proofCandidates = listOf(
                DogecoinTrustedPersonalNodeProofCandidate.verifiedAtTip(
                    verifiedPrevout = prevout,
                    finalConfirmations = DOGECOIN_TPN_MIN_INPUT_CONFIRMATIONS,
                    finalBestBlockHash = tip.hash
                )
            ),
            totalProofBytes = prevout.previousTransactionByteCount
        )
    }

    private fun emptyProofSnapshot(): DogecoinTrustedPersonalNodeProofSnapshot {
        val tip = DogecoinTrustedPersonalNodeBlockTip(5_000_000, "11".repeat(32))
        return DogecoinTrustedPersonalNodeProofSnapshot.complete(
            binding = binding,
            capturedAtMonotonicMillis = 5_000L,
            startTip = tip,
            endTip = tip,
            proofCandidates = emptyList(),
            totalProofBytes = 0
        )
    }

    private fun previousTransactionHex(amountKoinu: Long, outputScript: ByteArray): String {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(1, 0, 0, 0))
        output.write(1)
        output.write(ByteArray(32))
        output.write(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
        output.write(1)
        output.write(0)
        output.write(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
        output.write(1)
        writeLittleEndian(output, amountKoinu, 8)
        output.write(outputScript.size)
        output.write(outputScript)
        output.write(byteArrayOf(0, 0, 0, 0))
        return DogecoinHex.encode(output.toByteArray())
    }

    private fun writeLittleEndian(output: ByteArrayOutputStream, value: Long, byteCount: Int) {
        repeat(byteCount) { index -> output.write(((value ushr (index * 8)) and 0xffL).toInt()) }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun countAttemptExclusions(fileName: String): Int {
        val source = sequenceOf(
            File("app/src/main/res/xml/$fileName"),
            File("src/main/res/xml/$fileName"),
            File("../app/src/main/res/xml/$fileName")
        ).firstOrNull(File::isFile) ?: throw AssertionError("Could not locate $fileName")
        return Regex(
            """<exclude\s+domain="sharedpref"\s+path="dogecoin_tpn_attempts\.xml"\s*/>"""
        ).findAll(source.readText()).count()
    }

    private data class SignedFixture(
        val prevout: DogecoinVerifiedPrevout,
        val transaction: DogecoinSignedTransaction
    )
}
