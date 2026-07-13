package com.bitchat.android.features.dogecoin

/** TPN high fees are hard failures. There is intentionally no acknowledgement override. */
internal enum class DogecoinTrustedPersonalNodeFeeHardBlock {
    ABSOLUTE,
    RELATIVE
}

internal class DogecoinTrustedPersonalNodeFeeBlockedException(
    val block: DogecoinTrustedPersonalNodeFeeHardBlock,
    val feeKoinu: Long,
    val sendAmountKoinu: Long
) : IllegalArgumentException(
    when (block) {
        DogecoinTrustedPersonalNodeFeeHardBlock.ABSOLUTE ->
            "Trusted personal node total fee must be less than 1 DOGE."
        DogecoinTrustedPersonalNodeFeeHardBlock.RELATIVE ->
            "Trusted personal node total fee must be less than 10% of the send amount."
    }
)

internal fun dogecoinTrustedPersonalNodeFeeHardBlock(
    feeKoinu: Long,
    sendAmountKoinu: Long
): DogecoinTrustedPersonalNodeFeeHardBlock? {
    require(feeKoinu >= 0L) { "Trusted personal node fee must be non-negative." }
    require(sendAmountKoinu > 0L) { "Trusted personal node send amount must be positive." }
    if (feeKoinu >= DogecoinProtocol.HIGH_FEE_ABSOLUTE_KOINU) {
        return DogecoinTrustedPersonalNodeFeeHardBlock.ABSOLUTE
    }
    val relativeThreshold = sendAmountKoinu / DogecoinProtocol.HIGH_FEE_RELATIVE_DENOMINATOR +
        if (sendAmountKoinu % DogecoinProtocol.HIGH_FEE_RELATIVE_DENOMINATOR == 0L) 0L else 1L
    return DogecoinTrustedPersonalNodeFeeHardBlock.RELATIVE.takeIf {
        feeKoinu >= relativeThreshold
    }
}

internal fun requireDogecoinTrustedPersonalNodeFeeAllowed(
    feeKoinu: Long,
    sendAmountKoinu: Long
) {
    when (dogecoinTrustedPersonalNodeFeeHardBlock(feeKoinu, sendAmountKoinu)) {
        DogecoinTrustedPersonalNodeFeeHardBlock.ABSOLUTE -> throw
            DogecoinTrustedPersonalNodeFeeBlockedException(
                DogecoinTrustedPersonalNodeFeeHardBlock.ABSOLUTE,
                feeKoinu,
                sendAmountKoinu
            )
        DogecoinTrustedPersonalNodeFeeHardBlock.RELATIVE -> throw
            DogecoinTrustedPersonalNodeFeeBlockedException(
                DogecoinTrustedPersonalNodeFeeHardBlock.RELATIVE,
                feeKoinu,
                sendAmountKoinu
            )
        null -> Unit
    }
}

/**
 * Immutable, proof-backed review for the named TPN route. This is deliberately not a
 * [DogecoinSignedTransaction]: it cannot enter raw export, helper, generic RPC, or SPV APIs by type.
 * The private constructor and strict local reparse keep node scalars out of every fee and output
 * invariant.
 */
internal class DogecoinTrustedPersonalNodeFrozenReview private constructor(
    val authorization: DogecoinTrustedPersonalNodeSpendAuthorization,
    val rawTransactionHex: String,
    val txid: String,
    val createdAtMillis: Long,
    val recipientAddress: String,
    val inputTotalKoinu: Long,
    val sendAmountKoinu: Long,
    val feePerKbKoinu: Long,
    val feeKoinu: Long,
    val changeKoinu: Long,
    val changeAddress: String?,
    selectedProofCandidates: List<DogecoinTrustedPersonalNodeProofCandidate>
) {
    val binding: DogecoinTrustedPersonalNodeSessionBinding = authorization.binding
    val proofSnapshot: DogecoinTrustedPersonalNodeProofSnapshot = authorization.proofSnapshot
    val selectedProofCandidates: List<DogecoinTrustedPersonalNodeProofCandidate> =
        selectedProofCandidates.toList()
    val selectedPrevouts: List<DogecoinVerifiedPrevout> =
        this.selectedProofCandidates.map { it.verifiedPrevout }

    init {
        requireRevalidated()
    }

    fun isExpired(nowMillis: Long, maxAgeMillis: Long): Boolean {
        require(maxAgeMillis > 0L) { "Maximum trusted personal node review age must be positive." }
        return nowMillis < createdAtMillis || nowMillis - createdAtMillis > maxAgeMillis
    }

    /** Re-run immediately before persistence/disclosure; this trusts no cached parsed scalar. */
    fun requireRevalidated() {
        require(binding.network == DogecoinNetwork.MAINNET) {
            "Trusted personal node reviews are mainnet-only."
        }
        require(proofSnapshot.binding == binding) {
            "Trusted personal node review no longer matches its proof binding."
        }
        require(selectedProofCandidates.isNotEmpty()) {
            "Trusted personal node review has no proof-backed inputs."
        }
        require(selectedProofCandidates.all { candidate ->
            proofSnapshot.proofCandidates.any { it === candidate }
        }) {
            "Trusted personal node review contains an input outside its frozen proof snapshot."
        }
        require(
            selectedProofCandidates.distinctBy {
                dogecoinTrustedPersonalNodeOutpointKey(it.verifiedPrevout.txid, it.verifiedPrevout.vout)
            }.size == selectedProofCandidates.size
        ) {
            "Trusted personal node review contains duplicate selected inputs."
        }
        require(DogecoinAddress.isValidAddress(recipientAddress, binding.network)) {
            "Trusted personal node review recipient is invalid."
        }
        require(sendAmountKoinu >= DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU) {
            "Trusted personal node review send amount is below the standard output minimum."
        }
        require(feePerKbKoinu >= DogecoinProtocol.MIN_TX_FEE_KOINU) {
            "Trusted personal node review fee rate is below the local minimum."
        }

        // Bound and parse first, before the legacy validator allocates its decoded array. The ordinary
        // validator then supplies the existing standard-script/dust gates independently.
        val parsed = parseDogecoinTrustedPersonalNodeSignedTransaction(rawTransactionHex)
        DogecoinRawTxValidator.validateHex(
            rawTransactionHex,
            DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU
        )
        val parsedInputKeys = parsed.inputs.map {
            dogecoinTrustedPersonalNodeOutpointKey(it.txid, it.vout)
        }
        val verifiedInputKeys = selectedPrevouts.map {
            dogecoinTrustedPersonalNodeOutpointKey(it.txid, it.vout)
        }
        require(parsedInputKeys.size == parsedInputKeys.distinct().size) {
            "Trusted personal node signed transaction contains duplicate inputs."
        }
        require(parsedInputKeys.toSet() == verifiedInputKeys.toSet() && parsedInputKeys.size == verifiedInputKeys.size) {
            "Trusted personal node signed transaction inputs do not exactly match the verified prevouts."
        }

        val expectedOutputs = buildList {
            add(
                ParsedTrustedPersonalNodeOutput(
                    amountKoinu = sendAmountKoinu,
                    scriptPubKeyHex = DogecoinHex.encode(
                        DogecoinAddress.scriptPubKey(recipientAddress, binding.network)
                    )
                )
            )
            if (changeKoinu > 0L) {
                require(changeAddress == binding.androidAddress) {
                    "Trusted personal node change is not bound to the Android wallet address."
                }
                add(
                    ParsedTrustedPersonalNodeOutput(
                        amountKoinu = changeKoinu,
                        scriptPubKeyHex = DogecoinHex.encode(
                            DogecoinAddress.p2pkhScript(binding.androidAddress, binding.network)
                        )
                    )
                )
            } else {
                require(changeAddress == null) {
                    "Trusted personal node review records a change address without change."
                }
            }
        }
        require(parsed.outputs == expectedOutputs) {
            "Trusted personal node signed transaction outputs differ from the frozen review."
        }

        val locallyVerifiedInputTotal = checkedTrustedPersonalNodeSum(
            selectedPrevouts.map { it.amountKoinu },
            "Trusted personal node verified input total overflowed."
        )
        require(locallyVerifiedInputTotal == inputTotalKoinu) {
            "Trusted personal node review input total differs from its verified prevouts."
        }
        val locallyParsedOutputTotal = checkedTrustedPersonalNodeSum(
            parsed.outputs.map { it.amountKoinu },
            "Trusted personal node signed output total overflowed."
        )
        require(locallyVerifiedInputTotal >= locallyParsedOutputTotal) {
            "Trusted personal node signed transaction has a negative fee."
        }
        val actualFee = locallyVerifiedInputTotal - locallyParsedOutputTotal
        require(actualFee == feeKoinu) {
            "Trusted personal node actual fee differs from the frozen review."
        }
        require(changeKoinu == if (parsed.outputs.size == 2) parsed.outputs[1].amountKoinu else 0L) {
            "Trusted personal node change differs from the signed bytes."
        }
        val localTxid = DogecoinTransactionBuilder.transactionId(rawTransactionHex)
        require(localTxid == txid) {
            "Trusted personal node signed transaction id differs from the frozen review."
        }
        requireDogecoinTrustedPersonalNodeFeeAllowed(actualFee, sendAmountKoinu)
    }

    companion object {
        internal fun freeze(
            authorization: DogecoinTrustedPersonalNodeSpendAuthorization,
            signed: DogecoinSignedTransaction,
            selectedProofCandidates: List<DogecoinTrustedPersonalNodeProofCandidate>
        ): DogecoinTrustedPersonalNodeFrozenReview = DogecoinTrustedPersonalNodeFrozenReview(
            authorization = authorization,
            rawTransactionHex = signed.rawTransactionHex,
            txid = signed.txid,
            createdAtMillis = signed.createdAtMillis,
            recipientAddress = signed.recipientAddress,
            inputTotalKoinu = signed.inputTotalKoinu,
            sendAmountKoinu = signed.sendAmountKoinu,
            feePerKbKoinu = signed.feePerKbKoinu,
            feeKoinu = signed.feeKoinu,
            changeKoinu = signed.changeKoinu,
            changeAddress = signed.changeAddress,
            selectedProofCandidates = selectedProofCandidates
        )

        /**
         * Restore the exact same signed bytes after process death only after a newly activated session
         * has collected a fresh complete proof containing every reserved outpoint with identical
         * hash-committed amount/script metadata. No generic transaction or node scalar is reconstructed.
         */
        internal fun recover(
            sessionHolder: DogecoinTrustedPersonalNodeSessionHolder,
            authorization: DogecoinTrustedPersonalNodeSpendAuthorization,
            nowMonotonicMillis: Long,
            persisted: DogecoinTrustedPersonalNodeAttemptReviewRecord
        ): DogecoinTrustedPersonalNodeFrozenReview {
            val currentProof = sessionHolder.freshProofSnapshot(
                authorization,
                nowMonotonicMillis
            ) ?: throw IllegalStateException(
                "Trusted personal node recovery requires a fresh proof-backed session."
            )
            require(persisted.binding == authorization.binding && currentProof.binding == persisted.binding) {
                "Trusted personal node recovery profile or revision changed."
            }
            val currentByOutpoint = currentProof.proofCandidates.associateBy { candidate ->
                dogecoinTrustedPersonalNodeOutpointKey(
                    candidate.verifiedPrevout.txid,
                    candidate.verifiedPrevout.vout
                )
            }
            val selectedCandidates = persisted.proofReferences.map { reference ->
                val candidate = currentByOutpoint[
                    dogecoinTrustedPersonalNodeOutpointKey(reference.txid, reference.vout)
                ] ?: throw IllegalStateException(
                    "Trusted personal node recovery proof is incomplete for a reserved input."
                )
                val currentReference =
                    DogecoinTrustedPersonalNodeAttemptProofReference.fromVerifiedPrevout(
                        candidate.verifiedPrevout
                    )
                require(reference.sameAs(currentReference)) {
                    "Trusted personal node recovery proof differs from the reserved prevout."
                }
                candidate
            }
            return DogecoinTrustedPersonalNodeFrozenReview(
                authorization = authorization,
                rawTransactionHex = persisted.signedRawTransactionHex,
                txid = persisted.localTxid,
                createdAtMillis = persisted.createdAtMillis,
                recipientAddress = persisted.recipientAddress,
                inputTotalKoinu = persisted.inputTotalKoinu,
                sendAmountKoinu = persisted.sendAmountKoinu,
                feePerKbKoinu = persisted.feePerKbKoinu,
                feeKoinu = persisted.feeKoinu,
                changeKoinu = persisted.changeKoinu,
                changeAddress = persisted.binding.androidAddress.takeIf { persisted.changeKoinu > 0L },
                selectedProofCandidates = selectedCandidates
            )
        }
    }
}

/** Only proof-backed snapshot candidates can enter this signer entry point. */
internal object DogecoinTrustedPersonalNodeTransactionBuilder {
    fun createFrozenReview(
        wallet: DogecoinWalletKey,
        sessionHolder: DogecoinTrustedPersonalNodeSessionHolder,
        authorization: DogecoinTrustedPersonalNodeSpendAuthorization,
        nowMonotonicMillis: Long,
        recipientAddress: String,
        amount: String,
        feePerKbKoinu: Long = DogecoinProtocol.DEFAULT_FEE_PER_KB_KOINU
    ): DogecoinTrustedPersonalNodeFrozenReview {
        val proofSnapshot = sessionHolder.freshProofSnapshot(
            authorization,
            nowMonotonicMillis
        ) ?: throw IllegalStateException(
            "Trusted personal node spend authorization or proof snapshot is stale."
        )
        require(authorization.binding == proofSnapshot.binding) {
            "Trusted personal node spend authorization does not match its proof snapshot."
        }
        require(wallet.network == DogecoinNetwork.MAINNET) {
            "Trusted personal node signer requires a mainnet wallet."
        }
        require(wallet.address == authorization.binding.androidAddress) {
            "Trusted personal node signer wallet differs from the authorized Android address."
        }
        require(proofSnapshot.proofCandidates.isNotEmpty()) {
            "Trusted personal node proof snapshot has no spend candidates."
        }

        val signed = DogecoinTransactionBuilder.createSignedTransactionFromVerifiedPrevouts(
            wallet = wallet,
            proofCandidates = proofSnapshot.proofCandidates,
            recipientAddress = recipientAddress,
            amount = amount,
            feePerKbKoinu = feePerKbKoinu
        )
        val proofByOutpoint = proofSnapshot.proofCandidates.associateBy {
            dogecoinTrustedPersonalNodeOutpointKey(it.verifiedPrevout.txid, it.verifiedPrevout.vout)
        }
        val selectedProofs = signed.selectedUtxos.map { selected ->
            proofByOutpoint[dogecoinTrustedPersonalNodeOutpointKey(selected.txid, selected.vout)]
                ?: throw IllegalStateException(
                    "Trusted personal node signer selected an input outside the proof snapshot."
                )
        }
        return DogecoinTrustedPersonalNodeFrozenReview.freeze(
            authorization = authorization,
            signed = signed,
            selectedProofCandidates = selectedProofs
        )
    }
}

private data class ParsedTrustedPersonalNodeInput(val txid: String, val vout: Int)

private data class ParsedTrustedPersonalNodeOutput(
    val amountKoinu: Long,
    val scriptPubKeyHex: String
)

private data class ParsedTrustedPersonalNodeTransaction(
    val inputs: List<ParsedTrustedPersonalNodeInput>,
    val outputs: List<ParsedTrustedPersonalNodeOutput>
)

private fun parseDogecoinTrustedPersonalNodeSignedTransaction(
    rawTransactionHex: String
): ParsedTrustedPersonalNodeTransaction {
    require(rawTransactionHex.isNotEmpty() && rawTransactionHex.length % 2 == 0) {
        "Trusted personal node signed transaction hex is malformed."
    }
    require(rawTransactionHex.length <= DOGECOIN_TPN_MAX_SIGNED_TRANSACTION_BYTES * 2) {
        "Trusted personal node signed transaction exceeds its size bound."
    }
    require(rawTransactionHex.all { it in '0'..'9' || it in 'a'..'f' }) {
        "Trusted personal node signed transaction must be canonical lowercase hexadecimal."
    }
    val bytes = DogecoinHex.decode(rawTransactionHex)
    val reader = TrustedPersonalNodeSignedReader(bytes)
    reader.skip(4, "version")
    val inputCount = reader.readCompactSize("input count")
    val maximumInputs = ((reader.remaining.toLong() - MIN_SIGNED_OUTPUT_SECTION_BYTES)
        .coerceAtLeast(0L)) / MIN_SIGNED_INPUT_BYTES
    require(inputCount > 0L && inputCount <= maximumInputs) {
        "Trusted personal node signed transaction input count is invalid."
    }
    val inputs = ArrayList<ParsedTrustedPersonalNodeInput>(inputCount.toInt())
    repeat(inputCount.toInt()) { index ->
        val previousTxid = DogecoinHex.encode(
            reader.readBytes(32, "input $index previous transaction id").reversedArray()
        )
        val vout = reader.readUnsignedLittleEndian(4, "input $index output index")
        require(vout <= Int.MAX_VALUE.toLong()) {
            "Trusted personal node signed transaction input output index is too large."
        }
        val scriptLength = reader.readCompactSize("input $index script length")
        require(scriptLength <= reader.remaining.toLong() - 4L) {
            "Trusted personal node signed transaction input script is truncated."
        }
        reader.skip(scriptLength.toInt(), "input $index script")
        reader.skip(4, "input $index sequence")
        inputs += ParsedTrustedPersonalNodeInput(previousTxid, vout.toInt())
    }

    val outputCount = reader.readCompactSize("output count")
    val maximumOutputs = ((reader.remaining.toLong() - SIGNED_LOCK_TIME_BYTES)
        .coerceAtLeast(0L)) / MIN_SIGNED_OUTPUT_BYTES
    require(outputCount > 0L && outputCount <= maximumOutputs) {
        "Trusted personal node signed transaction output count is invalid."
    }
    val outputs = ArrayList<ParsedTrustedPersonalNodeOutput>(outputCount.toInt())
    var outputTotal = 0L
    repeat(outputCount.toInt()) { index ->
        val amount = reader.readNonNegativeInt64("output $index amount")
        require(amount in 1L..DOGECOIN_TPN_MAX_MONEY_KOINU) {
            "Trusted personal node signed transaction output amount is invalid."
        }
        outputTotal = try {
            Math.addExact(outputTotal, amount)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Trusted personal node signed output total overflowed.")
        }
        require(outputTotal <= DOGECOIN_TPN_MAX_MONEY_KOINU) {
            "Trusted personal node signed output total exceeds Dogecoin MAX_MONEY."
        }
        val scriptLength = reader.readCompactSize("output $index script length")
        require(scriptLength > 0L && scriptLength <= reader.remaining.toLong() - 4L) {
            "Trusted personal node signed transaction output script is truncated."
        }
        outputs += ParsedTrustedPersonalNodeOutput(
            amountKoinu = amount,
            scriptPubKeyHex = DogecoinHex.encode(
                reader.readBytes(scriptLength.toInt(), "output $index script")
            )
        )
    }
    reader.skip(4, "lock time")
    require(reader.remaining == 0) {
        "Trusted personal node signed transaction has trailing bytes."
    }
    return ParsedTrustedPersonalNodeTransaction(inputs, outputs)
}

private class TrustedPersonalNodeSignedReader(private val bytes: ByteArray) {
    private var offset = 0
    val remaining: Int get() = bytes.size - offset

    fun readBytes(count: Int, field: String): ByteArray {
        require(count >= 0 && count <= remaining) {
            "Trusted personal node signed transaction ended while reading $field."
        }
        return bytes.copyOfRange(offset, offset + count).also { offset += count }
    }

    fun skip(count: Int, field: String) {
        readBytes(count, field)
    }

    fun readUnsignedLittleEndian(count: Int, field: String): Long {
        require(count in 1..8 && count <= remaining) {
            "Trusted personal node signed transaction ended while reading $field."
        }
        var value = 0L
        repeat(count) { index ->
            val part = bytes[offset + index].toLong() and 0xffL
            if (index == 7) {
                require(part <= 0x7fL) {
                    "Trusted personal node signed transaction $field is too large."
                }
            }
            value = value or (part shl (index * 8))
        }
        offset += count
        return value
    }

    fun readNonNegativeInt64(field: String): Long = readUnsignedLittleEndian(8, field)

    fun readCompactSize(field: String): Long {
        val first = readUnsignedLittleEndian(1, field).toInt()
        return when (first) {
            in 0..0xfc -> first.toLong()
            0xfd -> readUnsignedLittleEndian(2, field).also {
                require(it >= 0xfdL) {
                    "Trusted personal node signed transaction has non-canonical $field."
                }
            }
            0xfe -> readUnsignedLittleEndian(4, field).also {
                require(it > 0xffffL) {
                    "Trusted personal node signed transaction has non-canonical $field."
                }
            }
            else -> readUnsignedLittleEndian(8, field).also {
                require(it > 0xffffffffL) {
                    "Trusted personal node signed transaction has non-canonical $field."
                }
            }
        }
    }
}

private fun checkedTrustedPersonalNodeSum(values: List<Long>, message: String): Long =
    values.fold(0L) { sum, value ->
        require(value >= 0L) { message }
        try {
            Math.addExact(sum, value)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException(message)
        }
    }

private fun dogecoinTrustedPersonalNodeOutpointKey(txid: String, vout: Int): String =
    "${txid.lowercase()}:$vout"

private const val DOGECOIN_TPN_MAX_SIGNED_TRANSACTION_BYTES = 1_000_000
private const val MIN_SIGNED_INPUT_BYTES = 41L
private const val MIN_SIGNED_OUTPUT_BYTES = 9L
private const val SIGNED_LOCK_TIME_BYTES = 4L
private const val MIN_SIGNED_OUTPUT_SECTION_BYTES = 14L
