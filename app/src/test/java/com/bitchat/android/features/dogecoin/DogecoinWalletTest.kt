package com.bitchat.android.features.dogecoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DogecoinWalletTest {
    @Test
    fun `generated wallet defaults to valid mainnet address and compressed WIF`() {
        val wallet = DogecoinKeyGenerator.generate()

        assertEquals(DogecoinNetwork.MAINNET, wallet.network)
        assertTrue(wallet.isCompressed)
        assertTrue(DogecoinAddress.isValidAddress(wallet.address, DogecoinNetwork.MAINNET))
        assertFalse(DogecoinAddress.isValidAddress(wallet.address, DogecoinNetwork.TESTNET))

        val decodedWif = DogecoinBase58.decodeChecked(wallet.wif)
        assertEquals(DogecoinNetwork.MAINNET.wifPrivateKeyHeader, decodedWif[0].toInt() and 0xff)
        assertEquals(34, decodedWif.size)
        assertEquals(0x01, decodedWif.last().toInt() and 0xff)
    }

    @Test
    fun `generated wallet can create valid testnet address and compressed WIF`() {
        val wallet = DogecoinKeyGenerator.generate(DogecoinNetwork.TESTNET)

        assertEquals(DogecoinNetwork.TESTNET, wallet.network)
        assertTrue(wallet.isCompressed)
        assertTrue(DogecoinAddress.isValidAddress(wallet.address, DogecoinNetwork.TESTNET))
        assertFalse(DogecoinAddress.isValidAddress(wallet.address, DogecoinNetwork.MAINNET))

        val decodedWif = DogecoinBase58.decodeChecked(wallet.wif)
        assertEquals(DogecoinNetwork.TESTNET.wifPrivateKeyHeader, decodedWif[0].toInt() and 0xff)
        assertEquals(34, decodedWif.size)
        assertEquals(0x01, decodedWif.last().toInt() and 0xff)
    }

    @Test
    fun `known private key is deterministic`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val sameWallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )

        assertEquals(wallet.address, sameWallet.address)
        assertEquals(wallet.wif, sameWallet.wif)
        assertTrue(DogecoinAddress.isValidAddress(wallet.address, DogecoinNetwork.MAINNET))
    }

    @Test
    fun `wif import restores mainnet wallet`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET
        )

        val imported = DogecoinKeyGenerator.fromWif(wallet.wif, expectedNetwork = DogecoinNetwork.MAINNET)

        assertEquals(wallet.privateKeyHex, imported.privateKeyHex)
        assertEquals(wallet.publicKeyHex, imported.publicKeyHex)
        assertEquals(wallet.address, imported.address)
        assertEquals(DogecoinNetwork.MAINNET, imported.network)
        assertTrue(imported.isCompressed)
    }

    @Test
    fun `wif import restores testnet wallet`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.TESTNET
        )

        val imported = DogecoinKeyGenerator.fromWif(wallet.wif, expectedNetwork = DogecoinNetwork.TESTNET)

        assertEquals(wallet.privateKeyHex, imported.privateKeyHex)
        assertEquals(wallet.address, imported.address)
        assertEquals(DogecoinNetwork.TESTNET, imported.network)
        assertTrue(imported.isCompressed)
    }

    @Test
    fun `wif import restores uncompressed mainnet wallet`() {
        val privateKey = "0000000000000000000000000000000000000000000000000000000000000001"
        val compressedWallet = DogecoinKeyGenerator.fromPrivateKeyHex(privateKey, DogecoinNetwork.MAINNET)
        val uncompressedWallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            privateKey,
            DogecoinNetwork.MAINNET,
            compressed = false
        )

        val imported = DogecoinKeyGenerator.fromWif(
            uncompressedWallet.wif,
            expectedNetwork = DogecoinNetwork.MAINNET
        )
        val decodedWif = DogecoinBase58.decodeChecked(imported.wif)

        assertFalse(imported.isCompressed)
        assertEquals(33, decodedWif.size)
        assertEquals(uncompressedWallet.privateKeyHex, imported.privateKeyHex)
        assertEquals(uncompressedWallet.publicKeyHex, imported.publicKeyHex)
        assertEquals(uncompressedWallet.address, imported.address)
        assertEquals(uncompressedWallet.wif, imported.wif)
        assertEquals(DogecoinNetwork.MAINNET, imported.network)
        assertTrue(imported.publicKeyHex.startsWith("04"))
        assertEquals(130, imported.publicKeyHex.length)
        assertTrue(DogecoinAddress.isValidAddress(imported.address, DogecoinNetwork.MAINNET))
        assertNotEquals(compressedWallet.address, imported.address)
    }

    @Test
    fun `wif import restores uncompressed testnet wallet`() {
        val privateKey = "0000000000000000000000000000000000000000000000000000000000000001"
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            privateKey,
            DogecoinNetwork.TESTNET,
            compressed = false
        )

        val imported = DogecoinKeyGenerator.fromWif(wallet.wif, expectedNetwork = DogecoinNetwork.TESTNET)
        val decodedWif = DogecoinBase58.decodeChecked(imported.wif)

        assertFalse(imported.isCompressed)
        assertEquals(33, decodedWif.size)
        assertEquals(wallet.privateKeyHex, imported.privateKeyHex)
        assertEquals(wallet.address, imported.address)
        assertEquals(DogecoinNetwork.TESTNET, imported.network)
        assertTrue(DogecoinAddress.isValidAddress(imported.address, DogecoinNetwork.TESTNET))
    }

    @Test
    fun `wif copy state only matches the recorded wallet address`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET
        )
        val otherWallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002",
            DogecoinNetwork.MAINNET
        )

        val copiedState = DogecoinWifCopyState(wallet.address, copiedAtMillis = 1_700_000_000_000L)
        val missingState = DogecoinWifCopyState(wallet.address, copiedAtMillis = 0L)

        assertTrue(copiedState.matches(wallet))
        assertFalse(copiedState.matches(otherWallet))
        assertFalse(missingState.matches(wallet))
    }

    @Test
    fun `wif import rejects wrong selected network`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.TESTNET
        )

        try {
            DogecoinKeyGenerator.fromWif(wallet.wif, expectedNetwork = DogecoinNetwork.MAINNET)
            fail("Expected wrong-network WIF rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("testnet"))
        }
    }

    @Test
    fun `payment uri includes amount label and message`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )

        val uri = DogecoinProtocol.createPaymentUri(
            network = DogecoinNetwork.MAINNET,
            address = wallet.address,
            amount = "12.50000001",
            label = "bitchat test",
            message = "mesh meetup"
        )

        assertEquals(
            "dogecoin:${wallet.address}?amount=12.50000001&label=bitchat%20test&message=mesh%20meetup",
            uri
        )
    }

    @Test
    fun `payment request parser detects mainnet amount label and message`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET
        )

        val request = DogecoinPaymentRequest.parse(
            "dogecoin:${wallet.address}?amount=3.25&label=coffee%20tip&message=thanks%20for%20relay"
        )

        requireNotNull(request)
        assertEquals(DogecoinNetwork.MAINNET, request.network)
        assertEquals(wallet.address, request.address)
        assertEquals("3.25", request.amount)
        assertEquals("coffee tip", request.label)
        assertEquals("thanks for relay", request.message)
    }

    @Test
    fun `payment request parser accepts external uri casing and whitespace`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET
        )

        val request = DogecoinPaymentRequest.parse(
            "  DOGECOIN:${wallet.address}?AMOUNT=3.25&LABEL=coffee+tip&MESSAGE=mesh+relay  "
        )

        requireNotNull(request)
        assertEquals(DogecoinNetwork.MAINNET, request.network)
        assertEquals(wallet.address, request.address)
        assertEquals("3.25", request.amount)
        assertEquals("coffee tip", request.label)
        assertEquals("mesh relay", request.message)
    }

    @Test
    fun `payment request parser ignores optional unknown parameters`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET
        )

        val request = DogecoinPaymentRequest.parse(
            "dogecoin:${wallet.address}?amount=3.25&memo=wallet-note&time=1700000000&label=coffee"
        )

        requireNotNull(request)
        assertEquals(DogecoinNetwork.MAINNET, request.network)
        assertEquals(wallet.address, request.address)
        assertEquals("3.25", request.amount)
        assertEquals("coffee", request.label)
        assertNull(request.message)
    }

    @Test
    fun `payment request parser accepts authority style dogecoin uri`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET
        )

        val request = DogecoinPaymentRequest.parse(
            "dogecoin://${wallet.address}?amount=3.25&label=coffee"
        )

        requireNotNull(request)
        assertEquals(DogecoinNetwork.MAINNET, request.network)
        assertEquals(wallet.address, request.address)
        assertEquals("3.25", request.amount)
        assertEquals("coffee", request.label)
    }

    @Test
    fun `payment request parser detects testnet request network`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.TESTNET
        )

        val request = DogecoinPaymentRequest.parse("dogecoin:${wallet.address}?amount=1")

        requireNotNull(request)
        assertEquals(DogecoinNetwork.TESTNET, request.network)
        assertEquals(wallet.address, request.address)
        assertEquals("1", request.amount)
    }

    @Test
    fun `payment request parser detects regtest request network`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.REGTEST
        )

        val request = DogecoinPaymentRequest.parse("dogecoin:${wallet.address}?amount=1")

        requireNotNull(request)
        assertEquals(DogecoinNetwork.REGTEST, request.network)
        assertEquals(wallet.address, request.address)
        assertEquals("1", request.amount)
    }

    @Test
    fun `payment request parser accepts mainnet p2sh requests`() {
        val address = p2shAddress(DogecoinNetwork.MAINNET)

        val request = DogecoinPaymentRequest.parse("dogecoin:$address?amount=1")

        requireNotNull(request)
        assertEquals(DogecoinNetwork.MAINNET, request.network)
        assertEquals(address, request.address)
        assertEquals("1", request.amount)
    }

    @Test
    fun `payment request parser accepts bare mainnet address for scanned QR`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET
        )

        val request = DogecoinPaymentRequest.parseAddressOrUri(" ${wallet.address} ")

        requireNotNull(request)
        assertEquals(DogecoinNetwork.MAINNET, request.network)
        assertEquals(wallet.address, request.address)
        assertNull(request.amount)
        assertEquals("dogecoin:${wallet.address}", request.uri)
    }

    @Test
    fun `payment request parser accepts bare testnet address for scanned QR`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.TESTNET
        )

        val request = DogecoinPaymentRequest.parseAddressOrUri(wallet.address)

        requireNotNull(request)
        assertEquals(DogecoinNetwork.TESTNET, request.network)
        assertEquals(wallet.address, request.address)
        assertNull(request.amount)
        assertEquals("dogecoin:${wallet.address}", request.uri)
    }

    @Test
    fun `payment request parser accepts authority style uri for scanned QR`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET
        )

        val request = DogecoinPaymentRequest.parseAddressOrUri("dogecoin://${wallet.address}")

        requireNotNull(request)
        assertEquals(DogecoinNetwork.MAINNET, request.network)
        assertEquals(wallet.address, request.address)
        assertNull(request.amount)
    }

    @Test
    fun `payment request parser accepts embedded dogecoin uri for pasted request text`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET
        )

        val request = DogecoinPaymentRequest.parseAddressOrUri(
            "please pay (dogecoin:${wallet.address}?amount=2.5&label=coffee), thanks"
        )

        requireNotNull(request)
        assertEquals(DogecoinNetwork.MAINNET, request.network)
        assertEquals(wallet.address, request.address)
        assertEquals("2.5", request.amount)
        assertEquals("coffee", request.label)
    }

    @Test
    fun `payment request parser accepts network labeled shared dogecoin request text`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET
        )

        val request = DogecoinPaymentRequest.parseAddressOrUri(
            "Dogecoin mainnet payment request:\n" +
                "dogecoin:${wallet.address}?amount=2.5&label=coffee"
        )

        requireNotNull(request)
        assertEquals(DogecoinNetwork.MAINNET, request.network)
        assertEquals(wallet.address, request.address)
        assertEquals("2.5", request.amount)
        assertEquals("coffee", request.label)
    }

    @Test
    fun `payment request parser accepts embedded bare mainnet address for pasted request text`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET
        )

        val request = DogecoinPaymentRequest.parseAddressOrUri("send DOGE to ${wallet.address}, please")

        requireNotNull(request)
        assertEquals(DogecoinNetwork.MAINNET, request.network)
        assertEquals(wallet.address, request.address)
        assertNull(request.amount)
        assertEquals("dogecoin:${wallet.address}", request.uri)
    }

    @Test
    fun `payment request parser accepts embedded bare testnet address for pasted request text`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.TESTNET
        )

        val request = DogecoinPaymentRequest.parseAddressOrUri("test funds go to (${wallet.address})")

        requireNotNull(request)
        assertEquals(DogecoinNetwork.TESTNET, request.network)
        assertEquals(wallet.address, request.address)
        assertNull(request.amount)
        assertEquals("dogecoin:${wallet.address}", request.uri)
    }

    @Test
    fun `payment request parser rejects embedded bare address with invalid checksum`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET
        )
        val invalidAddress = wallet.address.dropLast(1) + if (wallet.address.last() == '1') "2" else "1"

        assertNull(DogecoinPaymentRequest.parseAddressOrUri("send DOGE to $invalidAddress"))
    }

    @Test
    fun `payment request parser ignores embedded dogecoin scheme inside another token`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET
        )

        assertNull(
            DogecoinPaymentRequest.parseAddressOrUri(
                "notadogecoin:${wallet.address}?amount=2.5"
            )
        )
    }

    @Test
    fun `payment request parser rejects non dogecoin scanned QR`() {
        assertNull(DogecoinPaymentRequest.parseAddressOrUri("bitcoin:bc1qexample"))
        assertNull(DogecoinPaymentRequest.parseAddressOrUri("not a dogecoin payment request"))
    }

    @Test
    fun `payment request parser rejects invalid amount`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET
        )

        assertNull(DogecoinPaymentRequest.parse("dogecoin:${wallet.address}?amount=0"))
        assertNull(DogecoinPaymentRequest.parse("dogecoin:${wallet.address}?amount=1.123456789"))
        assertNull(DogecoinPaymentRequest.parse("dogecoin:${wallet.address}?amount=92233720368.54775808"))
    }

    @Test
    fun `payment request parser rejects unsupported required parameters`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET
        )

        assertNull(DogecoinPaymentRequest.parse("dogecoin:${wallet.address}?amount=1&req-memo=required"))
        assertNull(DogecoinPaymentRequest.parse("dogecoin:${wallet.address}?REQ-SOMETHING=required"))
    }

    @Test
    fun `payment request parser rejects malformed query encoding`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET
        )

        assertNull(DogecoinPaymentRequest.parse("dogecoin:${wallet.address}?label=%"))
    }

    @Test
    fun `amount validation rejects zero negative and over precision values`() {
        assertFalse(DogecoinAmount.isValidAmount("0"))
        assertFalse(DogecoinAmount.isValidAmount("-1"))
        assertFalse(DogecoinAmount.isValidAmount("1.123456789"))
        assertTrue(DogecoinAmount.isValidAmount("1.12345678"))
        assertEquals(112345678, DogecoinAmount.toKoinu("1.12345678"))
        assertEquals("1.12345678", DogecoinAmount.formatKoinu(112345678))
    }

    @Test
    fun `amount validation rejects values that cannot fit koinu long`() {
        assertTrue(DogecoinAmount.isValidAmount("92233720368.54775807"))
        assertEquals(Long.MAX_VALUE, DogecoinAmount.toKoinu("92233720368.54775807"))
        assertFalse(DogecoinAmount.isValidAmount("92233720368.54775808"))
        assertFalse(DogecoinAmount.isValidAmount("999999999999999999999999999999999999.99999999"))
    }

    @Test
    fun `fee rate validation requires dogecoin minimum fee per kb`() {
        assertFalse(DogecoinAmount.isValidFeePerKb("0"))
        assertFalse(DogecoinAmount.isValidFeePerKb("0.00999999"))
        assertTrue(DogecoinAmount.isValidFeePerKb("0.01"))
        assertTrue(DogecoinAmount.isValidFeePerKb("1"))
        assertFalse(DogecoinAmount.isValidFeePerKb("1.123456789"))
    }

    @Test
    fun `standard output validation requires dogecoin dust limit`() {
        assertFalse(DogecoinAmount.isStandardOutputAmount("0.00999999"))
        assertTrue(DogecoinAmount.isStandardOutputAmount("0.01"))
        assertTrue(DogecoinAmount.isStandardOutputAmount("1"))
        assertFalse(DogecoinAmount.isStandardOutputAmount("1.123456789"))
    }

    @Test
    fun `effective standard output uses node soft dust limit when higher`() {
        assertEquals(
            DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU,
            dogecoinEffectiveStandardOutputKoinu(null)
        )
        assertEquals(
            DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU,
            dogecoinEffectiveStandardOutputKoinu(DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU / 2)
        )
        assertEquals(
            3_000_000L,
            dogecoinEffectiveStandardOutputKoinu(3_000_000L)
        )
    }

    @Test
    fun `payment uri rejects unrepresentable amount`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )

        try {
            DogecoinProtocol.createPaymentUri(
                network = DogecoinNetwork.MAINNET,
                address = wallet.address,
                amount = "92233720368.54775808"
            )
            fail("Expected unrepresentable payment URI amount rejection")
        } catch (e: IllegalArgumentException) {
            assertEquals("Invalid Dogecoin amount", e.message)
        }
    }

    @Test
    fun `payment uri rejects amount below dogecoin standard output minimum`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )

        try {
            DogecoinProtocol.createPaymentUri(
                network = DogecoinNetwork.MAINNET,
                address = wallet.address,
                amount = "0.00999999"
            )
            fail("Expected dust payment URI amount rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("at least 0.01 DOGE"))
        }
    }

    @Test
    fun `mainnet and testnet dogecoin addresses are network separated`() {
        val privateKey = "0000000000000000000000000000000000000000000000000000000000000001"
        val mainnetWallet = DogecoinKeyGenerator.fromPrivateKeyHex(privateKey, DogecoinNetwork.MAINNET)
        val testnetWallet = DogecoinKeyGenerator.fromPrivateKeyHex(privateKey, DogecoinNetwork.TESTNET)

        assertTrue(DogecoinAddress.isValidAddress(mainnetWallet.address, DogecoinNetwork.MAINNET))
        assertFalse(DogecoinAddress.isValidAddress(mainnetWallet.address, DogecoinNetwork.TESTNET))
        assertTrue(DogecoinAddress.isValidAddress(testnetWallet.address, DogecoinNetwork.TESTNET))
        assertFalse(DogecoinAddress.isValidAddress(testnetWallet.address, DogecoinNetwork.MAINNET))
        assertNotEquals(mainnetWallet.address, testnetWallet.address)
    }

    @Test
    fun `regtest dogecoin uses its own version bytes and round-trips through wif`() {
        assertEquals(111, DogecoinNetwork.REGTEST.p2pkhAddressHeader)
        assertEquals(196, DogecoinNetwork.REGTEST.p2shAddressHeader)
        assertEquals(239, DogecoinNetwork.REGTEST.wifPrivateKeyHeader)
        assertEquals("regtest", DogecoinNetwork.REGTEST.chainName)

        val privateKey = "0000000000000000000000000000000000000000000000000000000000000001"
        val regtest = DogecoinKeyGenerator.fromPrivateKeyHex(privateKey, DogecoinNetwork.REGTEST)

        // Regtest address must be valid only for regtest, distinct from testnet (pubkey 111 vs 113).
        assertTrue(DogecoinAddress.isValidAddress(regtest.address, DogecoinNetwork.REGTEST))
        assertFalse(DogecoinAddress.isValidAddress(regtest.address, DogecoinNetwork.TESTNET))
        assertFalse(DogecoinAddress.isValidAddress(regtest.address, DogecoinNetwork.MAINNET))

        // The unique WIF version byte (239) makes import unambiguous.
        val decodedWif = DogecoinBase58.decodeChecked(regtest.wif)
        assertEquals(239, decodedWif[0].toInt() and 0xff)
        val imported = DogecoinKeyGenerator.fromWif(regtest.wif, expectedNetwork = DogecoinNetwork.REGTEST)
        assertEquals(regtest.address, imported.address)
        assertEquals(DogecoinNetwork.REGTEST, imported.network)

        // A regtest WIF must not import as testnet despite the shared script-address prefix.
        try {
            DogecoinKeyGenerator.fromWif(regtest.wif, expectedNetwork = DogecoinNetwork.TESTNET)
            fail("Expected regtest WIF to be rejected for testnet")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("regtest"))
        }
    }

    @Test
    fun `mainnet and testnet dogecoin p2sh addresses are network separated`() {
        val mainnetAddress = p2shAddress(DogecoinNetwork.MAINNET)
        val testnetAddress = p2shAddress(DogecoinNetwork.TESTNET)

        assertEquals(DogecoinAddressType.P2SH, DogecoinAddress.addressType(mainnetAddress, DogecoinNetwork.MAINNET))
        assertEquals(DogecoinAddressType.P2SH, DogecoinAddress.addressType(testnetAddress, DogecoinNetwork.TESTNET))
        assertTrue(DogecoinAddress.isValidAddress(mainnetAddress, DogecoinNetwork.MAINNET))
        assertFalse(DogecoinAddress.isValidP2pkhAddress(mainnetAddress, DogecoinNetwork.MAINNET))
        assertFalse(DogecoinAddress.isValidAddress(mainnetAddress, DogecoinNetwork.TESTNET))
        assertTrue(DogecoinAddress.isValidAddress(testnetAddress, DogecoinNetwork.TESTNET))
        assertFalse(DogecoinAddress.isValidP2pkhAddress(testnetAddress, DogecoinNetwork.TESTNET))
        assertFalse(DogecoinAddress.isValidAddress(testnetAddress, DogecoinNetwork.MAINNET))
        assertNotEquals(mainnetAddress, testnetAddress)
    }

    @Test
    fun `node status separates matching chain from wallet readiness`() {
        val syncing = DogecoinNodeStatus(
            connected = true,
            expectedNetwork = DogecoinNetwork.MAINNET,
            chain = DogecoinNetwork.MAINNET.chainName,
            initialBlockDownload = true
        )
        val ready = syncing.copy(initialBlockDownload = false)

        assertTrue(syncing.isUsable)
        assertFalse(syncing.isReady)
        assertTrue(ready.isUsable)
        assertTrue(ready.isReady)
    }

    @Test
    fun `node status requires ready wallet rpc for wallet actions`() {
        val walletUnavailable = DogecoinNodeStatus(
            connected = true,
            expectedNetwork = DogecoinNetwork.MAINNET,
            chain = DogecoinNetwork.MAINNET.chainName,
            initialBlockDownload = false,
            walletReady = false,
            walletError = "Dogecoin Core wallet is not loaded."
        )

        assertTrue(walletUnavailable.isUsable)
        assertFalse(walletUnavailable.isReady)
        assertFalse(walletUnavailable.isReadyFor(DogecoinNetwork.MAINNET))
        assertFalse(walletUnavailable.supportsHistoricalRescanFor(DogecoinNetwork.MAINNET))
    }

    @Test
    fun `node status separates wallet readiness from broadcast relay readiness`() {
        val relayUnavailable = DogecoinNodeStatus(
            connected = true,
            expectedNetwork = DogecoinNetwork.MAINNET,
            chain = DogecoinNetwork.MAINNET.chainName,
            initialBlockDownload = false,
            walletReady = true,
            relayReady = false,
            networkActive = true,
            peerCount = 0,
            relayError = "Dogecoin Core has no connected peers for broadcast relay."
        )

        assertTrue(relayUnavailable.isReadyFor(DogecoinNetwork.MAINNET))
        assertFalse(relayUnavailable.canBroadcastFor(DogecoinNetwork.MAINNET))
        assertTrue(relayUnavailable.supportsHistoricalRescanFor(DogecoinNetwork.MAINNET))
    }

    @Test
    fun `node status requires explicit relay readiness before broadcast`() {
        val relayNotChecked = DogecoinNodeStatus(
            connected = true,
            expectedNetwork = DogecoinNetwork.MAINNET,
            chain = DogecoinNetwork.MAINNET.chainName,
            initialBlockDownload = false,
            walletReady = true
        )

        assertTrue(relayNotChecked.isReadyFor(DogecoinNetwork.MAINNET))
        assertFalse(relayNotChecked.canBroadcastFor(DogecoinNetwork.MAINNET))
    }

    @Test
    fun `node status treats pruned mainnet node as ready but not rescan capable`() {
        val prunedReady = DogecoinNodeStatus(
            connected = true,
            expectedNetwork = DogecoinNetwork.MAINNET,
            chain = DogecoinNetwork.MAINNET.chainName,
            pruned = true,
            initialBlockDownload = false
        )

        assertTrue(prunedReady.isReadyFor(DogecoinNetwork.MAINNET))
        assertFalse(prunedReady.supportsHistoricalRescanFor(DogecoinNetwork.MAINNET))
    }

    @Test
    fun `node status readiness is scoped to selected dogecoin network`() {
        val mainnetReady = DogecoinNodeStatus(
            connected = true,
            expectedNetwork = DogecoinNetwork.MAINNET,
            chain = DogecoinNetwork.MAINNET.chainName,
            initialBlockDownload = false
        )
        val testnetSyncing = DogecoinNodeStatus(
            connected = true,
            expectedNetwork = DogecoinNetwork.TESTNET,
            chain = DogecoinNetwork.TESTNET.chainName,
            initialBlockDownload = true
        )

        assertTrue(mainnetReady.isReady)
        assertTrue(mainnetReady.isReadyFor(DogecoinNetwork.MAINNET))
        assertFalse(mainnetReady.isUsableFor(DogecoinNetwork.TESTNET))
        assertFalse(mainnetReady.isReadyFor(DogecoinNetwork.TESTNET))

        assertTrue(testnetSyncing.isUsableFor(DogecoinNetwork.TESTNET))
        assertFalse(testnetSyncing.isReadyFor(DogecoinNetwork.TESTNET))
        assertFalse(testnetSyncing.isReadyFor(DogecoinNetwork.MAINNET))
    }

    @Test
    fun `stored network selection defaults first run to testnet while fromId fallback stays mainnet`() {
        assertEquals(DogecoinNetwork.MAINNET, DogecoinNetwork.DEFAULT)
        assertEquals(DogecoinNetwork.MAINNET, DogecoinNetwork.fromId(null))
        // Genuine first run (no selection AND no existing wallet) defaults to testnet.
        assertEquals(DogecoinNetwork.TESTNET, dogecoinNetworkForStoredSelection(null, hasExistingWallet = false))
        assertEquals(DogecoinNetwork.TESTNET, dogecoinNetworkForStoredSelection("", hasExistingWallet = false))
        // An existing wallet with no explicit selection keeps the historical mainnet default
        // (no silent flip to testnet on upgrade).
        assertEquals(DogecoinNetwork.MAINNET, dogecoinNetworkForStoredSelection(null, hasExistingWallet = true))
        assertEquals(DogecoinNetwork.MAINNET, dogecoinNetworkForStoredSelection("", hasExistingWallet = true))
        // An explicit stored selection always wins, regardless of wallet presence.
        assertEquals(DogecoinNetwork.MAINNET, dogecoinNetworkForStoredSelection("mainnet", hasExistingWallet = false))
        assertEquals(DogecoinNetwork.TESTNET, dogecoinNetworkForStoredSelection("testnet", hasExistingWallet = true))
        assertEquals(DogecoinNetwork.MAINNET, dogecoinNetworkForStoredSelection("unknown", hasExistingWallet = false))
    }

    @Test
    fun `rpc config keeps blank url until user enters a reachable node`() {
        val mainnetConfig = DogecoinRpcConfig(
            url = "  ",
            username = " dogeuser ",
            password = "secret",
            walletName = " main wallet "
        ).normalized(DogecoinNetwork.MAINNET)
        val testnetConfig = DogecoinRpcConfig(url = "").normalized(DogecoinNetwork.TESTNET)

        assertEquals("", mainnetConfig.url)
        assertEquals("dogeuser", mainnetConfig.username)
        assertEquals("secret", mainnetConfig.password)
        assertEquals("main wallet", mainnetConfig.walletName)
        assertEquals("", testnetConfig.url)
        assertEquals("http://10.0.2.2:22555", DogecoinNetwork.MAINNET.emulatorRpcUrl)
        assertEquals("http://10.0.2.2:44555", DogecoinNetwork.TESTNET.emulatorRpcUrl)
    }

    @Test
    fun `rpc config splits pasted dogecoin core auth token when password is blank`() {
        val config = DogecoinRpcConfig(
            url = "http://dogecoin.local:22555",
            username = " dogeuser:doge-pass ",
            password = ""
        ).normalized(DogecoinNetwork.MAINNET)

        assertEquals("dogeuser", config.username)
        assertEquals("doge-pass", config.password)
    }

    @Test
    fun `rpc config keeps explicit password when username contains colon`() {
        val config = DogecoinRpcConfig(
            url = "http://dogecoin.local:22555",
            username = "dogeuser:not-the-password",
            password = "explicit-password"
        ).normalized(DogecoinNetwork.MAINNET)

        assertEquals("dogeuser:not-the-password", config.username)
        assertEquals("explicit-password", config.password)
    }

    @Test
    fun `rpc config derives dogecoin core wallet endpoint from wallet name`() {
        val config = DogecoinRpcConfig(
            url = " http://dogecoin.local:22555 ",
            walletName = " main wallet "
        ).normalized(DogecoinNetwork.MAINNET)
        val existingWalletEndpointConfig = DogecoinRpcConfig(
            url = "http://dogecoin.local:22555/wallet/old-wallet",
            walletName = "new wallet"
        ).normalized(DogecoinNetwork.MAINNET)

        assertEquals("http://dogecoin.local:22555/wallet/main%20wallet", config.walletEndpointUrl())
        assertEquals(
            "http://dogecoin.local:22555/wallet/new%20wallet",
            existingWalletEndpointConfig.walletEndpointUrl()
        )
        assertEquals(
            "http://dogecoin.local:22555",
            DogecoinRpcConfig(url = "http://dogecoin.local:22555").normalized(DogecoinNetwork.MAINNET)
                .walletEndpointUrl()
        )
    }

    @Test
    fun `rpc config validates real node endpoints before use`() {
        assertFalse(DogecoinRpcConfig(url = "").hasValidUrl(DogecoinNetwork.MAINNET))
        assertTrue(DogecoinRpcConfig(url = " http://10.0.2.2:22555 ").hasValidUrl(DogecoinNetwork.MAINNET))
        assertTrue(DogecoinRpcConfig(url = " http://192.168.1.44:22555 ").hasValidUrl(DogecoinNetwork.MAINNET))
        assertTrue(DogecoinRpcConfig(url = "http://172.16.4.2:22555").hasValidUrl(DogecoinNetwork.MAINNET))
        assertTrue(DogecoinRpcConfig(url = "http://localhost:22555").hasValidUrl(DogecoinNetwork.MAINNET))
        assertTrue(DogecoinRpcConfig(url = "http://dogecoin-node:22555").hasValidUrl(DogecoinNetwork.MAINNET))
        assertTrue(DogecoinRpcConfig(url = "http://dogecoin.local:22555").hasValidUrl(DogecoinNetwork.MAINNET))
        assertTrue(DogecoinRpcConfig(url = "https://dogecoin.example.com:22555").hasValidUrl(DogecoinNetwork.MAINNET))

        assertFalse(DogecoinRpcConfig(url = "http://dogecoin.example.com:22555").hasValidUrl(DogecoinNetwork.MAINNET))
        assertFalse(DogecoinRpcConfig(url = "http://172.32.4.2:22555").hasValidUrl(DogecoinNetwork.MAINNET))
        assertFalse(DogecoinRpcConfig(url = "dogecoin.example.com:22555").hasValidUrl(DogecoinNetwork.MAINNET))
        assertFalse(DogecoinRpcConfig(url = "ftp://dogecoin.example.com:22555").hasValidUrl(DogecoinNetwork.MAINNET))
        assertFalse(DogecoinRpcConfig(url = "http://").hasValidUrl(DogecoinNetwork.MAINNET))
    }

    @Test
    fun `signed transaction spends mainnet p2pkh utxo with change`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val txid = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val utxo = DogecoinUtxo(
            txid = txid,
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )

        val transaction = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = "2.0"
        )

        assertEquals(10L * DogecoinProtocol.KOINU_PER_DOGE, transaction.inputTotalKoinu)
        assertEquals(2L * DogecoinProtocol.KOINU_PER_DOGE, transaction.sendAmountKoinu)
        assertEquals(DogecoinNetwork.MAINNET, transaction.network)
        assertEquals(recipient.address, transaction.recipientAddress)
        assertEquals(DogecoinProtocol.DEFAULT_FEE_PER_KB_KOINU, transaction.feePerKbKoinu)
        assertEquals(DogecoinProtocol.MIN_TX_FEE_KOINU, transaction.feeKoinu)
        assertFalse(transaction.requiresHighFeeAcknowledgement())
        assertEquals(201L * 1_000_000L, transaction.totalDebitKoinu)
        assertEquals(799L * 1_000_000L, transaction.changeKoinu)
        assertEquals(wallet.address, transaction.changeAddress)
        assertEquals(transaction.txid, DogecoinTransactionBuilder.transactionId(transaction.rawTransactionHex))
        assertEquals(64, transaction.txid.length)
        assertNotEquals(txid, transaction.txid)
        assertTrue(transaction.rawTransactionHex.contains(txid.chunked(2).reversed().joinToString("")))
        assertTrue(
            transaction.rawTransactionHex.contains(
                DogecoinHex.encode(DogecoinAddress.p2pkhScript(recipient.address, DogecoinNetwork.MAINNET))
            )
        )
        assertTrue(
            transaction.rawTransactionHex.contains(
                DogecoinHex.encode(DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET))
            )
        )
    }

    @Test
    fun `signed transaction sweeps change below node dust floor into fee`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val utxo = DogecoinUtxo(
            txid = "010202030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = 5L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )

        val transaction = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = "3.0",
            minimumOutputKoinu = 3L * DogecoinProtocol.KOINU_PER_DOGE
        )

        assertEquals(5L * DogecoinProtocol.KOINU_PER_DOGE, transaction.inputTotalKoinu)
        assertEquals(3L * DogecoinProtocol.KOINU_PER_DOGE, transaction.sendAmountKoinu)
        assertEquals(2L * DogecoinProtocol.KOINU_PER_DOGE, transaction.feeKoinu)
        assertEquals(0L, transaction.changeKoinu)
        assertNull(transaction.changeAddress)
    }

    @Test
    fun `signed transaction rejects output below node dust floor`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val utxo = DogecoinUtxo(
            txid = "020202030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = 5L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )

        try {
            DogecoinTransactionBuilder.createSignedTransaction(
                wallet = wallet,
                utxos = listOf(utxo),
                recipientAddress = recipient.address,
                amount = "2.99999999",
                minimumOutputKoinu = 3L * DogecoinProtocol.KOINU_PER_DOGE
            )
            fail("Expected node dust floor send amount rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("at least 3 DOGE"))
        }
    }

    @Test
    fun `signed transaction flags high fee relative to send amount`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val utxo = DogecoinUtxo(
            txid = "121202030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )

        val transaction = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = "0.1"
        )
        val absoluteHighFee = transaction.copy(
            feeKoinu = DogecoinProtocol.HIGH_FEE_ABSOLUTE_KOINU
        )

        assertEquals(DogecoinProtocol.MIN_TX_FEE_KOINU, transaction.feeKoinu)
        assertTrue(transaction.requiresHighFeeAcknowledgement())
        assertTrue(absoluteHighFee.requiresHighFeeAcknowledgement())
    }

    @Test
    fun `signed transaction review arithmetic does not wrap for extreme copied values`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val utxo = DogecoinUtxo(
            txid = "121302030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )
        val transaction = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = "0.1"
        )

        val extremeTransaction = transaction.copy(
            sendAmountKoinu = Long.MAX_VALUE,
            feeKoinu = 1L
        )

        assertEquals(Long.MAX_VALUE, extremeTransaction.totalDebitKoinu)
        assertFalse(extremeTransaction.requiresHighFeeAcknowledgement())
    }

    @Test
    fun `signed transaction rejects negative copied review amounts`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val utxo = DogecoinUtxo(
            txid = "121402030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )
        val transaction = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = "0.1"
        )

        try {
            transaction.copy(feeKoinu = -1L)
            fail("Expected negative fee rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("fee must be non-negative"))
        }
    }

    @Test
    fun `signed transaction records review time and expires after max age`() {
        val before = System.currentTimeMillis()
        val transaction = signedMainnetTransaction()
        val after = System.currentTimeMillis()

        assertTrue(transaction.createdAtMillis in before..after)
        assertFalse(transaction.isExpired(transaction.createdAtMillis + 600_000L, 600_000L))
        assertTrue(transaction.isExpired(transaction.createdAtMillis + 600_001L, 600_000L))
    }

    @Test
    fun `review send readiness mirrors the effective backend`() {
        // Built-in remains locked while unsynced; a ready node cannot unlock it unless assist changes the
        // effective backend to RPC.
        assertFalse(
            canReviewDogecoinSend(
                network = DogecoinNetwork.TESTNET,
                effectiveBackend = DogecoinBackend.SPV,
                spvSynced = false,
                nodeReady = true
            )
        )
        assertTrue(
            canReviewDogecoinSend(
                network = DogecoinNetwork.TESTNET,
                effectiveBackend = DogecoinBackend.SPV,
                spvSynced = true,
                nodeReady = false
            )
        )

        // Effective RPC covers both a persisted node backend and session-only home-node assist.
        assertTrue(
            canReviewDogecoinSend(
                network = DogecoinNetwork.TESTNET,
                effectiveBackend = DogecoinBackend.RPC,
                spvSynced = false,
                nodeReady = true
            )
        )
        assertFalse(
            canReviewDogecoinSend(
                network = DogecoinNetwork.TESTNET,
                effectiveBackend = DogecoinBackend.RPC,
                spvSynced = true,
                nodeReady = false
            )
        )

        // With no TPN profile/session type in the product yet, every generic mainnet node route is read-only.
        assertFalse(
            canReviewDogecoinSend(
                network = DogecoinNetwork.MAINNET,
                effectiveBackend = DogecoinBackend.RPC,
                spvSynced = true,
                nodeReady = true
            )
        )
        assertFalse(
            canReviewDogecoinSend(
                network = DogecoinNetwork.MAINNET,
                effectiveBackend = DogecoinBackend.EXPLORER,
                spvSynced = true,
                nodeReady = true
            )
        )
        // The existing mainnet Built-in route stays governed only by its SPV sync readiness here.
        assertTrue(
            canReviewDogecoinSend(
                network = DogecoinNetwork.MAINNET,
                effectiveBackend = DogecoinBackend.SPV,
                spvSynced = true,
                nodeReady = false
            )
        )
    }

    @Test
    fun `broadcast readiness blocks generic mainnet without cross unlocking routes`() {
        assertFalse(
            canBroadcastDogecoinSend(
                transactionNetwork = DogecoinNetwork.MAINNET,
                selectedNetwork = DogecoinNetwork.MAINNET,
                effectiveBackend = DogecoinBackend.RPC,
                spvSynced = true,
                nodeReady = true
            )
        )
        assertTrue(
            canBroadcastDogecoinSend(
                transactionNetwork = DogecoinNetwork.MAINNET,
                selectedNetwork = DogecoinNetwork.MAINNET,
                effectiveBackend = DogecoinBackend.SPV,
                spvSynced = true,
                nodeReady = false
            )
        )
        assertTrue(
            canBroadcastDogecoinSend(
                transactionNetwork = DogecoinNetwork.TESTNET,
                selectedNetwork = DogecoinNetwork.TESTNET,
                effectiveBackend = DogecoinBackend.RPC,
                spvSynced = false,
                nodeReady = true
            )
        )
        assertFalse(
            canBroadcastDogecoinSend(
                transactionNetwork = DogecoinNetwork.TESTNET,
                selectedNetwork = DogecoinNetwork.MAINNET,
                effectiveBackend = DogecoinBackend.SPV,
                spvSynced = true,
                nodeReady = true
            )
        )
        assertFalse(
            canBroadcastDogecoinSend(
                transactionNetwork = DogecoinNetwork.TESTNET,
                selectedNetwork = DogecoinNetwork.TESTNET,
                effectiveBackend = DogecoinBackend.SPV,
                spvSynced = false,
                nodeReady = true
            )
        )
    }

    @Test
    fun `confirmation progress source mirrors the effective backend`() {
        assertEquals(
            DogecoinConfirmationProgressSource.SPV,
            dogecoinConfirmationProgressSource(DogecoinBackend.SPV)
        )
        assertEquals(
            DogecoinConfirmationProgressSource.RPC,
            dogecoinConfirmationProgressSource(DogecoinBackend.RPC)
        )
        assertEquals(
            DogecoinConfirmationProgressSource.RPC,
            dogecoinConfirmationProgressSource(DogecoinBackend.EXPLORER)
        )
    }

    @Test
    fun `confirmation ring visibility preserves SPV gate but not for RPC assist`() {
        assertTrue(
            shouldShowDogecoinConfirmingRing(
                effectiveBackend = DogecoinBackend.RPC,
                hasActiveReceipt = true,
                confirmationDepth = 0,
                spvSyncedOrNearTip = false
            )
        )
        assertFalse(
            shouldShowDogecoinConfirmingRing(
                effectiveBackend = DogecoinBackend.SPV,
                hasActiveReceipt = true,
                confirmationDepth = 1,
                spvSyncedOrNearTip = false
            )
        )
        assertTrue(
            shouldShowDogecoinConfirmingRing(
                effectiveBackend = DogecoinBackend.SPV,
                hasActiveReceipt = true,
                confirmationDepth = 1,
                spvSyncedOrNearTip = true
            )
        )
        assertFalse(
            shouldShowDogecoinConfirmingRing(
                effectiveBackend = DogecoinBackend.RPC,
                hasActiveReceipt = false,
                confirmationDepth = 1,
                spvSyncedOrNearTip = false
            )
        )
        assertFalse(
            shouldShowDogecoinConfirmingRing(
                effectiveBackend = DogecoinBackend.RPC,
                hasActiveReceipt = true,
                confirmationDepth = null,
                spvSyncedOrNearTip = false
            )
        )
        assertFalse(
            shouldShowDogecoinConfirmingRing(
                effectiveBackend = DogecoinBackend.RPC,
                hasActiveReceipt = true,
                confirmationDepth = DOGECOIN_SPV_CONFIRM_TARGET,
                spvSyncedOrNearTip = false
            )
        )
    }

    @Test
    fun `signed mainnet transaction export requires mainnet acknowledgement`() {
        val transaction = signedMainnetTransaction()
        val nowMillis = transaction.createdAtMillis

        assertFalse(
            canExportOrBroadcastSignedDogecoinTransaction(
                transaction = transaction,
                nowMillis = nowMillis,
                maxAgeMillis = 600_000L,
                mainnetAcknowledged = false,
                highFeeAcknowledged = false
            )
        )
        assertTrue(
            canExportOrBroadcastSignedDogecoinTransaction(
                transaction = transaction,
                nowMillis = nowMillis,
                maxAgeMillis = 600_000L,
                mainnetAcknowledged = true,
                highFeeAcknowledged = false
            )
        )
    }

    @Test
    fun `signed testnet transaction export does not require mainnet acknowledgement`() {
        val transaction = signedMainnetTransaction().copy(network = DogecoinNetwork.TESTNET)

        assertTrue(
            canExportOrBroadcastSignedDogecoinTransaction(
                transaction = transaction,
                nowMillis = transaction.createdAtMillis,
                maxAgeMillis = 600_000L,
                mainnetAcknowledged = false,
                highFeeAcknowledged = false
            )
        )
    }

    @Test
    fun `signed high fee transaction export requires high fee acknowledgement`() {
        val transaction = signedMainnetTransaction().copy(
            feeKoinu = DogecoinProtocol.HIGH_FEE_ABSOLUTE_KOINU
        )
        val nowMillis = transaction.createdAtMillis

        assertTrue(transaction.requiresHighFeeAcknowledgement())
        assertFalse(
            canExportOrBroadcastSignedDogecoinTransaction(
                transaction = transaction,
                nowMillis = nowMillis,
                maxAgeMillis = 600_000L,
                mainnetAcknowledged = true,
                highFeeAcknowledged = false
            )
        )
        assertTrue(
            canExportOrBroadcastSignedDogecoinTransaction(
                transaction = transaction,
                nowMillis = nowMillis,
                maxAgeMillis = 600_000L,
                mainnetAcknowledged = true,
                highFeeAcknowledged = true
            )
        )
    }

    @Test
    fun `signed transaction export rejects inconsistent raw transaction id`() {
        val transaction = signedMainnetTransaction()
        val corruptedReview = transaction.copy(
            txid = "00".repeat(32)
        )
        val nowMillis = corruptedReview.createdAtMillis

        assertTrue(transaction.hasConsistentRawTransactionId())
        assertFalse(corruptedReview.hasConsistentRawTransactionId())
        assertFalse(
            canExportOrBroadcastSignedDogecoinTransaction(
                transaction = corruptedReview,
                nowMillis = nowMillis,
                maxAgeMillis = 600_000L,
                mainnetAcknowledged = true,
                highFeeAcknowledged = false
            )
        )
        assertFalse(
            canExportSignedRawDogecoinTransaction(
                transaction = corruptedReview,
                nowMillis = nowMillis,
                maxAgeMillis = 600_000L,
                mainnetAcknowledged = true,
                highFeeAcknowledged = false,
                selectedNetwork = DogecoinNetwork.MAINNET,
                nodeReady = true
            )
        )

        try {
            corruptedReview.requireConsistentRawTransactionId()
            fail("Expected inconsistent raw transaction id rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("Refresh wallet balance"))
        }
    }

    @Test
    fun `signed mainnet transaction export requires acknowledgement when policy precheck is unavailable`() {
        val transaction = signedMainnetTransaction().copy(
            mempoolAcceptance = DogecoinMempoolAcceptance(
                checked = false,
                allowed = null,
                error = "Dogecoin Core testmempoolaccept is unavailable on this node."
            )
        )
        val nowMillis = transaction.createdAtMillis

        assertTrue(transaction.requiresPolicyUnavailableAcknowledgement())
        assertFalse(
            canExportOrBroadcastSignedDogecoinTransaction(
                transaction = transaction,
                nowMillis = nowMillis,
                maxAgeMillis = 600_000L,
                mainnetAcknowledged = true,
                highFeeAcknowledged = false,
                policyUnavailableAcknowledged = false
            )
        )
        assertTrue(
            canExportOrBroadcastSignedDogecoinTransaction(
                transaction = transaction,
                nowMillis = nowMillis,
                maxAgeMillis = 600_000L,
                mainnetAcknowledged = true,
                highFeeAcknowledged = false,
                policyUnavailableAcknowledged = true
            )
        )
    }

    @Test
    fun `signed raw transaction export requires selected network and ready node`() {
        val transaction = signedMainnetTransaction()
        val nowMillis = transaction.createdAtMillis

        assertFalse(
            canExportSignedRawDogecoinTransaction(
                transaction = transaction,
                nowMillis = nowMillis,
                maxAgeMillis = 600_000L,
                mainnetAcknowledged = true,
                highFeeAcknowledged = false,
                selectedNetwork = DogecoinNetwork.TESTNET,
                nodeReady = true
            )
        )
        assertFalse(
            canExportSignedRawDogecoinTransaction(
                transaction = transaction,
                nowMillis = nowMillis,
                maxAgeMillis = 600_000L,
                mainnetAcknowledged = true,
                highFeeAcknowledged = false,
                selectedNetwork = DogecoinNetwork.MAINNET,
                nodeReady = false
            )
        )
        assertTrue(
            canExportSignedRawDogecoinTransaction(
                transaction = transaction,
                nowMillis = nowMillis,
                maxAgeMillis = 600_000L,
                mainnetAcknowledged = true,
                highFeeAcknowledged = false,
                selectedNetwork = DogecoinNetwork.MAINNET,
                nodeReady = true
            )
        )
    }

    @Test
    fun `signed raw transaction export still requires policy unavailable acknowledgement`() {
        val transaction = signedMainnetTransaction().copy(
            mempoolAcceptance = DogecoinMempoolAcceptance(
                checked = false,
                allowed = null,
                error = "Dogecoin Core testmempoolaccept is unavailable on this node."
            )
        )
        val nowMillis = transaction.createdAtMillis

        assertFalse(
            canExportSignedRawDogecoinTransaction(
                transaction = transaction,
                nowMillis = nowMillis,
                maxAgeMillis = 600_000L,
                mainnetAcknowledged = true,
                highFeeAcknowledged = false,
                policyUnavailableAcknowledged = false,
                selectedNetwork = DogecoinNetwork.MAINNET,
                nodeReady = true
            )
        )
        assertTrue(
            canExportSignedRawDogecoinTransaction(
                transaction = transaction,
                nowMillis = nowMillis,
                maxAgeMillis = 600_000L,
                mainnetAcknowledged = true,
                highFeeAcknowledged = false,
                policyUnavailableAcknowledged = true,
                selectedNetwork = DogecoinNetwork.MAINNET,
                nodeReady = true
            )
        )
    }

    @Test
    fun `signed testnet transaction export does not require policy unavailable acknowledgement`() {
        val transaction = signedMainnetTransaction().copy(
            network = DogecoinNetwork.TESTNET,
            mempoolAcceptance = DogecoinMempoolAcceptance(
                checked = false,
                allowed = null,
                error = "Dogecoin Core testmempoolaccept is unavailable on this node."
            )
        )

        assertFalse(transaction.requiresPolicyUnavailableAcknowledgement())
        assertTrue(
            canExportOrBroadcastSignedDogecoinTransaction(
                transaction = transaction,
                nowMillis = transaction.createdAtMillis,
                maxAgeMillis = 600_000L,
                mainnetAcknowledged = false,
                highFeeAcknowledged = false,
                policyUnavailableAcknowledged = false
            )
        )
    }

    @Test
    fun `expired signed transaction cannot be exported or broadcast`() {
        val transaction = signedMainnetTransaction()

        assertFalse(
            canExportOrBroadcastSignedDogecoinTransaction(
                transaction = transaction,
                nowMillis = transaction.createdAtMillis + 600_001L,
                maxAgeMillis = 600_000L,
                mainnetAcknowledged = true,
                highFeeAcknowledged = true
            )
        )
    }

    @Test
    fun `signed transaction applies custom mainnet fee rate`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val txid = "707102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val utxo = DogecoinUtxo(
            txid = txid,
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )
        val feeRateKoinu = DogecoinProtocol.KOINU_PER_DOGE

        val transaction = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = "2.0",
            feePerKbKoinu = feeRateKoinu
        )

        assertEquals(feeRateKoinu, transaction.feePerKbKoinu)
        assertEquals(22_700_000L, transaction.feeKoinu)
        assertEquals(777_300_000L, transaction.changeKoinu)
    }

    @Test
    fun `transaction builder estimates fee for selected inputs without signing`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val utxo = DogecoinUtxo(
            txid = "121702030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )
        val feeRateKoinu = DogecoinProtocol.KOINU_PER_DOGE

        val estimatedFee = DogecoinTransactionBuilder.estimateFeeForSelection(
            wallet = wallet,
            utxos = listOf(utxo),
            sendAmountKoinu = 2L * DogecoinProtocol.KOINU_PER_DOGE,
            feePerKbKoinu = feeRateKoinu
        )
        val transaction = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = DogecoinKeyGenerator.fromPrivateKeyHex(
                "0000000000000000000000000000000000000000000000000000000000000002"
            ).address,
            amount = "2",
            feePerKbKoinu = feeRateKoinu
        )

        assertEquals(transaction.feeKoinu, estimatedFee)
        assertEquals(
            22_700_000L,
            DogecoinTransactionBuilder.estimateFeeForSelection(
                wallet = wallet,
                inputCount = 1,
                outputCount = 2,
                feePerKbKoinu = feeRateKoinu
            )
        )
    }

    @Test
    fun `signed transaction estimates larger fee for uncompressed mainnet input`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET,
            compressed = false
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val utxo = DogecoinUtxo(
            txid = "777702030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )
        val feeRateKoinu = DogecoinProtocol.KOINU_PER_DOGE

        val transaction = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = "2.0",
            feePerKbKoinu = feeRateKoinu
        )

        assertFalse(wallet.isCompressed)
        assertEquals(feeRateKoinu, transaction.feePerKbKoinu)
        assertEquals(25_900_000L, transaction.feeKoinu)
        assertEquals(774_100_000L, transaction.changeKoinu)
    }

    @Test
    fun `max spendable sweeps confirmed mainnet utxo after fee`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val utxo = DogecoinUtxo(
            txid = "909102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )

        val maxSpend = DogecoinTransactionBuilder.maxSpendable(wallet, listOf(utxo))
        val transaction = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = DogecoinAmount.formatKoinu(maxSpend.amountKoinu)
        )

        assertEquals(10L * DogecoinProtocol.KOINU_PER_DOGE, maxSpend.inputTotalKoinu)
        assertEquals(DogecoinProtocol.MIN_TX_FEE_KOINU, maxSpend.feeKoinu)
        assertEquals(999L * 1_000_000L, maxSpend.amountKoinu)
        assertEquals(maxSpend.amountKoinu, transaction.sendAmountKoinu)
        assertEquals(maxSpend.feeKoinu, transaction.feeKoinu)
        assertEquals(0L, transaction.changeKoinu)
        assertNull(transaction.changeAddress)
    }

    @Test
    fun `max spendable applies custom fee rate`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val utxo = DogecoinUtxo(
            txid = "a0a102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )

        val maxSpend = DogecoinTransactionBuilder.maxSpendable(
            wallet = wallet,
            utxos = listOf(utxo),
            feePerKbKoinu = DogecoinProtocol.KOINU_PER_DOGE
        )

        assertEquals(19_300_000L, maxSpend.feeKoinu)
        assertEquals(980_700_000L, maxSpend.amountKoinu)
    }

    @Test
    fun `max spendable estimates larger fee for uncompressed mainnet input`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET,
            compressed = false
        )
        val utxo = DogecoinUtxo(
            txid = "b7b702030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )

        val maxSpend = DogecoinTransactionBuilder.maxSpendable(
            wallet = wallet,
            utxos = listOf(utxo),
            feePerKbKoinu = DogecoinProtocol.KOINU_PER_DOGE
        )

        assertFalse(wallet.isCompressed)
        assertEquals(22_500_000L, maxSpend.feeKoinu)
        assertEquals(977_500_000L, maxSpend.amountKoinu)
    }

    @Test
    fun `max spendable rejects only unconfirmed funds`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val utxo = DogecoinUtxo(
            txid = "b0b102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 0
        )

        try {
            DogecoinTransactionBuilder.maxSpendable(wallet, listOf(utxo))
            fail("Expected unconfirmed max spend rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("No confirmed spendable"))
        }
    }

    @Test
    fun `max spendable rejects balance that would create dust output after fee`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val utxo = DogecoinUtxo(
            txid = "c0c102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = DogecoinProtocol.MIN_TX_FEE_KOINU + DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU - 1,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )

        try {
            DogecoinTransactionBuilder.maxSpendable(wallet, listOf(utxo))
            fail("Expected dust max-spend rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("standard output"))
        }
    }

    @Test
    fun `max spendable rejects balance below node dust floor after fee`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val nodeDustFloorKoinu = 3L * DogecoinProtocol.KOINU_PER_DOGE
        val utxo = DogecoinUtxo(
            txid = "c1c202030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = DogecoinProtocol.MIN_TX_FEE_KOINU + nodeDustFloorKoinu - 1,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )

        try {
            DogecoinTransactionBuilder.maxSpendable(
                wallet = wallet,
                utxos = listOf(utxo),
                minimumOutputKoinu = nodeDustFloorKoinu
            )
            fail("Expected node dust floor max-spend rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("standard output"))
            assertTrue(e.message.orEmpty().contains("3.01"))
        }
    }

    @Test
    fun `max spendable rejects overflowing dogecoin input total`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val scriptPubKeyHex = DogecoinHex.encode(
            DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
        )
        val firstUtxo = DogecoinUtxo(
            txid = "c8c902030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 0,
            amountKoinu = Long.MAX_VALUE,
            scriptPubKeyHex = scriptPubKeyHex,
            confirmations = 6
        )
        val secondUtxo = DogecoinUtxo(
            txid = "d8d902030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = Long.MAX_VALUE,
            scriptPubKeyHex = scriptPubKeyHex,
            confirmations = 6
        )

        try {
            DogecoinTransactionBuilder.maxSpendable(wallet, listOf(firstUtxo, secondUtxo))
            fail("Expected overflowing input total rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("input total is too large"))
        }
    }

    @Test
    fun `signed transaction rejects fee rate below dogecoin minimum`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val utxo = DogecoinUtxo(
            txid = "808102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )

        try {
            DogecoinTransactionBuilder.createSignedTransaction(
                wallet = wallet,
                utxos = listOf(utxo),
                recipientAddress = recipient.address,
                amount = "2.0",
                feePerKbKoinu = DogecoinProtocol.MIN_TX_FEE_KOINU - 1
            )
            fail("Expected fee rate rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("Fee rate"))
        }
    }

    @Test
    fun `signed transaction rejects fee rate that overflows fee calculation`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val utxo = DogecoinUtxo(
            txid = "a8a902030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )

        try {
            DogecoinTransactionBuilder.createSignedTransaction(
                wallet = wallet,
                utxos = listOf(utxo),
                recipientAddress = recipient.address,
                amount = "2.0",
                feePerKbKoinu = Long.MAX_VALUE
            )
            fail("Expected overflowing fee calculation rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("fee rate is too high"))
        }
    }

    @Test
    fun `signed transaction rejects recipient output below dogecoin standard minimum`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val utxo = DogecoinUtxo(
            txid = "818102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )

        try {
            DogecoinTransactionBuilder.createSignedTransaction(
                wallet = wallet,
                utxos = listOf(utxo),
                recipientAddress = recipient.address,
                amount = "0.00999999"
            )
            fail("Expected dust recipient output rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("at least 0.01 DOGE"))
        }
    }

    @Test
    fun `signed transaction can send to mainnet p2sh recipient`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = p2shAddress(DogecoinNetwork.MAINNET)
        val txid = "202102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val utxo = DogecoinUtxo(
            txid = txid,
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )

        val transaction = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient,
            amount = "2.0"
        )

        assertEquals(DogecoinNetwork.MAINNET, transaction.network)
        assertEquals(recipient, transaction.recipientAddress)
        assertTrue(
            transaction.rawTransactionHex.contains(
                DogecoinHex.encode(DogecoinAddress.scriptPubKey(recipient, DogecoinNetwork.MAINNET))
            )
        )
    }

    @Test
    fun `signed transaction ignores unconfirmed mainnet utxos`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val unconfirmedTxid = "404102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val confirmedTxid = "505102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val scriptPubKeyHex = DogecoinHex.encode(
            DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
        )
        val unconfirmedUtxo = DogecoinUtxo(
            txid = unconfirmedTxid,
            vout = 0,
            amountKoinu = 20L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = scriptPubKeyHex,
            confirmations = 0
        )
        val confirmedUtxo = DogecoinUtxo(
            txid = confirmedTxid,
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = scriptPubKeyHex,
            confirmations = 1
        )

        val transaction = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(unconfirmedUtxo, confirmedUtxo),
            recipientAddress = recipient.address,
            amount = "2.0"
        )

        assertEquals(listOf(confirmedUtxo), transaction.selectedUtxos)
        assertTrue(
            transaction.rawTransactionHex.contains(confirmedTxid.chunked(2).reversed().joinToString(""))
        )
        assertFalse(
            transaction.rawTransactionHex.contains(unconfirmedTxid.chunked(2).reversed().joinToString(""))
        )
    }

    @Test
    fun `signed transaction freshness accepts still spendable selected input`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val utxo = DogecoinUtxo(
            txid = "606102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )
        val transaction = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = "2.0"
        )

        transaction.requireSelectedInputsStillSpendable(
            listOf(utxo.copy(txid = utxo.txid.uppercase(), confirmations = 7))
        )
    }

    @Test
    fun `signed transaction freshness rejects selected input no longer listed`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val utxo = DogecoinUtxo(
            txid = "616102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )
        val transaction = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = "2.0"
        )

        try {
            transaction.requireSelectedInputsStillSpendable(emptyList())
            fail("Expected missing selected input rejection")
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            assertTrue(message.contains("no longer spendable"))
            assertTrue(message.contains("Refresh wallet balance"))
        }
    }

    @Test
    fun `signed transaction freshness rejects selected input that is no longer confirmed`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val utxo = DogecoinUtxo(
            txid = "626102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )
        val transaction = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = "2.0"
        )

        try {
            transaction.requireSelectedInputsStillSpendable(listOf(utxo.copy(confirmations = 0)))
            fail("Expected unconfirmed selected input rejection")
        } catch (e: IllegalArgumentException) {
            val message = e.message.orEmpty()
            assertTrue(message.contains("no longer confirmed"))
            assertTrue(message.contains("Refresh wallet balance"))
        }
    }

    @Test
    fun `signed transaction freshness rejects selected input amount change`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val utxo = DogecoinUtxo(
            txid = "636102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )
        val transaction = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = "2.0"
        )

        try {
            transaction.requireSelectedInputsStillSpendable(listOf(utxo.copy(amountKoinu = utxo.amountKoinu - 1)))
            fail("Expected changed selected input amount rejection")
        } catch (e: IllegalArgumentException) {
            val message = e.message.orEmpty()
            assertTrue(message.contains("amount changed"))
            assertTrue(message.contains("Refresh wallet balance"))
        }
    }

    @Test
    fun `signed transaction prefers larger confirmed utxo to reduce input count and fee`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val scriptPubKeyHex = DogecoinHex.encode(
            DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
        )
        val smallConfirmedUtxo = DogecoinUtxo(
            txid = "707172737475767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f",
            vout = 0,
            amountKoinu = 3L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = scriptPubKeyHex,
            confirmations = 100
        )
        val largeConfirmedUtxo = DogecoinUtxo(
            txid = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f",
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = scriptPubKeyHex,
            confirmations = 2
        )

        val transaction = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(smallConfirmedUtxo, largeConfirmedUtxo),
            recipientAddress = recipient.address,
            amount = "5.0",
            feePerKbKoinu = DogecoinProtocol.KOINU_PER_DOGE
        )

        assertEquals(listOf(largeConfirmedUtxo), transaction.selectedUtxos)
        assertEquals(1, transaction.selectedUtxos.size)
        assertEquals(22_700_000L, transaction.feeKoinu)
        assertTrue(
            transaction.rawTransactionHex.contains(largeConfirmedUtxo.txid.chunked(2).reversed().joinToString(""))
        )
        assertFalse(
            transaction.rawTransactionHex.contains(smallConfirmedUtxo.txid.chunked(2).reversed().joinToString(""))
        )
    }

    @Test
    fun `signed transaction spends uncompressed mainnet p2pkh utxo`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET,
            compressed = false
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val txid = "303102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val utxo = DogecoinUtxo(
            txid = txid,
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )

        val transaction = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = "2.0"
        )

        assertFalse(wallet.isCompressed)
        assertEquals(DogecoinNetwork.MAINNET, transaction.network)
        assertTrue(transaction.rawTransactionHex.contains(wallet.publicKeyHex))
        assertTrue(
            transaction.rawTransactionHex.contains(
                DogecoinHex.encode(DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET))
            )
        )
    }

    @Test
    fun `signed transaction spends testnet p2pkh utxo with change`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.TESTNET
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002",
            DogecoinNetwork.TESTNET
        )
        val txid = "101102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val utxo = DogecoinUtxo(
            txid = txid,
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.TESTNET)
            ),
            confirmations = 6
        )

        val transaction = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = "2.0",
            network = DogecoinNetwork.TESTNET
        )

        assertEquals(10L * DogecoinProtocol.KOINU_PER_DOGE, transaction.inputTotalKoinu)
        assertEquals(2L * DogecoinProtocol.KOINU_PER_DOGE, transaction.sendAmountKoinu)
        assertEquals(DogecoinNetwork.TESTNET, transaction.network)
        assertEquals(recipient.address, transaction.recipientAddress)
        assertEquals(DogecoinProtocol.MIN_TX_FEE_KOINU, transaction.feeKoinu)
        assertTrue(
            transaction.rawTransactionHex.contains(
                DogecoinHex.encode(DogecoinAddress.p2pkhScript(recipient.address, DogecoinNetwork.TESTNET))
            )
        )
    }

    @Test
    fun `signed transaction rejects recipient address from wrong network`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val testnetRecipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002",
            DogecoinNetwork.TESTNET
        )
        val utxo = DogecoinUtxo(
            txid = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            vout = 0,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )

        try {
            DogecoinTransactionBuilder.createSignedTransaction(
                wallet = wallet,
                utxos = listOf(utxo),
                recipientAddress = testnetRecipient.address,
                amount = "1"
            )
            fail("Expected wrong-network recipient rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("mainnet"))
        }
    }

    @Test
    fun `signed transaction rejects only unconfirmed mainnet funds`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val utxo = DogecoinUtxo(
            txid = "606102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 0,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 0
        )

        try {
            DogecoinTransactionBuilder.createSignedTransaction(
                wallet = wallet,
                utxos = listOf(utxo),
                recipientAddress = recipient.address,
                amount = "1"
            )
            fail("Expected unconfirmed UTXO rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("No confirmed spendable"))
        }
    }

    @Test
    fun `signed transaction rejects insufficient funds`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val utxo = DogecoinUtxo(
            txid = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            vout = 0,
            amountKoinu = DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 1
        )

        try {
            DogecoinTransactionBuilder.createSignedTransaction(
                wallet = wallet,
                utxos = listOf(utxo),
                recipientAddress = recipient.address,
                amount = "1"
            )
            fail("Expected insufficient funds")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("Insufficient"))
        }
    }

    @Test
    fun `signed transaction rejects malformed confirmed mainnet utxo txid`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val utxo = DogecoinUtxo(
            txid = "not-a-txid",
            vout = 0,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 1
        )

        try {
            DogecoinTransactionBuilder.createSignedTransaction(
                wallet = wallet,
                utxos = listOf(utxo),
                recipientAddress = recipient.address,
                amount = "1"
            )
            fail("Expected malformed UTXO txid rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("UTXO txid"))
        }
    }

    @Test
    fun `signed transaction rejects duplicate confirmed mainnet utxo outpoints`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val duplicateUtxo = DogecoinUtxo(
            txid = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            vout = 0,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 1
        )

        try {
            DogecoinTransactionBuilder.createSignedTransaction(
                wallet = wallet,
                utxos = listOf(duplicateUtxo, duplicateUtxo),
                recipientAddress = recipient.address,
                amount = "1"
            )
            fail("Expected duplicate UTXO outpoint rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("Duplicate Dogecoin UTXO"))
        }
    }

    private fun signedMainnetTransaction(): DogecoinSignedTransaction {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val utxo = DogecoinUtxo(
            txid = "111102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            vout = 1,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE,
            scriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            ),
            confirmations = 6
        )
        return DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = "2.0"
        )
    }

    private fun p2shAddress(network: DogecoinNetwork): String {
        val scriptHash = ByteArray(20) { index -> (index + 1).toByte() }
        return DogecoinBase58.encodeChecked(network.p2shAddressHeader, scriptHash)
    }
}
