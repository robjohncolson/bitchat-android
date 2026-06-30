package com.bitchat.android.features.dogecoin

/**
 * [DogecoinWalletDataSource] backed by a personal Dogecoin Core RPC node.
 *
 * Thin delegation to [DogecoinRpcClient] using the [config] the caller already captured, so routing a
 * read through this adapter is byte-identical to calling the client directly (the existing wallet sheet
 * captures + saves [config] immediately before each read). broadcast() maps to `sendRawTransaction`.
 */
class DogecoinRpcDataSource(
    private val rpcClient: DogecoinRpcClient,
    private val config: DogecoinRpcConfig
) : DogecoinWalletDataSource {

    override suspend fun getBalance(address: String, network: DogecoinNetwork): DogecoinWalletBalance =
        rpcClient.getWalletBalance(config, address, network)

    override suspend fun listUnspent(address: String, network: DogecoinNetwork): List<DogecoinUtxo> =
        rpcClient.listUnspent(config, address, network)

    override suspend fun broadcast(rawTransactionHex: String, network: DogecoinNetwork): String =
        rpcClient.sendRawTransaction(config, rawTransactionHex, network)
}
