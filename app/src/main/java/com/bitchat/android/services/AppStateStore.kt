package com.bitchat.android.services

import android.content.Context
import android.util.Log
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date

/**
 * Process-wide in-memory state store that survives Activity recreation.
 * The foreground Mesh service updates this store; UI subscribes/hydrates from it.
 */
object AppStateStore {
    private const val TAG = "AppStateStore"
    // Global de-dup set by message id to avoid duplicate keys in Compose lists
    private val seenMessageIds = mutableSetOf<String>()
    private val seenPublicMessageKeys = mutableSetOf<String>()
    private val peerIdsByTransport = mutableMapOf<String, Set<String>>()
    // Direct (single-hop) peer IDs per transport, used to gossip a unified neighbor set.
    private val directPeerIdsByTransport = mutableMapOf<String, Set<String>>()
    // Connected peer IDs (mesh ephemeral IDs)
    private val _peers = MutableStateFlow<List<String>>(emptyList())
    val peers: StateFlow<List<String>> = _peers.asStateFlow()

    // Public mesh timeline messages (non-channel)
    private val _publicMessages = MutableStateFlow<List<BitchatMessage>>(emptyList())
    val publicMessages: StateFlow<List<BitchatMessage>> = _publicMessages.asStateFlow()

    // Private messages by peerID
    private val _privateMessages = MutableStateFlow<Map<String, List<BitchatMessage>>>(emptyMap())
    val privateMessages: StateFlow<Map<String, List<BitchatMessage>>> = _privateMessages.asStateFlow()

    // Channel messages by channel name
    private val _channelMessages = MutableStateFlow<Map<String, List<BitchatMessage>>>(emptyMap())
    val channelMessages: StateFlow<Map<String, List<BitchatMessage>>> = _channelMessages.asStateFlow()

    // --- Persistence: make private (DM) + channel history survive process death ---
    // Messages were previously in-memory only, so a process kill / app exit lost all chat history. We now
    // debounce-write the two message maps to a small file and restore them at startup. No-op until init().
    @Volatile private var appContext: Context? = null
    private val persistScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var persistJob: Job? = null
    private val writeLock = Any()
    // Bumped by wipePersisted(). persistNow() snapshots this before encoding and re-checks it under writeLock,
    // so an in-flight write that began before a panic wipe cannot resurrect the deleted plaintext file
    // (coroutine cancellation is cooperative and persistNow has no suspension points, so cancel() alone is
    // not enough once the debounced job has passed delay()).
    @Volatile private var persistGeneration = 0
    private val gson = Gson()
    private const val HISTORY_FILE = "chat_history_v1.json"
    private const val MAX_PER_CONVERSATION = 1000
    private const val PERSIST_DEBOUNCE_MS = 750L

    /** Wire an application Context once (from BitchatApplication) to enable on-disk history. */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /**
     * Restore persisted private + channel history into the in-memory flows. Call ONCE at process start,
     * BEFORE any UI subscribes, so the first emission already carries history. No-op without [init].
     */
    fun load() {
        val ctx = appContext ?: return
        synchronized(this) {
            val file = File(ctx.filesDir, HISTORY_FILE)
            if (!file.exists()) return
            val stored = try {
                gson.fromJson(file.readText(), StoredHistory::class.java)
            } catch (e: Exception) {
                // Corrupt / unparseable history (truncated write, disk error, or a future schema change).
                // Quarantine the bad file instead of silently discarding it — otherwise the first new message
                // would call persistNow() and overwrite the only (possibly recoverable) copy, making the loss
                // permanent. Start empty this session; the .corrupt-* copy is left for manual recovery.
                Log.e(TAG, "Failed to parse chat history; quarantining and starting empty: ${e.message}")
                runCatching { file.renameTo(File(ctx.filesDir, "$HISTORY_FILE.corrupt-${file.lastModified()}")) }
                return
            } ?: return
            val priv = decodeMap(stored.privateChats)
            val chan = decodeMap(stored.channels)
            // Seed the de-dup set so a re-received message (e.g. a Nostr DM redelivered within the 48h
            // window, or a mesh echo) is not appended again on top of the restored copy.
            priv.values.forEach { list -> list.forEach { seenMessageIds.add(it.id) } }
            chan.values.forEach { list -> list.forEach { seenMessageIds.add(it.id) } }
            if (priv.isNotEmpty()) _privateMessages.value = priv
            if (chan.isNotEmpty()) _channelMessages.value = chan
        }
    }

    private data class StoredHistory(
        val privateChats: Map<String, List<StoredMsg>> = emptyMap(),
        val channels: Map<String, List<StoredMsg>> = emptyMap()
    )

    // A flat, self-describing record. (The binary wire payload would have dropped `type` and
    // `deliveryStatus` and silently lost any message over ~4 KB; this keeps all display fields + status
    // with no size cap.)
    private data class StoredMsg(
        val id: String,
        val sender: String,
        val content: String,
        val type: String,
        val ts: Long,
        val isPrivate: Boolean,
        val senderPeerID: String?,
        val recipientNickname: String?,
        val channel: String?,
        val mentions: List<String>?,
        val originalSender: String?,
        val isRelay: Boolean,
        val status: StoredStatus?,
        // Group sender's account pubkey — drives the Simple "tap a name to add" affordance. Without persisting
        // it, restored group bubbles decode with null and the feature silently dies for existing messages.
        val senderNostrPubkey: String? = null
    )

    private data class StoredStatus(
        val kind: String,
        val who: String? = null,
        val at: Long? = null,
        val reached: Int? = null,
        val total: Int? = null
    )

    private fun encodeMap(map: Map<String, List<BitchatMessage>>): Map<String, List<StoredMsg>> =
        map.mapValues { (_, list) -> list.takeLast(MAX_PER_CONVERSATION).map { encode(it) } }
            .filterValues { it.isNotEmpty() }

    private fun decodeMap(map: Map<String, List<StoredMsg>>): Map<String, List<BitchatMessage>> =
        map.mapValues { (_, list) -> list.mapNotNull { decode(it) } }
            .filterValues { it.isNotEmpty() }

    private fun encode(m: BitchatMessage) = StoredMsg(
        id = m.id, sender = m.sender, content = m.content, type = m.type.name, ts = m.timestamp.time,
        isPrivate = m.isPrivate, senderPeerID = m.senderPeerID, recipientNickname = m.recipientNickname,
        channel = m.channel, mentions = m.mentions, originalSender = m.originalSender, isRelay = m.isRelay,
        status = encodeStatus(m.deliveryStatus), senderNostrPubkey = m.senderNostrPubkey
    )

    private fun decode(s: StoredMsg): BitchatMessage? = runCatching {
        BitchatMessage(
            id = s.id, sender = s.sender, content = s.content,
            type = runCatching { BitchatMessageType.valueOf(s.type) }.getOrDefault(BitchatMessageType.Message),
            timestamp = Date(s.ts), isRelay = s.isRelay, originalSender = s.originalSender,
            isPrivate = s.isPrivate, recipientNickname = s.recipientNickname, senderPeerID = s.senderPeerID,
            mentions = s.mentions, channel = s.channel, deliveryStatus = decodeStatus(s.status),
            senderNostrPubkey = s.senderNostrPubkey
        )
    }.getOrNull()

    private fun encodeStatus(s: DeliveryStatus?): StoredStatus? = when (s) {
        null -> null
        is DeliveryStatus.Sending -> StoredStatus("sending")
        is DeliveryStatus.Sent -> StoredStatus("sent")
        is DeliveryStatus.Delivered -> StoredStatus("delivered", who = s.to, at = s.at.time)
        is DeliveryStatus.Read -> StoredStatus("read", who = s.by, at = s.at.time)
        is DeliveryStatus.Failed -> StoredStatus("failed", who = s.reason)
        is DeliveryStatus.PartiallyDelivered -> StoredStatus("partial", reached = s.reached, total = s.total)
    }

    private fun decodeStatus(s: StoredStatus?): DeliveryStatus? = when (s?.kind) {
        "sending" -> DeliveryStatus.Sending
        "sent" -> DeliveryStatus.Sent
        "delivered" -> DeliveryStatus.Delivered(s.who ?: "", Date(s.at ?: 0L))
        "read" -> DeliveryStatus.Read(s.who ?: "", Date(s.at ?: 0L))
        "failed" -> DeliveryStatus.Failed(s.who ?: "")
        "partial" -> DeliveryStatus.PartiallyDelivered(s.reached ?: 0, s.total ?: 0)
        else -> null
    }

    /** Coalesce rapid message bursts into a single debounced disk write (off the caller's thread). */
    private fun schedulePersist() {
        if (appContext == null) return
        persistJob?.cancel()
        persistJob = persistScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persistNow()
        }
    }

    // Atomic + serialized write: encode the snapshot, then write a temp file and rename it over the target
    // under a lock — so an ill-timed process kill (or a concurrent flush from clear()) can never truncate or
    // corrupt the live history file. The previous good file stays intact until the rename completes.
    private fun persistNow() {
        val ctx = appContext ?: return
        val genAtStart = persistGeneration
        val json = runCatching {
            gson.toJson(
                StoredHistory(
                    privateChats = encodeMap(_privateMessages.value),
                    channels = encodeMap(_channelMessages.value)
                )
            )
        }.getOrNull() ?: return
        synchronized(writeLock) {
            // A panic wipe may have run after we snapshotted the maps above. Re-check under the same lock the
            // wipe holds, and abort rather than recreate the file with pre-wipe plaintext.
            if (persistGeneration != genAtStart) return
            runCatching {
                val dir = ctx.filesDir
                val tmp = File(dir, "$HISTORY_FILE.tmp")
                val dst = File(dir, HISTORY_FILE)
                tmp.writeText(json)
                if (!tmp.renameTo(dst)) {     // some filesystems won't rename onto an existing file
                    dst.delete()
                    tmp.renameTo(dst)
                }
            }
        }
    }

    fun setPeers(ids: List<String>) {
        synchronized(this) {
            _peers.value = ids.distinct()
        }
    }

    fun setTransportPeers(transportId: String, ids: List<String>) {
        synchronized(this) {
            peerIdsByTransport[transportId] = ids.toSet()
            publishTransportPeersLocked()
        }
    }

    fun clearTransportPeers(transportId: String) {
        synchronized(this) {
            peerIdsByTransport.remove(transportId)
            publishTransportPeersLocked()
        }
    }

    private fun publishTransportPeersLocked() {
        _peers.value = peerIdsByTransport.values
            .asSequence()
            .flatten()
            .distinct()
            .toList()
    }

    /**
     * Record the set of direct (single-hop) peers reachable over a given transport. Each transport
     * (BLE, Wi-Fi Aware, ...) only knows its own direct peers; [getDirectPeers] unions them so every
     * transport can gossip the same complete neighbor list under our shared node identity.
     */
    fun setTransportDirectPeers(transportId: String, ids: Collection<String>) {
        synchronized(this) {
            directPeerIdsByTransport[transportId] = ids.toSet()
        }
    }

    fun clearTransportDirectPeers(transportId: String) {
        synchronized(this) {
            directPeerIdsByTransport.remove(transportId)
        }
    }

    /** Union of direct peers across all transports. */
    fun getDirectPeers(): Set<String> {
        synchronized(this) {
            return directPeerIdsByTransport.values.flatten().toSet()
        }
    }

    fun addPublicMessage(msg: BitchatMessage) {
        synchronized(this) {
            val publicKey = publicMessageKey(msg)
            if (seenMessageIds.contains(msg.id) || seenPublicMessageKeys.contains(publicKey)) return
            seenMessageIds.add(msg.id)
            seenPublicMessageKeys.add(publicKey)
            _publicMessages.value = _publicMessages.value + msg
        }
    }

    fun addPrivateMessage(peerID: String, msg: BitchatMessage) {
        synchronized(this) {
            if (seenMessageIds.contains(msg.id)) return
            seenMessageIds.add(msg.id)
            val map = _privateMessages.value.toMutableMap()
            val list = (map[peerID] ?: emptyList()) + msg
            map[peerID] = list
            _privateMessages.value = map
            schedulePersist()
        }
    }

    private fun statusPriority(status: DeliveryStatus?): Int = when (status) {
        null -> 0
        is DeliveryStatus.Sending -> 1
        is DeliveryStatus.Sent -> 2
        is DeliveryStatus.PartiallyDelivered -> 3
        is DeliveryStatus.Delivered -> 4
        is DeliveryStatus.Read -> 5
        is DeliveryStatus.Failed -> 0
    }

    fun updatePrivateMessageStatus(messageID: String, status: DeliveryStatus) {
        synchronized(this) {
            val map = _privateMessages.value.toMutableMap()
            var changed = false
            map.keys.toList().forEach { peer ->
                val list = map[peer]?.toMutableList() ?: mutableListOf()
                val idx = list.indexOfFirst { it.id == messageID }
                if (idx >= 0) {
                    val current = list[idx].deliveryStatus
                    // Do not downgrade (e.g., Read -> Delivered)
                    if (statusPriority(status) >= statusPriority(current)) {
                        list[idx] = list[idx].copy(deliveryStatus = status)
                        map[peer] = list
                        changed = true
                    }
                }
            }
            if (changed) {
                _privateMessages.value = map
                schedulePersist()
            }
        }
    }

    fun addChannelMessage(channel: String, msg: BitchatMessage) {
        synchronized(this) {
            if (seenMessageIds.contains(msg.id)) return
            seenMessageIds.add(msg.id)
            val map = _channelMessages.value.toMutableMap()
            val list = (map[channel] ?: emptyList()) + msg
            map[channel] = list
            _channelMessages.value = map
            schedulePersist()
        }
    }

    // Clear all in-memory state (used for full app shutdown). This is a clean exit, NOT a data wipe, so
    // flush history to disk first and keep the file; load() restores it on the next launch.
    fun clear() {
        persistJob?.cancel()
        persistNow()
        synchronized(this) {
            seenMessageIds.clear()
            seenPublicMessageKeys.clear()
            peerIdsByTransport.clear()
            directPeerIdsByTransport.clear()
            _peers.value = emptyList()
            _publicMessages.value = emptyList()
            _privateMessages.value = emptyMap()
            _channelMessages.value = emptyMap()
        }
    }

    /**
     * Panic wipe: drop in-memory history AND delete the on-disk history file WITHOUT flushing first.
     * Unlike [clear] (a clean exit that persists then keeps the file), this is the emergency "destroy
     * everything" path — before this branch added persistence, panic + process exit genuinely erased chat
     * history; this restores that guarantee. Called from ChatViewModel.panicClearAllData.
     */
    fun wipePersisted() {
        persistJob?.cancel()
        synchronized(this) {
            seenMessageIds.clear()
            seenPublicMessageKeys.clear()
            _publicMessages.value = emptyList()
            _privateMessages.value = emptyMap()
            _channelMessages.value = emptyMap()
        }
        synchronized(writeLock) {
            // Bump the generation FIRST, under the same lock persistNow writes under: an in-flight persist that
            // snapshotted pre-wipe data will see the change and abort instead of recreating the file. A later
            // legitimate message re-snapshots at the new generation and persists normally.
            persistGeneration++
            val ctx = appContext ?: return
            runCatching {
                File(ctx.filesDir, HISTORY_FILE).delete()
                File(ctx.filesDir, "$HISTORY_FILE.tmp").delete()
            }
        }
    }

    private fun publicMessageKey(msg: BitchatMessage): String {
        val sender = msg.senderPeerID ?: msg.sender
        return listOf(
            sender,
            msg.timestamp.time.toString(),
            msg.type.name,
            msg.channel ?: "",
            msg.content
        ).joinToString("\u001F")
    }
}
