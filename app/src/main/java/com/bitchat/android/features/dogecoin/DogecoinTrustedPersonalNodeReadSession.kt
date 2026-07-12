package com.bitchat.android.features.dogecoin

/** DES-1-B display/readiness values are fresh for at most two monotonic minutes. */
internal const val DOGECOIN_TPN_SNAPSHOT_TTL_MILLIS = 120_000L

/** The complete durable tuple an in-memory activation is bound to. */
internal data class DogecoinTrustedPersonalNodeSessionBinding(
    val origin: String,
    val network: DogecoinNetwork,
    val androidAddress: String,
    val coreWalletId: String,
    val policyVersion: Int,
    val profileRevision: Long
)

internal fun DogecoinTrustedPersonalNodeProfile.toSessionBinding():
    DogecoinTrustedPersonalNodeSessionBinding = DogecoinTrustedPersonalNodeSessionBinding(
        origin = origin,
        network = network,
        androidAddress = androidAddress,
        coreWalletId = coreWalletId,
        policyVersion = policyVersion,
        profileRevision = revision
    )

/** One explicit, process-memory-only activation attempt. */
internal data class DogecoinTrustedPersonalNodeActivationToken(
    val nonce: Long,
    val binding: DogecoinTrustedPersonalNodeSessionBinding,
    val startedAtMonotonicMillis: Long
)

/** Read-only values for display. These types provide no signer or broadcast capability. */
internal data class DogecoinTrustedPersonalNodeTimedDisplaySnapshot(
    val binding: DogecoinTrustedPersonalNodeSessionBinding,
    val capturedAtMonotonicMillis: Long,
    val nodeSnapshot: DogecoinTrustedPersonalNodeDisplaySnapshot
)

internal fun isDogecoinTrustedPersonalNodeFresh(
    capturedAtMonotonicMillis: Long,
    nowMonotonicMillis: Long,
    ttlMillis: Long = DOGECOIN_TPN_SNAPSHOT_TTL_MILLIS
): Boolean {
    if (capturedAtMonotonicMillis < 0L || nowMonotonicMillis < capturedAtMonotonicMillis) return false
    if (ttlMillis < 0L) return false
    return nowMonotonicMillis - capturedAtMonotonicMillis <= ttlMillis
}

/** AUTH_REQUIRED is an inactive error state; only these states still own node/SPV session work. */
internal fun dogecoinTrustedPersonalNodeSessionUsesNode(
    state: DogecoinTrustedPersonalNodeState
): Boolean = state == DogecoinTrustedPersonalNodeState.CHECKING ||
    state == DogecoinTrustedPersonalNodeState.ACTIVE_UNVERIFIED ||
    state == DogecoinTrustedPersonalNodeState.DEGRADED

/**
 * Pure second boundary around the fixed RPC workflow. The RPC method cannot construct its result until
 * chain/IBD/network/wallet/watch/rescan/capability checks pass; this additionally rebinds every returned
 * identity and locked numeric release threshold before process session state accepts it.
 */
internal fun isDogecoinTrustedPersonalNodeReadSnapshotReady(
    profile: DogecoinTrustedPersonalNodeProfile,
    snapshot: DogecoinTrustedPersonalNodeDisplaySnapshot
): Boolean {
    if (!isValidDogecoinTrustedPersonalNodeProfile(profile)) return false
    if (snapshot.profileRevision != profile.revision) return false
    if (snapshot.origin != profile.origin) return false
    if (snapshot.androidAddress != profile.androidAddress) return false
    if (snapshot.coreWalletId != profile.coreWalletId) return false
    if (snapshot.blocks < 0 || snapshot.headers < snapshot.blocks) return false
    if (snapshot.headers - snapshot.blocks > DogecoinRpcClient.DOGECOIN_TPN_MAX_BLOCK_HEADER_LAG) return false
    if (!snapshot.verificationProgress.isFinite() || snapshot.verificationProgress !in 0.0..1.0) return false
    if (snapshot.verificationProgress < DogecoinRpcClient.DOGECOIN_TPN_MIN_VERIFICATION_PROGRESS) return false
    if (snapshot.peerCount < DogecoinRpcClient.DOGECOIN_TPN_MIN_MAINNET_PEERS) return false
    return true
}

internal fun isDogecoinTrustedPersonalNodeTimedSnapshotFresh(
    profile: DogecoinTrustedPersonalNodeProfile,
    snapshot: DogecoinTrustedPersonalNodeTimedDisplaySnapshot,
    nowMonotonicMillis: Long
): Boolean =
    snapshot.binding == profile.toSessionBinding() &&
        isDogecoinTrustedPersonalNodeReadSnapshotReady(profile, snapshot.nodeSnapshot) &&
        isDogecoinTrustedPersonalNodeFresh(snapshot.capturedAtMonotonicMillis, nowMonotonicMillis)

/**
 * One process-wide holder preserves an explicit activation across sheet close/reopen, but never across
 * process death. Every bind projects durable AUTHORIZED_INACTIVE data back into this memory holder;
 * network/profile/address changes synchronously discard the live session.
 */
internal object DogecoinTrustedPersonalNodeProcessSessionRegistry {
    private var holder = DogecoinTrustedPersonalNodeSessionHolder()

    @Synchronized
    fun bindPersistedAuthorization(
        savedState: DogecoinTrustedPersonalNodeState,
        savedProfile: DogecoinTrustedPersonalNodeProfile?,
        selectedNetwork: DogecoinNetwork,
        androidAddress: String
    ): DogecoinTrustedPersonalNodeSessionHolder {
        val matchingProfile = savedProfile?.takeIf {
            selectedNetwork == DogecoinNetwork.MAINNET &&
                it.network == selectedNetwork &&
                it.androidAddress == androidAddress &&
                isValidDogecoinTrustedPersonalNodeProfile(it)
        }
        val projectedState = when {
            selectedNetwork != DogecoinNetwork.MAINNET -> DogecoinTrustedPersonalNodeState.UNAUTHORIZED
            savedState == DogecoinTrustedPersonalNodeState.REVOKED -> DogecoinTrustedPersonalNodeState.REVOKED
            matchingProfile != null -> DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE
            else -> DogecoinTrustedPersonalNodeState.UNAUTHORIZED
        }
        holder.synchronizePersistedAuthorization(projectedState, matchingProfile)
        return holder
    }

    @Synchronized
    fun current(): DogecoinTrustedPersonalNodeSessionHolder = holder

    /** Tests only: production has no API that recreates an inactive holder short of process death. */
    @Synchronized
    internal fun resetForTests() {
        holder = DogecoinTrustedPersonalNodeSessionHolder()
    }
}
