package com.bitchat.android.features.dogecoin

internal const val DOGECOIN_TRUSTED_PERSONAL_NODE_POLICY_VERSION = 1

internal enum class DogecoinTrustedPersonalNodeState {
    UNAUTHORIZED,
    PROVISIONING,
    AUTHORIZED_INACTIVE,
    CHECKING,
    ACTIVE_UNVERIFIED,
    DEGRADED,
    AUTH_REQUIRED,
    REVOKED
}

internal data class DogecoinTrustedPersonalNodeProfileCandidate(
    val origin: String,
    val network: DogecoinNetwork,
    val androidAddress: String,
    val coreWalletId: String,
    val rescanAttested: Boolean,
    val rescanAttestedAtMillis: Long
)

internal data class DogecoinTrustedPersonalNodeProfile(
    val origin: String,
    val network: DogecoinNetwork,
    val androidAddress: String,
    val coreWalletId: String,
    val policyVersion: Int,
    val revision: Long,
    val authorizedAtMillis: Long,
    val rescanAttested: Boolean,
    val rescanAttestedAtMillis: Long
)

/** RPC credentials are never part of the durable trust record or a printable config object. */
internal data class DogecoinTrustedPersonalNodeCredentials(
    val username: String,
    val password: String
) {
    fun isValid(): Boolean =
        username.isNotBlank() &&
            username == username.trim() &&
            username.length <= MAX_USERNAME_CHARS &&
            ':' !in username &&
            username.none { it.isISOControl() } &&
            password.isNotBlank() &&
            password.length <= MAX_PASSWORD_CHARS &&
            password.none { it.isISOControl() }

    override fun toString(): String =
        "DogecoinTrustedPersonalNodeCredentials(username=<redacted>, password=<redacted>)"

    private companion object {
        const val MAX_USERNAME_CHARS = 128
        const val MAX_PASSWORD_CHARS = 1024
    }
}

/** The five independent acknowledgements required by the first-authorize oracle warning. */
internal data class DogecoinTrustedPersonalNodeConfirmations(
    val controlsLaptop: Boolean,
    val loopbackServeNoFunnel: Boolean,
    val watchOnlyNoWif: Boolean,
    val acceptsNodeOracleRisk: Boolean,
    val understandsTailscaleIsNotAnonymity: Boolean
) {
    val allRequired: Boolean
        get() = controlsLaptop &&
            loopbackServeNoFunnel &&
            watchOnlyNoWif &&
            acceptsNodeOracleRisk &&
            understandsTailscaleIsNotAnonymity
}

/**
 * Strict TPN origin parser. Unlike the general RPC classifier this accepts only the canonical literal
 * origin that is displayed and authorized: lowercase ASCII, implicit 443, and no root slash or other
 * URL component. A valid sibling host is a valid candidate for a new ceremony, but is never equivalent.
 */
internal fun exactDogecoinTrustedPersonalNodeOriginOrNull(raw: String): String? {
    val match = EXACT_TAILSCALE_ORIGIN.matchEntire(raw) ?: return null
    val machine = match.groupValues[1]
    val tailnet = match.groupValues[2]
    if (machine.startsWith("xn--") || tailnet.startsWith("xn--")) return null
    return raw
}

internal fun dogecoinTrustedPersonalNodeOriginMatches(bound: String, candidate: String): Boolean =
    exactDogecoinTrustedPersonalNodeOriginOrNull(bound) != null &&
        exactDogecoinTrustedPersonalNodeOriginOrNull(candidate) != null &&
        bound == candidate

/** Core wallet identity returned by the node, stored exactly as tested and as one URL path segment. */
internal fun canonicalDogecoinTrustedPersonalNodeWalletIdOrNull(raw: String): String? {
    if (raw.isEmpty() || raw.length > 128) return null
    // OkHttp canonicalizes these as URL path traversal rather than a literal wallet segment.
    if (raw == "." || raw == "..") return null
    if (raw != raw.trim()) return null
    if (raw.any { it.isISOControl() || it == '/' || it == '\\' }) return null
    return raw
}

internal fun isValidDogecoinTrustedPersonalNodeProfileCandidate(
    candidate: DogecoinTrustedPersonalNodeProfileCandidate
): Boolean =
    exactDogecoinTrustedPersonalNodeOriginOrNull(candidate.origin) == candidate.origin &&
        candidate.network == DogecoinNetwork.MAINNET &&
        isExactMainnetP2pkhAddress(candidate.androidAddress) &&
        canonicalDogecoinTrustedPersonalNodeWalletIdOrNull(candidate.coreWalletId) == candidate.coreWalletId &&
        candidate.rescanAttested &&
        candidate.rescanAttestedAtMillis > 0L

internal fun isValidDogecoinTrustedPersonalNodeProfile(
    profile: DogecoinTrustedPersonalNodeProfile
): Boolean =
    exactDogecoinTrustedPersonalNodeOriginOrNull(profile.origin) == profile.origin &&
        profile.network == DogecoinNetwork.MAINNET &&
        isExactMainnetP2pkhAddress(profile.androidAddress) &&
        canonicalDogecoinTrustedPersonalNodeWalletIdOrNull(profile.coreWalletId) == profile.coreWalletId &&
        profile.policyVersion == DOGECOIN_TRUSTED_PERSONAL_NODE_POLICY_VERSION &&
        profile.revision > 0L &&
        profile.authorizedAtMillis > 0L &&
        profile.rescanAttested &&
        profile.rescanAttestedAtMillis > 0L &&
        profile.rescanAttestedAtMillis <= profile.authorizedAtMillis

internal fun isValidDogecoinTrustedPersonalNodeProvisioningResult(
    result: DogecoinTrustedPersonalNodeProvisioningResult
): Boolean =
    exactDogecoinTrustedPersonalNodeOriginOrNull(result.origin) == result.origin &&
        result.network == DogecoinNetwork.MAINNET &&
        result.chain == DogecoinNetwork.MAINNET.chainName &&
        isExactMainnetP2pkhAddress(result.androidAddress) &&
        canonicalDogecoinTrustedPersonalNodeWalletIdOrNull(result.coreWalletId) == result.coreWalletId &&
        result.watchStatus.address == result.androidAddress &&
        result.watchStatus.isMine == false &&
        result.watchStatus.isWatchOnly == true

private fun isExactMainnetP2pkhAddress(address: String): Boolean =
    address == address.trim() && DogecoinAddress.isValidP2pkhAddress(address, DogecoinNetwork.MAINNET)

private val EXACT_TAILSCALE_ORIGIN = Regex(
    "^https://([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.ts\\.net$"
)
