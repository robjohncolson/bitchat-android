package com.bitchat.android.features.dogecoin

import com.bitchat.android.features.dogecoin.ui.ConfirmationRing
import com.bitchat.android.features.dogecoin.ui.DogecoinWalletTheme
import com.bitchat.android.features.dogecoin.ui.RingMode
import com.bitchat.android.features.dogecoin.ui.dogeWalletColors
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.button.CloseButton
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// Reusable wallet UI components extracted from DogecoinWalletSheet (P2-1, behavior-preserving).
@Composable
internal fun WalletCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

/**
 * Home-node assist surface (spec R-C3). Offer state: the light client is behind but a node is already
 * configured — one tap rides reads+broadcast on the node for THIS session while SPV keeps syncing.
 * Active state: the R-C3 banner with honest SPV progress, an explicit not-over-Tor disclosure when Tor is on
 * (the RPC path never routes over Tor), and a one-tap revert. Presentation-only: callers own the state,
 * and nothing here persists a backend choice (R-C4a).
 */
@Composable
internal fun NodeAssistCard(
    active: Boolean,
    spvProgress: String,
    notOverTor: Boolean,
    onUse: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (active) {
                    stringResource(R.string.dogecoin_node_assist_active, spvProgress) +
                        if (notOverTor) " · " + stringResource(R.string.dogecoin_node_assist_not_over_tor) else ""
                } else {
                    stringResource(R.string.dogecoin_node_assist_offer)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = if (active) onStop else onUse) {
                Text(
                    text = stringResource(
                        if (active) R.string.dogecoin_node_assist_stop else R.string.dogecoin_node_assist_use
                    )
                )
            }
        }
    }
}

/**
 * One transaction row: direction arrow + signed amount + confirmation status. Pending txs show the small
 * 0->target ConfirmationRing (the same ornament as the focal hero) so progress reads at a glance; once at
 * the target it collapses to a gold check. Presentation-only.
 */
@Composable
internal fun WalletTxRowView(row: WalletTxRow, onClick: (() -> Unit)? = null) {
    val colors = dogeWalletColors
    val incomingGreen = Color(0xFF2E7D32)
    val confirmed = row.confirmations >= DOGECOIN_SPV_CONFIRM_TARGET
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = if (row.incoming) "↓" else "↑",
            style = MaterialTheme.typography.titleMedium,
            color = if (row.incoming) incomingGreen else colors.ink
        )
        Text(
            text = (if (row.incoming) "+" else "−") + DogecoinAmount.formatKoinu(row.amountKoinu) + " DOGE",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = colors.ink,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (confirmed) {
            Text("✓", style = MaterialTheme.typography.titleMedium, color = colors.gold)
        } else {
            ConfirmationRing(
                mode = RingMode.CONFIRMING,
                progress = row.confirmations.toFloat() / DOGECOIN_SPV_CONFIRM_TARGET,
                diameter = 24.dp,
                strokeWidth = 3.dp,
                segments = DOGECOIN_SPV_CONFIRM_TARGET,
                contentDescription = "${row.confirmations} of $DOGECOIN_SPV_CONFIRM_TARGET confirmations"
            )
            Text(
                text = "${row.confirmations}/$DOGECOIN_SPV_CONFIRM_TARGET",
                style = MaterialTheme.typography.labelMedium,
                color = colors.muted
            )
        }
    }
}

@Composable
internal fun SecureWindowFlagEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled, view) {
        if (!enabled) return@DisposableEffect onDispose { }

        val window = (view.context as? Activity)?.window
        val windowFlags = window?.attributes?.flags ?: 0
        val hadSecureFlag = (windowFlags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        onDispose {
            if (window != null && !hadSecureFlag) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

@Composable
internal fun NodeStatusRow(
    status: DogecoinNodeStatus?,
    refreshing: Boolean,
    network: DogecoinNetwork
) {
    val colorScheme = MaterialTheme.colorScheme
    val readyForNetwork = status?.isReadyFor(network) == true
    val usableForNetwork = status?.isUsableFor(network) == true
    val text = when {
        refreshing -> stringResource(R.string.dogecoin_node_checking, network.displayName)
        status == null -> stringResource(R.string.dogecoin_node_not_checked)
        usableForNetwork && status.walletReady == false -> stringResource(
            R.string.dogecoin_node_wallet_unavailable,
            network.displayName,
            status.walletError ?: stringResource(R.string.dogecoin_node_wallet_unknown_error)
        )
        usableForNetwork && status.relayReady == false -> stringResource(
            R.string.dogecoin_node_relay_unavailable,
            network.displayName,
            status.relayError ?: stringResource(R.string.dogecoin_node_relay_unknown_error)
        )
        readyForNetwork -> stringResource(
            if (status.pruned == true) {
                R.string.dogecoin_node_connected_pruned
            } else {
                R.string.dogecoin_node_connected
            },
            network.displayName,
            status.blocks ?: 0,
            status.headers ?: 0,
            status.peerCount ?: 0
        )
        usableForNetwork && status.initialBlockDownload == true -> stringResource(
            R.string.dogecoin_node_syncing,
            network.displayName,
            status.blocks ?: 0,
            status.headers ?: 0,
            ((status.verificationProgress ?: 0.0) * 100.0).coerceIn(0.0, 100.0)
        )
        status.chain != null -> stringResource(
            R.string.dogecoin_node_wrong_chain,
            status.chain,
            network.displayName
        )
        else -> status.error ?: stringResource(R.string.dogecoin_node_unreachable, network.displayName)
    }

    val color = when {
        refreshing -> colorScheme.primary
        usableForNetwork && status?.walletReady == false -> colorScheme.error
        usableForNetwork && status?.relayReady == false -> colorScheme.error
        readyForNetwork && status?.pruned == true -> colorScheme.tertiary
        readyForNetwork -> colorScheme.primary
        else -> colorScheme.error
    }

    val policyCheckText = when {
        readyForNetwork && status?.policyCheckAvailable == true ->
            stringResource(R.string.dogecoin_node_policy_check_available)
        readyForNetwork && status?.policyCheckAvailable == false ->
            status.policyCheckError ?: stringResource(R.string.dogecoin_node_policy_check_unavailable)
        else -> null
    }
    val rescanBlockchainText = when {
        readyForNetwork && status?.rescanBlockchainAvailable == false ->
            status.rescanBlockchainError ?: stringResource(R.string.dogecoin_node_rescanblockchain_unavailable)
        else -> null
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        lineHeight = 18.sp
    )
    policyCheckText?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = if (status?.policyCheckAvailable == true) {
                colorScheme.onSurface.copy(alpha = 0.65f)
            } else {
                colorScheme.tertiary
            },
            lineHeight = 18.sp
        )
    }
    rescanBlockchainText?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.tertiary,
            lineHeight = 18.sp
        )
    }
}

@Composable
internal fun DogecoinUtxoSection(
    title: String,
    utxos: List<DogecoinUtxo>,
    limit: Int = DOGECOIN_UTXO_PREVIEW_LIMIT
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        utxos.take(limit).forEach { utxo ->
            SelectionContainer {
                Text(
                    text = stringResource(
                        R.string.dogecoin_utxo_row,
                        shortDogecoinTxid(utxo.txid),
                        utxo.vout,
                        DogecoinAmount.formatKoinu(utxo.amountKoinu),
                        utxo.confirmations
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    lineHeight = 18.sp
                )
            }
        }
        val hiddenCount = utxos.size - limit
        if (hiddenCount > 0) {
            Text(
                text = stringResource(R.string.dogecoin_utxo_more, hiddenCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
internal fun DogecoinActivitySection(
    activity: List<DogecoinWalletActivity>,
    error: String?,
    onCopyTxid: (String) -> Unit,
    onShareTxid: (String) -> Unit,
    onShareTxidToChat: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.dogecoin_activity_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        when {
            error != null -> Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                lineHeight = 18.sp
            )
            activity.isEmpty() -> Text(
                text = stringResource(R.string.dogecoin_activity_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                lineHeight = 18.sp
            )
            else -> activity.take(DOGECOIN_ACTIVITY_PREVIEW_LIMIT).forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SelectionContainer {
                        Text(
                            text = stringResource(
                                R.string.dogecoin_activity_row,
                                item.category,
                                formatSignedDogecoin(item.amountKoinu),
                                shortDogecoinTxid(item.txid),
                                item.confirmations,
                                formatDogecoinActivityTime(item.timeSeconds, stringResource(R.string.unknown))
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                            lineHeight = 18.sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onCopyTxid(item.txid) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.dogecoin_copy_txid))
                        }
                        TextButton(onClick = { onShareTxid(item.txid) }) {
                            Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.dogecoin_share_txid))
                        }
                    }
                    TextButton(onClick = { onShareTxidToChat(item.txid) }) {
                        Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.share_to_chat))
                    }
                }
            }
        }

        val hiddenCount = activity.size - DOGECOIN_ACTIVITY_PREVIEW_LIMIT
        if (hiddenCount > 0) {
            Text(
                text = stringResource(R.string.dogecoin_activity_more, hiddenCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                lineHeight = 18.sp
            )
        }
    }
}
