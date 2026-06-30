package com.bitchat.android.nostr

import android.os.Build
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trips the 3b.1 Nostr-fallback envelope: encodeNoisePayloadForNostr (0x30/0x31) must produce a
 * `bitchat1:` BitChat packet whose decoded NoisePayload preserves the type and the inner bytes exactly,
 * mirroring the inbound path in NostrDirectMessageHandler.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class NostrEmbeddedBitChatBroadcastTest {

    // Decode with the JVM URL-safe decoder (lenient about missing padding) so the test does not depend on
    // android.util.Base64 at compile time; the production encode under test still runs via Robolectric.
    private fun decodeBitchat1(value: String): ByteArray =
        java.util.Base64.getUrlDecoder().decode(value.removePrefix("bitchat1:"))

    @Test
    fun `request payload round-trips through the bitchat1 envelope`() {
        val data = byteArrayOf(0x00, 0x01, 0x7f, 0xff.toByte(), 0x30, 0x42)
        val encoded = NostrEmbeddedBitChat.encodeNoisePayloadForNostr(
            type = NoisePayloadType.PAYMENT_BROADCAST_REQUEST,
            data = data,
            senderPeerID = "1122334455667788",
            recipientPeerID = "aabbccddeeff0011"
        )
        assertTrue(encoded != null && encoded.startsWith("bitchat1:"))

        val packet = BitchatPacket.fromBinaryData(decodeBitchat1(encoded!!))
        assertEquals(MessageType.NOISE_ENCRYPTED.value, packet!!.type)

        val payload = NoisePayload.decode(packet.payload)
        assertEquals(NoisePayloadType.PAYMENT_BROADCAST_REQUEST, payload!!.type)
        assertArrayEquals(data, payload.data)
    }

    @Test
    fun `result payload round-trips with no embedded recipient`() {
        val data = byteArrayOf(0x31, 0x10, 0x20, 0x30)
        val encoded = NostrEmbeddedBitChat.encodeNoisePayloadForNostr(
            type = NoisePayloadType.PAYMENT_BROADCAST_RESULT,
            data = data,
            senderPeerID = "1122334455667788"
        )
        assertTrue(encoded != null)

        val packet = BitchatPacket.fromBinaryData(decodeBitchat1(encoded!!))
        assertEquals(MessageType.NOISE_ENCRYPTED.value, packet!!.type)

        val payload = NoisePayload.decode(packet.payload)
        assertEquals(NoisePayloadType.PAYMENT_BROADCAST_RESULT, payload!!.type)
        assertArrayEquals(data, payload.data)
    }
}
