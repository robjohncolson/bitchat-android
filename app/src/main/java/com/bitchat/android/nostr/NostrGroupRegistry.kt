package com.bitchat.android.nostr

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import java.util.concurrent.ConcurrentHashMap

/**
 * NostrGroupRegistry
 * - Global, thread-safe registry for E2E "family group" conversations:
 *     convKey ("nostr_grp_<id>") -> (groupId, member account-pubkey hexes, optional subject).
 * - The reply-routing source of truth: MessageRouter.sendPrivate fans a reply out to the whole member
 *   set via NostrTransport.sendGroupMessage. Populated on both group creation (send) and receive.
 * - Persisted to SharedPreferences (one JSON value per convKey) to survive app restarts, mirroring
 *   [GeohashAliasRegistry].
 *
 * A group is just N NIP-17 1:1 gift wraps that share a group id sealed inside the rumor; this registry
 * holds the membership so a typed reply re-fans to everyone. It carries NO secret material.
 */
object NostrGroupRegistry {

    data class Group(
        val groupId: String,
        val members: List<String>,   // account pubkey hexes (lowercase), incl. self
        val subject: String?
    )

    private val map: MutableMap<String, Group> = ConcurrentHashMap()
    private const val PREFS_NAME = "nostr_group_registry"
    private var prefs: SharedPreferences? = null
    private val gson = Gson()

    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadFromPrefs()
        }
    }

    private fun loadFromPrefs() {
        prefs?.all?.forEach { (key, value) ->
            if (key is String && value is String) {
                runCatching { gson.fromJson(value, Group::class.java) }.getOrNull()?.let { map[key] = it }
            }
        }
    }

    /** Record/refresh a group. Members are normalized to lowercase + deduped. */
    fun put(convKey: String, groupId: String, members: List<String>, subject: String?) {
        val group = Group(groupId, members.map { it.lowercase() }.distinct(), subject?.takeIf { it.isNotBlank() })
        map[convKey] = group
        prefs?.edit()?.putString(convKey, gson.toJson(group))?.apply()
    }

    fun get(convKey: String): Group? = map[convKey]

    fun contains(convKey: String): Boolean = map.containsKey(convKey)

    fun snapshot(): Map<String, Group> = HashMap(map)

    fun clear() {
        map.clear()
        prefs?.edit()?.clear()?.apply()
    }
}
