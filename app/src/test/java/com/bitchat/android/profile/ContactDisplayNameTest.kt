package com.bitchat.android.profile

import com.bitchat.android.favorites.FavoriteRelationship
import com.bitchat.android.favorites.FavoritesPersistenceService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Workstream A — pure display-name resolution (spec §3). No Android Context.
 */
class ContactDisplayNameTest {

    private val keyA = ByteArray(32) { it.toByte() }
    private val keyB = ByteArray(32) { (50 + it).toByte() }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun rel(
        noiseKey: ByteArray,
        nick: String,
        nostrHex: String? = null
    ) = FavoriteRelationship(
        peerNoisePublicKey = noiseKey,
        peerNostrPublicKey = nostrHex,
        peerNickname = nick,
        isFavorite = true,
        theyFavoritedUs = true,
        favoritedAt = Date(0),
        lastUpdated = Date(0)
    )

    @Test
    fun `banned tokens include anon Unknown and anon hash suffix`() {
        assertTrue(ContactDisplayName.isBannedToken("anon"))
        assertTrue(ContactDisplayName.isBannedToken("ANON"))
        assertTrue(ContactDisplayName.isBannedToken("anon#a1b2"))
        assertTrue(ContactDisplayName.isBannedToken("Unknown"))
        assertTrue(ContactDisplayName.isBannedToken("unknown"))
        assertTrue(ContactDisplayName.isBannedToken(null))
        assertTrue(ContactDisplayName.isBannedToken("  "))
        assertFalse(ContactDisplayName.isBannedToken("googlepad"))
        assertFalse(ContactDisplayName.isBannedToken("blueberry"))
    }

    @Test
    fun `sanitize strips bidi overrides and control chars`() {
        val dirty = "mo\u202Em\u0000"
        val clean = ContactDisplayName.sanitizeRemote(dirty)
        assertNotNull(clean)
        assertFalse(clean!!.contains('\u202E'))
        assertFalse(clean.contains('\u0000'))
    }

    @Test
    fun `sanitizeRemote rejects banned tokens`() {
        assertNull(ContactDisplayName.sanitizeRemote("anon"))
        assertNull(ContactDisplayName.sanitizeRemote("anon#dead"))
        assertNull(ContactDisplayName.sanitizeRemote("Unknown"))
    }

    @Test
    fun `pet-name input caps at 24 and rejects empty banned`() {
        assertEquals("googlepad", ContactDisplayName.sanitizePetNameInput("  googlepad  "))
        assertNull(ContactDisplayName.sanitizePetNameInput("anon"))
        assertNull(ContactDisplayName.sanitizePetNameInput("   "))
        val long = "a".repeat(40)
        assertEquals(24, ContactDisplayName.sanitizePetNameInput(long)!!.length)
    }

    @Test
    fun `local label wins over message sender`() {
        val r = ContactDisplayName.resolve(
            localLabel = "googlepad",
            messageSenderFallback = "blueberry",
            allLocalLabels = setOf("googlepad"),
            familyFallback = "Family",
            shortIdHex = "abcd"
        )
        assertEquals("googlepad", r.display)
        assertTrue(r.isLocalLabel)
        assertFalse(r.isRemoteAsserted)
    }

    @Test
    fun `banned stored sender falls through to short id fallback`() {
        val r = ContactDisplayName.resolve(
            localLabel = null,
            messageSenderFallback = "anon",
            allLocalLabels = emptySet(),
            familyFallback = "Family",
            shortIdHex = "a1b2"
        )
        assertEquals("Family · a1b2", r.display)
        assertFalse(r.isLocalLabel)
        assertFalse(r.display.equals("anon", ignoreCase = true))
    }

    @Test
    fun `remote name colliding with local label gets disambiguator`() {
        val r = ContactDisplayName.resolve(
            localLabel = null,
            messageSenderFallback = "Mom",
            allLocalLabels = setOf("mom"),
            familyFallback = "Family",
            shortIdHex = "dead"
        )
        assertTrue(r.isUnverifiedCollision)
        assertTrue(r.display.contains("dead"))
        assertTrue(r.isRemoteAsserted)
    }

    @Test
    fun `lookup by fingerprint mesh peerID not noise-key prefix`() {
        val favorites = mapOf(hex(keyA) to rel(keyA, "alice"))
        val fingerprintPrefix = FavoritesPersistenceService.fingerprintHex(keyA).take(16)
        val noisePrefix = hex(keyA).take(16)
        assertNotEquals(
            "fingerprint-not-noise-prefix: mesh peerID must differ from noise prefix",
            fingerprintPrefix,
            noisePrefix
        )

        val byFingerprint = ContactDisplayName.lookupLocalLabel(
            ContactDisplayName.Identity(meshPeerId = fingerprintPrefix),
            favorites,
            emptyMap()
        )
        assertEquals("alice", byFingerprint)

        val byNoisePrefix = ContactDisplayName.lookupLocalLabel(
            ContactDisplayName.Identity(meshPeerId = noisePrefix),
            favorites,
            emptyMap()
        )
        // noise-key prefix must NOT resolve as mesh peerID (the shipped bug class)
        assertNull(byNoisePrefix)
    }

    @Test
    fun fingerprint_not_noise_prefix() {
        // Named exactly as required by the spec's unit-test checklist.
        `lookup by fingerprint mesh peerID not noise-key prefix`()
    }

    @Test
    fun `lookup by full noise key hex`() {
        val favorites = mapOf(hex(keyA) to rel(keyA, "pad"))
        val label = ContactDisplayName.lookupLocalLabel(
            ContactDisplayName.Identity(noiseKeyHex = hex(keyA)),
            favorites,
            emptyMap()
        )
        assertEquals("pad", label)
    }

    @Test
    fun `lookup by nostr hex prefers lowest noise key when multi-device`() {
        val nostr = "aa".repeat(32)
        // keyB hex sorts after keyA if keyA bytes are 0..31 and keyB is 50.. — actually need deterministic order
        val lowKey = ByteArray(32) { 0x01 }
        val highKey = ByteArray(32) { 0xFF.toByte() }
        val favorites = mapOf(
            hex(highKey) to rel(highKey, "tablet-b", nostr),
            hex(lowKey) to rel(lowKey, "tablet-a", nostr)
        )
        val label = ContactDisplayName.lookupLocalLabel(
            ContactDisplayName.Identity(nostrPubkeyHex = nostr),
            favorites,
            emptyMap()
        )
        assertEquals("tablet-a", label) // lowest noise hex
    }

    @Test
    fun `KnownNpub label used when no favorite`() {
        val nostr = "bb".repeat(32)
        val label = ContactDisplayName.lookupLocalLabel(
            ContactDisplayName.Identity(nostrPubkeyHex = nostr),
            emptyMap(),
            mapOf(nostr to "from-group")
        )
        assertEquals("from-group", label)
    }

    @Test
    fun `shortIdFromIdentity uses convKey prefix`() {
        val id = ContactDisplayName.Identity(convKey = "nostr_abcdef0123456789")
        assertEquals("abcd", ContactDisplayName.shortIdFromIdentity(id))
    }
}
