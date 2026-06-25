package com.bitchat.android.model

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class IdentityAnnouncementTest {
    private val nickname = "peer"
    private val noiseKey = ByteArray(32) { 0x0B }
    private val signingKey = ByteArray(32) { 0x0A }
    private val testnetAddress = "nZqK9XAAwKZ6hFsPZ2dL5nJ8dSZr6cTzxE"

    @Test
    fun `encode decode round trip preserves dogecoin address tlv`() {
        val announcement = IdentityAnnouncement(
            nickname = nickname,
            noisePublicKey = noiseKey,
            signingPublicKey = signingKey,
            dogecoinAddresses = listOf(DogecoinIdentityAddress("testnet", testnetAddress))
        )

        val decoded = IdentityAnnouncement.decode(announcement.encode()!!)

        assertEquals(announcement, decoded)
        assertEquals(listOf(DogecoinIdentityAddress("testnet", testnetAddress)), decoded?.dogecoinAddresses)
    }

    @Test
    fun `announcement without dogecoin tlv decodes as before`() {
        val announcement = IdentityAnnouncement(
            nickname = nickname,
            noisePublicKey = noiseKey,
            signingPublicKey = signingKey
        )

        val decoded = IdentityAnnouncement.decode(announcement.encode()!!)

        assertNotNull(decoded)
        assertEquals(nickname, decoded?.nickname)
        assertTrue(decoded?.noisePublicKey?.contentEquals(noiseKey) == true)
        assertTrue(decoded?.signingPublicKey?.contentEquals(signingKey) == true)
        assertEquals(emptyList<DogecoinIdentityAddress>(), decoded?.dogecoinAddresses)
    }

    @Test
    fun `announcement with unknown tlv still decodes known fields`() {
        val announcement = IdentityAnnouncement(
            nickname = nickname,
            noisePublicKey = noiseKey,
            signingPublicKey = signingKey,
            dogecoinAddresses = listOf(DogecoinIdentityAddress("testnet", testnetAddress))
        )
        val payloadWithUnknownTlv = announcement.encode()!! + byteArrayOf(0x7F, 0x03, 0x01, 0x02, 0x03)

        val decoded = IdentityAnnouncement.decode(payloadWithUnknownTlv)

        assertNotNull(decoded)
        assertEquals(nickname, decoded?.nickname)
        assertEquals(listOf(DogecoinIdentityAddress("testnet", testnetAddress)), decoded?.dogecoinAddresses)
    }

    @Test
    fun `encode decode round trip preserves node helper tlv`() {
        val announcement = IdentityAnnouncement(
            nickname = nickname,
            noisePublicKey = noiseKey,
            signingPublicKey = signingKey,
            dogecoinAddresses = listOf(DogecoinIdentityAddress("testnet", testnetAddress)),
            helperNetworks = listOf("testnet", "mainnet")
        )

        val decoded = IdentityAnnouncement.decode(announcement.encode()!!)

        assertEquals(announcement, decoded)
        assertEquals(listOf("testnet", "mainnet"), decoded?.helperNetworks)
        assertEquals(listOf(DogecoinIdentityAddress("testnet", testnetAddress)), decoded?.dogecoinAddresses)
    }

    @Test
    fun `node helper networks are deduped and capped on encode`() {
        val announcement = IdentityAnnouncement(
            nickname = nickname,
            noisePublicKey = noiseKey,
            signingPublicKey = signingKey,
            helperNetworks = listOf("testnet", "testnet", "mainnet", "regtest", "extra")
        )

        val decoded = IdentityAnnouncement.decode(announcement.encode()!!)

        assertEquals(listOf("testnet", "mainnet", "regtest"), decoded?.helperNetworks)
    }

    @Test
    fun `announcement without node helper tlv decodes empty`() {
        val announcement = IdentityAnnouncement(
            nickname = nickname,
            noisePublicKey = noiseKey,
            signingPublicKey = signingKey
        )

        val decoded = IdentityAnnouncement.decode(announcement.encode()!!)

        assertEquals(emptyList<String>(), decoded?.helperNetworks)
    }
}
