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

/** Which focal flow the wallet is showing — the "Coin" one-thing-at-a-time view-state. */
private enum class DogeWalletAction { NONE, SEND, RECEIVE, SETTINGS, ACTIVITY }

/** Backend-agnostic transaction row for the pending cards + full activity list (presentation-only). */
private data class WalletTxRow(
    val txid: String,
    val incoming: Boolean,
    val amountKoinu: Long,
    val confirmations: Int,
    val timeSeconds: Long?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DogecoinWalletSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    onShareToChat: (String) -> Unit,
    onPaymentReceiptClaimed: (DogepaidPaymentContext, DogepaidBroadcastClaim) -> Unit = { _, _ -> },
    onAdvertisedAddressChanged: () -> Unit = {},
    onHelperEnabledChanged: () -> Unit = {},
    onRequestPeerBroadcast: (DogecoinSignedTransaction, DogepaidPaymentContext?) -> Unit = { _, _ -> },
    peerBroadcastState: PeerBroadcastUiState = PeerBroadcastUiState.Idle,
    hasHelperCandidate: Boolean = false,
    onClearPeerBroadcast: () -> Unit = {},
    paymentRequest: DogecoinPaymentRequest? = null,
    paymentReceiptContext: DogepaidPaymentContext? = null,
    isSimpleProfile: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (!isPresented) return

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val repository = remember(context) { DogecoinWalletRepository(context) }
    val rpcClient = remember { DogecoinRpcClient() }
    // Synchronous, thread-safe lease generation checked inside DogecoinRpcClient before every request.
    // Saving/switching/stopping a route revokes multi-call workflows before their next HTTP request.
    val rpcRequestGeneration = remember { AtomicInteger(0) }
    // Phase 2: the on-device SPV light client (process singleton — shared with the debug console). Read-only;
    // started on demand when the user selects the "Built-in" backend, stopped on close. See walletReadSource.
    val spvService = remember { DogecoinSpvService.getInstance(context, repository) }
    val spvStatus by spvService.status.collectAsState()
    val spvDataSource = remember(spvService) { DogecoinSpvDataSource(spvService) }
    // Tor state drives the SPV connection disclosure: with Tor ON the light client routes peers over Tor and
    // never connects clearnet (intent = mode != OFF; ready = bootstrapped + RUNNING). See DogecoinSpvService.
    val torStatus by com.bitchat.android.net.ArtiTorManager.getInstance().statusFlow.collectAsState()
    val torIntentOn = torStatus.mode != com.bitchat.android.net.TorMode.OFF
    val torReady = torIntentOn &&
        torStatus.bootstrapPercent >= 100 &&
        torStatus.state == com.bitchat.android.net.ArtiTorManager.TorState.RUNNING
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
    // Phase 2 backend selector: which source serves balance/UTXO reads for the selected network. Default RPC.
    // SPV = the no-node built-in light client; EXPLORER is not user-selectable here (read-only/console-only).
    // Default to "Built-in" (SPV) when no node is configured and SPV is practical, so a no-node user sees
    // their balance without digging into settings; an explicit selector choice persists and wins.
    var persistedBackend by remember(selectedNetwork) { mutableStateOf(repository.resolveBackend(selectedNetwork)) }
    // Home-node assist (spec R-C3/R-C4a): while the built-in light client is still syncing, reads and
    // broadcast can ride the already-configured node for THIS session only. Session-only by construction:
    // nothing here calls saveBackend (only the Connection selector does), so kill+relaunch resolves the
    // persisted backend exactly as before. The SPV lifecycle keys on persistedBackend, so the light client
    // KEEPS syncing in the background while assist is active. Mainnet is excluded in v1 — mainnet reads
    // from a node require the explicit pin flow (spec R-C2) — and the RPC path re-runs its full gate
    // ladder (chain match, relay ready, testmempoolaccept, txid-vs-bytes) exactly as a normal node send.
    var nodeAssist by remember(selectedNetwork) { mutableStateOf(false) }
    val dogecoinBackend =
        if (nodeAssist && persistedBackend == DogecoinBackend.SPV) DogecoinBackend.RPC else persistedBackend
    // Read seam: SPV reads the synced light-client wallet; everything else uses the caller's captured RPC
    // config (byte-identical to the prior direct calls). Node-specific ops (status/watch/mempool/rescan),
    // rich activity, and broadcast stay on rpcClient (SPV broadcast is a later phase).
    fun walletReadSource(
        rpcConfig: DogecoinRpcConfig,
        activeRpcClient: DogecoinRpcClient = rpcClient
    ): DogecoinWalletDataSource =
        if (dogecoinBackend == DogecoinBackend.SPV) spvDataSource
        else DogecoinRpcDataSource(activeRpcClient, rpcConfig)
    var wifCopyState by remember { mutableStateOf(repository.loadWifCopyState(snapshot.key)) }
    var practiceNudgeDismissed by remember { mutableStateOf(repository.loadPracticeNudgeDismissed()) }
    var advertiseAddressEnabled by remember { mutableStateOf(repository.loadAdvertiseAddressEnabled()) }
    var helperEnabled by remember(selectedNetwork) { mutableStateOf(repository.loadHelperEnabled(selectedNetwork)) }
    var helperFavoritesOnly by remember { mutableStateOf(repository.loadHelperFavoritesOnly()) }
    var helperMainnetConsent by remember(selectedNetwork) { mutableStateOf(false) }
    // 3b.1 sender-side: independent on-chain corroboration of a single-helper Claimed peer broadcast.
    var onChainCorroborationEnabled by remember(selectedNetwork) {
        mutableStateOf(repository.loadOnChainCorroborationEnabled(selectedNetwork))
    }
    var explorerUrlTemplate by remember(selectedNetwork) {
        mutableStateOf(repository.loadExplorerUrlTemplate(selectedNetwork).orEmpty())
    }
    var peerBroadcastAck by remember { mutableStateOf(false) }
    var savedAddresses by remember { mutableStateOf(repository.loadSavedAddresses(snapshot.key.network)) }
    // Node settings are edited as an in-memory DRAFT (rpcUrl/rpcUsername/rpcPassword/rpcWalletName).
    // NOTHING is persisted and NO network I/O happens while typing or displaying them; savedRpcConfig is
    // the persisted ACTIVE config that all node reads/sends use, updated only by the explicit Save action.
    var rpcUrl by remember { mutableStateOf(snapshot.rpcConfig.url) }
    var rpcUsername by remember { mutableStateOf(snapshot.rpcConfig.username) }
    var rpcPassword by remember { mutableStateOf(snapshot.rpcConfig.password) }
    var rpcWalletName by remember { mutableStateOf(snapshot.rpcConfig.walletName) }
    var savedRpcConfig by remember { mutableStateOf(snapshot.rpcConfig) }
    var rpcConfigRevision by remember { mutableStateOf(0) }
    // One-shot "Test connection" result for the current DRAFT (never persisted, never gates money paths).
    var draftTestStatus by remember { mutableStateOf<DogecoinNodeStatus?>(null) }
    var draftTesting by remember { mutableStateOf(false) }
    val draftTestGeneration = remember { AtomicInteger(0) }
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
    var pendingPaymentReceiptContext by remember { mutableStateOf<DogepaidPaymentContext?>(null) }
    // One-shot within this sheet instance. Keeping the parent prop unchanged must not let a second send
    // reuse the original conversation/request binding after the first transaction succeeds.
    var availablePaymentReceiptContext by remember(paymentReceiptContext) {
        mutableStateOf(paymentReceiptContext)
    }
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
    var exportingRawTransaction by remember { mutableStateOf(false) }
    var scanningPaymentQr by remember { mutableStateOf(false) }
    var scanningWifQr by remember { mutableStateOf(false) }
    var qrScanError by remember { mutableStateOf<String?>(null) }
    var wifScanError by remember { mutableStateOf<String?>(null) }
    var showNodeHelp by remember { mutableStateOf(false) }
    val rpcUrlBlank = rpcUrl.trim().isEmpty()
    // Node/developer settings (connection, network, helper, corroboration, danger zone) collapse into one
    // expander so first-time users see balance/receive/send first; auto-open when no node is configured yet.
    // "Coin" focal-swap: default shows the ring; Send/Receive/Settings replace the focal area, one at a time.
    var walletAction by remember { mutableStateOf(DogeWalletAction.NONE) }
    var showUtxoDetails by remember { mutableStateOf(false) }
    var showRequest by remember { mutableStateOf(false) }
    val rpcUrlValid = remember(rpcUrl, selectedNetwork) {
        !rpcUrlBlank && DogecoinRpcConfig(url = rpcUrl).hasValidUrl(selectedNetwork)
    }
    // Trust classification of the ACTIVE (saved) endpoint — the only thing that may authorize node I/O.
    // URL syntax validity above is presentation-only and never a trust decision.
    val savedEndpointClass = remember(savedRpcConfig) { classifyDogecoinRpcEndpoint(savedRpcConfig.url) }
    val savedEndpointTrusted = savedEndpointClass.isTrustedRpcRoute
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

    // True when the on-screen draft differs from the persisted active config (Save required to use it).
    val rpcDraftDirty = remember(rpcUrl, rpcUsername, rpcPassword, rpcWalletName, savedRpcConfig, selectedNetwork) {
        currentRpcConfig() != savedRpcConfig
    }

    fun isValidSelectedFeeRate(value: String): Boolean {
        if (!DogecoinAmount.isValidAmount(value)) return false
        return DogecoinAmount.toKoinu(value) >= minimumSendFeePerKbKoinu
    }

    fun isValidSelectedSendAmount(value: String): Boolean {
        if (!DogecoinAmount.isValidAmount(value)) return false
        return DogecoinAmount.toKoinu(value) >= minimumSendOutputKoinu
    }

    fun revokeActiveRpcRequests() {
        rpcRequestGeneration.incrementAndGet()
        rpcConfigRevision += 1
    }

    fun guardedRpcClient(generation: Int): DogecoinRpcClient = rpcClient.guardedBy {
        check(rpcRequestGeneration.get() == generation) {
            "Dogecoin node route changed; the pending RPC workflow was stopped before its next request."
        }
    }

    fun invalidateRpcRuntimeState() {
        revokeActiveRpcRequests()
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
        sendError = null
        sentReceipt = null
        pendingTransaction = null
        pendingPaymentReceiptContext = null
        mainnetBroadcastAcknowledged = false
        highFeeAcknowledged = false
        policyUnavailableAcknowledged = false
        sending = false
        exportingRawTransaction = false
        pendingWatchImportAction = null
    }

    fun invalidateDraftTestState() {
        draftTestGeneration.incrementAndGet()
        draftTestStatus = null
        draftTesting = false
    }

    fun setNodeAssistEnabled(enabled: Boolean) {
        if (nodeAssist == enabled) return
        invalidateRpcRuntimeState()
        nodeAssist = enabled
    }

    // Field edits mutate ONLY the in-memory draft: nothing is persisted and no I/O happens until the
    // explicit Save action. The active (saved) config — and anything in flight against it — is untouched.
    fun updateRpcUrl(value: String) {
        if (rpcUrl == value) return
        rpcUrl = value
        invalidateDraftTestState()
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
        invalidateDraftTestState()
    }

    fun updateRpcPassword(value: String) {
        if (rpcPassword == value) return
        rpcPassword = value
        invalidateDraftTestState()
    }

    fun updateRpcWalletName(value: String) {
        if (rpcWalletName == value) return
        rpcWalletName = value
        invalidateDraftTestState()
    }

    /**
     * Explicit Save: the ONLY place draft node settings are persisted and become the active config.
     * Stops a session-only node assist (the endpoint it was authorized for no longer exists) and
     * re-resolves the backend so an untrusted endpoint lands on Built-in SPV, never on a probed node.
     */
    fun saveNodeSettings() {
        // Revoke the old active route BEFORE committing the replacement. Any in-flight multi-call RPC
        // workflow may finish its current HTTP exchange, but cannot issue another request afterward.
        invalidateRpcRuntimeState()
        invalidateDraftTestState()
        nodeAssist = false
        repository.saveRpcConfig(selectedNetwork, currentRpcConfig())
        val persisted = repository.loadRpcConfig(selectedNetwork)
        savedRpcConfig = persisted
        // Reflect normalization (trimming, user:pass token split) back into the draft fields.
        rpcUrl = persisted.url
        rpcUsername = persisted.username
        rpcPassword = persisted.password
        rpcWalletName = persisted.walletName
        persistedBackend = repository.resolveBackend(selectedNetwork)
        val helperStillEligible = repository.loadHelperEnabled(selectedNetwork)
        if (helperEnabled != helperStillEligible) {
            helperEnabled = helperStillEligible
            onHelperEnabledChanged()
        }
    }

    /**
     * Explicit "Test connection" for the DRAFT: one-shot status probe, zero persistence, and it never
     * sends the wallet address (no watch lookup/import). Untrusted endpoints are refused with the
     * classifier's reason before any request is built.
     */
    fun testDraftConnection() {
        val config = currentRpcConfig()
        if (config.url.isBlank()) return
        val testGeneration = draftTestGeneration.incrementAndGet()
        val endpointClass = classifyDogecoinRpcEndpoint(config.url)
        if (!endpointClass.isTrustedRpcRoute) {
            draftTesting = false
            draftTestStatus = DogecoinNodeStatus(
                connected = false,
                expectedNetwork = selectedNetwork,
                error = dogecoinEndpointBlockedReason(endpointClass, context)
            )
            return
        }
        draftTesting = true
        draftTestStatus = null
        val network = selectedNetwork
        val draftRpcClient = rpcClient.guardedBy {
            check(draftTestGeneration.get() == testGeneration) {
                "Dogecoin node draft changed; the connection test was stopped before its next request."
            }
        }
        coroutineScope.launch {
            val status = draftRpcClient.getBlockchainStatus(config, network)
            if (selectedNetwork == network && draftTestGeneration.get() == testGeneration) {
                draftTestStatus = status
                draftTesting = false
            }
        }
    }

    fun switchNetwork(network: DogecoinNetwork) {
        if (network == selectedNetwork) return

        // An unsaved node-settings draft is intentionally discarded: only an explicit Save persists it.
        revokeActiveRpcRequests()
        invalidateDraftTestState()
        nodeAssist = false
        repository.saveSelectedNetwork(network)
        selectedNetwork = network

        val nextSnapshot = repository.loadOrCreateWallet(network)
        snapshot = nextSnapshot
        wifCopyState = repository.loadWifCopyState(nextSnapshot.key)
        practiceNudgeDismissed = repository.loadPracticeNudgeDismissed()
        savedAddresses = repository.loadSavedAddresses(network)
        rpcUrl = nextSnapshot.rpcConfig.url
        rpcUsername = nextSnapshot.rpcConfig.username
        rpcPassword = nextSnapshot.rpcConfig.password
        rpcWalletName = nextSnapshot.rpcConfig.walletName
        savedRpcConfig = nextSnapshot.rpcConfig
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
        pendingPaymentReceiptContext = null
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
        revokeActiveRpcRequests()
        invalidateDraftTestState()
        nodeAssist = false
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
        pendingPaymentReceiptContext = null
        mainnetBroadcastAcknowledged = false
        highFeeAcknowledged = false
        policyUnavailableAcknowledged = false
        pendingWatchImportAction = null
        rescanStartHeightInput = ""
        importWifRevealed = false
        paymentRequestLabel = null
        paymentRequestMessage = null
        sending = false
        exportingRawTransaction = false
        scanningPaymentQr = false
        scanningWifQr = false
        qrScanError = null
        wifScanError = null
    }

    suspend fun refreshAddressWatchStatusFromNode(
        activeRpcClient: DogecoinRpcClient,
        config: DogecoinRpcConfig,
        address: String,
        network: DogecoinNetwork,
        configRevision: Int
    ) {
        runCatching {
            activeRpcClient.getAddressWatchStatus(config, address, network)
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
        // Explicit action against the ACTIVE (saved) config only. The draft has its own Test action.
        val config = savedRpcConfig
        if (config.url.isBlank()) {
            nodeStatus = null
            refreshing = false
            return
        }
        if (!savedEndpointTrusted) {
            // Untrusted endpoint: zero I/O — the classifier's reason is the whole status.
            nodeStatus = DogecoinNodeStatus(
                connected = false,
                expectedNetwork = selectedNetwork,
                error = dogecoinEndpointBlockedReason(savedEndpointClass, context)
            )
            return
        }

        refreshing = true
        val network = selectedNetwork
        val address = snapshot.key.address
        val configRevision = rpcConfigRevision
        val requestGeneration = rpcRequestGeneration.get()
        val activeRpcClient = guardedRpcClient(requestGeneration)
        coroutineScope.launch {
            val status = activeRpcClient.getBlockchainStatus(config, network)
            val watchStatusResult = if (status.isReadyFor(network)) {
                runCatching { activeRpcClient.getAddressWatchStatus(config, address, network) }
            } else {
                null
            }
            if (selectedNetwork == network && rpcConfigRevision == configRevision) {
                nodeStatus = status
                if (snapshot.key.address == address) {
                    addressWatchStatus = watchStatusResult?.getOrNull()
                    addressWatchStatusError = watchStatusResult?.exceptionOrNull()?.message
                }
                refreshing = false
            }
        }
    }

    fun refreshWalletBalance() {
        if (dogecoinBackend == DogecoinBackend.SPV) {
            // SPV: balance comes from the light-client wallet (driven reactively by the sync-status effect);
            // pull a fresh snapshot here too. Skip the RPC-only watch/activity calls entirely.
            val net = selectedNetwork
            val addr = snapshot.key.address
            coroutineScope.launch {
                runCatching { spvDataSource.getBalance(addr, net) }
                    .onSuccess {
                        if (selectedNetwork == net && snapshot.key.address == addr) {
                            walletBalance = it; walletBalanceError = null
                            walletActivity = emptyList(); walletActivityError = null
                        }
                    }  // onFailure: not synced yet — the sync-status card shows progress
            }
            return
        }
        refreshingBalance = true
        walletBalanceError = null
        walletActivityError = null
        rescanError = null
        val network = selectedNetwork
        val address = snapshot.key.address
        val config = savedRpcConfig
        val configRevision = rpcConfigRevision
        val requestGeneration = rpcRequestGeneration.get()
        val activeRpcClient = guardedRpcClient(requestGeneration)
        coroutineScope.launch {
            runCatching {
                walletReadSource(config, activeRpcClient).getBalance(address, network)
            }.onSuccess {
                if (
                    selectedNetwork == network &&
                    snapshot.key.address == address &&
                    rpcConfigRevision == configRevision
                ) {
                    walletBalance = it
                    refreshAddressWatchStatusFromNode(
                        activeRpcClient,
                        config,
                        address,
                        network,
                        configRevision
                    )
                    runCatching {
                        activeRpcClient.getWalletActivity(config, address, network)
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
        val config = savedRpcConfig
        val configRevision = rpcConfigRevision
        val requestGeneration = rpcRequestGeneration.get()
        val activeRpcClient = guardedRpcClient(requestGeneration)
        coroutineScope.launch {
            runCatching {
                activeRpcClient.rescanWalletHistory(config, address, network, startHeight)
                val balance = walletReadSource(config, activeRpcClient).getBalance(address, network)
                val watchStatus = runCatching {
                    activeRpcClient.getAddressWatchStatus(config, address, network)
                }
                val activity = runCatching {
                    activeRpcClient.getWalletActivity(config, address, network)
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
            // Make the dead-end actionable: open the WIF-backup dialog inline so the user can act here instead of
            // hunting up to the Receive card. This records NOTHING on its own — the gate still returns, and the
            // send still requires the recorded backup (matches()) plus the mainnet ack before any tx is built.
            sendError = context.getString(R.string.dogecoin_send_backup_required)
            pendingWifCopy = snapshot.key
            return
        }
        if (shouldConfirmImportingRefresh && !allowWatchImport) {
            pendingWatchImportAction = DogecoinWatchImportAction.REVIEW_SEND
            return
        }

        sending = true
        val network = selectedNetwork
        val wallet = snapshot.key
        val config = savedRpcConfig
        val configRevision = rpcConfigRevision
        val requestGeneration = rpcRequestGeneration.get()
        val activeRpcClient = guardedRpcClient(requestGeneration)
        val feePerKbKoinu = DogecoinAmount.toKoinu(feeRate)
        val minimumOutputKoinu = minimumSendOutputKoinu
        val requestLabel = paymentRequestLabel
        val requestMessage = paymentRequestMessage
        // The target itself was captured at Pay tap. Bind it to this reviewed transaction only when the
        // originating request still matches the actual network/address; an edited recipient gets no receipt.
        val reviewedPaymentReceiptContext = availablePaymentReceiptContext?.takeIf { receiptContext ->
            DogecoinPaymentRequest.parse(receiptContext.paymentUri)?.let { request ->
                request.network == network && request.address == recipient
            } == true
        }
        coroutineScope.launch {
            runCatching {
                val utxos = walletReadSource(config, activeRpcClient).listUnspent(wallet.address, network)
                if (dogecoinBackend != DogecoinBackend.SPV) {
                    refreshAddressWatchStatusFromNode(
                        activeRpcClient,
                        config,
                        wallet.address,
                        network,
                        configRevision
                    )
                }
                val signedTransaction = DogecoinTransactionBuilder.createSignedTransaction(
                    wallet = wallet,
                    utxos = utxos,
                    recipientAddress = recipient,
                    amount = dogeAmount,
                    network = network,
                    feePerKbKoinu = feePerKbKoinu,
                    minimumOutputKoinu = minimumOutputKoinu
                )
                val mempoolAcceptance = if (dogecoinBackend == DogecoinBackend.SPV) {
                    // No testmempoolaccept under SPV. checked=false records "no node policy check ran"
                    // (honest), satisfies the check below, and auto-engages the MAINNET ack in Phase 4.
                    // requiresPolicyUnavailableAcknowledgement() is MAINNET-gated, so no ack pops on testnet.
                    DogecoinMempoolAcceptance(checked = false, allowed = null)
                } else {
                    activeRpcClient.testMempoolAcceptance(
                        config = config,
                        rawTransactionHex = signedTransaction.rawTransactionHex,
                        network = network
                    )
                }
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
                    pendingPaymentReceiptContext = reviewedPaymentReceiptContext
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

    fun broadcastSignedTransaction(transaction: DogecoinSignedTransaction) {
        val nowMillis = System.currentTimeMillis()
        if (transaction.network == DogecoinNetwork.MAINNET && !wifCopyState.matches(snapshot.key)) {
            // Defense-in-depth: a MAINNET broadcast must never proceed without a WIF backup, independently of
            // reviewSend()'s identical gate — so the broadcast path itself enforces every mainnet precondition
            // (this + the acks below) before mainnetAuthorized=true is passed to the SPV broadcast.
            sendError = context.getString(R.string.dogecoin_send_backup_required)
            pendingWifCopy = snapshot.key   // open the backup dialog inline; still requires Copy + the mainnet ack to record
            return
        }
        if (transaction.network != selectedNetwork) {
            pendingTransaction = null
            pendingPaymentReceiptContext = null
            mainnetBroadcastAcknowledged = false
            highFeeAcknowledged = false
            policyUnavailableAcknowledged = false
            sendError = context.getString(R.string.dogecoin_send_network_changed)
            return
        }
        if (transaction.isExpired(nowMillis, DOGECOIN_SIGNED_TX_MAX_AGE_MILLIS)) {
            pendingTransaction = null
            pendingPaymentReceiptContext = null
            mainnetBroadcastAcknowledged = false
            highFeeAcknowledged = false
            policyUnavailableAcknowledged = false
            sendError = context.getString(R.string.dogecoin_send_review_expired)
            return
        }
        if (!transaction.hasConsistentRawTransactionId()) {
            pendingTransaction = null
            pendingPaymentReceiptContext = null
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
        val config = savedRpcConfig
        val configRevision = rpcConfigRevision
        val requestGeneration = rpcRequestGeneration.get()
        val activeRpcClient = guardedRpcClient(requestGeneration)
        val claimedPaymentReceiptContext = pendingPaymentReceiptContext.takeIf {
            pendingTransaction?.txid == transaction.txid
        }
        coroutineScope.launch {
            runCatching {
                transaction.requireConsistentRawTransactionId()
                val currentUtxos = walletReadSource(config, activeRpcClient).listUnspent(address, network)
                transaction.requireSelectedInputsStillSpendable(currentUtxos)
                if (dogecoinBackend == DogecoinBackend.SPV) {
                    // Built-in light client: peers relay only; a returned txid is CLAIMED, not accepted.
                    // The receipt below stays Claimed until the on-chain confirmationDepth poll corroborates.
                    // mainnetAuthorized = true is SAFE here: this line is reached only AFTER the send flow's
                    // mainnet gates have all passed — WIF-backup (above), and the mainnet / high-fee /
                    // policy-unavailable acknowledgements enforced by canExportOrBroadcastSignedDogecoinTransaction.
                    spvDataSource.broadcast(transaction.rawTransactionHex, network, mainnetAuthorized = true)
                } else {
                    refreshAddressWatchStatusFromNode(
                        activeRpcClient,
                        config,
                        address,
                        network,
                        configRevision
                    )
                    activeRpcClient.sendRawTransaction(config, transaction.rawTransactionHex, network)
                }
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
                        requestMessage = transaction.requestMessage,
                        // Keep the structured manual-share path behind the same byte-derived txid equality
                        // required by automatic emission. The legacy human receipt remains unchanged.
                        paymentContext = claimedPaymentReceiptContext.takeIf { txid == transaction.txid },
                        viaSpvClaimedOnly = dogecoinBackend == DogecoinBackend.SPV
                    )
                    if (txid == transaction.txid && claimedPaymentReceiptContext != null) {
                        onPaymentReceiptClaimed(
                            claimedPaymentReceiptContext,
                            DogepaidBroadcastClaim(
                                network = transaction.network,
                                txid = txid,
                                amountKoinu = transaction.sendAmountKoinu,
                                recipientAddress = transaction.recipientAddress
                            )
                        )
                    }
                    if (claimedPaymentReceiptContext != null) {
                        // Any successful backend return consumes this Pay session, including the defensive
                        // impossible/mismatched-txid branch where structured emission is intentionally refused.
                        availablePaymentReceiptContext = null
                    }
                    pendingPaymentReceiptContext = null
                    if (dogecoinBackend == DogecoinBackend.SPV && txid == transaction.txid) {
                        // Claimed -> Confirmed corroboration from our OWN synced chain (no third party).
                        // Leaves the receipt Claimed if depth never reaches 1 within the poll budget.
                        coroutineScope.launch {
                            repeat(DOGECOIN_SPV_CORROBORATION_POLLS) {
                                // Stop promptly if the receipt was dismissed/replaced or the backend switched.
                                if (dogecoinBackend != DogecoinBackend.SPV || sentReceipt?.txid != txid) return@launch
                                val depth = withContext(Dispatchers.IO) {
                                    spvService.confirmationDepth(network, txid)
                                }
                                if (depth != null && depth >= 1) {
                                    val latest = sentReceipt
                                    if (selectedNetwork == network && snapshot.key.address == address &&
                                        latest != null && latest.txid == txid) {
                                        sentReceipt = latest.copy(viaSpvClaimedOnly = false)
                                    }
                                    return@launch
                                }
                                delay(DOGECOIN_SPV_CORROBORATION_INTERVAL_MS)
                            }
                        }
                    }
                    sendAmount = ""
                    // Land back on the focal/balance view so the confirmation ring (keyed on sentReceipt.txid)
                    // is immediately visible; the receipt + txid-copy are surfaced there too. Presentation-only,
                    // reached only after a successful broadcast inside the active-key/network relevance guard.
                    walletAction = DogeWalletAction.NONE
                    Toast.makeText(
                        context,
                        context.getString(R.string.dogecoin_transaction_broadcast),
                        Toast.LENGTH_SHORT
                    ).show()
                    runCatching {
                        walletReadSource(config, activeRpcClient).getBalance(address, network)
                    }.onSuccess { balance ->
                        if (
                            selectedNetwork == network &&
                            snapshot.key.address == address &&
                            rpcConfigRevision == configRevision
                        ) {
                            walletBalance = balance
                            walletBalanceError = null
                            if (dogecoinBackend != DogecoinBackend.SPV) {
                                refreshAddressWatchStatusFromNode(
                                    activeRpcClient,
                                    config,
                                    address,
                                    network,
                                    configRevision
                                )
                            }
                            runCatching {
                                if (dogecoinBackend == DogecoinBackend.SPV) emptyList()
                                else activeRpcClient.getWalletActivity(config, address, network)
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
            pendingPaymentReceiptContext = null
            mainnetBroadcastAcknowledged = false
            highFeeAcknowledged = false
            policyUnavailableAcknowledged = false
            sendError = context.getString(R.string.dogecoin_send_network_changed)
            return
        }
        if (transaction.isExpired(nowMillis, DOGECOIN_SIGNED_TX_MAX_AGE_MILLIS)) {
            pendingTransaction = null
            pendingPaymentReceiptContext = null
            mainnetBroadcastAcknowledged = false
            highFeeAcknowledged = false
            policyUnavailableAcknowledged = false
            sendError = context.getString(R.string.dogecoin_send_review_expired)
            return
        }
        if (!transaction.hasConsistentRawTransactionId()) {
            pendingTransaction = null
            pendingPaymentReceiptContext = null
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
        val config = savedRpcConfig
        val configRevision = rpcConfigRevision
        val requestGeneration = rpcRequestGeneration.get()
        val activeRpcClient = guardedRpcClient(requestGeneration)
        coroutineScope.launch {
            runCatching {
                transaction.requireConsistentRawTransactionId()
                val currentUtxos = walletReadSource(config, activeRpcClient).listUnspent(address, network)
                transaction.requireSelectedInputsStillSpendable(currentUtxos)
                refreshAddressWatchStatusFromNode(
                    activeRpcClient,
                    config,
                    address,
                    network,
                    configRevision
                )
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
        walletAction = DogeWalletAction.SEND
        coroutineScope.launch {
            try {
                listState.animateScrollToItem(0)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    fun clearReviewedSendState() {
        sentReceipt = null
        pendingTransaction = null
        pendingPaymentReceiptContext = null
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
        // A QR/clipboard/address-field request is a different origin. Even if it happens to reuse the
        // same address, it must not inherit the chat conversation or reqRef captured by an earlier Pay tap.
        if (availablePaymentReceiptContext?.paymentUri != request.uri) {
            availablePaymentReceiptContext = null
        }
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

    // Guardrail: there is deliberately NO automatic node probe here. Opening the wallet or editing node
    // settings performs zero RPC I/O (no credentials, no wallet address on the wire); node status is only
    // fetched by the explicit Test connection / Check node status actions.

    LaunchedEffect(paymentRequest?.uri) {
        val request = paymentRequest ?: return@LaunchedEffect
        applyPaymentRequest(request)
    }

    // Phase 2 SPV lifecycle: start the built-in light client when selected (and supported), stop otherwise.
    // Sync-on-demand — also stopped when the sheet leaves composition.
    // Keyed on persistedBackend (NOT the assist-effective backend): home-node assist must leave the light
    // client syncing in the background, so enabling assist never stops SPV here.
    LaunchedEffect(persistedBackend, selectedNetwork) {
        if (persistedBackend == DogecoinBackend.SPV && spvService.isSupported(selectedNetwork)) {
            if (!nodeAssist) walletBalance = null  // show "syncing" until the light client reads a balance
            // start() opens the mmap block store + loads checkpoints + builds the chain — keep it off Main.
            withContext(Dispatchers.IO) { spvService.start(selectedNetwork) }
        } else {
            withContext(Dispatchers.IO) { spvService.stop() }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            rpcRequestGeneration.incrementAndGet()
            draftTestGeneration.incrementAndGet()
            spvService.stop()
        }
    }

    // Home-node assist toggles: clear the displayed balance BEFORE the re-read so a stale value from the
    // other source is never shown under the new source's label (an SPV balance captioned "My node" would
    // claim node provenance the node can't back — and vice versa). This effect's block comes from the
    // post-flip composition, so refreshWalletBalance() reads through the assist-effective backend.
    LaunchedEffect(nodeAssist) {
        walletBalance = null
        walletBalanceError = null
        if (nodeAssist) refreshWalletBalance()
        // On revert the SPV reactive-balance effect repopulates (it restarts on the backend flip).
    }

    // Reactively pull the SPV balance/UTXOs as the light client syncs (chainHeight climbs as it finds our
    // funding txs). RPC/explorer balances are driven by refreshWalletBalance() instead.
    LaunchedEffect(dogecoinBackend, spvStatus.chainHeight, spvStatus.synced) {
        if (dogecoinBackend != DogecoinBackend.SPV) return@LaunchedEffect
        val net = selectedNetwork
        val addr = snapshot.key.address
        runCatching { spvDataSource.getBalance(addr, net) }
            .onSuccess {
                if (selectedNetwork == net && snapshot.key.address == addr) {
                    walletBalance = it
                    walletBalanceError = null
                    walletActivity = emptyList()  // SPV has no rich activity history yet
                    walletActivityError = null
                }
            }  // onFailure: not synced/active enough to read yet — the sync status conveys progress
    }

    // Sync ETA (presentation-only): while syncing, average headers/sec since this run's anchor and surface the
    // minutes-left. Recomputed on a slow tick (not only on chainHeight changes) so a stall AGES the estimate up
    // (dMs grows while dHeight is frozen -> rate falls -> ETA rises) instead of freezing an optimistic number;
    // re-anchored on a chain regression (rescan); skipped entirely once essentially caught up. Reads the
    // StateFlow directly so each tick sees the fresh height. Cosmetic — never gates anything.
    var syncEtaMinutes by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(dogecoinBackend, selectedNetwork) {
        var anchorHeight = -1
        var anchorTimeMs = 0L
        while (true) {
            val st = spvService.status.value
            val isSyncing = dogecoinBackend == DogecoinBackend.SPV && st.running && !st.synced
            if (!isSyncing || st.chainHeight <= 0 || st.blocksBehind <= 0) {
                anchorHeight = -1
                syncEtaMinutes = null
            } else {
                val now = android.os.SystemClock.elapsedRealtime()
                if (anchorHeight < 0 || st.chainHeight < anchorHeight) {
                    // Start (or restart on a regression) the measurement window.
                    anchorHeight = st.chainHeight
                    anchorTimeMs = now
                    syncEtaMinutes = null
                } else {
                    val dHeight = st.chainHeight - anchorHeight
                    val dMs = now - anchorTimeMs
                    // Wait for a meaningful window so the first jumpy samples don't flash a wild ETA.
                    if (dHeight >= 10 && dMs >= 4_000L) {
                        val rate = dHeight.toDouble() / (dMs / 1000.0)  // headers/sec
                        if (rate > 0.0) {
                            syncEtaMinutes = kotlin.math.ceil(st.blocksBehind / rate / 60.0)
                                .toInt().coerceIn(1, 24 * 60)
                        }
                    }
                }
            }
            delay(5_000L)
        }
    }

    // Confirmation ring fill (presentation-only): track an SPV-sent tx's depth 0..target so the focal ring can
    // fill 0->6 as blocks land. Independent of the receipt's Claimed->Confirmed corroboration poll above (which
    // flips at depth 1); this one runs to the target. Bounded so a never-confirming tx eventually reverts the
    // ring to the idle balance. Reads only confirmationDepth() — never touches the money path.
    var confirmingDepth by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(sentReceipt?.txid, dogecoinBackend, selectedNetwork) {
        val receipt = sentReceipt
        if (receipt == null || dogecoinBackend != DogecoinBackend.SPV || receipt.network != selectedNetwork) {
            confirmingDepth = null
            return@LaunchedEffect
        }
        val txid = receipt.txid
        repeat(DOGECOIN_SPV_CONFIRM_POLLS) {
            // null = the tx isn't (yet) known to the SPV wallet (e.g. lost/replaced) — show NO confirming ring
            // rather than a fake, never-progressing "0 of 6"; a known tx reports depth 0..target and drives the fill.
            val depth = withContext(Dispatchers.IO) { spvService.confirmationDepth(selectedNetwork, txid) }
            confirmingDepth = depth?.coerceIn(0, DOGECOIN_SPV_CONFIRM_TARGET)
            if (depth != null && depth >= DOGECOIN_SPV_CONFIRM_TARGET) return@LaunchedEffect
            delay(DOGECOIN_SPV_CORROBORATION_INTERVAL_MS)
        }
        // Budget exhausted without reaching the target — stop showing a stale count; revert to idle/balance.
        confirmingDepth = null
    }

    // Activity / pending list (SPV): poll the READ-ONLY wallet-tx snapshot so the pending cards + full activity
    // list reflect confirmations climbing 0->target. Incoming txs appear here automatically (bloom-matched), so
    // the RECEIVING phone sees an inbound payment confirm without any extra plumbing. Never feeds signing.
    var spvTxs by remember { mutableStateOf<List<DogecoinSpvTx>>(emptyList()) }
    LaunchedEffect(dogecoinBackend, selectedNetwork) {
        if (dogecoinBackend != DogecoinBackend.SPV) {
            spvTxs = emptyList()
            return@LaunchedEffect
        }
        while (true) {
            spvTxs = withContext(Dispatchers.IO) {
                spvService.snapshotTransactions(selectedNetwork) ?: emptyList()
            }
            delay(DOGECOIN_SPV_CORROBORATION_INTERVAL_MS)
        }
    }
    // Backend-agnostic rows: SPV from the wallet snapshot, RPC from getWalletActivity (already polled below).
    val txRows: List<WalletTxRow> = remember(spvTxs, walletActivity, dogecoinBackend) {
        if (dogecoinBackend == DogecoinBackend.SPV) {
            spvTxs.map { WalletTxRow(it.txid, it.incoming, it.amountKoinu, it.confirmations, it.timeSeconds) }
        } else {
            walletActivity.map {
                WalletTxRow(
                    txid = it.txid,
                    incoming = !it.category.equals("send", ignoreCase = true),
                    amountKoinu = kotlin.math.abs(it.amountKoinu),
                    confirmations = it.confirmations.coerceAtLeast(0),
                    timeSeconds = it.timeSeconds
                )
            }
        }
    }
    // "Pending" = not yet at the confirmation target (in-flight, still filling the ring). Shown as cards on the
    // main screen; the rest of the history lives behind "View all".
    val pendingTxRows = txRows.filter { it.confirmations < DOGECOIN_SPV_CONFIRM_TARGET }
    // Tap-to-inspect: which tx's confirmation-detail dialog is open. Keyed by txid (not the row) so the dialog's
    // ring keeps climbing as the 15s poll refreshes the underlying row.
    var walletTxDetailId by remember { mutableStateOf<String?>(null) }

    // Auto-save every recipient on a successful send (per-network) so it appears in the recipient picker without
    // a manual "Save recipient" tap. Runs only AFTER sentReceipt is set (post-broadcast), isolated in runCatching
    // so it can never throw into the send flow, and writes ONLY the suggestion store — it never feeds back into
    // signing/broadcast. Keyed on the recipient so it fires once per distinct send (covers local + peer paths).
    LaunchedEffect(sentReceipt?.recipientAddress, sentReceipt?.network) {
        val receipt = sentReceipt ?: return@LaunchedEffect
        runCatching {
            repository.upsertSavedAddress(receipt.network, receipt.recipientAddress, receipt.requestLabel.orEmpty())
        }.onSuccess { updated -> if (receipt.network == selectedNetwork) savedAddresses = updated }
    }

    BitchatBottomSheet(
        modifier = modifier,
        // Keep the Compose-owned local broadcast coroutine alive until it reports success/failure. Helper
        // broadcasts are app-scope in ChatViewModel and do not rely on this guard.
        onDismissRequest = { if (!sending) onDismiss() }
    ) {
        // Scope the wallet to its own Rams/Dogecoin palette (gold/ink/paper); the rest of the app keeps green.
        DogecoinWalletTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 80.dp, start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(key = "header") {
                    // Per-network accent: mainnet = red (real money, attention); testnet = green; regtest = blue.
                    // Used to tint the Doge mark and the network label so the active network is unmistakable.
                    val netColor = when (selectedNetwork) {
                        DogecoinNetwork.MAINNET -> dogeWalletColors.danger
                        DogecoinNetwork.TESTNET -> Color(0xFF2E7D32)
                        DogecoinNetwork.REGTEST -> Color(0xFF1565C0)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(modifier = Modifier.size(30.dp)) {
                                    val dogePainter = painterResource(R.drawable.doge_coin)
                                    // Mainnet (real money) shows the full-colour Shiba untouched.
                                    Image(
                                        painter = dogePainter,
                                        contentDescription = "${selectedNetwork.displayName} Dogecoin network",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    // Test/regtest: a translucent green/blue WASH laid over the original art
                                    // (keeps the Doge's detail, unlike a flat silhouette) so you can never
                                    // confuse play money for real.
                                    if (selectedNetwork != DogecoinNetwork.MAINNET) {
                                        Image(
                                            painter = dogePainter,
                                            contentDescription = null,
                                            colorFilter = ColorFilter.tint(netColor),
                                            alpha = 0.5f,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                                Text(
                                    text = stringResource(R.string.dogecoin_wallet_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            // The settings gear (network / backend / advanced) is hidden in the SIMPLE profile so a
                            // non-technical user can't change the network or break connectivity. The money-path
                            // gates (WIF backup, mainnet/high-fee/policy acks, fail-closed verifier) are unaffected.
                            if (walletAction == DogeWalletAction.NONE && !isSimpleProfile) {
                                IconButton(onClick = { walletAction = DogeWalletAction.SETTINGS }) {
                                    Icon(
                                        imageVector = Icons.Filled.Settings,
                                        contentDescription = "Settings",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                        Text(
                            text = selectedNetwork.displayName.uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = netColor
                        )
                    }
                }

                // Back affordance shown only inside a Send/Receive flow.
                if (walletAction != DogeWalletAction.NONE) {
                    item(key = "action_back") {
                        Text(
                            text = "‹  " + when (walletAction) {
                                DogeWalletAction.SEND -> "Send"
                                DogeWalletAction.RECEIVE -> "Receive"
                                DogeWalletAction.ACTIVITY -> "Activity"
                                else -> "Settings"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = dogeWalletColors.ink,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { walletAction = DogeWalletAction.NONE }
                                .padding(vertical = 4.dp)
                        )
                    }
                }

                if (walletAction == DogeWalletAction.NONE) {
                // "Coin" hero: the balance lives inside the one gold ring (idle solid / syncing arc).
                item(key = "focal") {
                    val spv = dogecoinBackend == DogecoinBackend.SPV
                    val s = spvStatus
                    val colors = dogeWalletColors
                    // A pending SPV send fills the ring 0..target as confirmations land. The depth comes from our
                    // OWN chain head (confirmationDepth()), accurate regardless of peer count — so show CONFIRMING
                    // even when the strict `synced` flag momentarily flaps (a peer dip below the floor, or a fresh
                    // block a block or two ahead), as long as we're effectively at the tip. A genuine backlog
                    // (> DOGECOIN_SPV_NEARTIP_BLOCKS) keeps it in SYNCING.
                    val confDepth = confirmingDepth
                    val hasPending = spv && confDepth != null && confDepth < DOGECOIN_SPV_CONFIRM_TARGET
                    val nearTip = s.bestPeerHeight > 0L && s.blocksBehind <= DOGECOIN_SPV_NEARTIP_BLOCKS
                    val confirming = hasPending && (s.synced || nearTip)
                    // Mutually exclusive with confirming: only show Syncing when behind AND not filling the ring.
                    val syncing = spv && s.running && !s.synced && !confirming
                    val syncProgress = if (syncing) {
                        val behind = s.blocksBehind.coerceAtLeast(0).toFloat()
                        (1f - behind / (behind + 1500f)).coerceIn(0.04f, 0.97f)
                    } else 1f
                    val bal = walletBalance
                    val ringMode = when {
                        confirming -> RingMode.CONFIRMING
                        syncing -> RingMode.SYNCING
                        else -> RingMode.IDLE
                    }
                    val ringProgress = when {
                        syncing -> syncProgress
                        confirming -> confDepth!!.toFloat() / DOGECOIN_SPV_CONFIRM_TARGET
                        else -> 1f
                    }
                    val ringDesc = when {
                        syncing -> "Syncing, ${s.blocksBehind} blocks behind" +
                            (syncEtaMinutes?.let { ", about $it minutes left" } ?: "")
                        confirming -> "$confDepth of $DOGECOIN_SPV_CONFIRM_TARGET confirmations"
                        else -> bal?.let { "Balance ${DogecoinAmount.formatKoinu(it.confirmedKoinu)} DOGE" }
                            ?: "Balance not loaded"
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        ConfirmationRing(
                            mode = ringMode,
                            progress = ringProgress,
                            diameter = 210.dp,
                            segments = DOGECOIN_SPV_CONFIRM_TARGET,
                            contentDescription = ringDesc
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                when {
                                    syncing -> {
                                        Text("Syncing", style = MaterialTheme.typography.titleMedium, color = colors.ink)
                                        Text(
                                            "${s.blocksBehind} behind",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.muted
                                        )
                                        syncEtaMinutes?.let { eta ->
                                            Text(
                                                "~$eta min left",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.muted
                                            )
                                        }
                                    }
                                    confirming -> {
                                        Text("Confirming", style = MaterialTheme.typography.titleMedium, color = colors.ink)
                                        Text(
                                            "$confDepth of $DOGECOIN_SPV_CONFIRM_TARGET",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.muted
                                        )
                                    }
                                    bal != null -> {
                                        val balText = DogecoinAmount.formatKoinu(bal.confirmedKoinu)
                                        Text(
                                            text = balText,
                                            // Adapt to length so a large balance still fits inside the ring.
                                            fontSize = when {
                                                balText.length <= 8 -> 30.sp
                                                balText.length <= 12 -> 24.sp
                                                balText.length <= 16 -> 19.sp
                                                else -> 15.sp
                                            },
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.ink,
                                            maxLines = 1
                                        )
                                        Text("DOGE", style = MaterialTheme.typography.labelMedium, color = colors.muted)
                                        if (bal.unconfirmedKoinu > 0L) {
                                            Text(
                                                "+${DogecoinAmount.formatKoinu(bal.unconfirmedKoinu)} pending",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.muted
                                            )
                                        }
                                    }
                                    else -> Text("—", style = MaterialTheme.typography.headlineMedium, color = colors.muted)
                                }
                            }
                        }
                        val strip = buildList {
                            add(if (spv) "Built-in" else "My node")
                            // Mirror the ring's own state so the strip can never contradict it (confirming/syncing
                            // are the same locals that drive ringMode; "synced" == the ring showing the idle balance).
                            if (spv) add(if (confirming) "confirming" else if (syncing) "syncing" else if (s.running) "synced" else "starting")
                            if (s.overTor && torReady) add("Tor on") else if (torIntentOn) add("Tor starting")
                        }.joinToString("   ·   ")
                        Text(strip, style = MaterialTheme.typography.labelMedium, color = colors.muted)
                        // Provenance (P0-3): a node-backed balance ("My node" or active home-node assist) is
                        // reported by the node and NOT independently verified by this device. SPV reads come
                        // from our own validated headers, so they carry no such caption.
                        if (!spv) {
                            Text(
                                text = stringResource(R.string.dogecoin_node_reported_provenance),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.muted
                            )
                            // Distinguish "node reachable but reports nothing for this address" (e.g. a
                            // watch-only address the node hasn't caught up on) from an actual read error,
                            // which surfaces separately as walletBalanceError.
                            if (bal != null && bal.confirmedKoinu == 0L && bal.unconfirmedKoinu == 0L && bal.utxoCount == 0) {
                                Text(
                                    text = stringResource(R.string.dogecoin_node_empty_watch),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.muted,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                        if (selectedNetwork == DogecoinNetwork.MAINNET && !wifCopyState.matches(snapshot.key)) {
                            Surface(
                                color = colors.danger.copy(alpha = 0.14f),
                                shape = MaterialTheme.shapes.small,
                                // Straight to the backup flow (not the full Settings menu) — backing up the key
                                // is one focused action, not a settings hunt.
                                modifier = Modifier.clickable { pendingWifCopy = snapshot.key }
                            ) {
                                Text(
                                    text = "!  Back up your key",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.danger,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // Home-node assist (R-C3): offered while the light client is behind and a TRUSTED node
                // endpoint is already SAVED; testnet/regtest only (mainnet reads require the explicit pin
                // flow). Classifier-gated: an unverified public HTTPS / .onion / invalid URL never renders
                // the offer, so it can never be activated (URL syntax is not a trust decision).
                run {
                    val offerVisible = persistedBackend == DogecoinBackend.SPV && !spvStatus.synced &&
                        dogecoinNodeAssistEligible(selectedNetwork, savedEndpointClass)
                    if (nodeAssist || offerVisible) {
                        item(key = "node_assist") {
                            NodeAssistCard(
                                active = nodeAssist,
                                blocksBehind = spvStatus.blocksBehind,
                                notOverTor = torIntentOn,
                                // The balance refresh runs in the LaunchedEffect(nodeAssist) below the state
                                // declarations — a refresh called HERE would capture this composition's
                                // pre-flip backend and read from the wrong source.
                                onUse = { setNodeAssistEnabled(true) },
                                onStop = { setNodeAssistEnabled(false) }
                            )
                        }
                    }
                }

                // In-flight payments (sent or received) each with their own 0->target fill, right under the
                // balance so confirmation progress is visible without opening anything.
                if (pendingTxRows.isNotEmpty()) {
                    item(key = "pending") {
                        WalletCard {
                            Text(
                                text = "Pending",
                                style = MaterialTheme.typography.labelLarge,
                                color = dogeWalletColors.muted
                            )
                            pendingTxRows.take(DOGECOIN_PENDING_CARDS_LIMIT).forEach { row ->
                                WalletTxRowView(row) { walletTxDetailId = row.txid }
                            }
                            val morePending = pendingTxRows.size - DOGECOIN_PENDING_CARDS_LIMIT
                            if (morePending > 0) {
                                Text(
                                    text = "+$morePending more pending",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = dogeWalletColors.muted
                                )
                            }
                        }
                    }
                }

                item(key = "actions") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { walletAction = DogeWalletAction.SEND },
                            modifier = Modifier.weight(1f)
                        ) { Text("Send") }
                        OutlinedButton(
                            onClick = { walletAction = DogeWalletAction.RECEIVE },
                            modifier = Modifier.weight(1f)
                        ) { Text("Receive") }
                    }
                }

                // Full history (sent + received, pending + confirmed) lives one tap away to keep the default clean.
                if (txRows.isNotEmpty()) {
                    item(key = "viewall") {
                        TextButton(
                            onClick = { walletAction = DogeWalletAction.ACTIVITY },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("View all activity  ›") }
                    }
                }

                // After a send we land here; surface a compact receipt so the txid-copy isn't buried in the
                // Send view. The full receipt (share/save) stays reachable via "Details". Presentation-only.
                if (sentReceipt != null) {
                    item(key = "receipt") {
                        sentReceipt?.let { receipt ->
                            val rc = dogeWalletColors
                            WalletCard {
                                Text(
                                    text = when {
                                        receipt.viaSpvClaimedOnly ->
                                            stringResource(R.string.dogecoin_send_receipt_via_spv_claimed)
                                        receipt.viaPeer -> stringResource(
                                            if (receipt.peerCorroborated) {
                                                R.string.dogecoin_send_receipt_via_peer_corroborated
                                            } else {
                                                R.string.dogecoin_send_receipt_via_peer
                                            }
                                        )
                                        else -> "Sent"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = rc.ink
                                )
                                Text(
                                    text = "${DogecoinAmount.formatKoinu(receipt.sendAmountKoinu)} DOGE",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = rc.muted
                                )
                                SelectionContainer {
                                    Text(
                                        text = receipt.txid.take(10) + "…" + receipt.txid.takeLast(8),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = rc.muted
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = {
                                        copy(receipt.txid, context.getString(R.string.dogecoin_txid_copied))
                                    }) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.dogecoin_copy_txid))
                                    }
                                    TextButton(onClick = { walletAction = DogeWalletAction.SEND }) { Text("Details") }
                                    TextButton(onClick = { sentReceipt = null }) { Text("Done") }
                                }
                            }
                        }
                    }
                }

                }
                if (walletAction == DogeWalletAction.ACTIVITY) {
                    item(key = "activity_list") {
                        if (txRows.isEmpty()) {
                            Text(
                                text = "No transactions yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = dogeWalletColors.muted
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                txRows.take(DOGECOIN_ACTIVITY_FULL_LIMIT).forEach { row ->
                                    WalletCard {
                                        WalletTxRowView(row) { walletTxDetailId = row.txid }
                                        SelectionContainer {
                                            Text(
                                                text = row.txid.take(10) + "…" + row.txid.takeLast(8),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = dogeWalletColors.muted
                                            )
                                        }
                                        TextButton(onClick = {
                                            copy(row.txid, context.getString(R.string.dogecoin_txid_copied))
                                        }) {
                                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.dogecoin_copy_txid))
                                        }
                                    }
                                }
                                val hidden = txRows.size - DOGECOIN_ACTIVITY_FULL_LIMIT
                                if (hidden > 0) {
                                    Text(
                                        text = "+$hidden older",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = dogeWalletColors.muted
                                    )
                                }
                            }
                        }
                    }
                }
                if (walletAction == DogeWalletAction.SETTINGS) {
                item(key = "backend") {
                    WalletCard {
                        Text(
                            text = "Connection",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        val backendOptions = buildList {
                            add(DogecoinBackend.RPC to "My node")
                            if (selectedNetwork != DogecoinNetwork.REGTEST) add(DogecoinBackend.SPV to "Built-in")
                        }
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            backendOptions.forEachIndexed { index, option ->
                                SegmentedButton(
                                    selected = persistedBackend == option.first,
                                    onClick = {
                                        if (persistedBackend != option.first || nodeAssist) {
                                            invalidateRpcRuntimeState()
                                        }
                                        persistedBackend = option.first
                                        // An explicit selector choice supersedes any session-only assist.
                                        nodeAssist = false
                                        repository.saveBackend(selectedNetwork, option.first)
                                        // SPV is driven by its own lifecycle/sync effects; for a node backend
                                        // re-pull the balance immediately on switch.
                                        if (option.first != DogecoinBackend.SPV) refreshWalletBalance()
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = backendOptions.size)
                                ) { Text(option.second) }
                            }
                        }
                        Text(
                            text = when {
                                dogecoinBackend != DogecoinBackend.SPV ->
                                    "Connects to your own Dogecoin Core node over RPC."
                                !torIntentOn ->
                                    "Built-in light client: syncs block headers from the Dogecoin network on-device — no node or API key needed. It connects to Dogecoin peers directly over the internet, so your address may be linkable to your IP. Turn on Tor in Settings to route the light client over Tor."
                                // Claim "IP hidden" only when the live PeerGroup was ACTUALLY built over Tor (overTor)
                                // AND Tor is bootstrapped — never on mere intent, so the text can't over-promise.
                                spvStatus.overTor && torReady ->
                                    "Built-in light client: syncs block headers on-device — no node or API key needed. Peer connections are routed over Tor, so your IP is hidden from Dogecoin peers. (The one-time seed-node lookup uses your device's normal DNS.)"
                                else ->
                                    "Built-in light client: starting up over Tor — it never connects directly while Tor is on. Your balance appears once Tor is ready and the headers sync."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            lineHeight = 18.sp
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
                        // Draft-first controls: editing performs zero I/O; Test probes the draft once
                        // (never persisted), Save is the ONLY way the draft becomes the active config.
                        if (rpcDraftDirty) {
                            Text(
                                text = stringResource(R.string.dogecoin_node_draft_unsaved),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                lineHeight = 18.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { testDraftConnection() },
                                enabled = !draftTesting && rpcUrl.trim().isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.dogecoin_node_test_connection))
                            }
                            Button(
                                onClick = { saveNodeSettings() },
                                enabled = rpcDraftDirty,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.dogecoin_node_save_settings))
                            }
                        }
                        // P0-3: make the draft-vs-saved split explicit so a failed draft Test is not mistaken
                        // for a broken wallet — the saved config (used by real reads/sends) is unaffected until Save.
                        Text(
                            text = stringResource(R.string.dogecoin_node_test_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            lineHeight = 16.sp
                        )
                        if (draftTesting) {
                            Text(
                                text = stringResource(R.string.dogecoin_node_testing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                lineHeight = 18.sp
                            )
                        }
                        draftTestStatus?.let { testStatus ->
                            Text(
                                text = stringResource(R.string.dogecoin_node_draft_test_note),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                            NodeStatusRow(
                                status = testStatus,
                                refreshing = false,
                                network = selectedNetwork
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                        // When the on-screen draft differs from the saved config, the status below (and the
                        // real reads/sends) still reflect the SAVED settings — say so to avoid confusion.
                        if (rpcDraftDirty) {
                            Text(
                                text = stringResource(R.string.dogecoin_node_status_reflects_saved),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                lineHeight = 16.sp
                            )
                        }
                        NodeStatusRow(
                            status = nodeStatus,
                            refreshing = refreshing,
                            network = selectedNetwork
                        )
                        when {
                            refreshing -> Text(
                                text = stringResource(R.string.dogecoin_node_checking, selectedNetwork.displayName),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                lineHeight = 18.sp
                            )
                            savedRpcConfig.url.isNotBlank() && !savedEndpointTrusted -> Text(
                                text = dogecoinEndpointBlockedReason(savedEndpointClass, context),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 18.sp
                            )
                            nodeStatus == null && savedRpcConfig.url.isNotBlank() -> Text(
                                text = stringResource(R.string.dogecoin_node_recheck_required),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                lineHeight = 18.sp
                            )
                        }
                        Button(
                            onClick = { refreshNodeStatus() },
                            enabled = !refreshing && savedRpcConfig.url.isNotBlank() && savedEndpointTrusted,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.refresh_node_status))
                        }
                    }
                }
                }

                if (dogecoinBackend == DogecoinBackend.SPV && walletAction == DogeWalletAction.SETTINGS) {
                item(key = "spv_status") {
                    WalletCard {
                        Text(
                            text = "Built-in node",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        val s = spvStatus
                        val line = when {
                            !s.running -> "Starting…"
                            s.peerCount == 0 -> "Connecting to the Dogecoin network…"
                            s.synced -> "Synced · block ${s.chainHeight} · ${s.peerCount} peer(s)"
                            s.bestPeerHeight > 0L -> "Syncing… ${s.blocksBehind} blocks behind · ${s.peerCount} peer(s)"
                            else -> "Syncing… block ${s.chainHeight} · ${s.peerCount} peer(s)"
                        }
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (s.synced) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        if (!s.synced) {
                            Text(
                                text = "Your balance appears as the headers catch up to the tip.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                lineHeight = 18.sp
                            )
                        }
                        // Honest transport line: overTor reflects how the PeerGroup was actually built, not just intent.
                        val torNote = when {
                            s.overTor && !torReady -> "Routing over Tor — waiting for Tor to finish starting…"
                            s.overTor -> "Connections are routed over Tor."
                            else -> null
                        }
                        if (torNote != null) {
                            Text(
                                text = torNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
                }

                if (walletAction == DogeWalletAction.SETTINGS) {
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
                                style = MaterialTheme.typography.headlineMedium,
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
                            // P0-3 provenance: a node-backed balance is node-reported, not independently
                            // verified; an all-zero node read means the node sees nothing for this address
                            // yet (distinct from a read error, which sets walletBalanceError above).
                            if (dogecoinBackend != DogecoinBackend.SPV) {
                                Text(
                                    text = stringResource(R.string.dogecoin_node_reported_provenance),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                )
                                if (balance.confirmedKoinu == 0L && balance.unconfirmedKoinu == 0L && balance.utxoCount == 0) {
                                    Text(
                                        text = stringResource(R.string.dogecoin_node_empty_watch),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                            if (refreshing) {
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
                                Text(
                                    text = if (showUtxoDetails) stringResource(R.string.dogecoin_hide_coins)
                                    else stringResource(R.string.dogecoin_show_coins, balance.utxoCount),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { showUtxoDetails = !showUtxoDetails }
                                )
                                if (showUtxoDetails) {
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
                }

                if (walletAction == DogeWalletAction.RECEIVE) {
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
                            // Sends are gated by the backup ONLY on mainnet, so don't imply a hard requirement on
                            // testnet ("before funding or sending"). Mainnet keeps the urgent wording.
                            text = when {
                                wifCopyRecorded -> stringResource(
                                    R.string.dogecoin_wif_copy_recorded,
                                    formatDogecoinWalletTime(wifCopyState.copiedAtMillis)
                                )
                                selectedNetwork == DogecoinNetwork.MAINNET ->
                                    stringResource(R.string.dogecoin_wif_copy_missing)
                                else -> stringResource(R.string.dogecoin_wif_copy_missing_testnet)
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

                item(key = "request") {
                    WalletCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showRequest = !showRequest },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.dogecoin_request_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Icon(
                                imageVector = if (showRequest) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = null
                            )
                        }
                        if (showRequest) {
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
                }
                }
                if (walletAction == DogeWalletAction.SEND) {
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
                            // Dropdown of previously-used recipients (auto-saved on each successful send). Pick one
                            // to fill the field; the trailing trash removes it. A selection flows through the same
                            // updateSendAddressInput() path as typing/paste, so it is re-validated downstream.
                            var recipientsExpanded by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { recipientsExpanded = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = stringResource(R.string.dogecoin_saved_recipients_title) +
                                            "  (${savedAddresses.size})",
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text("▾")
                                }
                                DropdownMenu(
                                    expanded = recipientsExpanded,
                                    onDismissRequest = { recipientsExpanded = false }
                                ) {
                                    savedAddresses.forEach { savedAddress ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
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
                                            },
                                            onClick = {
                                                updateSendAddressInput(savedAddress.address)
                                                recipientsExpanded = false
                                            },
                                            trailingIcon = {
                                                IconButton(onClick = { removeRecipientAddress(savedAddress.address) }) {
                                                    Icon(
                                                        Icons.Filled.Delete,
                                                        contentDescription = stringResource(
                                                            R.string.dogecoin_saved_recipient_remove
                                                        )
                                                    )
                                                }
                                            }
                                        )
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
                                // Same string the send gate sets, so the user never sees two different sentences
                                // for one missing-backup condition.
                                text = stringResource(R.string.dogecoin_send_backup_required),
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
                                if (receipt.viaSpvClaimedOnly) {
                                    Text(
                                        text = stringResource(R.string.dogecoin_send_receipt_via_spv_claimed),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        lineHeight = 16.sp
                                    )
                                }
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
                                        val paymentContext = receipt.paymentContext
                                        if (paymentContext != null) {
                                            // Manual share and automatic share converge on the ViewModel's
                                            // persisted (network, txid) reservation, so they cannot duplicate.
                                            onPaymentReceiptClaimed(
                                                paymentContext,
                                                DogepaidBroadcastClaim(
                                                    network = receipt.network,
                                                    txid = receipt.txid,
                                                    amountKoinu = receipt.sendAmountKoinu,
                                                    recipientAddress = receipt.recipientAddress
                                                )
                                            )
                                        } else {
                                            onShareToChat(receiptText)
                                        }
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                if (paymentContext != null) {
                                                    R.string.dogepaid_receipt_share_queued
                                                } else {
                                                    R.string.dogecoin_receipt_shared
                                                }
                                            ),
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
                        // Phase 4: mainnet SPV sending is enabled. The mainnet-specific safety gates (WIF-backup
                        // + mainnet/high-fee/policy-unavailable acks) are enforced in reviewSend()/the broadcast
                        // flow, mirroring the configured-node mainnet path — so the button only needs a synced SPV.
                        val spvSendReady = dogecoinBackend == DogecoinBackend.SPV && spvStatus.synced
                        Button(
                            onClick = { reviewSend() },
                            enabled = !sending && !rescanning &&
                                ((dogecoinBackend != DogecoinBackend.SPV && nodeReady) || spvSendReady),
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
                        if (dogecoinBackend == DogecoinBackend.SPV && !spvStatus.synced) {
                            Text(
                                text = "Syncing the built-in light client… sending unlocks once it catches up " +
                                    "(peers ${spvStatus.peerCount}, ${spvStatus.blocksBehind} blocks behind).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
                }

                if (walletAction == DogeWalletAction.SETTINGS) {
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
                                    (
                                        savedEndpointTrusted &&
                                            (
                                                selectedNetwork != DogecoinNetwork.MAINNET ||
                                                    helperMainnetConsent
                                                )
                                        ),
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

                // 3b.1: sender-side on-chain corroboration. Meaningful only where a public explorer exists
                // (not regtest). When a single helper claims a broadcast, optionally confirm the txid via
                // an external block explorer to upgrade the receipt from "claimed" to "confirmed".
                if (selectedNetwork != DogecoinNetwork.REGTEST) {
                    item(key = "corroboration") {
                        WalletCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.dogecoin_corroboration_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = onChainCorroborationEnabled,
                                    onCheckedChange = { enabled ->
                                        onChainCorroborationEnabled = enabled
                                        repository.saveOnChainCorroborationEnabled(selectedNetwork, enabled)
                                    }
                                )
                            }
                            Text(
                                text = stringResource(R.string.dogecoin_corroboration_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                lineHeight = 16.sp
                            )
                            if (onChainCorroborationEnabled) {
                                OutlinedTextField(
                                    value = explorerUrlTemplate,
                                    onValueChange = {
                                        explorerUrlTemplate = it
                                        repository.saveExplorerUrlTemplate(selectedNetwork, it)
                                    },
                                    label = { Text(stringResource(R.string.dogecoin_corroboration_url_label)) },
                                    supportingText = {
                                        Text(stringResource(R.string.dogecoin_corroboration_url_hint))
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
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
            }

            CloseButton(
                onClick = { if (!sending) onDismiss() },
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
            val receiptPaymentContext = pendingPaymentReceiptContext
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
                paymentContext = receiptPaymentContext,
                viaPeer = true,
                peerCorroborated = corroborated
            )
            if (receiptPaymentContext != null) {
                // ChatViewModel owns automatic emission for helper broadcasts so it survives sheet dismissal.
                // This local effect only consumes the one-shot UI context and builds the existing receipt.
                availablePaymentReceiptContext = null
            }
            pendingPaymentReceiptContext = null
            sendAmount = ""
            // Return to the focal/balance view (where the receipt + txid-copy now live) after a successful
            // peer broadcast — same as the local path. Guarded by resultTxid == transaction.txid above.
            walletAction = DogeWalletAction.NONE
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
        val canBroadcastViaSpv = dogecoinBackend == DogecoinBackend.SPV && spvStatus.synced &&
            transaction.network == selectedNetwork
        // Route-mirroring: gate the confirm button on EXACTLY the backend broadcastSignedTransaction will use,
        // so the button can't enable via a configured node while routing actually goes to an unsynced SPV.
        // (Phase 4: mainnet SPV broadcast is allowed; its WIF-backup + mainnet/high-fee/policy acks are enforced
        // separately by canExportOrBroadcastSignedDogecoinTransaction before the tx reaches the wire.)
        val canBroadcastNow = if (dogecoinBackend == DogecoinBackend.SPV) canBroadcastViaSpv else canBroadcastThroughConfiguredNode

        AlertDialog(
            onDismissRequest = {
                if (!sending) {
                    pendingTransaction = null
                    pendingPaymentReceiptContext = null
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
                    if (!transactionReviewExpired && !canBroadcastThroughConfiguredNode &&
                        hasHelperCandidate && dogecoinBackend != DogecoinBackend.SPV) {
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
                            onClick = { onRequestPeerBroadcast(transaction, pendingPaymentReceiptContext) },
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
                        canBroadcastNow
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
                        pendingPaymentReceiptContext = null
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

    // Tap-a-tx detail: the live confirmation ring for one transaction (the user's "click a tx to check its
    // status"). The row is looked up by id each recomposition so the ring climbs as the poll refreshes; if the
    // tx vanishes (e.g. a backend/network switch) the dialog closes itself. Presentation-only.
    val txDetailRow = walletTxDetailId?.let { id -> txRows.firstOrNull { it.txid == id } }
    if (walletTxDetailId != null && txDetailRow == null) {
        walletTxDetailId = null
    }
    if (txDetailRow != null) {
        DogecoinWalletTheme {
            val target = DOGECOIN_SPV_CONFIRM_TARGET
            val confirmed = txDetailRow.confirmations >= target
            AlertDialog(
                onDismissRequest = { walletTxDetailId = null },
                title = { Text(if (txDetailRow.incoming) "Incoming payment" else "Outgoing payment") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ConfirmationRing(
                            mode = if (confirmed) RingMode.IDLE else RingMode.CONFIRMING,
                            progress = if (confirmed) 1f else txDetailRow.confirmations.toFloat() / target,
                            diameter = 150.dp,
                            segments = target,
                            contentDescription = if (confirmed) "Confirmed"
                                else "${txDetailRow.confirmations} of $target confirmations"
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (confirmed) {
                                    Text("Confirmed", style = MaterialTheme.typography.titleMedium, color = dogeWalletColors.ink)
                                } else {
                                    Text("Confirming", style = MaterialTheme.typography.titleMedium, color = dogeWalletColors.ink)
                                    Text(
                                        "${txDetailRow.confirmations} of $target",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = dogeWalletColors.muted
                                    )
                                }
                            }
                        }
                        Text(
                            text = (if (txDetailRow.incoming) "+" else "−") +
                                DogecoinAmount.formatKoinu(txDetailRow.amountKoinu) + " DOGE",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            color = dogeWalletColors.ink
                        )
                        SelectionContainer {
                            Text(
                                text = txDetailRow.txid,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = dogeWalletColors.muted
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        copy(txDetailRow.txid, context.getString(R.string.dogecoin_txid_copied))
                    }) { Text(stringResource(R.string.dogecoin_copy_txid)) }
                },
                dismissButton = {
                    TextButton(onClick = { walletTxDetailId = null }) { Text("Close") }
                }
            )
        }
    }

    pendingWifCopy?.let { key ->
        var wifQrRevealed by remember(key.address, key.network) { mutableStateOf(false) }
        val needsBackupAcknowledgement = key.network == DogecoinNetwork.MAINNET
        val canRecordWifBackup = !needsBackupAcknowledgement || mainnetWifBackupAcknowledged
        val dismissBackup = {
            pendingWifCopy = null
            mainnetWifBackupAcknowledged = false
            wifQrRevealed = false
        }
        // Record the backup (a timestamp that satisfies the mainnet send gate) two ways: "I've written it down"
        // (NO clipboard — safer for a hand-copied key) and "Copy" (also puts it on the clipboard). Both record
        // identically; only the optional clipboard write differs. The signer and send gates are untouched.
        val recordBackup = { toClipboard: Boolean ->
            if (toClipboard) copy(key.wif, context.getString(R.string.dogecoin_wif_copied))
            val copyState = repository.markWifCopied(key)
            if (selectedNetwork == key.network && snapshot.key.address == key.address) {
                wifCopyState = copyState
            }
            dismissBackup()
        }
        AlertDialog(
            onDismissRequest = dismissBackup,
            title = {
                Text(
                    stringResource(
                        R.string.dogecoin_confirm_wif_title,
                        key.network.displayName
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.dogecoin_confirm_wif_body),
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp
                    )
                    Text(
                        text = stringResource(R.string.dogecoin_wif_qr_secret_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        lineHeight = 18.sp
                    )
                    // Reveal is freely available (it sits behind the secret warning + a screenshot block); only
                    // RECORDING the backup is gated by the mainnet acknowledgement below.
                    OutlinedButton(
                        onClick = { wifQrRevealed = !wifQrRevealed },
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
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { recordBackup(false) },
                        // Must have actually SEEN the key before claiming to have written it down.
                        enabled = canRecordWifBackup && wifQrRevealed
                    ) {
                        Text(stringResource(R.string.dogecoin_backup_written_down))
                    }
                    OutlinedButton(
                        onClick = { recordBackup(true) },
                        enabled = canRecordWifBackup
                    ) {
                        Text(stringResource(R.string.dogecoin_confirm_wif_copy))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = dismissBackup) {
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
                        clearWalletRuntimeState()
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
                if (!refreshingBalance && !sending) {
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
                        }
                    },
                    enabled = !refreshingBalance && !sending
                ) {
                    Text(
                        stringResource(
                            when (action) {
                                DogecoinWatchImportAction.REFRESH_BALANCE ->
                                    R.string.dogecoin_confirm_refresh_before_rescan_action
                                DogecoinWatchImportAction.REVIEW_SEND ->
                                    R.string.dogecoin_confirm_review_before_rescan_action
                            }
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingWatchImportAction = null },
                    enabled = !refreshingBalance && !sending
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
                        clearWalletRuntimeState()
                        val importedSnapshot = repository.importWalletFromWif(key.network, importWif)
                        snapshot = importedSnapshot
                        wifCopyState = repository.loadWifCopyState(importedSnapshot.key)
                        importWif = ""
                        importWifRevealed = false
                        importWifError = null
                        pendingImportKey = null
                        mainnetWifImportAcknowledged = false
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

/**
 * Home-node assist surface (spec R-C3). Offer state: the light client is behind but a node is already
 * configured — one tap rides reads+broadcast on the node for THIS session while SPV keeps syncing.
 * Active state: the R-C3 banner with blocks-behind, an explicit not-over-Tor disclosure when Tor is on
 * (the RPC path never routes over Tor), and a one-tap revert. Presentation-only: callers own the state,
 * and nothing here persists a backend choice (R-C4a).
 */
@Composable
private fun NodeAssistCard(
    active: Boolean,
    blocksBehind: Int,
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
                    stringResource(R.string.dogecoin_node_assist_active, blocksBehind) +
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
private fun WalletTxRowView(row: WalletTxRow, onClick: (() -> Unit)? = null) {
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

private enum class DogecoinWatchImportAction {
    REFRESH_BALANCE,
    REVIEW_SEND
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

/** User-facing reason a configured node endpoint is refused by the trust classifier (zero-I/O paths). */
private fun dogecoinEndpointBlockedReason(
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

private const val DOGECOIN_UTXO_PREVIEW_LIMIT = 5
private const val DOGECOIN_CONFIRM_UTXO_PREVIEW_LIMIT = 3
private const val DOGECOIN_ACTIVITY_PREVIEW_LIMIT = 5
// Pending cards shown on the main screen; the rest fold into "View all activity". Full list is capped so a
// long history can't balloon a single LazyColumn item.
private const val DOGECOIN_PENDING_CARDS_LIMIT = 4
private const val DOGECOIN_ACTIVITY_FULL_LIMIT = 50
private const val DOGECOIN_SEND_ITEM_INDEX = 6
private const val DOGECOIN_SIGNED_TX_MAX_AGE_MILLIS = 10L * 60L * 1000L
private const val DOGECOIN_SPV_CORROBORATION_POLLS = 40
private const val DOGECOIN_SPV_CORROBORATION_INTERVAL_MS = 15_000L
// Focal-ring confirmation fill (presentation-only): how many confirmations the ring shows as "done" (a full
// gold ring), and the poll budget before a never-confirming tx reverts the ring to the idle balance.
private const val DOGECOIN_SPV_CONFIRM_TARGET = 6
private const val DOGECOIN_SPV_CONFIRM_POLLS = 80
// Presentation-only: show the confirmation fill (not "Syncing") for a pending tx while within this many blocks
// of the tip, so a momentary `synced` flap (peer dip / a fresh block) can't mask it. confirmationDepth is read
// from our own chain head, so it stays accurate this close to the tip.
private const val DOGECOIN_SPV_NEARTIP_BLOCKS = 6

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
