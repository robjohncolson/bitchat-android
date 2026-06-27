package com.bitchat.android.features.dogecoin

/**
 * [DogecoinWalletDataSource] backed by the on-device [DogecoinSpvService] (bitcoinj light client).
 *
 * READ-ONLY this phase: balance/UTXO come from the bitcoinj wallet's synced view; [broadcast] THROWS
 * (SPV broadcast is wired, fail-closed, in a later phase — build+sign always stays on-device in
 * [DogecoinTransactionBuilder]). Callers gate trust on [DogecoinSpvService.status] (synced + peer floor)
 * before treating reads as authoritative. See docs/dogecoin-spv-integration-plan.md.
 */
class DogecoinSpvDataSource(
    private val service: DogecoinSpvService
) : DogecoinWalletDataSource {

    override suspend fun getBalance(address: String, network: DogecoinNetwork): DogecoinWalletBalance =
        service.snapshotBalance(network)
            ?: throw IllegalStateException("Dogecoin SPV is not active for ${network.displayName}.")

    override suspend fun listUnspent(address: String, network: DogecoinNetwork): List<DogecoinUtxo> =
        service.snapshotUnspents(network)
            ?: throw IllegalStateException("Dogecoin SPV is not active for ${network.displayName}.")

    override suspend fun broadcast(rawTransactionHex: String, network: DogecoinNetwork): String =
        throw UnsupportedOperationException(
            "Dogecoin SPV broadcast is enabled in a later phase; broadcast currently goes via a node or the mesh."
        )
}
