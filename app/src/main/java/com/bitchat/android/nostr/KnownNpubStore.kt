package com.bitchat.android.nostr

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * KnownNpubStore — a tiny, isolated store of npub-only "known" contacts (account-pubkey hex -> display name)
 * that the Simple UI lists alongside mutual favorites. Populated by "tap a group member's name -> Add".
 *
 * DELIBERATELY separate from FavoritesPersistenceService. A FavoriteRelationship requires a 32-byte Noise
 * static key, which is load-bearing for mesh routing, the fingerprint index, the Dogecoin broadcast-helper
 * mutual-favorite gate, AND the E2E-group transitive-trust gate. A group only carries an npub, so a
 * tap-added contact is a Nostr-DM-only relationship: it can 1:1 DM and appears in the list, but is NOT a
 * favorite, cannot be a Dogecoin helper, and is never auto-trusted as a group member. Upgrading to a
 * verified mutual favorite still requires scanning the signed identity QR. Carries NO secret material.
 */
object KnownNpubStore {
    private val map: MutableMap<String, String> = ConcurrentHashMap()   // account pubkey hex (lowercase) -> name
    private const val PREFS_NAME = "known_npub_store"
    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs?.all?.forEach { (k, v) -> if (k is String && v is String) map[k] = v }
        }
    }

    fun put(pubkeyHex: String, name: String) {
        val hex = pubkeyHex.lowercase()
        map[hex] = name
        prefs?.edit()?.putString(hex, name)?.apply()
    }

    fun get(pubkeyHex: String): String? = map[pubkeyHex.lowercase()]

    fun contains(pubkeyHex: String): Boolean = map.containsKey(pubkeyHex.lowercase())

    fun snapshot(): Map<String, String> = HashMap(map)

    fun remove(pubkeyHex: String) {
        val hex = pubkeyHex.lowercase()
        map.remove(hex)
        prefs?.edit()?.remove(hex)?.apply()
    }
}
