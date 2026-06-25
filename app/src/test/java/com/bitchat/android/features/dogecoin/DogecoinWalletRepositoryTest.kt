package com.bitchat.android.features.dogecoin

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DogecoinWalletRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearDogecoinPrefs()
    }

    @After
    fun tearDown() {
        clearDogecoinPrefs()
    }

    @Test
    fun `address book persists dedupes removes scopes by network and clears on reset`() {
        var repository = DogecoinWalletRepository(context)
        val mainAddressA = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET
        ).address
        val mainAddressB = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002",
            DogecoinNetwork.MAINNET
        ).address
        val testnetAddress = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.TESTNET
        ).address

        repository.upsertSavedAddress(DogecoinNetwork.MAINNET, mainAddressA, "first", 100L)
        repository.upsertSavedAddress(DogecoinNetwork.MAINNET, mainAddressB, "second", 200L)

        repository = DogecoinWalletRepository(context)
        assertEquals(
            listOf(mainAddressB, mainAddressA),
            repository.loadSavedAddresses(DogecoinNetwork.MAINNET).map { it.address }
        )

        repository.upsertSavedAddress(DogecoinNetwork.MAINNET, mainAddressA, "updated", 300L)
        val dedupedMainnet = repository.loadSavedAddresses(DogecoinNetwork.MAINNET)
        assertEquals(listOf(mainAddressA, mainAddressB), dedupedMainnet.map { it.address })
        assertEquals("updated", dedupedMainnet.first().label)
        assertEquals(300L, dedupedMainnet.first().savedAtMillis)

        repository.upsertSavedAddress(DogecoinNetwork.TESTNET, testnetAddress, "testnet", 400L)
        assertEquals(2, repository.loadSavedAddresses(DogecoinNetwork.MAINNET).size)
        assertEquals(
            listOf(testnetAddress),
            repository.loadSavedAddresses(DogecoinNetwork.TESTNET).map { it.address }
        )

        repository.removeSavedAddress(DogecoinNetwork.MAINNET, mainAddressB)
        assertEquals(
            listOf(mainAddressA),
            repository.loadSavedAddresses(DogecoinNetwork.MAINNET).map { it.address }
        )

        repository.resetWallet(DogecoinNetwork.MAINNET)
        assertTrue(repository.loadSavedAddresses(DogecoinNetwork.MAINNET).isEmpty())
        assertEquals(
            listOf(testnetAddress),
            repository.loadSavedAddresses(DogecoinNetwork.TESTNET).map { it.address }
        )
    }

    private fun clearDogecoinPrefs() {
        context.getSharedPreferences("dogecoin_wallet", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("dogecoin_testnet_wallet", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
