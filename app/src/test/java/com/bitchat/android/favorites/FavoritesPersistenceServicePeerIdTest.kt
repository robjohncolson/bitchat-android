package com.bitchat.android.favorites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Regression tests for the mutual-favorite key-mismatch bug: a peer's short mesh peerID is the first
 * 16 hex chars of its FINGERPRINT (SHA-256 of the Noise static key), not of the Noise key itself, so the
 * favorites lookup must match against the derived fingerprint. These exercise the pure matching helpers
 * (no Android Context required).
 */
class FavoritesPersistenceServicePeerIdTest {

    private val keyA = ByteArray(32) { it.toByte() }            // distinct, deterministic Noise keys
    private val keyB = ByteArray(32) { (100 + it).toByte() }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun rel(noiseKey: ByteArray, nick: String, fav: Boolean = true, theyFav: Boolean = true) =
        FavoriteRelationship(
            peerNoisePublicKey = noiseKey,
            peerNostrPublicKey = null,
            peerNickname = nick,
            isFavorite = fav,
            theyFavoritedUs = theyFav,
            favoritedAt = Date(0),
            lastUpdated = Date(0)
        )

    private fun favoritesOf(vararg keys: Pair<ByteArray, String>) =
        keys.associate { (k, nick) -> hex(k) to rel(k, nick) }

    @Test
    fun `resolves a favorite by its 16-hex fingerprint-prefix peerID`() {
        val favorites = favoritesOf(keyA to "alice", keyB to "bob")
        val peerIdA = FavoritesPersistenceService.fingerprintHex(keyA).take(16)
        val found = FavoritesPersistenceService.matchFavoriteByPeerID(favorites, peerIdA)
        assertNotNull("peerID derived from the fingerprint must resolve", found)
        assertEquals("alice", found!!.peerNickname)
        assertTrue("mutual status must now resolve", found.isMutual)
    }

    @Test
    fun `the fingerprint-prefix peerID differs from the noise-key prefix (root cause)`() {
        // This is exactly what the old code conflated: peerID (fingerprint prefix) != noise-key prefix.
        val fingerprintPrefix = FavoritesPersistenceService.fingerprintHex(keyA).take(16)
        val noiseKeyPrefix = hex(keyA).take(16)
        assertNotEquals(fingerprintPrefix, noiseKeyPrefix)
    }

    @Test
    fun `a noise-key prefix that is not a fingerprint prefix does not match (the old bug)`() {
        val favorites = favoritesOf(keyA to "alice")
        // The buggy implementation matched peerID against the raw noise-key hex; the fix keys off the
        // fingerprint, so a 16-hex noise-key prefix (not also a fingerprint prefix) must NOT resolve.
        assertNull(FavoritesPersistenceService.matchFavoriteByPeerID(favorites, hex(keyA).take(16)))
    }

    @Test
    fun `returns null for a non-matching peerID`() {
        val favorites = favoritesOf(keyA to "alice")
        assertNull(FavoritesPersistenceService.matchFavoriteByPeerID(favorites, "0123456789abcdef"))
    }

    @Test
    fun `a full noise-key hex still resolves via a direct map hit`() {
        val favorites = favoritesOf(keyA to "alice")
        val found = FavoritesPersistenceService.matchFavoriteByPeerID(favorites, hex(keyA))
        assertEquals("alice", found?.peerNickname)
    }

    @Test
    fun `peerID matching is case-insensitive`() {
        val favorites = favoritesOf(keyA to "alice")
        val peerIdUpper = FavoritesPersistenceService.fingerprintHex(keyA).take(16).uppercase()
        assertEquals("alice", FavoritesPersistenceService.matchFavoriteByPeerID(favorites, peerIdUpper)?.peerNickname)
    }

    @Test
    fun `a blank peerID returns null rather than the first favorite`() {
        val favorites = favoritesOf(keyA to "alice", keyB to "bob")
        assertNull(FavoritesPersistenceService.matchFavoriteByPeerID(favorites, ""))
    }

    @Test
    fun `fingerprintHex matches the canonical SHA-256 hex derivation`() {
        // Pin the derivation so it can't silently drift from NoiseEncryptionService.calculateFingerprint.
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(keyA).joinToString("") { "%02x".format(it) }
        assertEquals(expected, FavoritesPersistenceService.fingerprintHex(keyA))
    }
}
