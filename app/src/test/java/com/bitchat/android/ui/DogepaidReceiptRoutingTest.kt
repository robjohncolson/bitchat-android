package com.bitchat.android.ui

import com.bitchat.android.features.dogecoin.DogecoinBase58
import com.bitchat.android.features.dogecoin.DogecoinNetwork
import com.bitchat.android.features.dogecoin.DogepaidReceipt
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DogepaidReceiptRoutingTest {
    @Test
    fun `receipt never enters generic clickable URL path`() {
        val address = DogecoinBase58.encodeChecked(
            DogecoinNetwork.TESTNET.p2pkhAddressHeader,
            ByteArray(20) { (it + 1).toByte() }
        )
        val encoded = DogepaidReceipt.encode(
            DogecoinNetwork.TESTNET,
            "ab".repeat(32),
            100_000_000L,
            address
        )

        assertTrue(MessageSpecialParser.findUrls(encoded).isEmpty())
    }
}
