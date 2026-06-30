package com.bitchat.android.features.dogecoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DogecoinSpvCrossCheckTest {

    private val script = "76a914000000000000000000000000000000000000000088ac"

    private fun utxo(txid: String, vout: Int, koinu: Long, script: String = this.script) =
        DogecoinUtxo(txid = txid, vout = vout, amountKoinu = koinu, scriptPubKeyHex = script, confirmations = 3)

    private fun out(koinu: Long, script: String = this.script, conf: Int = 3) =
        DogecoinTxOut(amountKoinu = koinu, scriptPubKeyHex = script, confirmations = conf)

    private fun key(txid: String, vout: Int) = DogecoinSpvCrossCheck.outpoint(txid, vout)

    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test
    fun `all utxos confirmed unspent by the node - PASS`() {
        val spv = listOf(utxo(a, 0, 500_000_000L), utxo(b, 1, 397_000_000L))
        val oracle = mapOf(key(a, 0) to out(500_000_000L), key(b, 1) to out(397_000_000L))

        val report = DogecoinSpvCrossCheck.compare(spv, oracle)

        assertTrue(report.allMatch)
        assertTrue(report.mismatches.isEmpty())
        assertEquals(897_000_000L, report.spvTotalKoinu)
        assertEquals(897_000_000L, report.nodeConfirmedKoinu)
    }

    @Test
    fun `a spent or missing utxo is flagged - FAIL`() {
        // The node returns null for an outpoint it considers spent/absent.
        val spv = listOf(utxo(a, 0, 500_000_000L), utxo(b, 1, 397_000_000L))
        val oracle = mapOf(key(a, 0) to out(500_000_000L), key(b, 1) to null)

        val report = DogecoinSpvCrossCheck.compare(spv, oracle)

        assertFalse(report.allMatch)
        assertEquals(1, report.mismatches.size)
        assertEquals(DogecoinSpvCrossCheck.Status.SPENT_OR_MISSING, report.mismatches.single().status)
        // nodeConfirmed only counts the MATCH entry.
        assertEquals(500_000_000L, report.nodeConfirmedKoinu)
    }

    @Test
    fun `an outpoint absent from the oracle map is spent or missing`() {
        val spv = listOf(utxo(a, 0, 500_000_000L))
        val report = DogecoinSpvCrossCheck.compare(spv, emptyMap())
        assertEquals(DogecoinSpvCrossCheck.Status.SPENT_OR_MISSING, report.entries.single().status)
        assertFalse(report.allMatch)
    }

    @Test
    fun `amount mismatch is flagged`() {
        val spv = listOf(utxo(a, 0, 500_000_000L))
        val oracle = mapOf(key(a, 0) to out(499_000_000L))
        val report = DogecoinSpvCrossCheck.compare(spv, oracle)
        assertEquals(DogecoinSpvCrossCheck.Status.AMOUNT_MISMATCH, report.entries.single().status)
        assertFalse(report.allMatch)
        assertEquals(0L, report.nodeConfirmedKoinu)
    }

    @Test
    fun `script mismatch is flagged`() {
        val spv = listOf(utxo(a, 0, 500_000_000L))
        val oracle = mapOf(key(a, 0) to out(500_000_000L, script = "76a914ffffffffffffffffffffffffffffffffffffffff88ac"))
        val report = DogecoinSpvCrossCheck.compare(spv, oracle)
        assertEquals(DogecoinSpvCrossCheck.Status.SCRIPT_MISMATCH, report.entries.single().status)
        assertFalse(report.allMatch)
    }

    @Test
    fun `script comparison is case-insensitive`() {
        val spv = listOf(utxo(a, 0, 500_000_000L, script = script.uppercase()))
        val oracle = mapOf(key(a, 0) to out(500_000_000L, script = script.lowercase()))
        val report = DogecoinSpvCrossCheck.compare(spv, oracle)
        assertTrue(report.allMatch)
    }

    @Test
    fun `empty spv set is not a pass`() {
        // Nothing to confirm => allMatch is false so an empty soak run never reads as "validated".
        val report = DogecoinSpvCrossCheck.compare(emptyList(), emptyMap())
        assertFalse(report.allMatch)
        assertEquals(0L, report.spvTotalKoinu)
    }
}
