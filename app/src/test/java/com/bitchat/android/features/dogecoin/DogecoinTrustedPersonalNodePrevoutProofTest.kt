package com.bitchat.android.features.dogecoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class DogecoinTrustedPersonalNodePrevoutProofTest {

    private val walletScript = byteArrayOf(0x76, 0xa9.toByte(), 0x14) +
        ByteArray(20) { (it + 1).toByte() } +
        byteArrayOf(0x88.toByte(), 0xac.toByte())

    @Test
    fun `coinbase and unrelated historical outputs are accepted while referenced bytes are derived`() {
        val rawBytes = legacyTransaction(
            inputs = listOf(
                TestInput(
                    previousTxid = ByteArray(32),
                    outputIndex = 0xffffffffL,
                    script = byteArrayOf(0x03, 0x01, 0x02, 0x03)
                )
            ),
            outputs = listOf(
                TestOutput(0L, byteArrayOf(0x6a, 0x01, 0x01)),
                TestOutput(123_456_789L, walletScript),
                TestOutput(1L, byteArrayOf(0x51))
            )
        )
        val rawHex = DogecoinHex.encode(rawBytes)
        val txid = exactTxid(rawBytes)

        val verified = DogecoinVerifiedPrevout.verify(
            rawPreviousTransactionHex = rawHex,
            expectedTxid = txid,
            vout = 1,
            expectedP2pkhScript = walletScript,
            source = DogecoinTrustedPersonalNodePreviousTransactionSource.WALLET_GETTRANSACTION
        )

        assertEquals(txid, verified.txid)
        assertEquals(1, verified.vout)
        assertEquals(123_456_789L, verified.amountKoinu)
        assertEquals(DogecoinHex.encode(walletScript), verified.scriptPubKeyHex)
        assertEquals(rawHex, verified.rawPreviousTransactionHex)
        assertEquals(rawBytes.size, verified.previousTransactionByteCount)
        assertEquals(
            DogecoinTrustedPersonalNodePreviousTransactionSource.WALLET_GETTRANSACTION,
            verified.source
        )
    }

    @Test
    fun `fabricated previous transaction id and missing vout fail closed`() {
        val rawBytes = standardPreviousTransaction()

        assertFailureContains("do not match") {
            verify(rawBytes, expectedTxid = "00".repeat(32))
        }
        assertFailureContains("does not contain output") {
            verify(rawBytes, vout = 1)
        }
        assertFailureContains("non-negative") {
            verify(rawBytes, vout = -1)
        }
        assertFailureContains("lowercase hexadecimal") {
            verify(rawBytes, expectedTxid = exactTxid(rawBytes).uppercase())
        }
    }

    @Test
    fun `referenced output must be positive and match exact local P2PKH script`() {
        val zeroReferenced = legacyTransaction(
            inputs = listOf(TestInput()),
            outputs = listOf(TestOutput(0L, walletScript))
        )
        assertFailureContains("must be positive") { verify(zeroReferenced) }

        val wrongScript = walletScript.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertFailureContains("does not match") {
            verify(standardPreviousTransaction(), expectedScript = wrongScript)
        }
        assertFailureContains("exact P2PKH") {
            verify(standardPreviousTransaction(), expectedScript = byteArrayOf(0x51))
        }
    }

    @Test
    fun `hex is exact and bounded before decoding`() {
        val rawBytes = standardPreviousTransaction()
        val rawHex = DogecoinHex.encode(rawBytes)
        val txid = exactTxid(rawBytes)

        listOf(" $rawHex", "$rawHex\n", "0$rawHex", "gg").forEach { malformed ->
            assertThrows(IllegalArgumentException::class.java) {
                DogecoinVerifiedPrevout.verify(
                    malformed,
                    txid,
                    0,
                    walletScript,
                    DogecoinTrustedPersonalNodePreviousTransactionSource.TXINDEX_GETRAWTRANSACTION
                )
            }
        }

        val oversized = "00".repeat(DOGECOIN_TPN_MAX_PREVIOUS_TRANSACTION_BYTES + 1)
        assertFailureContains("1,000,000-byte") {
            DogecoinVerifiedPrevout.verify(
                oversized,
                "00".repeat(32),
                0,
                walletScript,
                DogecoinTrustedPersonalNodePreviousTransactionSource.TXINDEX_GETRAWTRANSACTION
            )
        }
    }

    @Test
    fun `non-canonical CompactSize and count bombs fail before iteration`() {
        val canonical = standardPreviousTransaction()
        val nonCanonicalInputCount = canonical.copyOfRange(0, 4) +
            byteArrayOf(0xfd.toByte(), 0x01, 0x00) +
            canonical.copyOfRange(5, canonical.size)
        assertFailureContains("non-canonical input count") { verify(nonCanonicalInputCount) }

        val impossibleInputCount = uint32Le(1L) +
            byteArrayOf(0x02) +
            ByteArray(41) +
            byteArrayOf(0x01) +
            ByteArray(9) +
            uint32Le(0L)
        assertFailureContains("input count exceeds") { verify(impossibleInputCount) }

        val oneInput = serializedInput(TestInput())
        val impossibleOutputCount = uint32Le(1L) +
            byteArrayOf(0x01) + oneInput +
            byteArrayOf(0x02) +
            int64Le(1L) + byteArrayOf(0x00) +
            uint32Le(0L)
        assertFailureContains("output count exceeds") { verify(impossibleOutputCount) }

        val unsignedCompactSizeOverflow = uint32Le(1L) +
            byteArrayOf(0xff.toByte()) +
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0x80.toByte()) +
            ByteArray(60)
        assertFailureContains("exceeds signed 64-bit") { verify(unsignedCompactSizeOverflow) }
    }

    @Test
    fun `script length bombs truncation and trailing bytes fail closed`() {
        val inputScriptBomb = uint32Le(1L) +
            byteArrayOf(0x01) +
            ByteArray(32) + uint32Le(0L) +
            byteArrayOf(0xfe.toByte()) + uint32Le(0xffffffffL) +
            ByteArray(60)
        assertFailureContains("script exceeds") { verify(inputScriptBomb) }

        val outputNonCanonicalLength = uint32Le(1L) +
            byteArrayOf(0x01) + serializedInput(TestInput()) +
            byteArrayOf(0x01) + int64Le(2L) +
            byteArrayOf(0xfd.toByte(), 0x19, 0x00) + walletScript +
            uint32Le(0L)
        assertFailureContains("non-canonical output 0 script length") {
            verify(outputNonCanonicalLength)
        }

        val canonical = standardPreviousTransaction()
        assertFailureContains("too short") { verify(canonical.copyOf(20)) }
        assertFailureContains("trailing bytes") { verify(canonical + byteArrayOf(0x00)) }
    }

    @Test
    fun `negative output fails closed`() {
        val negative = legacyTransaction(
            inputs = listOf(TestInput()),
            outputs = listOf(TestOutput(Long.MIN_VALUE, walletScript))
        )
        assertFailureContains("negative or exceeds") { verify(negative) }
    }

    @Test
    fun `individual and aggregate values above Dogecoin MAX_MONEY fail closed`() {
        val oversizedOutput = legacyTransaction(
            inputs = listOf(TestInput()),
            outputs = listOf(TestOutput(DOGECOIN_TPN_MAX_MONEY_KOINU + 1L, walletScript))
        )
        assertFailureContains("output 0 exceeds Dogecoin MAX_MONEY") {
            verify(oversizedOutput)
        }

        val oversizedTotal = legacyTransaction(
            inputs = listOf(TestInput()),
            outputs = listOf(
                TestOutput(DOGECOIN_TPN_MAX_MONEY_KOINU, walletScript),
                TestOutput(1L, byteArrayOf(0x51))
            )
        )
        assertFailureContains("output total exceeds Dogecoin MAX_MONEY") {
            verify(oversizedTotal)
        }
    }

    @Test
    fun `legacy transaction requires at least one input and output`() {
        val witnessMarker = uint32Le(1L) + byteArrayOf(0x00, 0x01) + ByteArray(54)
        assertFailureContains("Witness transaction serialization") { verify(witnessMarker) }

        val noInputs = uint32Le(1L) + byteArrayOf(0xfd.toByte(), 0xfd.toByte(), 0x00) +
            ByteArray(53)
        assertFailureContains("input count exceeds") { verify(noInputs) }

        val zeroInputMarker = uint32Le(1L) + byteArrayOf(0x00) + byteArrayOf(0x00) + uint32Le(0L) +
            ByteArray(50)
        assertFailureContains("Witness transaction serialization") { verify(zeroInputMarker) }

        val noOutputs = uint32Le(1L) + byteArrayOf(0x01) + serializedInput(TestInput()) +
            byteArrayOf(0x00) + uint32Le(0L) + ByteArray(10)
        assertFailureContains("at least one output") { verify(noOutputs) }
    }

    private fun standardPreviousTransaction(): ByteArray = legacyTransaction(
        inputs = listOf(TestInput()),
        outputs = listOf(TestOutput(500_000_000L, walletScript))
    )

    private fun verify(
        rawBytes: ByteArray,
        expectedTxid: String = exactTxid(rawBytes),
        vout: Int = 0,
        expectedScript: ByteArray = walletScript
    ): DogecoinVerifiedPrevout = DogecoinVerifiedPrevout.verify(
        rawPreviousTransactionHex = DogecoinHex.encode(rawBytes),
        expectedTxid = expectedTxid,
        vout = vout,
        expectedP2pkhScript = expectedScript,
        source = DogecoinTrustedPersonalNodePreviousTransactionSource.TXINDEX_GETRAWTRANSACTION
    )

    private fun assertFailureContains(message: String, block: () -> Unit) {
        val thrown = assertThrows(IllegalArgumentException::class.java) { block() }
        assertTrue(
            "Expected '${thrown.message}' to contain '$message'",
            thrown.message.orEmpty().contains(message, ignoreCase = true)
        )
    }

    private fun legacyTransaction(
        inputs: List<TestInput>,
        outputs: List<TestOutput>
    ): ByteArray = ByteArrayOutputStream().apply {
        write(uint32Le(1L))
        write(compactSize(inputs.size.toLong()))
        inputs.forEach { write(serializedInput(it)) }
        write(compactSize(outputs.size.toLong()))
        outputs.forEach { output ->
            write(int64Le(output.amountKoinu))
            write(compactSize(output.script.size.toLong()))
            write(output.script)
        }
        write(uint32Le(0L))
    }.toByteArray()

    private fun serializedInput(input: TestInput): ByteArray = ByteArrayOutputStream().apply {
        write(input.previousTxid)
        write(uint32Le(input.outputIndex))
        write(compactSize(input.script.size.toLong()))
        write(input.script)
        write(uint32Le(input.sequence))
    }.toByteArray()

    private fun compactSize(value: Long): ByteArray = when {
        value < 0xfdL -> byteArrayOf(value.toByte())
        value <= 0xffffL -> byteArrayOf(
            0xfd.toByte(),
            value.toByte(),
            (value ushr 8).toByte()
        )
        value <= 0xffffffffL -> byteArrayOf(0xfe.toByte()) + uint32Le(value)
        else -> byteArrayOf(0xff.toByte()) + int64Le(value)
    }

    private fun uint32Le(value: Long): ByteArray =
        ByteArray(4) { index -> (value ushr (index * 8)).toByte() }

    private fun int64Le(value: Long): ByteArray =
        ByteArray(8) { index -> (value ushr (index * 8)).toByte() }

    private fun exactTxid(bytes: ByteArray): String {
        val sha256 = MessageDigest.getInstance("SHA-256")
        return DogecoinHex.encode(sha256.digest(sha256.digest(bytes)).reversedArray())
    }

    private data class TestInput(
        val previousTxid: ByteArray = ByteArray(32) { 0x44 },
        val outputIndex: Long = 0L,
        val script: ByteArray = ByteArray(0),
        val sequence: Long = 0xffffffffL
    )

    private data class TestOutput(
        val amountKoinu: Long,
        val script: ByteArray
    )
}
