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

                val decryptResult = NostrProtocol.decryptPrivateMessage(giftWrap, identity)
                if (decryptResult == null) {
                    Log.w(TAG, "Failed to decrypt Nostr message")
                    return@launch
                }

                val (content, senderPubkey, rumorTimestamp) = decryptResult

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
                val convKey = "nostr_${senderPubkey.take(16)}"
                repo.putNostrKeyMapping(convKey, senderPubkey)
                com.bitchat.android.nostr.GeohashAliasRegistry.put(convKey, senderPubkey)
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

                val senderNickname = repo.displayNameForNostrPubkeyUI(senderPubkey)

                processNoisePayload(noisePayload, convKey, senderNickname, messageTimestamp, senderPubkey, identity)

            } catch (e: Exception) {
                Log.e(TAG, "onGiftWrap error: ${e.message}")
            }
        }
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
        recipientIdentity: NostrIdentity
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

                val message = BitchatMessage(
                    id = pm.messageID,
                    sender = senderNickname,
                    content = pm.content,
                    timestamp = timestamp,
                    isRelay = false,
                    isPrivate = true,
                    recipientNickname = state.getNicknameValue(),
                    senderPeerID = convKey,
                    deliveryStatus = DeliveryStatus.Delivered(to = state.getNicknameValue() ?: "Unknown", at = Date())
                )

                val isViewing = state.getSelectedPrivateChatPeerValue() == convKey
                val suppressUnread = seenStore.hasRead(pm.messageID)

                withContext(Dispatchers.Main) {
                    privateChatManager.handleIncomingPrivateMessage(message, suppressUnread)
                }

                if (!seenStore.hasDelivered(pm.messageID)) {
                    val nostrTransport = NostrTransport.getInstance(application)
                    nostrTransport.sendDeliveryAckGeohash(pm.messageID, senderPubkey, recipientIdentity)
                    seenStore.markDelivered(pm.messageID)
                }

                if (isViewing && !suppressUnread) {
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
                    withContext(Dispatchers.Main) {
                        privateChatManager.handleIncomingPrivateMessage(message, suppressUnread = false)
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
