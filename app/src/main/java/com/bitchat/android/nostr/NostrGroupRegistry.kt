package com.bitchat.android.nostr

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * NostrGroupRegistry — global, thread-safe registry for E2E "family group" conversations:
 *     convKey ("nostr_grp_<id>") -> Group(groupId, members, subject).
 * Reply-routing source of truth: MessageRouter.sendPrivate re-fans a typed reply to the whole member set
 * via NostrTransport.sendGroupMessage. Populated on group creation (send) and — ONLY AFTER the receive trust
 * gate accepts — on receive. Persisted to SharedPreferences (one JSON value per convKey). NO secret material.
 *
 * groupId is the DETERMINISTIC sha256 of the sorted member account-pubkey hexes ([computeGroupId]); members
 * carry an optional display name for discovery (names ride in the SEALED rumor, never the public wrap).
 */
object NostrGroupRegistry {

    data class GroupMember(val pubkeyHex: String, val name: String? = null)

    data class Group(
        val groupId: String,
        val members: List<GroupMember>,
        val subject: String?
    ) {
        /** Account-pubkey hexes (lowercase) — used for reply fan-out + the groupId integrity check. */
        val memberHexes: List<String> get() = members.map { it.pubkeyHex }
    }

    private val map: MutableMap<String, Group> = ConcurrentHashMap()
    private const val PREFS_NAME = "nostr_group_registry"
    private var prefs: SharedPreferences? = null
    private val gson = Gson()

    /**
     * The ONE definition of a group's id: sha256 of the sorted, lowercased, distinct member hexes, first 16
     * hex chars. Every member derives the identical id with no setup handshake; membership is immutable per
     * thread (adding anyone yields a new id / a new thread), which the receive gate enforces.
     */
    fun computeGroupId(memberHexes: Collection<String>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(memberHexes.map { it.lowercase() }.distinct().sorted().joinToString(",").toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadFromPrefs()
        }
    }

    private fun loadFromPrefs() {
        prefs?.all?.forEach { (key, value) ->
            if (key is String && value is String) {
                // Increment-1 stored members as List<String>; that JSON no longer parses into List<GroupMember>
                // and is silently dropped here (those groups were console-only test artifacts).
                runCatching { gson.fromJson(value, Group::class.java) }.getOrNull()
                    ?.takeIf { it.members.isNotEmpty() }
                    ?.let { map[key] = it }
            }
        }
    }

    /** Record/refresh a group. Members deduped by lowercased hex, preferring a non-blank name. */
    fun put(convKey: String, groupId: String, members: List<GroupMember>, subject: String?) {
        val deduped = LinkedHashMap<String, GroupMember>()
        members.forEach { m ->
            val hex = m.pubkeyHex.lowercase()
            val name = m.name?.takeIf { it.isNotBlank() } ?: deduped[hex]?.name
            deduped[hex] = GroupMember(hex, name)
        }
        val group = Group(groupId, deduped.values.toList(), subject?.takeIf { it.isNotBlank() })
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
