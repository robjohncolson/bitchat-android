package com.bitchat.android.features.dogecoin

/**
 * Shared, stateless validation of raw signed Dogecoin transactions.
 *
 * Extracted verbatim from [DogecoinRpcClient] so any backend that handles an UNTRUSTED raw
 * transaction can run the exact same structural/standardness checks the local broadcast path runs,
 * without instantiating an RPC client. The first consumer is the broadcast-over-mesh helper
 * (`BroadcastHelperService`), which must re-validate a peer-supplied tx before touching
 * `sendrawtransaction`.
 *
 * IMPORTANT: these checks cover structure, standardness (P2PKH/P2SH only), positive/non-dust
 * outputs, canonical varints, and no trailing bytes. They deliberately do NOT verify signatures or
 * that inputs are funded/unspent — the Dogecoin node (`testmempoolaccept` / `sendrawtransaction`)
 * remains the real arbiter of validity. Callers must not present a passing result as "the tx is
 * valid/will be accepted", only as "the tx is well-formed and standard".
 */
internal object DogecoinRawTxValidator {

    private const val MIN_RAW_TRANSACTION_BYTES = 10
    val txidRegex = Regex("^[0-9a-f]{64}$")

    /**
     * Trim/lowercase + hex + full structural validation against the standard dust minimum.
     * Returns the normalized lowercase hex. Throws [IllegalArgumentException] on any problem.
     */
    fun normalize(rawTransactionHex: String): String {
        val normalized = rawTransactionHex.trim().lowercase()
        require(normalized.isNotEmpty()) {
            "Signed Dogecoin transaction hex is empty."
        }
        require(normalized.length % 2 == 0) {
            "Signed Dogecoin transaction hex must have an even length."
        }
        require(normalized.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Signed Dogecoin transaction hex must contain only hex characters."
        }

        val rawBytes = DogecoinHex.decode(normalized)
        require(rawBytes.size >= MIN_RAW_TRANSACTION_BYTES) {
            "Signed Dogecoin transaction is too short."
        }
        validateShape(rawBytes, DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU)
        return normalized
    }

    fun validateHex(rawTransactionHex: String, minimumOutputKoinu: Long) {
        validateShape(
            DogecoinHex.decode(rawTransactionHex),
            dogecoinEffectiveStandardOutputKoinu(minimumOutputKoinu)
        )
    }

    fun validateShape(bytes: ByteArray, minimumOutputKoinu: Long) {
        var offset = 0

        fun requireBytes(count: Int, fieldName: String) {
            require(offset <= bytes.size - count) {
                "Signed Dogecoin transaction ended while reading $fieldName."
            }
        }

        fun readBytes(count: Int, fieldName: String): ByteArray {
            requireBytes(count, fieldName)
            val value = bytes.copyOfRange(offset, offset + count)
            offset += count
            return value
        }

        fun readUnsignedLittleEndian(count: Int, fieldName: String): Long {
            requireBytes(count, fieldName)
            var value = 0L
            for (index in 0 until count) {
                val part = bytes[offset + index].toLong() and 0xffL
                if (index == 7) {
                    require(part <= 0x7fL) {
                        "Signed Dogecoin transaction $fieldName is too large."
                    }
                }
                value = value or (part shl (8 * index))
            }
            offset += count
            return value
        }

        fun readVarInt(fieldName: String): Long {
            requireBytes(1, fieldName)
            val first = bytes[offset].toInt() and 0xff
            offset += 1
            return when (first) {
                in 0x00..0xfc -> first.toLong()
                0xfd -> readUnsignedLittleEndian(2, fieldName).also {
                    require(it >= 0xfdL) {
                        "Signed Dogecoin transaction has a non-canonical $fieldName."
                    }
                }
                0xfe -> readUnsignedLittleEndian(4, fieldName).also {
                    require(it > 0xffffL) {
                        "Signed Dogecoin transaction has a non-canonical $fieldName."
                    }
                }
                else -> readUnsignedLittleEndian(8, fieldName).also {
                    require(it > 0xffffffffL) {
                        "Signed Dogecoin transaction has a non-canonical $fieldName."
                    }
                }
            }
        }

        readBytes(4, "version")
        val inputCount = readVarInt("input count")
        require(inputCount > 0L) {
            "Signed Dogecoin transaction must include at least one input."
        }
        require(inputCount <= bytes.size.toLong()) {
            "Signed Dogecoin transaction input count is not plausible."
        }
        repeat(inputCount.toInt()) { inputIndex ->
            readBytes(32, "input $inputIndex previous transaction id")
            readBytes(4, "input $inputIndex output index")
            val scriptLength = readVarInt("input $inputIndex script length")
            require(scriptLength <= bytes.size - offset) {
                "Signed Dogecoin transaction input $inputIndex script is truncated."
            }
            readBytes(scriptLength.toInt(), "input $inputIndex script")
            readBytes(4, "input $inputIndex sequence")
        }

        val outputCount = readVarInt("output count")
        require(outputCount > 0L) {
            "Signed Dogecoin transaction must include at least one output."
        }
        require(outputCount <= bytes.size.toLong()) {
            "Signed Dogecoin transaction output count is not plausible."
        }
        var outputTotalKoinu = 0L
        repeat(outputCount.toInt()) { outputIndex ->
            val outputAmountKoinu = readUnsignedLittleEndian(8, "output $outputIndex amount")
            require(outputAmountKoinu > 0L) {
                "Signed Dogecoin transaction output $outputIndex amount must be positive."
            }
            require(outputAmountKoinu >= minimumOutputKoinu) {
                if (minimumOutputKoinu == DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU) {
                    "Signed Dogecoin transaction output $outputIndex amount is below the Dogecoin standard output minimum."
                } else {
                    "Signed Dogecoin transaction output $outputIndex amount is below the Dogecoin node soft dust limit of " +
                        "${DogecoinAmount.formatKoinu(minimumOutputKoinu)} DOGE."
                }
            }
            outputTotalKoinu = try {
                Math.addExact(outputTotalKoinu, outputAmountKoinu)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("Signed Dogecoin transaction output total is too large.")
            }
            val scriptLength = readVarInt("output $outputIndex script length")
            require(scriptLength > 0L) {
                "Signed Dogecoin transaction output $outputIndex script is empty."
            }
            require(scriptLength <= bytes.size - offset) {
                "Signed Dogecoin transaction output $outputIndex script is truncated."
            }
            val outputScript = readBytes(scriptLength.toInt(), "output $outputIndex script")
            require(isStandardOutputScript(outputScript)) {
                "Signed Dogecoin transaction output $outputIndex script must be standard P2PKH or P2SH."
            }
        }

        readBytes(4, "lock time")
        require(offset == bytes.size) {
            "Signed Dogecoin transaction has extra trailing bytes."
        }
    }

    fun isStandardOutputScript(script: ByteArray): Boolean {
        val p2pkh = script.size == 25 &&
            script[0] == 0x76.toByte() &&
            script[1] == 0xa9.toByte() &&
            script[2] == 0x14.toByte() &&
            script[23] == 0x88.toByte() &&
            script[24] == 0xac.toByte()
        val p2sh = script.size == 23 &&
            script[0] == 0xa9.toByte() &&
            script[1] == 0x14.toByte() &&
            script[22] == 0x87.toByte()
        return p2pkh || p2sh
    }

    fun verifyBroadcastTxid(rawTransactionHex: String, rpcTxid: String): String {
        val expectedTxid = DogecoinTransactionBuilder.transactionId(rawTransactionHex)
        val normalizedRpcTxid = rpcTxid.trim().lowercase()
        require(txidRegex.matches(normalizedRpcTxid)) {
            "RPC sendrawtransaction returned an invalid Dogecoin txid."
        }
        require(normalizedRpcTxid == expectedTxid) {
            "RPC broadcast txid did not match the signed Dogecoin transaction."
        }
        return expectedTxid
    }
}
