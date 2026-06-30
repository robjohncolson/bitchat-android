package com.bitchat.android.features.dogecoin

import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.junit.Assert.assertEquals
import org.junit.Test
import org.libdohj.params.DogecoinMainNetParams
import org.libdohj.params.DogecoinTestNet3Params

/**
 * Phase 2 foundation gate (docs/dogecoin-spv-integration-plan.md): the SPV light client shares the
 * wallet's EXISTING on-device key. bitcoinj must therefore import that raw secp256k1 key and derive the
 * IDENTICAL Base58 P2PKH address the app's own [DogecoinKeyGenerator] produces — otherwise SPV would
 * watch the wrong address and silently report a zero balance (a money-missing bug).
 *
 * This pins that equivalence (bitcoinj ECKey + libdohj Dogecoin params == app address) across networks
 * and key compression. No network needed.
 */
class DogecoinSpvKeyImportTest {

    @Test
    fun `bitcoinj plus libdohj derives the same P2PKH address as the app signer`() {
        // mainnet, compressed
        assertAddressMatches(
            "00000000000000000000000000000000000000000000000000000000000000a1",
            DogecoinNetwork.MAINNET, compressed = true, params = DogecoinMainNetParams.get()
        )
        // testnet, compressed
        assertAddressMatches(
            "0000000000000000000000000000000000000000000000000000000000000a17",
            DogecoinNetwork.TESTNET, compressed = true, params = DogecoinTestNet3Params.get()
        )
        // mainnet, uncompressed (65-byte pubkey)
        assertAddressMatches(
            "00000000000000000000000000000000000000000000000000000000000000e5",
            DogecoinNetwork.MAINNET, compressed = false, params = DogecoinMainNetParams.get()
        )
    }

    private fun assertAddressMatches(
        privHex: String,
        network: DogecoinNetwork,
        compressed: Boolean,
        params: NetworkParameters
    ) {
        val appAddress = DogecoinKeyGenerator.fromPrivateKeyHex(privHex, network, compressed).address
        val ecKey = ECKey.fromPrivate(DogecoinHex.decode(privHex), compressed)
        // bitcoinj 0.14.7: ECKey.toAddress(params) returns the P2PKH Address (LegacyAddress is 0.15+).
        val bitcoinjAddress = ecKey.toAddress(params).toString()
        assertEquals(
            "address mismatch for $network compressed=$compressed — SPV would watch the wrong address",
            appAddress,
            bitcoinjAddress
        )
    }
}
