package com.bitchat.android.features.dogecoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure parsers in [DogecoinExplorerClient]: the Blockbook v2 UTXO/sendtx shapes (the
 * keyless default) and the Blockchair dashboards/push shapes. Network I/O is not exercised here.
 */
class DogecoinExplorerClientTest {

    private val net = DogecoinNetwork.MAINNET
    private val cAddr = "DExampleAddress111111111111111111"
    private val script = "76a914deadbeefdeadbeefdeadbeefdeadbeefdeadbeef88ac"

    // A real, valid testnet P2PKH address so script derivation (used by the Blockbook parser) succeeds.
    private val tnet = DogecoinNetwork.TESTNET
    private val tAddr = "nceDCkWAP9tSktE5TJ5X6LVmD2r3HwiAXN"

    // ---- Blockbook (keyless default) ----

    @Test
    fun `parseBlockbookUtxos reads value-string, confirmations, derives p2pkh script`() {
        val body = """
            [
              {"txid":"${"aa".repeat(32)}","vout":0,"value":"100000000","height":5,"confirmations":10},
              {"txid":"${"bb".repeat(32)}","vout":2,"value":"50000000","confirmations":0}
            ]
        """.trimIndent()
        val utxos = DogecoinExplorerClient.parseBlockbookUtxos(body, tAddr, tnet)
        assertEquals(2, utxos.size)
        assertEquals("aa".repeat(32), utxos[0].txid)
        assertEquals(0, utxos[0].vout)
        assertEquals(100000000L, utxos[0].amountKoinu)
        assertEquals(10, utxos[0].confirmations)
        assertEquals(0, utxos[1].confirmations)
        assertTrue(utxos[0].scriptPubKeyHex.startsWith("76a914") && utxos[0].scriptPubKeyHex.endsWith("88ac"))
    }

    @Test
    fun `parseBlockbookUtxos skips malformed entries and non-array bodies`() {
        val body = """[{"txid":"short","vout":0,"value":"1","confirmations":1},{"txid":"${"cc".repeat(32)}","vout":1,"value":"7","confirmations":3}]"""
        val utxos = DogecoinExplorerClient.parseBlockbookUtxos(body, tAddr, tnet)
        assertEquals(1, utxos.size)
        assertEquals("cc".repeat(32), utxos[0].txid)
        assertTrue(DogecoinExplorerClient.parseBlockbookUtxos("""{"not":"array"}""", tAddr, tnet).isEmpty())
    }

    @Test
    fun `parseBlockbookSendResult reads result, rejects errors`() {
        val txid = "cc".repeat(32)
        assertEquals(txid, DogecoinExplorerClient.parseBlockbookSendResult("""{"result":"$txid"}"""))
        assertNull(DogecoinExplorerClient.parseBlockbookSendResult("""{"error":{"message":"bad tx"}}"""))
        assertNull(DogecoinExplorerClient.parseBlockbookSendResult("garbage"))
    }

    // ---- Blockchair (alternative) ----

    @Test
    fun `parseBlockchairAddress reads utxos, balance, derives confirmations`() {
        val body = """
            {"data":{"$cAddr":{
              "address":{"balance":150000000,"unconfirmed_balance":0,"utxo_count":2},
              "utxo":[
                {"block_id":991,"transaction_hash":"${"aa".repeat(32)}","index":0,"value":100000000,"script_hex":"$script"},
                {"block_id":-1,"transaction_hash":"${"bb".repeat(32)}","index":3,"value":50000000,"script_hex":"$script"}
              ]}},"context":{"state":1000}}
        """.trimIndent()
        val (utxos, balance) = DogecoinExplorerClient.parseBlockchairAddress(body, cAddr, net)
        assertEquals(2, utxos.size)
        assertEquals(10, utxos[0].confirmations)       // 1000 - 991 + 1
        assertEquals(0, utxos[1].confirmations)         // block_id -1 => mempool
        assertEquals(script, utxos[0].scriptPubKeyHex)
        assertEquals(150000000L, balance!!.confirmedKoinu)
        assertEquals(2, balance.utxoCount)
    }

    @Test
    fun `parseBlockchairAddress handles empty or malformed data`() {
        val (u1, b1) = DogecoinExplorerClient.parseBlockchairAddress("""{"data":{},"context":{"state":1}}""", cAddr, net)
        assertTrue(u1.isEmpty()); assertNull(b1)
        val (u2, b2) = DogecoinExplorerClient.parseBlockchairAddress("not json", cAddr, net)
        assertTrue(u2.isEmpty()); assertNull(b2)
    }

    @Test
    fun `parseBlockchairBroadcastTxid reads transaction_hash and bare string, rejects garbage`() {
        val txid = "dd".repeat(32)
        assertEquals(txid, DogecoinExplorerClient.parseBlockchairBroadcastTxid("""{"data":{"transaction_hash":"$txid"}}"""))
        assertEquals(txid, DogecoinExplorerClient.parseBlockchairBroadcastTxid("""{"data":"$txid"}"""))
        assertNull(DogecoinExplorerClient.parseBlockchairBroadcastTxid("""{"data":null,"error":"bad"}"""))
        assertNull(DogecoinExplorerClient.parseBlockchairBroadcastTxid("garbage"))
    }
}
