package com.bitchat.android.features.dogecoin

import com.bitchat.android.ui.DogecoinUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DogepaidReceiptTest {
    private val txid = "ab".repeat(32)
    private val requestRef = "0123456789abcdef"

    @Test
    fun `encode from koinu is canonical and round trips`() {
        val address = addressFor(DogecoinNetwork.TESTNET)

        val encoded = DogepaidReceipt.encode(
            network = DogecoinNetwork.TESTNET,
            txid = txid,
            amountKoinu = 123_456_789L,
            toAddress = address,
            requestRef = requestRef
        )

        assertEquals(
            "dogepaid:testnet:$txid?v=1&amount=1.23456789&to=$address&req=$requestRef",
            encoded
        )
        assertEquals(
            DogepaidReceipt(
                network = DogecoinNetwork.TESTNET,
                txid = txid,
                amountKoinu = 123_456_789L,
                toAddress = address,
                requestRef = requestRef
            ),
            DogepaidReceipt.parse("  $encoded\n")
        )
    }

    @Test
    fun `parser accepts each network only with a matching address`() {
        DogecoinNetwork.values().forEach { network ->
            val address = addressFor(network)
            val encoded = DogepaidReceipt.encode(network, txid, 100_000_000L, address)

            assertEquals(network, DogepaidReceipt.parse(encoded)?.network)
            assertNull(
                DogepaidReceipt.parse(
                    "dogepaid:${differentNetwork(network).id}:$txid?v=1&amount=1&to=$address"
                )
            )
        }
    }

    @Test
    fun `parser is whole-message only and bounded`() {
        val encoded = validReceipt()

        assertNull(DogepaidReceipt.parse("receipt $encoded"))
        assertNull(DogepaidReceipt.parse("$encoded thanks"))
        assertNull(DogepaidReceipt.parse(encoded + " ".repeat(257)))
        assertNull(DogepaidReceipt.parse("dogepaid:testnet:$txid?" + "x".repeat(257)))
    }

    @Test
    fun `scheme network and txid are exact lowercase tokens`() {
        val address = addressFor(DogecoinNetwork.TESTNET)
        val canonical = "dogepaid:testnet:$txid?v=1&amount=1&to=$address"

        assertNull(DogepaidReceipt.parse(canonical.replaceFirst("dogepaid:", "DOGEPAID:")))
        assertNull(DogepaidReceipt.parse(canonical.replaceFirst("testnet", "TESTNET")))
        assertNull(DogepaidReceipt.parse(canonical.replaceFirst(txid, txid.uppercase())))
        assertNull(DogepaidReceipt.parse(canonical.replace(txid, txid.dropLast(1))))
        assertNull(DogepaidReceipt.parse(canonical.replace("testnet:$txid", "testnet:extra:$txid")))
    }

    @Test
    fun `mandatory fields and version fail closed`() {
        val address = addressFor(DogecoinNetwork.TESTNET)
        val base = "dogepaid:testnet:$txid?"

        assertNull(DogepaidReceipt.parse(base + "amount=1&to=$address"))
        assertNull(DogepaidReceipt.parse(base + "v=2&amount=1&to=$address"))
        assertNull(DogepaidReceipt.parse(base + "v=1&to=$address"))
        assertNull(DogepaidReceipt.parse(base + "v=1&amount=1"))
        assertNull(DogepaidReceipt.parse(base + "v=1&amount=0&to=$address"))
        assertNull(DogepaidReceipt.parse(base + "v=1&amount=1.000000001&to=$address"))
        assertNull(DogepaidReceipt.parse(base + "v=1&amount=1,5&to=$address"))
    }

    @Test
    fun `request reference is optional strict lowercase hex`() {
        val address = addressFor(DogecoinNetwork.TESTNET)
        val base = "dogepaid:testnet:$txid?v=1&amount=1&to=$address"

        assertNull(DogepaidReceipt.parse("$base&req=0123456789abcde"))
        assertNull(DogepaidReceipt.parse("$base&req=0123456789ABCDEf"))
        assertNull(DogepaidReceipt.parse("$base&req-extra=0123456789abcdef"))
        assertEquals(requestRef, DogepaidReceipt.parse("$base&req=$requestRef")?.requestRef)
    }

    @Test
    fun `query rejects duplicates malformed fields and encoded text`() {
        val address = addressFor(DogecoinNetwork.TESTNET)
        val base = "dogepaid:testnet:$txid?"

        listOf(
            "v=1&v=1&amount=1&to=$address",
            "v=1&amount=1&amount=1&to=$address",
            "v=1&amount=1&to=$address&future=x&future=y",
            "v=1&&amount=1&to=$address",
            "v=1&amount=1&to=$address&",
            "v=1&amount&to=$address",
            "v=1&amount==1&to=$address",
            "v=1&amount=1&to=",
            "V=1&amount=1&to=$address",
            "v=1&amount=%31&to=$address",
            "v=1&amount=1&to=$address&future=evil.com",
            "v=1&amount=1&to=$address&label=coffee",
            "v=1&amount=1&to=$address&message=thanks",
            "v=1&amount=1&to=$address&status=confirmed"
        ).forEach { query -> assertNull(query, DogepaidReceipt.parse(base + query)) }
    }

    @Test
    fun `unknown token parameters are ignored for forward compatibility`() {
        val encoded = validReceipt() + "&future=alpha-1_beta~two"

        assertEquals(100_000_000L, DogepaidReceipt.parse(encoded)?.amountKoinu)
    }

    @Test
    fun `constructor and encoder reject invalid claims`() {
        val address = addressFor(DogecoinNetwork.TESTNET)

        assertThrows(IllegalArgumentException::class.java) {
            DogepaidReceipt(DogecoinNetwork.TESTNET, txid.uppercase(), 1L, address)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DogepaidReceipt(DogecoinNetwork.TESTNET, txid, 0L, address)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DogepaidReceipt(DogecoinNetwork.MAINNET, txid, 1L, address)
        }
    }

    @Test
    fun `reqRef hashes exact whole payment URI bytes without reserialization`() {
        val uri = "dogecoin:D8Bz2qkQy6VnY9uQkgQH7M7q6XkVQp7sWa" +
            "?amount=1.25&label=coffee%20fund&message=thanks%21"

        assertEquals("5652458ca35a796f", DogepaidReceipt.reqRef(uri))
        assertEquals("5652458ca35a796f", DogepaidReceipt.reqRef(" \n$uri\t"))
        assertNull(DogepaidReceipt.reqRef("please pay $uri"))
        assertNull(DogepaidReceipt.reqRef("$uri thanks"))
        assertTrue(DogepaidReceipt.reqRef(uri)!!.matches(Regex("^[0-9a-f]{16}$")))
    }

    @Test
    fun `receipt never enters payment tap paths`() {
        val encoded = validReceipt()

        assertTrue(DogecoinUri.findPaymentUris(encoded).isEmpty())
        assertNull(DogecoinUri.wholeMessagePaymentUri(encoded))
    }

    private fun validReceipt(): String {
        val address = addressFor(DogecoinNetwork.TESTNET)
        return "dogepaid:testnet:$txid?v=1&amount=1&to=$address"
    }

    private fun addressFor(network: DogecoinNetwork): String =
        DogecoinBase58.encodeChecked(network.p2pkhAddressHeader, ByteArray(20) { (it + 1).toByte() })

    private fun differentNetwork(network: DogecoinNetwork): DogecoinNetwork = when (network) {
        DogecoinNetwork.MAINNET -> DogecoinNetwork.TESTNET
        DogecoinNetwork.TESTNET -> DogecoinNetwork.MAINNET
        DogecoinNetwork.REGTEST -> DogecoinNetwork.MAINNET
    }
}
