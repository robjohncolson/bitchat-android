package com.bitchat.android.ui

import com.bitchat.android.features.dogecoin.DogecoinBase58
import com.bitchat.android.features.dogecoin.DogecoinNetwork
import com.bitchat.android.features.dogecoin.DogepaidReceipt
import com.bitchat.android.model.BitchatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class DogepaidReceiptPresentationTest {
    private val txid = "12".repeat(32)
    private val address = DogecoinBase58.encodeChecked(
        DogecoinNetwork.TESTNET.p2pkhAddressHeader,
        ByteArray(20) { (it + 1).toByte() }
    )
    private val wire = DogepaidReceipt.encode(
        DogecoinNetwork.TESTNET,
        txid,
        100_000_000L,
        address
    )

    @Test
    fun `same structural sender and transaction gets one live receipt surface`() {
        val messages = listOf(
            message("first", "peer-a"),
            message("duplicate", "peer-a")
        )

        assertEquals(setOf("first"), canonicalDogepaidReceiptMessageIds(messages))
    }

    @Test
    fun `different sender cannot create a second live status for the same transaction`() {
        val messages = listOf(
            message("attacker", "peer-a"),
            message("legitimate", "peer-b")
        )

        assertEquals(setOf("attacker"), canonicalDogepaidReceiptMessageIds(messages))
    }

    @Test
    fun `missing structural identity still dedupes by transaction`() {
        val messages = listOf(
            message("one", null),
            message("two", null)
        )

        assertEquals(setOf("one"), canonicalDogepaidReceiptMessageIds(messages))
    }

    @Test
    fun `ordinary text never enters receipt dedupe`() {
        val ordinary = BitchatMessage(id = "ordinary", sender = "alice", content = "hello", timestamp = Date())
        assertTrue(canonicalDogepaidReceiptMessageIds(listOf(ordinary)).isEmpty())
    }

    private fun message(id: String, senderPeerId: String?) = BitchatMessage(
        id = id,
        sender = "same display name",
        content = wire,
        timestamp = Date(),
        isPrivate = true,
        senderPeerID = senderPeerId
    )
}
