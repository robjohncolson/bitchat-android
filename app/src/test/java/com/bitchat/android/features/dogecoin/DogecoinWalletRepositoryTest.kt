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

    @Test
    fun `saved address json parsing tolerates malformed invalid and duplicate entries`() {
        val network = DogecoinNetwork.MAINNET
        val mainnetAddress = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            network
        ).address
        val testnetAddress = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.TESTNET
        ).address

        // null / blank / non-array JSON inputs return an empty list and never throw.
        assertTrue(dogecoinSavedAddressesFromJson(null, network).isEmpty())
        assertTrue(dogecoinSavedAddressesFromJson("", network).isEmpty())
        assertTrue(dogecoinSavedAddressesFromJson("{ not json", network).isEmpty())
        assertTrue(dogecoinSavedAddressesFromJson("not even close", network).isEmpty())
        assertTrue(dogecoinSavedAddressesFromJson("{\"address\":\"$mainnetAddress\"}", network).isEmpty())

        // An array with junk entries: non-object, missing/invalid address, and wrong-network
        // address are all skipped; the duplicate of the valid address is deduped (first wins).
        val raw = "[" +
            "\"string-not-object\"," +
            "{\"label\":\"no address\"}," +
            "{\"address\":\"not-a-valid-address\",\"label\":\"bad\"}," +
            "{\"address\":\"$testnetAddress\",\"label\":\"wrong network\"}," +
            "{\"address\":\"$mainnetAddress\",\"label\":\"first\",\"savedAtMillis\":5}," +
            "{\"address\":\"$mainnetAddress\",\"label\":\"dupe\",\"savedAtMillis\":9}" +
            "]"
        val parsed = dogecoinSavedAddressesFromJson(raw, network)
        assertEquals(1, parsed.size)
        assertEquals(mainnetAddress, parsed[0].address)
        assertEquals("first", parsed[0].label)
        assertEquals(5L, parsed[0].savedAtMillis)
    }

    private fun clearDogecoinPrefs() {
        context.getSharedPreferences("dogecoin_wallet", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("dogecoin_testnet_wallet", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
