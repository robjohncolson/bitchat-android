package com.bitchat.android.features.dogecoin

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `advertise address flag defaults off and persists across instances`() {
        var repository = DogecoinWalletRepository(context)
        assertFalse(repository.loadAdvertiseAddressEnabled())

        repository.saveAdvertiseAddressEnabled(true)
        repository = DogecoinWalletRepository(context)
        assertTrue(repository.loadAdvertiseAddressEnabled())

        repository.saveAdvertiseAddressEnabled(false)
        repository = DogecoinWalletRepository(context)
        assertFalse(repository.loadAdvertiseAddressEnabled())
    }

    @Test
    fun `wallet backend defaults to RPC and persists scoped per network across instances`() {
        var repository = DogecoinWalletRepository(context)
        assertEquals(DogecoinBackend.RPC, repository.loadBackend(DogecoinNetwork.MAINNET))
        assertEquals(DogecoinBackend.RPC, repository.loadBackend(DogecoinNetwork.TESTNET))

        repository.saveBackend(DogecoinNetwork.TESTNET, DogecoinBackend.SPV)
        repository = DogecoinWalletRepository(context)
        // Per-network scope: testnet changed, mainnet still the default.
        assertEquals(DogecoinBackend.SPV, repository.loadBackend(DogecoinNetwork.TESTNET))
        assertEquals(DogecoinBackend.RPC, repository.loadBackend(DogecoinNetwork.MAINNET))
    }

    @Test
    fun `resolveBackend defaults to Built-in only when no node is configured and a checkpoint asset ships`() {
        val repository = DogecoinWalletRepository(context)
        // Pure decision logic (asset-independent): Built-in only when no node AND a checkpoint asset ships.
        assertEquals(DogecoinBackend.SPV, repository.defaultBackend(noNodeConfigured = true, spvCheckpointShipped = true))
        assertEquals(DogecoinBackend.RPC, repository.defaultBackend(noNodeConfigured = false, spvCheckpointShipped = true))
        assertEquals(DogecoinBackend.RPC, repository.defaultBackend(noNodeConfigured = true, spvCheckpointShipped = false))
        assertEquals(DogecoinBackend.RPC, repository.defaultBackend(noNodeConfigured = false, spvCheckpointShipped = false))

        // resolveBackend branches that don't depend on the (unit-test-unavailable) asset:
        // regtest never offers SPV; mainnet ships no checkpoint asset -> RPC. (testnet's no-node -> SPV path
        // needs the bundled asset and is validated on-device.)
        assertEquals(DogecoinBackend.RPC, repository.resolveBackend(DogecoinNetwork.REGTEST))
        assertEquals(DogecoinBackend.RPC, repository.resolveBackend(DogecoinNetwork.MAINNET))
        // A configured node forces RPC even where SPV could ship.
        repository.saveRpcConfig(DogecoinNetwork.TESTNET, DogecoinRpcConfig(url = "http://10.0.0.24:44555"))
        assertEquals(DogecoinBackend.RPC, repository.resolveBackend(DogecoinNetwork.TESTNET))
        // An explicit saved choice always wins, regardless of node config.
        repository.saveBackend(DogecoinNetwork.TESTNET, DogecoinBackend.SPV)
        assertEquals(DogecoinBackend.SPV, repository.resolveBackend(DogecoinNetwork.TESTNET))
    }

    @Test
    fun `resolveDogecoinBackend routes untrusted endpoints to SPV and treats malformed choices as unset`() {
        // Guardrail: a saved RPC choice pointing at an UNTRUSTED endpoint resolves to Built-in SPV — the
        // untrusted node is never probed or used.
        assertEquals(
            DogecoinBackend.SPV,
            resolveDogecoinBackend(
                savedBackendName = "RPC",
                nodeConfigured = true,
                nodeEndpointTrusted = false,
                spvAvailable = true
            )
        )
        // Fail closed even without a recent SPV checkpoint: an untrusted RPC route never remains resolved.
        assertEquals(
            DogecoinBackend.SPV,
            resolveDogecoinBackend(
                savedBackendName = "RPC",
                nodeConfigured = true,
                nodeEndpointTrusted = false,
                spvAvailable = false
            )
        )
        // Trusted endpoint + explicit RPC choice: respected.
        assertEquals(
            DogecoinBackend.RPC,
            resolveDogecoinBackend(
                savedBackendName = "RPC",
                nodeConfigured = true,
                nodeEndpointTrusted = true,
                spvAvailable = true
            )
        )
        // Explicit SPV choice always stands.
        assertEquals(
            DogecoinBackend.SPV,
            resolveDogecoinBackend(
                savedBackendName = "SPV",
                nodeConfigured = true,
                nodeEndpointTrusted = true,
                spvAvailable = true
            )
        )
        // Malformed saved value: falls through to the soft default instead of silently meaning RPC.
        assertEquals(
            DogecoinBackend.SPV,
            resolveDogecoinBackend(
                savedBackendName = "garbage",
                nodeConfigured = false,
                nodeEndpointTrusted = false,
                spvAvailable = true
            )
        )
        assertEquals(
            DogecoinBackend.RPC,
            resolveDogecoinBackend(
                savedBackendName = "garbage",
                nodeConfigured = true,
                nodeEndpointTrusted = true,
                spvAvailable = true
            )
        )
        // Soft default: a configured-but-untrusted node always resolves away from RPC.
        assertEquals(
            DogecoinBackend.SPV,
            resolveDogecoinBackend(
                savedBackendName = null,
                nodeConfigured = true,
                nodeEndpointTrusted = false,
                spvAvailable = false
            )
        )
        assertEquals(
            DogecoinBackend.RPC,
            resolveDogecoinBackend(
                savedBackendName = null,
                nodeConfigured = true,
                nodeEndpointTrusted = true,
                spvAvailable = true
            )
        )
    }

    @Test
    fun `resolveBackend never selects a saved RPC node with an untrusted endpoint for probing`() {
        val repository = DogecoinWalletRepository(context)
        // Saved node URL is unverified public HTTPS. Resolution fails closed to SPV even when this unit-test
        // context has no checkpoint asset; the route may never remain selected merely because SPV is slower.
        repository.saveRpcConfig(DogecoinNetwork.TESTNET, DogecoinRpcConfig(url = "https://rpc.example.com"))
        repository.saveHelperEnabled(DogecoinNetwork.TESTNET, true)
        assertFalse(
            classifyDogecoinRpcEndpoint(
                repository.loadRpcConfig(DogecoinNetwork.TESTNET).url
            ).isTrustedRpcRoute
        )
        assertEquals(DogecoinBackend.SPV, repository.resolveBackend(DogecoinNetwork.TESTNET))
        assertFalse(repository.loadHelperEnabled(DogecoinNetwork.TESTNET))

        repository.saveRpcConfig(DogecoinNetwork.TESTNET, DogecoinRpcConfig(url = "http://127.0.0.1:44555"))
        assertTrue(repository.loadHelperEnabled(DogecoinNetwork.TESTNET))
    }

    @Test
    fun `spv birthdate is generation time for a generated key and the conservative floor for an import`() {
        val conservativeFloorMillis = 1609459200000L // 2021-01-01 UTC, the import default
        val repository = DogecoinWalletRepository(context)
        val beforeGenMillis = System.currentTimeMillis()

        // Generated key: SPV birthdate ~= now (no funds can predate generation), NOT the old floor.
        repository.loadOrCreateWallet(DogecoinNetwork.TESTNET)
        val genBirthdate = repository.loadSpvBirthdateMillis(DogecoinNetwork.TESTNET)
        assertTrue("generated key birthdate should be ~now", genBirthdate >= beforeGenMillis - 5000)

        // Import a WIF over it: birthdate must drop to the conservative floor — NEVER the import time,
        // which would let CheckpointManager fast-forward past funding txs (silent money-missing).
        val wif = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000a17",
            DogecoinNetwork.TESTNET
        ).wif
        repository.importWalletFromWif(DogecoinNetwork.TESTNET, wif)
        val importBirthdate = repository.loadSpvBirthdateMillis(DogecoinNetwork.TESTNET)
        assertEquals("import must use the conservative floor, not import time", conservativeFloorMillis, importBirthdate)
        assertTrue("import birthdate must be earlier than now", importBirthdate < beforeGenMillis)

        // lower-only: a later candidate (e.g. a re-import/backup) must never push the birthdate forward.
        repository.lowerSpvBirthdateMillis(DogecoinNetwork.TESTNET, System.currentTimeMillis())
        assertEquals(conservativeFloorMillis, repository.loadSpvBirthdateMillis(DogecoinNetwork.TESTNET))
        // ...but an EARLIER candidate does lower it.
        repository.lowerSpvBirthdateMillis(DogecoinNetwork.TESTNET, conservativeFloorMillis - 1000L)
        assertEquals(conservativeFloorMillis - 1000L, repository.loadSpvBirthdateMillis(DogecoinNetwork.TESTNET))
    }

    @Test
    fun `loadWalletIfPresent is read-only and never generates a key`() {
        val repository = DogecoinWalletRepository(context)

        // No key yet: returns null, and repeated reads do not create one as a side effect.
        assertNull(repository.loadWalletIfPresent(DogecoinNetwork.MAINNET))
        assertNull(repository.loadWalletIfPresent(DogecoinNetwork.MAINNET))

        // Once a wallet exists, the read-only loader returns the same key.
        val created = repository.loadOrCreateWallet(DogecoinNetwork.MAINNET)
        assertEquals(created.key.address, repository.loadWalletIfPresent(DogecoinNetwork.MAINNET)?.key?.address)
    }

    @Test
    fun `currentReceiveAddress is opt-in off by default and read-only`() {
        val repository = DogecoinWalletRepository(context)
        repository.saveSelectedNetwork(DogecoinNetwork.TESTNET)
        val snapshot = repository.loadOrCreateWallet(DogecoinNetwork.TESTNET)

        // Advertising is off by default: nothing is advertised even though a wallet exists.
        assertFalse(repository.loadAdvertiseAddressEnabled())
        assertNull(DogecoinIdentityAnnouncement.currentReceiveAddress(context))

        // Enabled + wallet exists: advertises the selected-network address.
        repository.saveAdvertiseAddressEnabled(true)
        val advertised = DogecoinIdentityAnnouncement.currentReceiveAddress(context)
        assertEquals(DogecoinNetwork.TESTNET.id, advertised?.networkId)
        assertEquals(snapshot.key.address, advertised?.address)
    }

    @Test
    fun `currentReceiveAddress never creates a wallet key for an absent network`() {
        val repository = DogecoinWalletRepository(context)
        repository.saveSelectedNetwork(DogecoinNetwork.MAINNET)
        repository.saveAdvertiseAddressEnabled(true)

        // Advertising is enabled but no mainnet key exists: must return null without generating one.
        assertNull(DogecoinIdentityAnnouncement.currentReceiveAddress(context))
        assertNull(repository.loadWalletIfPresent(DogecoinNetwork.MAINNET))
    }

    private fun clearDogecoinPrefs() {
        context.getSharedPreferences("dogecoin_wallet", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("dogecoin_testnet_wallet", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
