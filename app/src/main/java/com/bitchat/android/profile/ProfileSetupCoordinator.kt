package com.bitchat.android.profile

import android.app.Application
import android.content.Context
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
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
 *    "punch-through" toggle re-enables it when an ISP blocks Nostr (a later phase).
 *  - Proof-of-work OFF: a PoW mismatch otherwise SILENTLY drops a peer's geohash messages
 *    (GeohashMessageHandler), which would look like the app is simply broken to a non-technical user.
 *  - Optionally pin a shared family "room" geohash so the app boots straight into it.
 *
 * [AppProfile.POWER] only records the flag — a power user manages Tor / PoW / channels themselves, so
 * switching back never silently rewrites their settings.
 */
object ProfileSetupCoordinator {

    /**
     * The shared family-room geohash (30 Wakefield Ave, Wakefield MA — building precision, ~38x19 m cell).
     * Every family phone pins this SAME fixed string, so they share ONE room regardless of where they
     * physically are; the tight precision keeps strangers from stumbling in. A later provisioning phase
     * makes this configurable per family — for now it's the agreed default room key.
     */
    const val WAKEFIELD_FAMILY_ROOM = "drt3ydn6"

    // Geohash base32 alphabet (excludes a, i, l, o). A room key must be a valid geohash.
    private const val GEOHASH_ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz"

    /** A syntactically valid geohash: non-empty, <= 12 chars, base32 alphabet only. */
    fun isValidGeohash(geohash: String): Boolean =
        geohash.isNotEmpty() && geohash.length <= 12 && geohash.all { it in GEOHASH_ALPHABET }

    /**
     * Record [profile] and seed its defaults. For [AppProfile.SIMPLE], [roomGeohash] (at [roomLevel])
     * pins the shared family room when it is a valid geohash. Suspends because flipping Tor resets the
     * live network connections.
     */
    suspend fun applyProfileDefaults(
        application: Application,
        profile: AppProfile,
        roomGeohash: String? = null,
        roomLevel: GeohashChannelLevel = GeohashChannelLevel.BUILDING
    ) {
        ProfilePreferenceManager.set(application, profile)
        if (profile == AppProfile.SIMPLE) {
            // Apply the instant, load-bearing settings FIRST so they take effect immediately and are
            // never delayed — or stranded — by the slow live Tor teardown below (which can suspend for
            // tens of seconds while the network resets).
            // Reliability over anonymity: clearnet Nostr by default (pref takes effect on next connect).
            TorPreferenceManager.set(application, TorMode.OFF)
            // Never silently drop a peer's geohash messages on a PoW mismatch.
            PoWPreferenceManager.setPowEnabled(false)
            // Pin the shared family room, if one was provided.
            roomGeohash?.let { pinRoom(application, it, roomLevel) }
            // Tear down live Tor connections LAST — slow / may suspend a while; doing it after the prefs
            // above guarantees a sluggish or stuck teardown can't block them from applying.
            runCatching { ArtiTorManager.getInstance().applyMode(application, TorMode.OFF) }
        }
    }

    /**
     * Pin a geohash "room" as the active public channel (joined on the next observe of
     * [LocationChannelManager.selectedChannel]). No-ops on an invalid geohash so a bad provisioning
     * value can't strand the user on an unjoinable channel. Returns whether the room was pinned.
     */
    fun pinRoom(
        context: Context,
        geohash: String,
        level: GeohashChannelLevel = GeohashChannelLevel.BUILDING
    ): Boolean {
        val gh = geohash.trim().lowercase()
        if (!isValidGeohash(gh)) return false
        LocationChannelManager.getInstance(context).select(ChannelID.Location(GeohashChannel(level, gh)))
        return true
    }
}
