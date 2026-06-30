package com.bitchat.android.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure (no-Android) guard on the room-key validation used by the SIMPLE profile seeder. The seeder's
 * actual side effects (Tor off, PoW off, channel pin) are Android-backed and verified on-device via the
 * `profile` debug-console command; what we pin here is that a bad geohash can never reach
 * LocationChannelManager.select.
 */
class ProfileSetupCoordinatorTest {

    @Test
    fun `accepts a valid building-level geohash`() {
        assertTrue(ProfileSetupCoordinator.isValidGeohash("drt2zm9k")) // 8 chars, all base32
    }

    @Test
    fun `rejects empty geohash`() {
        assertFalse(ProfileSetupCoordinator.isValidGeohash(""))
    }

    @Test
    fun `rejects an overlong geohash`() {
        assertFalse(ProfileSetupCoordinator.isValidGeohash("bcdefghjkmnpq")) // 13 chars
    }

    @Test
    fun `rejects out-of-alphabet characters`() {
        // 'a', 'i', 'l', 'o' are NOT in the geohash base32 alphabet.
        assertFalse(ProfileSetupCoordinator.isValidGeohash("drt2za"))
        assertFalse(ProfileSetupCoordinator.isValidGeohash("hello1"))
    }
}
