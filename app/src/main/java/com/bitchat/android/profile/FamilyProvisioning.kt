package com.bitchat.android.profile

import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.services.VerificationService
import com.bitchat.android.util.dataFromHexString

/**
 * Family provisioning for the SIMPLE profile. When a power user sets up a relative's phone, the two
 * phones scan each other's signed identity QR — the EXISTING [VerificationService.VerificationQR], which
 * already carries the peer's Noise static key + npub + nickname, Ed25519-signed. This writes that peer in
 * as a MUTUAL favorite so a private 1:1 Nostr DM thread works immediately, with no BLE/Noise handshake
 * (NIP-17 gift-wrapped DMs are self-contained — the relative may never be in Bluetooth range).
 *
 * Run on BOTH phones (each scans the other) for a symmetric, working thread. Because the power user
 * physically provisions both ends, we pre-set `theyFavoritedUs = true` rather than wait for an over-the-
 * wire favorite notification.
 *
 * The caller MUST pass a QR that already passed [VerificationService.verifyScannedQR] (signature valid),
 * so the fields here are trusted.
 */
object FamilyProvisioning {

    /**
     * Inject [qr]'s peer as a mutual favorite (we-favorite-them + they-favorited-us + npub binding).
     * Returns false only if the Noise key hex can't be decoded.
     */
    fun provisionFamilyContact(qr: VerificationService.VerificationQR): Boolean {
        val noiseKey = qr.noiseKeyHex.dataFromHexString() ?: return false
        val fav = FavoritesPersistenceService.shared
        fav.updateFavoriteStatus(noiseKey, qr.nickname, isFavorite = true) // we favorite them
        fav.updatePeerFavoritedUs(noiseKey, true)                          // pre-mark mutual (we set up both ends)
        qr.npub?.let { fav.updateNostrPublicKey(noiseKey, it) }            // bind npub for off-mesh Nostr routing
        return true
    }
}
