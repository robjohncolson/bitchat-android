package com.bitchat.android.features.dogecoin

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DogecoinWalletSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    onShareToChat: (String) -> Unit,
    onAdvertisedAddressChanged: () -> Unit = {},
    onHelperEnabledChanged: () -> Unit = {},
    onRequestPeerBroadcast: (DogecoinSignedTransaction) -> Unit = {},
    peerBroadcastState: PeerBroadcastUiState = PeerBroadcastUiState.Idle,
    hasHelperCandidate: Boolean = false,
    onClearPeerBroadcast: () -> Unit = {},
    paymentRequest: DogecoinPaymentRequest? = null,
    modifier: Modifier = Modifier
) {
    if (!isPresented) return

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val repository = remember(context) { DogecoinWalletRepository(context) }
    val rpcClient = remember { DogecoinRpcClient() }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // Only mainnet and testnet are user-selectable in release builds. Debug builds also expose
    // regtest for on-device testing against a local regtest node (REGTEST is never shown in release).
    val networks = remember {
        if (com.bitchat.android.BuildConfig.DEBUG) {
            listOf(DogecoinNetwork.MAINNET, DogecoinNetwork.TESTNET, DogecoinNetwork.REGTEST)
        } else {
            listOf(DogecoinNetwork.MAINNET, DogecoinNetwork.TESTNET)
        }
    }

    var snapshot by remember { mutableStateOf(repository.loadOrCreateWallet()) }
    var selectedNetwork by remember { mutableStateOf(snapshot.key.network) }
    var wifCopyState by remember { mutableStateOf(repository.loadWifCopyState(snapshot.key)) }
    var practiceNudgeDismissed by remember { mutableStateOf(repository.loadPracticeNudgeDismissed()) }
    var advertiseAddressEnabled by remember { mutableStateOf(repository.loadAdvertiseAddressEnabled()) }
    var helperEnabled by remember(selectedNetwork) { mutableStateOf(repository.loadHelperEnabled(selectedNetwork)) }
    var helperFavoritesOnly by remember { mutableStateOf(repository.loadHelperFavoritesOnly()) }
    var helperMainnetConsent by remember(selectedNetwork) { mutableStateOf(false) }
    var peerBroadcastAck by remember { mutableStateOf(false) }
    var savedAddresses by remember { mutableStateOf(repository.loadSavedAddresses(snapshot.key.network)) }
    var rpcUrl by remember { mutableStateOf(snapshot.rpcConfig.url) }
    var rpcUsername by remember { mutableStateOf(snapshot.rpcConfig.username) }
    var rpcPassword by remember { mutableStateOf(snapshot.rpcConfig.password) }
    var rpcWalletName by remember { mutableStateOf(snapshot.rpcConfig.walletName) }
    var rpcConfigRevision by remember { mutableStateOf(0) }
    var rpcConfigNeedsRecheck by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("bitchat") }
    var requestMessage by remember { mutableStateOf("") }
    var nodeStatus by remember { mutableStateOf<DogecoinNodeStatus?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var walletBalance by remember { mutableStateOf<DogecoinWalletBalance?>(null) }
    var walletBalanceError by remember { mutableStateOf<String?>(null) }
    var addressWatchStatus by remember { mutableStateOf<DogecoinAddressWatchStatus?>(null) }
    var addressWatchStatusError by remember { mutableStateOf<String?>(null) }
    var walletActivity by remember { mutableStateOf<List<DogecoinWalletActivity>>(emptyList()) }
    var walletActivityError by remember { mutableStateOf<String?>(null) }
    var refreshingBalance by remember { mutableStateOf(false) }
    var rescanning by remember { mutableStateOf(false) }
    var rescanError by remember { mutableStateOf<String?>(null) }
    var sendAddress by remember { mutableStateOf("") }
    var sendAmount by remember { mutableStateOf("") }
    var sendFeeRate by remember {
        mutableStateOf(DogecoinAmount.formatKoinu(DogecoinProtocol.DEFAULT_FEE_PER_KB_KOINU))
    }
    var sendFeePreset by remember { mutableStateOf(DogecoinFeePreset.NORMAL) }
    var showAdvancedFee by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var sentReceipt by remember { mutableStateOf<DogecoinBroadcastReceipt?>(null) }
    var pendingTransaction by remember { mutableStateOf<DogecoinSignedTransaction?>(null) }
    var mainnetBroadcastAcknowledged by remember { mutableStateOf(false) }
    var highFeeAcknowledged by remember { mutableStateOf(false) }
    var policyUnavailableAcknowledged by remember { mutableStateOf(false) }
    var pendingWifCopy by remember { mutableStateOf<DogecoinWalletKey?>(null) }
    var mainnetWifBackupAcknowledged by remember { mutableStateOf(false) }
    var pendingResetNetwork by remember { mutableStateOf<DogecoinNetwork?>(null) }
    var pendingWatchImportAction by remember { mutableStateOf<DogecoinWatchImportAction?>(null) }
    var pendingRescanNetwork by remember { mutableStateOf<DogecoinNetwork?>(null) }
    var rescanStartHeightInput by remember { mutableStateOf("") }
    var importWif by remember { mutableStateOf("") }
    var importWifRevealed by remember { mutableStateOf(false) }
    var importWifError by remember { mutableStateOf<String?>(null) }
    var pendingImportKey by remember { mutableStateOf<DogecoinWalletKey?>(null) }
    var mainnetWifImportAcknowledged by remember { mutableStateOf(false) }
    var paymentRequestLabel by remember { mutableStateOf<String?>(null) }
    var paymentRequestMessage by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var calculatingMaxSend by remember { mutableStateOf(false) }
    var exportingRawTransaction by remember { mutableStateOf(false) }
    var scanningPaymentQr by remember { mutableStateOf(false) }
    var scanningWifQr by remember { mutableStateOf(false) }
    var qrScanError by remember { mutableStateOf<String?>(null) }
    var wifScanError by remember { mutableStateOf<String?>(null) }
    var showNodeHelp by remember { mutableStateOf(false) }
    val rpcUrlBlank = rpcUrl.trim().isEmpty()
    val rpcUrlValid = remember(rpcUrl, selectedNetwork) {
        !rpcUrlBlank && DogecoinRpcConfig(url = rpcUrl).hasValidUrl(selectedNetwork)
    }
    val nodeReady = nodeStatus?.isReadyFor(selectedNetwork) == true
    val broadcastNodeReady = nodeStatus?.canBroadcastFor(selectedNetwork) == true
    val canRescanWalletHistory = nodeStatus?.supportsHistoricalRescanFor(selectedNetwork) == true
    val usesImportAddressHistoricalRescan = selectedNetwork == DogecoinNetwork.MAINNET &&
        canRescanWalletHistory &&
        nodeStatus?.rescanBlockchainAvailable == false
    val addressAlreadyImported = addressWatchStatus?.isImported == true
    val shouldConfirmImportingRefresh = usesImportAddressHistoricalRescan &&
        walletBalance == null &&
        !addressAlreadyImported
    val usableNodeStatus = nodeStatus?.takeIf { it.isUsableFor(selectedNetwork) }
    val minimumSendFeePerKbKoinu = maxOf(
        DogecoinProtocol.MIN_TX_FEE_KOINU,
        usableNodeStatus
            ?.relayFeePerKbKoinu
            ?: DogecoinProtocol.MIN_TX_FEE_KOINU
    )
    val minimumSendOutputKoinu = dogecoinEffectiveStandardOutputKoinu(usableNodeStatus?.softDustLimitKoinu)
    val wifCopyRecorded = wifCopyState.matches(snapshot.key)

    SecureWindowFlagEffect(enabled = pendingWifCopy != null || importWifRevealed)
    val sendFeePresets = remember(
        minimumSendFeePerKbKoinu,
        usableNodeStatus?.incrementalFeePerKbKoinu
    ) {
        dogecoinFeePresetOptions(
            minimumFeePerKbKoinu = minimumSendFeePerKbKoinu,
            incrementalFeePerKbKoinu = usableNodeStatus?.incrementalFeePerKbKoinu
        )
    }
    val selectedFeePresetRateKoinu = sendFeePresets.firstOrNull { it.preset == sendFeePreset }
        ?.feePerKbKoinu
        ?: minimumSendFeePerKbKoinu

    fun currentRpcConfig(): DogecoinRpcConfig {
        return DogecoinRpcConfig(
            url = rpcUrl,
            username = rpcUsername,
            password = rpcPassword,
            walletName = rpcWalletName
        ).normalized(selectedNetwork)
    }

    fun persistRpcConfig() {
        repository.saveRpcConfig(selectedNetwork, currentRpcConfig())
    }

    fun isValidSelectedFeeRate(value: String): Boolean {
        if (!DogecoinAmount.isValidAmount(value)) return false
        return DogecoinAmount.toKoinu(value) >= minimumSendFeePerKbKoinu
    }

    fun isValidSelectedSendAmount(value: String): Boolean {
        if (!DogecoinAmount.isValidAmount(value)) return false
        return DogecoinAmount.toKoinu(value) >= minimumSendOutputKoinu
    }

    fun invalidateRpcRuntimeState() {
        rpcConfigRevision += 1
        rpcConfigNeedsRecheck = true
        nodeStatus = null
        refreshing = false
        walletBalanceError = null
        addressWatchStatus = null
        addressWatchStatusError = null
        walletActivityError = null
        refreshingBalance = false
        rescanning = false
        rescanError = null
        sendError = null
        sentReceipt = null
        pendingTransaction = null
        mainnetBroadcastAcknowledged = false
        highFeeAcknowledged = false
        policyUnavailableAcknowledged = false
        calculatingMaxSend = false
        exportingRawTransaction = false
        pendingWatchImportAction = null
    }

    fun updateRpcUrl(value: String) {
        if (rpcUrl == value) return
        rpcUrl = value
        invalidateRpcRuntimeState()
        persistRpcConfig()
    }

    fun updateRpcUsername(value: String) {
        if (rpcUsername == value) return
        val parsedAuth = if (rpcPassword.isBlank()) parseDogecoinRpcAuthToken(value) else null
        if (parsedAuth == null) {
            rpcUsername = value
        } else {
            rpcUsername = parsedAuth.first
            rpcPassword = parsedAuth.second
        }
        invalidateRpcRuntimeState()
        persistRpcConfig()
    }

    fun updateRpcPassword(value: String) {
        if (rpcPassword == value) return
        rpcPassword = value
        invalidateRpcRuntimeState()
        persistRpcConfig()
    }

    fun updateRpcWalletName(value: String) {
        if (rpcWalletName == value) return
        rpcWalletName = value
        invalidateRpcRuntimeState()
        persistRpcConfig()
    }

    fun switchNetwork(network: DogecoinNetwork) {
        if (network == selectedNetwork) return

        repository.saveRpcConfig(selectedNetwork, currentRpcConfig())
        repository.saveSelectedNetwork(network)
        selectedNetwork = network
        rpcConfigRevision += 1
        rpcConfigNeedsRecheck = false

        val nextSnapshot = repository.loadOrCreateWallet(network)
        snapshot = nextSnapshot
        wifCopyState = repository.loadWifCopyState(nextSnapshot.key)
        practiceNudgeDismissed = repository.loadPracticeNudgeDismissed()
        savedAddresses = repository.loadSavedAddresses(network)
        rpcUrl = nextSnapshot.rpcConfig.url
        rpcUsername = nextSnapshot.rpcConfig.username
        rpcPassword = nextSnapshot.rpcConfig.password
        rpcWalletName = nextSnapshot.rpcConfig.walletName
        nodeStatus = null
        refreshing = false
        walletBalance = null
        walletBalanceError = null
        addressWatchStatus = null
        addressWatchStatusError = null
        walletActivity = emptyList()
        walletActivityError = null
        refreshingBalance = false
        rescanning = false
        rescanError = null
        sendAddress = ""
        sendAmount = ""
        sendFeeRate = DogecoinAmount.formatKoinu(DogecoinProtocol.DEFAULT_FEE_PER_KB_KOINU)
        sendFeePreset = DogecoinFeePreset.NORMAL
        showAdvancedFee = false
        sendError = null
        sentReceipt = null
        pendingTransaction = null
        mainnetBroadcastAcknowledged = false
        highFeeAcknowledged = false
        policyUnavailableAcknowledged = false
        pendingWifCopy = null
        mainnetWifBackupAcknowledged = false
        pendingResetNetwork = null
        pendingWatchImportAction = null
        pendingRescanNetwork = null
        rescanStartHeightInput = ""
        importWif = ""
        importWifRevealed = false
        importWifError = null
        pendingImportKey = null
        mainnetWifImportAcknowledged = false
        paymentRequestLabel = null
        paymentRequestMessage = null
        sending = false
        calculatingMaxSend = false
        exportingRawTransaction = false
        scanningPaymentQr = false
        scanningWifQr = false
        qrScanError = null
        wifScanError = null
        peerBroadcastAck = false
        onClearPeerBroadcast()
        onAdvertisedAddressChanged()
    }

    fun clearWalletRuntimeState() {
        nodeStatus = null
        rpcConfigNeedsRecheck = false
        refreshing = false
        walletBalance = null
        walletBalanceError = null
        walletActivity = emptyList()
        walletActivityError = null
        refreshingBalance = false
        rescanning = false
        rescanError = null
        sendAddress = ""
        sendAmount = ""
        sendFeeRate = DogecoinAmount.formatKoinu(DogecoinProtocol.DEFAULT_FEE_PER_KB_KOINU)
        sendFeePreset = DogecoinFeePreset.NORMAL
        showAdvancedFee = false
        sendError = null
        sentReceipt = null
        pendingTransaction = null
        mainnetBroadcastAcknowledged = false
        highFeeAcknowledged = false
        policyUnavailableAcknowledged = false
        rescanStartHeightInput = ""
        importWifRevealed = false
        paymentRequestLabel = null
        paymentRequestMessage = null
        sending = false
        calculatingMaxSend = false
        exportingRawTransaction = false
        scanningPaymentQr = false
        scanningWifQr = false
        qrScanError = null
        wifScanError = null
    }

    suspend fun refreshAddressWatchStatusFromNode(
        config: DogecoinRpcConfig,
        address: String,
        network: DogecoinNetwork,
        configRevision: Int
    ) {
        runCatching {
            rpcClient.getAddressWatchStatus(config, address, network)
        }.onSuccess { watchStatus ->
            if (
                selectedNetwork == network &&
                snapshot.key.address == address &&
                rpcConfigRevision == configRevision
            ) {
                addressWatchStatus = watchStatus
                addressWatchStatusError = null
            }
        }.onFailure { watchError ->
            if (
                selectedNetwork == network &&
                snapshot.key.address == address &&
                rpcConfigRevision == configRevision
            ) {
                addressWatchStatusError = watchError.message
            }
        }
    }

    fun reviewImportWif(rawWif: String = importWif, onInvalid: (String) -> Unit = { importWifError = it }) {
        val cleanWif = rawWif.trim()
        importWifError = null
        pendingImportKey = null
        mainnetWifImportAcknowledged = false
        runCatching {
            DogecoinKeyGenerator.fromWif(cleanWif, expectedNetwork = selectedNetwork)
        }.onSuccess {
            importWif = cleanWif
            pendingImportKey = it
        }.onFailure {
            onInvalid(it.message ?: context.getString(R.string.dogecoin_import_wif_invalid))
        }
    }

    fun refreshNodeStatus() {
        if (rpcUrlBlank) {
            nodeStatus = null
            refreshing = false
            return
        }
        if (!rpcUrlValid) {
            nodeStatus = DogecoinNodeStatus(
                connected = false,
                expectedNetwork = selectedNetwork,
                error = context.getString(R.string.dogecoin_rpc_url_invalid)
            )
            return
        }

        refreshing = true
        val network = selectedNetwork
        val address = snapshot.key.address
        val config = currentRpcConfig()
        val configRevision = rpcConfigRevision
        repository.saveRpcConfig(network, config)
        coroutineScope.launch {
            val status = rpcClient.getBlockchainStatus(config, network)
            val watchStatusResult = if (status.isReadyFor(network)) {
                runCatching { rpcClient.getAddressWatchStatus(config, address, network) }
            } else {
                null
            }
            if (selectedNetwork == network && rpcConfigRevision == configRevision) {
                nodeStatus = status
                rpcConfigNeedsRecheck = false
                if (snapshot.key.address == address) {
                    addressWatchStatus = watchStatusResult?.getOrNull()
                    addressWatchStatusError = watchStatusResult?.exceptionOrNull()?.message
                }
                refreshing = false
            }
        }
    }

    fun refreshWalletBalance() {
        refreshingBalance = true
        walletBalanceError = null
        walletActivityError = null
        rescanError = null
        val network = selectedNetwork
        val address = snapshot.key.address
        val config = currentRpcConfig()
        val configRevision = rpcConfigRevision
        repository.saveRpcConfig(network, config)
        coroutineScope.launch {
            runCatching {
                rpcClient.getWalletBalance(config, address, network)
            }.onSuccess {
                if (
                    selectedNetwork == network &&
                    snapshot.key.address == address &&
                    rpcConfigRevision == configRevision
                ) {
                    walletBalance = it
                    refreshAddressWatchStatusFromNode(config, address, network, configRevision)
                    runCatching {
                        rpcClient.getWalletActivity(config, address, network)
                    }.onSuccess { activity ->
                        if (
                            selectedNetwork == network &&
                            snapshot.key.address == address &&
                            rpcConfigRevision == configRevision
                        ) {
                            walletActivity = activity
                        }
                    }.onFailure { activityError ->
                        if (
                            selectedNetwork == network &&
                            snapshot.key.address == address &&
                            rpcConfigRevision == configRevision
                        ) {
                            walletActivity = emptyList()
                            walletActivityError = activityError.message
                                ?: context.getString(R.string.dogecoin_activity_unavailable)
                        }
                    }
                }
            }.onFailure {
                if (
                    selectedNetwork == network &&
                    snapshot.key.address == address &&
                    rpcConfigRevision == configRevision
                ) {
                    walletBalanceError = it.message ?: context.getString(R.string.dogecoin_balance_unavailable)
                    addressWatchStatus = null
                    addressWatchStatusError = null
                    walletActivity = emptyList()
                    walletActivityError = null
                }
            }
            if (
                selectedNetwork == network &&
                snapshot.key.address == address &&
                rpcConfigRevision == configRevision
            ) {
                refreshingBalance = false
            }
        }
    }

    fun rescanWalletHistory(network: DogecoinNetwork, startHeight: Int?) {
        if (network != selectedNetwork) return
        if (nodeStatus?.supportsHistoricalRescanFor(network) != true) {
            rescanError = context.getString(R.string.dogecoin_rescan_pruned_unavailable)
            return
        }

        rescanning = true
        walletBalanceError = null
        walletActivityError = null
        rescanError = null
        val address = snapshot.key.address
        val config = currentRpcConfig()
        val configRevision = rpcConfigRevision
        repository.saveRpcConfig(network, config)
        coroutineScope.launch {
            runCatching {
                rpcClient.rescanWalletHistory(config, address, network, startHeight)
                val balance = rpcClient.getWalletBalance(config, address, network)
                val watchStatus = runCatching {
                    rpcClient.getAddressWatchStatus(config, address, network)
                }
                val activity = runCatching {
                    rpcClient.getWalletActivity(config, address, network)
                }
                Triple(balance, watchStatus, activity)
            }.onSuccess {
                if (
                    selectedNetwork == network &&
                    snapshot.key.address == address &&
                    rpcConfigRevision == configRevision
                ) {
                    walletBalance = it.first
                    it.second.onSuccess { watchStatus ->
                        addressWatchStatus = watchStatus
                        addressWatchStatusError = null
                    }.onFailure { watchError ->
                        addressWatchStatusError = watchError.message
                    }
                    it.third.onSuccess { activity ->
                        walletActivity = activity
                    }.onFailure { activityError ->
                        walletActivity = emptyList()
                        walletActivityError = activityError.message
                            ?: context.getString(R.string.dogecoin_activity_unavailable)
                    }
                    Toast.makeText(
                        context,
                        context.getString(R.string.dogecoin_rescan_complete),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.onFailure {
                if (
                    selectedNetwork == network &&
                    snapshot.key.address == address &&
                    rpcConfigRevision == configRevision
                ) {
                    rescanError = it.message ?: context.getString(R.string.dogecoin_rescan_failed)
                }
            }
            if (
                selectedNetwork == network &&
                snapshot.key.address == address &&
                rpcConfigRevision == configRevision
            ) {
                rescanning = false
            }
        }
    }

    fun reviewSend(allowWatchImport: Boolean = false) {
        val recipient = sendAddress.trim()
        val dogeAmount = sendAmount.trim()
        val feeRate = sendFeeRate.trim()
        sendError = null
        sentReceipt = null

        if (!DogecoinAddress.isValidAddress(recipient, selectedNetwork)) {
            sendError = context.getString(R.string.dogecoin_send_invalid_address)
            return
        }
        if (!DogecoinAmount.isValidAmount(dogeAmount)) {
            sendError = context.getString(R.string.dogecoin_send_invalid_amount)
            return
        }
        if (!isValidSelectedSendAmount(dogeAmount)) {
            sendError = context.getString(
                R.string.dogecoin_send_amount_too_small,
                DogecoinAmount.formatKoinu(minimumSendOutputKoinu)
            )
            return
        }
        if (!isValidSelectedFeeRate(feeRate)) {
            sendError = context.getString(
                R.string.dogecoin_send_invalid_fee_rate,
                DogecoinAmount.formatKoinu(minimumSendFeePerKbKoinu)
            )
            return
        }
        if (selectedNetwork == DogecoinNetwork.MAINNET && !wifCopyState.matches(snapshot.key)) {
            sendError = context.getString(R.string.dogecoin_send_backup_required)
            return
        }
        if (shouldConfirmImportingRefresh && !allowWatchImport) {
            pendingWatchImportAction = DogecoinWatchImportAction.REVIEW_SEND
            return
        }

        sending = true
        val network = selectedNetwork
        val wallet = snapshot.key
        val config = currentRpcConfig()
        val configRevision = rpcConfigRevision
        val feePerKbKoinu = DogecoinAmount.toKoinu(feeRate)
        val minimumOutputKoinu = minimumSendOutputKoinu
        val requestLabel = paymentRequestLabel
        val requestMessage = paymentRequestMessage
        repository.saveRpcConfig(network, config)
        coroutineScope.launch {
            runCatching {
                val utxos = rpcClient.listUnspent(config, wallet.address, network)
                refreshAddressWatchStatusFromNode(config, wallet.address, network, configRevision)
                val signedTransaction = DogecoinTransactionBuilder.createSignedTransaction(
                    wallet = wallet,
                    utxos = utxos,
                    recipientAddress = recipient,
                    amount = dogeAmount,
                    network = network,
                    feePerKbKoinu = feePerKbKoinu,
                    minimumOutputKoinu = minimumOutputKoinu
                )
                val mempoolAcceptance = rpcClient.testMempoolAcceptance(
                    config = config,
                    rawTransactionHex = signedTransaction.rawTransactionHex,
                    network = network
                )
                check(!mempoolAcceptance.checked || mempoolAcceptance.allowed == true) {
                    mempoolAcceptance.error ?: context.getString(R.string.dogecoin_send_policy_rejected)
                }
                signedTransaction.copy(
                    mempoolAcceptance = mempoolAcceptance,
                    requestLabel = requestLabel,
                    requestMessage = requestMessage
                )
            }.onSuccess {
                if (
                    selectedNetwork == network &&
                    snapshot.key.address == wallet.address &&
                    rpcConfigRevision == configRevision
                ) {
                    mainnetBroadcastAcknowledged = false
                    highFeeAcknowledged = false
                    policyUnavailableAcknowledged = false
                    pendingTransaction = it
                }
            }.onFailure {
                if (
                    selectedNetwork == network &&
                    snapshot.key.address == wallet.address &&
                    rpcConfigRevision == configRevision
                ) {
                    sendError = it.message ?: context.getString(R.string.dogecoin_send_failed)
                }
            }
            if (
                selectedNetwork == network &&
                snapshot.key.address == wallet.address &&
                rpcConfigRevision == configRevision
            ) {
                sending = false
            }
        }
    }

    fun useMaxSendAmount(allowWatchImport: Boolean = false) {
        val feeRate = sendFeeRate.trim()
        sendError = null
        sentReceipt = null

        if (!isValidSelectedFeeRate(feeRate)) {
            sendError = context.getString(
                R.string.dogecoin_send_invalid_fee_rate,
                DogecoinAmount.formatKoinu(minimumSendFeePerKbKoinu)
            )
            return
        }
        if (shouldConfirmImportingRefresh && !allowWatchImport) {
            pendingWatchImportAction = DogecoinWatchImportAction.USE_MAX_SEND
            return
        }

        calculatingMaxSend = true
        val network = selectedNetwork
        val wallet = snapshot.key
        val config = currentRpcConfig()
        val configRevision = rpcConfigRevision
        val feePerKbKoinu = DogecoinAmount.toKoinu(feeRate)
        val minimumOutputKoinu = minimumSendOutputKoinu
        repository.saveRpcConfig(network, config)
        coroutineScope.launch {
            runCatching {
                val utxos = rpcClient.listUnspent(config, wallet.address, network)
                refreshAddressWatchStatusFromNode(config, wallet.address, network, configRevision)
                DogecoinTransactionBuilder.maxSpendable(
                    wallet = wallet,
                    utxos = utxos,
                    network = network,
                    feePerKbKoinu = feePerKbKoinu,
                    minimumOutputKoinu = minimumOutputKoinu
                )
            }.onSuccess { maxSpend ->
                if (
                    selectedNetwork == network &&
                    snapshot.key.address == wallet.address &&
                    rpcConfigRevision == configRevision
                ) {
                    if (maxSpend.amountKoinu < minimumOutputKoinu) {
                        sendError = context.getString(
                            R.string.dogecoin_send_amount_too_small,
                            DogecoinAmount.formatKoinu(minimumOutputKoinu)
                        )
                    } else {
                        sendAmount = DogecoinAmount.formatKoinu(maxSpend.amountKoinu)
                        paymentRequestLabel = null
                        paymentRequestMessage = null
                        pendingTransaction = null
                        mainnetBroadcastAcknowledged = false
                        highFeeAcknowledged = false
                        policyUnavailableAcknowledged = false
                        Toast.makeText(
                            context,
                            context.getString(R.string.dogecoin_send_max_loaded),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }.onFailure {
                if (
                    selectedNetwork == network &&
                    snapshot.key.address == wallet.address &&
                    rpcConfigRevision == configRevision
                ) {
                    sendError = it.message ?: context.getString(R.string.dogecoin_send_max_failed)
                }
            }
            if (
                selectedNetwork == network &&
                snapshot.key.address == wallet.address &&
                rpcConfigRevision == configRevision
            ) {
                calculatingMaxSend = false
            }
        }
    }

    fun broadcastSignedTransaction(transaction: DogecoinSignedTransaction) {
        val nowMillis = System.currentTimeMillis()
        if (transaction.network != selectedNetwork) {
            pendingTransaction = null
            mainnetBroadcastAcknowledged = false
            highFeeAcknowledged = false
            policyUnavailableAcknowledged = false
            sendError = context.getString(R.string.dogecoin_send_network_changed)
            return
        }
        if (transaction.isExpired(nowMillis, DOGECOIN_SIGNED_TX_MAX_AGE_MILLIS)) {
            pendingTransaction = null
            mainnetBroadcastAcknowledged = false
            highFeeAcknowledged = false
            policyUnavailableAcknowledged = false
            sendError = context.getString(R.string.dogecoin_send_review_expired)
            return
        }
        if (!transaction.hasConsistentRawTransactionId()) {
            pendingTransaction = null
            mainnetBroadcastAcknowledged = false
            highFeeAcknowledged = false
            policyUnavailableAcknowledged = false
            sendError = context.getString(R.string.dogecoin_send_raw_txid_mismatch)
            return
        }
        if (
            !canExportOrBroadcastSignedDogecoinTransaction(
                transaction = transaction,
                nowMillis = nowMillis,
                maxAgeMillis = DOGECOIN_SIGNED_TX_MAX_AGE_MILLIS,
                mainnetAcknowledged = mainnetBroadcastAcknowledged,
                highFeeAcknowledged = highFeeAcknowledged,
                policyUnavailableAcknowledged = policyUnavailableAcknowledged
            )
        ) {
            sendError = when {
                transaction.network == DogecoinNetwork.MAINNET && !mainnetBroadcastAcknowledged ->
                    context.getString(R.string.dogecoin_send_mainnet_ack_required)
                transaction.requiresPolicyUnavailableAcknowledgement() && !policyUnavailableAcknowledged ->
                    context.getString(R.string.dogecoin_send_policy_unavailable_ack_required)
                else -> context.getString(R.string.dogecoin_send_high_fee_ack_required)
            }
            return
        }

        sending = true
        sendError = null
        val network = selectedNetwork
        val address = snapshot.key.address
        val config = currentRpcConfig()
        val configRevision = rpcConfigRevision
        repository.saveRpcConfig(network, config)
        coroutineScope.launch {
            runCatching {
                transaction.requireConsistentRawTransactionId()
                val currentUtxos = rpcClient.listUnspent(config, address, network)
                transaction.requireSelectedInputsStillSpendable(currentUtxos)
                refreshAddressWatchStatusFromNode(config, address, network, configRevision)
                rpcClient.sendRawTransaction(config, transaction.rawTransactionHex, network)
            }.onSuccess { txid ->
                if (
                    selectedNetwork == network &&
                    snapshot.key.address == address &&
                    rpcConfigRevision == configRevision
                ) {
                    pendingTransaction = null
                    mainnetBroadcastAcknowledged = false
                    highFeeAcknowledged = false
                    policyUnavailableAcknowledged = false
                    sentReceipt = DogecoinBroadcastReceipt(
                        txid = txid,
                        network = transaction.network,
                        recipientAddress = transaction.recipientAddress,
                        sendAmountKoinu = transaction.sendAmountKoinu,
                        feeKoinu = transaction.feeKoinu,
                        changeKoinu = transaction.changeKoinu,
                        changeAddress = transaction.changeAddress,
                        requestLabel = transaction.requestLabel,
                        requestMessage = transaction.requestMessage
                    )
                    sendAmount = ""
                    Toast.makeText(
                        context,
                        context.getString(R.string.dogecoin_transaction_broadcast),
                        Toast.LENGTH_SHORT
                    ).show()
                    runCatching {
                        rpcClient.getWalletBalance(config, address, network)
                    }.onSuccess { balance ->
                        if (
                            selectedNetwork == network &&
                            snapshot.key.address == address &&
                            rpcConfigRevision == configRevision
                        ) {
                            walletBalance = balance
                            walletBalanceError = null
                            refreshAddressWatchStatusFromNode(config, address, network, configRevision)
                            runCatching {
                                rpcClient.getWalletActivity(config, address, network)
                            }.onSuccess { activity ->
                                if (
                                    selectedNetwork == network &&
                                    snapshot.key.address == address &&
                                    rpcConfigRevision == configRevision
                                ) {
                                    walletActivity = activity
                                    walletActivityError = null
                                }
                            }.onFailure { activityError ->
                                if (
                                    selectedNetwork == network &&
                                    snapshot.key.address == address &&
                                    rpcConfigRevision == configRevision
                                ) {
                                    walletActivity = emptyList()
                                    walletActivityError = activityError.message
                                        ?: context.getString(R.string.dogecoin_activity_unavailable)
                                }
                            }
                        }
                    }
                }
            }.onFailure {
                if (
                    selectedNetwork == network &&
                    snapshot.key.address == address &&
                    rpcConfigRevision == configRevision
                ) {
                    sendError = it.message ?: context.getString(R.string.dogecoin_send_failed)
                }
            }
            if (
                selectedNetwork == network &&
                snapshot.key.address == address &&
                rpcConfigRevision == configRevision
            ) {
                sending = false
            }
        }
    }

    fun copy(text: String, message: String) {
        clipboardManager.setText(AnnotatedString(text))
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun shareExternal(text: String, title: String, failureMessage: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(sendIntent, title)
        runCatching {
            context.startActivity(chooser)
        }.onFailure {
            Toast.makeText(
                context,
                failureMessage,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun exportSignedRawTransaction(
        transaction: DogecoinSignedTransaction,
        action: DogecoinRawTransactionExportAction
    ) {
        val nowMillis = System.currentTimeMillis()
        if (transaction.network != selectedNetwork) {
            pendingTransaction = null
            mainnetBroadcastAcknowledged = false
            highFeeAcknowledged = false
            policyUnavailableAcknowledged = false
            sendError = context.getString(R.string.dogecoin_send_network_changed)
            return
        }
        if (transaction.isExpired(nowMillis, DOGECOIN_SIGNED_TX_MAX_AGE_MILLIS)) {
            pendingTransaction = null
            mainnetBroadcastAcknowledged = false
            highFeeAcknowledged = false
            policyUnavailableAcknowledged = false
            sendError = context.getString(R.string.dogecoin_send_review_expired)
            return
        }
        if (!transaction.hasConsistentRawTransactionId()) {
            pendingTransaction = null
            mainnetBroadcastAcknowledged = false
            highFeeAcknowledged = false
            policyUnavailableAcknowledged = false
            sendError = context.getString(R.string.dogecoin_send_raw_txid_mismatch)
            return
        }
        if (
            !canExportSignedRawDogecoinTransaction(
                transaction = transaction,
                nowMillis = nowMillis,
                maxAgeMillis = DOGECOIN_SIGNED_TX_MAX_AGE_MILLIS,
                mainnetAcknowledged = mainnetBroadcastAcknowledged,
                highFeeAcknowledged = highFeeAcknowledged,
                policyUnavailableAcknowledged = policyUnavailableAcknowledged,
                selectedNetwork = selectedNetwork,
                nodeReady = nodeReady
            )
        ) {
            sendError = when {
                !nodeReady -> context.getString(R.string.dogecoin_send_raw_export_node_required)
                transaction.network == DogecoinNetwork.MAINNET && !mainnetBroadcastAcknowledged ->
                    context.getString(R.string.dogecoin_send_mainnet_ack_required)
                transaction.requiresPolicyUnavailableAcknowledgement() && !policyUnavailableAcknowledged ->
                    context.getString(R.string.dogecoin_send_policy_unavailable_ack_required)
                else -> context.getString(R.string.dogecoin_send_high_fee_ack_required)
            }
            return
        }

        exportingRawTransaction = true
        sendError = null
        val network = selectedNetwork
        val address = snapshot.key.address
        val config = currentRpcConfig()
        val configRevision = rpcConfigRevision
        repository.saveRpcConfig(network, config)
        coroutineScope.launch {
            runCatching {
                transaction.requireConsistentRawTransactionId()
                val currentUtxos = rpcClient.listUnspent(config, address, network)
                transaction.requireSelectedInputsStillSpendable(currentUtxos)
                refreshAddressWatchStatusFromNode(config, address, network, configRevision)
                when (action) {
                    DogecoinRawTransactionExportAction.COPY -> copy(
                        transaction.rawTransactionHex,
                        context.getString(R.string.dogecoin_raw_transaction_copied)
                    )
                    DogecoinRawTransactionExportAction.SHARE -> shareExternal(
                        transaction.rawTransactionHex,
                        context.getString(R.string.dogecoin_share_raw_transaction_external_title),
                        context.getString(R.string.dogecoin_raw_transaction_share_failed)
                    )
                }
            }.onFailure {
                if (
                    selectedNetwork == network &&
                    snapshot.key.address == address &&
                    rpcConfigRevision == configRevision
                ) {
                    sendError = it.message ?: context.getString(R.string.dogecoin_send_failed)
                }
            }
            if (
                selectedNetwork == network &&
                snapshot.key.address == address &&
                rpcConfigRevision == configRevision
            ) {
                exportingRawTransaction = false
            }
        }
    }

    fun revealSendForm() {
        coroutineScope.launch {
            try {
                listState.animateScrollToItem(DOGECOIN_SEND_ITEM_INDEX)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    fun clearReviewedSendState() {
        sentReceipt = null
        pendingTransaction = null
        mainnetBroadcastAcknowledged = false
        highFeeAcknowledged = false
        policyUnavailableAcknowledged = false
        exportingRawTransaction = false
        peerBroadcastAck = false
        onClearPeerBroadcast()
    }

    fun clearManualSendRequestMetadata() {
        paymentRequestLabel = null
        paymentRequestMessage = null
    }

    val receiveUri = remember(selectedNetwork, snapshot.key.address) {
        DogecoinProtocol.createPaymentUri(selectedNetwork, snapshot.key.address)
    }
    val receiveShareText = stringResource(
        R.string.dogecoin_receive_share_text,
        selectedNetwork.displayName,
        receiveUri
    )
    val nodeConfigSnippet = remember(selectedNetwork, rpcUsername, rpcPassword) {
        dogecoinConfSnippet(
            network = selectedNetwork,
            username = rpcUsername,
            password = rpcPassword
        )
    }

    val paymentUri = remember(selectedNetwork, snapshot.key.address, amount, label, requestMessage) {
        runCatching {
            DogecoinProtocol.createPaymentUri(
                network = selectedNetwork,
                address = snapshot.key.address,
                amount = amount.takeIf { it.isNotBlank() },
                label = label.takeIf { it.isNotBlank() },
                message = requestMessage.takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }
    val paymentShareText = paymentUri?.let {
        stringResource(
            R.string.dogecoin_request_share_text,
            selectedNetwork.displayName,
            it
        )
    }

    fun applyPaymentRequest(request: DogecoinPaymentRequest, revealSend: Boolean = true) {
        if (request.network != selectedNetwork) {
            switchNetwork(request.network)
        }
        sendAddress = request.address
        sendAmount = request.amount.orEmpty()
        sendError = null
        clearReviewedSendState()
        paymentRequestLabel = request.label
        paymentRequestMessage = request.message
        if (revealSend) {
            revealSendForm()
        }
    }

    fun updateSendAddressInput(value: String) {
        val trimmed = value.trim()
        val request = DogecoinPaymentRequest.parseAddressOrUri(trimmed)

        if (request != null) {
            applyPaymentRequest(request, revealSend = false)
        } else {
            sendAddress = trimmed
            sendError = null
            clearManualSendRequestMetadata()
            clearReviewedSendState()
        }
    }

    fun updateSendAmountInput(value: String) {
        sendAmount = value
        sendError = null
        clearManualSendRequestMetadata()
        clearReviewedSendState()
    }

    fun updateSendFeeRateInput(value: String) {
        sendFeeRate = value
        showAdvancedFee = true
        sendError = null
        clearReviewedSendState()
    }

    fun selectSendFeePreset(preset: DogecoinFeePreset) {
        sendFeePreset = preset
        showAdvancedFee = false
        sendFeeRate = DogecoinAmount.formatKoinu(
            sendFeePresets.firstOrNull { it.preset == preset }?.feePerKbKoinu ?: minimumSendFeePerKbKoinu
        )
        sendError = null
        clearReviewedSendState()
    }

    fun estimateSendFee(feePerKbKoinu: Long): Long? {
        val balance = walletBalance
        val sendAmountKoinu = if (isValidSelectedSendAmount(sendAmount)) {
            DogecoinAmount.toKoinu(sendAmount)
        } else {
            null
        }
        if (balance != null && sendAmountKoinu != null) {
            runCatching {
                DogecoinTransactionBuilder.estimateFeeForSelection(
                    wallet = snapshot.key,
                    utxos = balance.utxos,
                    sendAmountKoinu = sendAmountKoinu,
                    network = selectedNetwork,
                    feePerKbKoinu = feePerKbKoinu,
                    minimumOutputKoinu = minimumSendOutputKoinu
                )
            }.getOrNull()?.let { return it }
        }

        return runCatching {
            DogecoinTransactionBuilder.estimateFeeForSelection(
                wallet = snapshot.key,
                inputCount = 1,
                outputCount = 2,
                feePerKbKoinu = feePerKbKoinu
            )
        }.getOrNull()
    }

    fun pastePaymentRequestFromClipboard() {
        val raw = clipboardManager.getText()?.text.orEmpty()
        val request = DogecoinPaymentRequest.parseAddressOrUri(raw)
        if (request == null) {
            sendError = context.getString(R.string.dogecoin_clipboard_payment_request_invalid)
            return
        }

        applyPaymentRequest(request)
        Toast.makeText(
            context,
            context.getString(
                R.string.dogecoin_payment_request_loaded,
                request.network.displayName
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    fun saveRecipientAddress(address: String, label: String = "") {
        savedAddresses = repository.upsertSavedAddress(selectedNetwork, address, label)
        Toast.makeText(
            context,
            context.getString(R.string.dogecoin_saved_recipient_added),
            Toast.LENGTH_SHORT
        ).show()
    }

    fun removeRecipientAddress(address: String) {
        savedAddresses = repository.removeSavedAddress(selectedNetwork, address)
        Toast.makeText(
            context,
            context.getString(R.string.dogecoin_saved_recipient_removed),
            Toast.LENGTH_SHORT
        ).show()
    }

    LaunchedEffect(sendFeePreset, selectedFeePresetRateKoinu, showAdvancedFee) {
        if (!showAdvancedFee) {
            sendFeeRate = DogecoinAmount.formatKoinu(selectedFeePresetRateKoinu)
        }
    }

    LaunchedEffect(selectedNetwork, snapshot.key.address, rpcConfigRevision) {
        if (rpcConfigNeedsRecheck) {
            delay(DOGECOIN_RPC_RECHECK_DEBOUNCE_MILLIS)
        }
        if (rpcUrlBlank || !rpcUrlValid) {
            // Nothing to revalidate against a blank/invalid URL; clear the pending-recheck hint
            // so the "revalidating" balance/node notice does not stick when the URL is cleared.
            rpcConfigNeedsRecheck = false
            return@LaunchedEffect
        }
        refreshNodeStatus()
    }

    LaunchedEffect(paymentRequest?.uri) {
        val request = paymentRequest ?: return@LaunchedEffect
        applyPaymentRequest(request)
    }

    BitchatBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismiss
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 80.dp, start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AccountBalanceWallet,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = stringResource(R.string.dogecoin_wallet_title),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.dogecoin_wallet_network_summary,
                                selectedNetwork.displayName
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                }

                item(key = "network") {
                    WalletCard {
                        Text(
                            text = stringResource(R.string.dogecoin_network_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            networks.forEachIndexed { index, network ->
                                SegmentedButton(
                                    selected = selectedNetwork == network,
                                    onClick = { switchNetwork(network) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = networks.size
                                    )
                                ) {
                                    Text(
                                        text = stringResource(
                                            when (network) {
                                                DogecoinNetwork.MAINNET -> R.string.dogecoin_network_mainnet
                                                DogecoinNetwork.TESTNET -> R.string.dogecoin_network_testnet
                                                DogecoinNetwork.REGTEST -> R.string.dogecoin_network_regtest
                                            }
                                        )
                                    )
                                }
                            }
                        }
                        Text(
                            text = stringResource(
                                R.string.dogecoin_network_hint,
                                selectedNetwork.displayName
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            lineHeight = 18.sp
                        )
                        if (selectedNetwork == DogecoinNetwork.MAINNET) {
                            Text(
                                text = stringResource(R.string.dogecoin_mainnet_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 18.sp
                            )
                        }
                        if (selectedNetwork == DogecoinNetwork.TESTNET && !practiceNudgeDismissed) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                            Text(
                                text = stringResource(R.string.dogecoin_practice_nudge),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                lineHeight = 18.sp
                            )
                            TextButton(
                                onClick = {
                                    repository.dismissPracticeNudge()
                                    practiceNudgeDismissed = true
                                }
                            ) {
                                Text(stringResource(R.string.dismiss))
                            }
                        }
                    }
                }

                item(key = "node") {
                    WalletCard {
                        Text(
                            text = stringResource(R.string.dogecoin_node_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        OutlinedTextField(
                            value = rpcUrl,
                            onValueChange = { updateRpcUrl(it) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.dogecoin_rpc_url_label)) },
                            placeholder = {
                                Text(
                                    stringResource(
                                        R.string.dogecoin_rpc_url_placeholder,
                                        selectedNetwork.rpcPort
                                    )
                                )
                            },
                            isError = rpcUrl.isNotBlank() && !rpcUrlValid
                        )
                        when {
                            rpcUrlBlank -> Text(
                                text = stringResource(
                                    R.string.dogecoin_rpc_empty_hint,
                                    selectedNetwork.displayName
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                lineHeight = 18.sp
                            )
                            !rpcUrlValid -> Text(
                                text = stringResource(R.string.dogecoin_rpc_url_invalid),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 18.sp
                            )
                        }
                        Text(
                            text = stringResource(R.string.dogecoin_rpc_device_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            lineHeight = 18.sp
                        )
                        if (com.bitchat.android.BuildConfig.DEBUG) {
                            OutlinedButton(
                                onClick = { updateRpcUrl(selectedNetwork.emulatorRpcUrl) },
                                enabled = rpcUrl.trim() != selectedNetwork.emulatorRpcUrl,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.dogecoin_rpc_use_emulator))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = rpcUsername,
                                onValueChange = { updateRpcUsername(it) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text(stringResource(R.string.dogecoin_rpc_user_label)) }
                            )
                            OutlinedTextField(
                                value = rpcPassword,
                                onValueChange = { updateRpcPassword(it) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text(stringResource(R.string.dogecoin_rpc_password_label)) },
                                visualTransformation = PasswordVisualTransformation()
                            )
                        }
                        OutlinedTextField(
                            value = rpcWalletName,
                            onValueChange = { updateRpcWalletName(it) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.dogecoin_rpc_wallet_label)) }
                        )
                        Text(
                            text = stringResource(R.string.dogecoin_rpc_wallet_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            lineHeight = 18.sp
                        )
                        Text(
                            text = stringResource(R.string.dogecoin_rpc_auth_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            lineHeight = 18.sp
                        )
                        TextButton(onClick = { showNodeHelp = !showNodeHelp }) {
                            Text(
                                stringResource(
                                    if (showNodeHelp) {
                                        R.string.dogecoin_node_help_hide
                                    } else {
                                        R.string.dogecoin_node_help_show
                                    }
                                )
                            )
                        }
                        if (showNodeHelp) {
                            SelectionContainer {
                                Text(
                                    text = nodeConfigSnippet,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    copy(
                                        nodeConfigSnippet,
                                        context.getString(R.string.dogecoin_node_conf_copied)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.dogecoin_node_conf_copy))
                            }
                        }
                        NodeStatusRow(
                            status = nodeStatus,
                            refreshing = refreshing,
                            network = selectedNetwork
                        )
                        when {
                            refreshing -> Text(
                                text = stringResource(R.string.dogecoin_node_auto_rechecking),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                lineHeight = 18.sp
                            )
                            rpcConfigNeedsRecheck && !rpcUrlBlank && rpcUrlValid -> Text(
                                text = stringResource(R.string.dogecoin_node_revalidating),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                lineHeight = 18.sp
                            )
                            nodeStatus == null && !rpcUrlBlank -> Text(
                                text = stringResource(R.string.dogecoin_node_recheck_required),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                lineHeight = 18.sp
                            )
                        }
                        Button(
                            onClick = { refreshNodeStatus() },
                            enabled = !refreshing && rpcUrlValid,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.refresh_node_status))
                        }
                    }
                }

                item(key = "address") {
                    WalletCard {
                        Text(
                            text = stringResource(R.string.dogecoin_receive_address),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        SelectionContainer {
                            Text(
                                text = snapshot.key.address,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 15.sp,
                                lineHeight = 20.sp
                            )
                        }
                        DogecoinQrCodeImage(
                            data = receiveUri,
                            contentDescription = stringResource(R.string.dogecoin_address_qr_description),
                            size = 160.dp,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = {
                                    copy(
                                        snapshot.key.address,
                                        context.getString(R.string.dogecoin_address_copied)
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.copy_address))
                            }
                            OutlinedButton(
                                onClick = {
                                    copy(
                                        receiveUri,
                                        context.getString(R.string.dogecoin_receive_uri_copied)
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.dogecoin_copy_receive_uri))
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                shareExternal(
                                    receiveShareText,
                                    context.getString(R.string.dogecoin_share_address_external_title),
                                    context.getString(R.string.dogecoin_address_share_failed)
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.dogecoin_share_address))
                        }
                        Button(
                            onClick = {
                                onShareToChat(receiveShareText)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.dogecoin_address_shared),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.share_to_chat))
                        }
                        OutlinedButton(
                            onClick = { pendingWifCopy = snapshot.key },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.copy_wif))
                        }
                        Text(
                            text = if (wifCopyRecorded) {
                                stringResource(
                                    R.string.dogecoin_wif_copy_recorded,
                                    formatDogecoinWalletTime(wifCopyState.copiedAtMillis)
                                )
                            } else {
                                stringResource(R.string.dogecoin_wif_copy_missing)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!wifCopyRecorded && selectedNetwork == DogecoinNetwork.MAINNET) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                            },
                            lineHeight = 18.sp
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.dogecoin_advertise_address_title),
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    text = stringResource(
                                        R.string.dogecoin_advertise_address_warning,
                                        selectedNetwork.displayName
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                    lineHeight = 16.sp
                                )
                            }
                            Switch(
                                checked = advertiseAddressEnabled,
                                onCheckedChange = { enabled ->
                                    advertiseAddressEnabled = enabled
                                    repository.saveAdvertiseAddressEnabled(enabled)
                                    onAdvertisedAddressChanged()
                                }
                            )
                        }
                    }
                }

                item(key = "balance") {
                    WalletCard {
                        Text(
                            text = stringResource(R.string.dogecoin_balance_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        val balance = walletBalance
                        if (balance == null) {
                            Text(
                                text = walletBalanceError ?: stringResource(R.string.dogecoin_balance_not_loaded),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (walletBalanceError == null) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                lineHeight = 18.sp
                            )
                        } else {
                            val maxSpendEstimate = if (isValidSelectedFeeRate(sendFeeRate)) {
                                runCatching {
                                    DogecoinTransactionBuilder.maxSpendable(
                                        wallet = snapshot.key,
                                        utxos = balance.utxos,
                                        network = selectedNetwork,
                                        feePerKbKoinu = DogecoinAmount.toKoinu(sendFeeRate.trim()),
                                        minimumOutputKoinu = minimumSendOutputKoinu
                                    )
                                }.getOrNull()?.takeIf { it.amountKoinu >= minimumSendOutputKoinu }
                            } else {
                                null
                            }
                            Text(
                                text = stringResource(
                                    R.string.dogecoin_balance_available,
                                    DogecoinAmount.formatKoinu(balance.confirmedKoinu)
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = stringResource(
                                    R.string.dogecoin_balance_details,
                                    DogecoinAmount.formatKoinu(balance.confirmedKoinu),
                                    DogecoinAmount.formatKoinu(balance.unconfirmedKoinu),
                                    balance.utxoCount
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                lineHeight = 18.sp
                            )
                            if (rpcConfigNeedsRecheck || refreshing) {
                                Text(
                                    text = stringResource(R.string.dogecoin_balance_revalidating),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    lineHeight = 18.sp
                                )
                            }
                            maxSpendEstimate?.let { maxSpend ->
                                Text(
                                    text = stringResource(
                                        R.string.dogecoin_balance_max_spendable,
                                        DogecoinAmount.formatKoinu(maxSpend.amountKoinu),
                                        DogecoinAmount.formatKoinu(maxSpend.feeKoinu)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                                    lineHeight = 18.sp
                                )
                            }
                            if (balance.utxos.isNotEmpty()) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                                if (balance.confirmedUtxos.isNotEmpty()) {
                                    DogecoinUtxoSection(
                                        title = stringResource(R.string.dogecoin_confirmed_utxos_title),
                                        utxos = balance.confirmedUtxos
                                    )
                                }
                                if (balance.unconfirmedUtxos.isNotEmpty()) {
                                    DogecoinUtxoSection(
                                        title = stringResource(R.string.dogecoin_pending_utxos_title),
                                        utxos = balance.unconfirmedUtxos
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                            DogecoinActivitySection(
                                activity = walletActivity,
                                error = walletActivityError,
                                onCopyTxid = {
                                    copy(it, context.getString(R.string.dogecoin_txid_copied))
                                },
                                onShareTxid = {
                                    shareExternal(
                                        it,
                                        context.getString(R.string.dogecoin_share_txid_external_title),
                                        context.getString(R.string.dogecoin_txid_share_failed)
                                    )
                                },
                                onShareTxidToChat = {
                                    onShareToChat(it)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.dogecoin_txid_shared),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                        Button(
                            onClick = {
                                if (shouldConfirmImportingRefresh) {
                                    pendingWatchImportAction = DogecoinWatchImportAction.REFRESH_BALANCE
                                } else {
                                    refreshWalletBalance()
                                }
                            },
                            enabled = !refreshingBalance && !rescanning && nodeReady,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (refreshingBalance) {
                                    stringResource(R.string.dogecoin_balance_refreshing)
                                } else {
                                    stringResource(R.string.dogecoin_balance_refresh)
                                }
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                rescanStartHeightInput = ""
                                pendingRescanNetwork = selectedNetwork
                            },
                            enabled = !refreshingBalance && !rescanning && canRescanWalletHistory,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (rescanning) {
                                    stringResource(R.string.dogecoin_balance_rescanning)
                                } else {
                                    stringResource(R.string.dogecoin_balance_rescan)
                                }
                            )
                        }
                        if (nodeReady && !canRescanWalletHistory) {
                            Text(
                                text = stringResource(R.string.dogecoin_rescan_pruned_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                lineHeight = 18.sp
                            )
                        }
                        if (usesImportAddressHistoricalRescan) {
                            Text(
                                text = if (addressAlreadyImported) {
                                    stringResource(R.string.dogecoin_rescan_already_imported_hint)
                                } else {
                                    stringResource(R.string.dogecoin_rescan_refresh_order_hint)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                lineHeight = 18.sp
                            )
                        }
                        if (usesImportAddressHistoricalRescan && addressWatchStatusError != null) {
                            Text(
                                text = stringResource(
                                    R.string.dogecoin_address_watch_status_unavailable,
                                    addressWatchStatusError ?: ""
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                lineHeight = 18.sp
                            )
                        }
                        rescanError?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }

                item(key = "request") {
                    WalletCard {
                        Text(
                            text = stringResource(R.string.dogecoin_request_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        OutlinedTextField(
                            value = amount,
                            onValueChange = { amount = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.dogecoin_amount_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = amount.isNotBlank() && !DogecoinAmount.isStandardOutputAmount(amount)
                        )
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.dogecoin_label_label)) }
                        )
                        OutlinedTextField(
                            value = requestMessage,
                            onValueChange = { requestMessage = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.dogecoin_message_label)) }
                        )
                        SelectionContainer {
                            Text(
                                text = paymentUri ?: stringResource(R.string.dogecoin_invalid_request),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = if (paymentUri == null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        if (paymentUri != null) {
                            DogecoinQrCodeImage(
                                data = paymentUri,
                                contentDescription = stringResource(R.string.dogecoin_request_qr_description),
                                size = 188.dp,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = {
                                    paymentUri?.let {
                                        copy(it, context.getString(R.string.dogecoin_request_copied))
                                    }
                                },
                                enabled = paymentUri != null,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.copy_request))
                            }
                            OutlinedButton(
                                onClick = {
                                    paymentShareText?.let {
                                        shareExternal(
                                            it,
                                            context.getString(R.string.dogecoin_share_request_external_title),
                                            context.getString(R.string.dogecoin_request_share_failed)
                                        )
                                    }
                                },
                                enabled = paymentShareText != null,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.dogecoin_share_external))
                            }
                        }
                        Button(
                            onClick = {
                                paymentShareText?.let {
                                    onShareToChat(it)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.dogecoin_request_shared),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            enabled = paymentShareText != null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.share_to_chat))
                        }
                    }
                }

                item(key = "send") {
                    WalletCard {
                        Text(
                            text = stringResource(R.string.dogecoin_send_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        OutlinedTextField(
                            value = sendAddress,
                            onValueChange = { updateSendAddressInput(it) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.dogecoin_send_address_label)) },
                            isError = sendAddress.isNotBlank() &&
                                !DogecoinAddress.isValidAddress(sendAddress.trim(), selectedNetwork)
                        )
                        if (
                            sendAddress.isNotBlank() &&
                            DogecoinAddress.isValidAddress(sendAddress.trim(), selectedNetwork)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.dogecoin_send_valid_address,
                                    selectedNetwork.displayName
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                lineHeight = 18.sp
                            )
                        }
                        if (savedAddresses.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.dogecoin_saved_recipients_title),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                savedAddresses.forEach { savedAddress ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        OutlinedButton(
                                            onClick = { updateSendAddressInput(savedAddress.address) },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.Start,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = savedAddress.label.takeIf { it.isNotBlank() }
                                                        ?: shortDogecoinAddress(savedAddress.address),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    maxLines = 1
                                                )
                                                Text(
                                                    text = savedAddress.address,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                        IconButton(onClick = { removeRecipientAddress(savedAddress.address) }) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = stringResource(
                                                    R.string.dogecoin_saved_recipient_remove
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = sendAmount,
                            onValueChange = { updateSendAmountInput(it) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.dogecoin_send_amount_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = sendAmount.isNotBlank() && !isValidSelectedSendAmount(sendAmount)
                        )
                        val presetFeeEstimates = remember(
                            sendAmount,
                            walletBalance,
                            selectedNetwork,
                            snapshot.key,
                            sendFeePresets,
                            minimumSendOutputKoinu
                        ) {
                            sendFeePresets.map { estimateSendFee(it.feePerKbKoinu) }
                        }
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            sendFeePresets.forEachIndexed { index, option ->
                                val estimatedFee = presetFeeEstimates.getOrNull(index)
                                SegmentedButton(
                                    selected = !showAdvancedFee && sendFeePreset == option.preset,
                                    onClick = { selectSendFeePreset(option.preset) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = sendFeePresets.size
                                    )
                                ) {
                                    Text(
                                        text = stringResource(
                                            option.preset.labelResId,
                                            // Show an em dash rather than a misleadingly low fee when the
                                            // estimate is unavailable (e.g. a pathological overflow rate).
                                            estimatedFee?.let { DogecoinAmount.formatKoinu(it) } ?: "—"
                                        )
                                    )
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Checkbox(
                                checked = showAdvancedFee,
                                onCheckedChange = {
                                    showAdvancedFee = it
                                    if (!it) {
                                        selectSendFeePreset(sendFeePreset)
                                    }
                                }
                            )
                            Text(
                                text = stringResource(R.string.dogecoin_send_fee_advanced),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                            )
                        }
                        if (showAdvancedFee) {
                            OutlinedTextField(
                                value = sendFeeRate,
                                onValueChange = { updateSendFeeRateInput(it) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(stringResource(R.string.dogecoin_send_fee_rate_label)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                isError = sendFeeRate.isNotBlank() &&
                                    !isValidSelectedFeeRate(sendFeeRate)
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.dogecoin_send_fee_rate_hint,
                                DogecoinAmount.formatKoinu(minimumSendFeePerKbKoinu)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            lineHeight = 18.sp
                        )
                        if (
                            usableNodeStatus?.softDustLimitKoinu != null &&
                            usableNodeStatus.hardDustLimitKoinu != null &&
                            usableNodeStatus.incrementalFeePerKbKoinu != null
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.dogecoin_send_node_policy_hint,
                                    DogecoinAmount.formatKoinu(usableNodeStatus.softDustLimitKoinu),
                                    DogecoinAmount.formatKoinu(usableNodeStatus.hardDustLimitKoinu),
                                    DogecoinAmount.formatKoinu(usableNodeStatus.incrementalFeePerKbKoinu)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                lineHeight = 18.sp
                            )
                        }
                        OutlinedButton(
                            onClick = { useMaxSendAmount() },
                            enabled = !calculatingMaxSend && !sending && !rescanning && nodeReady,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (calculatingMaxSend) {
                                    stringResource(R.string.dogecoin_send_max_working)
                                } else {
                                    stringResource(R.string.dogecoin_send_use_max)
                                }
                            )
                        }
                        Text(
                            text = stringResource(R.string.dogecoin_send_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            lineHeight = 18.sp
                        )
                        if (usesImportAddressHistoricalRescan) {
                            Text(
                                text = stringResource(
                                    if (addressAlreadyImported) {
                                        R.string.dogecoin_send_rescan_already_imported_hint
                                    } else {
                                        R.string.dogecoin_send_rescan_before_spend_hint
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                lineHeight = 18.sp
                            )
                        }
                        if (selectedNetwork == DogecoinNetwork.MAINNET && !wifCopyState.matches(snapshot.key)) {
                            Text(
                                text = stringResource(R.string.dogecoin_wif_copy_missing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 18.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    qrScanError = null
                                    scanningPaymentQr = true
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.dogecoin_scan_payment_qr))
                            }
                            OutlinedButton(
                                onClick = { pastePaymentRequestFromClipboard() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.dogecoin_paste_payment_request))
                            }
                        }
                        paymentRequestLabel?.let {
                            Text(
                                text = stringResource(R.string.dogecoin_payment_request_label, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                lineHeight = 18.sp
                            )
                        }
                        paymentRequestMessage?.let {
                            Text(
                                text = stringResource(R.string.dogecoin_payment_request_message, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                lineHeight = 18.sp
                            )
                        }
                        sendError?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 18.sp
                            )
                        }
                        sentReceipt?.let { receipt ->
                            val receiptRecipientSaved = savedAddresses.any {
                                it.address == receipt.recipientAddress
                            }
                            val receiptText = stringResource(
                                R.string.dogecoin_send_receipt,
                                receipt.network.displayName,
                                receipt.txid,
                                receipt.recipientAddress,
                                DogecoinAmount.formatKoinu(receipt.sendAmountKoinu),
                                DogecoinAmount.formatKoinu(receipt.feeKoinu),
                                DogecoinAmount.formatKoinu(receipt.totalDebitKoinu),
                                DogecoinAmount.formatKoinu(receipt.changeKoinu)
                            ) + receipt.changeAddress?.let { changeAddress ->
                                "\n" + stringResource(R.string.dogecoin_send_receipt_change_address, changeAddress)
                            }.orEmpty() + receipt.requestLabel?.let { label ->
                                "\n" + stringResource(R.string.dogecoin_send_receipt_label, label)
                            }.orEmpty() + receipt.requestMessage?.let { message ->
                                "\n" + stringResource(R.string.dogecoin_send_receipt_message, message)
                            }.orEmpty()
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (receipt.viaPeer) {
                                    Text(
                                        text = stringResource(
                                            if (receipt.peerCorroborated) {
                                                R.string.dogecoin_send_receipt_via_peer_corroborated
                                            } else {
                                                R.string.dogecoin_send_receipt_via_peer
                                            }
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                        lineHeight = 16.sp
                                    )
                                }
                                SelectionContainer {
                                    Text(
                                        text = receiptText,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 18.sp
                                    )
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    TextButton(
                                        onClick = {
                                            copy(
                                                receipt.txid,
                                                context.getString(R.string.dogecoin_txid_copied)
                                            )
                                        }
                                    ) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.dogecoin_copy_txid))
                                    }
                                    TextButton(
                                        onClick = {
                                            shareExternal(
                                                receipt.txid,
                                                context.getString(R.string.dogecoin_share_txid_external_title),
                                                context.getString(R.string.dogecoin_txid_share_failed)
                                            )
                                        }
                                    ) {
                                        Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.dogecoin_share_txid))
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        onShareToChat(receiptText)
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.dogecoin_receipt_shared),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                ) {
                                    Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.dogecoin_share_receipt_to_chat))
                                }
                                if (!receiptRecipientSaved) {
                                    TextButton(
                                        onClick = {
                                            saveRecipientAddress(
                                                receipt.recipientAddress,
                                                receipt.requestLabel.orEmpty()
                                            )
                                        }
                                    ) {
                                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.dogecoin_save_recipient))
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = { reviewSend() },
                            enabled = !sending && !rescanning && nodeReady,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (sending) {
                                    stringResource(R.string.dogecoin_send_working)
                                } else {
                                    stringResource(R.string.dogecoin_send_review)
                                }
                            )
                        }
                    }
                }

                item(key = "helper") {
                    WalletCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.dogecoin_helper_title),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = helperEnabled,
                                enabled = helperEnabled ||
                                    selectedNetwork != DogecoinNetwork.MAINNET ||
                                    helperMainnetConsent,
                                onCheckedChange = { enabled ->
                                    helperEnabled = enabled
                                    repository.saveHelperEnabled(selectedNetwork, enabled)
                                    // Re-announce immediately so peers learn the changed NODE_HELPER
                                    // capability now, instead of waiting for the ~30s periodic announce.
                                    onHelperEnabledChanged()
                                }
                            )
                        }
                        Text(
                            text = stringResource(R.string.dogecoin_helper_description, selectedNetwork.displayName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            lineHeight = 16.sp
                        )
                        if (selectedNetwork == DogecoinNetwork.MAINNET && !helperEnabled) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Checkbox(
                                    checked = helperMainnetConsent,
                                    onCheckedChange = { helperMainnetConsent = it }
                                )
                                Text(
                                    text = stringResource(R.string.dogecoin_helper_mainnet_consent),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.dogecoin_helper_favorites_only),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = helperFavoritesOnly,
                                onCheckedChange = { enabled ->
                                    helperFavoritesOnly = enabled
                                    repository.saveHelperFavoritesOnly(enabled)
                                }
                            )
                        }
                    }
                }

                item(key = "danger") {
                    WalletCard {
                        Text(
                            text = stringResource(R.string.dogecoin_wallet_controls_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = stringResource(R.string.dogecoin_wallet_controls_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            lineHeight = 18.sp
                        )
                        OutlinedTextField(
                            value = importWif,
                            onValueChange = {
                                importWif = it.trim()
                                importWifError = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.dogecoin_import_wif_label)) },
                            visualTransformation = if (importWifRevealed) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { importWifRevealed = !importWifRevealed }) {
                                    Icon(
                                        imageVector = if (importWifRevealed) {
                                            Icons.Filled.VisibilityOff
                                        } else {
                                            Icons.Filled.Visibility
                                        },
                                        contentDescription = stringResource(
                                            if (importWifRevealed) {
                                                R.string.dogecoin_hide_wif
                                            } else {
                                                R.string.dogecoin_reveal_wif
                                            }
                                        )
                                    )
                                }
                            },
                            isError = importWifError != null
                        )
                        importWifError?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 18.sp
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = {
                                    wifScanError = null
                                    scanningWifQr = true
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Outlined.QrCodeScanner,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.dogecoin_scan_wif_qr))
                            }
                            OutlinedButton(
                                onClick = { reviewImportWif() },
                                enabled = importWif.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Filled.AccountBalanceWallet,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.dogecoin_import_wif_review))
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                        OutlinedButton(
                            onClick = { pendingResetNetwork = selectedNetwork },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.dogecoin_reset_selected_wallet))
                        }
                    }
                }
            }

            CloseButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            )
        }
    }

    if (scanningPaymentQr) {
        DogecoinPaymentQrScannerDialog(
            title = stringResource(R.string.dogecoin_scan_payment_qr_title),
            prompt = stringResource(R.string.dogecoin_scan_payment_qr_prompt),
            error = qrScanError,
            onDismiss = {
                scanningPaymentQr = false
                qrScanError = null
            },
            onScan = { raw ->
                val request = DogecoinPaymentRequest.parseAddressOrUri(raw)
                if (request == null) {
                    qrScanError = context.getString(R.string.dogecoin_scan_payment_qr_invalid)
                } else {
                    applyPaymentRequest(request)
                    scanningPaymentQr = false
                    qrScanError = null
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.dogecoin_payment_request_loaded,
                            request.network.displayName
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    if (scanningWifQr) {
        DogecoinPaymentQrScannerDialog(
            title = stringResource(R.string.dogecoin_scan_wif_qr_title),
            prompt = stringResource(R.string.dogecoin_scan_wif_qr_prompt),
            error = wifScanError,
            onDismiss = {
                scanningWifQr = false
                wifScanError = null
            },
            onScan = { raw ->
                reviewImportWif(raw) { message ->
                    wifScanError = message
                }
                if (pendingImportKey != null) {
                    scanningWifQr = false
                    wifScanError = null
                }
            }
        )
    }

    pendingTransaction?.let { transaction ->
        var reviewNowMillis by remember(transaction.txid, transaction.createdAtMillis) {
            mutableStateOf(System.currentTimeMillis())
        }
        LaunchedEffect(transaction.txid, transaction.createdAtMillis) {
            while (true) {
                reviewNowMillis = System.currentTimeMillis()
                val remainingMillis = DOGECOIN_SIGNED_TX_MAX_AGE_MILLIS -
                    (reviewNowMillis - transaction.createdAtMillis)
                if (remainingMillis <= 0L) break
                delay(remainingMillis.coerceIn(1_000L, 60_000L))
            }
            reviewNowMillis = System.currentTimeMillis()
        }

        // Peer-broadcast (3b): when a helper confirms/claims, populate the same receipt the local path uses.
        LaunchedEffect(peerBroadcastState) {
            // A Confirmed is corroborated by >=2 helpers; a Claimed is a single helper's uncorroborated
            // claim (3b.1) — both populate a receipt, but the via-peer disclaimer is stronger for a claim.
            val (resultTxid, corroborated) = when (val pb = peerBroadcastState) {
                is PeerBroadcastUiState.Confirmed -> pb.txid to true
                is PeerBroadcastUiState.Claimed -> pb.txid to false
                else -> return@LaunchedEffect
            }
            if (selectedNetwork != transaction.network) return@LaunchedEffect
            // Money-safety: only consume a result that belongs to THIS transaction. Otherwise a stale
            // peer-broadcast result (dialog dismissed while pending, then a different tx opened on the
            // same network) could pair another tx's txid with this tx's amounts and mark it "sent".
            if (resultTxid != transaction.txid) return@LaunchedEffect
            sentReceipt = DogecoinBroadcastReceipt(
                txid = resultTxid,
                network = transaction.network,
                recipientAddress = transaction.recipientAddress,
                sendAmountKoinu = transaction.sendAmountKoinu,
                feeKoinu = transaction.feeKoinu,
                changeKoinu = transaction.changeKoinu,
                changeAddress = transaction.changeAddress,
                requestLabel = transaction.requestLabel,
                requestMessage = transaction.requestMessage,
                viaPeer = true,
                peerCorroborated = corroborated
            )
            sendAmount = ""
            pendingTransaction = null
            mainnetBroadcastAcknowledged = false
            highFeeAcknowledged = false
            policyUnavailableAcknowledged = false
            peerBroadcastAck = false
            onClearPeerBroadcast()
        }

        val transactionReviewExpired = transaction.isExpired(
            reviewNowMillis,
            DOGECOIN_SIGNED_TX_MAX_AGE_MILLIS
        )
        val transactionReviewConsistent = transaction.hasConsistentRawTransactionId()
        val highFeeAcknowledgementRequired = transaction.requiresHighFeeAcknowledgement()
        val policyUnavailableAcknowledgementRequired = transaction.requiresPolicyUnavailableAcknowledgement()
        val canExportOrBroadcastAfterAcknowledgements = canExportOrBroadcastSignedDogecoinTransaction(
            transaction = transaction,
            nowMillis = reviewNowMillis,
            maxAgeMillis = DOGECOIN_SIGNED_TX_MAX_AGE_MILLIS,
            mainnetAcknowledged = mainnetBroadcastAcknowledged,
            highFeeAcknowledged = highFeeAcknowledged,
            policyUnavailableAcknowledged = policyUnavailableAcknowledged
        )
        val canExportSignedRawTransaction = canExportSignedRawDogecoinTransaction(
            transaction = transaction,
            nowMillis = reviewNowMillis,
            maxAgeMillis = DOGECOIN_SIGNED_TX_MAX_AGE_MILLIS,
            mainnetAcknowledged = mainnetBroadcastAcknowledged,
            highFeeAcknowledged = highFeeAcknowledged,
            policyUnavailableAcknowledged = policyUnavailableAcknowledged,
            selectedNetwork = selectedNetwork,
            nodeReady = nodeReady
        )
        val canBroadcastThroughConfiguredNode = transaction.network == selectedNetwork && broadcastNodeReady

        AlertDialog(
            onDismissRequest = {
                if (!sending) {
                    pendingTransaction = null
                    mainnetBroadcastAcknowledged = false
                    highFeeAcknowledged = false
                    policyUnavailableAcknowledged = false
                }
            },
            title = {
                Text(
                    stringResource(
                        if (transaction.network == DogecoinNetwork.MAINNET) {
                            R.string.dogecoin_confirm_mainnet_send_title
                        } else {
                            R.string.dogecoin_confirm_send_title
                        }
                    )
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.dogecoin_confirm_send_body,
                            transaction.network.displayName,
                            transaction.recipientAddress,
                            DogecoinAmount.formatKoinu(transaction.sendAmountKoinu),
                            DogecoinAmount.formatKoinu(transaction.feeKoinu),
                            DogecoinAmount.formatKoinu(transaction.totalDebitKoinu),
                            DogecoinAmount.formatKoinu(transaction.changeKoinu)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp
                    )
                    transaction.changeAddress?.let { changeAddress ->
                        SelectionContainer {
                            Text(
                                text = stringResource(R.string.dogecoin_confirm_change_address, changeAddress),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                lineHeight = 18.sp
                            )
                        }
                    }
                    transaction.requestLabel?.let { label ->
                        Text(
                            text = stringResource(R.string.dogecoin_confirm_request_label, label),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            lineHeight = 18.sp
                        )
                    }
                    transaction.requestMessage?.let { message ->
                        Text(
                            text = stringResource(R.string.dogecoin_confirm_request_message, message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            lineHeight = 18.sp
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.dogecoin_confirm_input_details,
                            transaction.selectedUtxos.size,
                            DogecoinAmount.formatKoinu(transaction.inputTotalKoinu)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        lineHeight = 18.sp
                    )
                    DogecoinUtxoSection(
                        title = stringResource(R.string.dogecoin_confirm_selected_inputs_title),
                        utxos = transaction.selectedUtxos,
                        limit = DOGECOIN_CONFIRM_UTXO_PREVIEW_LIMIT
                    )
                    Text(
                        text = stringResource(
                            R.string.dogecoin_confirm_fee_rate,
                            DogecoinAmount.formatKoinu(transaction.feePerKbKoinu)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        lineHeight = 18.sp
                    )
                    Text(
                        text = stringResource(
                            R.string.dogecoin_confirm_signed_at,
                            formatDogecoinWalletTime(transaction.createdAtMillis)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        lineHeight = 18.sp
                    )
                    if (transactionReviewExpired) {
                        Text(
                            text = stringResource(R.string.dogecoin_send_review_expired),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 18.sp
                        )
                    }
                    if (!transactionReviewConsistent) {
                        Text(
                            text = stringResource(R.string.dogecoin_send_raw_txid_mismatch),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 18.sp
                        )
                    }
                    transaction.mempoolAcceptance?.let { acceptance ->
                        Text(
                            text = when {
                                acceptance.isAllowed -> stringResource(R.string.dogecoin_confirm_policy_allowed)
                                acceptance.checked -> acceptance.error
                                    ?: stringResource(R.string.dogecoin_confirm_policy_rejected)
                                else -> stringResource(
                                    R.string.dogecoin_confirm_policy_unavailable,
                                    acceptance.error ?: stringResource(R.string.unknown)
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (acceptance.isAllowed) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            lineHeight = 18.sp
                        )
                    }
                    if (policyUnavailableAcknowledgementRequired) {
                        Text(
                            text = stringResource(R.string.dogecoin_confirm_policy_unavailable_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 18.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = policyUnavailableAcknowledged,
                                onCheckedChange = { policyUnavailableAcknowledged = it },
                                enabled = !sending
                            )
                            Text(
                                text = stringResource(R.string.dogecoin_confirm_policy_unavailable_acknowledge),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 18.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (transaction.network == DogecoinNetwork.MAINNET) {
                        Text(
                            text = stringResource(R.string.dogecoin_confirm_mainnet_send_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 18.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = mainnetBroadcastAcknowledged,
                                onCheckedChange = { mainnetBroadcastAcknowledged = it },
                                enabled = !sending
                            )
                            Text(
                                text = stringResource(R.string.dogecoin_confirm_mainnet_acknowledge),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 18.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (highFeeAcknowledgementRequired) {
                        Text(
                            text = stringResource(
                                R.string.dogecoin_confirm_high_fee_warning,
                                DogecoinAmount.formatKoinu(transaction.feeKoinu),
                                DogecoinAmount.formatKoinu(transaction.sendAmountKoinu)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 18.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = highFeeAcknowledged,
                                onCheckedChange = { highFeeAcknowledged = it },
                                enabled = !sending
                            )
                            Text(
                                text = stringResource(R.string.dogecoin_confirm_high_fee_acknowledge),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 18.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (!transactionReviewExpired && !canBroadcastThroughConfiguredNode) {
                        Text(
                            text = stringResource(R.string.dogecoin_confirm_broadcast_relay_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 18.sp
                        )
                    }
                    if (
                        !transactionReviewExpired &&
                        canExportOrBroadcastAfterAcknowledgements &&
                        !canExportSignedRawTransaction
                    ) {
                        Text(
                            text = stringResource(R.string.dogecoin_confirm_raw_export_node_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 18.sp
                        )
                    }
                    SelectionContainer {
                        Text(
                            text = stringResource(R.string.dogecoin_confirm_txid, transaction.txid),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            lineHeight = 18.sp
                        )
                    }
                    SelectionContainer {
                        Text(
                            text = stringResource(
                                R.string.dogecoin_confirm_raw_preview,
                                previewDogecoinHex(transaction.rawTransactionHex)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            lineHeight = 18.sp
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            copy(
                                transaction.txid,
                                context.getString(R.string.dogecoin_signed_txid_copied)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.dogecoin_copy_signed_txid))
                    }
                    OutlinedButton(
                        onClick = {
                            exportSignedRawTransaction(
                                transaction,
                                DogecoinRawTransactionExportAction.COPY
                            )
                        },
                        enabled = !sending && !exportingRawTransaction && canExportSignedRawTransaction,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.dogecoin_copy_raw_transaction))
                    }
                    OutlinedButton(
                        onClick = {
                            exportSignedRawTransaction(
                                transaction,
                                DogecoinRawTransactionExportAction.SHARE
                            )
                        },
                        enabled = !sending && !exportingRawTransaction && canExportSignedRawTransaction,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.dogecoin_share_raw_transaction))
                    }
                    if (!transactionReviewExpired && !canBroadcastThroughConfiguredNode && hasHelperCandidate) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                        Text(
                            text = stringResource(R.string.dogecoin_peer_broadcast_explainer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            lineHeight = 18.sp
                        )
                        when (val pbState = peerBroadcastState) {
                            is PeerBroadcastUiState.Pending -> Text(
                                text = stringResource(R.string.dogecoin_peer_broadcast_pending),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                lineHeight = 18.sp
                            )
                            is PeerBroadcastUiState.Failed -> Text(
                                text = pbState.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 18.sp
                            )
                            else -> Unit
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = peerBroadcastAck,
                                onCheckedChange = { peerBroadcastAck = it },
                                enabled = peerBroadcastState !is PeerBroadcastUiState.Pending
                            )
                            Text(
                                text = stringResource(R.string.dogecoin_peer_broadcast_ack),
                                style = MaterialTheme.typography.bodySmall,
                                lineHeight = 18.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Button(
                            onClick = { onRequestPeerBroadcast(transaction) },
                            enabled = !sending &&
                                canExportOrBroadcastAfterAcknowledgements &&
                                peerBroadcastAck &&
                                peerBroadcastState !is PeerBroadcastUiState.Pending,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.dogecoin_peer_broadcast_cta))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { broadcastSignedTransaction(transaction) },
                        enabled = !sending &&
                        canExportOrBroadcastAfterAcknowledgements &&
                        canBroadcastThroughConfiguredNode
                ) {
                    Text(
                        stringResource(
                            if (transaction.network == DogecoinNetwork.MAINNET) {
                                R.string.dogecoin_confirm_broadcast_mainnet
                            } else {
                                R.string.dogecoin_confirm_broadcast
                            }
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingTransaction = null
                        mainnetBroadcastAcknowledged = false
                        highFeeAcknowledged = false
                        policyUnavailableAcknowledged = false
                        peerBroadcastAck = false
                        onClearPeerBroadcast()
                    },
                    enabled = !sending
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    pendingWifCopy?.let { key ->
        var wifQrRevealed by remember(key.address, key.network) { mutableStateOf(false) }
        val needsBackupAcknowledgement = key.network == DogecoinNetwork.MAINNET
        val canRecordWifBackup = !needsBackupAcknowledgement || mainnetWifBackupAcknowledged
        AlertDialog(
            onDismissRequest = {
                pendingWifCopy = null
                mainnetWifBackupAcknowledged = false
                wifQrRevealed = false
            },
            title = {
                Text(
                    stringResource(
                        R.string.dogecoin_confirm_wif_title,
                        key.network.displayName
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.dogecoin_confirm_wif_body),
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp
                    )
                    if (needsBackupAcknowledgement) {
                        Text(
                            text = stringResource(R.string.dogecoin_confirm_wif_mainnet_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 18.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = mainnetWifBackupAcknowledged,
                                onCheckedChange = { mainnetWifBackupAcknowledged = it }
                            )
                            Text(
                                text = stringResource(R.string.dogecoin_confirm_wif_mainnet_acknowledge),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 18.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.dogecoin_wif_qr_secret_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        lineHeight = 18.sp
                    )
                    OutlinedButton(
                        onClick = { wifQrRevealed = !wifQrRevealed },
                        enabled = canRecordWifBackup,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (wifQrRevealed) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            stringResource(
                                if (wifQrRevealed) {
                                    R.string.dogecoin_hide_wif_qr
                                } else {
                                    R.string.dogecoin_reveal_wif_qr
                                }
                            )
                        )
                    }
                    if (wifQrRevealed) {
                        DogecoinQrCodeImage(
                            data = key.wif,
                            contentDescription = stringResource(R.string.dogecoin_wif_qr_description),
                            size = 180.dp,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        SelectionContainer {
                            Text(
                                text = key.wif,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        copy(key.wif, context.getString(R.string.dogecoin_wif_copied))
                        val copyState = repository.markWifCopied(key)
                        if (selectedNetwork == key.network && snapshot.key.address == key.address) {
                            wifCopyState = copyState
                        }
                        pendingWifCopy = null
                        mainnetWifBackupAcknowledged = false
                        wifQrRevealed = false
                    },
                    enabled = canRecordWifBackup
                ) {
                    Text(stringResource(R.string.dogecoin_confirm_wif_copy))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingWifCopy = null
                        mainnetWifBackupAcknowledged = false
                        wifQrRevealed = false
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    pendingResetNetwork?.let { network ->
        AlertDialog(
            onDismissRequest = { pendingResetNetwork = null },
            title = {
                Text(
                    stringResource(
                        R.string.dogecoin_confirm_reset_title,
                        network.displayName
                    )
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.dogecoin_confirm_reset_body,
                        network.displayName
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val resetSnapshot = repository.resetWallet(network)
                        snapshot = resetSnapshot
                        wifCopyState = repository.loadWifCopyState(resetSnapshot.key)
                        savedAddresses = repository.loadSavedAddresses(network)
                        amount = ""
                        requestMessage = ""
                        importWif = ""
                        importWifRevealed = false
                        importWifError = null
                        pendingResetNetwork = null
                        clearWalletRuntimeState()
                        Toast.makeText(
                            context,
                            context.getString(R.string.dogecoin_wallet_reset),
                            Toast.LENGTH_SHORT
                        ).show()
                        onAdvertisedAddressChanged()
                    }
                ) {
                    Text(stringResource(R.string.dogecoin_confirm_reset_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingResetNetwork = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    pendingWatchImportAction?.let { action ->
        AlertDialog(
            onDismissRequest = {
                if (!refreshingBalance && !sending && !calculatingMaxSend) {
                    pendingWatchImportAction = null
                }
            },
            title = {
                Text(stringResource(R.string.dogecoin_confirm_watch_import_before_rescan_title))
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.dogecoin_confirm_watch_import_before_rescan_body,
                        selectedNetwork.displayName
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingWatchImportAction = null
                        when (action) {
                            DogecoinWatchImportAction.REFRESH_BALANCE -> refreshWalletBalance()
                            DogecoinWatchImportAction.REVIEW_SEND -> reviewSend(allowWatchImport = true)
                            DogecoinWatchImportAction.USE_MAX_SEND -> useMaxSendAmount(allowWatchImport = true)
                        }
                    },
                    enabled = !refreshingBalance && !sending && !calculatingMaxSend
                ) {
                    Text(
                        stringResource(
                            when (action) {
                                DogecoinWatchImportAction.REFRESH_BALANCE ->
                                    R.string.dogecoin_confirm_refresh_before_rescan_action
                                DogecoinWatchImportAction.REVIEW_SEND ->
                                    R.string.dogecoin_confirm_review_before_rescan_action
                                DogecoinWatchImportAction.USE_MAX_SEND ->
                                    R.string.dogecoin_confirm_use_max_before_rescan_action
                            }
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingWatchImportAction = null },
                    enabled = !refreshingBalance && !sending && !calculatingMaxSend
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    pendingRescanNetwork?.let { network ->
        val cleanStartHeight = rescanStartHeightInput.trim()
        val parsedStartHeight = cleanStartHeight.toIntOrNull()
        val validStartHeight = cleanStartHeight.isBlank() || (parsedStartHeight != null && parsedStartHeight >= 0)
        AlertDialog(
            onDismissRequest = { if (!rescanning) pendingRescanNetwork = null },
            title = {
                Text(
                    stringResource(
                        R.string.dogecoin_confirm_rescan_title,
                        network.displayName
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(
                            R.string.dogecoin_confirm_rescan_body,
                            network.displayName
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp
                    )
                    OutlinedTextField(
                        value = rescanStartHeightInput,
                        onValueChange = { rescanStartHeightInput = it },
                        enabled = !rescanning,
                        singleLine = true,
                        label = { Text(stringResource(R.string.dogecoin_rescan_start_height_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = !validStartHeight
                    )
                    Text(
                        text = stringResource(R.string.dogecoin_rescan_start_height_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        lineHeight = 18.sp
                    )
                    if (!validStartHeight) {
                        Text(
                            text = stringResource(R.string.dogecoin_rescan_start_height_invalid),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 18.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRescanNetwork = null
                        rescanWalletHistory(network, parsedStartHeight)
                    },
                    enabled = !rescanning && validStartHeight
                ) {
                    Text(stringResource(R.string.dogecoin_confirm_rescan_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingRescanNetwork = null },
                    enabled = !rescanning
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    pendingImportKey?.let { key ->
        val needsImportAcknowledgement = key.network == DogecoinNetwork.MAINNET
        val canImportWif = !needsImportAcknowledgement || mainnetWifImportAcknowledged
        AlertDialog(
            onDismissRequest = {
                pendingImportKey = null
                mainnetWifImportAcknowledged = false
            },
            title = {
                Text(
                    stringResource(
                        R.string.dogecoin_confirm_import_wif_title,
                        key.network.displayName
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(
                            R.string.dogecoin_confirm_import_wif_body,
                            key.network.displayName
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp
                    )
                    SelectionContainer {
                        Text(
                            text = key.address,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            lineHeight = 18.sp
                        )
                    }
                    if (needsImportAcknowledgement) {
                        Text(
                            text = stringResource(R.string.dogecoin_confirm_import_wif_mainnet_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 18.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = mainnetWifImportAcknowledged,
                                onCheckedChange = { mainnetWifImportAcknowledged = it }
                            )
                            Text(
                                text = stringResource(R.string.dogecoin_confirm_import_wif_mainnet_acknowledge),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 18.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val importedSnapshot = repository.importWalletFromWif(key.network, importWif)
                        snapshot = importedSnapshot
                        wifCopyState = repository.loadWifCopyState(importedSnapshot.key)
                        importWif = ""
                        importWifRevealed = false
                        importWifError = null
                        pendingImportKey = null
                        mainnetWifImportAcknowledged = false
                        clearWalletRuntimeState()
                        Toast.makeText(
                            context,
                            context.getString(R.string.dogecoin_import_wif_complete),
                            Toast.LENGTH_SHORT
                        ).show()
                        onAdvertisedAddressChanged()
                    },
                    enabled = canImportWif
                ) {
                    Text(stringResource(R.string.dogecoin_confirm_import_wif_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingImportKey = null
                        mainnetWifImportAcknowledged = false
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun WalletCard(content: @Composable ColumnScope.() -> Unit) {
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

@Composable
private fun SecureWindowFlagEffect(enabled: Boolean) {
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
private fun NodeStatusRow(
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
private fun DogecoinUtxoSection(
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
private fun DogecoinActivitySection(
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

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun DogecoinPaymentQrScannerDialog(
    title: String,
    prompt: String,
    error: String?,
    onDismiss: () -> Unit,
    onScan: (String) -> Unit
) {
    val permissionState = rememberPermissionState(Manifest.permission.CAMERA)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (permissionState.status.isGranted) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        DogecoinQrScannerView(onScan = onScan)
                        Box(
                            modifier = Modifier
                                .size(220.dp)
                                .border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(12.dp)
                                )
                        )
                        Text(
                            text = prompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp)
                                .background(Color.Black.copy(alpha = 0.64f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.dogecoin_scan_camera_permission),
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp
                    )
                    Button(onClick = { permissionState.launchPermissionRequest() }) {
                        Text(stringResource(R.string.dogecoin_scan_request_camera))
                    }
                }

                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        lineHeight = 18.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun DogecoinQrScannerView(
    onScan: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastValid by remember { mutableStateOf<String?>(null) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val surfaceRequests = remember { MutableStateFlow<SurfaceRequest?>(null) }
    val surfaceRequest by surfaceRequests.collectAsState(initial = null)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val onScanState = rememberUpdatedState(onScan)
    val analyzer = remember {
        DogecoinQrCodeAnalyzer { text ->
            mainHandler.post {
                if (text == lastValid) return@post
                lastValid = text
                onScanState.value(text)
            }
        }
    }

    DisposableEffect(Unit) {
        val executor = ContextCompat.getMainExecutor(context)
        var cameraProvider: ProcessCameraProvider? = null

        cameraProviderFuture.addListener(
            {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider { request -> surfaceRequests.value = request }
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, analyzer) }

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }.onFailure {
                    Log.w("DogecoinWalletSheet", "Failed to bind camera: ${it.message}")
                }
            },
            executor
        )

        onDispose {
            surfaceRequests.value = null
            runCatching { cameraProvider?.unbindAll() }
            analyzer.close()
            cameraExecutor.shutdown()
        }
    }

    surfaceRequest?.let { request ->
        CameraXViewfinder(
            surfaceRequest = request,
            implementationMode = ImplementationMode.EMBEDDED,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun DogecoinQrCodeImage(
    data: String,
    contentDescription: String,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val sizePx = with(LocalDensity.current) { size.toPx().toInt() }
    val bitmap = remember(data, sizePx) { generateDogecoinQrBitmap(data, sizePx) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier.size(size)
        )
    }
}

private fun generateDogecoinQrBitmap(data: String, sizePx: Int): Bitmap? {
    if (data.isBlank() || sizePx <= 0) return null
    return runCatching {
        val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, sizePx, sizePx)
        bitmapFromQrMatrix(matrix)
    }.getOrNull()
}

private fun bitmapFromQrMatrix(matrix: BitMatrix): Bitmap {
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            pixels[y * width + x] =
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }

    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}

private fun previewDogecoinHex(value: String, maxChars: Int = 96): String {
    return if (value.length <= maxChars) value else value.take(maxChars) + "..."
}

private fun shortDogecoinTxid(txid: String): String {
    return if (txid.length <= 18) txid else txid.take(8) + "..." + txid.takeLast(8)
}

private fun shortDogecoinAddress(address: String): String {
    return if (address.length <= 18) address else address.take(8) + "..." + address.takeLast(8)
}

private fun formatSignedDogecoin(koinu: Long): String {
    return when {
        koinu > 0 -> "+${DogecoinAmount.formatKoinu(koinu)}"
        koinu < 0 -> "-${DogecoinAmount.formatKoinu(-koinu)}"
        else -> "0"
    }
}

private fun formatDogecoinActivityTime(timeSeconds: Long?, fallback: String): String {
    if (timeSeconds == null) return fallback
    val millis = runCatching { Math.multiplyExact(timeSeconds, 1000L) }.getOrNull() ?: return fallback
    return SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(millis))
}

private fun formatDogecoinWalletTime(timeMillis: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(timeMillis))
}

private data class DogecoinBroadcastReceipt(
    val txid: String,
    val network: DogecoinNetwork,
    val recipientAddress: String,
    val sendAmountKoinu: Long,
    val feeKoinu: Long,
    val changeKoinu: Long,
    val changeAddress: String?,
    val requestLabel: String?,
    val requestMessage: String?,
    val viaPeer: Boolean = false,  // 3b: broadcast was relayed by a peer's node, not this device's node
    // 3b.1: when relayed by a peer, whether two or more helpers independently corroborated it. A single
    // helper's claim (false) is not chain-verified by this device and gets the stronger "verify" warning.
    val peerCorroborated: Boolean = false
) {
    val totalDebitKoinu: Long
        get() = dogecoinSaturatingAddKoinu(sendAmountKoinu, feeKoinu)
}

private enum class DogecoinWatchImportAction {
    REFRESH_BALANCE,
    REVIEW_SEND,
    USE_MAX_SEND
}

private enum class DogecoinRawTransactionExportAction {
    COPY,
    SHARE
}

private enum class DogecoinFeePreset(val labelResId: Int) {
    SLOW(R.string.dogecoin_send_fee_preset_slow),
    NORMAL(R.string.dogecoin_send_fee_preset_normal),
    FAST(R.string.dogecoin_send_fee_preset_fast)
}

private data class DogecoinFeePresetOption(
    val preset: DogecoinFeePreset,
    val feePerKbKoinu: Long
)

private fun dogecoinFeePresetOptions(
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

private fun dogecoinSaturatingMultiplyKoinu(left: Long, right: Long): Long {
    require(left >= 0L && right >= 0L) { "Dogecoin amounts must be non-negative" }
    return try {
        Math.multiplyExact(left, right)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}

private fun dogecoinConfSnippet(
    network: DogecoinNetwork,
    username: String,
    password: String
): String {
    val rpcUser = dogecoinConfValue(username, "bitchat")
    val rpcPassword = dogecoinConfValue(password, "choose-a-long-password")
    val networkLine = when (network) {
        DogecoinNetwork.MAINNET -> null
        DogecoinNetwork.TESTNET -> "testnet=1"
        DogecoinNetwork.REGTEST -> "regtest=1"
    }
    return buildList {
        networkLine?.let { add(it) }
        add("server=1")
        add("rpcuser=$rpcUser")
        add("rpcpassword=$rpcPassword")
        add("rpcbind=0.0.0.0")
        add("rpcallowip=10.0.0.0/8")
        add("rpcallowip=172.16.0.0/12")
        add("rpcallowip=192.168.0.0/16")
        add("rpcport=${network.rpcPort}")
    }.joinToString("\n")
}

private fun dogecoinConfValue(value: String, fallback: String): String {
    return value.trim().replace(Regex("\\s+"), "-").ifEmpty { fallback }
}

private const val DOGECOIN_UTXO_PREVIEW_LIMIT = 5
private const val DOGECOIN_CONFIRM_UTXO_PREVIEW_LIMIT = 3
private const val DOGECOIN_ACTIVITY_PREVIEW_LIMIT = 5
private const val DOGECOIN_SEND_ITEM_INDEX = 6
private const val DOGECOIN_SIGNED_TX_MAX_AGE_MILLIS = 10L * 60L * 1000L
private const val DOGECOIN_RPC_RECHECK_DEBOUNCE_MILLIS = 650L

private class DogecoinQrCodeAnalyzer(
    private val onCode: (String) -> Unit
) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val text = barcodes.firstOrNull()?.rawValue
                if (!text.isNullOrBlank()) onCode(text)
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    fun close() {
        scanner.close()
    }
}
