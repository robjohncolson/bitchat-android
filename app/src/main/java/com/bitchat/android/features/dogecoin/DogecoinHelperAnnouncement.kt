package com.bitchat.android.features.dogecoin

import android.content.Context

/**
 * Builds the signed NODE_HELPER capability advert (Milestone 3b): the list of Dogecoin networks this
 * device has opted into broadcasting OTHER peers' transactions for. Advertising is exactly the helper
 * opt-in flag, per network and default off (mainnet must be enabled separately), so a device that is
 * not a helper advertises nothing. The advert rides the Ed25519-signed IdentityAnnouncement, so peers
 * only learn of it after signature verification.
 */
object DogecoinHelperAnnouncement {
    fun helperNetworks(context: Context): List<String> {
        val repository = DogecoinWalletRepository(context.applicationContext)
        return DogecoinNetwork.values()
            .filter { repository.loadHelperEnabled(it) }
            .map { it.id }
    }
}
