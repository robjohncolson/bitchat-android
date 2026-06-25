package com.bitchat.android.features.dogecoin

import android.content.Context
import com.bitchat.android.model.DogecoinIdentityAddress

object DogecoinIdentityAnnouncement {
    fun currentReceiveAddress(context: Context): DogecoinIdentityAddress? {
        val repository = DogecoinWalletRepository(context.applicationContext)

        // Opt-in only: never advertise a receive address unless the user explicitly enabled it.
        if (!repository.loadAdvertiseAddressEnabled()) return null

        val network = repository.loadSelectedNetwork()
        // Read-only: announcing must not create a wallet/key as a side effect. If no key exists for
        // the selected network, advertise nothing.
        val snapshot = repository.loadWalletIfPresent(network) ?: return null
        val address = snapshot.key.address

        if (!DogecoinAddress.isValidAddress(address, network)) return null

        return DogecoinIdentityAddress(
            networkId = network.id,
            address = address
        )
    }
}
