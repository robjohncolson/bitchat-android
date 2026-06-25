package com.bitchat.android.identity

import android.content.Context
import android.os.Build
import com.bitchat.android.features.dogecoin.DogecoinKeyGenerator
import com.bitchat.android.features.dogecoin.DogecoinNetwork
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class SecureIdentityStateManagerDogecoinTest {
    private lateinit var manager: SecureIdentityStateManager

    private val fingerprint = "a".repeat(64)
    private val oldPeerID = "aaaabbbbccccdddd"
    private val newPeerID = "ddddccccbbbbaaaa"

    @Before
    fun setup() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("dogecoin_identity_address_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        manager = SecureIdentityStateManager(prefs, forTesting = true)
        manager.clearIdentityData()
    }

    @After
    fun tearDown() {
        manager.clearIdentityData()
    }

    @Test
    fun `dogecoin address lookup is per network and survives peer id rotation`() {
        val testnetAddress = DogecoinKeyGenerator.generate(DogecoinNetwork.TESTNET).address
        val regtestAddress = DogecoinKeyGenerator.generate(DogecoinNetwork.REGTEST).address

        manager.cachePeerFingerprint(oldPeerID, fingerprint)
        manager.cachePeerDogecoinAddress(fingerprint, DogecoinNetwork.TESTNET.id, testnetAddress)
        manager.cachePeerDogecoinAddress(fingerprint, DogecoinNetwork.REGTEST.id, regtestAddress)

        assertEquals(testnetAddress, manager.getPeerDogecoinAddress(oldPeerID, DogecoinNetwork.TESTNET.id))
        assertEquals(regtestAddress, manager.getPeerDogecoinAddress(oldPeerID, DogecoinNetwork.REGTEST.id))

        manager.cachePeerFingerprint(newPeerID, fingerprint)

        assertEquals(testnetAddress, manager.getPeerDogecoinAddress(newPeerID, DogecoinNetwork.TESTNET.id))
        assertEquals(regtestAddress, manager.getPeerDogecoinAddress(fingerprint, DogecoinNetwork.REGTEST.id))
    }
}
