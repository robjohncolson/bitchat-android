package com.bitchat.android.features.dogecoin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guardrail: the one-tap dogecoin.conf help snippet must never suggest exposing RPC beyond the node
 * machine (no wildcard bind, no RFC1918 allowlist ranges) — loopback only, tunnel described in comments.
 */
class DogecoinNodeConfSnippetTest {

    @Test
    fun `snippet binds RPC to loopback only for every network`() {
        DogecoinNetwork.values().forEach { network ->
            val snippet = dogecoinConfSnippet(network, "user", "pass")
            val directives = snippet.lines().filterNot { it.trimStart().startsWith("#") }

            assertTrue("$network must bind loopback", directives.contains("rpcbind=127.0.0.1"))
            assertTrue("$network must allow loopback only", directives.contains("rpcallowip=127.0.0.1"))
            assertTrue("$network must set the network rpcport", directives.contains("rpcport=${network.rpcPort}"))

            val flattened = directives.joinToString("\n")
            assertFalse("$network must never bind all interfaces", flattened.contains("0.0.0.0"))
            assertFalse("$network must never allowlist 10/8", flattened.contains("10.0.0.0/8"))
            assertFalse("$network must never allowlist 172.16/12", flattened.contains("172.16.0.0/12"))
            assertFalse("$network must never allowlist 192.168/16", flattened.contains("192.168.0.0/16"))
        }
    }

    @Test
    fun `snippet keeps network selector, credentials, and fallbacks`() {
        val testnet = dogecoinConfSnippet(DogecoinNetwork.TESTNET, "alice", "s3cret")
        assertTrue(testnet.contains("testnet=1"))
        assertTrue(testnet.contains("server=1"))
        assertTrue(testnet.contains("rpcuser=alice"))
        assertTrue(testnet.contains("rpcpassword=s3cret"))

        val mainnet = dogecoinConfSnippet(DogecoinNetwork.MAINNET, "", "")
        assertFalse(mainnet.contains("testnet=1"))
        assertFalse(mainnet.contains("regtest=1"))
        assertTrue(mainnet.contains("rpcuser=bitchat"))
        assertTrue(mainnet.contains("rpcpassword=choose-a-long-password"))

        val regtest = dogecoinConfSnippet(DogecoinNetwork.REGTEST, "a b", "p w")
        assertTrue(regtest.contains("regtest=1"))
        // whitespace in credentials is collapsed so the conf line stays a single token
        assertTrue(regtest.contains("rpcuser=a-b"))
        assertTrue(regtest.contains("rpcpassword=p-w"))
    }

    @Test
    fun `tunnel guidance is present and LAN exposure is comment-only expert guidance off mainnet`() {
        val testnet = dogecoinConfSnippet(DogecoinNetwork.TESTNET, "", "")
        assertTrue(testnet.contains("tailscale serve"))
        assertTrue(testnet.lines().any { it.startsWith("#") && it.contains("LAN") })

        // Mainnet gets no LAN-exposure suggestion at all, not even as a comment.
        val mainnet = dogecoinConfSnippet(DogecoinNetwork.MAINNET, "", "")
        assertFalse(mainnet.lines().any { it.contains("Expert alternative") })
    }
}
