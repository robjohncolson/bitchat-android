package com.bitchat.android.features.dogecoin

import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest

data class DogecoinUtxo(
    val txid: String,
    val vout: Int,
    val amountKoinu: Long,
    val scriptPubKeyHex: String,
    val confirmations: Int
)

data class DogecoinSignedTransaction(
    val network: DogecoinNetwork,
    val recipientAddress: String,
    val rawTransactionHex: String,
    val txid: String,
    val createdAtMillis: Long,
    val inputTotalKoinu: Long,
    val sendAmountKoinu: Long,
    val feePerKbKoinu: Long,
    val feeKoinu: Long,
    val changeKoinu: Long,
    val changeAddress: String?,
    val selectedUtxos: List<DogecoinUtxo>,
    val mempoolAcceptance: DogecoinMempoolAcceptance? = null,
    val requestLabel: String? = null,
    val requestMessage: String? = null
) {
    init {
        require(inputTotalKoinu >= 0L) { "Dogecoin input total must be non-negative" }
        require(sendAmountKoinu >= 0L) { "Dogecoin send amount must be non-negative" }
        require(feePerKbKoinu >= 0L) { "Dogecoin fee rate must be non-negative" }
        require(feeKoinu >= 0L) { "Dogecoin fee must be non-negative" }
        require(changeKoinu >= 0L) { "Dogecoin change amount must be non-negative" }
    }

    val totalDebitKoinu: Long
        get() = dogecoinSaturatingAddKoinu(sendAmountKoinu, feeKoinu)

    fun isExpired(nowMillis: Long, maxAgeMillis: Long): Boolean {
        require(maxAgeMillis > 0L) { "Maximum transaction review age must be positive" }
        return nowMillis - createdAtMillis > maxAgeMillis
    }

    fun requiresHighFeeAcknowledgement(): Boolean {
        val relativeThreshold = dogecoinCeilDivKoinu(
            sendAmountKoinu,
            DogecoinProtocol.HIGH_FEE_RELATIVE_DENOMINATOR
        )
        return feeKoinu >= DogecoinProtocol.HIGH_FEE_ABSOLUTE_KOINU ||
            feeKoinu >= relativeThreshold
    }

    fun requiresPolicyUnavailableAcknowledgement(): Boolean {
        return network == DogecoinNetwork.MAINNET && mempoolAcceptance?.checked == false
    }

    fun hasConsistentRawTransactionId(): Boolean {
        return runCatching {
            DogecoinTransactionBuilder.transactionId(rawTransactionHex).equals(txid.trim(), ignoreCase = true)
        }.getOrDefault(false)
    }
}

internal fun canExportOrBroadcastSignedDogecoinTransaction(
    transaction: DogecoinSignedTransaction,
    nowMillis: Long,
    maxAgeMillis: Long,
    mainnetAcknowledged: Boolean,
    highFeeAcknowledged: Boolean,
    policyUnavailableAcknowledged: Boolean = false
): Boolean {
    return !transaction.isExpired(nowMillis, maxAgeMillis) &&
        transaction.hasConsistentRawTransactionId() &&
        (transaction.network != DogecoinNetwork.MAINNET || mainnetAcknowledged) &&
        (!transaction.requiresHighFeeAcknowledgement() || highFeeAcknowledged) &&
        (!transaction.requiresPolicyUnavailableAcknowledgement() || policyUnavailableAcknowledged)
}

internal fun DogecoinSignedTransaction.requireConsistentRawTransactionId() {
    require(hasConsistentRawTransactionId()) {
        "Signed Dogecoin transaction review is inconsistent. Refresh wallet balance and review the send again."
    }
}

internal fun canExportSignedRawDogecoinTransaction(
    transaction: DogecoinSignedTransaction,
    nowMillis: Long,
    maxAgeMillis: Long,
    mainnetAcknowledged: Boolean,
    highFeeAcknowledged: Boolean,
    policyUnavailableAcknowledged: Boolean = false,
    selectedNetwork: DogecoinNetwork,
    nodeReady: Boolean
): Boolean {
    return nodeReady &&
        transaction.network == selectedNetwork &&
        canExportOrBroadcastSignedDogecoinTransaction(
            transaction = transaction,
            nowMillis = nowMillis,
            maxAgeMillis = maxAgeMillis,
            mainnetAcknowledged = mainnetAcknowledged,
            highFeeAcknowledged = highFeeAcknowledged,
            policyUnavailableAcknowledged = policyUnavailableAcknowledged
        )
}

internal fun DogecoinSignedTransaction.requireSelectedInputsStillSpendable(
    currentUtxos: List<DogecoinUtxo>
) {
    require(selectedUtxos.isNotEmpty()) {
        "Signed Dogecoin transaction has no recorded selected inputs. Refresh wallet balance and review the send again."
    }

    val duplicateOutpoint = currentUtxos
        .groupingBy { dogecoinOutpointKey(it) }
        .eachCount()
        .entries
        .firstOrNull { it.value > 1 }
    require(duplicateOutpoint == null) {
        "Dogecoin node reported duplicate UTXO outpoint ${duplicateOutpoint?.key}. Refresh wallet balance and review the send again."
    }

    val currentByOutpoint = currentUtxos.associateBy { dogecoinOutpointKey(it) }
    selectedUtxos.forEach { selected ->
        val current = currentByOutpoint[dogecoinOutpointKey(selected)]
            ?: throw IllegalStateException(
                "Selected Dogecoin input ${dogecoinOutpointKey(selected)} is no longer spendable. " +
                    "Refresh wallet balance and review the send again."
            )
        require(current.confirmations > 0) {
            "Selected Dogecoin input ${dogecoinOutpointKey(selected)} is no longer confirmed. " +
                "Refresh wallet balance and review the send again."
        }
        require(current.amountKoinu == selected.amountKoinu) {
            "Selected Dogecoin input ${dogecoinOutpointKey(selected)} amount changed. " +
                "Refresh wallet balance and review the send again."
        }
        require(current.scriptPubKeyHex.trim().lowercase() == selected.scriptPubKeyHex.trim().lowercase()) {
            "Selected Dogecoin input ${dogecoinOutpointKey(selected)} script changed. " +
                "Refresh wallet balance and review the send again."
        }
    }
}

private fun dogecoinOutpointKey(utxo: DogecoinUtxo): String {
    return "${utxo.txid.trim().lowercase()}:${utxo.vout}"
}

internal fun dogecoinSaturatingAddKoinu(left: Long, right: Long): Long {
    require(left >= 0L && right >= 0L) { "Dogecoin amounts must be non-negative" }
    return try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}

internal fun dogecoinEffectiveStandardOutputKoinu(nodeSoftDustLimitKoinu: Long?): Long {
    nodeSoftDustLimitKoinu?.let {
        require(it > 0L) { "Dogecoin soft dust limit must be positive" }
    }
    return maxOf(
        DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU,
        nodeSoftDustLimitKoinu ?: DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU
    )
}

private fun dogecoinCeilDivKoinu(value: Long, denominator: Long): Long {
    require(value >= 0L) { "Dogecoin amount must be non-negative" }
    require(denominator > 0L) { "Dogecoin divisor must be positive" }
    return value / denominator + if (value % denominator == 0L) 0L else 1L
}

data class DogecoinMaxSpend(
    val amountKoinu: Long,
    val feeKoinu: Long,
    val inputTotalKoinu: Long,
    val selectedUtxos: List<DogecoinUtxo>
)

object DogecoinTransactionBuilder {
    private const val VERSION = 1L
    private const val LOCK_TIME = 0L
    private const val SEQUENCE_FINAL = 0xffffffffL
    private const val SIGHASH_ALL = 1
    private const val MAX_DER_SIGNATURE_WITH_SIGHASH_SIZE = 73L
    private const val P2PKH_OUTPUT_SIZE_BYTES = 34L
    private val txidRegex = Regex("^[0-9a-fA-F]{64}$")

    private val curve = CustomNamedCurves.getByName("secp256k1")
    private val params = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)

    fun createSignedTransaction(
        wallet: DogecoinWalletKey,
        utxos: List<DogecoinUtxo>,
        recipientAddress: String,
        amount: String,
        network: DogecoinNetwork = wallet.network,
        feePerKbKoinu: Long = DogecoinProtocol.DEFAULT_FEE_PER_KB_KOINU,
        minimumOutputKoinu: Long = DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU
    ): DogecoinSignedTransaction {
        require(wallet.network == network) {
            "Wallet key belongs to Dogecoin ${wallet.network.displayName}, not ${network.displayName}"
        }
        require(DogecoinAddress.isValidP2pkhAddress(wallet.address, network)) {
            "Wallet address is not a Dogecoin ${network.displayName} P2PKH address"
        }
        require(DogecoinAddress.isValidAddress(recipientAddress, network)) {
            "Recipient must be a Dogecoin ${network.displayName} address"
        }
        require(feePerKbKoinu >= DogecoinProtocol.MIN_TX_FEE_KOINU) {
            "Fee rate must be at least ${DogecoinAmount.formatKoinu(DogecoinProtocol.MIN_TX_FEE_KOINU)} DOGE/kB"
        }

        val effectiveMinimumOutputKoinu = dogecoinEffectiveStandardOutputKoinu(minimumOutputKoinu)
        val sendAmountKoinu = DogecoinAmount.toKoinu(amount)
        require(sendAmountKoinu >= effectiveMinimumOutputKoinu) {
            "Dogecoin output amount must be at least " +
                "${DogecoinAmount.formatKoinu(effectiveMinimumOutputKoinu)} DOGE"
        }
        val spendPlan = selectInputs(
            wallet = wallet,
            utxos = utxos,
            sendAmountKoinu = sendAmountKoinu,
            network = network,
            feePerKbKoinu = feePerKbKoinu,
            minimumOutputKoinu = effectiveMinimumOutputKoinu
        )
        val publicKey = DogecoinHex.decode(wallet.publicKeyHex)
        val privateKey = DogecoinHex.decode(wallet.privateKeyHex)

        val unsignedInputs = spendPlan.selectedUtxos.map {
            TransactionInput(
                previousTxIdLittleEndian = txidToLittleEndian(it.txid),
                outputIndex = it.vout.toLong(),
                scriptSig = ByteArray(0),
                sequence = SEQUENCE_FINAL
            )
        }

        val outputs = mutableListOf(
            TransactionOutput(
                amountKoinu = sendAmountKoinu,
                scriptPubKey = DogecoinAddress.scriptPubKey(recipientAddress, network)
            )
        )
        if (spendPlan.changeKoinu > 0) {
            outputs.add(
                TransactionOutput(
                    amountKoinu = spendPlan.changeKoinu,
                    scriptPubKey = DogecoinAddress.p2pkhScript(wallet.address, network)
                )
            )
        }

        val signedInputs = unsignedInputs.mapIndexed { index, input ->
            val scriptCode = DogecoinHex.decode(spendPlan.selectedUtxos[index].scriptPubKeyHex)
            val sighash = signatureHash(unsignedInputs, outputs, index, scriptCode)
            val signature = sign(privateKey, sighash) + byteArrayOf(SIGHASH_ALL.toByte())
            input.copy(scriptSig = pushData(signature) + pushData(publicKey))
        }

        val rawBytes = serializeTransaction(signedInputs, outputs)
        val rawHex = DogecoinHex.encode(rawBytes)
        return DogecoinSignedTransaction(
            network = network,
            recipientAddress = recipientAddress,
            rawTransactionHex = rawHex,
            txid = transactionId(rawHex),
            createdAtMillis = System.currentTimeMillis(),
            inputTotalKoinu = spendPlan.inputTotalKoinu,
            sendAmountKoinu = sendAmountKoinu,
            feePerKbKoinu = feePerKbKoinu,
            feeKoinu = spendPlan.feeKoinu,
            changeKoinu = spendPlan.changeKoinu,
            changeAddress = wallet.address.takeIf { spendPlan.changeKoinu > 0 },
            selectedUtxos = spendPlan.selectedUtxos
        )
    }

    fun transactionId(rawTransactionHex: String): String {
        return DogecoinHex.encode(doubleSha256(DogecoinHex.decode(rawTransactionHex)).reversedArray())
    }

    fun maxSpendable(
        wallet: DogecoinWalletKey,
        utxos: List<DogecoinUtxo>,
        network: DogecoinNetwork = wallet.network,
        feePerKbKoinu: Long = DogecoinProtocol.DEFAULT_FEE_PER_KB_KOINU,
        minimumOutputKoinu: Long = DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU
    ): DogecoinMaxSpend {
        require(wallet.network == network) {
            "Wallet key belongs to Dogecoin ${wallet.network.displayName}, not ${network.displayName}"
        }
        require(DogecoinAddress.isValidP2pkhAddress(wallet.address, network)) {
            "Wallet address is not a Dogecoin ${network.displayName} P2PKH address"
        }
        require(feePerKbKoinu >= DogecoinProtocol.MIN_TX_FEE_KOINU) {
            "Fee rate must be at least ${DogecoinAmount.formatKoinu(DogecoinProtocol.MIN_TX_FEE_KOINU)} DOGE/kB"
        }

        val spendable = spendableUtxos(wallet, utxos, network)
        require(spendable.isNotEmpty()) {
            "No confirmed spendable Dogecoin ${network.displayName} UTXOs found for this wallet address"
        }

        val effectiveMinimumOutputKoinu = dogecoinEffectiveStandardOutputKoinu(minimumOutputKoinu)
        val inputTotal = sumKoinu(spendable.map { it.amountKoinu })
        val fee = estimateFeeKoinu(
            inputCount = spendable.size,
            outputCount = 1,
            feePerKbKoinu = feePerKbKoinu,
            inputSizeBytes = estimatedP2pkhInputSize(wallet)
        )
        val maxAmount = inputTotal - fee
        val minimumSpendableAmount = addKoinu(
            fee,
            effectiveMinimumOutputKoinu,
            "Dogecoin fee is too large to create a standard output."
        )
        require(maxAmount >= effectiveMinimumOutputKoinu) {
            "Insufficient ${network.displayName} DOGE to create a standard output after fee. " +
                "Need at least ${DogecoinAmount.formatKoinu(minimumSpendableAmount)}."
        }

        return DogecoinMaxSpend(
            amountKoinu = maxAmount,
            feeKoinu = fee,
            inputTotalKoinu = inputTotal,
            selectedUtxos = spendable
        )
    }

    fun estimateFeeForSelection(
        wallet: DogecoinWalletKey,
        utxos: List<DogecoinUtxo>,
        sendAmountKoinu: Long,
        network: DogecoinNetwork = wallet.network,
        feePerKbKoinu: Long = DogecoinProtocol.DEFAULT_FEE_PER_KB_KOINU,
        minimumOutputKoinu: Long = DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU
    ): Long {
        require(wallet.network == network) {
            "Wallet key belongs to Dogecoin ${wallet.network.displayName}, not ${network.displayName}"
        }
        require(DogecoinAddress.isValidP2pkhAddress(wallet.address, network)) {
            "Wallet address is not a Dogecoin ${network.displayName} P2PKH address"
        }
        require(feePerKbKoinu >= DogecoinProtocol.MIN_TX_FEE_KOINU) {
            "Fee rate must be at least ${DogecoinAmount.formatKoinu(DogecoinProtocol.MIN_TX_FEE_KOINU)} DOGE/kB"
        }

        val effectiveMinimumOutputKoinu = dogecoinEffectiveStandardOutputKoinu(minimumOutputKoinu)
        require(sendAmountKoinu >= effectiveMinimumOutputKoinu) {
            "Dogecoin output amount must be at least " +
                "${DogecoinAmount.formatKoinu(effectiveMinimumOutputKoinu)} DOGE"
        }
        val spendPlan = selectInputs(
            wallet = wallet,
            utxos = utxos,
            sendAmountKoinu = sendAmountKoinu,
            network = network,
            feePerKbKoinu = feePerKbKoinu,
            minimumOutputKoinu = effectiveMinimumOutputKoinu
        )
        return spendPlan.feeKoinu
    }

    fun estimateFeeForSelection(
        wallet: DogecoinWalletKey,
        inputCount: Int,
        outputCount: Int,
        feePerKbKoinu: Long = DogecoinProtocol.DEFAULT_FEE_PER_KB_KOINU
    ): Long {
        require(feePerKbKoinu >= DogecoinProtocol.MIN_TX_FEE_KOINU) {
            "Fee rate must be at least ${DogecoinAmount.formatKoinu(DogecoinProtocol.MIN_TX_FEE_KOINU)} DOGE/kB"
        }
        return estimateFeeKoinu(
            inputCount = inputCount,
            outputCount = outputCount,
            feePerKbKoinu = feePerKbKoinu,
            inputSizeBytes = estimatedP2pkhInputSize(wallet)
        )
    }

    private fun selectInputs(
        wallet: DogecoinWalletKey,
        utxos: List<DogecoinUtxo>,
        sendAmountKoinu: Long,
        network: DogecoinNetwork,
        feePerKbKoinu: Long,
        minimumOutputKoinu: Long
    ): SpendPlan {
        val spendable = spendableUtxos(wallet, utxos, network)
        val inputSizeBytes = estimatedP2pkhInputSize(wallet)

        require(spendable.isNotEmpty()) {
            "No confirmed spendable Dogecoin ${network.displayName} UTXOs found for this wallet address"
        }

        val selected = mutableListOf<DogecoinUtxo>()
        var inputTotal = 0L
        for (utxo in spendable) {
            selected.add(utxo)
            inputTotal = addKoinu(inputTotal, utxo.amountKoinu, "Selected Dogecoin input total is too large.")

            val feeWithChange = estimateFeeKoinu(
                inputCount = selected.size,
                outputCount = 2,
                feePerKbKoinu = feePerKbKoinu,
                inputSizeBytes = inputSizeBytes
            )
            val totalNeededWithChange = addKoinu(
                sendAmountKoinu,
                feeWithChange,
                "Dogecoin send amount and fee are too large."
            )
            val changeWithChange = if (inputTotal >= totalNeededWithChange) {
                inputTotal - totalNeededWithChange
            } else {
                -1L
            }
            if (changeWithChange >= minimumOutputKoinu) {
                return SpendPlan(
                    selectedUtxos = selected.toList(),
                    inputTotalKoinu = inputTotal,
                    feeKoinu = feeWithChange,
                    changeKoinu = changeWithChange
                )
            }

            val minimumNoChangeFee = estimateFeeKoinu(
                inputCount = selected.size,
                outputCount = 1,
                feePerKbKoinu = feePerKbKoinu,
                inputSizeBytes = inputSizeBytes
            )
            val totalNeededNoChange = addKoinu(
                sendAmountKoinu,
                minimumNoChangeFee,
                "Dogecoin send amount and fee are too large."
            )
            if (inputTotal >= totalNeededNoChange) {
                return SpendPlan(
                    selectedUtxos = selected.toList(),
                    inputTotalKoinu = inputTotal,
                    feeKoinu = inputTotal - sendAmountKoinu,
                    changeKoinu = 0L
                )
            }
        }

        val needed = addKoinu(
            sendAmountKoinu,
            estimateFeeKoinu(
                inputCount = selected.size.coerceAtLeast(1),
                outputCount = 1,
                feePerKbKoinu = feePerKbKoinu,
                inputSizeBytes = inputSizeBytes
            ),
            "Dogecoin send amount and fee are too large."
        )
        throw IllegalStateException(
            "Insufficient ${network.displayName} DOGE. Need at least ${DogecoinAmount.formatKoinu(needed)}, " +
            "available ${DogecoinAmount.formatKoinu(inputTotal)}."
        )
    }

    private fun spendableUtxos(
        wallet: DogecoinWalletKey,
        utxos: List<DogecoinUtxo>,
        network: DogecoinNetwork
    ): List<DogecoinUtxo> {
        val expectedScript = DogecoinHex.encode(DogecoinAddress.p2pkhScript(wallet.address, network))
        val candidates = utxos
            .filter {
                it.amountKoinu > 0 &&
                    it.vout >= 0 &&
                    it.confirmations > 0 &&
                    it.scriptPubKeyHex.trim().lowercase() == expectedScript
            }

        candidates.forEach { utxo ->
            require(txidRegex.matches(utxo.txid.trim())) {
                "Dogecoin UTXO txid must be 64 hex characters."
            }
        }

        val duplicateOutpoint = candidates
            .groupingBy { "${it.txid.trim().lowercase()}:${it.vout}" }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
        require(duplicateOutpoint == null) {
            "Duplicate Dogecoin UTXO outpoint reported by node: ${duplicateOutpoint?.key}"
        }

        return candidates
            .sortedWith(
                compareByDescending<DogecoinUtxo> { it.amountKoinu }
                    .thenByDescending { it.confirmations }
                    .thenBy { it.txid }
                    .thenBy { it.vout }
            )
    }

    private fun estimatedP2pkhInputSize(wallet: DogecoinWalletKey): Long {
        val publicKeySize = DogecoinHex.decode(wallet.publicKeyHex).size.toLong()
        val signaturePushSize = 1L + MAX_DER_SIGNATURE_WITH_SIGHASH_SIZE
        val publicKeyPushSize = 1L + publicKeySize
        val scriptSigSize = signaturePushSize + publicKeyPushSize
        return 32L + 4L + 1L + scriptSigSize + 4L
    }

    private fun estimateFeeKoinu(
        inputCount: Int,
        outputCount: Int,
        feePerKbKoinu: Long,
        inputSizeBytes: Long
    ): Long {
        require(inputCount > 0) { "Transaction must have at least one input" }
        require(outputCount > 0) { "Transaction must have at least one output" }
        val inputBytes = multiplyKoinu(inputCount.toLong(), inputSizeBytes, "Dogecoin input fee size is too large.")
        val outputBytes = multiplyKoinu(
            outputCount.toLong(),
            P2PKH_OUTPUT_SIZE_BYTES,
            "Dogecoin output fee size is too large."
        )
        val estimatedBytes = addKoinu(
            addKoinu(10L, inputBytes, "Dogecoin fee size is too large."),
            outputBytes,
            "Dogecoin fee size is too large."
        )
        val feeNumerator = addKoinu(
            multiplyKoinu(
                estimatedBytes,
                feePerKbKoinu,
                "Dogecoin fee rate is too high for this transaction size."
            ),
            999L,
            "Dogecoin fee rate is too high for this transaction size."
        )
        val fee = feeNumerator / 1000L
        return maxOf(fee, DogecoinProtocol.MIN_TX_FEE_KOINU)
    }

    private fun sumKoinu(values: List<Long>): Long {
        return values.fold(0L) { total, value ->
            addKoinu(total, value, "Dogecoin input total is too large.")
        }
    }

    private fun addKoinu(left: Long, right: Long, message: String): Long {
        return try {
            Math.addExact(left, right)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException(message)
        }
    }

    private fun multiplyKoinu(left: Long, right: Long, message: String): Long {
        return try {
            Math.multiplyExact(left, right)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException(message)
        }
    }

    private fun signatureHash(
        inputs: List<TransactionInput>,
        outputs: List<TransactionOutput>,
        inputIndex: Int,
        scriptCode: ByteArray
    ): ByteArray {
        val signingInputs = inputs.mapIndexed { index, input ->
            if (index == inputIndex) {
                input.copy(scriptSig = scriptCode)
            } else {
                input.copy(scriptSig = ByteArray(0))
            }
        }
        return doubleSha256(
            serializeTransaction(signingInputs, outputs) + uint32LittleEndian(SIGHASH_ALL.toLong())
        )
    }

    private fun sign(privateKey: ByteArray, hash: ByteArray): ByteArray {
        val privateKeyValue = BigInteger(1, privateKey)
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(privateKeyValue, params))
        val components = signer.generateSignature(hash)
        val r = components[0]
        var s = components[1]
        val halfCurveOrder = params.n.shiftRight(1)
        if (s > halfCurveOrder) {
            s = params.n.subtract(s)
        }

        val vector = ASN1EncodableVector()
        vector.add(ASN1Integer(r))
        vector.add(ASN1Integer(s))
        return DERSequence(vector).encoded
    }

    private fun serializeTransaction(
        inputs: List<TransactionInput>,
        outputs: List<TransactionOutput>
    ): ByteArray {
        val stream = ByteArrayOutputStream()
        stream.write(uint32LittleEndian(VERSION))
        stream.writeVarInt(inputs.size.toLong())
        inputs.forEach { input ->
            stream.write(input.previousTxIdLittleEndian)
            stream.write(uint32LittleEndian(input.outputIndex))
            stream.writeVarInt(input.scriptSig.size.toLong())
            stream.write(input.scriptSig)
            stream.write(uint32LittleEndian(input.sequence))
        }
        stream.writeVarInt(outputs.size.toLong())
        outputs.forEach { output ->
            stream.write(int64LittleEndian(output.amountKoinu))
            stream.writeVarInt(output.scriptPubKey.size.toLong())
            stream.write(output.scriptPubKey)
        }
        stream.write(uint32LittleEndian(LOCK_TIME))
        return stream.toByteArray()
    }

    private fun txidToLittleEndian(txid: String): ByteArray {
        val bytes = DogecoinHex.decode(txid)
        require(bytes.size == 32) { "Transaction id must be 32 bytes" }
        return bytes.reversedArray()
    }

    private fun pushData(data: ByteArray): ByteArray {
        require(data.size < 76) { "Only small pushdata values are supported" }
        return byteArrayOf(data.size.toByte()) + data
    }

    private fun uint32LittleEndian(value: Long): ByteArray {
        require(value in 0..0xffffffffL) { "Value must fit uint32" }
        return ByteArray(4) { index -> ((value shr (index * 8)) and 0xff).toByte() }
    }

    private fun int64LittleEndian(value: Long): ByteArray {
        require(value >= 0) { "Value must be non-negative" }
        return ByteArray(8) { index -> ((value shr (index * 8)) and 0xff).toByte() }
    }

    private fun doubleSha256(bytes: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(digest.digest(bytes))
    }

    private fun ByteArrayOutputStream.writeVarInt(value: Long) {
        require(value >= 0) { "VarInt value must be non-negative" }
        when {
            value < 0xfdL -> write(value.toInt())
            value <= 0xffffL -> {
                write(0xfd)
                write(byteArrayOf((value and 0xff).toByte(), ((value shr 8) and 0xff).toByte()))
            }
            value <= 0xffffffffL -> {
                write(0xfe)
                write(uint32LittleEndian(value))
            }
            else -> {
                write(0xff)
                write(int64LittleEndian(value))
            }
        }
    }

    private data class SpendPlan(
        val selectedUtxos: List<DogecoinUtxo>,
        val inputTotalKoinu: Long,
        val feeKoinu: Long,
        val changeKoinu: Long
    )

    private data class TransactionInput(
        val previousTxIdLittleEndian: ByteArray,
        val outputIndex: Long,
        val scriptSig: ByteArray,
        val sequence: Long
    )

    private data class TransactionOutput(
        val amountKoinu: Long,
        val scriptPubKey: ByteArray
    )
}
