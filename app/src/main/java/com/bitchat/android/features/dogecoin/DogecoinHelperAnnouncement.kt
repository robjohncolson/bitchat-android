package com.bitchat.android.features.dogecoin

import android.content.Context

/**
 * Builds the signed NODE_HELPER capability advert (Milestone 3b): the list of Dogecoin networks this
 * device has opted into broadcasting OTHER peers' transactions for. Advertising is exactly the helper
 * opt-in flag intersected with current route eligibility (generic mainnet RPC is never eligible), so a
 * device that is not an effective helper advertises nothing. The advert rides the Ed25519-signed IdentityAnnouncement, so peers
 * only learn of it after signature verification.
 */
object DogecoinHelperAnnouncement {
    fun helperNetworks(context: Context): List<String> {
        val repository = DogecoinWalletRepository(context.applicationContext)
        return DogecoinNetwork.values()
            .filter { dogecoinGenericRpcSpendAllowed(it) && repository.loadHelperEnabled(it) }
            .map { it.id }
    }
}
