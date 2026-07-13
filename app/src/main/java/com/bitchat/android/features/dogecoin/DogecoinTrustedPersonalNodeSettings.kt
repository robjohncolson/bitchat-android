package com.bitchat.android.features.dogecoin

import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * DES-1-A/B mainnet trust ceremony and explicit process-only read session. This surface owns a separate
 * draft/authorization and a display-only node snapshot; it never writes generic RPC settings, changes the
 * selected backend, exposes signer inputs, signs, or broadcasts.
 */
@Composable
internal fun DogecoinTrustedPersonalNodeSettings(
    androidMainnetAddress: String,
    onSessionUseChanged: (Boolean) -> Unit = {},
    onSessionStateChanged: (DogecoinTrustedPersonalNodeState) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember(appContext) { DogecoinTrustedPersonalNodeStore(appContext) }
    val scope = rememberCoroutineScope()

    var storeLoaded by remember(androidMainnetAddress) { mutableStateOf(false) }
    var loadedState by remember(androidMainnetAddress) {
        mutableStateOf(DogecoinTrustedPersonalNodeState.UNAUTHORIZED)
    }
    var loadedProfile by remember(androidMainnetAddress) {
        mutableStateOf<DogecoinTrustedPersonalNodeProfile?>(null)
    }
    var storeError by remember(androidMainnetAddress) { mutableStateOf<String?>(null) }

    // Local-only draft. No edit callback below touches the store or starts a coroutine.
    var origin by remember(androidMainnetAddress) { mutableStateOf("") }
    var username by remember(androidMainnetAddress) { mutableStateOf("") }
    var password by remember(androidMainnetAddress) { mutableStateOf("") }
    var requestedWalletId by remember(androidMainnetAddress) { mutableStateOf("") }
    var draftRevision by remember(androidMainnetAddress) { mutableLongStateOf(0L) }
    val probeGeneration = remember(androidMainnetAddress) { AtomicLong(0L) }
    val readGeneration = remember(androidMainnetAddress) { AtomicLong(0L) }

    var probeClient by remember(androidMainnetAddress) { mutableStateOf(DogecoinRpcClient()) }
    var activeToken by remember(androidMainnetAddress) {
        mutableStateOf<DogecoinTrustedPersonalNodeProvisioningToken?>(null)
    }
    var provisioningResult by remember(androidMainnetAddress) {
        mutableStateOf<DogecoinTrustedPersonalNodeProvisioningResult?>(null)
    }
    var probeError by remember(androidMainnetAddress) { mutableStateOf<String?>(null) }
    var readError by remember(androidMainnetAddress) { mutableStateOf<String?>(null) }
    var provisioning by remember(androidMainnetAddress) { mutableStateOf(false) }
    var authorizing by remember(androidMainnetAddress) { mutableStateOf(false) }
    var activeReadToken by remember(androidMainnetAddress) {
        mutableStateOf<DogecoinTrustedPersonalNodeActivationToken?>(null)
    }
    var sessionWarningAcknowledged by remember(androidMainnetAddress) { mutableStateOf(false) }
    var nowMonotonicMillis by remember(androidMainnetAddress) {
        mutableLongStateOf(SystemClock.elapsedRealtime())
    }
    var showRevokeConfirmation by remember(androidMainnetAddress) { mutableStateOf(false) }

    var controlsLaptop by remember(androidMainnetAddress) { mutableStateOf(false) }
    var loopbackServeNoFunnel by remember(androidMainnetAddress) { mutableStateOf(false) }
    var watchOnlyNoWifAndRescanned by remember(androidMainnetAddress) { mutableStateOf(false) }
    var acceptsNodeOracleRisk by remember(androidMainnetAddress) { mutableStateOf(false) }
    var understandsTailscaleIsNotAnonymity by remember(androidMainnetAddress) { mutableStateOf(false) }

    // Session-holder values are intentionally not persisted. Recreating this component/process from a
    // durable authorization always yields AUTHORIZED_INACTIVE, never an active node session.
    val holderInitialState = if (
        loadedProfile != null && loadedProfile?.androidAddress != androidMainnetAddress
    ) {
        DogecoinTrustedPersonalNodeState.REVOKED
    } else {
        loadedState
    }
    val sessionHolder = remember(storeLoaded, holderInitialState, loadedProfile, androidMainnetAddress) {
        if (!storeLoaded) {
            // Do not project the temporary pre-load UNAUTHORIZED value into the process registry: on a
            // sheet reopen that would erase a valid process-only active session before encrypted prefs load.
            DogecoinTrustedPersonalNodeProcessSessionRegistry.current()
        } else {
            DogecoinTrustedPersonalNodeProcessSessionRegistry.bindPersistedAuthorization(
                savedState = holderInitialState,
                savedProfile = loadedProfile?.takeIf { it.androidAddress == androidMainnetAddress },
                selectedNetwork = DogecoinNetwork.MAINNET,
                androidAddress = androidMainnetAddress
            )
        }
    }
    // Holder state is plain process memory; this epoch makes its deliberate transitions observable by Compose.
    var holderEpoch by remember(androidMainnetAddress) { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val observedHolderEpoch = holderEpoch
    val authorizationState = sessionHolder.state
    val authorizedProfile = sessionHolder.profile
    val timedDisplaySnapshot = sessionHolder.displaySnapshot
    val freshTimedDisplaySnapshot = timedDisplaySnapshot?.takeIf { candidate ->
        authorizedProfile != null && isDogecoinTrustedPersonalNodeTimedSnapshotFresh(
            authorizedProfile,
            candidate,
            nowMonotonicMillis
        )
    }
    val displaySnapshotFresh = freshTimedDisplaySnapshot != null
    val processSessionUsesNode = dogecoinTrustedPersonalNodeSessionUsesNode(authorizationState)

    LaunchedEffect(store, androidMainnetAddress) {
        val loaded = try {
            withContext(Dispatchers.IO) {
                val savedState = store.loadState()
                val savedProfile = store.loadProfile()
                if (
                    savedProfile != null &&
                    savedProfile.androidAddress != androidMainnetAddress
                ) {
                    // A wallet reset/import changes the only signing address this authorization binds.
                    // Persist the REVOKED tombstone and remove its credential before rendering the new
                    // wallet; a holder-only projection would leave stale authority durable on disk.
                    check(store.revoke()) {
                        context.getString(R.string.dogecoin_tpn_wallet_changed_revoke_failed)
                    }
                    DogecoinTrustedPersonalNodeState.REVOKED to null
                } else {
                    savedState to savedProfile
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            storeError = error.message ?: error.javaClass.simpleName
            null
        }
        loaded?.let { (state, profile) ->
            loadedState = state
            loadedProfile = profile
        }
        storeLoaded = true
    }

    DisposableEffect(probeClient, sessionHolder) {
        onDispose {
            probeGeneration.incrementAndGet()
            readGeneration.incrementAndGet()
            probeClient.cancelActiveRequests()
            activeReadToken?.let(sessionHolder::cancelActivation)
            sessionHolder.cancelProvisioning()
        }
    }

    LaunchedEffect(processSessionUsesNode, authorizationState) {
        onSessionUseChanged(processSessionUsesNode)
        onSessionStateChanged(authorizationState)
    }

    // Display age and expiry use only the phone's monotonic clock. This loop performs no network or disk I/O.
    LaunchedEffect(authorizationState, timedDisplaySnapshot?.capturedAtMonotonicMillis) {
        while (sessionHolder.displaySnapshot != null) {
            val now = SystemClock.elapsedRealtime()
            nowMonotonicMillis = now
            val before = sessionHolder.state
            sessionHolder.refreshFreshness(now)
            if (sessionHolder.state != before) {
                holderEpoch += 1
            }
            delay(1_000L)
        }
    }

    fun resetConfirmations() {
        controlsLaptop = false
        loopbackServeNoFunnel = false
        watchOnlyNoWifAndRescanned = false
        acceptsNodeOracleRisk = false
        understandsTailscaleIsNotAnonymity = false
    }

    fun invalidateProvisioningDraft() {
        probeGeneration.incrementAndGet()
        draftRevision += 1L
        sessionHolder.invalidateDraft(draftRevision)
        holderEpoch += 1
        activeToken = null
        provisioningResult = null
        probeError = null
        provisioning = false
        authorizing = false
        resetConfirmations()
        // A canceled OkHttp call cannot be reused as proof for a changed draft. Replace the client family
        // so even the registration race handled by cancelActiveRequests remains fail-closed.
        probeClient.cancelActiveRequests()
        probeClient = DogecoinRpcClient()
    }

    val exactOrigin = remember(origin) { exactDogecoinTrustedPersonalNodeOriginOrNull(origin) }
    val credentials = remember(username, password) {
        DogecoinTrustedPersonalNodeCredentials(username = username, password = password)
    }
    val canonicalWalletId = remember(requestedWalletId) {
        if (requestedWalletId.isEmpty()) "" else canonicalDogecoinTrustedPersonalNodeWalletIdOrNull(requestedWalletId)
    }
    val draftLocallyValid = exactOrigin != null && credentials.isValid() && canonicalWalletId != null

    fun beginOneShotProvisioning() {
        val disclosedOrigin = exactOrigin ?: return
        if (!draftLocallyValid || provisioning || authorizing) return

        val token = sessionHolder.beginProvisioning(draftRevision)
        holderEpoch += 1
        // The token is consumed immediately before the only code path allowed to run the fixed probe.
        if (!sessionHolder.consumeProvisioningProbe(token)) {
            sessionHolder.cancelProvisioning()
            holderEpoch += 1
            probeError = context.getString(R.string.dogecoin_tpn_probe_expired)
            return
        }

        val capturedAddress = androidMainnetAddress
        val capturedRevision = draftRevision
        val capturedGeneration = probeGeneration.incrementAndGet()
        val capturedCredentials = credentials
        val capturedWalletId = canonicalWalletId.orEmpty()
        val capturedClient = probeClient.guardedBy {
            check(probeGeneration.get() == capturedGeneration) {
                context.getString(R.string.dogecoin_tpn_probe_stale)
            }
        }
        activeToken = token
        provisioningResult = null
        probeError = null
        resetConfirmations()
        provisioning = true

        scope.launch {
            val outcome = runCatching {
                capturedClient.probeTrustedPersonalNode(
                    origin = disclosedOrigin,
                    credentials = capturedCredentials,
                    requestedWalletId = capturedWalletId,
                    boundMainnetAddress = capturedAddress
                )
            }
            if (
                probeGeneration.get() != capturedGeneration ||
                activeToken != token ||
                draftRevision != capturedRevision ||
                androidMainnetAddress != capturedAddress
            ) {
                return@launch
            }
            provisioning = false
            outcome.fold(
                onSuccess = { result ->
                    if (sessionHolder.recordSuccessfulProvisioning(token, result)) {
                        holderEpoch += 1
                        provisioningResult = result
                    } else {
                        sessionHolder.cancelProvisioning()
                        holderEpoch += 1
                        activeToken = null
                        probeError = context.getString(R.string.dogecoin_tpn_probe_stale)
                    }
                },
                onFailure = { error ->
                    sessionHolder.cancelProvisioning()
                    holderEpoch += 1
                    activeToken = null
                    probeError = error.message ?: error.javaClass.simpleName
                }
            )
        }
    }

    fun authorizeInactiveProfile() {
        val token = activeToken ?: return
        if (authorizing || provisioning) return
        val confirmations = DogecoinTrustedPersonalNodeConfirmations(
            controlsLaptop = controlsLaptop,
            loopbackServeNoFunnel = loopbackServeNoFunnel,
            watchOnlyNoWif = watchOnlyNoWifAndRescanned,
            acceptsNodeOracleRisk = acceptsNodeOracleRisk,
            understandsTailscaleIsNotAnonymity = understandsTailscaleIsNotAnonymity
        )
        val now = System.currentTimeMillis()
        val candidate = sessionHolder.authorizationCandidate(
            token = token,
            confirmations = confirmations,
            rescanAttestedAtMillis = now
        ) ?: return

        val capturedCredentials = credentials
        val capturedRevision = draftRevision
        authorizing = true
        storeError = null
        scope.launch {
            val persisted = try {
                withContext(Dispatchers.IO) {
                    store.authorize(
                        candidate = candidate,
                        credentials = capturedCredentials,
                        authorizedAtMillis = now
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                storeError = error.message ?: error.javaClass.simpleName
                null
            }
            if (
                activeToken != token ||
                draftRevision != capturedRevision ||
                androidMainnetAddress != candidate.androidAddress
            ) {
                authorizing = false
                return@launch
            }
            if (persisted != null && sessionHolder.authorizationPersisted(token, persisted)) {
                loadedState = DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE
                loadedProfile = persisted
                password = ""
                activeToken = null
                provisioningResult = null
                resetConfirmations()
                holderEpoch += 1
            } else if (persisted == null && storeError == null) {
                storeError = context.getString(R.string.dogecoin_tpn_authorization_refused)
            } else if (persisted != null) {
                storeError = context.getString(R.string.dogecoin_tpn_authorization_stale)
            }
            authorizing = false
        }
    }

    fun runTrustedPersonalNodeRead() {
        if (provisioning || authorizing || activeReadToken != null) return
        val capturedProfile = sessionHolder.profile ?: return
        val startedAt = SystemClock.elapsedRealtime()
        val token = when (sessionHolder.state) {
            DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE ->
                sessionHolder.beginActivation(startedAt)
            DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED ->
                sessionHolder.refreshActiveReadSnapshot(startedAt)
            DogecoinTrustedPersonalNodeState.DEGRADED ->
                sessionHolder.retryDegradedActivation(startedAt)
            else -> null
        } ?: return

        val capturedGeneration = readGeneration.incrementAndGet()
        val capturedClient = probeClient.guardedBy {
            check(readGeneration.get() == capturedGeneration) {
                context.getString(R.string.dogecoin_tpn_probe_stale)
            }
            check(sessionHolder.isActivationCurrent(token)) {
                context.getString(R.string.dogecoin_tpn_probe_stale)
            }
        }
        activeReadToken = token
        readError = null
        nowMonotonicMillis = startedAt
        holderEpoch += 1

        scope.launch {
            val outcome = try {
                val storedCredentials = withContext(Dispatchers.IO) {
                    store.loadCredentials(capturedProfile)
                } ?: throw DogecoinTrustedPersonalNodeCredentialsUnavailableException(
                    context.getString(R.string.dogecoin_tpn_credentials_unavailable)
                )
                Result.success(
                    capturedClient.readTrustedPersonalNodeDisplaySnapshot(
                        profile = capturedProfile,
                        credentials = storedCredentials
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }

            if (
                readGeneration.get() != capturedGeneration ||
                activeReadToken != token ||
                sessionHolder.profile != capturedProfile ||
                !sessionHolder.isActivationCurrent(token)
            ) {
                sessionHolder.cancelActivation(token)
                holderEpoch += 1
                return@launch
            }

            activeReadToken = null
            nowMonotonicMillis = SystemClock.elapsedRealtime()
            outcome.fold(
                onSuccess = { snapshot ->
                    if (!sessionHolder.recordSuccessfulReadSnapshot(token, snapshot, nowMonotonicMillis)) {
                        readError = context.getString(R.string.dogecoin_tpn_snapshot_expired)
                    }
                },
                onFailure = { error ->
                    if (
                        error.isDogecoinRpcAuthenticationFailure() ||
                        error is DogecoinTrustedPersonalNodeCredentialsUnavailableException
                    ) {
                        sessionHolder.recordAuthenticationRequired(token)
                    } else {
                        sessionHolder.recordTransientFailure(token)
                    }
                    readError = error.message ?: error.javaClass.simpleName
                }
            )
            holderEpoch += 1
        }
    }

    fun stopTrustedPersonalNodeRead() {
        readGeneration.incrementAndGet()
        activeReadToken?.let(sessionHolder::cancelActivation)
        activeReadToken = null
        probeClient.cancelActiveRequests()
        probeClient = DogecoinRpcClient()
        sessionHolder.deactivate()
        sessionWarningAcknowledged = false
        readError = null
        holderEpoch += 1
    }

    WalletCard {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.dogecoin_tpn_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Text(
                text = stringResource(R.string.dogecoin_tpn_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                lineHeight = 18.sp
            )

            when {
                !storeLoaded -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.dogecoin_tpn_loading))
                    }
                }
                authorizedProfile != null &&
                    authorizationState != DogecoinTrustedPersonalNodeState.UNAUTHORIZED &&
                    authorizationState != DogecoinTrustedPersonalNodeState.REVOKED &&
                    authorizationState != DogecoinTrustedPersonalNodeState.PROVISIONING -> {
                    SelectionContainer {
                        Text(
                            text = stringResource(
                                R.string.dogecoin_tpn_authorized_summary,
                                authorizedProfile.origin,
                                authorizedProfile.androidAddress,
                                authorizedProfile.coreWalletId,
                                authorizedProfile.revision,
                                authorizedProfile.policyVersion,
                                formatDogecoinWalletTime(authorizedProfile.authorizedAtMillis),
                                formatDogecoinWalletTime(authorizedProfile.rescanAttestedAtMillis)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp
                        )
                    }
                    if (authorizationState !in setOf(
                            DogecoinTrustedPersonalNodeState.DISPUTED,
                            DogecoinTrustedPersonalNodeState.AUTH_REQUIRED
                        )
                    ) {
                        TpnConnectionProfileExport(
                            profile = authorizedProfile,
                            store = store
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.dogecoin_tpn_profile_share_blocked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 18.sp
                        )
                    }

                    when (authorizationState) {
                        DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE -> {
                            Text(
                                text = stringResource(R.string.dogecoin_tpn_authorized_inactive),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.dogecoin_tpn_inactive_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                lineHeight = 18.sp
                            )
                            Text(
                                text = stringResource(R.string.dogecoin_tpn_session_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 18.sp
                            )
                            TpnConfirmationRow(
                                checked = sessionWarningAcknowledged,
                                onCheckedChange = { sessionWarningAcknowledged = it },
                                text = stringResource(R.string.dogecoin_tpn_session_acknowledge),
                                enabled = activeReadToken == null
                            )
                            Button(
                                onClick = { runTrustedPersonalNodeRead() },
                                enabled = sessionWarningAcknowledged && activeReadToken == null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.dogecoin_tpn_use_action))
                            }
                        }
                        DogecoinTrustedPersonalNodeState.CHECKING -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.dogecoin_tpn_checking))
                            }
                        }
                        DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED -> {
                            Text(
                                text = stringResource(R.string.dogecoin_tpn_active_read_only),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        DogecoinTrustedPersonalNodeState.DEGRADED -> {
                            Text(
                                text = stringResource(R.string.dogecoin_tpn_degraded),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 18.sp
                            )
                            if (displaySnapshotFresh) {
                                Text(
                                    text = stringResource(R.string.dogecoin_tpn_degraded_cached),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                        DogecoinTrustedPersonalNodeState.AUTH_REQUIRED -> {
                            Text(
                                text = stringResource(R.string.dogecoin_tpn_auth_required),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 18.sp
                            )
                        }
                        DogecoinTrustedPersonalNodeState.DISPUTED -> {
                            Text(
                                text = stringResource(R.string.dogecoin_tpn_disputed_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = stringResource(R.string.dogecoin_tpn_disputed_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 18.sp
                            )
                        }
                        else -> Unit
                    }

                    readError?.let { error ->
                        Text(
                            text = stringResource(R.string.dogecoin_tpn_read_failed, error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 18.sp
                        )
                    }

                    if (
                        freshTimedDisplaySnapshot != null &&
                        authorizationState != DogecoinTrustedPersonalNodeState.AUTH_REQUIRED
                    ) {
                        TpnReadOnlySnapshot(
                            snapshot = freshTimedDisplaySnapshot,
                            ageSeconds = (
                                (nowMonotonicMillis - freshTimedDisplaySnapshot.capturedAtMonotonicMillis)
                                    .coerceAtLeast(0L) / 1_000L
                                )
                        )
                    } else if (
                        timedDisplaySnapshot != null &&
                        authorizationState != DogecoinTrustedPersonalNodeState.AUTH_REQUIRED
                    ) {
                        Text(
                            text = stringResource(R.string.dogecoin_tpn_snapshot_expired),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 18.sp
                        )
                    }

                    if (
                        authorizationState == DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED ||
                        authorizationState == DogecoinTrustedPersonalNodeState.DEGRADED
                    ) {
                        OutlinedButton(
                            onClick = { runTrustedPersonalNodeRead() },
                            enabled = activeReadToken == null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.dogecoin_tpn_refresh_action))
                        }
                    }
                    if (
                        authorizationState == DogecoinTrustedPersonalNodeState.CHECKING ||
                        authorizationState == DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED ||
                        authorizationState == DogecoinTrustedPersonalNodeState.DEGRADED
                    ) {
                        OutlinedButton(
                            onClick = { stopTrustedPersonalNodeRead() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.dogecoin_tpn_stop_action))
                        }
                    }
                    OutlinedButton(
                        onClick = { showRevokeConfirmation = true },
                        enabled = !authorizing && activeReadToken == null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.dogecoin_tpn_revoke_action))
                    }
                }
                else -> {
                    Text(
                        text = stringResource(
                            if (authorizationState == DogecoinTrustedPersonalNodeState.REVOKED) {
                                R.string.dogecoin_tpn_revoked
                            } else {
                                R.string.dogecoin_tpn_unauthorized
                            }
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    TpnConnectionProfileImport(
                        enabled = !provisioning && !authorizing,
                        onImported = { imported ->
                            val draft = dogecoinTrustedPersonalNodeConnectionDraftFrom(imported)
                            invalidateProvisioningDraft()
                            origin = draft.origin
                            username = draft.username
                            password = draft.password
                            requestedWalletId = draft.coreWalletId
                        }
                    )
                    OutlinedTextField(
                        value = origin,
                        onValueChange = {
                            if (origin != it) {
                                invalidateProvisioningDraft()
                                origin = it
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !provisioning && !authorizing,
                        singleLine = true,
                        label = { Text(stringResource(R.string.dogecoin_tpn_origin_label)) },
                        isError = origin.isNotEmpty() && exactOrigin == null
                    )
                    Text(
                        text = stringResource(R.string.dogecoin_tpn_origin_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        lineHeight = 16.sp
                    )
                    if (origin.isNotEmpty() && exactOrigin == null) {
                        Text(
                            text = stringResource(R.string.dogecoin_tpn_origin_invalid),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = {
                                if (username != it) {
                                    invalidateProvisioningDraft()
                                    username = it
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !provisioning && !authorizing,
                            singleLine = true,
                            label = { Text(stringResource(R.string.dogecoin_tpn_username_label)) }
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                if (password != it) {
                                    invalidateProvisioningDraft()
                                    password = it
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !provisioning && !authorizing,
                            singleLine = true,
                            label = { Text(stringResource(R.string.dogecoin_tpn_password_label)) },
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                    OutlinedTextField(
                        value = requestedWalletId,
                        onValueChange = {
                            if (requestedWalletId != it) {
                                invalidateProvisioningDraft()
                                requestedWalletId = it
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !provisioning && !authorizing,
                        singleLine = true,
                        label = { Text(stringResource(R.string.dogecoin_tpn_wallet_label)) },
                        isError = requestedWalletId.isNotEmpty() && canonicalWalletId == null
                    )
                    Text(
                        text = stringResource(R.string.dogecoin_tpn_wallet_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        lineHeight = 16.sp
                    )
                    if (requestedWalletId.isNotEmpty() && canonicalWalletId == null) {
                        Text(
                            text = stringResource(R.string.dogecoin_tpn_wallet_invalid),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 18.sp
                        )
                    }

                    exactOrigin?.let { disclosedOrigin ->
                        SelectionContainer {
                            Text(
                                text = stringResource(
                                    R.string.dogecoin_tpn_disclosure,
                                    disclosedOrigin,
                                    androidMainnetAddress
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                lineHeight = 18.sp
                            )
                        }
                    }
                    Button(
                        onClick = { beginOneShotProvisioning() },
                        enabled = storeLoaded && draftLocallyValid && !provisioning && !authorizing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.dogecoin_tpn_test_exact_origin))
                    }
                    if (provisioning) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.dogecoin_tpn_provisioning))
                        }
                    }
                    probeError?.let { error ->
                        Text(
                            text = stringResource(R.string.dogecoin_tpn_probe_failed, error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 18.sp
                        )
                    }
                    provisioningResult?.let { result ->
                        SelectionContainer {
                            Text(
                                text = stringResource(
                                    R.string.dogecoin_tpn_probe_success,
                                    result.origin,
                                    result.coreWalletId
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                lineHeight = 18.sp
                            )
                        }
                        Text(
                            text = stringResource(R.string.dogecoin_tpn_probe_watch_state),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 18.sp
                        )
                        TpnConfirmationRow(
                            checked = controlsLaptop,
                            onCheckedChange = { controlsLaptop = it },
                            text = stringResource(R.string.dogecoin_tpn_confirm_controlled_laptop),
                            enabled = !authorizing
                        )
                        TpnConfirmationRow(
                            checked = loopbackServeNoFunnel,
                            onCheckedChange = { loopbackServeNoFunnel = it },
                            text = stringResource(R.string.dogecoin_tpn_confirm_private_route),
                            enabled = !authorizing
                        )
                        TpnConfirmationRow(
                            checked = watchOnlyNoWifAndRescanned,
                            onCheckedChange = { watchOnlyNoWifAndRescanned = it },
                            text = stringResource(R.string.dogecoin_tpn_confirm_watch_only_rescan),
                            enabled = !authorizing
                        )
                        TpnConfirmationRow(
                            checked = acceptsNodeOracleRisk,
                            onCheckedChange = { acceptsNodeOracleRisk = it },
                            text = stringResource(R.string.dogecoin_tpn_confirm_oracle_risk),
                            enabled = !authorizing
                        )
                        TpnConfirmationRow(
                            checked = understandsTailscaleIsNotAnonymity,
                            onCheckedChange = { understandsTailscaleIsNotAnonymity = it },
                            text = stringResource(R.string.dogecoin_tpn_confirm_tailscale_privacy),
                            enabled = !authorizing
                        )
                        Button(
                            onClick = { authorizeInactiveProfile() },
                            enabled = !authorizing &&
                                controlsLaptop &&
                                loopbackServeNoFunnel &&
                                watchOnlyNoWifAndRescanned &&
                                acceptsNodeOracleRisk &&
                                understandsTailscaleIsNotAnonymity,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(
                                    if (authorizing) {
                                        R.string.dogecoin_tpn_authorizing
                                    } else {
                                        R.string.dogecoin_tpn_authorize_action
                                    }
                                )
                            )
                        }
                    }
                }
            }

            storeError?.let { error ->
                Text(
                    text = stringResource(R.string.dogecoin_tpn_store_failed, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    lineHeight = 18.sp
                )
            }
        }
    }

    if (showRevokeConfirmation) {
        AlertDialog(
            onDismissRequest = { showRevokeConfirmation = false },
            title = { Text(stringResource(R.string.dogecoin_tpn_revoke_title)) },
            text = {
                Text(
                    text = stringResource(R.string.dogecoin_tpn_revoke_body),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRevokeConfirmation = false
                        storeError = null
                        readGeneration.incrementAndGet()
                        activeReadToken?.let(sessionHolder::cancelActivation)
                        activeReadToken = null
                        probeClient.cancelActiveRequests()
                        probeClient = DogecoinRpcClient()
                        authorizing = true
                        scope.launch {
                            val revoked = try {
                                withContext(Dispatchers.IO) { store.revoke() }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                storeError = error.message ?: error.javaClass.simpleName
                                false
                            }
                            if (revoked) {
                                sessionHolder.revoke()
                                loadedState = DogecoinTrustedPersonalNodeState.REVOKED
                                loadedProfile = null
                                password = ""
                                readError = null
                                sessionWarningAcknowledged = false
                                holderEpoch += 1
                            } else if (storeError == null) {
                                storeError = context.getString(R.string.dogecoin_tpn_revoke_failed)
                            }
                            authorizing = false
                        }
                    },
                    enabled = !authorizing
                ) {
                    Text(stringResource(R.string.dogecoin_tpn_revoke_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun TpnConnectionProfileExport(
    profile: DogecoinTrustedPersonalNodeProfile,
    store: DogecoinTrustedPersonalNodeStore
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includePassword by remember(profile) { mutableStateOf(false) }
    var passwordDisclosureAccepted by remember(profile) { mutableStateOf(false) }
    var sharing by remember(profile) { mutableStateOf(false) }
    var shareError by remember(profile) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.dogecoin_tpn_profile_share_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text = stringResource(R.string.dogecoin_tpn_profile_share_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            lineHeight = 18.sp
        )
        TpnConfirmationRow(
            checked = includePassword,
            onCheckedChange = {
                includePassword = it
                passwordDisclosureAccepted = false
                shareError = null
            },
            text = stringResource(R.string.dogecoin_tpn_profile_include_password),
            enabled = !sharing
        )
        if (includePassword) {
            TpnConfirmationRow(
                checked = passwordDisclosureAccepted,
                onCheckedChange = {
                    passwordDisclosureAccepted = it
                    shareError = null
                },
                text = stringResource(R.string.dogecoin_tpn_profile_password_disclosure),
                enabled = !sharing
            )
        }
        OutlinedButton(
            onClick = {
                if (sharing) return@OutlinedButton
                sharing = true
                shareError = null
                scope.launch {
                    val result = runCatching {
                        val credentials = withContext(Dispatchers.IO) {
                            store.loadCredentials(profile)
                        } ?: error(context.getString(R.string.dogecoin_tpn_credentials_unavailable))
                        encodeDogecoinTrustedPersonalNodeConnectionProfile(
                            DogecoinTrustedPersonalNodeConnectionProfile(
                                origin = profile.origin,
                                username = credentials.username,
                                coreWalletId = profile.coreWalletId,
                                password = credentials.password.takeIf { includePassword }
                            )
                        ) ?: error(context.getString(R.string.dogecoin_tpn_profile_invalid))
                    }
                    result.fold(
                        onSuccess = { encoded ->
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, encoded)
                            }
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(
                                        sendIntent,
                                        context.getString(R.string.dogecoin_tpn_profile_share_chooser)
                                    )
                                )
                            }.onSuccess {
                                // A password-bearing export is always a fresh, explicit choice.
                                includePassword = false
                                passwordDisclosureAccepted = false
                            }.onFailure { error ->
                                shareError = error.message ?: error.javaClass.simpleName
                            }
                        },
                        onFailure = { error ->
                            shareError = error.message ?: error.javaClass.simpleName
                        }
                    )
                    sharing = false
                }
            },
            enabled = !sharing && (!includePassword || passwordDisclosureAccepted),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(
                    if (sharing) {
                        R.string.dogecoin_tpn_profile_preparing
                    } else {
                        R.string.dogecoin_tpn_profile_share_action
                    }
                )
            )
        }
        shareError?.let { error ->
            Text(
                text = stringResource(R.string.dogecoin_tpn_profile_share_failed, error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun TpnConnectionProfileImport(
    enabled: Boolean,
    onImported: (DogecoinTrustedPersonalNodeConnectionProfile) -> Unit
) {
    var encoded by remember { mutableStateOf("") }
    var passwordDisclosureAccepted by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf(false) }
    var tooLong by remember { mutableStateOf(false) }
    val parsed = remember(encoded, tooLong) {
        if (tooLong) null else decodeDogecoinTrustedPersonalNodeConnectionProfileOrNull(encoded)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.dogecoin_tpn_profile_import_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text = stringResource(R.string.dogecoin_tpn_profile_import_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            lineHeight = 18.sp
        )
        OutlinedTextField(
            value = encoded,
            onValueChange = { changed ->
                if (changed.length <= DOGECOIN_TPN_CONNECTION_PROFILE_MAX_CHARS) {
                    encoded = changed
                    tooLong = false
                } else {
                    // Never leave an older valid candidate actionable after rejecting a replacement.
                    encoded = ""
                    tooLong = true
                }
                passwordDisclosureAccepted = false
                imported = false
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            minLines = 2,
            maxLines = 4,
            label = { Text(stringResource(R.string.dogecoin_tpn_profile_import_label)) },
            visualTransformation = PasswordVisualTransformation(),
            isError = tooLong || (encoded.isNotEmpty() && parsed == null)
        )
        if (tooLong || (encoded.isNotEmpty() && parsed == null)) {
            Text(
                text = stringResource(R.string.dogecoin_tpn_profile_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                lineHeight = 18.sp
            )
        }
        parsed?.let { candidate ->
            Text(
                text = stringResource(
                    R.string.dogecoin_tpn_profile_import_preview,
                    candidate.origin,
                    candidate.username,
                    candidate.coreWalletId
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp
            )
            if (candidate.password != null) {
                TpnConfirmationRow(
                    checked = passwordDisclosureAccepted,
                    onCheckedChange = { passwordDisclosureAccepted = it },
                    text = stringResource(R.string.dogecoin_tpn_profile_import_password_disclosure),
                    enabled = enabled
                )
            }
            Button(
                onClick = {
                    onImported(candidate)
                    encoded = ""
                    passwordDisclosureAccepted = false
                    imported = true
                },
                enabled = enabled && !tooLong &&
                    (candidate.password == null || passwordDisclosureAccepted),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.dogecoin_tpn_profile_import_action))
            }
        }
        if (imported) {
            Text(
                text = stringResource(R.string.dogecoin_tpn_profile_imported_draft),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun TpnReadOnlySnapshot(
    snapshot: DogecoinTrustedPersonalNodeTimedDisplaySnapshot,
    ageSeconds: Long
) {
    val node = snapshot.nodeSnapshot
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionContainer {
            Text(
                text = stringResource(
                    R.string.dogecoin_tpn_provenance,
                    node.origin,
                    ageSeconds
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.tertiary,
                lineHeight = 18.sp
            )
        }
        Text(
            text = stringResource(
                R.string.dogecoin_tpn_readiness_summary,
                node.blocks,
                node.headers,
                node.peerCount
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
        )
        Text(
            text = stringResource(R.string.dogecoin_tpn_balance_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text = stringResource(
                R.string.dogecoin_tpn_balance_summary,
                DogecoinAmount.formatKoinu(node.balance.confirmedKoinu),
                DogecoinAmount.formatKoinu(node.balance.unconfirmedKoinu),
                node.balance.utxoCount
            ),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            lineHeight = 18.sp
        )
        if (node.balance.totalKoinu == 0L) {
            Text(
                text = stringResource(R.string.dogecoin_tpn_balance_zero_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                lineHeight = 18.sp
            )
        }
        Text(
            text = stringResource(R.string.dogecoin_tpn_activity_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        if (node.activity.isEmpty()) {
            Text(
                text = stringResource(R.string.dogecoin_tpn_activity_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                lineHeight = 18.sp
            )
        } else {
            node.activity.take(TPN_ACTIVITY_PREVIEW_LIMIT).forEach { item ->
                SelectionContainer {
                    Text(
                        text = stringResource(
                            R.string.dogecoin_tpn_activity_row,
                            item.category,
                            formatSignedDogecoin(item.amountKoinu),
                            shortDogecoinTxid(item.txid),
                            item.confirmations,
                            formatDogecoinActivityTime(
                                item.timeSeconds,
                                stringResource(R.string.unknown)
                            )
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                        lineHeight = 18.sp
                    )
                }
            }
            val hiddenCount = node.activity.size - TPN_ACTIVITY_PREVIEW_LIMIT
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
}

@Composable
private fun TpnConfirmationRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String,
    enabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            lineHeight = 18.sp
        )
    }
}

private const val TPN_ACTIVITY_PREVIEW_LIMIT = 5

private class DogecoinTrustedPersonalNodeCredentialsUnavailableException(message: String) :
    IllegalStateException(message)
