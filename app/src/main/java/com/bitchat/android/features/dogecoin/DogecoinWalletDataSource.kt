package com.bitchat.android.features.dogecoin

/**
 * Which backend serves the wallet's balance/UTXO reads (and, later, broadcast) for a given network.
 * Persisted per network via [DogecoinWalletRepository.loadBackend]/[DogecoinWalletRepository.saveBackend];
 * default is [RPC]. [SPV] (bitcoinj + libdohj light client) is wired in a later phase — see
 * docs/dogecoin-spv-integration-plan.md.
 */
enum class DogecoinBackend { RPC, EXPLORER, SPV }

/**
 * Backend-agnostic read + broadcast surface the wallet UI consumes, so the RPC node, a public
 * explorer, and (later) the on-device SPV light client are interchangeable for balance/UTXO reads.
 *
 * Deliberately NARROW: only the surface every backend can honor lives here. Node-specific operations
 * (blockchain/node status, address watch status, mempool pre-acceptance, historical rescan) and rich
 * activity history stay RPC-specific — forcing them through one interface would be a leaky abstraction
 * (see docs/dogecoin-spv-integration-plan.md). Build+sign always stays on-device in
 * [DogecoinTransactionBuilder]; a data source only reads and relays bytes.
 */
interface DogecoinWalletDataSource {
    /** Confirmed/unconfirmed balance + UTXOs for [address]. */
    suspend fun getBalance(address: String, network: DogecoinNetwork): DogecoinWalletBalance

    /** Spendable UTXOs for [address], shaped like the RPC `listunspent` result. */
    suspend fun listUnspent(address: String, network: DogecoinNetwork): List<DogecoinUtxo>

    /**
     * Publish an already-signed raw transaction; returns the node-reported txid. Not every backend
     * may broadcast (e.g. the explorer adapter blocks it), so callers must handle the unsupported case.
     */
    suspend fun broadcast(rawTransactionHex: String, network: DogecoinNetwork): String
}
