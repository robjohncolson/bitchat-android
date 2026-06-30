package com.bitchat.android.profile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.TorMode
import com.bitchat.android.net.TorPreferenceManager
import com.bitchat.android.nostr.Bech32
import com.bitchat.android.profile.AppProfile
import com.bitchat.android.profile.ProfileSetupCoordinator
import com.bitchat.android.ui.ChatScreen
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.PrivateChatSheet
import kotlinx.coroutines.launch

/**
 * LINE-style surface for the SIMPLE ("Family") profile: a clean chat list (the pinned family room + the
 * user's family contacts) over the SAME engine, reusing the existing conversation UI for the actual chat.
 * The full Power UI is untouched — this is presentation only.
 *
 * Phase 3a scope: the home list + the FAMILY ROOM conversation (reuses [ChatScreen], which renders the
 * pinned geohash channel; system Back returns to the list). The 1:1 contact chat, the in-app "add family"
 * QR scan, the minimal settings, and the conversation reskin land in the next sub-steps.
 */
@Composable
fun SimpleModeScreen(viewModel: ChatViewModel) {
    var inRoom by remember { mutableStateOf(false) }
    val chatPeer by viewModel.privateChatSheetPeer.collectAsState()

    if (inRoom) {
        BackHandler(enabled = true) { inRoom = false }
        // Reuse the existing conversation surface for the family room (the active geohash channel).
        // Conversation chrome reskin is a later sub-step; Back returns to the LINE home.
        ChatScreen(viewModel = viewModel)
    } else {
        LineTheme {
            SimpleHome(
                viewModel = viewModel,
                onOpenRoom = { inRoom = true },
                onOpenContact = { npub ->
                    // Open a private 1:1 Nostr DM with the family contact. startGeohashDM registers the
                    // conversation key mapping (nostr_<pub16> -> full pubkey) and opens the chat sheet.
                    nostrPubkeyToHex(npub)?.let { viewModel.startGeohashDM(it) }
                }
            )
        }
        // The 1:1 family chat reuses the existing private-chat surface. It normally lives inside
        // ChatScreen; render it here for the LINE home view (opened via showPrivateChatSheet).
        chatPeer?.let { peer ->
            PrivateChatSheet(
                isPresented = true,
                peerID = peer,
                viewModel = viewModel,
                onDismiss = { viewModel.hidePrivateChatSheet() }
            )
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
private fun SimpleHome(viewModel: ChatViewModel, onOpenRoom: () -> Unit, onOpenContact: (String?) -> Unit) {
    // Snapshot of mutual-favorite family contacts (recomputed on recomposition; reactive flow comes later).
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
                ChatListRow(
                    title = c.peerNickname.ifBlank { "Family" },
                    subtitle = if (c.peerNostrPublicKey != null) "Tap to chat" else "No internet contact yet",
                    avatarInitial = c.peerNickname.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    avatarColor = Color(0xFF9AA3AB),
                    onClick = { onOpenContact(c.peerNostrPublicKey) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }

    if (showSettings) {
        SimpleSettingsSheet(viewModel = viewModel, onDismiss = { showSettings = false })
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
                Icon(Icons.Default.Group, contentDescription = null, tint = Color.White)
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
