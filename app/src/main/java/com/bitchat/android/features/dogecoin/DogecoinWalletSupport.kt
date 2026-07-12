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

// Wallet formatters, models, fee helpers, and constants extracted from DogecoinWalletSheet (P2-1, behavior-preserving).
internal fun previewDogecoinHex(value: String, maxChars: Int = 96): String {
    return if (value.length <= maxChars) value else value.take(maxChars) + "..."
}

internal fun shortDogecoinTxid(txid: String): String {
    return if (txid.length <= 18) txid else txid.take(8) + "..." + txid.takeLast(8)
}

internal fun shortDogecoinAddress(address: String): String {
    return if (address.length <= 18) address else address.take(8) + "..." + address.takeLast(8)
}

internal fun formatSignedDogecoin(koinu: Long): String {
    return when {
        koinu > 0 -> "+${DogecoinAmount.formatKoinu(koinu)}"
        koinu < 0 -> "-${DogecoinAmount.formatKoinu(-koinu)}"
        else -> "0"
    }
}

internal fun formatDogecoinActivityTime(timeSeconds: Long?, fallback: String): String {
    if (timeSeconds == null) return fallback
    val millis = runCatching { Math.multiplyExact(timeSeconds, 1000L) }.getOrNull() ?: return fallback
    return SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(millis))
}

internal fun formatDogecoinWalletTime(timeMillis: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(timeMillis))
}

internal data class DogecoinBroadcastReceipt(
    val txid: String,
    val network: DogecoinNetwork,
    val recipientAddress: String,
    val sendAmountKoinu: Long,
    val feeKoinu: Long,
    val changeKoinu: Long,
    val changeAddress: String?,
    val requestLabel: String?,
    val requestMessage: String?,
    val paymentContext: DogepaidPaymentContext? = null,
    val viaPeer: Boolean = false,  // 3b: broadcast was relayed by a peer's node, not this device's node
    // 3b.1: when relayed by a peer, whether two or more helpers independently corroborated it. A single
    // helper's claim (false) is not chain-verified by this device and gets the stronger "verify" warning.
    val peerCorroborated: Boolean = false,
    // Phase 3: SPV self-broadcast was relayed to peers but is not yet chain-confirmed. Renders "Claimed"
    // until the on-chain confirmationDepth poll flips it to false (then it shows as a normal receipt).
    val viaSpvClaimedOnly: Boolean = false
) {
    val totalDebitKoinu: Long
        get() = dogecoinSaturatingAddKoinu(sendAmountKoinu, feeKoinu)
}

internal enum class DogecoinWatchImportAction {
    REFRESH_BALANCE,
    REVIEW_SEND
}

internal enum class DogecoinRawTransactionExportAction {
    COPY,
    SHARE
}

/**
 * Presentation readiness for Review send, keyed to the backend that will actually build the transaction.
 * Broadcast confirmation has its own stricter route-specific gates.
 */
internal fun canReviewDogecoinSend(
    network: DogecoinNetwork,
    effectiveBackend: DogecoinBackend,
    spvSynced: Boolean,
    nodeReady: Boolean
): Boolean = dogecoinSpendRouteAllowed(network, effectiveBackend) &&
    when (effectiveBackend) {
        DogecoinBackend.SPV -> spvSynced
        DogecoinBackend.RPC,
        DogecoinBackend.EXPLORER -> nodeReady
    }

/** Confirm readiness follows the selected route and cannot cross-unlock another backend. */
internal fun canBroadcastDogecoinSend(
    transactionNetwork: DogecoinNetwork,
    selectedNetwork: DogecoinNetwork,
    effectiveBackend: DogecoinBackend,
    spvSynced: Boolean,
    nodeReady: Boolean
): Boolean {
    if (transactionNetwork != selectedNetwork) return false
    if (!dogecoinSpendRouteAllowed(selectedNetwork, effectiveBackend)) return false
    return when (effectiveBackend) {
        DogecoinBackend.SPV -> spvSynced
        DogecoinBackend.RPC,
        DogecoinBackend.EXPLORER -> nodeReady
    }
}

internal enum class DogecoinConfirmationProgressSource {
    SPV,
    RPC
}

/** Confirmation observation follows the effective read/broadcast route, including session-only assist. */
internal fun dogecoinConfirmationProgressSource(
    effectiveBackend: DogecoinBackend
): DogecoinConfirmationProgressSource = when (effectiveBackend) {
    DogecoinBackend.SPV -> DogecoinConfirmationProgressSource.SPV
    DogecoinBackend.RPC,
    DogecoinBackend.EXPLORER -> DogecoinConfirmationProgressSource.RPC
}

/** Pure presentation policy; node progress never depends on SPV sync state. */
internal fun shouldShowDogecoinConfirmingRing(
    effectiveBackend: DogecoinBackend,
    hasActiveReceipt: Boolean,
    confirmationDepth: Int?,
    spvSyncedOrNearTip: Boolean
): Boolean {
    val depth = confirmationDepth ?: return false
    if (!hasActiveReceipt || depth !in 0 until DOGECOIN_SPV_CONFIRM_TARGET) return false
    return when (dogecoinConfirmationProgressSource(effectiveBackend)) {
        DogecoinConfirmationProgressSource.RPC -> true
        DogecoinConfirmationProgressSource.SPV -> spvSyncedOrNearTip
    }
}

/** Presentation-only sync distance. Unknown peer height must never masquerade as a real zero-behind tip. */
internal sealed interface DogecoinSpvBehindLabel {
    data object Starting : DogecoinSpvBehindLabel
    data object FindingPeers : DogecoinSpvBehindLabel
    data class Behind(val blocks: Int) : DogecoinSpvBehindLabel
}

internal fun dogecoinSpvBehindLabel(
    bestPeerHeight: Long,
    chainHeight: Int,
    running: Boolean
): DogecoinSpvBehindLabel = when {
    !running -> DogecoinSpvBehindLabel.Starting
    bestPeerHeight <= 0L -> DogecoinSpvBehindLabel.FindingPeers
    else -> DogecoinSpvBehindLabel.Behind(
        (bestPeerHeight - chainHeight.toLong())
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
    )
}

/** Sync-ring progress only. Unknown peer height stays visibly at the beginning, never a false 97%. */
internal fun dogecoinSpvSyncProgress(label: DogecoinSpvBehindLabel): Float = when (label) {
    DogecoinSpvBehindLabel.Starting,
    DogecoinSpvBehindLabel.FindingPeers -> 0.04f
    is DogecoinSpvBehindLabel.Behind -> {
        val behind = label.blocks.coerceAtLeast(0).toFloat()
        (1f - behind / (behind + 1500f)).coerceIn(0.04f, 0.97f)
    }
}

/** Presentation polling only; this never participates in SPV read/send readiness. */
internal fun shouldPollDogecoinSpvBalance(
    effectiveBackend: DogecoinBackend,
    running: Boolean,
    synced: Boolean,
    balanceKnown: Boolean
): Boolean = effectiveBackend == DogecoinBackend.SPV &&
    running &&
    (!balanceKnown || !synced)

/** Process SPV ownership follows the persisted backend, not session-only home-node assist. */
internal fun dogecoinSpvTargetNetwork(
    persistedBackend: DogecoinBackend,
    selectedNetwork: DogecoinNetwork,
    supported: Boolean
): DogecoinNetwork? = selectedNetwork.takeIf {
    persistedBackend == DogecoinBackend.SPV && supported
}

/**
 * Fail-closed sheet projection: a fully-synced status for another chain is idle for this sheet. This is
 * presentation/readiness state only; service snapshots and broadcast independently require activeNetwork match.
 */
internal fun dogecoinSpvStatusForSelectedNetwork(
    processStatus: DogecoinSpvStatus,
    selectedNetwork: DogecoinNetwork
): DogecoinSpvStatus = if (processStatus.network == selectedNetwork) {
    processStatus
} else {
    DogecoinSpvStatus(network = selectedNetwork)
}

internal enum class DogecoinFeePreset(val labelResId: Int) {
    SLOW(R.string.dogecoin_send_fee_preset_slow),
    NORMAL(R.string.dogecoin_send_fee_preset_normal),
    FAST(R.string.dogecoin_send_fee_preset_fast)
}

internal data class DogecoinFeePresetOption(
    val preset: DogecoinFeePreset,
    val feePerKbKoinu: Long
)

internal fun dogecoinFeePresetOptions(
    minimumFeePerKbKoinu: Long,
    incrementalFeePerKbKoinu: Long?
): List<DogecoinFeePresetOption> {
    val slow = maxOf(DogecoinProtocol.MIN_TX_FEE_KOINU, minimumFeePerKbKoinu)
    val feeStep = incrementalFeePerKbKoinu
        ?.takeIf { it > 0L }
        ?: slow
    val normal = dogecoinSaturatingAddKoinu(slow, feeStep)
    val fast = dogecoinSaturatingAddKoinu(slow, dogecoinSaturatingMultiplyKoinu(feeStep, 2L))
    return listOf(
        DogecoinFeePresetOption(DogecoinFeePreset.SLOW, slow),
        DogecoinFeePresetOption(DogecoinFeePreset.NORMAL, maxOf(slow, normal)),
        DogecoinFeePresetOption(DogecoinFeePreset.FAST, maxOf(normal, fast))
    )
}

internal fun dogecoinSaturatingMultiplyKoinu(left: Long, right: Long): Long {
    require(left >= 0L && right >= 0L) { "Dogecoin amounts must be non-negative" }
    return try {
        Math.multiplyExact(left, right)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}

/** User-facing reason a configured node endpoint is refused by the trust classifier (zero-I/O paths). */
internal fun dogecoinEndpointBlockedReason(
    endpointClass: DogecoinRpcEndpointClass,
    context: android.content.Context
): String = when (endpointClass) {
    DogecoinRpcEndpointClass.ONION_DIRECT ->
        context.getString(R.string.dogecoin_rpc_endpoint_onion_blocked)
    DogecoinRpcEndpointClass.PUBLIC_HTTPS_UNVERIFIED ->
        context.getString(R.string.dogecoin_rpc_endpoint_public_https_blocked)
    else ->
        context.getString(R.string.dogecoin_rpc_endpoint_invalid_blocked)
}

internal const val DOGECOIN_UTXO_PREVIEW_LIMIT = 5
internal const val DOGECOIN_CONFIRM_UTXO_PREVIEW_LIMIT = 3
internal const val DOGECOIN_ACTIVITY_PREVIEW_LIMIT = 5
// Pending cards shown on the main screen; the rest fold into "View all activity". Full list is capped so a
// long history can't balloon a single LazyColumn item.
internal const val DOGECOIN_PENDING_CARDS_LIMIT = 4
internal const val DOGECOIN_ACTIVITY_FULL_LIMIT = 50
internal const val DOGECOIN_SEND_ITEM_INDEX = 6
internal const val DOGECOIN_SIGNED_TX_MAX_AGE_MILLIS = 10L * 60L * 1000L
internal const val DOGECOIN_SPV_CORROBORATION_POLLS = 40
internal const val DOGECOIN_SPV_CORROBORATION_INTERVAL_MS = 15_000L
internal const val DOGECOIN_SPV_BALANCE_REFRESH_INTERVAL_MS = 12_000L
// Focal-ring confirmation fill (presentation-only): how many confirmations the ring shows as "done" (a full
// gold ring), and the poll budget before a never-confirming tx reverts the ring to the idle balance.
internal const val DOGECOIN_SPV_CONFIRM_TARGET = 6
internal const val DOGECOIN_SPV_CONFIRM_POLLS = 80
// Active node-path sends poll quickly enough to expose intermediate confirmations. At normal response latency,
// 300 attempts at this cadence roughly match the existing SPV poll window; the attempt budget is strictly bounded.
internal const val DOGECOIN_RPC_CONFIRM_POLLS = 300
internal const val DOGECOIN_RPC_CONFIRM_INTERVAL_MS = 4_000L
// Presentation-only: show the confirmation fill (not "Syncing") for a pending tx while within this many blocks
// of the tip, so a momentary `synced` flap (peer dip / a fresh block) can't mask it. confirmationDepth is read
// from our own chain head, so it stays accurate this close to the tip.
internal const val DOGECOIN_SPV_NEARTIP_BLOCKS = 6
