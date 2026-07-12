package com.bitchat.android.features.dogecoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DogecoinTrustedPersonalNodeReadSessionTest {
    private val mainnetAddress = DogecoinKeyGenerator.fromPrivateKeyHex(
        "0000000000000000000000000000000000000000000000000000000000000001",
        DogecoinNetwork.MAINNET
    ).address

    @Test
    fun `explicit activation accepts exact fresh readiness and display through ttl boundary`() {
        val profile = profile(revision = 7L)
        val holder = holder(profile)
        val token = assertNotNullValue(holder.beginActivation(nowMonotonicMillis = 1_000L))

        assertEquals(DogecoinTrustedPersonalNodeState.CHECKING, holder.state)
        assertTrue(holder.recordSuccessfulReadSnapshot(token, rawSnapshot(profile), 1_010L))
        assertEquals(DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED, holder.state)

        val snapshot = assertNotNullValue(holder.displaySnapshot)
        assertEquals(1_000L, snapshot.capturedAtMonotonicMillis)
        assertEquals(snapshot, holder.freshDisplaySnapshot(121_000L))
        assertNull(holder.freshDisplaySnapshot(121_001L))
        assertEquals(DogecoinTrustedPersonalNodeState.DEGRADED, holder.state)
        // DEGRADED may render this only with explicit stale provenance; it is never returned as fresh.
        assertEquals(snapshot, holder.displaySnapshot)
    }

    @Test
    fun `readiness requires every operational gate and exact full profile binding`() {
        val profile = profile(revision = 4L)
        val valid = rawSnapshot(profile)
        assertTrue(isDogecoinTrustedPersonalNodeReadSnapshotReady(profile, valid))

        listOf(
            valid.copy(origin = "https://other.tail1234.ts.net"),
            valid.copy(profileRevision = 5L),
            valid.copy(androidAddress = profile.androidAddress.reversed()),
            valid.copy(coreWalletId = "other-wallet.dat"),
            valid.copy(blocks = 100, headers = 99),
            valid.copy(blocks = 100, headers = 103),
            valid.copy(verificationProgress = Double.NaN),
            valid.copy(verificationProgress = Double.POSITIVE_INFINITY),
            valid.copy(verificationProgress = -0.1),
            valid.copy(
                verificationProgress = DogecoinRpcClient.DOGECOIN_TPN_MIN_VERIFICATION_PROGRESS - 0.000001
            ),
            valid.copy(peerCount = DogecoinRpcClient.DOGECOIN_TPN_MIN_MAINNET_PEERS - 1)
        ).forEach { rejected ->
            assertFalse(rejected.toString(), isDogecoinTrustedPersonalNodeReadSnapshotReady(profile, rejected))
        }
    }

    @Test
    fun `degraded recovery is explicit while auth required cannot retry old credential`() {
        val profile = profile(revision = 2L)
        val holder = holder(profile)
        val first = assertNotNullValue(holder.beginActivation(100L))
        assertTrue(holder.recordTransientFailure(first))
        assertEquals(DogecoinTrustedPersonalNodeState.DEGRADED, holder.state)
        assertNull(holder.beginActivation(101L))

        val retry = assertNotNullValue(holder.retryDegradedActivation(102L))
        assertEquals(DogecoinTrustedPersonalNodeState.CHECKING, holder.state)
        assertTrue(holder.recordAuthenticationRequired(retry))
        assertEquals(DogecoinTrustedPersonalNodeState.AUTH_REQUIRED, holder.state)
        assertNull(holder.retryDegradedActivation(103L))
        assertNull(holder.beginActivation(103L))
        holder.deactivate()
        assertEquals(DogecoinTrustedPersonalNodeState.AUTH_REQUIRED, holder.state)
        assertFalse(dogecoinTrustedPersonalNodeSessionUsesNode(holder.state))
        assertTrue(
            dogecoinTrustedPersonalNodeSessionUsesNode(
                DogecoinTrustedPersonalNodeState.DEGRADED
            )
        )
    }

    @Test
    fun `active refresh is explicit and cancellation cannot leave checking stuck`() {
        val profile = profile(revision = 12L)
        val holder = holder(profile)
        val first = assertNotNullValue(holder.beginActivation(100L))
        assertTrue(holder.recordSuccessfulReadSnapshot(first, rawSnapshot(profile), 101L))
        val firstSnapshot = holder.displaySnapshot

        val refresh = assertNotNullValue(holder.refreshActiveReadSnapshot(200L))
        assertEquals(DogecoinTrustedPersonalNodeState.CHECKING, holder.state)
        assertTrue(holder.isActivationCurrent(refresh))
        assertEquals(firstSnapshot, holder.displaySnapshot)
        assertTrue(holder.cancelActivation(refresh))
        assertFalse(holder.isActivationCurrent(refresh))
        assertEquals(DogecoinTrustedPersonalNodeState.DEGRADED, holder.state)
        assertEquals(firstSnapshot, holder.displaySnapshot)

        holder.deactivate()
        val newAttempt = assertNotNullValue(holder.beginActivation(300L))
        assertTrue(holder.cancelActivation(newAttempt))
        assertEquals(DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE, holder.state)
        assertNull(holder.displaySnapshot)
    }

    @Test
    fun `changed durable tuple invalidates in-memory readiness and old token`() {
        val profile = profile(revision = 9L)
        val holder = holder(profile)
        val token = assertNotNullValue(holder.beginActivation(10L))
        assertTrue(holder.recordSuccessfulReadSnapshot(token, rawSnapshot(profile), 11L))

        val replacement = profile.copy(revision = 10L)
        holder.synchronizePersistedAuthorization(
            DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE,
            replacement
        )

        assertEquals(DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE, holder.state)
        assertEquals(replacement, holder.profile)
        assertNull(holder.displaySnapshot)
        assertFalse(holder.recordSuccessfulReadSnapshot(token, rawSnapshot(profile), 12L))
    }

    @Test
    fun `process reconstruction never restores active session or node snapshot`() {
        val profile = profile(revision = 3L)
        val active = holder(profile)
        val token = assertNotNullValue(active.beginActivation(5L))
        assertTrue(active.recordSuccessfulReadSnapshot(token, rawSnapshot(profile), 6L))

        val afterDeath = holder(profile)
        assertEquals(DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE, afterDeath.state)
        assertNull(afterDeath.displaySnapshot)
    }

    @Test
    fun `active read session does not unlock mainnet rpc spending`() {
        val profile = profile(revision = 13L)
        val holder = holder(profile)
        val token = assertNotNullValue(holder.beginActivation(1_000L))
        assertTrue(holder.recordSuccessfulReadSnapshot(token, rawSnapshot(profile), 1_001L))
        assertEquals(DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED, holder.state)

        val proofToken = assertNotNullValue(holder.beginProofSnapshot(1_002L))
        assertTrue(
            holder.recordSuccessfulProofSnapshot(
                proofToken,
                proofSnapshot(profile, proofToken.startedAtMonotonicMillis),
                1_003L
            )
        )
        assertNotNull(holder.freshProofSnapshot(1_004L))

        assertFalse(dogecoinSpendRouteAllowed(DogecoinNetwork.MAINNET, DogecoinBackend.RPC))
        assertFalse(dogecoinGenericRpcSpendAllowed(DogecoinNetwork.MAINNET))
        assertTrue(dogecoinSpendRouteAllowed(DogecoinNetwork.MAINNET, DogecoinBackend.SPV))
    }

    @Test
    fun `proof token requires active fresh display and exact binding start time`() {
        val profile = profile(revision = 14L)
        val inactive = holder(profile)
        assertNull(inactive.beginProofSnapshot(100L))

        val holder = holder(profile)
        val activation = assertNotNullValue(holder.beginActivation(1_000L))
        assertTrue(holder.recordSuccessfulReadSnapshot(activation, rawSnapshot(profile), 1_001L))

        val first = assertNotNullValue(holder.beginProofSnapshot(1_002L))
        assertTrue(holder.isProofSnapshotCurrent(first, 1_003L))
        val replacement = assertNotNullValue(holder.beginProofSnapshot(1_004L))
        assertFalse(holder.isProofSnapshotCurrent(first, 1_005L))
        assertTrue(holder.isProofSnapshotCurrent(replacement, 1_005L))

        val wrongBinding = profile.copy(revision = profile.revision + 1L).toSessionBinding()
        assertFalse(
            holder.recordSuccessfulProofSnapshot(
                replacement,
                proofSnapshot(profile, replacement.startedAtMonotonicMillis, wrongBinding),
                1_006L
            )
        )
        assertNull(holder.freshProofSnapshot(1_006L))
        assertEquals(DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED, holder.state)

        val wrongStart = assertNotNullValue(holder.beginProofSnapshot(1_007L))
        assertFalse(
            holder.recordSuccessfulProofSnapshot(
                wrongStart,
                proofSnapshot(profile, wrongStart.startedAtMonotonicMillis + 1L),
                1_009L
            )
        )
        assertNull(holder.freshProofSnapshot(1_009L))
    }

    @Test
    fun `incomplete proof collection stays display only and cannot publish a prefix`() {
        val profile = profile(revision = 15L)
        val holder = activeHolder(profile, displayStartedAt = 500L)
        val display = assertNotNullValue(holder.displaySnapshot)
        val token = assertNotNullValue(holder.beginProofSnapshot(501L))

        assertTrue(holder.recordProofSnapshotFailure(token))
        assertEquals(DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED, holder.state)
        assertEquals(display, holder.displaySnapshot)
        assertNull(holder.freshProofSnapshot(501L))
        assertFalse(holder.recordProofSnapshotFailure(token))
        assertFalse(dogecoinSpendRouteAllowed(DogecoinNetwork.MAINNET, DogecoinBackend.RPC))
    }

    @Test
    fun `proof is process only and expires with the bound display ttl`() {
        val profile = profile(revision = 16L)
        val holder = activeHolder(profile, displayStartedAt = 1_000L)
        val token = assertNotNullValue(holder.beginProofSnapshot(1_001L))
        assertTrue(
            holder.recordSuccessfulProofSnapshot(
                token,
                proofSnapshot(profile, token.startedAtMonotonicMillis),
                1_002L
            )
        )
        assertNotNull(holder.freshProofSnapshot(121_000L))
        assertNull(holder.freshProofSnapshot(121_001L))
        assertEquals(DogecoinTrustedPersonalNodeState.DEGRADED, holder.state)

        val afterProcessDeath = holder(profile)
        assertEquals(DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE, afterProcessDeath.state)
        assertNull(afterProcessDeath.displaySnapshot)
        assertNull(afterProcessDeath.freshProofSnapshot(1_002L))
    }

    @Test
    fun `every read session lifecycle boundary invalidates proof`() {
        val profile = profile(revision = 17L)

        activeHolderWithProof(profile, 100L).let { holder ->
            assertNotNull(holder.refreshActiveReadSnapshot(200L))
            assertNull(holder.freshProofSnapshot(200L))
        }
        activeHolderWithProof(profile, 300L).let { holder ->
            holder.deactivate()
            assertNull(holder.freshProofSnapshot(304L))
        }
        activeHolderWithProof(profile, 500L).let { holder ->
            holder.beginProvisioning(1L)
            assertNull(holder.freshProofSnapshot(504L))
        }
        activeFixtureWithProof(profile, 700L).let { fixture ->
            assertTrue(fixture.holder.recordTransientFailure(fixture.activationToken))
            assertNull(fixture.holder.freshProofSnapshot(704L))
            assertNotNull(fixture.holder.retryDegradedActivation(704L))
            assertNull(fixture.holder.freshProofSnapshot(704L))
        }
        activeFixtureWithProof(profile, 900L).let { fixture ->
            assertTrue(fixture.holder.recordAuthenticationRequired(fixture.activationToken))
            assertNull(fixture.holder.freshProofSnapshot(904L))
        }
        activeHolderWithProof(profile, 1_100L).let { holder ->
            holder.synchronizePersistedAuthorization(
                DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE,
                profile.copy(revision = profile.revision + 1L)
            )
            assertNull(holder.freshProofSnapshot(1_104L))
        }
        activeHolderWithProof(profile, 1_300L).let { holder ->
            holder.revoke()
            assertNull(holder.freshProofSnapshot(1_304L))
        }
    }

    @Test
    fun `process registry keeps same live session but network and revision rebind invalidate it`() {
        DogecoinTrustedPersonalNodeProcessSessionRegistry.resetForTests()
        val profile = profile(revision = 6L)
        val first = DogecoinTrustedPersonalNodeProcessSessionRegistry.bindPersistedAuthorization(
            DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE,
            profile,
            DogecoinNetwork.MAINNET,
            profile.androidAddress
        )
        val token = assertNotNullValue(first.beginActivation(100L))
        assertTrue(first.recordSuccessfulReadSnapshot(token, rawSnapshot(profile), 101L))

        val reopened = DogecoinTrustedPersonalNodeProcessSessionRegistry.bindPersistedAuthorization(
            DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE,
            profile,
            DogecoinNetwork.MAINNET,
            profile.androidAddress
        )
        assertTrue(first === reopened)
        assertEquals(DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED, reopened.state)

        DogecoinTrustedPersonalNodeProcessSessionRegistry.bindPersistedAuthorization(
            DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE,
            profile,
            DogecoinNetwork.TESTNET,
            profile.androidAddress
        )
        assertEquals(DogecoinTrustedPersonalNodeState.UNAUTHORIZED, reopened.state)
        assertNull(reopened.displaySnapshot)

        val rebound = DogecoinTrustedPersonalNodeProcessSessionRegistry.bindPersistedAuthorization(
            DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE,
            profile.copy(revision = 7L),
            DogecoinNetwork.MAINNET,
            profile.androidAddress
        )
        assertEquals(DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE, rebound.state)
        assertEquals(7L, rebound.profile?.revision)
        DogecoinTrustedPersonalNodeProcessSessionRegistry.resetForTests()
    }

    @Test
    fun `trusted read session keeps mainnet spv running without changing backend`() {
        assertEquals(
            DogecoinNetwork.MAINNET,
            dogecoinSpvTargetNetwork(
                persistedBackend = DogecoinBackend.RPC,
                selectedNetwork = DogecoinNetwork.MAINNET,
                supported = true,
                trustedPersonalNodeReadSession = true
            )
        )
        assertNull(
            dogecoinSpvTargetNetwork(
                persistedBackend = DogecoinBackend.RPC,
                selectedNetwork = DogecoinNetwork.TESTNET,
                supported = true,
                trustedPersonalNodeReadSession = true
            )
        )
        assertNull(
            dogecoinSpvTargetNetwork(
                persistedBackend = DogecoinBackend.RPC,
                selectedNetwork = DogecoinNetwork.MAINNET,
                supported = false,
                trustedPersonalNodeReadSession = true
            )
        )
    }

    @Test
    fun `freshness uses monotonic inclusive ttl and rejects future values`() {
        assertTrue(isDogecoinTrustedPersonalNodeFresh(50L, 50L))
        assertTrue(isDogecoinTrustedPersonalNodeFresh(50L, 50L + DOGECOIN_TPN_SNAPSHOT_TTL_MILLIS))
        assertFalse(isDogecoinTrustedPersonalNodeFresh(50L, 51L + DOGECOIN_TPN_SNAPSHOT_TTL_MILLIS))
        assertFalse(isDogecoinTrustedPersonalNodeFresh(51L, 50L))
        assertFalse(isDogecoinTrustedPersonalNodeFresh(-1L, 50L))
    }

    @Test
    fun `slow readiness workflow cannot extend freshness from completion time`() {
        val profile = profile(revision = 8L)
        val holder = holder(profile)
        val token = assertNotNullValue(holder.beginActivation(1_000L))

        assertFalse(
            holder.recordSuccessfulReadSnapshot(
                token,
                rawSnapshot(profile),
                1_000L + DOGECOIN_TPN_SNAPSHOT_TTL_MILLIS + 1L
            )
        )
        assertEquals(DogecoinTrustedPersonalNodeState.DEGRADED, holder.state)
        assertNull(holder.displaySnapshot)
    }

    private fun holder(profile: DogecoinTrustedPersonalNodeProfile) =
        DogecoinTrustedPersonalNodeSessionHolder(
            savedState = DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE,
            savedProfile = profile
        )

    private fun activeHolder(
        profile: DogecoinTrustedPersonalNodeProfile,
        displayStartedAt: Long
    ): DogecoinTrustedPersonalNodeSessionHolder =
        activeFixture(profile, displayStartedAt).holder

    private fun activeFixture(
        profile: DogecoinTrustedPersonalNodeProfile,
        displayStartedAt: Long
    ): ActiveFixture {
        val holder = holder(profile)
        val activation = assertNotNullValue(holder.beginActivation(displayStartedAt))
        assertTrue(
            holder.recordSuccessfulReadSnapshot(
                activation,
                rawSnapshot(profile),
                displayStartedAt + 1L
            )
        )
        return ActiveFixture(holder, activation)
    }

    private fun activeHolderWithProof(
        profile: DogecoinTrustedPersonalNodeProfile,
        displayStartedAt: Long
    ): DogecoinTrustedPersonalNodeSessionHolder =
        activeFixtureWithProof(profile, displayStartedAt).holder

    private fun activeFixtureWithProof(
        profile: DogecoinTrustedPersonalNodeProfile,
        displayStartedAt: Long
    ): ActiveFixture = activeFixture(profile, displayStartedAt).also { fixture ->
        val token = assertNotNullValue(fixture.holder.beginProofSnapshot(displayStartedAt + 2L))
        assertTrue(
            fixture.holder.recordSuccessfulProofSnapshot(
                token,
                proofSnapshot(profile, token.startedAtMonotonicMillis),
                displayStartedAt + 3L
            )
        )
    }

    private fun profile(revision: Long): DogecoinTrustedPersonalNodeProfile =
        DogecoinTrustedPersonalNodeProfile(
            origin = "https://dogebox.tail1234.ts.net",
            network = DogecoinNetwork.MAINNET,
            androidAddress = mainnetAddress,
            coreWalletId = "bitchat-watch.dat",
            policyVersion = DOGECOIN_TRUSTED_PERSONAL_NODE_POLICY_VERSION,
            revision = revision,
            authorizedAtMillis = 3_000L,
            rescanAttested = true,
            rescanAttestedAtMillis = 2_000L
        )

    private fun rawSnapshot(
        profile: DogecoinTrustedPersonalNodeProfile
    ): DogecoinTrustedPersonalNodeDisplaySnapshot = DogecoinTrustedPersonalNodeDisplaySnapshot(
            profileRevision = profile.revision,
            origin = profile.origin,
            androidAddress = profile.androidAddress,
            coreWalletId = profile.coreWalletId,
            blocks = 100,
            headers = 100,
            verificationProgress = 1.0,
            peerCount = DogecoinRpcClient.DOGECOIN_TPN_MIN_MAINNET_PEERS,
            balance = DogecoinTrustedPersonalNodeDisplayBalance(
                confirmedKoinu = 123_000_000L,
                unconfirmedKoinu = 0L,
                utxoCount = 1
            ),
            activity = emptyList()
        )

    private fun proofSnapshot(
        profile: DogecoinTrustedPersonalNodeProfile,
        capturedAtMonotonicMillis: Long,
        binding: DogecoinTrustedPersonalNodeSessionBinding = profile.toSessionBinding()
    ): DogecoinTrustedPersonalNodeProofSnapshot =
        DogecoinTrustedPersonalNodeProofSnapshot.complete(
            binding = binding,
            capturedAtMonotonicMillis = capturedAtMonotonicMillis,
            startTip = DogecoinTrustedPersonalNodeBlockTip(100, "11".repeat(32)),
            endTip = DogecoinTrustedPersonalNodeBlockTip(100, "11".repeat(32)),
            proofCandidates = emptyList(),
            totalProofBytes = 0
        )

    private fun <T : Any> assertNotNullValue(value: T?): T {
        assertNotNull(value)
        return requireNotNull(value)
    }

    private data class ActiveFixture(
        val holder: DogecoinTrustedPersonalNodeSessionHolder,
        val activationToken: DogecoinTrustedPersonalNodeActivationToken
    )
}
