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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.TorMode
import com.bitchat.android.net.TorPreferenceManager
import com.bitchat.android.nostr.Bech32
import com.bitchat.android.profile.AppProfile
import com.bitchat.android.profile.ProfileSetupCoordinator
import com.bitchat.android.ui.ChatViewModel
import kotlinx.coroutines.launch

/**
 * LINE-style surface for the SIMPLE ("Family") profile: a clean chat list AND a clean conversation, both
 * rendered natively here over the SAME ChatViewModel — no fall-through to the terminal UI. Power UI is
 * untouched; this is presentation only.
 */
private sealed interface SimpleTarget {
    data object Room : SimpleTarget
    data class Contact(val peerID: String, val name: String) : SimpleTarget
}

@Composable
fun SimpleModeScreen(viewModel: ChatViewModel) {
    var target by remember { mutableStateOf<SimpleTarget?>(null) }

    when (val t = target) {
        null -> LineTheme {
            SimpleHome(
                viewModel = viewModel,
                onOpenRoom = {
                    // The family room is the pinned geohash channel; clear any private-chat context so sends
                    // route to the room.
                    viewModel.endPrivateChat()
                    target = SimpleTarget.Room
                },
                onOpenContact = { npub, name ->
                    val hex = nostrPubkeyToHex(npub)
                    if (hex != null) {
                        val convKey = "nostr_${hex.take(16)}"
                        viewModel.startGeohashDM(hex)        // register the conv-key -> pubkey mapping + subscription
                        viewModel.startPrivateChat(convKey)  // make it the active private-chat context for sends
                        target = SimpleTarget.Contact(convKey, name)
                    }
                }
            )
        }
        SimpleTarget.Room -> {
            BackHandler { target = null }
            LineTheme {
                SimpleConversation(
                    viewModel = viewModel,
                    title = "Family Room",
                    isPrivate = false,
                    peerID = null,
                    onBack = { target = null }
                )
            }
        }
        is SimpleTarget.Contact -> {
            BackHandler { viewModel.endPrivateChat(); target = null }
            LineTheme {
                SimpleConversation(
                    viewModel = viewModel,
                    title = t.name,
                    isPrivate = true,
                    peerID = t.peerID,
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
    onOpenRoom: () -> Unit,
    onOpenContact: (npub: String?, name: String) -> Unit
) {
    val contacts = remember { FavoritesPersistenceService.shared.getMutualFavorites() }
    var showSettings by remember { mutableStateOf(false) }

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
                IconButton(onClick = { showSettings = true }) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "family_room") {
                ChatListRow(
                    title = "Family Room",
                    subtitle = "Everyone in one place",
                    avatarInitial = null,
                    avatarColor = MaterialTheme.colorScheme.primary,
                    onClick = onOpenRoom
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
                    onClick = { if (c.peerNostrPublicKey != null) onOpenContact(c.peerNostrPublicKey, name) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }

    if (showSettings) {
        SimpleSettingsSheet(viewModel = viewModel, onDismiss = { showSettings = false })
    }
}

@Composable
private fun SimpleConversation(
    viewModel: ChatViewModel,
    title: String,
    isPrivate: Boolean,
    peerID: String?,
    onBack: () -> Unit
) {
    val nickname by viewModel.nickname.collectAsState()
    val privateChats by viewModel.privateChats.collectAsState()
    val channelMessages by viewModel.channelMessages.collectAsState()
    val selectedLoc by viewModel.selectedLocationChannel.collectAsState()

    val messages: List<BitchatMessage> = if (isPrivate && peerID != null) {
        privateChats[peerID] ?: emptyList()
    } else {
        val gh = (selectedLoc as? ChannelID.Location)?.channel?.geohash
        if (gh != null) channelMessages["geo:$gh"] ?: emptyList() else emptyList()
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
                MessageBubble(message = m, isMine = m.sender == nickname, showSender = !isPrivate)
            }
        }

        // Input bar
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleSettingsSheet(viewModel: ChatViewModel, onDismiss: () -> Unit) {
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
                    onCheckedChange = { on ->
                        val m = if (on) TorMode.ON else TorMode.OFF
                        TorPreferenceManager.set(context, m)
                        scope.launch {
                            ArtiTorManager.getInstance()
                                .applyMode(context.applicationContext as android.app.Application, m)
                        }
                    }
                )
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
