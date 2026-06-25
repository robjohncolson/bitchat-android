package com.bitchat.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentBroadcastPacketTest {

    private fun uuid(seed: Int) = ByteArray(16) { (seed + it).toByte() }
    private val txid = "a".repeat(64)
    private val rawHex = "0100000001" + "ab".repeat(100) // even-length lowercase hex

    @Test
    fun `request round-trips`() {
        val req = PaymentBroadcastRequest(uuid(7), "testnet", rawHex, txid)
        val encoded = req.encode()
        assertNotNull(encoded)
        assertEquals(req, PaymentBroadcastRequest.decode(encoded!!))
    }

    @Test
    fun `request rejects wrong uuid length`() {
        assertNull(PaymentBroadcastRequest(ByteArray(15), "testnet", rawHex, txid).encode())
    }

    @Test
    fun `request rejects non-hex, odd-length, and bad txid`() {
        assertNull(PaymentBroadcastRequest(uuid(1), "testnet", "zzzz", txid).encode())
        assertNull(PaymentBroadcastRequest(uuid(1), "testnet", "abc", txid).encode())
        assertNull(PaymentBroadcastRequest(uuid(1), "testnet", rawHex, "xyz").encode())
    }

    @Test
    fun `oversize raw tx is rejected before allocation`() {
        val huge = "ab".repeat(PAYMENT_BROADCAST_MAX_RAW_TX_HEX) // 2x the cap in chars
        assertNull(PaymentBroadcastRequest(uuid(1), "testnet", huge, txid).encode())
    }

    @Test
    fun `decode rejects empty and truncated payloads`() {
        assertNull(PaymentBroadcastRequest.decode(ByteArray(0)))
        // declares a 16-byte UUID TLV but supplies no value bytes
        assertNull(PaymentBroadcastRequest.decode(byteArrayOf(0x00, 0, 0, 0, 16)))
    }

    @Test
    fun `unknown tlv is skipped for forward compatibility`() {
        val base = PaymentBroadcastRequest(uuid(5), "testnet", rawHex, txid).encode()!!
        val unknown = byteArrayOf(0x7f, 0, 0, 0, 2, 1, 2) // type 0x7f, len 2
        val decoded = PaymentBroadcastRequest.decode(base + unknown)
        assertNotNull(decoded)
        assertEquals("testnet", decoded!!.networkId)
    }

    @Test
    fun `result accepted round-trips and requires a txid`() {
        val res = PaymentBroadcastResult(uuid(2), PaymentBroadcastStatus.ACCEPTED, txid = txid)
        assertEquals(res, PaymentBroadcastResult.decode(res.encode()!!))
        assertNull(PaymentBroadcastResult(uuid(2), PaymentBroadcastStatus.ACCEPTED, txid = null).encode())
    }

    @Test
    fun `result rejected and declined round-trip`() {
        val rejected = PaymentBroadcastResult(
            uuid(3), PaymentBroadcastStatus.REJECTED,
            rejectCode = PaymentBroadcastRejectCode.MISSING_INPUTS, rejectDetail = "inputs spent"
        )
        val rDec = PaymentBroadcastResult.decode(rejected.encode()!!)!!
        assertEquals(PaymentBroadcastStatus.REJECTED, rDec.status)
        assertEquals(PaymentBroadcastRejectCode.MISSING_INPUTS, rDec.rejectCode)

        val declined = PaymentBroadcastResult(uuid(4), PaymentBroadcastStatus.DECLINED, rejectDetail = "not a helper")
        assertEquals(PaymentBroadcastStatus.DECLINED, PaymentBroadcastResult.decode(declined.encode()!!)!!.status)
    }

    @Test
    fun `reject code classification mirrors node messages`() {
        assertEquals(PaymentBroadcastRejectCode.MISSING_INPUTS, PaymentBroadcastRejectCode.classify("bad-txns-inputs-missingorspent"))
        assertEquals(PaymentBroadcastRejectCode.INSUFFICIENT_FEE, PaymentBroadcastRejectCode.classify("min relay fee not met"))
        assertEquals(PaymentBroadcastRejectCode.DUST, PaymentBroadcastRejectCode.classify("dust"))
        assertEquals(PaymentBroadcastRejectCode.ALREADY_KNOWN, PaymentBroadcastRejectCode.classify("txn-already-in-mempool"))
        assertEquals(PaymentBroadcastRejectCode.SCRIPT_VERIFY, PaymentBroadcastRejectCode.classify("mandatory-script-verify-flag-failed (...)"))
        assertEquals(PaymentBroadcastRejectCode.OTHER, PaymentBroadcastRejectCode.classify("some unrelated error"))
    }

    @Test
    fun `terminal-for-transaction classification`() {
        assertTrue(PaymentBroadcastRejectCode.MISSING_INPUTS.isTerminalForTransaction)
        assertTrue(PaymentBroadcastRejectCode.SCRIPT_VERIFY.isTerminalForTransaction)
        assertFalse(PaymentBroadcastRejectCode.ALREADY_KNOWN.isTerminalForTransaction)
        assertFalse(PaymentBroadcastRejectCode.NODE_NOT_READY.isTerminalForTransaction)
    }
}
