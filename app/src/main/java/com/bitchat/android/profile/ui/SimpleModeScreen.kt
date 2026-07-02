package com.bitchat.android.profile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.favorites.FavoritesChangeListener
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.features.dogecoin.DogecoinPaymentRequest
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.TorMode
import com.bitchat.android.net.TorPreferenceManager
import com.bitchat.android.nostr.Bech32
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.nostr.KnownNpubStore
import com.bitchat.android.nostr.NostrGroupRegistry
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.profile.AppProfile
import com.bitchat.android.profile.ProfilePreferenceManager
import com.bitchat.android.profile.ProfileSetupCoordinator
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.DogecoinUri
import com.bitchat.android.ui.RequestDogeDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * LINE-style surface for the SIMPLE ("Family") profile: a clean chat list AND a clean conversation, both
 * rendered natively here over the SAME ChatViewModel — no fall-through to the terminal UI. Power UI is
 * untouched; this is presentation only.
 */
private sealed interface SimpleTarget {
    data class Contact(
        val peerID: String, val name: String, val noiseHex: String?, val pubkeyHex: String?
    ) : SimpleTarget
    data class Group(
        val convKey: String, val subject: String?, val members: List<NostrGroupRegistry.GroupMember>
    ) : SimpleTarget
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
    // The open conversation's key, kept in a Saveable so it survives Activity recreation (dark-mode / locale /
    // font-size / split-screen change). `target` is not Saveable, so after recreation it resets to null while
    // the ViewModel still thinks the thread is open — which would suppress that thread's notifications and fire
    // false read receipts. We restore `target` from this key below.
    var savedConvKey by rememberSaveable { mutableStateOf<String?>(null) }

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
        val name = if (hex != null) {
            viewModel.geohashViewModel.displayNameForNostrPubkeyUI(hex)
        } else {
            viewModel.privateChats.value[convKey]?.lastOrNull { it.senderPeerID == convKey }?.sender ?: "Family"
        }
        val noiseHex = hex?.let { h ->
            FavoritesPersistenceService.shared.findNoiseKey(h)?.joinToString("") { b -> "%02x".format(b) }
        }
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
                SimpleConversation(
                    viewModel = viewModel,
                    title = t.name,
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
                    title = t.subject ?: "Family group",
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

@Composable
private fun SimpleHome(
    viewModel: ChatViewModel,
    onOpenContact: (npub: String?, name: String, noiseHex: String?) -> Unit,
    onOpenGroup: (convKey: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableStateOf(0) }

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
                    text = "Chats",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showAddFamily = true }) {
                    Icon(
                        Icons.Filled.PersonAdd,
                        contentDescription = "Add family",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Settings",
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

        LazyColumn(Modifier.fillMaxSize()) {
            if (contacts.isEmpty() && groups.isEmpty() && known.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 56.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No family added yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Tap the add-person button above to add a family member, then chat privately.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            items(groups, key = { "grp_" + it.key }) { entry ->
                ChatListRow(
                    title = entry.value.subject ?: "Family group",
                    subtitle = "Family group",
                    avatarInitial = null,
                    avatarColor = MaterialTheme.colorScheme.primary,
                    onClick = { onOpenGroup(entry.key) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
            items(
                items = contacts,
                key = { it.peerNoisePublicKey.joinToString("") { b -> "%02x".format(b) } }
            ) { c ->
                val name = c.peerNickname.ifBlank { "Family" }
                ChatListRow(
                    title = name,
                    subtitle = if (c.peerNostrPublicKey != null) "Tap to chat" else "No internet contact yet",
                    avatarInitial = c.peerNickname.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    avatarColor = Color(0xFF9AA3AB),
                    onClick = {
                        if (c.peerNostrPublicKey != null) {
                            val noiseHex = c.peerNoisePublicKey.joinToString("") { b -> "%02x".format(b) }
                            onOpenContact(c.peerNostrPublicKey, name, noiseHex)
                        }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
            items(known, key = { "npub_" + it.key }) { entry ->
                // Tap-added-from-a-group contacts are NOT verified favorites — their display name is whatever a
                // group message asserted. Flag that so a non-technical user can tell them apart from a family
                // member they added by scanning the signed QR code (a verified mutual favorite, plain above).
                ChatListRow(
                    title = entry.value,
                    subtitle = "Added from a group · not verified",
                    avatarInitial = entry.value.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    avatarColor = Color(0xFFC0A9B0),   // muted mauve, distinct from the verified-favorite gray
                    onClick = { onOpenContact(entry.key, entry.value, null) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
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
                        contentDescription = "Back",
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
                            contentDescription = "Start a group",
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
            items(items = messages, key = { it.id }) { m ->
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
                if (payReq != null) {
                    PaymentRequestBubble(
                        request = payReq,
                        isMine = isMine,
                        senderName = m.sender,
                        showSender = isGroup,
                        canPay = walletEnabled && !isMine && payReq.network == walletNetwork,
                        timestamp = m.timestamp,
                        onPay = { payRequest = payReq }
                    )
                } else {
                    val memberHex = m.senderNostrPubkey
                    MessageBubble(
                        message = m,
                        isMine = isMine,
                        showSender = isGroup,
                        // Offer "Add" only for a group member you don't already have (favorite or tap-added).
                        onSenderClick = if (
                            isGroup && !isMine && memberHex != null &&
                            FavoritesPersistenceService.shared.findNoiseKey(memberHex) == null &&
                            !KnownNpubStore.contains(memberHex)
                        ) {
                            { tapAddMember = memberHex to m.sender }
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
                            contentDescription = "Request Dogecoin",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
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
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
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
        AlertDialog(
            onDismissRequest = { tapAddMember = null },
            title = { Text("Add $name?") },
            text = { Text("Add $name to your contacts so you can message them privately, one-to-one. " +
                "This name is what the group said — it isn't verified. To be sure it's really them, add them " +
                "by scanning their code.") },
            confirmButton = {
                TextButton(onClick = {
                    val convKey = "nostr_${hex.take(16)}"
                    viewModel.startGeohashDM(hex)          // register the alias -> pubkey mapping + subscription
                    GeohashAliasRegistry.put(convKey, hex) // enable 1:1 account-DM routing (no favorite needed)
                    KnownNpubStore.put(hex, name)          // surface them in the Simple contacts list
                    tapAddMember = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { tapAddMember = null }) { Text("Cancel") } }
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
            onDismiss = { payRequest = null },
            onShareToChat = { viewModel.sendMessage(it) },
            paymentRequest = req,
            isSimpleProfile = true
        )
    }
}

@Composable
private fun MessageBubble(
    message: BitchatMessage,
    isMine: Boolean,
    showSender: Boolean,
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
                // shown in the accent colour as an affordance.
                Text(
                    text = message.sender,
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
                        is DeliveryStatus.Sending -> "Sending…"
                        is DeliveryStatus.Sent -> "Sent"
                        is DeliveryStatus.Delivered -> "Delivered"
                        is DeliveryStatus.Read -> "Read"
                        is DeliveryStatus.PartiallyDelivered -> "Sent"
                        is DeliveryStatus.Failed -> "Not sent"
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

private fun formatBubbleTime(date: java.util.Date): String =
    java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(date)

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
    val candidates = remember {
        FavoritesPersistenceService.shared.getMutualFavorites().mapNotNull { fav ->
            val hex = nostrPubkeyToHex(fav.peerNostrPublicKey)
            if (hex == null || hex == excludeHex) null else hex to fav.peerNickname.ifBlank { "Family" }
        }
    }
    var selected by remember { mutableStateOf(setOf<String>()) }
    BitchatBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Start a group",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Add more family to this chat to make a private group. Everyone's messages stay encrypted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (candidates.isEmpty()) {
                Text(
                    text = "No other family members to add yet.",
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
                Text("Create group")
            }
        }
    }
}

private val DogeGold = Color(0xFFC2A633)

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
                            text = if (isMine) "You requested" else "Dogecoin request",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = request.amount?.let { "$it DOGE" } ?: "Any amount",
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
                        text = "to $shortAddress",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (canPay) {
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onPay, modifier = Modifier.fillMaxWidth()) {
                            Text(request.amount?.let { "Pay $it DOGE" } ?: "Pay")
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
            title = "You appear to be offline"
            subtitle = "Check your Wi-Fi or mobile data"
        }
        TroubleMode.SUGGEST_TOR -> {
            title = "Messages aren't connecting"
            subtitle = "Try turning on a stronger connection"
        }
        TroubleMode.TOR_ON -> {
            title = "Connecting over a stronger connection…"
            subtitle = "This can take a moment — you can turn it off if it doesn't help"
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
                TroubleMode.SUGGEST_TOR -> TextButton(onClick = onTurnOnTor) { Text("Turn on") }
                TroubleMode.TOR_ON -> TextButton(onClick = onTurnOffTor) { Text("Turn off") }
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

    if (editingName) {
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Your name") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it.take(32) },
                    singleLine = true,
                    label = { Text("Name") }
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
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingName = false }) { Text("Cancel") } }
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
                text = "Settings",
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
                        text = "Your name",
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
                    text = "Edit",
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
                        text = "Stronger connection (Tor)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Turn on only if your messages won't connect",
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
                        text = "Use Dogecoin",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "A simple wallet to send and receive Dogecoin",
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
                    Text("Open wallet")
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
                Text("Switch to full bitchat (advanced)")
            }
        }
    }
}

@Composable
private fun ChatListRow(
    title: String,
    subtitle: String,
    avatarInitial: String?,
    avatarColor: Color,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
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
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
