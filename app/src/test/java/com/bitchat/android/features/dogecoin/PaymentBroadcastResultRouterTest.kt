package com.bitchat.android.features.dogecoin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PaymentBroadcastResultRouter] — the bridge that routes a Nostr-delivered
 * PAYMENT_BROADCAST_RESULT to the active coordinator.
 */
class PaymentBroadcastResultRouterTest {

    @After
    fun tearDown() {
        PaymentBroadcastResultRouter.setSink(null)
    }

    @Test
    fun `deliver returns false when no sink is registered`() {
        PaymentBroadcastResultRouter.setSink(null)
        assertFalse(PaymentBroadcastResultRouter.deliver("helper", byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `deliver forwards id and payload to the registered sink`() {
        var seenId: String? = null
        var seenPayload: ByteArray? = null
        PaymentBroadcastResultRouter.setSink { id, payload ->
            seenId = id
            seenPayload = payload
        }
        val payload = byteArrayOf(9, 8, 7)
        assertTrue(PaymentBroadcastResultRouter.deliver("npubhex", payload))
        assertEquals("npubhex", seenId)
        assertTrue(payload.contentEquals(seenPayload!!))
    }

    @Test
    fun `a throwing sink is contained and reported as not consumed`() {
        PaymentBroadcastResultRouter.setSink { _, _ -> throw RuntimeException("boom") }
        assertFalse(PaymentBroadcastResultRouter.deliver("x", byteArrayOf()))
    }

    @Test
    fun `last writer wins and a null sink clears`() {
        var firstCalls = 0
        var secondCalls = 0
        PaymentBroadcastResultRouter.setSink { _, _ -> firstCalls++ }
        PaymentBroadcastResultRouter.setSink { _, _ -> secondCalls++ }
        PaymentBroadcastResultRouter.deliver("x", byteArrayOf())
        assertEquals(0, firstCalls)
        assertEquals(1, secondCalls)

        PaymentBroadcastResultRouter.setSink(null)
        assertFalse(PaymentBroadcastResultRouter.deliver("x", byteArrayOf()))
    }
}
