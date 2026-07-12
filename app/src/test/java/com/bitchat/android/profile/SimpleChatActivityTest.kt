package com.bitchat.android.profile

import com.bitchat.android.model.BitchatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

/**
 * Pure guards for the Simple chat-list activity derivation (P1-2): last-message selection across a
 * contact's multiple filing keys, unread counting (dedup by id, own messages excluded, read excluded),
 * and stable recency ordering.
 */
class SimpleChatActivityTest {

    private fun msg(id: String, sender: String, content: String, tsMs: Long) =
        BitchatMessage(id = id, sender = sender, content = content, timestamp = Date(tsMs))

    @Test
    fun `last message is the most recent across all conversation keys`() {
        val chats = mapOf(
            "nostr_abc" to listOf(msg("1", "them", "hi", 1_000L)),
            "noiseHex" to listOf(msg("2", "them", "later over mesh", 3_000L)),
            "meshpid" to listOf(msg("3", "them", "middle", 2_000L))
        )
        val a = SimpleChatActivity.compute(
            conversationKeys = setOf("nostr_abc", "noiseHex", "meshpid"),
            privateChats = chats,
            myNickname = "me",
            isRead = { false }
        )
        assertEquals(3_000L, a.lastActivityMs)
        assertEquals("later over mesh", a.lastMessage?.content)
    }

    @Test
    fun `unread excludes own messages and already-read, and dedups by id across keys`() {
        val shared = msg("dup", "them", "seen once under two keys", 5_000L)
        val chats = mapOf(
            "k1" to listOf(
                msg("a", "them", "unread incoming", 1_000L),
                msg("b", "me", "my own message", 2_000L),   // own -> never unread
                msg("c", "them", "read incoming", 3_000L),  // read -> not counted
                shared
            ),
            "k2" to listOf(shared) // same id under a second key -> counted once
        )
        val a = SimpleChatActivity.compute(
            conversationKeys = setOf("k1", "k2"),
            privateChats = chats,
            myNickname = "me",
            isRead = { id -> id == "c" }
        )
        // "a" + "dup" are unread incoming; "b" is own; "c" is read; "dup" counted once.
        assertEquals(2, a.unreadCount)
    }

    @Test
    fun `absent conversation yields zero activity and null last message`() {
        val a = SimpleChatActivity.compute(
            conversationKeys = setOf("nobody"),
            privateChats = emptyMap(),
            myNickname = "me",
            isRead = { false }
        )
        assertEquals(0L, a.lastActivityMs)
        assertNull(a.lastMessage)
        assertEquals(0, a.unreadCount)
    }

    @Test
    fun `null nickname treats every message as incoming`() {
        val chats = mapOf("k" to listOf(msg("1", "anyone", "x", 1L)))
        val a = SimpleChatActivity.compute(setOf("k"), chats, myNickname = null, isRead = { false })
        assertEquals(1, a.unreadCount)
    }

    @Test
    fun `recency ordering is most-recent first and stable for zero-activity ties`() {
        data class Row(val name: String, val lastMs: Long)
        val rows = listOf(
            Row("older", 1_000L),
            Row("newest", 9_000L),
            Row("noMessagesA", 0L),
            Row("middle", 5_000L),
            Row("noMessagesB", 0L)
        )
        val sorted = SimpleChatActivity.sortByRecency(rows) { it.lastMs }.map { it.name }
        // Active rows descending by time; the two zero-activity rows sink last and keep their input order.
        assertEquals(listOf("newest", "middle", "older", "noMessagesA", "noMessagesB"), sorted)
    }
}
