package com.bitchat.android.features.dogecoin

/**
 * DES-1 guardrail while TRUSTED_PERSONAL_NODE is not implemented.
 *
 * A generic RPC node may remain a read-only source on mainnet, but it is not an authorized signer input,
 * policy oracle, or broadcast route. A future TPN route must carry its own proof-backed profile/session type;
 * do not replace this boundary with a caller-supplied Boolean.
 */
internal fun dogecoinSpendRouteAllowed(
    network: DogecoinNetwork,
    effectiveBackend: DogecoinBackend
): Boolean = network != DogecoinNetwork.MAINNET || effectiveBackend == DogecoinBackend.SPV

/** Generic RPC helpers and clients have no authorized mainnet spend route until TPN exists. */
internal fun dogecoinGenericRpcSpendAllowed(network: DogecoinNetwork): Boolean =
    network != DogecoinNetwork.MAINNET

/** Fail before parsing or disclosing signed transaction bytes to a generic mainnet RPC endpoint. */
internal fun requireDogecoinGenericRpcSpendAllowed(network: DogecoinNetwork) {
    check(dogecoinGenericRpcSpendAllowed(network)) {
        "Mainnet RPC spending requires a Trusted personal node profile and an active session."
    }
}
