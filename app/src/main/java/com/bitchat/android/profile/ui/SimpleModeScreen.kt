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
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.TorMode
import com.bitchat.android.net.TorPreferenceManager
import com.bitchat.android.nostr.Bech32
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
    data class Contact(val peerID: String, val name: String, val noiseHex: String?) : SimpleTarget
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

    when (val t = target) {
        null -> LineTheme {
            SimpleHome(
                viewModel = viewModel,
                onOpenContact = { npub, name, noiseHex ->
                    val hex = nostrPubkeyToHex(npub)
                    if (hex != null) {
                        val convKey = "nostr_${hex.take(16)}"
                        viewModel.startGeohashDM(hex)        // register the conv-key -> pubkey mapping + subscription
                        viewModel.startPrivateChat(convKey)  // make it the active private-chat context for sends
                        target = SimpleTarget.Contact(convKey, name, noiseHex)
                    }
                }
            )
        }
        is SimpleTarget.Contact -> {
            BackHandler { viewModel.endPrivateChat(); target = null }
            LineTheme {
                SimpleConversation(
                    viewModel = viewModel,
                    title = t.name,
                    isPrivate = true,
                    peerID = t.peerID,
                    noiseHex = t.noiseHex,
                    onBack = { viewModel.endPrivateChat(); target = null }
                )
            }
        }
    }
}

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
    onOpenContact: (npub: String?, name: String, noiseHex: String) -> Unit
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
    var showSettings by remember { mutableStateOf(false) }
    var showAddFamily by remember { mutableStateOf(false) }
    var showWallet by remember { mutableStateOf(false) }

    // Smart Tor "punch-through". The banner appears only after Nostr relays have actually been unreachable
    // for a sustained window — keyed on REAL relay connectivity, so turning Tor on can't make it vanish
    // before messages truly connect — and its message adapts to WHY: offline (Tor can't help) vs. relays
    // blocked (offer Tor) vs. already on Tor and still failing (offer to turn it back off).
    val relayConnected by NostrRelayManager.shared.isConnected.collectAsState()
    val torMode by TorPreferenceManager.modeFlow.collectAsState()
    val hasInternet by rememberHasInternet()
    var connectionTrouble by remember { mutableStateOf(false) }
    LaunchedEffect(relayConnected) {
        if (relayConnected) {
            connectionTrouble = false
        } else {
            // Debounce so a normal few-second cold-start connect doesn't flash the banner.
            delay(CONNECTION_TROUBLE_DELAY_MS)
            connectionTrouble = true
        }
    }

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

        if (connectionTrouble) {
            ConnectionTroubleBanner(
                mode = when {
                    !hasInternet -> TroubleMode.OFFLINE
                    torMode == TorMode.ON -> TroubleMode.TOR_ON
                    else -> TroubleMode.SUGGEST_TOR
                },
                onTurnOnTor = { applyTorMode(context, scope, on = true) },
                onTurnOffTor = { applyTorMode(context, scope, on = false) }
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (contacts.isEmpty()) {
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
    isPrivate: Boolean,
    peerID: String?,
    noiseHex: String?,
    onBack: () -> Unit
) {
    val nickname by viewModel.nickname.collectAsState()
    val privateChats by viewModel.privateChats.collectAsState()
    val channelMessages by viewModel.channelMessages.collectAsState()
    val selectedLoc by viewModel.selectedLocationChannel.collectAsState()
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.collectAsState()
    val walletEnabled by ProfilePreferenceManager.walletEnabledFlow.collectAsState()
    val context = LocalContext.current
    // The Simple wallet is locked to ONE network. A payment request for a DIFFERENT network must not be
    // payable here: tapping Pay opens the sheet with paymentRequest, whose prefill calls switchNetwork() —
    // so an untrusted cross-network `dogecoin:` chat message could otherwise silently move a locked
    // (e.g. testnet play-money) wallet to mainnet. Gate the Pay button on a network match below.
    val walletNetwork = remember(context) {
        com.bitchat.android.features.dogecoin.DogecoinWalletRepository(context).loadSelectedNetwork()
    }
    var showRequestDialog by remember { mutableStateOf(false) }
    var payRequest by remember { mutableStateOf<DogecoinPaymentRequest?>(null) }

    // A contact's DM messages can be filed under several keys depending on transport + the app's
    // opportunistic consolidation: the temporary nostr_<pub16> alias this screen opened with, the canonical
    // 64-hex Noise key (offline favorite), and — when the contact is connected over BLE mesh — an ephemeral
    // mesh peerID (the app's current "canonical" peer, exposed as selectedPrivateChatPeer). Union all of
    // them so messages show regardless of transport, deduped by id and time-sorted. Memoized so it does NOT
    // recompute on every keystroke / unrelated recomposition.
    val messages: List<BitchatMessage> = remember(
        privateChats, channelMessages, selectedLoc, isPrivate, peerID, noiseHex, selectedPrivatePeer
    ) {
        if (isPrivate && peerID != null) {
            val keys = buildList {
                add(peerID)
                noiseHex?.let { add(it) }
                selectedPrivatePeer?.let { add(it) }
            }
            keys.flatMap { privateChats[it] ?: emptyList() }
                .distinctBy { it.id }
                .sortedBy { it.timestamp }
        } else {
            val gh = (selectedLoc as? ChannelID.Location)?.channel?.geohash
            if (gh != null) channelMessages["geo:$gh"] ?: emptyList() else emptyList()
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
                    overflow = TextOverflow.Ellipsis
                )
            }
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
                val isMine = m.sender == nickname
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
                        showSender = !isPrivate,
                        canPay = walletEnabled && !isMine && payReq.network == walletNetwork,
                        timestamp = m.timestamp,
                        onPay = { payRequest = payReq }
                    )
                } else {
                    MessageBubble(message = m, isMine = isMine, showSender = !isPrivate)
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
                if (walletEnabled) {
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

    if (showRequestDialog) {
        // Reuse the app's request-DOGE dialog (self-contained: builds the URI from this device's locked
        // wallet). Public confirmation only for the Family Room (a public geohash); 1:1 DMs are private.
        RequestDogeDialog(
            requiresPublicConfirmation = !isPrivate,
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
private fun MessageBubble(message: BitchatMessage, isMine: Boolean, showSender: Boolean) {
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
                    text = message.sender,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 2.dp)
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
            Text(
                text = remember(message.timestamp) { formatBubbleTime(message.timestamp) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

private fun formatBubbleTime(date: java.util.Date): String =
    java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(date)

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
