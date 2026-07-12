package com.bitchat.android.features.dogecoin

internal data class DogecoinTrustedPersonalNodeProvisioningToken(
    val nonce: Long,
    val draftRevision: Long
)

/**
 * Process-memory-only trust-ceremony and read-session state. A saved profile always reconstructs as
 * [DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE]; activation, readiness, and display snapshots
 * are never durable and this holder intentionally exposes no signing or broadcast authority.
 */
internal class DogecoinTrustedPersonalNodeSessionHolder(
    savedState: DogecoinTrustedPersonalNodeState = DogecoinTrustedPersonalNodeState.UNAUTHORIZED,
    savedProfile: DogecoinTrustedPersonalNodeProfile? = null
) {
    private val initialProfile = savedProfile
        ?.takeIf { savedState == DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE }
        ?.takeIf(::isValidDogecoinTrustedPersonalNodeProfile)

    var state: DogecoinTrustedPersonalNodeState = initialState(savedState, initialProfile)
        private set

    var profile: DogecoinTrustedPersonalNodeProfile? = initialProfile
        private set

    private var inactiveStateBeforeProvisioning = state

    private var nextNonce = 1L
    private var offeredToken: DogecoinTrustedPersonalNodeProvisioningToken? = null
    private var consumedToken: DogecoinTrustedPersonalNodeProvisioningToken? = null
    private var provisioningResult: DogecoinTrustedPersonalNodeProvisioningResult? = null
    private var issuedCandidate: DogecoinTrustedPersonalNodeProfileCandidate? = null

    private var nextActivationNonce = 1L
    private var activationToken: DogecoinTrustedPersonalNodeActivationToken? = null
    private var stateBeforeChecking = DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE

    private var nextProofNonce = 1L
    private var proofToken: DogecoinTrustedPersonalNodeProofRequestToken? = null

    var displaySnapshot: DogecoinTrustedPersonalNodeTimedDisplaySnapshot? = null
        private set

    // Never expose retained proof state directly: callers must pass through freshProofSnapshot(),
    // which rechecks both the display lease and proof TTL at the moment of use.
    private var retainedProofSnapshot: DogecoinTrustedPersonalNodeProofSnapshot? = null

    @Synchronized
    fun beginProvisioning(draftRevision: Long): DogecoinTrustedPersonalNodeProvisioningToken {
        require(draftRevision >= 0L) { "Draft revision must be non-negative." }
        if (state != DogecoinTrustedPersonalNodeState.PROVISIONING) {
            inactiveStateBeforeProvisioning = when (state) {
                DogecoinTrustedPersonalNodeState.REVOKED -> DogecoinTrustedPersonalNodeState.REVOKED
                DogecoinTrustedPersonalNodeState.AUTH_REQUIRED -> DogecoinTrustedPersonalNodeState.AUTH_REQUIRED
                else -> if (profile != null) {
                    DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE
                } else {
                    DogecoinTrustedPersonalNodeState.UNAUTHORIZED
                }
            }
        }
        clearReadSession()
        clearTransientProvisioning()
        val token = DogecoinTrustedPersonalNodeProvisioningToken(
            nonce = nextNonce,
            draftRevision = draftRevision
        )
        nextNonce = if (nextNonce == Long.MAX_VALUE) 1L else nextNonce + 1L
        offeredToken = token
        state = DogecoinTrustedPersonalNodeState.PROVISIONING
        return token
    }

    /** Returns true exactly once, immediately before the fixed provisioning probe is launched. */
    @Synchronized
    fun consumeProvisioningProbe(token: DogecoinTrustedPersonalNodeProvisioningToken): Boolean {
        if (state != DogecoinTrustedPersonalNodeState.PROVISIONING || offeredToken != token) return false
        offeredToken = null
        consumedToken = token
        return true
    }

    @Synchronized
    fun recordSuccessfulProvisioning(
        token: DogecoinTrustedPersonalNodeProvisioningToken,
        result: DogecoinTrustedPersonalNodeProvisioningResult
    ): Boolean {
        if (state != DogecoinTrustedPersonalNodeState.PROVISIONING) return false
        if (consumedToken != token || provisioningResult != null || issuedCandidate != null) return false
        if (!isValidDogecoinTrustedPersonalNodeProvisioningResult(result)) return false
        provisioningResult = result
        return true
    }

    /**
     * The non-UI authorization gate. A successful probe alone is insufficient: all five oracle-risk
     * confirmations and a positive operator rescan attestation timestamp are required. The candidate
     * can be issued only once, preventing double-tap authorization from minting two revisions.
     */
    @Synchronized
    fun authorizationCandidate(
        token: DogecoinTrustedPersonalNodeProvisioningToken,
        confirmations: DogecoinTrustedPersonalNodeConfirmations,
        rescanAttestedAtMillis: Long
    ): DogecoinTrustedPersonalNodeProfileCandidate? {
        if (state != DogecoinTrustedPersonalNodeState.PROVISIONING) return null
        if (consumedToken != token || issuedCandidate != null) return null
        val result = provisioningResult ?: return null
        if (!confirmations.allRequired || rescanAttestedAtMillis <= 0L) return null

        return DogecoinTrustedPersonalNodeProfileCandidate(
            origin = result.origin,
            network = result.network,
            androidAddress = result.androidAddress,
            coreWalletId = result.coreWalletId,
            rescanAttested = true,
            rescanAttestedAtMillis = rescanAttestedAtMillis
        ).takeIf(::isValidDogecoinTrustedPersonalNodeProfileCandidate)?.also {
            issuedCandidate = it
        }
    }

    @Synchronized
    fun authorizationPersisted(
        token: DogecoinTrustedPersonalNodeProvisioningToken,
        persistedProfile: DogecoinTrustedPersonalNodeProfile
    ): Boolean {
        val candidate = issuedCandidate ?: return false
        if (state != DogecoinTrustedPersonalNodeState.PROVISIONING || consumedToken != token) return false
        if (!isValidDogecoinTrustedPersonalNodeProfile(persistedProfile)) return false
        if (!persistedProfile.matches(candidate)) return false

        profile = persistedProfile
        clearTransientProvisioning()
        state = DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE
        inactiveStateBeforeProvisioning = state
        return true
    }

    /** Any field edit increments the UI revision and invalidates both an offered and an in-flight probe. */
    @Synchronized
    fun invalidateDraft(nextDraftRevision: Long) {
        val token = offeredToken ?: consumedToken ?: return
        if (token.draftRevision != nextDraftRevision) {
            clearTransientProvisioning()
            state = inactiveFallbackState()
        }
    }

    @Synchronized
    fun cancelProvisioning() {
        if (state != DogecoinTrustedPersonalNodeState.PROVISIONING) return
        clearTransientProvisioning()
        state = inactiveFallbackState()
    }

    /** Starts a fresh readiness check only after an explicit tap on a valid durable authorization. */
    @Synchronized
    fun beginActivation(nowMonotonicMillis: Long): DogecoinTrustedPersonalNodeActivationToken? {
        val boundProfile = profile?.takeIf(::isValidDogecoinTrustedPersonalNodeProfile) ?: return null
        if (state != DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE || nowMonotonicMillis < 0L) {
            return null
        }
        return beginActivationInternal(
            boundProfile,
            nowMonotonicMillis,
            keepDisplaySnapshot = false,
            previousState = state
        )
    }

    /** DEGRADED never auto-recovers; a new readiness check requires another explicit operator action. */
    @Synchronized
    fun retryDegradedActivation(nowMonotonicMillis: Long): DogecoinTrustedPersonalNodeActivationToken? {
        val boundProfile = profile?.takeIf(::isValidDogecoinTrustedPersonalNodeProfile) ?: return null
        if (state != DogecoinTrustedPersonalNodeState.DEGRADED || nowMonotonicMillis < 0L) return null
        return beginActivationInternal(
            boundProfile,
            nowMonotonicMillis,
            keepDisplaySnapshot = true,
            previousState = state
        )
    }

    /** Refresh is explicit and re-runs the complete readiness/read workflow; old data becomes stale meanwhile. */
    @Synchronized
    fun refreshActiveReadSnapshot(nowMonotonicMillis: Long): DogecoinTrustedPersonalNodeActivationToken? {
        val boundProfile = profile?.takeIf(::isValidDogecoinTrustedPersonalNodeProfile) ?: return null
        if (state != DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED || nowMonotonicMillis < 0L) return null
        return beginActivationInternal(
            boundProfile,
            nowMonotonicMillis,
            keepDisplaySnapshot = true,
            previousState = state
        )
    }

    /**
     * Starts an all-or-nothing proof collection only inside the exact active, fresh display session.
     * Starting again immediately discards any older proof so stale inputs cannot remain available while
     * a replacement is in flight.
     */
    @Synchronized
    fun beginProofSnapshot(nowMonotonicMillis: Long): DogecoinTrustedPersonalNodeProofRequestToken? {
        val boundProfile = profile?.takeIf(::isValidDogecoinTrustedPersonalNodeProfile) ?: return null
        val currentDisplay = freshDisplaySnapshot(nowMonotonicMillis) ?: return null
        if (state != DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED) return null
        val binding = boundProfile.toSessionBinding()
        if (currentDisplay.binding != binding) return null

        clearProofSession()
        val token = DogecoinTrustedPersonalNodeProofRequestToken(
            nonce = nextProofNonce,
            binding = binding,
            startedAtMonotonicMillis = nowMonotonicMillis
        )
        nextProofNonce = if (nextProofNonce == Long.MAX_VALUE) 1L else nextProofNonce + 1L
        proofToken = token
        return token
    }

    /** Lease check for each future proof RPC; it also expires stale display/proof work fail closed. */
    @Synchronized
    fun isProofSnapshotCurrent(
        token: DogecoinTrustedPersonalNodeProofRequestToken,
        nowMonotonicMillis: Long
    ): Boolean {
        refreshFreshness(nowMonotonicMillis)
        val current = matchesProofToken(token) &&
            state == DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED &&
            isDogecoinTrustedPersonalNodeFresh(token.startedAtMonotonicMillis, nowMonotonicMillis)
        if (!current && proofToken == token) clearProofSession()
        return current
    }

    /**
     * Atomically publishes only an exact-bound, complete proof result. A current but mismatched/stale
     * response consumes the lease and leaves the still-fresh node display active without spend inputs.
     */
    @Synchronized
    fun recordSuccessfulProofSnapshot(
        token: DogecoinTrustedPersonalNodeProofRequestToken,
        result: DogecoinTrustedPersonalNodeProofSnapshot,
        nowMonotonicMillis: Long
    ): Boolean {
        if (!matchesProofToken(token)) return false
        val boundProfile = profile
        val currentDisplay = displaySnapshot
        proofToken = null
        retainedProofSnapshot = null
        if (
            state != DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED ||
            boundProfile == null ||
            currentDisplay == null ||
            !isDogecoinTrustedPersonalNodeProofSnapshotFresh(
                profile = boundProfile,
                displaySnapshot = currentDisplay,
                proofToken = token,
                proofSnapshot = result,
                nowMonotonicMillis = nowMonotonicMillis
            )
        ) {
            refreshFreshness(nowMonotonicMillis)
            return false
        }
        retainedProofSnapshot = result
        return true
    }

    /** Incomplete/failed proof collection never degrades an otherwise fresh display session. */
    @Synchronized
    fun recordProofSnapshotFailure(token: DogecoinTrustedPersonalNodeProofRequestToken): Boolean {
        if (!matchesProofToken(token)) return false
        clearProofSession()
        return true
    }

    @Synchronized
    fun freshProofSnapshot(nowMonotonicMillis: Long): DogecoinTrustedPersonalNodeProofSnapshot? {
        refreshFreshness(nowMonotonicMillis)
        val boundProfile = profile
        val currentDisplay = displaySnapshot
        val currentProof = retainedProofSnapshot
        if (
            state != DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED ||
            boundProfile == null ||
            currentDisplay == null ||
            currentProof == null ||
            currentProof.binding != boundProfile.toSessionBinding() ||
            currentProof.capturedAtMonotonicMillis < currentDisplay.capturedAtMonotonicMillis ||
            !isDogecoinTrustedPersonalNodeFresh(
                currentProof.capturedAtMonotonicMillis,
                nowMonotonicMillis
            )
        ) {
            retainedProofSnapshot = null
            return null
        }
        return currentProof
    }

    /** Dismiss/cancellation resolves CHECKING synchronously instead of leaving an immortal spinner. */
    @Synchronized
    fun cancelActivation(token: DogecoinTrustedPersonalNodeActivationToken): Boolean {
        if (state != DogecoinTrustedPersonalNodeState.CHECKING || !matchesActiveToken(token)) return false
        activationToken = null
        state = if (stateBeforeChecking == DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE) {
            DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE
        } else {
            DogecoinTrustedPersonalNodeState.DEGRADED
        }
        return true
    }

    /** Synchronous lease check used before every RPC in a multi-call activation workflow. */
    @Synchronized
    fun isActivationCurrent(token: DogecoinTrustedPersonalNodeActivationToken): Boolean =
        state == DogecoinTrustedPersonalNodeState.CHECKING && matchesActiveToken(token)

    /**
     * Atomically accepts the fixed readiness+read result. Freshness starts at the explicit activation tap,
     * not RPC completion, so a slow multi-call workflow cannot mint a newly fresh two-minute window.
     */
    @Synchronized
    fun recordSuccessfulReadSnapshot(
        token: DogecoinTrustedPersonalNodeActivationToken,
        result: DogecoinTrustedPersonalNodeDisplaySnapshot,
        nowMonotonicMillis: Long
    ): Boolean {
        val boundProfile = profile ?: return false
        if (state != DogecoinTrustedPersonalNodeState.CHECKING || !matchesActiveToken(token)) return false
        val timedSnapshot = DogecoinTrustedPersonalNodeTimedDisplaySnapshot(
            binding = token.binding,
            capturedAtMonotonicMillis = token.startedAtMonotonicMillis,
            nodeSnapshot = result
        )
        if (!isDogecoinTrustedPersonalNodeTimedSnapshotFresh(boundProfile, timedSnapshot, nowMonotonicMillis)) {
            state = DogecoinTrustedPersonalNodeState.DEGRADED
            return false
        }
        displaySnapshot = timedSnapshot
        state = DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED
        stateBeforeChecking = state
        return true
    }

    /** Transport, parse, or ordinary node failures retain any last snapshot only as explicitly stale data. */
    @Synchronized
    fun recordTransientFailure(token: DogecoinTrustedPersonalNodeActivationToken): Boolean {
        if (!matchesActiveToken(token)) return false
        if (
            state != DogecoinTrustedPersonalNodeState.CHECKING &&
            state != DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED
        ) {
            return false
        }
        clearProofSession()
        state = DogecoinTrustedPersonalNodeState.DEGRADED
        return true
    }

    /** A rejected credential cannot enter the DEGRADED retry loop. Re-provisioning is required. */
    @Synchronized
    fun recordAuthenticationRequired(token: DogecoinTrustedPersonalNodeActivationToken): Boolean {
        if (!matchesActiveToken(token)) return false
        if (
            state != DogecoinTrustedPersonalNodeState.CHECKING &&
            state != DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED
        ) {
            return false
        }
        clearReadSession()
        state = DogecoinTrustedPersonalNodeState.AUTH_REQUIRED
        return true
    }

    /** Expires a live readiness/snapshot by phone monotonic time; no server clock is consulted. */
    @Synchronized
    fun refreshFreshness(nowMonotonicMillis: Long): DogecoinTrustedPersonalNodeState {
        if (state != DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED) return state
        val boundProfile = profile
        val currentSnapshot = displaySnapshot
        if (
            boundProfile == null ||
            currentSnapshot == null ||
            !isDogecoinTrustedPersonalNodeTimedSnapshotFresh(boundProfile, currentSnapshot, nowMonotonicMillis)
        ) {
            clearProofSession()
            state = DogecoinTrustedPersonalNodeState.DEGRADED
        } else {
            retainedProofSnapshot?.let { proof ->
                if (
                    proof.binding != boundProfile.toSessionBinding() ||
                    proof.capturedAtMonotonicMillis < currentSnapshot.capturedAtMonotonicMillis ||
                    !isDogecoinTrustedPersonalNodeFresh(
                        proof.capturedAtMonotonicMillis,
                        nowMonotonicMillis
                    )
                ) {
                    clearProofSession()
                }
            }
        }
        return state
    }

    @Synchronized
    fun freshDisplaySnapshot(nowMonotonicMillis: Long): DogecoinTrustedPersonalNodeTimedDisplaySnapshot? {
        refreshFreshness(nowMonotonicMillis)
        return displaySnapshot.takeIf { state == DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED }
    }

    /** Ends use for this process without deleting the durable authorization. */
    @Synchronized
    fun deactivate() {
        if (
            profile == null ||
            (state != DogecoinTrustedPersonalNodeState.CHECKING &&
                state != DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED &&
                state != DogecoinTrustedPersonalNodeState.DEGRADED)
        ) {
            return
        }
        clearReadSession()
        clearTransientProvisioning()
        state = DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE
        inactiveStateBeforeProvisioning = state
    }

    /** Rebinding to a different durable revision always drops transient provisioning/session state. */
    @Synchronized
    fun synchronizePersistedAuthorization(
        savedState: DogecoinTrustedPersonalNodeState,
        savedProfile: DogecoinTrustedPersonalNodeProfile?
    ) {
        val validProfile = savedProfile
            ?.takeIf { savedState == DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE }
            ?.takeIf(::isValidDogecoinTrustedPersonalNodeProfile)
        if (profile == validProfile && validProfile != null && state.isLiveReadSessionState()) {
            return
        }
        clearTransientProvisioning()
        clearReadSession()
        profile = validProfile
        state = initialState(savedState, validProfile)
        inactiveStateBeforeProvisioning = state
    }

    @Synchronized
    fun revoke() {
        clearTransientProvisioning()
        clearReadSession()
        profile = null
        state = DogecoinTrustedPersonalNodeState.REVOKED
        inactiveStateBeforeProvisioning = state
    }

    private fun inactiveFallbackState(): DogecoinTrustedPersonalNodeState =
        when {
            inactiveStateBeforeProvisioning == DogecoinTrustedPersonalNodeState.REVOKED ->
                DogecoinTrustedPersonalNodeState.REVOKED
            inactiveStateBeforeProvisioning == DogecoinTrustedPersonalNodeState.AUTH_REQUIRED ->
                DogecoinTrustedPersonalNodeState.AUTH_REQUIRED
            profile != null -> DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE
            else -> DogecoinTrustedPersonalNodeState.UNAUTHORIZED
        }

    private fun clearTransientProvisioning() {
        offeredToken = null
        consumedToken = null
        provisioningResult = null
        issuedCandidate = null
    }

    private fun beginActivationInternal(
        boundProfile: DogecoinTrustedPersonalNodeProfile,
        nowMonotonicMillis: Long,
        keepDisplaySnapshot: Boolean,
        previousState: DogecoinTrustedPersonalNodeState
    ): DogecoinTrustedPersonalNodeActivationToken {
        clearTransientProvisioning()
        clearProofSession()
        if (!keepDisplaySnapshot) displaySnapshot = null
        val token = DogecoinTrustedPersonalNodeActivationToken(
            nonce = nextActivationNonce,
            binding = boundProfile.toSessionBinding(),
            startedAtMonotonicMillis = nowMonotonicMillis
        )
        nextActivationNonce = if (nextActivationNonce == Long.MAX_VALUE) 1L else nextActivationNonce + 1L
        activationToken = token
        stateBeforeChecking = previousState
        state = DogecoinTrustedPersonalNodeState.CHECKING
        return token
    }

    private fun matchesActiveToken(token: DogecoinTrustedPersonalNodeActivationToken): Boolean =
        activationToken == token && profile?.toSessionBinding() == token.binding

    private fun matchesProofToken(token: DogecoinTrustedPersonalNodeProofRequestToken): Boolean =
        proofToken == token && profile?.toSessionBinding() == token.binding

    private fun clearProofSession() {
        proofToken = null
        retainedProofSnapshot = null
    }

    private fun clearReadSession() {
        activationToken = null
        stateBeforeChecking = DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE
        displaySnapshot = null
        clearProofSession()
    }

    private fun DogecoinTrustedPersonalNodeState.isLiveReadSessionState(): Boolean =
        this == DogecoinTrustedPersonalNodeState.CHECKING ||
            this == DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED ||
            this == DogecoinTrustedPersonalNodeState.DEGRADED ||
            this == DogecoinTrustedPersonalNodeState.AUTH_REQUIRED

    private fun DogecoinTrustedPersonalNodeProfile.matches(
        candidate: DogecoinTrustedPersonalNodeProfileCandidate
    ): Boolean =
        origin == candidate.origin &&
            network == candidate.network &&
            androidAddress == candidate.androidAddress &&
            coreWalletId == candidate.coreWalletId &&
            rescanAttested == candidate.rescanAttested &&
            rescanAttestedAtMillis == candidate.rescanAttestedAtMillis

    private companion object {
        fun initialState(
            savedState: DogecoinTrustedPersonalNodeState,
            savedProfile: DogecoinTrustedPersonalNodeProfile?
        ): DogecoinTrustedPersonalNodeState = when {
            savedState == DogecoinTrustedPersonalNodeState.REVOKED ->
                DogecoinTrustedPersonalNodeState.REVOKED
            savedState == DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE &&
                savedProfile != null &&
                isValidDogecoinTrustedPersonalNodeProfile(savedProfile) ->
                DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE
            else -> DogecoinTrustedPersonalNodeState.UNAUTHORIZED
        }
    }
}
