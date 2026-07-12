package com.bitchat.android.features.dogecoin

internal data class DogecoinTrustedPersonalNodeProvisioningToken(
    val nonce: Long,
    val draftRevision: Long
)

/**
 * Process-memory-only trust-ceremony state. It deliberately has no ACTIVE state in DES-1-A: a saved
 * profile always reconstructs as [DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE], and cannot
 * unlock reads or spending. Provisioning tokens are one-shot and bound to the current draft revision.
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

    @Synchronized
    fun beginProvisioning(draftRevision: Long): DogecoinTrustedPersonalNodeProvisioningToken {
        require(draftRevision >= 0L) { "Draft revision must be non-negative." }
        if (state != DogecoinTrustedPersonalNodeState.PROVISIONING) {
            inactiveStateBeforeProvisioning = state
        }
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
        clearTransientProvisioning()
        state = inactiveFallbackState()
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
        if (profile != validProfile || state == DogecoinTrustedPersonalNodeState.PROVISIONING) {
            clearTransientProvisioning()
        }
        profile = validProfile
        state = initialState(savedState, validProfile)
        inactiveStateBeforeProvisioning = state
    }

    @Synchronized
    fun revoke() {
        clearTransientProvisioning()
        profile = null
        state = DogecoinTrustedPersonalNodeState.REVOKED
        inactiveStateBeforeProvisioning = state
    }

    private fun inactiveFallbackState(): DogecoinTrustedPersonalNodeState =
        if (profile != null) {
            DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE
        } else if (inactiveStateBeforeProvisioning == DogecoinTrustedPersonalNodeState.REVOKED) {
            DogecoinTrustedPersonalNodeState.REVOKED
        } else {
            DogecoinTrustedPersonalNodeState.UNAUTHORIZED
        }

    private fun clearTransientProvisioning() {
        offeredToken = null
        consumedToken = null
        provisioningResult = null
        issuedCandidate = null
    }

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
