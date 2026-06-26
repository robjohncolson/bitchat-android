package com.bitchat.android.features.dogecoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ExplorerTxConfirmationChecker.parsePresence] and the URL policy. The safety-critical
 * property: only a definitive sighting returns true (which upgrades Claimed -> Confirmed); ambiguous or
 * unrecognized responses must return null so they never manufacture corroboration.
 */
class DogecoinTxConfirmationCheckerTest {

    private val txid = "ab".repeat(32) // 64 lowercase hex

    @Test
    fun `blockchair envelope containing the txid is present`() {
        val body = """{"data":{"$txid":{"transaction":{"block_id":123}}},"context":{"code":200}}"""
        assertEquals(true, ExplorerTxConfirmationChecker.parsePresence(body, txid))
    }

    @Test
    fun `blockchair match is case-insensitive on the txid key`() {
        val body = """{"data":{"${txid.uppercase()}":{"transaction":{}}},"context":{"code":200}}"""
        assertEquals(true, ExplorerTxConfirmationChecker.parsePresence(body, txid))
    }

    @Test
    fun `blockchair envelope with empty data is an authoritative not-found`() {
        val body = """{"data":{},"context":{"code":200}}"""
        assertEquals(false, ExplorerTxConfirmationChecker.parsePresence(body, txid))
    }

    @Test
    fun `blockchair-shaped data with a null value for the txid is not present`() {
        val body = """{"data":{"$txid":null},"context":{"code":200}}"""
        assertEquals(false, ExplorerTxConfirmationChecker.parsePresence(body, txid))
    }

    @Test
    fun `data object without a context envelope and no match is unknown`() {
        // A "data" object that is not the recognized Blockchair envelope: do not assert not-found.
        val body = """{"data":{"someOtherTxid":{}}}"""
        assertNull(ExplorerTxConfirmationChecker.parsePresence(body, txid))
    }

    @Test
    fun `generic explorer echoing the txid is present`() {
        assertEquals(true, ExplorerTxConfirmationChecker.parsePresence("""{"txid":"$txid","confirmations":3}""", txid))
        assertEquals(true, ExplorerTxConfirmationChecker.parsePresence("""{"hash":"$txid"}""", txid))
    }

    @Test
    fun `generic object that does not echo the txid is unknown`() {
        assertNull(ExplorerTxConfirmationChecker.parsePresence("""{"txid":"deadbeef"}""", txid))
    }

    @Test
    fun `malformed or non-object json is unknown`() {
        assertNull(ExplorerTxConfirmationChecker.parsePresence("not json", txid))
        assertNull(ExplorerTxConfirmationChecker.parsePresence("[1,2,3]", txid))
        assertNull(ExplorerTxConfirmationChecker.parsePresence("", txid))
    }

    @Test
    fun `url policy allows https anywhere and http only for local IP literals`() {
        assertEquals(true, ExplorerTxConfirmationChecker.isAllowedExplorerUrl("https://api.blockchair.com/x/$txid"))
        assertEquals(false, ExplorerTxConfirmationChecker.isAllowedExplorerUrl("http://api.blockchair.com/x/$txid"))
        assertEquals(true, ExplorerTxConfirmationChecker.isAllowedExplorerUrl("http://localhost:3001/tx/$txid"))
        assertEquals(true, ExplorerTxConfirmationChecker.isAllowedExplorerUrl("http://127.0.0.1/tx/$txid"))
        assertEquals(true, ExplorerTxConfirmationChecker.isAllowedExplorerUrl("http://192.168.1.50/tx/$txid"))
        assertEquals(true, ExplorerTxConfirmationChecker.isAllowedExplorerUrl("http://10.0.0.24/tx/$txid"))
        assertEquals(true, ExplorerTxConfirmationChecker.isAllowedExplorerUrl("http://172.16.0.5/tx/$txid"))
        assertEquals(false, ExplorerTxConfirmationChecker.isAllowedExplorerUrl("ftp://example.com/$txid"))
        assertEquals(false, ExplorerTxConfirmationChecker.isAllowedExplorerUrl("not a url"))
    }

    @Test
    fun `url policy rejects routable hostnames that merely look like private ranges (no prefix matching)`() {
        // The prefix-match bug: these are public, publicly-resolvable hosts, not local IPs.
        assertEquals(false, ExplorerTxConfirmationChecker.isAllowedExplorerUrl("http://10.attacker.com/tx/$txid"))
        assertEquals(false, ExplorerTxConfirmationChecker.isAllowedExplorerUrl("http://192.168.attacker.com/tx/$txid"))
        assertEquals(false, ExplorerTxConfirmationChecker.isAllowedExplorerUrl("http://172.16.evil.com/tx/$txid"))
        assertEquals(false, ExplorerTxConfirmationChecker.isAllowedExplorerUrl("http://node.local/tx/$txid"))
        // Out-of-range octets are not a valid private IPv4 literal.
        assertEquals(false, ExplorerTxConfirmationChecker.isAllowedExplorerUrl("http://10.0.0.999/tx/$txid"))
        assertEquals(false, ExplorerTxConfirmationChecker.isAllowedExplorerUrl("http://172.15.0.1/tx/$txid"))
        assertEquals(false, ExplorerTxConfirmationChecker.isAllowedExplorerUrl("http://172.32.0.1/tx/$txid"))
    }

    @Test
    fun `default mainnet template targets blockchair and carries the txid placeholder`() {
        val template = ExplorerTxConfirmationChecker.DEFAULT_MAINNET_URL_TEMPLATE
        assertEquals(true, template.contains(ExplorerTxConfirmationChecker.TXID_PLACEHOLDER))
        assertEquals(true, template.startsWith("https://"))
    }
}
