package com.bitchat.android.features.dogecoin

/**
 * [DogecoinWalletDataSource] backed by a public block explorer ("no-node" reads).
 *
 * READ-ONLY by design: [broadcast] is intentionally blocked so introducing this abstraction does NOT
 * turn the explorer into a user-selectable broadcast backend (the debug console keeps its own explicit
 * explorer-broadcast path, which is mainnet-refused). See docs/dogecoin-spv-integration-plan.md
 * (Phase 1 / adversarial critique).
 */
class DogecoinExplorerDataSource(
    private val explorerClient: DogecoinExplorerClient
) : DogecoinWalletDataSource {

    override suspend fun getBalance(address: String, network: DogecoinNetwork): DogecoinWalletBalance =
        explorerClient.getBalance(address, network)

    override suspend fun listUnspent(address: String, network: DogecoinNetwork): List<DogecoinUtxo> =
        explorerClient.listUtxos(address, network)

    override suspend fun broadcast(rawTransactionHex: String, network: DogecoinNetwork): String =
        throw UnsupportedOperationException(
            "Explorer broadcast is not exposed as a wallet backend; broadcast goes via a node or the mesh."
        )
}
