package com.bitchat.android.features.dogecoin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [DogecoinWalletDataSource] backed by the on-device [DogecoinSpvService] (bitcoinj light client).
 *
 * Reads (balance/UTXO) come from the bitcoinj wallet's synced view. [broadcast] is LIVE on TESTNET and
 * FAIL-CLOSED (MAINNET hard-blocked): build+sign always stays on-device in [DogecoinTransactionBuilder]
 * (Option B). This adapter only normalizes the signed hex, recomputes the canonical txid, and hands it
 * to [DogecoinSpvService.broadcast], which byte/txid-verifies via [DogecoinSpvBroadcastVerifier] before
 * any tx reaches peers. Callers gate trust on [DogecoinSpvService.status] (synced + peer floor) before
 * treating reads as authoritative. See docs/dogecoin-spv-phase3-plan.md.
 */
class DogecoinSpvDataSource(
    private val service: DogecoinSpvService
) : DogecoinWalletDataSource {

    override suspend fun getBalance(address: String, network: DogecoinNetwork): DogecoinWalletBalance =
        withContext(Dispatchers.IO) {  // lock acquisition + wallet iteration off the Main thread
            service.snapshotBalance(network)
                ?: throw IllegalStateException("Dogecoin SPV is not active for ${network.displayName}.")
        }

    override suspend fun listUnspent(address: String, network: DogecoinNetwork): List<DogecoinUtxo> =
        withContext(Dispatchers.IO) {
            service.snapshotUnspents(network)
                ?: throw IllegalStateException("Dogecoin SPV is not active for ${network.displayName}.")
        }

    override suspend fun broadcast(rawTransactionHex: String, network: DogecoinNetwork): String {
        require(network != DogecoinNetwork.MAINNET) {                           // defense-in-depth (service also refuses)
            "Dogecoin SPV broadcast is testnet-only in this version."
        }
        val normalized = DogecoinRawTxValidator.normalize(rawTransactionHex)     // fail closed on malformed/non-standard
        val expectedTxid = DogecoinTransactionBuilder.transactionId(normalized)  // canonical on-device txid (Option B)
        return withContext(Dispatchers.IO) {                                    // blocking peer await off the main thread
            service.broadcast(network, normalized, expectedTxid)
                ?: throw IllegalStateException(
                    "Dogecoin SPV is not synced/active for ${network.displayName}; cannot broadcast."
                )
        }
    }
}
