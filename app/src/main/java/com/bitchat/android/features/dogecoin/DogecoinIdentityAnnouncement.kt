package com.bitchat.android.features.dogecoin

import android.content.Context
import com.bitchat.android.model.DogecoinIdentityAddress

object DogecoinIdentityAnnouncement {
    fun currentReceiveAddress(context: Context): DogecoinIdentityAddress? {
        val repository = DogecoinWalletRepository(context.applicationContext)
        val network = repository.loadSelectedNetwork()
        val snapshot = repository.loadOrCreateWallet(network)
        val address = snapshot.key.address

        if (!DogecoinAddress.isValidAddress(address, network)) return null

        return DogecoinIdentityAddress(
            networkId = network.id,
            address = address
        )
    }
}
