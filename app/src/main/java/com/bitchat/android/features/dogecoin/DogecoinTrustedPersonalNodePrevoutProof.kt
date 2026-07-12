package com.bitchat.android.features.dogecoin

import java.security.MessageDigest

internal const val DOGECOIN_TPN_MAX_PREVIOUS_TRANSACTION_BYTES = 1_000_000
internal const val DOGECOIN_TPN_MAX_SNAPSHOT_PROOF_BYTES = 4 * 1024 * 1024
/** Dogecoin Core's consensus-critical MoneyRange ceiling: 10,000,000,000 DOGE. */
internal const val DOGECOIN_TPN_MAX_MONEY_KOINU = 1_000_000_000_000_000_000L

/** The fixed, read-only RPC that supplied the complete previous-transaction bytes. */
internal enum class DogecoinTrustedPersonalNodePreviousTransactionSource {
    WALLET_GETTRANSACTION,
    TXINDEX_GETRAWTRANSACTION
}

/**
 * A previous output whose amount and script were derived from the exact, txid-committed transaction
 * bytes. This is deliberately not a [DogecoinUtxo], and its private constructor prevents a scalar
 * amount reported by `listunspent` or `gettxout` from being dressed up as a proof.
 *
 * DES-1-C only creates and displays proof-backed snapshots. A future spend coordinator must retain
 * this type boundary rather than adding a public scalar-to-proof constructor.
 */
internal class DogecoinVerifiedPrevout private constructor(
    val txid: String,
    val vout: Int,
    val amountKoinu: Long,
    val scriptPubKeyHex: String,
    val rawPreviousTransactionHex: String,
    val previousTransactionByteCount: Int,
    val source: DogecoinTrustedPersonalNodePreviousTransactionSource
) {
    companion object {
        /**
         * Strictly parse and fully consume one complete legacy Dogecoin transaction, recompute its
         * txid from the exact decoded bytes, and derive the referenced output from those bytes alone.
         * Unrelated historical scripts and zero-value outputs are allowed; ownership/value policy is
         * applied only to [vout].
         */
        fun verify(
            rawPreviousTransactionHex: String,
            expectedTxid: String,
            vout: Int,
            expectedP2pkhScript: ByteArray,
            source: DogecoinTrustedPersonalNodePreviousTransactionSource
        ): DogecoinVerifiedPrevout {
            require(EXACT_TXID.matches(expectedTxid)) {
                "Previous transaction id must be exactly 64 lowercase hexadecimal characters."
            }
            require(vout >= 0) { "Previous transaction output index must be non-negative." }
            require(isExactP2pkhScript(expectedP2pkhScript)) {
                "Expected previous-output script must be an exact P2PKH script."
            }

            val rawBytes = decodeBoundedExactHex(rawPreviousTransactionHex)
            val parsed = parseLegacyTransaction(rawBytes, vout)
            val computedTxid = transactionId(rawBytes)
            require(computedTxid == expectedTxid) {
                "Previous transaction bytes do not match the selected outpoint txid."
            }
            val referencedOutput = parsed.referencedOutput
                ?: throw IllegalArgumentException("Previous transaction does not contain output $vout.")
            require(referencedOutput.amountKoinu > 0L) {
                "Referenced previous output amount must be positive."
            }
            require(referencedOutput.scriptPubKey.contentEquals(expectedP2pkhScript)) {
                "Referenced previous output script does not match the active Android address."
            }

            return DogecoinVerifiedPrevout(
                txid = computedTxid,
                vout = vout,
                amountKoinu = referencedOutput.amountKoinu,
                scriptPubKeyHex = DogecoinHex.encode(referencedOutput.scriptPubKey),
                rawPreviousTransactionHex = rawPreviousTransactionHex,
                previousTransactionByteCount = rawBytes.size,
                source = source
            )
        }

        private val EXACT_TXID = Regex("^[0-9a-f]{64}$")

        private fun decodeBoundedExactHex(hex: String): ByteArray {
            require(hex.isNotEmpty()) { "Previous transaction hex is empty." }
            require(hex.length % 2 == 0) { "Previous transaction hex must have an even length." }
            require(hex.length <= DOGECOIN_TPN_MAX_PREVIOUS_TRANSACTION_BYTES * 2) {
                "Previous transaction exceeds the 1,000,000-byte proof limit."
            }
            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                "Previous transaction contains a non-hexadecimal character."
            }

            // Decode directly into the final bounded array. DogecoinHex.decode intentionally trims and
            // uses chunked intermediate collections, neither of which is appropriate for hostile 1 MB
            // proof input whose exact representation must be rejected if it contains whitespace.
            return ByteArray(hex.length / 2) { index ->
                val high = Character.digit(hex[index * 2], 16)
                val low = Character.digit(hex[index * 2 + 1], 16)
                ((high shl 4) or low).toByte()
            }.also { bytes ->
                require(bytes.size <= DOGECOIN_TPN_MAX_PREVIOUS_TRANSACTION_BYTES) {
                    "Previous transaction exceeds the 1,000,000-byte proof limit."
                }
            }
        }

        private fun parseLegacyTransaction(bytes: ByteArray, referencedVout: Int): ParsedTransaction {
            require(bytes.size >= MIN_LEGACY_TRANSACTION_BYTES) {
                "Previous transaction is too short."
            }
            val reader = BoundedReader(bytes)
            reader.skip(4, "version")
            require((bytes[4].toInt() and 0xff) != 0x00) {
                "Witness transaction serialization is unsupported by Dogecoin."
            }

            val inputCount = reader.readCompactSize("input count")
            require(inputCount > 0L) { "Previous transaction must contain at least one input." }
            val maximumInputs = ((reader.remaining.toLong() - MIN_OUTPUT_SECTION_BYTES)
                .coerceAtLeast(0L)) / MIN_INPUT_BYTES
            require(inputCount <= maximumInputs) {
                "Previous transaction input count exceeds its bounded serialized size."
            }
            repeat(inputCount.toInt()) { inputIndex ->
                reader.skip(32, "input $inputIndex previous transaction id")
                reader.skip(4, "input $inputIndex output index")
                val scriptLength = reader.readCompactSize("input $inputIndex script length")
                val inputsAfterThis = inputCount - inputIndex - 1L
                val minimumTail = INPUT_SEQUENCE_BYTES +
                    inputsAfterThis * MIN_INPUT_BYTES +
                    MIN_OUTPUT_SECTION_BYTES
                require(scriptLength <= reader.remaining.toLong() - minimumTail) {
                    "Previous transaction input $inputIndex script exceeds the bounded transaction."
                }
                reader.skip(scriptLength.toInt(), "input $inputIndex script")
                reader.skip(4, "input $inputIndex sequence")
            }

            val outputCount = reader.readCompactSize("output count")
            require(outputCount > 0L) { "Previous transaction must contain at least one output." }
            val maximumOutputs = ((reader.remaining.toLong() - LOCK_TIME_BYTES).coerceAtLeast(0L)) /
                MIN_OUTPUT_BYTES
            require(outputCount <= maximumOutputs) {
                "Previous transaction output count exceeds its bounded serialized size."
            }

            var outputTotalKoinu = 0L
            var referencedOutput: ParsedOutput? = null
            repeat(outputCount.toInt()) { outputIndex ->
                val amountKoinu = reader.readNonNegativeInt64("output $outputIndex amount")
                require(amountKoinu <= DOGECOIN_TPN_MAX_MONEY_KOINU) {
                    "Previous transaction output $outputIndex exceeds Dogecoin MAX_MONEY."
                }
                outputTotalKoinu = try {
                    Math.addExact(outputTotalKoinu, amountKoinu)
                } catch (_: ArithmeticException) {
                    throw IllegalArgumentException("Previous transaction output total overflowed.")
                }
                require(outputTotalKoinu <= DOGECOIN_TPN_MAX_MONEY_KOINU) {
                    "Previous transaction output total exceeds Dogecoin MAX_MONEY."
                }
                val scriptLength = reader.readCompactSize("output $outputIndex script length")
                val outputsAfterThis = outputCount - outputIndex - 1L
                val minimumTail = outputsAfterThis * MIN_OUTPUT_BYTES + LOCK_TIME_BYTES
                require(scriptLength <= reader.remaining.toLong() - minimumTail) {
                    "Previous transaction output $outputIndex script exceeds the bounded transaction."
                }
                if (outputIndex == referencedVout) {
                    referencedOutput = ParsedOutput(
                        amountKoinu = amountKoinu,
                        scriptPubKey = reader.readBytes(scriptLength.toInt(), "output $outputIndex script")
                    )
                } else {
                    // Historical outputs unrelated to the selected outpoint may contain any script,
                    // including empty, data-carrier, non-standard, or currently unsupported scripts.
                    reader.skip(scriptLength.toInt(), "output $outputIndex script")
                }
            }

            reader.skip(LOCK_TIME_BYTES.toInt(), "lock time")
            require(reader.remaining == 0) { "Previous transaction contains trailing bytes." }
            return ParsedTransaction(referencedOutput)
        }

        private fun transactionId(bytes: ByteArray): String {
            val sha256 = MessageDigest.getInstance("SHA-256")
            val first = sha256.digest(bytes)
            val second = sha256.digest(first)
            return DogecoinHex.encode(second.reversedArray())
        }

        private fun isExactP2pkhScript(script: ByteArray): Boolean {
            return script.size == 25 &&
                script[0] == 0x76.toByte() &&
                script[1] == 0xa9.toByte() &&
                script[2] == 0x14.toByte() &&
                script[23] == 0x88.toByte() &&
                script[24] == 0xac.toByte()
        }

        private const val MIN_LEGACY_TRANSACTION_BYTES = 60
        private const val MIN_INPUT_BYTES = 41L
        private const val MIN_OUTPUT_BYTES = 9L
        private const val INPUT_SEQUENCE_BYTES = 4L
        private const val LOCK_TIME_BYTES = 4L
        private const val MIN_OUTPUT_SECTION_BYTES = 1L + MIN_OUTPUT_BYTES + LOCK_TIME_BYTES
    }

    private data class ParsedTransaction(val referencedOutput: ParsedOutput?)

    private data class ParsedOutput(
        val amountKoinu: Long,
        val scriptPubKey: ByteArray
    )

    private class BoundedReader(private val bytes: ByteArray) {
        var offset: Int = 0
            private set

        val remaining: Int
            get() = bytes.size - offset

        fun skip(count: Int, fieldName: String) {
            require(count >= 0 && count <= remaining) {
                "Previous transaction ended while reading $fieldName."
            }
            offset += count
        }

        fun readBytes(count: Int, fieldName: String): ByteArray {
            require(count >= 0 && count <= remaining) {
                "Previous transaction ended while reading $fieldName."
            }
            val result = bytes.copyOfRange(offset, offset + count)
            offset += count
            return result
        }

        fun readCompactSize(fieldName: String): Long {
            val first = readUnsignedByte(fieldName)
            return when (first) {
                in 0x00..0xfc -> first.toLong()
                0xfd -> readUnsignedLittleEndian(2, fieldName).also { value ->
                    require(value >= 0xfdL) {
                        "Previous transaction contains a non-canonical $fieldName."
                    }
                }
                0xfe -> readUnsignedLittleEndian(4, fieldName).also { value ->
                    require(value > 0xffffL) {
                        "Previous transaction contains a non-canonical $fieldName."
                    }
                }
                else -> readUnsignedLittleEndian(8, fieldName).also { value ->
                    require(value > 0xffffffffL) {
                        "Previous transaction contains a non-canonical $fieldName."
                    }
                }
            }
        }

        fun readNonNegativeInt64(fieldName: String): Long {
            require(8 <= remaining) { "Previous transaction ended while reading $fieldName." }
            require((bytes[offset + 7].toInt() and 0x80) == 0) {
                "Previous transaction $fieldName is negative or exceeds signed 64-bit range."
            }
            return readUnsignedLittleEndian(8, fieldName)
        }

        private fun readUnsignedByte(fieldName: String): Int {
            require(remaining >= 1) { "Previous transaction ended while reading $fieldName." }
            return bytes[offset++].toInt() and 0xff
        }

        private fun readUnsignedLittleEndian(count: Int, fieldName: String): Long {
            require(count in 1..8 && count <= remaining) {
                "Previous transaction ended while reading $fieldName."
            }
            if (count == 8) {
                require((bytes[offset + 7].toInt() and 0x80) == 0) {
                    "Previous transaction $fieldName exceeds signed 64-bit range."
                }
            }
            var value = 0L
            repeat(count) { index ->
                value = value or ((bytes[offset + index].toLong() and 0xffL) shl (index * 8))
            }
            offset += count
            return value
        }
    }
}
