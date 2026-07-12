package com.bitchat.android.profile

import com.bitchat.android.model.BitchatMessage

/**
 * Pure derivation of Simple-home chat-list activity (spec P1-2): last message, last-activity time, and
 * unread count for one conversation, plus recency ordering across conversations.
 *
 * A Simple 1:1 contact is filed under SEVERAL keys (nostr_<pub16> alias, 64-hex Noise key, mesh peerID),
 * so callers pass the whole key set and this unions them — mirroring [SimpleConversation]'s message union
 * so the home preview always matches what the thread shows.
 *
 * Pure/read-only: no persistence, no [SeenMessageStore] coupling (read-state is injected as [isRead] so
 * this is unit-testable and never touches the main thread with I/O). It never marks anything read.
 */
object SimpleChatActivity {

    data class Activity(
        val lastActivityMs: Long,
        val lastMessage: BitchatMessage?,
        val unreadCount: Int
    )

    /**
     * @param conversationKeys every key this conversation's messages could be filed under
     * @param privateChats the app's convKey -> messages map
     * @param myNickname the local user's nickname; messages from this sender are treated as outgoing and
     *   never counted as unread (mirrors the app's existing unread recompute predicate)
     * @param isRead in-memory read check (e.g. SeenMessageStore::hasRead) — no I/O
     */
    fun compute(
        conversationKeys: Set<String>,
        privateChats: Map<String, List<BitchatMessage>>,
        myNickname: String?,
        isRead: (String) -> Boolean
    ): Activity {
        var last: BitchatMessage? = null
        var lastMs = 0L
        val countedIds = HashSet<String>()
        var unread = 0
        for (key in conversationKeys) {
            val list = privateChats[key] ?: continue
            for (msg in list) {
                val ts = msg.timestamp.time
                if (last == null || ts > lastMs) {
                    last = msg
                    lastMs = ts
                }
                val incoming = myNickname == null || msg.sender != myNickname
                if (incoming && !isRead(msg.id) && countedIds.add(msg.id)) unread++
            }
        }
        return Activity(lastActivityMs = lastMs, lastMessage = last, unreadCount = unread)
    }

    /**
     * Order rows most-recent-first. Kotlin's sort is stable, so conversations with equal activity — in
     * particular those with none yet ([lastActivityMs] == 0) — keep their input order and sink below any
     * conversation that has messages.
     */
    fun <T> sortByRecency(rows: List<T>, lastActivityMs: (T) -> Long): List<T> =
        rows.sortedByDescending { lastActivityMs(it) }
}
