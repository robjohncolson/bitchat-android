package com.bitchat.android.profile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.favorites.FavoritesChangeListener
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.features.dogecoin.DogepaidPaymentContext
import com.bitchat.android.features.dogecoin.DogepaidReceipt
import com.bitchat.android.features.dogecoin.DogepaidReceiptCheckResult
import com.bitchat.android.features.dogecoin.DogecoinNetwork
import com.bitchat.android.features.dogecoin.DogecoinPaymentRequest
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.TorMode
import com.bitchat.android.net.TorPreferenceManager
import com.bitchat.android.R
import com.bitchat.android.nostr.Bech32
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.nostr.KnownNpubStore
import com.bitchat.android.nostr.NostrGroupRegistry
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.profile.AppProfile
import com.bitchat.android.profile.ContactDisplayName
import com.bitchat.android.profile.ProfilePreferenceManager
import com.bitchat.android.profile.ProfileSetupCoordinator
import com.bitchat.android.profile.SimpleChatActivity
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.DeliveryStatusIcon
import com.bitchat.android.ui.StatefulDogepaidReceiptCard
import com.bitchat.android.ui.DogecoinUri
import com.bitchat.android.ui.RequestDogeDialog
import com.bitchat.android.ui.canonicalDogepaidReceiptMessageIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.mutableIntStateOf

/**
 * LINE-style surface for the SIMPLE ("Family") profile: a clean chat list AND a clean conversation, both
 * rendered natively here over the SAME ChatViewModel — no fall-through to the terminal UI. Power UI is
 * untouched; this is presentation only.
 */
private sealed interface SimpleTarget {
    /**
     * 1:1 contact. [name] is not the source of truth for the title — [resolvedContactTitle] re-derives
     * from favorites/KnownNpub each composition so rename + activity recreation never show "anon".
     */
    data class Contact(
        val peerID: String, val name: String, val noiseHex: String?, val pubkeyHex: String?
    ) : SimpleTarget
    data class Group(
        val convKey: String, val subject: String?, val members: List<NostrGroupRegistry.GroupMember>
    ) : SimpleTarget
}

/** Display-time title for a Simple 1:1 contact (spec R-A1 / R-A3). */
@Composable
private fun resolvedContactTitle(
    convKey: String,
    noiseHex: String?,
    pubkeyHex: String?,
    messageSenderFallback: String?,
    nameEpoch: Int // recompose when favorites/known labels change
): String {
    val familyFallback = stringResource(R.string.simple_family_fallback)
    // nameEpoch read so Compose tracks renames
    @Suppress("UNUSED_EXPRESSION")
    nameEpoch
    return ContactDisplayName.resolveLive(
        identity = ContactDisplayName.Identity(
            convKey = convKey,
            noiseKeyHex = noiseHex,
            nostrPubkeyHex = pubkeyHex
        ),
        messageSenderFallback = messageSenderFallback,
        familyFallback = familyFallback
    ).display
}

@Composable
fun SimpleModeScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    // The public geohash "family room" is removed (it leaked stranger messages — geohash chat is public).
    // Ensure Simple never sits on a public geohash channel, INCLUDING existing installs that pinned one:
    // fall back to mesh (Simple talks over private 1:1 / E2E group DMs, never a public channel).
    LaunchedEffect(Unit) {
        val mgr = com.bitchat.android.geohash.LocationChannelManager.getInstance(context)
        if (mgr.selectedChannel.value is com.bitchat.android.geohash.ChannelID.Location) {
            mgr.select(com.bitchat.android.geohash.ChannelID.Mesh)
        }
    }
    var target by remember { mutableStateOf<SimpleTarget?>(null) }
    // Bumps when a favorite/KnownNpub label changes so open titles re-resolve without leaving the thread.
    var nameEpoch by remember { mutableIntStateOf(0) }
    // The open conversation's key, kept in a Saveable so it survives Activity recreation (dark-mode / locale /
    // font-size / split-screen change). `target` is not Saveable, so after recreation it resets to null while
    // the ViewModel still thinks the thread is open — which would suppress that thread's notifications and fire
    // false read receipts. We restore `target` from this key below.
    var savedConvKey by rememberSaveable { mutableStateOf<String?>(null) }

    // Live rename recompose for any open conversation (favorites listener is also on SimpleHome).
    DisposableEffect(Unit) {
        val listener = object : FavoritesChangeListener {
            override fun onFavoriteChanged(noiseKeyHex: String) { nameEpoch++ }
            override fun onAllCleared() { nameEpoch++ }
        }
        runCatching { FavoritesPersistenceService.shared.addListener(listener) }
        onDispose { runCatching { FavoritesPersistenceService.shared.removeListener(listener) } }
    }

    // Close the open conversation: end the private-chat context AND clear the durable key, so a later
    // recreation doesn't reopen a thread the user explicitly backed out of.
    val closeConversation: () -> Unit = {
        viewModel.endPrivateChat(); target = null; savedConvKey = null
    }

    // Open (or re-open) a group thread by conv-key: make it the active private-chat context for sends and
    // resolve its subject + members from the registry.
    val openGroup: (String) -> Unit = { convKey ->
        val g = NostrGroupRegistry.get(convKey)
        viewModel.startPrivateChat(convKey)
        savedConvKey = convKey
        target = SimpleTarget.Group(convKey, g?.subject, g?.members ?: emptyList())
    }

    // Open (or re-open) a 1:1 contact thread by conv-key, resolving name + keys from what we already know.
    // Mirrors onOpenContact but starts from the convKey (the notification deep-link has only that). The full
    // pubkey comes from GeohashAliasRegistry (populated when the message that raised the notification arrived);
    // if it's missing (e.g. a cold start after the OS killed the app), fall back to the last incoming sender
    // name and still open the thread — its history persists under the convKey.
    val openContactByConvKey: (String) -> Unit = { convKey ->
        val hex = GeohashAliasRegistry.get(convKey)
        // Prefer favorites Noise key even when alias hex is missing (cold start).
        val noiseHex = hex?.let { h ->
            FavoritesPersistenceService.shared.findNoiseKey(h)?.joinToString("") { b -> "%02x".format(b) }
        }
        val fallbackSender = viewModel.privateChats.value[convKey]
            ?.lastOrNull { it.senderPeerID == convKey || (!it.senderPeerID.isNullOrBlank() && it.senderPeerID != viewModel.myPeerID) }
            ?.sender
        val name = ContactDisplayName.resolveLive(
            identity = ContactDisplayName.Identity(
                convKey = convKey,
                noiseKeyHex = noiseHex,
                nostrPubkeyHex = hex
            ),
            messageSenderFallback = fallbackSender,
            familyFallback = context.getString(R.string.simple_family_fallback)
        ).display
        if (hex != null) {
            viewModel.startGeohashDM(hex)
            GeohashAliasRegistry.put(convKey, hex) // enable account-DM routing so the first reply isn't queued forever
        }
        viewModel.startPrivateChat(convKey)
        savedConvKey = convKey
        target = SimpleTarget.Contact(convKey, name, noiseHex, hex)
    }

    // Deep-link from a notification tap (see MainActivity.handleNotificationIntent → requestOpenConversation):
    // navigate to the requested conversation, then consume the one-shot signal.
    LaunchedEffect(Unit) {
        viewModel.pendingOpenConversation.collect { convKey ->
            if (convKey != null) {
                if (convKey.startsWith("nostr_grp_")) openGroup(convKey) else openContactByConvKey(convKey)
                viewModel.consumePendingOpenConversation()
            }
        }
    }

    // Restore the open conversation after Activity recreation: `target` reset to null but savedConvKey
    // (Saveable) survived, so rebuild the thread from it instead of leaving the ViewModel "viewing" a thread
    // the UI has left. Runs only when nothing is open and the user hadn't explicitly backed out (savedConvKey
    // is cleared by closeConversation).
    LaunchedEffect(Unit) {
        if (target == null) {
            savedConvKey?.let { key ->
                if (key.startsWith("nostr_grp_")) openGroup(key) else openContactByConvKey(key)
            }
        }
    }

    when (val t = target) {
        null -> LineTheme {
            SimpleHome(
                viewModel = viewModel,
                onNameChanged = { nameEpoch++ },
                onOpenContact = { npub, name, noiseHex ->
                    val hex = nostrPubkeyToHex(npub)
                    if (hex != null) {
                        val convKey = "nostr_${hex.take(16)}"
                        viewModel.startGeohashDM(hex)          // register the conv-key -> pubkey mapping + subscription
                        GeohashAliasRegistry.put(convKey, hex) // route the FIRST send as an account DM (else it queues forever)
                        viewModel.startPrivateChat(convKey)    // make it the active private-chat context for sends
                        savedConvKey = convKey
                        target = SimpleTarget.Contact(convKey, name, noiseHex, hex)
                    }
                },
                onOpenGroup = openGroup
            )
        }
        is SimpleTarget.Contact -> {
            BackHandler { closeConversation() }
            LineTheme {
                val liveTitle = resolvedContactTitle(
                    convKey = t.peerID,
                    noiseHex = t.noiseHex,
                    pubkeyHex = t.pubkeyHex,
                    messageSenderFallback = t.name,
                    nameEpoch = nameEpoch
                )
                SimpleConversation(
                    viewModel = viewModel,
                    title = liveTitle,
                    isGroup = false,
                    peerID = t.peerID,
                    noiseHex = t.noiseHex,
                    contactPubkeyHex = t.pubkeyHex,
                    onBack = closeConversation,
                    onOpenGroup = openGroup
                )
            }
        }
        is SimpleTarget.Group -> {
            BackHandler { closeConversation() }
            LineTheme {
                SimpleConversation(
                    viewModel = viewModel,
                    title = t.subject ?: stringResource(R.string.simple_family_group),
                    isGroup = true,
                    peerID = t.convKey,
                    noiseHex = null,
                    contactPubkeyHex = null,
                    onBack = closeConversation,
                    onOpenGroup = openGroup
                )
            }
        }
    }
}

/**
 * The stable mesh peerID for a contact = first 16 hex chars of SHA-256(noise public key), matching
 * BluetoothMeshService.myPeerID's derivation. Incoming BLE private messages are filed under this key.
 */
private fun meshPeerIdForNoiseHex(noiseHex: String): String? = runCatching {
    val bytes = noiseHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }.take(16)
}.getOrNull()

/** Decode a stored Nostr pubkey (npub bech32 or 64-char hex) to 64-char hex, or null if not resolvable. */
private fun nostrPubkeyToHex(value: String?): String? {
    if (value.isNullOrBlank()) return null
    return if (value.startsWith("npub")) {
        runCatching { Bech32.decode(value).second.joinToString("") { "%02x".format(it) } }
            .getOrNull()?.takeIf { it.length == 64 }
    } else {
        value.lowercase().takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }
}

/** One Simple-home list entry (group / contact / npub-only), unified so the list can sort by activity. */
private class SimpleHomeRow(
    val stableKey: String,
    val title: String,
    val avatarInitial: String?,
    val avatarColor: Color,
    val fallbackSubtitle: String,
    val activity: SimpleChatActivity.Activity,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)?
)

/** Target for the rename dialog (favorite Noise key and/or KnownNpub hex). */
private data class RenameTarget(
    val noiseKey: ByteArray?,
    val nostrHex: String?,
    val currentName: String
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SimpleHome(
    viewModel: ChatViewModel,
    onNameChanged: () -> Unit = {},
    onOpenContact: (npub: String?, name: String, noiseHex: String?) -> Unit,
    onOpenGroup: (convKey: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableStateOf(0) }
    var renameTarget by remember { mutableStateOf<RenameTarget?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf(false) }

    // Keep the family list in sync with the favorites store. A relative favoriting us BACK (e.g. over
    // Nostr) flips a relationship to mutual asynchronously, so observe the change listener and recompute
    // instead of only refreshing right after an in-app "Add family". The callback can fire on any thread,
    // so hop to the composition (main) scope before touching Compose state.
    DisposableEffect(Unit) {
        val listener = object : FavoritesChangeListener {
            override fun onFavoriteChanged(noiseKeyHex: String) { scope.launch { refreshKey++ } }
            override fun onAllCleared() { scope.launch { refreshKey++ } }
        }
        FavoritesPersistenceService.shared.addListener(listener)
        onDispose { FavoritesPersistenceService.shared.removeListener(listener) }
    }

    val contacts = remember(refreshKey) { FavoritesPersistenceService.shared.getMutualFavorites() }
    val privateChats by viewModel.privateChats.collectAsState()
    // For unread counting: the local nickname (own messages are never unread) + the in-memory read check.
    // SeenMessageStore.hasRead is a synchronized Set.contains (no I/O), so per-message counting here does
    // NOT reintroduce the main-thread markRead storm the P0 fix removed — nothing is ever marked read here.
    val myNick by viewModel.nickname.collectAsState()
    val seenStore = remember { com.bitchat.android.services.SeenMessageStore.getInstance(context) }
    // The user's E2E family groups, surfaced from the registry. Keyed on privateChats so a freshly-received
    // group appears once its first message lands (the receive path registers it AND appends to privateChats).
    val groups = remember(refreshKey, privateChats) {
        NostrGroupRegistry.snapshot().filterValues { it.members.size >= 2 }.entries.toList()
    }
    // npub-only contacts added by tapping a group member's name (a real mutual favorite supersedes them).
    val known = remember(refreshKey) {
        KnownNpubStore.snapshot().entries
            .filter { FavoritesPersistenceService.shared.findNoiseKey(it.key) == null }
            .toList()
    }
    var showSettings by remember { mutableStateOf(false) }
    var showAddFamily by remember { mutableStateOf(false) }
    var showWallet by remember { mutableStateOf(false) }
    // Opt-in family wallet: a direct home entry point appears ONLY once the user has enabled the wallet in
    // Settings, so the default curated path never surfaces money/node setup to someone who didn't ask for it.
    val walletEnabled by ProfilePreferenceManager.walletEnabledFlow.collectAsState()

    // Smart Tor "punch-through". The banner appears only after Nostr relays have actually been unreachable
    // for a sustained window (see rememberConnectionTroubleMode) and its message adapts to WHY: offline (Tor
    // can't help) vs. relays blocked (offer Tor) vs. already on Tor and still failing (offer to turn it off).
    val troubleMode = rememberConnectionTroubleMode()

    if (showAddFamily) {
        BackHandler { showAddFamily = false }
        AddFamilyScreen(
            viewModel = viewModel,
            onClose = { showAddFamily = false },
            onAdded = { refreshKey++ }
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.simple_chats_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                // Direct wallet entry: one tap from home to the (locked) Dogecoin wallet, shown only when the
                // opt-in wallet is enabled. The Settings → Open wallet path still works too.
                if (walletEnabled) {
                    IconButton(onClick = { showWallet = true }) {
                        Icon(
                            Icons.Filled.AccountBalanceWallet,
                            contentDescription = stringResource(R.string.simple_cd_wallet),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { showAddFamily = true }) {
                    Icon(
                        Icons.Filled.PersonAdd,
                        contentDescription = stringResource(R.string.simple_cd_add_family),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.simple_cd_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (troubleMode != null) {
            ConnectionTroubleBanner(
                mode = troubleMode,
                onTurnOnTor = { applyTorMode(context, scope, on = true) },
                onTurnOffTor = { applyTorMode(context, scope, on = false) }
            )
        }

        // Strings/colors needed while BUILDING the row model must be read in composable scope, not inside
        // the remember below.
        val familyFallback = stringResource(R.string.simple_family_fallback)
        val groupFallback = stringResource(R.string.simple_family_group)
        val tapToChat = stringResource(R.string.simple_tap_to_chat)
        val noInternetContact = stringResource(R.string.simple_no_internet_contact)
        val unverifiedContact = stringResource(R.string.simple_added_from_group_unverified)
        val groupColor = MaterialTheme.colorScheme.primary

        // One unified, activity-ordered list model (groups + verified contacts + npub-only), derived purely
        // from already-collected state. Recomputes when messages arrive (privateChats), a favorite/known
        // label changes (refreshKey), or the nickname changes — never on unrelated recomposition.
        val homeRows = remember(refreshKey, privateChats, contacts, groups, known, myNick) {
            val rows = ArrayList<SimpleHomeRow>(groups.size + contacts.size + known.size)
            groups.forEach { entry ->
                val keys = setOf(entry.key)
                rows.add(
                    SimpleHomeRow(
                        stableKey = "grp_" + entry.key,
                        title = entry.value.subject ?: groupFallback,
                        avatarInitial = null,
                        avatarColor = groupColor,
                        fallbackSubtitle = groupFallback,
                        activity = SimpleChatActivity.compute(keys, privateChats, myNick, seenStore::hasRead),
                        onClick = { onOpenGroup(entry.key) },
                        onLongClick = null
                    )
                )
            }
            contacts.forEach { c ->
                val noiseHex = c.peerNoisePublicKey.joinToString("") { b -> "%02x".format(b) }
                val pubHex = nostrPubkeyToHex(c.peerNostrPublicKey)
                val name = ContactDisplayName.resolveLive(
                    identity = ContactDisplayName.Identity(
                        noiseKeyHex = noiseHex,
                        npub = c.peerNostrPublicKey,
                        nostrPubkeyHex = pubHex
                    ),
                    messageSenderFallback = c.peerNickname,
                    familyFallback = familyFallback
                ).display
                // Union of every key this contact's messages can be filed under (mirrors SimpleConversation).
                val keys = setOfNotNull(
                    noiseHex,
                    meshPeerIdForNoiseHex(noiseHex),
                    pubHex?.let { "nostr_${it.take(16)}" }
                )
                rows.add(
                    SimpleHomeRow(
                        stableKey = noiseHex,
                        title = name,
                        avatarInitial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        avatarColor = Color(0xFF9AA3AB),
                        fallbackSubtitle = if (c.peerNostrPublicKey != null) tapToChat else noInternetContact,
                        activity = SimpleChatActivity.compute(keys, privateChats, myNick, seenStore::hasRead),
                        onClick = { if (c.peerNostrPublicKey != null) onOpenContact(c.peerNostrPublicKey, name, noiseHex) },
                        onLongClick = {
                            renameInput = name
                            renameError = false
                            renameTarget = RenameTarget(
                                noiseKey = c.peerNoisePublicKey,
                                nostrHex = pubHex,
                                currentName = name
                            )
                        }
                    )
                )
            }
            known.forEach { entry ->
                // Tap-added-from-a-group contacts are NOT verified favorites — their display name is whatever a
                // group message asserted. The subtitle flags that so a non-technical user can tell them apart
                // from a family member added by scanning the signed QR (a verified mutual favorite).
                val display = entry.value.ifBlank { familyFallback }
                val keys = setOf("nostr_${entry.key.take(16)}")
                rows.add(
                    SimpleHomeRow(
                        stableKey = "npub_" + entry.key,
                        title = display,
                        avatarInitial = display.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        avatarColor = Color(0xFFC0A9B0),   // muted mauve, distinct from the verified-favorite gray
                        fallbackSubtitle = unverifiedContact,
                        activity = SimpleChatActivity.compute(keys, privateChats, myNick, seenStore::hasRead),
                        onClick = { onOpenContact(entry.key, display, null) },
                        onLongClick = {
                            renameInput = display
                            renameError = false
                            renameTarget = RenameTarget(noiseKey = null, nostrHex = entry.key, currentName = display)
                        }
                    )
                )
            }
            SimpleChatActivity.sortByRecency(rows) { it.activity.lastActivityMs }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (homeRows.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 56.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.simple_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.simple_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            items(homeRows, key = { it.stableKey }) { row ->
                val lastMessage = row.activity.lastMessage
                val preview = lastMessage?.let { simpleChatPreview(it, context) }
                val timeText = lastMessage?.let { simpleChatListTime(it.timestamp, context) }
                ChatListRow(
                    title = row.title,
                    subtitle = preview ?: row.fallbackSubtitle,
                    avatarInitial = row.avatarInitial,
                    avatarColor = row.avatarColor,
                    onClick = row.onClick,
                    onLongClick = row.onLongClick,
                    timeText = timeText,
                    unreadCount = row.activity.unreadCount
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.simple_rename_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.simple_rename_body))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = {
                            renameInput = it.take(ContactDisplayName.MAX_PET_NAME_LEN)
                            renameError = false
                        },
                        singleLine = true,
                        isError = renameError,
                        label = { Text(stringResource(R.string.simple_name_label)) }
                    )
                    if (renameError) {
                        Text(
                            stringResource(R.string.simple_rename_invalid),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cleaned = ContactDisplayName.sanitizePetNameInput(renameInput)
                    if (cleaned == null) {
                        renameError = true
                        return@TextButton
                    }
                    target.noiseKey?.let { key ->
                        FavoritesPersistenceService.shared.renameFavoriteNickname(key, cleaned)
                    }
                    target.nostrHex?.let { hex ->
                        // Keep KnownNpub label in sync when present (npub-only or dual)
                        if (KnownNpubStore.contains(hex) || target.noiseKey == null) {
                            KnownNpubStore.put(hex, cleaned)
                        }
                    }
                    refreshKey++
                    onNameChanged() // recompose open-thread title if any
                    renameTarget = null
                }) { Text(stringResource(R.string.simple_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.simple_cancel))
                }
            }
        )
    }

    if (showSettings) {
        SimpleSettingsSheet(
            viewModel = viewModel,
            onDismiss = { showSettings = false },
            onOpenWallet = { showWallet = true }
        )
    }
    if (showWallet) {
        // The opt-in family wallet: the existing wallet sheet LOCKED (no settings gear -> no network/advanced
        // reachable); money-path gates stay intact.
        com.bitchat.android.features.dogecoin.DogecoinWalletSheet(
            isPresented = true,
            onDismiss = { showWallet = false },
            onShareToChat = {},
            isSimpleProfile = true
        )
    }
}

@Composable
private fun SimpleConversation(
    viewModel: ChatViewModel,
    title: String,
    isGroup: Boolean,
    peerID: String?,
    noiseHex: String?,
    contactPubkeyHex: String?,
    onBack: () -> Unit,
    onOpenGroup: (convKey: String) -> Unit
) {
    val nickname by viewModel.nickname.collectAsState()
    val privateChats by viewModel.privateChats.collectAsState()
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.collectAsState()
    val walletEnabled by ProfilePreferenceManager.walletEnabledFlow.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Surface the same connection-trouble banner inside the conversation (not just the chat list), so a
    // message composed while relays are down carries visible context instead of looking delivered.
    val troubleMode = rememberConnectionTroubleMode()
    // The Simple wallet is locked to ONE network. A payment request for a DIFFERENT network must not be
    // payable here: tapping Pay opens the sheet with paymentRequest, whose prefill calls switchNetwork() —
    // so an untrusted cross-network `dogecoin:` chat message could otherwise silently move a locked
    // (e.g. testnet play-money) wallet to mainnet. Gate the Pay button on a network match below.
    val walletNetwork = remember(context) {
        com.bitchat.android.features.dogecoin.DogecoinWalletRepository(context).loadSelectedNetwork()
    }
    var showRequestDialog by remember { mutableStateOf(false) }
    var payRequest by remember { mutableStateOf<DogecoinPaymentRequest?>(null) }
    var payReceiptContext by remember { mutableStateOf<DogepaidPaymentContext?>(null) }
    var showAddPeople by remember { mutableStateOf(false) }
    var tapAddMember by remember { mutableStateOf<Pair<String, String>?>(null) }  // (accountPubkeyHex, name)

    // A contact's DM messages can be filed under several keys depending on transport + the app's
    // opportunistic consolidation: the temporary nostr_<pub16> alias this screen opened with, the canonical
    // 64-hex Noise key (offline favorite), and — when the contact is connected over BLE mesh — an ephemeral
    // mesh peerID (the app's current "canonical" peer, exposed as selectedPrivateChatPeer). Union all of
    // them so messages show regardless of transport, deduped by id and time-sorted. Memoized so it does NOT
    // recompute on every keystroke / unrelated recomposition.
    val messages: List<BitchatMessage> = remember(
        privateChats, peerID, noiseHex, selectedPrivatePeer
    ) {
        if (peerID == null) emptyList()
        else {
            val keys = buildList {
                add(peerID)
                noiseHex?.let { hx ->
                    add(hx)
                    // BLE-delivered private messages file under the sender's mesh peerID (first 16 hex of
                    // SHA-256(noise key)), which is neither the nostr_ alias nor the 64-hex noiseHex — include
                    // it so same-house mesh messages (and our own mesh-sent copies) also show in the thread.
                    meshPeerIdForNoiseHex(hx)?.let { add(it) }
                }
                selectedPrivatePeer?.let { add(it) }
            }
            keys.flatMap { privateChats[it] ?: emptyList() }
                .distinctBy { it.id }
                .sortedBy { it.timestamp }
        }
    }
    val canonicalDogepaidReceiptIds = remember(messages) {
        canonicalDogepaidReceiptMessageIds(messages)
    }

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) runCatching { listState.animateScrollToItem(messages.size - 1) }
    }

    Column(Modifier.fillMaxSize()) {
        // Top bar
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.simple_cd_back),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // From a 1:1, start a private E2E group by adding more family members.
                if (!isGroup && contactPubkeyHex != null) {
                    IconButton(onClick = { showAddPeople = true }) {
                        Icon(
                            Icons.Filled.PersonAdd,
                            contentDescription = stringResource(R.string.simple_cd_start_group),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (troubleMode != null) {
            ConnectionTroubleBanner(
                mode = troubleMode,
                onTurnOnTor = { applyTorMode(context, scope, on = true) },
                onTurnOffTor = { applyTorMode(context, scope, on = false) }
            )
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(items = messages, key = { _, m -> m.id }) { index, m ->
                // Anchor the thread in time: bubbles show only a time-of-day, so without a marker at each
                // day boundary, multi-day history reads as one shuffled afternoon. Rendered inside the
                // item (not as its own list item) so item keys stay message ids.
                val previous = if (index > 0) messages[index - 1] else null
                if (previous == null || dayKey(previous.timestamp) != dayKey(m.timestamp)) {
                    DaySeparator(m.timestamp)
                }
                // Ownership is structural, not by display name: every locally-sent message is filed with
                // senderPeerID == our own mesh peerID, while incoming messages carry the sender's peerID or the
                // conversation key. A name-equality check would misattribute a same-named group member's message
                // as ours (and flip it after a rename). Fall back to the name only when there is no peerID.
                val isMine = m.senderPeerID?.let { it == viewModel.myPeerID } ?: (m.sender == nickname)
                // A message that is wholly a `dogecoin:` URI is a payment request — render it as a tappable
                // card (Pay → opens the locked wallet prefilled) instead of a raw link. Parsing only; the
                // money path stays entirely inside the wallet sheet's existing gates.
                val payReq = remember(m.content) {
                    DogecoinUri.wholeMessagePaymentUri(m.content)?.let { DogecoinPaymentRequest.parse(it) }
                }
                val paidReceipt = remember(m.content) { DogepaidReceipt.parse(m.content) }
                val memberHex = m.senderNostrPubkey
                val displaySender = if (isGroup && !isMine) {
                    ContactDisplayName.resolveLive(
                        identity = ContactDisplayName.Identity(nostrPubkeyHex = memberHex),
                        messageSenderFallback = m.sender,
                        familyFallback = stringResource(R.string.simple_family_fallback)
                    ).display
                } else m.sender
                if (paidReceipt != null) {
                    SimplePaymentSentBubble(
                        receipt = paidReceipt,
                        isMine = isMine,
                        senderName = displaySender,
                        showSender = isGroup,
                        duplicate = m.id !in canonicalDogepaidReceiptIds,
                        timestamp = m.timestamp,
                        deliveryStatus = m.deliveryStatus,
                        walletNetwork = walletNetwork,
                        onCheckStatus = viewModel::checkDogepaidReceipt,
                        onRetry = peerID?.let { conversationKey ->
                            { viewModel.retryDogepaidReceipt(m, conversationKey) }
                        }
                    )
                } else if (payReq != null) {
                    PaymentRequestBubble(
                        request = payReq,
                        isMine = isMine,
                        senderName = displaySender,
                        showSender = isGroup,
                        canPay = walletEnabled && !isMine && payReq.network == walletNetwork,
                        timestamp = m.timestamp,
                        onPay = {
                            // Capture the immutable receipt destination NOW. The broadcast may finish after
                            // this screen has navigated to another conversation. Group receipts deliberately
                            // become a 1:1 DM to the structural sender, never a group-wide disclosure.
                            payReceiptContext = if (isGroup) {
                                DogepaidPaymentContext.forGroupRequester(payReq, m.senderNostrPubkey)
                            } else {
                                peerID?.let { DogepaidPaymentContext.forPrivateConversation(payReq, it) }
                            }
                            payRequest = payReq
                        }
                    )
                } else {
                    MessageBubble(
                        message = m,
                        isMine = isMine,
                        showSender = isGroup,
                        displaySender = displaySender,
                        // Offer "Add" only for a group member you don't already have (favorite or tap-added).
                        onSenderClick = if (
                            isGroup && !isMine && memberHex != null &&
                            FavoritesPersistenceService.shared.findNoiseKey(memberHex) == null &&
                            !KnownNpubStore.contains(memberHex)
                        ) {
                            { tapAddMember = memberHex to displaySender }
                        } else null
                    )
                }
            }
        }

        // Input bar — pad for the keyboard (IME) so it floats above it, falling back to the nav bar when
        // the keyboard is hidden. Without this the soft keyboard covers the send button (edge-to-edge app).
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (walletEnabled && !isGroup) {
                    // Ask a family member for DOGE: posts a payment-request message they can tap to pay.
                    IconButton(onClick = { showRequestDialog = true }) {
                        Icon(
                            Icons.Filled.AccountBalanceWallet,
                            contentDescription = stringResource(R.string.simple_cd_request_doge),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.simple_message_hint)) },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                val canSend = input.isNotBlank()
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = canSend) {
                            viewModel.sendMessage(input.trim())
                            input = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.simple_cd_send), tint = Color.White)
                }
            }
        }
    }

    if (showAddPeople) {
        // Turn this 1:1 into a private E2E group by adding more family (the current contact is auto-included).
        AddPeopleSheet(
            excludeHex = contactPubkeyHex,
            onDismiss = { showAddPeople = false },
            onCreate = { extra ->
                val members = (listOfNotNull(contactPubkeyHex) + extra).distinct()
                if (members.isNotEmpty()) onOpenGroup(viewModel.startNostrGroup(members, null))
            }
        )
    }
    tapAddMember?.let { (hex, name) ->
        // Discovery: add a group member you don't already have as a 1:1 Nostr contact (from their npub — NOT a
        // verified mutual favorite; upgrading to that still needs the signed QR). No fake Noise key.
        // Show sanitized remote name + ≥16-hex identity so the trust decision is not name-only (spec R-A1).
        val safeName = ContactDisplayName.sanitizeRemote(name)
            ?: stringResource(R.string.simple_family_fallback)
        val idPreview = hex.take(16)
        AlertDialog(
            onDismissRequest = { tapAddMember = null },
            title = { Text(stringResource(R.string.simple_add_contact_title, safeName)) },
            text = {
                Column {
                    Text(stringResource(R.string.simple_add_contact_body, safeName))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.simple_add_contact_id, idPreview),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val convKey = "nostr_${hex.take(16)}"
                    viewModel.startGeohashDM(hex)          // register the alias -> pubkey mapping + subscription
                    GeohashAliasRegistry.put(convKey, hex) // enable 1:1 account-DM routing (no favorite needed)
                    KnownNpubStore.put(hex, safeName)     // surface them in the Simple contacts list
                    tapAddMember = null
                }) { Text(stringResource(R.string.simple_add)) }
            },
            dismissButton = { TextButton(onClick = { tapAddMember = null }) { Text(stringResource(R.string.simple_cancel)) } }
        )
    }
    if (showRequestDialog) {
        // Reuse the app's request-DOGE dialog (self-contained: builds the URI from this device's locked
        // wallet). Simple conversations are always private 1:1 / E2E group DMs, so no public-address warning.
        RequestDogeDialog(
            requiresPublicConfirmation = false,
            onDismiss = { showRequestDialog = false },
            onPostRequest = { uri -> viewModel.sendMessage(uri) }
        )
    }
    payRequest?.let { req ->
        // The locked family wallet, opened prefilled with the tapped request (money-path gates intact).
        com.bitchat.android.features.dogecoin.DogecoinWalletSheet(
            isPresented = true,
            onDismiss = {
                payRequest = null
                payReceiptContext = null
            },
            onShareToChat = { viewModel.sendMessage(it) },
            onPaymentReceiptClaimed = viewModel::postDogepaidReceipt,
            paymentRequest = req,
            paymentReceiptContext = payReceiptContext,
            isSimpleProfile = true
        )
    }
}

@Composable
private fun MessageBubble(
    message: BitchatMessage,
    isMine: Boolean,
    showSender: Boolean,
    displaySender: String = message.sender,
    onSenderClick: (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 290.dp)
        ) {
            if (showSender && !isMine) {
                // In a group, an unknown sender's name is tappable → "Add" them as a contact (onSenderClick),
                // shown in the accent colour as an affordance. Display-time resolve (never raw "anon").
                Text(
                    text = displaySender,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (onSenderClick != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .then(if (onSenderClick != null) Modifier.clickable { onSenderClick() } else Modifier)
                        .padding(start = 12.dp, bottom = 2.dp)
                )
            }
            Surface(
                color = if (isMine) MaterialTheme.colorScheme.primary else Color.White,
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 1.dp
            ) {
                Text(
                    text = message.content,
                    color = if (isMine) Color.White else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = remember(message.timestamp) { formatBubbleTime(message.timestamp) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Delivery feedback on OWN messages, so a send that hasn't landed doesn't look identical to a
                // delivered one. "Not sent" (Failed) is drawn in the error colour; everything else is muted.
                if (isMine) {
                    val statusLabel = when (message.deliveryStatus) {
                        is DeliveryStatus.Sending -> stringResource(R.string.simple_status_sending)
                        is DeliveryStatus.Sent -> stringResource(R.string.simple_status_sent)
                        is DeliveryStatus.Delivered -> stringResource(R.string.simple_status_delivered)
                        is DeliveryStatus.Read -> stringResource(R.string.simple_status_read)
                        is DeliveryStatus.PartiallyDelivered -> stringResource(R.string.simple_status_sent)
                        is DeliveryStatus.Failed -> stringResource(R.string.simple_status_not_sent)
                        null -> null
                    }
                    if (statusLabel != null) {
                        Text(
                            text = " · $statusLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (message.deliveryStatus is DeliveryStatus.Failed)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// Locale-aware short time (follows the in-app language: 10:35 PM in English, 22:35 in Japanese).
private fun formatBubbleTime(date: java.util.Date): String =
    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, java.util.Locale.getDefault()).format(date)

// Calendar-day identity in the device's local time zone (year * 1000 + day-of-year).
private fun dayKey(date: java.util.Date): Long {
    val cal = java.util.Calendar.getInstance()
    cal.time = date
    return cal.get(java.util.Calendar.YEAR) * 1000L + cal.get(java.util.Calendar.DAY_OF_YEAR)
}

@Composable
private fun DaySeparator(date: java.util.Date) {
    val now = java.util.Date()
    val label = when (dayKey(date)) {
        dayKey(now) -> stringResource(R.string.simple_today)
        dayKey(java.util.Date(now.time - 86_400_000L)) -> stringResource(R.string.simple_yesterday)
        else -> remember(dayKey(date)) {
            val locale = java.util.Locale.getDefault()
            val sameYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) ==
                java.util.Calendar.getInstance().apply { time = date }.get(java.util.Calendar.YEAR)
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(
                locale, if (sameYear) "EEEMMMd" else "yMMMd"
            )
            java.text.SimpleDateFormat(pattern, locale).format(date)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

/**
 * Pick family members (mutual favorites reachable over Nostr) to form a private E2E group with the current
 * 1:1 contact. The current contact is auto-included ([excludeHex]), so this lists the OTHER family members.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPeopleSheet(
    excludeHex: String?,
    onDismiss: () -> Unit,
    onCreate: (memberHexes: List<String>) -> Unit
) {
    val familyFallback = stringResource(R.string.simple_family_fallback)
    val candidates = remember(familyFallback) {
        FavoritesPersistenceService.shared.getMutualFavorites().mapNotNull { fav ->
            val hex = nostrPubkeyToHex(fav.peerNostrPublicKey)
            if (hex == null || hex == excludeHex) null else hex to fav.peerNickname.ifBlank { familyFallback }
        }
    }
    var selected by remember { mutableStateOf(setOf<String>()) }
    BitchatBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.simple_start_group_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.simple_start_group_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (candidates.isEmpty()) {
                Text(
                    text = stringResource(R.string.simple_no_other_family),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            candidates.forEach { (hex, name) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selected = if (hex in selected) selected - hex else selected + hex },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = hex in selected,
                        onCheckedChange = { on -> selected = if (on) selected + hex else selected - hex }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Button(
                onClick = { onCreate(selected.toList()); onDismiss() },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.simple_create_group))
            }
        }
    }
}

private val DogeGold = Color(0xFFC2A633)

/** Structured payment report. It stays a claim until a trusted local observation is supplied. */
@Composable
private fun SimplePaymentSentBubble(
    receipt: DogepaidReceipt,
    isMine: Boolean,
    senderName: String,
    showSender: Boolean,
    duplicate: Boolean,
    timestamp: java.util.Date,
    deliveryStatus: DeliveryStatus?,
    walletNetwork: DogecoinNetwork,
    onCheckStatus: (DogepaidReceipt, (DogepaidReceiptCheckResult) -> Unit) -> Unit,
    onRetry: (() -> Unit)?
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 290.dp)
        ) {
            if (showSender && !isMine) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 2.dp)
                )
            }
            StatefulDogepaidReceiptCard(
                receipt = receipt,
                duplicate = duplicate,
                outgoing = isMine,
                walletNetwork = walletNetwork,
                onCheckStatus = onCheckStatus,
                retryEnabled = isMine && deliveryStatus is DeliveryStatus.Failed && onRetry != null,
                onRetry = onRetry
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Text(
                    text = formatBubbleTime(timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isMine && deliveryStatus != null) {
                    DeliveryStatusIcon(status = deliveryStatus)
                }
            }
        }
    }
}

/**
 * A chat bubble for a Dogecoin payment request (a message that is wholly a `dogecoin:` URI). Shows the
 * amount + note and, for an INCOMING request when the wallet is on, a "Pay" button that opens the locked
 * wallet prefilled. Parsing/presentation only — the actual send stays behind the wallet sheet's gates.
 */
@Composable
private fun PaymentRequestBubble(
    request: DogecoinPaymentRequest,
    isMine: Boolean,
    senderName: String,
    showSender: Boolean,
    canPay: Boolean,
    timestamp: java.util.Date,
    onPay: () -> Unit
) {
    val shortAddress = remember(request.address) {
        if (request.address.length > 16) "${request.address.take(8)}…${request.address.takeLast(6)}"
        else request.address
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 290.dp)
        ) {
            if (showSender && !isMine) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 2.dp)
                )
            }
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 1.dp
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.AccountBalanceWallet,
                            contentDescription = null,
                            tint = DogeGold,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isMine) stringResource(R.string.simple_you_requested)
                            else stringResource(R.string.simple_doge_request),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = request.amount?.let { stringResource(R.string.simple_doge_amount, it) }
                            ?: stringResource(R.string.simple_any_amount),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    request.label?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    request.message?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.simple_to_address, shortAddress),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (canPay) {
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onPay, modifier = Modifier.fillMaxWidth()) {
                            Text(request.amount?.let { stringResource(R.string.simple_pay_amount, it) }
                                ?: stringResource(R.string.simple_pay))
                        }
                    }
                }
            }
            Text(
                text = remember(timestamp) { formatBubbleTime(timestamp) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/** How long Nostr relays must stay unreachable before the punch-through banner appears. */
private const val CONNECTION_TROUBLE_DELAY_MS = 8_000L

/** Why the chat list is showing a connection-trouble banner — drives the banner's copy + action. */
private enum class TroubleMode { OFFLINE, SUGGEST_TOR, TOR_ON }

/**
 * Shared connection-trouble state for the chat list AND open conversations: null when Nostr relays are
 * healthy, otherwise the reason (OFFLINE / SUGGEST_TOR / TOR_ON). Keyed on REAL relay connectivity so
 * turning Tor on can't make it vanish before messages truly connect, and debounced so a normal cold-start
 * connect doesn't flash it.
 */
@Composable
private fun rememberConnectionTroubleMode(): TroubleMode? {
    val relayConnected by NostrRelayManager.shared.isConnected.collectAsState()
    val torMode by TorPreferenceManager.modeFlow.collectAsState()
    val hasInternet by rememberHasInternet()
    var trouble by remember { mutableStateOf(false) }
    LaunchedEffect(relayConnected) {
        if (relayConnected) {
            trouble = false
        } else {
            delay(CONNECTION_TROUBLE_DELAY_MS)
            trouble = true
        }
    }
    return if (!trouble) null else when {
        !hasInternet -> TroubleMode.OFFLINE
        torMode == TorMode.ON -> TroubleMode.TOR_ON
        else -> TroubleMode.SUGGEST_TOR
    }
}

/**
 * A soft, friendly attention strip shown on the chat list when Nostr relays can't be reached. It is honest
 * about the cause: when the device is offline it only points at Wi-Fi (Tor can't help with no internet);
 * when relays look blocked it offers Tor; when Tor is already on but still failing it offers to turn it back
 * off (so the reverse path isn't buried in Settings). It stays visible until the connection actually
 * recovers, so it never falsely reassures.
 */
@Composable
private fun ConnectionTroubleBanner(
    mode: TroubleMode,
    onTurnOnTor: () -> Unit,
    onTurnOffTor: () -> Unit
) {
    val ink = Color(0xFF8A5A00)
    val title: String
    val subtitle: String
    when (mode) {
        TroubleMode.OFFLINE -> {
            title = stringResource(R.string.simple_offline_title)
            subtitle = stringResource(R.string.simple_offline_body)
        }
        TroubleMode.SUGGEST_TOR -> {
            title = stringResource(R.string.simple_suggest_tor_title)
            subtitle = stringResource(R.string.simple_suggest_tor_body)
        }
        TroubleMode.TOR_ON -> {
            title = stringResource(R.string.simple_tor_on_title)
            subtitle = stringResource(R.string.simple_tor_on_body)
        }
    }
    Surface(color = Color(0xFFFFF4E5)) {   // soft amber: attention without alarm
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ink
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.copy(alpha = 0.85f)
                )
            }
            when (mode) {
                TroubleMode.SUGGEST_TOR -> TextButton(onClick = onTurnOnTor) { Text(stringResource(R.string.simple_turn_on)) }
                TroubleMode.TOR_ON -> TextButton(onClick = onTurnOffTor) { Text(stringResource(R.string.simple_turn_off)) }
                TroubleMode.OFFLINE -> {}
            }
        }
    }
}

/**
 * Reactive "does the device have a network that claims internet" signal, used to keep the trouble banner
 * honest: with genuinely no network, suggesting a "stronger connection" (Tor) would mislead — Tor needs
 * internet too. Registers a default-network callback for the lifetime of the composition.
 */
@Composable
private fun rememberHasInternet(): State<Boolean> {
    val context = LocalContext.current
    return produceState(initialValue = true) {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        fun online(): Boolean {
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        value = online()
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { value = online() }
            override fun onLost(network: android.net.Network) { value = online() }
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: android.net.NetworkCapabilities
            ) { value = online() }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        awaitDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
}

/** Unwrap a (possibly wrapped) Context to the hosting Activity, so a locale change can recreate it. */
private fun findActivitySimple(context: android.content.Context): android.app.Activity? {
    var c: android.content.Context? = context
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

/** Flip the app-wide Tor mode (pref applies immediately; the slow live network reset runs in [scope]). */
private fun applyTorMode(context: android.content.Context, scope: CoroutineScope, on: Boolean) {
    val m = if (on) TorMode.ON else TorMode.OFF
    TorPreferenceManager.set(context, m)
    scope.launch {
        ArtiTorManager.getInstance()
            .applyMode(context.applicationContext as android.app.Application, m)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleSettingsSheet(viewModel: ChatViewModel, onDismiss: () -> Unit, onOpenWallet: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nickname by viewModel.nickname.collectAsState()
    val torMode by TorPreferenceManager.modeFlow.collectAsState()
    var editingName by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var showLanguageDialog by remember { mutableStateOf(false) }
    // "" = follow the phone's language; "en"/"ja" = force that language.
    val currentLangTag = com.bitchat.android.profile.SimpleLanguage.getTag(context)
    val currentLangLabel = when (currentLangTag) {
        "en" -> stringResource(R.string.simple_language_english)
        "ja" -> stringResource(R.string.simple_language_japanese)
        else -> stringResource(R.string.simple_language_system)
    }

    if (editingName) {
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text(stringResource(R.string.simple_your_name)) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it.take(32) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.simple_name_label)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val n = nameInput.trim()
                        if (n.isNotEmpty()) viewModel.setNickname(n)
                        editingName = false
                    },
                    enabled = nameInput.trim().isNotEmpty()
                ) { Text(stringResource(R.string.simple_save)) }
            },
            dismissButton = { TextButton(onClick = { editingName = false }) { Text(stringResource(R.string.simple_cancel)) } }
        )
    }

    if (showLanguageDialog) {
        // Pick the UI language. Applying it persists the choice and recreates the Activity so the new locale
        // takes effect immediately (attachBaseContext re-wraps the context on recreation).
        val apply: (String) -> Unit = { tag ->
            com.bitchat.android.profile.SimpleLanguage.setTag(context, tag)
            showLanguageDialog = false
            onDismiss()
            findActivitySimple(context)?.recreate()
        }
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.simple_language)) },
            text = {
                Column {
                    TextButton(onClick = { apply("") }) { Text(stringResource(R.string.simple_language_system)) }
                    TextButton(onClick = { apply("en") }) { Text(stringResource(R.string.simple_language_english)) }
                    TextButton(onClick = { apply("ja") }) { Text(stringResource(R.string.simple_language_japanese)) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showLanguageDialog = false }) { Text(stringResource(R.string.simple_cancel)) } }
        )
    }

    BitchatBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = stringResource(R.string.simple_settings_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Your name (cosmetic, safe to change — re-announces but can't break connectivity).
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { nameInput = nickname; editingName = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.simple_your_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = nickname,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = stringResource(R.string.simple_edit),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // Language (cosmetic, safe): follow the phone or force English / 日本語.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showLanguageDialog = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.simple_language),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = currentLangLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = stringResource(R.string.simple_edit),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // Tor "punch-through": default off for reliability; turn on only if the network blocks Nostr.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.simple_tor_setting_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.simple_tor_setting_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = torMode == TorMode.ON,
                    onCheckedChange = { on -> applyTorMode(context, scope, on) }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // Dogecoin wallet opt-in (secondary; off by default, reversible — functionality always exists).
            val walletEnabled by com.bitchat.android.profile.ProfilePreferenceManager.walletEnabledFlow.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.simple_use_dogecoin),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.simple_use_dogecoin_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = walletEnabled,
                    onCheckedChange = {
                        com.bitchat.android.profile.ProfilePreferenceManager.setWalletEnabled(context, it)
                    }
                )
            }
            if (walletEnabled) {
                TextButton(onClick = { onDismiss(); onOpenWallet() }) {
                    Text(stringResource(R.string.simple_open_wallet))
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            TextButton(onClick = {
                onDismiss()
                scope.launch {
                    ProfileSetupCoordinator.applyProfileDefaults(
                        context.applicationContext as android.app.Application,
                        AppProfile.POWER
                    )
                }
            }) {
                Text(stringResource(R.string.simple_switch_to_power))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatListRow(
    title: String,
    subtitle: String,
    avatarInitial: String?,
    avatarColor: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    timeText: String? = null,
    unreadCount: Int = 0
) {
    val unread = unreadCount > 0
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable { onClick() }
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(avatarColor),
            contentAlignment = Alignment.Center
        ) {
            if (avatarInitial == null) {
                Icon(Icons.Filled.Group, contentDescription = null, tint = Color.White)
            } else {
                Text(avatarInitial, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                // An unread conversation's preview reads as primary text, not muted.
                color = if (unread) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Trailing meta: last-activity time over an unread badge, right-aligned.
        if (timeText != null || unread) {
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (timeText != null) {
                    Text(
                        timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (unread) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                if (unread) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** One-line preview for a message on the Simple home list; a dogecoin: invoice reads as a friendly label. */
private fun simpleChatPreview(message: BitchatMessage, context: android.content.Context): String {
    val raw = message.content.trim()
    if (raw.startsWith("dogecoin:", ignoreCase = true)) {
        return context.getString(R.string.simple_preview_payment)
    }
    // Collapse newlines/whitespace so the preview stays a single tidy line.
    return raw.replace(Regex("\\s+"), " ").take(120)
}

/** Compact list timestamp: today -> clock time, yesterday -> "Yesterday", else a short date. */
private fun simpleChatListTime(date: java.util.Date, context: android.content.Context): String {
    val now = java.util.Date()
    return when (dayKey(date)) {
        dayKey(now) -> formatBubbleTime(date)
        dayKey(java.util.Date(now.time - 86_400_000L)) -> context.getString(R.string.simple_yesterday)
        else -> java.text.DateFormat
            .getDateInstance(java.text.DateFormat.SHORT, java.util.Locale.getDefault())
            .format(date)
    }
}
