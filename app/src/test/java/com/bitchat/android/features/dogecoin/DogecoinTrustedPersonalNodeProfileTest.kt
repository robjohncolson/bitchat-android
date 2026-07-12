package com.bitchat.android.features.dogecoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DogecoinTrustedPersonalNodeProfileTest {
    private val mainnetAddress = DogecoinKeyGenerator.fromPrivateKeyHex(
        "0000000000000000000000000000000000000000000000000000000000000001",
        DogecoinNetwork.MAINNET
    ).address

    @Test
    fun `exact origin accepts only canonical tailscale origin`() {
        val exact = "https://dogebox.tail1234.ts.net"
        assertEquals(exact, exactDogecoinTrustedPersonalNodeOriginOrNull(exact))

        listOf(
            " https://dogebox.tail1234.ts.net",
            "https://dogebox.tail1234.ts.net ",
            "HTTPS://DOGEBOX.TAIL1234.TS.NET",
            "https://dogebox.tail1234.ts.net/",
            "https://dogebox.tail1234.ts.net:443",
            "https://user:pw@dogebox.tail1234.ts.net",
            "https://@dogebox.tail1234.ts.net",
            "https://dogebox.tail1234.ts.net/wallet/name",
            "https://dogebox.tail1234.ts.net?x=1",
            "https://dogebox.tail1234.ts.net#fragment",
            "https://dogebox.tail1234.ts.net.",
            "https://sub.dogebox.tail1234.ts.net",
            "https://dogebox.tail1234.ts.net.evil.example",
            "https://xn--doge-9za.tail1234.ts.net",
            "https://dogebox.xn--tail-9za.ts.net",
            "https://dogeböx.tail1234.ts.net",
            "https://100.64.0.1",
            "http://dogebox.tail1234.ts.net",
            "https://rpc.example.com"
        ).forEach { candidate ->
            assertNull(candidate, exactDogecoinTrustedPersonalNodeOriginOrNull(candidate))
        }
    }

    @Test
    fun `valid sibling is not equivalent to bound origin`() {
        val bound = "https://first.tail1234.ts.net"
        val sibling = "https://second.tail1234.ts.net"
        assertTrue(dogecoinTrustedPersonalNodeOriginMatches(bound, bound))
        assertFalse(dogecoinTrustedPersonalNodeOriginMatches(bound, sibling))
        assertFalse(dogecoinTrustedPersonalNodeOriginMatches(bound, "$bound/"))
    }

    @Test
    fun `core wallet id must be exact bounded and one segment`() {
        assertEquals("bitchat-watch.dat", canonicalDogecoinTrustedPersonalNodeWalletIdOrNull("bitchat-watch.dat"))
        assertEquals("wallet with spaces", canonicalDogecoinTrustedPersonalNodeWalletIdOrNull("wallet with spaces"))
        listOf("", ".", "..", " wallet", "wallet ", "a/b", "a\\b", "a\u0000b", "a\nb", "x".repeat(129)).forEach {
            assertNull(it, canonicalDogecoinTrustedPersonalNodeWalletIdOrNull(it))
        }
    }

    @Test
    fun `credentials validate exactly and never print secrets`() {
        val valid = DogecoinTrustedPersonalNodeCredentials("phone-user", "secret:with-spaces allowed")
        assertTrue(valid.isValid())
        assertFalse(valid.toString().contains("phone-user"))
        assertFalse(valid.toString().contains("secret"))

        listOf(
            DogecoinTrustedPersonalNodeCredentials("", "secret"),
            DogecoinTrustedPersonalNodeCredentials(" user", "secret"),
            DogecoinTrustedPersonalNodeCredentials("user:name", "secret"),
            DogecoinTrustedPersonalNodeCredentials("user\n", "secret"),
            DogecoinTrustedPersonalNodeCredentials("user", ""),
            DogecoinTrustedPersonalNodeCredentials("user", "   "),
            DogecoinTrustedPersonalNodeCredentials("user", "bad\nsecret"),
            DogecoinTrustedPersonalNodeCredentials("user", "bad\u0000secret")
        ).forEach { assertFalse(it.isValid()) }
    }

    @Test
    fun `profile validation requires exact mainnet p2pkh wallet policy revision and attestation`() {
        val profile = profile(revision = 7L)
        assertTrue(isValidDogecoinTrustedPersonalNodeProfile(profile))
        assertFalse(isValidDogecoinTrustedPersonalNodeProfile(profile.copy(network = DogecoinNetwork.TESTNET)))
        assertFalse(isValidDogecoinTrustedPersonalNodeProfile(profile.copy(androidAddress = " $mainnetAddress")))
        assertFalse(isValidDogecoinTrustedPersonalNodeProfile(profile.copy(coreWalletId = "wallet/name")))
        assertFalse(isValidDogecoinTrustedPersonalNodeProfile(profile.copy(policyVersion = 0)))
        assertFalse(isValidDogecoinTrustedPersonalNodeProfile(profile.copy(revision = 0L)))
        assertFalse(isValidDogecoinTrustedPersonalNodeProfile(profile.copy(rescanAttested = false)))
        assertFalse(isValidDogecoinTrustedPersonalNodeProfile(profile.copy(rescanAttestedAtMillis = 0L)))
        assertFalse(isValidDogecoinTrustedPersonalNodeProfile(profile.copy(rescanAttestedAtMillis = 3_001L)))
    }

    @Test
    fun `provisioning token is one shot and authorization needs successful result plus all confirmations`() {
        val holder = DogecoinTrustedPersonalNodeSessionHolder()
        val token = holder.beginProvisioning(draftRevision = 4L)
        assertEquals(DogecoinTrustedPersonalNodeState.PROVISIONING, holder.state)
        assertTrue(holder.consumeProvisioningProbe(token))
        assertFalse(holder.consumeProvisioningProbe(token))
        assertNull(holder.authorizationCandidate(token, confirmations(oneMissing = false), 2_000L))

        assertTrue(holder.recordSuccessfulProvisioning(token, successfulResult()))
        assertNull(holder.authorizationCandidate(token, confirmations(oneMissing = true), 2_000L))
        val candidate = holder.authorizationCandidate(token, confirmations(oneMissing = false), 2_000L)
        assertEquals(mainnetAddress, candidate?.androidAddress)
        assertTrue(candidate?.rescanAttested == true)
        assertNull(holder.authorizationCandidate(token, confirmations(oneMissing = false), 2_000L))

        val persisted = profile(revision = 1L)
        assertTrue(holder.authorizationPersisted(token, persisted))
        assertEquals(DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE, holder.state)
        assertEquals(persisted, holder.profile)
    }

    @Test
    fun `draft edit and persisted revision change invalidate stale provisioning`() {
        val firstProfile = profile(revision = 1L)
        val holder = DogecoinTrustedPersonalNodeSessionHolder(
            DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE,
            firstProfile
        )
        val editedToken = holder.beginProvisioning(10L)
        assertTrue(holder.consumeProvisioningProbe(editedToken))
        holder.invalidateDraft(11L)
        assertEquals(DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE, holder.state)
        assertFalse(holder.recordSuccessfulProvisioning(editedToken, successfulResult()))

        val revisionToken = holder.beginProvisioning(12L)
        assertTrue(holder.consumeProvisioningProbe(revisionToken))
        assertTrue(holder.recordSuccessfulProvisioning(revisionToken, successfulResult()))
        holder.synchronizePersistedAuthorization(
            DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE,
            firstProfile.copy(revision = 2L)
        )
        assertFalse(holder.recordSuccessfulProvisioning(revisionToken, successfulResult()))
        assertEquals(2L, holder.profile?.revision)

        val sameRevisionDifferentOrigin = holder.beginProvisioning(13L)
        assertTrue(holder.consumeProvisioningProbe(sameRevisionDifferentOrigin))
        assertTrue(holder.recordSuccessfulProvisioning(sameRevisionDifferentOrigin, successfulResult()))
        val replacement = holder.profile!!.copy(origin = "https://other.tail1234.ts.net")
        holder.synchronizePersistedAuthorization(
            DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE,
            replacement
        )
        assertFalse(holder.recordSuccessfulProvisioning(sameRevisionDifferentOrigin, successfulResult()))
        assertEquals(replacement, holder.profile)
    }

    @Test
    fun `new holder after process death keeps profile but always starts inactive`() {
        val saved = profile(revision = 3L)
        val first = DogecoinTrustedPersonalNodeSessionHolder(
            DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE,
            saved
        )
        val token = first.beginProvisioning(1L)
        assertTrue(first.consumeProvisioningProbe(token))

        val afterDeath = DogecoinTrustedPersonalNodeSessionHolder(
            DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE,
            saved
        )
        assertEquals(DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE, afterDeath.state)
        assertEquals(saved, afterDeath.profile)
        assertFalse(afterDeath.consumeProvisioningProbe(token))
    }

    @Test
    fun `revoked holder stays revoked when a replacement ceremony is cancelled`() {
        val holder = DogecoinTrustedPersonalNodeSessionHolder(DogecoinTrustedPersonalNodeState.REVOKED)
        val token = holder.beginProvisioning(1L)
        assertTrue(holder.consumeProvisioningProbe(token))
        holder.cancelProvisioning()
        assertEquals(DogecoinTrustedPersonalNodeState.REVOKED, holder.state)
        assertNull(holder.profile)
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

    private fun successfulResult(): DogecoinTrustedPersonalNodeProvisioningResult =
        DogecoinTrustedPersonalNodeProvisioningResult(
            origin = "https://dogebox.tail1234.ts.net",
            network = DogecoinNetwork.MAINNET,
            androidAddress = mainnetAddress,
            coreWalletId = "bitchat-watch.dat",
            chain = DogecoinNetwork.MAINNET.chainName,
            watchStatus = DogecoinAddressWatchStatus(
                address = mainnetAddress,
                isMine = false,
                isWatchOnly = true
            )
        )

    private fun confirmations(oneMissing: Boolean): DogecoinTrustedPersonalNodeConfirmations =
        DogecoinTrustedPersonalNodeConfirmations(
            controlsLaptop = true,
            loopbackServeNoFunnel = true,
            watchOnlyNoWif = true,
            acceptsNodeOracleRisk = !oneMissing,
            understandsTailscaleIsNotAnonymity = true
        )
}
