package com.bitchat.android.profile.ui

import android.Manifest
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitchat.android.profile.FamilyProvisioning
import com.bitchat.android.services.VerificationService
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.QRCodeImage
import com.bitchat.android.ui.ScannerView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

/**
 * SIMPLE-profile "Add family" flow: show MY signed identity QR for them to scan, OR scan theirs to add
 * them as a mutual favorite (a private Nostr thread). Reuses the app's existing QR generator + camera
 * scanner ([QRCodeImage], [ScannerView]) and [FamilyProvisioning]; no handshake needed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun AddFamilyScreen(
    viewModel: ChatViewModel,
    onClose: () -> Unit,
    onAdded: () -> Unit
) {
    val context = LocalContext.current
    val nickname by viewModel.nickname.collectAsState()
    val npub = remember { viewModel.getCurrentNpub() }
    val qrString = remember(nickname, npub) { viewModel.buildMyQRString(nickname, npub) }
    var tab by remember { mutableStateOf(0) } // 0 = My code, 1 = Scan
    var pasted by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf(false) }
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    fun tryAdd(code: String): Boolean {
        if (code.isBlank()) return false
        // Accept the raw bitchat:// URL or its base64 form; signature-verify either way.
        val url = if (code.startsWith("bitchat://")) code
        else runCatching { String(Base64.decode(code, Base64.URL_SAFE)) }.getOrNull() ?: code
        val qr = VerificationService.verifyScannedQR(url, maxAgeSeconds = Long.MAX_VALUE) ?: return false
        FamilyProvisioning.provisionFamilyContact(qr)
        Toast.makeText(context, "Added ${qr.nickname}", Toast.LENGTH_SHORT).show()
        onAdded()
        onClose()
        return true
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    text = "Add family",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        TabRow(
            selectedTabIndex = tab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("My code") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Scan") })
        }

        when (tab) {
            0 -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Show this to your family member,\nand have them tap “Scan”.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(24.dp))
                if (!qrString.isNullOrBlank()) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.White)
                            .padding(20.dp)
                    ) {
                        QRCodeImage(data = qrString, size = 260.dp)
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = nickname,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    Text(
                        text = "Your code isn't ready yet — try again in a moment.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            1 -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (cameraPermission.status.isGranted) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        ScannerView(onScan = { code -> tryAdd(code) })
                        Text(
                            text = "Point at their code",
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 24.dp)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                } else {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Outlined.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Allow the camera to scan a family member's code.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                            Text("Allow camera")
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it; pasteError = false },
                    label = { Text("Or paste their code") },
                    isError = pasteError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { if (!tryAdd(pasted.trim())) pasteError = true },
                    enabled = pasted.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add") }
            }
        }
    }
}
