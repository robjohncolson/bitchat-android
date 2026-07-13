package com.bitchat.android.features.dogecoin

/**
 * Generic-backend DES-1 guardrail. The typed TRUSTED_PERSONAL_NODE route below is intentionally not
 * representable by this legacy backend-only overload.
 *
 * A generic RPC node may remain a read-only source on mainnet, but it is not an authorized signer input,
 * policy oracle, or broadcast route. Do not replace the typed proof-backed route with a caller Boolean.
 */
internal fun dogecoinSpendRouteAllowed(
    network: DogecoinNetwork,
    effectiveBackend: DogecoinBackend
): Boolean = network != DogecoinNetwork.MAINNET || effectiveBackend == DogecoinBackend.SPV

/**
 * A route identity for one send action. The TPN variant carries an exact holder-owned process lease;
 * generic RPC and explorer routes remain categorically distinct and fail closed on mainnet.
 */
internal sealed class DogecoinSpendRoute {
    object SPV : DogecoinSpendRoute()
    object GENERIC_RPC : DogecoinSpendRoute()
    object EXPLORER : DogecoinSpendRoute()

    class TRUSTED_PERSONAL_NODE internal constructor(
        val authorization: DogecoinTrustedPersonalNodeSpendAuthorization
    ) : DogecoinSpendRoute()
}

internal fun DogecoinTrustedPersonalNodeSessionHolder.beginSpendRoute(
    nowMonotonicMillis: Long
): DogecoinSpendRoute.TRUSTED_PERSONAL_NODE? =
    beginSpendAuthorization(nowMonotonicMillis)?.let(DogecoinSpendRoute::TRUSTED_PERSONAL_NODE)

/** Typed route policy. TPN is mainnet-only and must still own a fresh exact session lease. */
internal fun dogecoinSpendRouteAllowed(
    network: DogecoinNetwork,
    route: DogecoinSpendRoute,
    sessionHolder: DogecoinTrustedPersonalNodeSessionHolder? = null,
    nowMonotonicMillis: Long = 0L
): Boolean = when (route) {
    DogecoinSpendRoute.SPV -> true
    DogecoinSpendRoute.GENERIC_RPC,
    DogecoinSpendRoute.EXPLORER -> network != DogecoinNetwork.MAINNET
    is DogecoinSpendRoute.TRUSTED_PERSONAL_NODE ->
        network == DogecoinNetwork.MAINNET &&
            route.authorization.binding.network == network &&
            sessionHolder?.isSpendAuthorizationCurrent(
                route.authorization,
                nowMonotonicMillis
            ) == true
}

/** Generic RPC helpers and clients have no authorized mainnet spend route until TPN exists. */
internal fun dogecoinGenericRpcSpendAllowed(network: DogecoinNetwork): Boolean =
    network != DogecoinNetwork.MAINNET

/** Fail before parsing or disclosing signed transaction bytes to a generic mainnet RPC endpoint. */
internal fun requireDogecoinGenericRpcSpendAllowed(network: DogecoinNetwork) {
    check(dogecoinGenericRpcSpendAllowed(network)) {
        "Mainnet RPC spending requires a Trusted personal node profile and an active session."
    }
}
