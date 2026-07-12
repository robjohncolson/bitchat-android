package com.bitchat.android.profile

import android.app.Application
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.TorMode
import com.bitchat.android.net.TorPreferenceManager
import com.bitchat.android.nostr.PoWPreferenceManager

/**
 * Seeds the curated app-wide settings for an [AppProfile]. A device is exactly ONE profile, so this
 * just sets the EXISTING global managers — it never makes them profile-aware. Idempotent.
 *
 * [AppProfile.SIMPLE] (the "Family" experience) prioritizes RELIABILITY over anonymity:
 *  - Tor OFF: Nostr talks to relays over clearnet. The embedded Tor stalls on flaky circuits; a
 *    "punch-through" toggle re-enables it when an ISP blocks Nostr.
 *  - Proof-of-work OFF: a PoW mismatch otherwise SILENTLY drops a peer's geohash messages
 *    (GeohashMessageHandler), which would look like the app is simply broken to a non-technical user.
 *  - No public channel: the old public "family room" leaked stranger messages (geohash chat is public),
 *    so Simple sits on mesh and talks only over private 1:1 / E2E-group DMs.
 *
 * [AppProfile.POWER] only records the flag — a power user manages Tor / PoW / channels themselves, so
 * switching back never silently rewrites their settings.
 */
object ProfileSetupCoordinator {

    // Geohash base32 alphabet (excludes a, i, l, o).
    private const val GEOHASH_ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz"

    /** A syntactically valid geohash: non-empty, <= 12 chars, base32 alphabet only. */
    fun isValidGeohash(geohash: String): Boolean =
        geohash.isNotEmpty() && geohash.length <= 12 && geohash.all { it in GEOHASH_ALPHABET }

    /**
     * Record [profile] and seed its defaults. Suspends because flipping Tor resets the live network
     * connections.
     */
    suspend fun applyProfileDefaults(application: Application, profile: AppProfile) {
        ProfilePreferenceManager.set(application, profile)
        if (profile == AppProfile.SIMPLE) {
            // Apply the instant, load-bearing settings FIRST so they take effect immediately and are
            // never delayed — or stranded — by the slow live Tor teardown below (which can suspend for
            // tens of seconds while the network resets).
            // Reliability over anonymity: clearnet Nostr by default (pref takes effect on next connect).
            TorPreferenceManager.set(application, TorMode.OFF)
            // Never silently drop a peer's geohash messages on a PoW mismatch.
            PoWPreferenceManager.setPowEnabled(false)
            // The public geohash "family room" is REMOVED (it leaked stranger messages — geohash chat is
            // public + was pinned to a real location). Make sure Simple sits on NO public channel — fall
            // back to mesh; Simple talks over private 1:1 / E2E-group DMs only.
            LocationChannelManager.getInstance(application).select(ChannelID.Mesh)
            // Tear down live Tor connections LAST — slow / may suspend a while; doing it after the prefs
            // above guarantees a sluggish or stuck teardown can't block them from applying.
            runCatching { ArtiTorManager.getInstance().applyMode(application, TorMode.OFF) }
        }
    }
}
