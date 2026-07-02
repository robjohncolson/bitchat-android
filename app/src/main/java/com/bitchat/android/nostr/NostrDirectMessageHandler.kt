package com.bitchat.android.nostr

import android.app.Application
import android.util.Log
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.MeshDelegateHandler
import com.bitchat.android.ui.PrivateChatManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class NostrDirectMessageHandler(
    private val application: Application,
    private val state: ChatState,
    private val privateChatManager: PrivateChatManager,
    private val meshDelegateHandler: MeshDelegateHandler,
    private val scope: CoroutineScope,
    private val repo: GeohashRepository,
    private val dataManager: com.bitchat.android.ui.DataManager
) {
    companion object { private const val TAG = "NostrDirectMessageHandler" }

    private val seenStore by lazy { SeenMessageStore.getInstance(application) }

    // Simple event deduplication
    private val processedIds = ArrayDeque<String>()
    private val seen = HashSet<String>()
    private val max = 2000

    private fun dedupe(id: String): Boolean {
        if (seen.contains(id)) return true
        seen.add(id)
        processedIds.addLast(id)
        if (processedIds.size > max) {
            val old = processedIds.removeFirst()
            seen.remove(old)
        }
        return false
    }

    fun onGiftWrap(giftWrap: NostrEvent, geohash: String, identity: NostrIdentity) {
        scope.launch(Dispatchers.Default) {
            try {
                if (dedupe(giftWrap.id)) return@launch

                val messageAge = System.currentTimeMillis() / 1000 - giftWrap.createdAt
                if (messageAge > 173700) return@launch // 48 hours + 15 mins

                val rumor = NostrProtocol.decryptPrivateMessageRumor(giftWrap, identity)
                if (rumor == null) {
                    Log.w(TAG, "Failed to decrypt Nostr message")
                    return@launch
                }

                val content = rumor.content
                val senderPubkey = rumor.pubkey

                // If sender is blocked for geohash contexts, drop any events from this pubkey
                // Applies to both geohash DMs (geohash != "") and account DMs (geohash == "")
                if (dataManager.isGeohashUserBlocked(senderPubkey)) return@launch
                if (!content.startsWith("bitchat1:")) return@launch

                val base64Content = content.removePrefix("bitchat1:")
                val packetData = base64URLDecode(base64Content) ?: return@launch
                val packet = BitchatPacket.fromBinaryData(packetData) ?: return@launch

                if (packet.type != com.bitchat.android.protocol.MessageType.NOISE_ENCRYPTED.value) return@launch

                val noisePayload = NoisePayload.decode(packet.payload) ?: return@launch
                val messageTimestamp = Date(giftWrap.createdAt * 1000L)

                // E2E "family group": a rumor carrying a sealed `bg` group tag threads under ONE shared group
                // key for all members, instead of the per-sender 1:1 alias. A rumor with NO `bg` tag is the
                // exact existing 1:1 path (additive, zero regression — 1:1 DMs are NEVER gated here).
                val groupId = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "bg" }?.get(1)
                val convKey: String
                var groupSenderName: String? = null
                if (groupId != null) {
                    convKey = "nostr_grp_$groupId"
                    // ── TRANSITIVE-TRUST GATE (mandatory) ──────────────────────────────────────────────────
                    // Accept a group message ONLY if the gift-wrap sender is a MUTUAL FAVORITE, or is already a
                    // STORED member of this group (introduced earlier by a mutual favorite). A cold stranger who
                    // knows our npub — or even an existing groupId — is dropped. The registry put() runs ONLY
                    // AFTER accept, so an untrusted sender can never poison the stored member set.
                    val fav = com.bitchat.android.favorites.FavoritesPersistenceService.shared
                    val senderIsMutualFavorite = run {
                        val nk = runCatching { fav.findNoiseKey(senderPubkey) }.getOrNull()
                        nk != null && runCatching { fav.getFavoriteStatus(nk)?.isMutual == true }.getOrDefault(false)
                    }
                    val storedGroup = NostrGroupRegistry.get(convKey)   // STORED members, read BEFORE any put
                    val senderIsInStoredGroup =
                        storedGroup?.members?.any { it.pubkeyHex.equals(senderPubkey, ignoreCase = true) } == true
                    val members = parseGroupMembers(rumor)
                    // Defense-in-depth: the member set MUST hash to the claimed groupId, so membership is
                    // IMMUTABLE PER THREAD — even a trusted member cannot silently expand the roster under a
                    // fixed id (adding anyone forces a new, independently-derivable id = a visibly new thread).
                    val wellFormed = NostrGroupRegistry.computeGroupId(members.map { it.pubkeyHex }) == groupId
                    if (!senderIsMutualFavorite && !senderIsInStoredGroup) {
                        // The sender isn't trusted YET. This is often a legitimate co-member whose message
                        // arrived BEFORE the introducing mutual-favorite's message (relays replay the 48h
                        // backlog newest-first), so dropping it loses it permanently — in-memory dedupe means
                        // it's only re-fetched on the next app restart. If the message is well-formed (its
                        // member set hashes to the claimed groupId) and the sender is in that set, buffer it and
                        // re-evaluate once a TRUSTED member stores the group. A buffered message NEVER
                        // establishes or expands a group: acceptance still requires the sender to appear in a
                        // group that a trusted member stored, so a cold stranger gains nothing.
                        // Only buffer when a member we ALREADY trust (a mutual favorite) is claimed in the set:
                        // that's the sole case where a real introduction — and thus a later drain — is plausible.
                        // A cold stranger's fabricated group (no trusted member) is dropped outright, so it can't
                        // consume buffer slots. (An attacker who knows a mutual favorite's pubkey could still
                        // craft {attacker, knownFavorite}; the per-conversation cap below bounds that to
                        // recoverable delay, and it never drains — the attacker is never in a trusted roster.)
                        val claimsTrustedMember = members.any { m ->
                            runCatching {
                                val nk = fav.findNoiseKey(m.pubkeyHex)
                                nk != null && fav.getFavoriteStatus(nk)?.isMutual == true
                            }.getOrDefault(false)
                        }
                        if (wellFormed && claimsTrustedMember &&
                            members.any { it.pubkeyHex.equals(senderPubkey, ignoreCase = true) }) {
                            bufferPendingGroupMessage(
                                PendingGroupMessage(convKey, senderPubkey, noisePayload, messageTimestamp, identity, giftWrap.id)
                            )
                            Log.d(TAG, "Deferring group msg from not-yet-trusted sender (awaiting introduction by a trusted member)")
                        } else {
                            Log.d(TAG, "Dropping group msg from untrusted sender (not a mutual favorite, not a known member)")
                        }
                        return@launch
                    }
                    if (!wellFormed) {
                        Log.d(TAG, "Dropping group msg: member set does not hash to the claimed groupId")
                        return@launch
                    }
                    val subject = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "subject" }?.get(1)
                    NostrGroupRegistry.put(convKey, groupId, members, subject)
                    groupSenderName = members.firstOrNull { it.pubkeyHex.equals(senderPubkey, ignoreCase = true) }?.name
                    // A trusted member just (re)stored the group — release any buffered messages from co-members
                    // this introduction now vouches for.
                    drainPendingGroupMessages(convKey)
                } else {
                    convKey = "nostr_${senderPubkey.take(16)}"
                    repo.putNostrKeyMapping(convKey, senderPubkey)
                    com.bitchat.android.nostr.GeohashAliasRegistry.put(convKey, senderPubkey)
                }
                if (geohash.isNotEmpty()) {
                    // Remember which geohash this conversation belongs to so we can subscribe on-demand
                    repo.setConversationGeohash(convKey, geohash)
                    GeohashConversationRegistry.set(convKey, geohash)
                }

                // Ensure sender appears in geohash people list even if they haven't posted publicly yet
                if (geohash.isNotEmpty()) {
                    // Cache a best-effort nickname and mark as participant
                    val cached = repo.getCachedNickname(senderPubkey)
                    if (cached == null) {
                        val base = repo.displayNameForNostrPubkeyUI(senderPubkey).substringBefore("#")
                        repo.cacheNickname(senderPubkey, base)
                    }
                    repo.updateParticipant(geohash, senderPubkey, messageTimestamp)
                }

                // For a group, use the per-member display name carried in the sealed `bgm` tag (authenticated by
                // the rumor signer); for 1:1, the existing geohash/nostr display name.
                val senderNickname = groupSenderName ?: repo.displayNameForNostrPubkeyUI(senderPubkey)

                processNoisePayload(noisePayload, convKey, senderNickname, messageTimestamp, senderPubkey, identity, giftWrap.id)

            } catch (e: Exception) {
                Log.e(TAG, "onGiftWrap error: ${e.message}")
            }
        }
    }

    // ── Out-of-order group buffering ────────────────────────────────────────────────────────────────────
    // Holds a group message from a not-yet-trusted (but well-formed) sender until a TRUSTED member stores the
    // group, so a co-member's message that arrives before the introducing member's isn't lost until the next
    // app restart. Bounded FIFO; buffered messages never establish or expand a group.
    private data class PendingGroupMessage(
        val convKey: String,
        val senderPubkey: String,
        val payload: NoisePayload,
        val timestamp: Date,
        val identity: NostrIdentity,
        val giftWrapId: String
    )
    private val pendingGroupLock = Any()
    // convKey -> FIFO of buffered messages. Partitioned PER conversation so a flood of junk in one (bogus)
    // conversation cannot evict another conversation's genuinely-pending messages. Bounded both per-conv and
    // in the number of tracked conversations.
    private val pendingGroupMessages = LinkedHashMap<String, ArrayDeque<PendingGroupMessage>>()
    private val maxPendingPerConv = 20
    private val maxPendingConvs = 50

    private fun bufferPendingGroupMessage(msg: PendingGroupMessage) {
        synchronized(pendingGroupLock) {
            val q = pendingGroupMessages.getOrPut(msg.convKey) { ArrayDeque() }
            q.removeAll { it.giftWrapId == msg.giftWrapId }
            q.addLast(msg)
            while (q.size > maxPendingPerConv) q.removeFirst()
            while (pendingGroupMessages.size > maxPendingConvs) {
                val oldest = pendingGroupMessages.keys.firstOrNull() ?: break
                pendingGroupMessages.remove(oldest)
            }
        }
    }

    /** After a trusted member stores [convKey], process any buffered messages whose sender is now a member. */
    private fun drainPendingGroupMessages(convKey: String) {
        val group = NostrGroupRegistry.get(convKey) ?: return
        val ready = synchronized(pendingGroupLock) {
            val q = pendingGroupMessages.remove(convKey) ?: return
            // Keep only senders the trusted member set vouches for; drop the rest (they claimed this groupId
            // but aren't in the trusted roster, so they were never valid).
            q.filter { p -> group.members.any { m -> m.pubkeyHex.equals(p.senderPubkey, ignoreCase = true) } }
        }
        ready.forEach { p ->
            scope.launch(Dispatchers.Default) {
                try {
                    val name = group.members.firstOrNull { it.pubkeyHex.equals(p.senderPubkey, ignoreCase = true) }?.name
                        ?: repo.displayNameForNostrPubkeyUI(p.senderPubkey)
                    processNoisePayload(p.payload, p.convKey, name, p.timestamp, p.senderPubkey, p.identity, p.giftWrapId)
                } catch (e: Exception) {
                    Log.e(TAG, "drainPendingGroupMessages error: ${e.message}")
                }
            }
        }
    }

    /**
     * Is the user currently viewing [convKey]'s conversation? A 1:1 send rewrites the selected peer from the
     * nostr_<pub16> alias to the favorite's canonical Noise key (or the live mesh peer) via
     * ConversationAliasResolver, so a plain `selected == convKey` check goes stale after the first send —
     * leaving the open thread marked unread and read receipts unsent. Treat any representation of the same
     * identity as "viewing". Groups are never rewritten, so the alias check suffices there.
     */
    private fun isViewingConversation(convKey: String, senderPubkey: String): Boolean {
        val selected = state.getSelectedPrivateChatPeerValue() ?: return false
        if (selected == convKey) return true
        if (convKey.startsWith("nostr_grp_")) return false
        val noiseKey = runCatching {
            com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(senderPubkey)
        }.getOrNull() ?: return false
        val noiseHex = noiseKey.joinToString("") { "%02x".format(it) }
        if (selected.equals(noiseHex, ignoreCase = true)) return true
        // The selected peer may be the live ephemeral mesh peerID for this identity.
        val selectedNoise = runCatching { meshDelegateHandler.getPeerInfo(selected)?.noisePublicKey }.getOrNull()
        return selectedNoise != null && selectedNoise.contentEquals(noiseKey)
    }

    /**
     * Parse a group rumor's member set: prefer the identity-carrying ["bgm", hex, name] tags, fall back to a
     * bare ["p", hex]; dedup by hex with the named entry winning. Used by the trust gate + the groupId integrity
     * check. (Members live in the SEALED kind-14 rumor, never the public gift wrap.)
     */
    private fun parseGroupMembers(rumor: NostrEvent): List<NostrGroupRegistry.GroupMember> {
        val byHex = LinkedHashMap<String, NostrGroupRegistry.GroupMember>()
        rumor.tags.forEach { tag ->
            if (tag.size < 2) return@forEach
            val hex = when (tag[0]) {
                "bgm", "p" -> tag[1].lowercase()
                else -> return@forEach
            }
            val name = if (tag[0] == "bgm" && tag.size >= 3) tag[2].takeIf { it.isNotBlank() } else null
            byHex[hex] = NostrGroupRegistry.GroupMember(hex, name ?: byHex[hex]?.name)
        }
        return byHex.values.toList()
    }

    /**
     * Process a [FAVORITED]/[UNFAVORITED] text DM received over Nostr (off-mesh), mirroring the mesh path so
     * mutual favorites can be established while out of BLE range. The sender's Noise key is resolved from
     * their Nostr pubkey via the existing favorite (favoriting earlier over mesh exchanged the Noise key);
     * if it can't be resolved (npub-only, no prior relationship) we cannot key the relationship and skip.
     */
    private fun handleNostrFavoriteNotification(content: String, senderPubkey: String, senderNickname: String) {
        try {
            val isFavorite = content.startsWith("[FAVORITED]")
            val fav = com.bitchat.android.favorites.FavoritesPersistenceService.shared
            // Resolve the relationship from the CRYPTOGRAPHICALLY AUTHENTICATED gift-wrap sender pubkey. We do
            // NOT (re)bind the npub carried in the message CONTENT: that field is attacker-chosen, and
            // overwriting the stored Nostr key with it would let a peer point their own Noise key's Nostr
            // routing at an arbitrary npub (poisoning the npub<->Noise index / misrouting our future DMs).
            // findNoiseKey already matched THIS relationship on a stored npub that normalizes to senderPubkey,
            // so the authenticated binding is already in place — the rebind only ever changed state on a lie.
            val noiseKey = fav.findNoiseKey(senderPubkey)
            if (noiseKey != null) {
                fav.updatePeerFavoritedUs(noiseKey, isFavorite)
                Log.d(TAG, "Processed Nostr ${if (isFavorite) "[FAVORITED]" else "[UNFAVORITED]"} from $senderNickname → theyFavoritedUs=$isFavorite")
            } else {
                Log.d(TAG, "Nostr ${if (isFavorite) "[FAVORITED]" else "[UNFAVORITED]"} from $senderNickname but no Noise key resolves from their pubkey — cannot record")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nostr favorite-notification handling failed: ${e.message}")
        }
    }

    private suspend fun processNoisePayload(
        payload: NoisePayload,
        convKey: String,
        senderNickname: String,
        timestamp: Date,
        senderPubkey: String,
        recipientIdentity: NostrIdentity,
        giftWrapId: String
    ) {
        when (payload.type) {
            NoisePayloadType.PRIVATE_MESSAGE -> {
                val pm = PrivateMessagePacket.decode(payload.data) ?: return
                // [FAVORITED]/[UNFAVORITED] arrive as text DMs. The mesh path intercepts them
                // (MessageHandler.handleFavoriteNotificationFromMesh) and records theyFavoritedUs, but the
                // Nostr path historically fell through and showed them as chat — so favoriting while OFF-MESH
                // never made the pair mutual, which silently broke the Nostr off-mesh broadcast (helper gate
                // is mutual-favorites-only). Process it here too, then stop (do NOT render as a message).
                if (pm.content.startsWith("[FAVORITED]") || pm.content.startsWith("[UNFAVORITED]")) {
                    handleNostrFavoriteNotification(pm.content, senderPubkey, senderNickname)
                    return
                }
                val existingMessages = state.getPrivateChatsValue()[convKey] ?: emptyList()
                if (existingMessages.any { it.id == pm.messageID }) return

                val isGroup = convKey.startsWith("nostr_grp_")

                val message = BitchatMessage(
                    id = pm.messageID,
                    sender = senderNickname,
                    content = pm.content,
                    timestamp = timestamp,
                    isRelay = false,
                    isPrivate = true,
                    recipientNickname = state.getNicknameValue(),
                    senderPeerID = convKey,
                    deliveryStatus = DeliveryStatus.Delivered(to = state.getNicknameValue() ?: "Unknown", at = Date()),
                    // Group bubbles need the individual sender's account pubkey for tap-to-add (Increment 2c).
                    senderNostrPubkey = if (isGroup) senderPubkey else null
                )

                val isViewing = isViewingConversation(convKey, senderPubkey)
                val suppressUnread = seenStore.hasRead(pm.messageID)

                withContext(Dispatchers.Main) {
                    privateChatManager.handleIncomingPrivateMessage(message, suppressUnread)
                }

                // Surface a system notification. Nostr deliveries were previously SILENT (only the mesh path
                // notifies inline), so a family member on the Simple profile — which is Nostr-centric — only
                // ever learned of an incoming message by opening the thread. Skip if already read (a re-fetch
                // after restart) or if this exact thread is already open; NotificationManager applies the same
                // "currently-viewing" gate as a backstop.
                if (!suppressUnread && !isViewing) {
                    val groupSubject = if (isGroup) NostrGroupRegistry.get(convKey)?.subject else null
                    meshDelegateHandler.notifyIncomingNostrMessage(
                        convKey = convKey,
                        senderNickname = senderNickname,
                        message = message,
                        groupSubject = groupSubject
                    )
                }

                // Per-author delivery/read acks make no sense for a group (the ack reaches only the one author,
                // not the set), so suppress them for group keys. Per-member group receipts are future work.
                if (!isGroup && !seenStore.hasDelivered(pm.messageID)) {
                    val nostrTransport = NostrTransport.getInstance(application)
                    nostrTransport.sendDeliveryAckGeohash(pm.messageID, senderPubkey, recipientIdentity)
                    seenStore.markDelivered(pm.messageID)
                }

                if (!isGroup && isViewing && !suppressUnread) {
                    val nostrTransport = NostrTransport.getInstance(application)
                    nostrTransport.sendReadReceiptGeohash(pm.messageID, senderPubkey, recipientIdentity)
                    seenStore.markRead(pm.messageID)
                }
            }
            NoisePayloadType.DELIVERED -> {
                val messageId = String(payload.data, Charsets.UTF_8)
                withContext(Dispatchers.Main) {
                    meshDelegateHandler.didReceiveDeliveryAck(messageId, convKey)
                }
            }
            NoisePayloadType.READ_RECEIPT -> {
                val messageId = String(payload.data, Charsets.UTF_8)
                withContext(Dispatchers.Main) {
                    meshDelegateHandler.didReceiveReadReceipt(messageId, convKey)
                }
            }
            NoisePayloadType.FILE_TRANSFER -> {
                // Dedup by gift-wrap id (persisted). The account subscription re-fetches the last 48h on every
                // launch; unlike PRIVATE_MESSAGE (deduped by messageID vs restored history) a file mints a fresh
                // UUID each time, so without this it re-saves the file, re-appends a bubble, and — since the
                // notification hook was added — re-notifies on every restart for 48h.
                val fileDedupKey = "filegw:$giftWrapId"
                if (seenStore.hasDelivered(fileDedupKey)) return
                // Properly handle encrypted file transfer
                val file = BitchatFilePacket.decode(payload.data)
                if (file != null) {
                    val uniqueMsgId = java.util.UUID.randomUUID().toString().uppercase()
                    val savedPath = com.bitchat.android.features.file.FileUtils.saveIncomingFile(application, file)
                    val message = BitchatMessage(
                        id = uniqueMsgId,
                        sender = senderNickname,
                        content = savedPath,
                        type = com.bitchat.android.features.file.FileUtils.messageTypeForMime(file.mimeType),
                        timestamp = timestamp,
                        isRelay = false,
                        isPrivate = true,
                        recipientNickname = state.getNicknameValue(),
                        senderPeerID = convKey
                    )
                    Log.d(TAG, "📄 Saved Nostr encrypted incoming file to $savedPath (msgId=$uniqueMsgId)")
                    seenStore.markDelivered(fileDedupKey)
                    withContext(Dispatchers.Main) {
                        privateChatManager.handleIncomingPrivateMessage(message, suppressUnread = false)
                    }
                    // Same silent-delivery fix as PRIVATE_MESSAGE: notify unless this thread is already open.
                    if (!isViewingConversation(convKey, senderPubkey)) {
                        val groupSubject = if (convKey.startsWith("nostr_grp_")) NostrGroupRegistry.get(convKey)?.subject else null
                        meshDelegateHandler.notifyIncomingNostrMessage(
                            convKey = convKey,
                            senderNickname = senderNickname,
                            message = message,
                            groupSubject = groupSubject
                        )
                    }
                } else {
                    Log.w(TAG, "⚠️ Failed to decode Nostr file transfer from $convKey")
                }
            }
            NoisePayloadType.VERIFY_CHALLENGE,
            NoisePayloadType.VERIFY_RESPONSE -> Unit // Ignore verification payloads in Nostr direct messages
            NoisePayloadType.PAYMENT_BROADCAST_REQUEST -> {
                // 3b.1 Nostr fallback (HELPER side): an off-mesh sender relayed a signed tx for us to
                // broadcast. The global Nostr transport is favorites-keyed by nature (a sender needs our
                // npub, exchanged via favoriting), so serve ONLY mutual favorites and DROP everyone else
                // SILENTLY. Replying to / doing work for an arbitrary party would leak our helper config
                // and online status and allow un-rate-limited reply + favorites-scan amplification over the
                // open Nostr network (the decline gates run before the rate-limit gate). handleRequest then
                // applies its full gate stack (per-network opt-in, network match, rate limits, txid check);
                // it holds no keys and never broadcasts a tx that fails its gates.
                val fromNoiseKey = runCatching {
                    com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(senderPubkey)
                }.getOrNull()
                val mutualFavorite = fromNoiseKey != null && runCatching {
                    com.bitchat.android.favorites.FavoritesPersistenceService.shared
                        .getFavoriteStatus(fromNoiseKey)?.isMutual == true
                }.getOrDefault(false)
                if (!mutualFavorite) {
                    Log.d(TAG, "Dropping Nostr broadcast REQUEST from non-mutual-favorite $convKey")
                } else {
                    Log.d(TAG, "💸 Payment broadcast REQUEST via Nostr from $convKey")
                    val fromNoiseKeyHex = fromNoiseKey!!.joinToString("") { "%02x".format(it) }
                    val helper = com.bitchat.android.features.dogecoin.BroadcastHelperService.getInstance(application)
                    val resultBytes = helper.handleRequest(
                        fromPeerID = convKey,
                        fromNoiseKeyHex = fromNoiseKeyHex,
                        requestPayload = payload.data,
                        nowMs = System.currentTimeMillis()
                    )
                    if (resultBytes != null) {
                        NostrTransport.getInstance(application)
                            .sendPaymentBroadcastResultToPubkey(resultBytes, senderPubkey, recipientIdentity)
                    }
                }
            }
            NoisePayloadType.PAYMENT_BROADCAST_RESULT -> {
                // 3b.1 Nostr fallback (SENDER side): a helper returned a broadcast result over Nostr.
                // Canonicalize the corroboration source id to the helper's STABLE Noise key (resolved from
                // their Nostr pubkey via favorites) and require a MUTUAL favorite — SYMMETRIC with the REQUEST
                // arm above (which only SERVES mutual favorites). This is the money-safety crux: a free-to-mint
                // Nostr keypair never resolves, AND a bare npub<->Noise binding is cheap to seed (a one-time
                // [FAVORITED] makes findNoiseKey resolve with isFavorite=false), so counting non-mutual keys
                // would let a pre-positioned helper mint a second identity and forge the two-helper Confirmed.
                // Mutual status requires the user's OWN favoriting, which an attacker cannot self-grant. The
                // coordinator's scarce-positive gate enforces the same rule centrally; this is defense-in-depth.
                val helperNoiseKey = runCatching {
                    com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(senderPubkey)
                }.getOrNull()
                val helperIsMutual = helperNoiseKey != null && runCatching {
                    com.bitchat.android.favorites.FavoritesPersistenceService.shared
                        .getFavoriteStatus(helperNoiseKey)?.isMutual == true
                }.getOrDefault(false)
                if (!helperIsMutual) {
                    Log.d(TAG, "Dropping Nostr broadcast RESULT from non-mutual-favorite $convKey")
                } else {
                    val helperNoiseKeyHex = helperNoiseKey!!.joinToString("") { "%02x".format(it) }
                    Log.d(TAG, "💸 Payment broadcast RESULT via Nostr from $convKey")
                    com.bitchat.android.features.dogecoin.PaymentBroadcastResultRouter
                        .deliver(helperNoiseKeyHex, payload.data)
                }
            }
        }
    }

    private fun base64URLDecode(input: String): ByteArray? {
        return try {
            val padded = input.replace("-", "+")
                .replace("_", "/")
                .let { str ->
                    val padding = (4 - str.length % 4) % 4
                    str + "=".repeat(padding)
                }
            android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64url: ${e.message}")
            null
        }
    }
}
