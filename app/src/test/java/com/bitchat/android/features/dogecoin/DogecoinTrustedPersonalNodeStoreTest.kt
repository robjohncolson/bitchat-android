package com.bitchat.android.features.dogecoin

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DogecoinTrustedPersonalNodeStoreTest {
    private lateinit var context: Context
    private lateinit var trustPrefs: SharedPreferences
    private lateinit var credentialPrefs: SharedPreferences
    private lateinit var store: DogecoinTrustedPersonalNodeStore

    private val mainnetAddress = DogecoinKeyGenerator.fromPrivateKeyHex(
        "0000000000000000000000000000000000000000000000000000000000000001",
        DogecoinNetwork.MAINNET
    ).address

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        trustPrefs = context.getSharedPreferences("dogecoin_tpn_trust_test", Context.MODE_PRIVATE)
        credentialPrefs = context.getSharedPreferences("dogecoin_tpn_credentials_test", Context.MODE_PRIVATE)
        clearPrefs()
        store = DogecoinTrustedPersonalNodeStore(trustPrefs, credentialPrefs)
    }

    @After
    fun tearDown() {
        clearPrefs()
    }

    @Test
    fun `authorization persists profile and credentials in separate revision-bound stores`() {
        assertEquals(DogecoinTrustedPersonalNodeState.UNAUTHORIZED, store.loadState())
        assertNull(store.loadProfile())

        val credentials = DogecoinTrustedPersonalNodeCredentials("phone-user", "throwaway-secret")
        val profile = store.authorize(candidate(), credentials, authorizedAtMillis = 3_000L)
        assertEquals(1L, profile?.revision)
        assertEquals(DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE, store.loadState())
        assertEquals(profile, store.loadProfile())
        assertEquals(credentials, profile?.let(store::loadCredentials))

        val trustDump = trustPrefs.all.toString()
        assertFalse(trustDump.contains(credentials.username))
        assertFalse(trustDump.contains(credentials.password))
        val credentialDump = credentialPrefs.all.toString()
        assertFalse(credentialDump.contains(profile!!.origin))
        assertFalse(credentialDump.contains(profile.androidAddress))
        assertFalse(credentialDump.contains(profile.coreWalletId))

        // A fresh repository instance models process death: authorization survives, but no session is stored.
        val reloaded = DogecoinTrustedPersonalNodeStore(trustPrefs, credentialPrefs)
        assertEquals(DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE, reloaded.loadState())
        assertEquals(profile, reloaded.loadProfile())
    }

    @Test
    fun `missing or mismatched credentials never authorize a trust record`() {
        val profile = store.authorize(
            candidate(),
            DogecoinTrustedPersonalNodeCredentials("phone-user", "secret"),
            3_000L
        )!!

        credentialPrefs.edit().putLong("credential_revision", profile.revision + 1L).commit()
        assertEquals(DogecoinTrustedPersonalNodeState.UNAUTHORIZED, store.loadState())
        assertNull(store.loadProfile())
        assertNull(store.loadCredentials(profile))

        credentialPrefs.edit().clear().commit()
        assertEquals(DogecoinTrustedPersonalNodeState.UNAUTHORIZED, store.loadState())
        assertNull(store.loadProfile())
    }

    @Test
    fun `corrupt wrong-network or unattested profile fails closed`() {
        store.authorize(
            candidate(),
            DogecoinTrustedPersonalNodeCredentials("phone-user", "secret"),
            3_000L
        )!!

        trustPrefs.edit().putString("profile_network", DogecoinNetwork.TESTNET.id).commit()
        assertEquals(DogecoinTrustedPersonalNodeState.UNAUTHORIZED, store.loadState())
        assertNull(store.loadProfile())

        trustPrefs.edit()
            .putString("profile_network", DogecoinNetwork.MAINNET.id)
            .putBoolean("profile_rescan_attested", false)
            .commit()
        assertEquals(DogecoinTrustedPersonalNodeState.UNAUTHORIZED, store.loadState())

        trustPrefs.edit()
            .putBoolean("profile_rescan_attested", true)
            .putLong("profile_rescan_attested_at", 0L)
            .commit()
        assertEquals(DogecoinTrustedPersonalNodeState.UNAUTHORIZED, store.loadState())
    }

    @Test
    fun `revoke tombstone survives restart clears credentials and preserves monotonic revision`() {
        val credentials = DogecoinTrustedPersonalNodeCredentials("phone-user", "secret")
        val first = store.authorize(candidate(), credentials, 3_000L)!!
        assertTrue(store.revoke())
        assertEquals(DogecoinTrustedPersonalNodeState.REVOKED, store.loadState())
        assertNull(store.loadProfile())
        assertNull(store.loadCredentials(first))
        assertTrue(credentialPrefs.all.isEmpty())

        val restarted = DogecoinTrustedPersonalNodeStore(trustPrefs, credentialPrefs)
        assertEquals(DogecoinTrustedPersonalNodeState.REVOKED, restarted.loadState())
        val replacement = restarted.authorize(candidate(), credentials, 4_000L)
        assertEquals(first.revision + 1L, replacement?.revision)
        assertEquals(DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE, restarted.loadState())
    }

    @Test
    fun `invalid profile or credentials do not write either store`() {
        val invalidCandidate = candidate().copy(
            network = DogecoinNetwork.TESTNET,
            rescanAttested = false
        )
        assertNull(
            store.authorize(
                invalidCandidate,
                DogecoinTrustedPersonalNodeCredentials("phone-user", "secret"),
                3_000L
            )
        )
        assertTrue(trustPrefs.all.isEmpty())
        assertTrue(credentialPrefs.all.isEmpty())

        assertNull(
            store.authorize(
                candidate(),
                DogecoinTrustedPersonalNodeCredentials("phone:user", "secret"),
                3_000L
            )
        )
        assertTrue(trustPrefs.all.isEmpty())
        assertTrue(credentialPrefs.all.isEmpty())
    }

    @Test
    fun `stable conflict disputes and only two fresh agreements arm explicit recovery`() {
        val credentials = DogecoinTrustedPersonalNodeCredentials("phone-user", "secret")
        val profile = store.authorize(candidate(), credentials, 3_000L)!!

        val conflictOne = crossCheck(
            id = "${"11".repeat(32)}:${"22".repeat(32)}",
            result = DogecoinTrustedPersonalNodeCrossCheckResult.CONFLICT,
            at = 4_000L,
            hasConflictingSpend = true
        )
        val first = store.recordCrossCheck(profile, conflictOne)!!
        assertEquals(DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE, first.state)
        assertEquals(1, first.stableConflictStreak)
        // Replaying the same snapshot cannot manufacture stability.
        assertEquals(1, store.recordCrossCheck(profile, conflictOne)!!.stableConflictStreak)
        assertNull(
            store.recordCrossCheck(
                profile,
                conflictOne.copy(
                    comparisonId = "${"aa".repeat(32)}:${"bb".repeat(32)}",
                    capturedAtMillis = 3_999L
                )
            )
        )
        assertEquals(1, store.loadDisputeStatus(profile)!!.stableConflictStreak)

        val disputed = store.recordCrossCheck(
            profile,
            conflictOne.copy(
                comparisonId = "${"33".repeat(32)}:${"44".repeat(32)}",
                capturedAtMillis = 5_000L
            )
        )!!
        assertEquals(DogecoinTrustedPersonalNodeState.DISPUTED, disputed.state)
        assertEquals(DogecoinTrustedPersonalNodeState.DISPUTED, store.loadState())
        assertEquals(profile, store.loadProfile())
        assertEquals(credentials, store.loadCredentials(profile))
        assertFalse(store.clearDisputeAfterOperatorConfirmation(profile))

        val agreementOne = crossCheck(
            id = "${"55".repeat(32)}:${"66".repeat(32)}",
            result = DogecoinTrustedPersonalNodeCrossCheckResult.AGREEMENT,
            at = 6_000L
        )
        assertEquals(1, store.recordCrossCheck(profile, agreementOne)!!.recoveryAgreementStreak)
        // Duplicate evidence cannot advance the recovery gate.
        assertEquals(1, store.recordCrossCheck(profile, agreementOne)!!.recoveryAgreementStreak)
        val recoveryReady = store.recordCrossCheck(
            profile,
            agreementOne.copy(
                comparisonId = "${"77".repeat(32)}:${"88".repeat(32)}",
                capturedAtMillis = 7_000L
            )
        )!!
        assertTrue(recoveryReady.recoveryReadyForOperator)
        assertEquals(DogecoinTrustedPersonalNodeState.DISPUTED, store.loadState())
        assertTrue(store.clearDisputeAfterOperatorConfirmation(profile))
        assertEquals(DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE, store.loadState())
    }

    @Test
    fun `inconclusive or under-depth comparison cannot dispute or clear dispute`() {
        val profile = store.authorize(
            candidate(),
            DogecoinTrustedPersonalNodeCredentials("phone-user", "secret"),
            3_000L
        )!!
        assertNull(
            store.recordCrossCheck(
                profile,
                crossCheck(
                    id = "${"11".repeat(32)}:${"22".repeat(32)}",
                    result = DogecoinTrustedPersonalNodeCrossCheckResult.CONFLICT,
                    at = 4_000L
                ).copy(fullySyncedMainnet = false)
            )
        )
        assertNull(
            store.recordCrossCheck(
                profile,
                crossCheck(
                    id = "${"33".repeat(32)}:${"44".repeat(32)}",
                    result = DogecoinTrustedPersonalNodeCrossCheckResult.AGREEMENT,
                    at = 5_000L
                ).copy(confirmationContextDepth = 5)
            )
        )
        assertEquals(DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE, store.loadState())
    }

    private fun crossCheck(
        id: String,
        result: DogecoinTrustedPersonalNodeCrossCheckResult,
        at: Long,
        hasConflictingSpend: Boolean = false
    ) = DogecoinTrustedPersonalNodeCrossCheckEvidence(
        comparisonId = id,
        result = result,
        fullySyncedMainnet = true,
        confirmationContextDepth = 6,
        hasConflictingSpend = hasConflictingSpend,
        capturedAtMillis = at
    )

    private fun candidate(): DogecoinTrustedPersonalNodeProfileCandidate =
        DogecoinTrustedPersonalNodeProfileCandidate(
            origin = "https://dogebox.tail1234.ts.net",
            network = DogecoinNetwork.MAINNET,
            androidAddress = mainnetAddress,
            coreWalletId = "bitchat-watch.dat",
            rescanAttested = true,
            rescanAttestedAtMillis = 2_000L
        )

    private fun clearPrefs() {
        if (::trustPrefs.isInitialized) trustPrefs.edit().clear().commit()
        if (::credentialPrefs.isInitialized) credentialPrefs.edit().clear().commit()
    }
}
