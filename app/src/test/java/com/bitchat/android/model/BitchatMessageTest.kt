package com.bitchat.android.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class BitchatMessageTest {
    @Test
    fun `binary payload preserves encrypted channel fields`() {
        val encryptedContent = byteArrayOf(1, 2, 3, 4, 5, 6)
        val message = BitchatMessage(
            id = "message-id",
            sender = "alice",
            content = "",
            timestamp = Date(1234L),
            senderPeerID = "peer-id",
            mentions = listOf("bob"),
            channel = "#ops",
            encryptedContent = encryptedContent,
            isEncrypted = true
        )

        val encoded = message.toBinaryPayload()
        assertNotNull(encoded)

        val decodedMessage = BitchatMessage.fromBinaryPayload(encoded!!)
        assertNotNull(decodedMessage)

        val decoded = decodedMessage!!
        assertEquals("message-id", decoded.id)
        assertEquals("alice", decoded.sender)
        assertEquals("peer-id", decoded.senderPeerID)
        assertEquals(listOf("bob"), decoded.mentions)
        assertEquals("#ops", decoded.channel)
        assertTrue(decoded.isEncrypted)
        assertArrayEquals(encryptedContent, decoded.encryptedContent)
    }
}
