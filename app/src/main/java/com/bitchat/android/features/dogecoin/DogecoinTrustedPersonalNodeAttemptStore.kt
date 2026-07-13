package com.bitchat.android.features.dogecoin

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

internal enum class DogecoinTrustedPersonalNodeAttemptState {
    READY_UNDISCLOSED,
    SUBMISSION_UNKNOWN,
    CLAIMED
}

/** A compact durable reference to one proof-backed input; full previous transactions stay in memory. */
internal class DogecoinTrustedPersonalNodeAttemptProofReference private constructor(
    val txid: String,
    val vout: Int,
    val amountKoinu: Long,
    val scriptPubKeyHex: String,
    val previousTransactionByteCount: Int,
    val source: DogecoinTrustedPersonalNodePreviousTransactionSource
) {
    override fun toString(): String =
        "DogecoinTrustedPersonalNodeAttemptProofReference(outpoint=<redacted>)"

    internal fun sameAs(other: DogecoinTrustedPersonalNodeAttemptProofReference): Boolean =
        txid == other.txid &&
            vout == other.vout &&
            amountKoinu == other.amountKoinu &&
            scriptPubKeyHex == other.scriptPubKeyHex &&
            previousTransactionByteCount == other.previousTransactionByteCount &&
            source == other.source

    internal fun toJson(): JSONObject = JSONObject()
        .put(KEY_TXID, txid)
        .put(KEY_VOUT, vout)
        .put(KEY_AMOUNT, amountKoinu.toString())
        .put(KEY_SCRIPT, scriptPubKeyHex)
        .put(KEY_PREVIOUS_TRANSACTION_BYTES, previousTransactionByteCount)
        .put(KEY_SOURCE, source.name)

    companion object {
        fun fromVerifiedPrevout(
            prevout: DogecoinVerifiedPrevout
        ): DogecoinTrustedPersonalNodeAttemptProofReference = validated(
            txid = prevout.txid,
            vout = prevout.vout,
            amountKoinu = prevout.amountKoinu,
            scriptPubKeyHex = prevout.scriptPubKeyHex,
            previousTransactionByteCount = prevout.previousTransactionByteCount,
            source = prevout.source
        )

        internal fun fromJson(json: JSONObject): DogecoinTrustedPersonalNodeAttemptProofReference {
            json.requireExactKeys(
                KEY_TXID,
                KEY_VOUT,
                KEY_AMOUNT,
                KEY_SCRIPT,
                KEY_PREVIOUS_TRANSACTION_BYTES,
                KEY_SOURCE
            )
            val sourceName = json.requireString(KEY_SOURCE)
            val source = DogecoinTrustedPersonalNodePreviousTransactionSource.values()
                .firstOrNull { it.name == sourceName }
                ?: throw IllegalArgumentException("Unknown trusted personal node proof source.")
            return validated(
                txid = json.requireString(KEY_TXID),
                vout = json.requireInt(KEY_VOUT),
                amountKoinu = json.requireCanonicalLongString(KEY_AMOUNT),
                scriptPubKeyHex = json.requireString(KEY_SCRIPT),
                previousTransactionByteCount = json.requireInt(KEY_PREVIOUS_TRANSACTION_BYTES),
                source = source
            )
        }

        private fun validated(
            txid: String,
            vout: Int,
            amountKoinu: Long,
            scriptPubKeyHex: String,
            previousTransactionByteCount: Int,
            source: DogecoinTrustedPersonalNodePreviousTransactionSource
        ): DogecoinTrustedPersonalNodeAttemptProofReference {
            require(EXACT_LOWER_TXID.matches(txid)) { "Attempt proof txid is invalid." }
            require(vout >= 0) { "Attempt proof output index is invalid." }
            require(amountKoinu in 1..DOGECOIN_TPN_MAX_MONEY_KOINU) {
                "Attempt proof amount is invalid."
            }
            require(EXACT_LOWER_HEX.matches(scriptPubKeyHex) && scriptPubKeyHex.length == 50) {
                "Attempt proof script is invalid."
            }
            require(previousTransactionByteCount in 1..DOGECOIN_TPN_MAX_PREVIOUS_TRANSACTION_BYTES) {
                "Attempt previous-transaction byte count is invalid."
            }
            return DogecoinTrustedPersonalNodeAttemptProofReference(
                txid = txid,
                vout = vout,
                amountKoinu = amountKoinu,
                scriptPubKeyHex = scriptPubKeyHex,
                previousTransactionByteCount = previousTransactionByteCount,
                source = source
            )
        }

        private const val KEY_TXID = "txid"
        private const val KEY_VOUT = "vout"
        private const val KEY_AMOUNT = "amount_koinu"
        private const val KEY_SCRIPT = "script_pub_key_hex"
        private const val KEY_PREVIOUS_TRANSACTION_BYTES = "previous_transaction_bytes"
        private const val KEY_SOURCE = "source"
    }
}

/**
 * Exact durable projection of the canonical [DogecoinTrustedPersonalNodeFrozenReview], created before
 * the first signed-byte disclosure. It retains compact references to verified previous outputs rather
 * than persisting the up-to-4-MiB full proof body.
 */
internal class DogecoinTrustedPersonalNodeAttemptReviewRecord private constructor(
    val binding: DogecoinTrustedPersonalNodeSessionBinding,
    val signedRawTransactionHex: String,
    val localTxid: String,
    val recipientAddress: String,
    val sendAmountKoinu: Long,
    val feePerKbKoinu: Long,
    val feeKoinu: Long,
    val changeKoinu: Long,
    val inputTotalKoinu: Long,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val mainnetAcknowledged: Boolean,
    val personalNodeOracleAcknowledged: Boolean,
    val startTip: DogecoinTrustedPersonalNodeBlockTip,
    val endTip: DogecoinTrustedPersonalNodeBlockTip,
    proofReferences: List<DogecoinTrustedPersonalNodeAttemptProofReference>
) {
    val proofReferences: List<DogecoinTrustedPersonalNodeAttemptProofReference> =
        proofReferences.toList()

    override fun toString(): String =
        "DogecoinTrustedPersonalNodeFrozenReview(binding=<redacted>, signedBytes=<redacted>, " +
            "proofReferences=<redacted>)"

    internal fun sameAs(other: DogecoinTrustedPersonalNodeAttemptReviewRecord): Boolean =
        binding == other.binding &&
            signedRawTransactionHex == other.signedRawTransactionHex &&
            localTxid == other.localTxid &&
            recipientAddress == other.recipientAddress &&
            sendAmountKoinu == other.sendAmountKoinu &&
            feePerKbKoinu == other.feePerKbKoinu &&
            feeKoinu == other.feeKoinu &&
            changeKoinu == other.changeKoinu &&
            inputTotalKoinu == other.inputTotalKoinu &&
            createdAtMillis == other.createdAtMillis &&
            expiresAtMillis == other.expiresAtMillis &&
            mainnetAcknowledged == other.mainnetAcknowledged &&
            personalNodeOracleAcknowledged == other.personalNodeOracleAcknowledged &&
            startTip == other.startTip &&
            endTip == other.endTip &&
            proofReferences.size == other.proofReferences.size &&
            proofReferences.zip(other.proofReferences).all { (left, right) -> left.sameAs(right) }

    /**
     * A recovery must collect a new proof at a new tip, but it may disclose only the already-persisted
     * bytes. Thus retry identity deliberately ignores the old/new proof tip pair while retaining every
     * signed field, profile revision, and hash-committed prevout reference exactly.
     */
    internal fun sameSignedAttemptAs(
        review: DogecoinTrustedPersonalNodeFrozenReview,
        mainnetAcknowledged: Boolean,
        personalNodeOracleAcknowledged: Boolean
    ): Boolean {
        if (!mainnetAcknowledged || !personalNodeOracleAcknowledged) return false
        if (runCatching { review.requireRevalidated() }.isFailure) return false
        val currentReferences = review.selectedPrevouts.map(
            DogecoinTrustedPersonalNodeAttemptProofReference::fromVerifiedPrevout
        )
        return binding == review.binding &&
            signedRawTransactionHex == review.rawTransactionHex &&
            localTxid == review.txid &&
            recipientAddress == review.recipientAddress &&
            sendAmountKoinu == review.sendAmountKoinu &&
            feePerKbKoinu == review.feePerKbKoinu &&
            feeKoinu == review.feeKoinu &&
            changeKoinu == review.changeKoinu &&
            inputTotalKoinu == review.inputTotalKoinu &&
            createdAtMillis == review.createdAtMillis &&
            proofReferences.size == currentReferences.size &&
            proofReferences.zip(currentReferences).all { (left, right) -> left.sameAs(right) }
    }

    internal fun toJson(): JSONObject {
        val proofs = JSONArray()
        proofReferences.forEach { proofs.put(it.toJson()) }
        return JSONObject()
            .put(KEY_ORIGIN, binding.origin)
            .put(KEY_NETWORK, binding.network.id)
            .put(KEY_ANDROID_ADDRESS, binding.androidAddress)
            .put(KEY_CORE_WALLET_ID, binding.coreWalletId)
            .put(KEY_POLICY_VERSION, binding.policyVersion)
            .put(KEY_PROFILE_REVISION, binding.profileRevision.toString())
            .put(KEY_RAW_TRANSACTION, signedRawTransactionHex)
            .put(KEY_LOCAL_TXID, localTxid)
            .put(KEY_RECIPIENT, recipientAddress)
            .put(KEY_SEND_AMOUNT, sendAmountKoinu.toString())
            .put(KEY_FEE_PER_KB, feePerKbKoinu.toString())
            .put(KEY_FEE, feeKoinu.toString())
            .put(KEY_CHANGE, changeKoinu.toString())
            .put(KEY_INPUT_TOTAL, inputTotalKoinu.toString())
            .put(KEY_CREATED_AT, createdAtMillis.toString())
            .put(KEY_EXPIRES_AT, expiresAtMillis.toString())
            .put(KEY_MAINNET_ACKNOWLEDGED, mainnetAcknowledged)
            .put(KEY_ORACLE_ACKNOWLEDGED, personalNodeOracleAcknowledged)
            .put(KEY_START_HEIGHT, startTip.height)
            .put(KEY_START_HASH, startTip.hash)
            .put(KEY_END_HEIGHT, endTip.height)
            .put(KEY_END_HASH, endTip.hash)
            .put(KEY_PROOFS, proofs)
    }

    companion object {
        fun fromFrozenReview(
            review: DogecoinTrustedPersonalNodeFrozenReview,
            mainnetAcknowledged: Boolean,
            personalNodeOracleAcknowledged: Boolean,
            expiresAtMillis: Long = Math.addExact(
                review.createdAtMillis,
                DOGECOIN_SIGNED_TX_MAX_AGE_MILLIS
            )
        ): DogecoinTrustedPersonalNodeAttemptReviewRecord {
            require(mainnetAcknowledged && personalNodeOracleAcknowledged) {
                "Both mainnet and personal-node acknowledgements are required."
            }
            review.requireRevalidated()
            val references = review.selectedPrevouts.map(
                DogecoinTrustedPersonalNodeAttemptProofReference::fromVerifiedPrevout
            )
            return validated(
                binding = review.binding,
                signedRawTransactionHex = review.rawTransactionHex,
                localTxid = review.txid,
                recipientAddress = review.recipientAddress,
                sendAmountKoinu = review.sendAmountKoinu,
                feePerKbKoinu = review.feePerKbKoinu,
                feeKoinu = review.feeKoinu,
                changeKoinu = review.changeKoinu,
                inputTotalKoinu = review.inputTotalKoinu,
                createdAtMillis = review.createdAtMillis,
                expiresAtMillis = expiresAtMillis,
                mainnetAcknowledged = true,
                personalNodeOracleAcknowledged = true,
                startTip = review.proofSnapshot.startTip,
                endTip = review.proofSnapshot.endTip,
                proofReferences = references
            )
        }

        internal fun fromJson(json: JSONObject): DogecoinTrustedPersonalNodeAttemptReviewRecord {
            json.requireExactKeys(
                KEY_ORIGIN,
                KEY_NETWORK,
                KEY_ANDROID_ADDRESS,
                KEY_CORE_WALLET_ID,
                KEY_POLICY_VERSION,
                KEY_PROFILE_REVISION,
                KEY_RAW_TRANSACTION,
                KEY_LOCAL_TXID,
                KEY_RECIPIENT,
                KEY_SEND_AMOUNT,
                KEY_FEE_PER_KB,
                KEY_FEE,
                KEY_CHANGE,
                KEY_INPUT_TOTAL,
                KEY_CREATED_AT,
                KEY_EXPIRES_AT,
                KEY_MAINNET_ACKNOWLEDGED,
                KEY_ORACLE_ACKNOWLEDGED,
                KEY_START_HEIGHT,
                KEY_START_HASH,
                KEY_END_HEIGHT,
                KEY_END_HASH,
                KEY_PROOFS
            )
            val network = DogecoinNetwork.values().firstOrNull {
                it.id == json.requireString(KEY_NETWORK)
            } ?: throw IllegalArgumentException("Unknown frozen-review network.")
            val proofArray = json.requireArray(KEY_PROOFS)
            require(proofArray.length() in 1..DOGECOIN_TPN_MAX_PROOF_CANDIDATES) {
                "Frozen-review proof count is invalid."
            }
            val references = (0 until proofArray.length()).map { index ->
                val value = proofArray.get(index)
                require(value is JSONObject) { "Frozen-review proof entry is invalid." }
                DogecoinTrustedPersonalNodeAttemptProofReference.fromJson(value)
            }
            return validated(
                binding = DogecoinTrustedPersonalNodeSessionBinding(
                    origin = json.requireString(KEY_ORIGIN),
                    network = network,
                    androidAddress = json.requireString(KEY_ANDROID_ADDRESS),
                    coreWalletId = json.requireString(KEY_CORE_WALLET_ID),
                    policyVersion = json.requireInt(KEY_POLICY_VERSION),
                    profileRevision = json.requireCanonicalLongString(KEY_PROFILE_REVISION)
                ),
                signedRawTransactionHex = json.requireString(KEY_RAW_TRANSACTION),
                localTxid = json.requireString(KEY_LOCAL_TXID),
                recipientAddress = json.requireString(KEY_RECIPIENT),
                sendAmountKoinu = json.requireCanonicalLongString(KEY_SEND_AMOUNT),
                feePerKbKoinu = json.requireCanonicalLongString(KEY_FEE_PER_KB),
                feeKoinu = json.requireCanonicalLongString(KEY_FEE),
                changeKoinu = json.requireCanonicalLongString(KEY_CHANGE),
                inputTotalKoinu = json.requireCanonicalLongString(KEY_INPUT_TOTAL),
                createdAtMillis = json.requireCanonicalLongString(KEY_CREATED_AT),
                expiresAtMillis = json.requireCanonicalLongString(KEY_EXPIRES_AT),
                mainnetAcknowledged = json.requireBoolean(KEY_MAINNET_ACKNOWLEDGED),
                personalNodeOracleAcknowledged = json.requireBoolean(KEY_ORACLE_ACKNOWLEDGED),
                startTip = DogecoinTrustedPersonalNodeBlockTip(
                    height = json.requireInt(KEY_START_HEIGHT),
                    hash = json.requireString(KEY_START_HASH)
                ),
                endTip = DogecoinTrustedPersonalNodeBlockTip(
                    height = json.requireInt(KEY_END_HEIGHT),
                    hash = json.requireString(KEY_END_HASH)
                ),
                proofReferences = references
            )
        }

        private fun validated(
            binding: DogecoinTrustedPersonalNodeSessionBinding,
            signedRawTransactionHex: String,
            localTxid: String,
            recipientAddress: String,
            sendAmountKoinu: Long,
            feePerKbKoinu: Long,
            feeKoinu: Long,
            changeKoinu: Long,
            inputTotalKoinu: Long,
            createdAtMillis: Long,
            expiresAtMillis: Long,
            mainnetAcknowledged: Boolean,
            personalNodeOracleAcknowledged: Boolean,
            startTip: DogecoinTrustedPersonalNodeBlockTip,
            endTip: DogecoinTrustedPersonalNodeBlockTip,
            proofReferences: List<DogecoinTrustedPersonalNodeAttemptProofReference>
        ): DogecoinTrustedPersonalNodeAttemptReviewRecord {
            require(isValidBinding(binding)) { "Frozen-review profile binding is invalid." }
            val normalized = DogecoinRawTxValidator.normalize(signedRawTransactionHex)
            require(normalized == signedRawTransactionHex && signedRawTransactionHex.length <= MAX_SIGNED_HEX_CHARS) {
                "Frozen-review signed bytes are not exact bounded lowercase hex."
            }
            require(EXACT_LOWER_TXID.matches(localTxid)) { "Frozen-review txid is invalid." }
            require(DogecoinTransactionBuilder.transactionId(signedRawTransactionHex) == localTxid) {
                "Frozen-review signed bytes do not match the local txid."
            }
            require(DogecoinAddress.isValidAddress(recipientAddress, DogecoinNetwork.MAINNET)) {
                "Frozen-review recipient is invalid."
            }
            require(sendAmountKoinu in 1..DOGECOIN_TPN_MAX_MONEY_KOINU) {
                "Frozen-review send amount is invalid."
            }
            require(feePerKbKoinu in DogecoinProtocol.MIN_TX_FEE_KOINU..DOGECOIN_TPN_MAX_MONEY_KOINU) {
                "Frozen-review fee rate is invalid."
            }
            require(feeKoinu in 0..DOGECOIN_TPN_MAX_MONEY_KOINU) {
                "Frozen-review fee is invalid."
            }
            require(changeKoinu in 0..DOGECOIN_TPN_MAX_MONEY_KOINU) {
                "Frozen-review change is invalid."
            }
            require(inputTotalKoinu in 1..DOGECOIN_TPN_MAX_MONEY_KOINU) {
                "Frozen-review input total is invalid."
            }
            require(Math.addExact(Math.addExact(sendAmountKoinu, feeKoinu), changeKoinu) == inputTotalKoinu) {
                "Frozen-review amounts are inconsistent."
            }
            require(createdAtMillis > 0L && expiresAtMillis > createdAtMillis) {
                "Frozen-review expiry is invalid."
            }
            require(expiresAtMillis - createdAtMillis == DOGECOIN_SIGNED_TX_MAX_AGE_MILLIS) {
                "Frozen-review expiry does not use the signed-review window."
            }
            require(mainnetAcknowledged && personalNodeOracleAcknowledged) {
                "Stored frozen review lacks a required per-spend acknowledgement."
            }
            require(isValidTip(startTip) && isValidTip(endTip)) {
                "Frozen-review proof tip is invalid."
            }
            require(endTip.height >= startTip.height &&
                endTip.height - startTip.height <= DOGECOIN_TPN_MAX_SNAPSHOT_TIP_EXTENSION) {
                "Frozen-review proof tip extension is invalid."
            }
            if (endTip.height == startTip.height) {
                require(endTip.hash == startTip.hash) { "Frozen-review proof tip was replaced." }
            }
            require(proofReferences.isNotEmpty() &&
                proofReferences.size <= DOGECOIN_TPN_MAX_PROOF_CANDIDATES) {
                "Frozen-review proof references are empty or excessive."
            }
            val proofKeys = proofReferences.map(::outpointKey)
            require(proofKeys.distinct().size == proofKeys.size) {
                "Frozen-review proof references contain duplicate outpoints."
            }
            val boundScript = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(binding.androidAddress, binding.network)
            )
            require(proofReferences.all { it.scriptPubKeyHex == boundScript }) {
                "Frozen-review proof reference is not owned by the bound Android address."
            }
            val proofTotal = proofReferences.fold(0L) { total, proof ->
                Math.addExact(total, proof.amountKoinu)
            }
            require(proofTotal == inputTotalKoinu) {
                "Frozen-review proof amounts do not equal the input total."
            }
            val proofByteTotal = proofReferences.fold(0L) { total, proof ->
                Math.addExact(total, proof.previousTransactionByteCount.toLong())
            }
            require(proofByteTotal <= DOGECOIN_TPN_MAX_SNAPSHOT_PROOF_BYTES.toLong()) {
                "Frozen-review proof references exceed the aggregate proof bound."
            }
            val parsed = parseSignedTransaction(signedRawTransactionHex)
            require(parsed.inputOutpoints == proofKeys) {
                "Frozen-review signed inputs do not match the proof references."
            }
            val expectedOutputs = buildList {
                add(
                    AttemptParsedOutput(
                        amountKoinu = sendAmountKoinu,
                        scriptPubKeyHex = DogecoinHex.encode(
                            DogecoinAddress.scriptPubKey(recipientAddress, DogecoinNetwork.MAINNET)
                        )
                    )
                )
                if (changeKoinu > 0L) {
                    add(
                        AttemptParsedOutput(
                            amountKoinu = changeKoinu,
                            scriptPubKeyHex = boundScript
                        )
                    )
                }
            }
            require(parsed.outputs == expectedOutputs) {
                "Frozen-review signed outputs do not match the reviewed recipient and change."
            }
            val parsedOutputTotal = parsed.outputs.fold(0L) { total, output ->
                Math.addExact(total, output.amountKoinu)
            }
            require(Math.subtractExact(inputTotalKoinu, parsedOutputTotal) == feeKoinu) {
                "Frozen-review fee was not derived from verified inputs and signed outputs."
            }
            val relativeHardLimit = sendAmountKoinu /
                DogecoinProtocol.HIGH_FEE_RELATIVE_DENOMINATOR +
                if (sendAmountKoinu % DogecoinProtocol.HIGH_FEE_RELATIVE_DENOMINATOR == 0L) 0L else 1L
            require(
                feeKoinu < DogecoinProtocol.HIGH_FEE_ABSOLUTE_KOINU &&
                    feeKoinu < relativeHardLimit
            ) {
                "Trusted personal node fee crosses a non-overridable hard limit."
            }
            return DogecoinTrustedPersonalNodeAttemptReviewRecord(
                binding = binding,
                signedRawTransactionHex = signedRawTransactionHex,
                localTxid = localTxid,
                recipientAddress = recipientAddress,
                sendAmountKoinu = sendAmountKoinu,
                feePerKbKoinu = feePerKbKoinu,
                feeKoinu = feeKoinu,
                changeKoinu = changeKoinu,
                inputTotalKoinu = inputTotalKoinu,
                createdAtMillis = createdAtMillis,
                expiresAtMillis = expiresAtMillis,
                mainnetAcknowledged = mainnetAcknowledged,
                personalNodeOracleAcknowledged = personalNodeOracleAcknowledged,
                startTip = startTip,
                endTip = endTip,
                proofReferences = proofReferences
            )
        }

        private fun isValidBinding(binding: DogecoinTrustedPersonalNodeSessionBinding): Boolean =
            binding.network == DogecoinNetwork.MAINNET &&
                exactDogecoinTrustedPersonalNodeOriginOrNull(binding.origin) == binding.origin &&
                DogecoinAddress.isValidP2pkhAddress(binding.androidAddress, binding.network) &&
                canonicalDogecoinTrustedPersonalNodeWalletIdOrNull(binding.coreWalletId) == binding.coreWalletId &&
                binding.policyVersion == DOGECOIN_TRUSTED_PERSONAL_NODE_POLICY_VERSION &&
                binding.profileRevision > 0L

        private fun isValidTip(tip: DogecoinTrustedPersonalNodeBlockTip): Boolean =
            tip.height >= 0 && EXACT_LOWER_TXID.matches(tip.hash)

        private fun outpointKey(prevout: DogecoinVerifiedPrevout): String =
            outpointKey(prevout.txid, prevout.vout)

        private fun outpointKey(reference: DogecoinTrustedPersonalNodeAttemptProofReference): String =
            outpointKey(reference.txid, reference.vout)

        private fun outpointKey(txid: String, vout: Int): String = "$txid:$vout"

        private fun parseSignedTransaction(rawHex: String): AttemptParsedTransaction {
            val bytes = DogecoinHex.decode(rawHex)
            val reader = AttemptRawReader(bytes)
            reader.skip(4)
            val inputCount = reader.readCompactSize()
            require(inputCount in 1..DOGECOIN_TPN_MAX_PROOF_CANDIDATES.toLong()) {
                "Frozen-review signed input count is invalid."
            }
            val inputs = List(inputCount.toInt()) {
                val littleEndianTxid = reader.readBytes(32)
                val txid = DogecoinHex.encode(littleEndianTxid.reversedArray())
                val voutLong = reader.readUnsignedLittleEndian(4)
                require(voutLong <= Int.MAX_VALUE.toLong()) {
                    "Frozen-review signed output index is unsupported."
                }
                val scriptLength = reader.readCompactSize()
                require(scriptLength <= reader.remaining.toLong()) {
                    "Frozen-review signed input script is truncated."
                }
                reader.skip(scriptLength.toInt())
                reader.skip(4)
                outpointKey(txid, voutLong.toInt())
            }
            val outputCount = reader.readCompactSize()
            require(outputCount in 1..2) {
                "Frozen-review signed output count is invalid."
            }
            val outputs = List(outputCount.toInt()) {
                val amount = reader.readUnsignedLittleEndian(8)
                val scriptLength = reader.readCompactSize()
                require(scriptLength <= reader.remaining.toLong()) {
                    "Frozen-review signed output script is truncated."
                }
                AttemptParsedOutput(
                    amountKoinu = amount,
                    scriptPubKeyHex = DogecoinHex.encode(reader.readBytes(scriptLength.toInt()))
                )
            }
            reader.skip(4)
            require(reader.remaining == 0) {
                "Frozen-review signed transaction has trailing bytes."
            }
            return AttemptParsedTransaction(inputs, outputs)
        }

        private const val MAX_SIGNED_HEX_CHARS = 2_000_000

        private const val KEY_ORIGIN = "origin"
        private const val KEY_NETWORK = "network"
        private const val KEY_ANDROID_ADDRESS = "android_address"
        private const val KEY_CORE_WALLET_ID = "core_wallet_id"
        private const val KEY_POLICY_VERSION = "policy_version"
        private const val KEY_PROFILE_REVISION = "profile_revision"
        private const val KEY_RAW_TRANSACTION = "signed_raw_transaction_hex"
        private const val KEY_LOCAL_TXID = "local_txid"
        private const val KEY_RECIPIENT = "recipient_address"
        private const val KEY_SEND_AMOUNT = "send_amount_koinu"
        private const val KEY_FEE_PER_KB = "fee_per_kb_koinu"
        private const val KEY_FEE = "fee_koinu"
        private const val KEY_CHANGE = "change_koinu"
        private const val KEY_INPUT_TOTAL = "input_total_koinu"
        private const val KEY_CREATED_AT = "created_at_millis"
        private const val KEY_EXPIRES_AT = "expires_at_millis"
        private const val KEY_MAINNET_ACKNOWLEDGED = "mainnet_acknowledged"
        private const val KEY_ORACLE_ACKNOWLEDGED = "personal_node_oracle_acknowledged"
        private const val KEY_START_HEIGHT = "start_tip_height"
        private const val KEY_START_HASH = "start_tip_hash"
        private const val KEY_END_HEIGHT = "end_tip_height"
        private const val KEY_END_HASH = "end_tip_hash"
        private const val KEY_PROOFS = "proof_references"
    }
}

/** One durable idempotency record. It intentionally has no API for releasing its input reservations. */
internal class DogecoinTrustedPersonalNodeAttempt private constructor(
    val correlationId: String,
    val state: DogecoinTrustedPersonalNodeAttemptState,
    val review: DogecoinTrustedPersonalNodeAttemptReviewRecord
) {
    override fun toString(): String =
        "DogecoinTrustedPersonalNodeAttempt(correlationId=<redacted>, state=$state, review=<redacted>)"

    internal fun withState(next: DogecoinTrustedPersonalNodeAttemptState):
        DogecoinTrustedPersonalNodeAttempt {
        require(
            next == state ||
                state == DogecoinTrustedPersonalNodeAttemptState.READY_UNDISCLOSED &&
                next == DogecoinTrustedPersonalNodeAttemptState.SUBMISSION_UNKNOWN ||
                state == DogecoinTrustedPersonalNodeAttemptState.SUBMISSION_UNKNOWN &&
                next == DogecoinTrustedPersonalNodeAttemptState.CLAIMED
        ) { "Trusted personal node attempt state cannot regress or skip disclosure." }
        return DogecoinTrustedPersonalNodeAttempt(correlationId, next, review)
    }

    internal fun toJson(): JSONObject = JSONObject()
        .put(KEY_CORRELATION_ID, correlationId)
        .put(KEY_STATE, state.name)
        .put(KEY_REVIEW, review.toJson())

    companion object {
        internal fun create(
            correlationId: String,
            review: DogecoinTrustedPersonalNodeAttemptReviewRecord
        ): DogecoinTrustedPersonalNodeAttempt {
            require(EXACT_CORRELATION_ID.matches(correlationId)) {
                "Trusted personal node correlation id is invalid."
            }
            return DogecoinTrustedPersonalNodeAttempt(
                correlationId,
                DogecoinTrustedPersonalNodeAttemptState.READY_UNDISCLOSED,
                review
            )
        }

        internal fun fromJson(json: JSONObject): DogecoinTrustedPersonalNodeAttempt {
            json.requireExactKeys(KEY_CORRELATION_ID, KEY_STATE, KEY_REVIEW)
            val id = json.requireString(KEY_CORRELATION_ID)
            require(EXACT_CORRELATION_ID.matches(id)) {
                "Stored trusted personal node correlation id is invalid."
            }
            val stateName = json.requireString(KEY_STATE)
            val state = DogecoinTrustedPersonalNodeAttemptState.values()
                .firstOrNull { it.name == stateName }
                ?: throw IllegalArgumentException("Stored trusted personal node attempt state is invalid.")
            return DogecoinTrustedPersonalNodeAttempt(
                correlationId = id,
                state = state,
                review = DogecoinTrustedPersonalNodeAttemptReviewRecord.fromJson(json.requireObject(KEY_REVIEW))
            )
        }

        private val EXACT_CORRELATION_ID = Regex("^[0-9a-f]{32}$")
        private const val KEY_CORRELATION_ID = "correlation_id"
        private const val KEY_STATE = "state"
        private const val KEY_REVIEW = "review"
    }
}

internal sealed class DogecoinTrustedPersonalNodeAttemptLoadResult {
    object Empty : DogecoinTrustedPersonalNodeAttemptLoadResult()
    class Available(val attempt: DogecoinTrustedPersonalNodeAttempt) :
        DogecoinTrustedPersonalNodeAttemptLoadResult()
    object Unavailable : DogecoinTrustedPersonalNodeAttemptLoadResult()
}

private data class AttemptParsedTransaction(
    val inputOutpoints: List<String>,
    val outputs: List<AttemptParsedOutput>
)

private data class AttemptParsedOutput(
    val amountKoinu: Long,
    val scriptPubKeyHex: String
)

/**
 * Encrypted, backup-excluded DES-1-D idempotency ledger. One unresolved attempt is the conservative
 * v1 bound: DES-1-E will own independent reconciliation and reservation release. A corrupt ledger is
 * never treated as empty, because doing so could silently reuse an already disclosed input.
 */
internal class DogecoinTrustedPersonalNodeAttemptStore private constructor(
    prefsProvider: () -> SharedPreferences,
    private val correlationIdFactory: () -> String
) {
    constructor(context: Context) : this(
        prefsProvider = encryptedPrefsProvider(context),
        correlationIdFactory = ::newCorrelationId
    )

    internal constructor(
        prefs: SharedPreferences,
        correlationIdFactory: () -> String = ::newCorrelationId
    ) : this({ prefs }, correlationIdFactory)

    private val prefs: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        prefsProvider()
    }

    fun load(): DogecoinTrustedPersonalNodeAttemptLoadResult = synchronized(STORE_LOCK) {
        loadLocked()
    }

    /**
     * Atomically creates the exact attempt and its reservations. Repeating the same frozen review is
     * idempotent and returns the original correlation record; any different unresolved attempt fails.
     */
    fun persistBeforeDisclosure(
        review: DogecoinTrustedPersonalNodeFrozenReview,
        mainnetAcknowledged: Boolean,
        personalNodeOracleAcknowledged: Boolean
    ): DogecoinTrustedPersonalNodeAttempt? = synchronized(STORE_LOCK) {
        val record = runCatching {
            DogecoinTrustedPersonalNodeAttemptReviewRecord.fromFrozenReview(
                review = review,
                mainnetAcknowledged = mainnetAcknowledged,
                personalNodeOracleAcknowledged = personalNodeOracleAcknowledged
            )
        }.getOrNull() ?: return@synchronized null
        when (val loaded = loadLocked()) {
            DogecoinTrustedPersonalNodeAttemptLoadResult.Empty -> {
                val attempt = runCatching {
                    DogecoinTrustedPersonalNodeAttempt.create(correlationIdFactory(), record)
                }.getOrNull() ?: return@synchronized null
                if (commitLocked(attempt)) attempt else null
            }
            is DogecoinTrustedPersonalNodeAttemptLoadResult.Available ->
                loaded.attempt.takeIf {
                    it.state != DogecoinTrustedPersonalNodeAttemptState.CLAIMED &&
                        it.review.sameSignedAttemptAs(
                            review,
                            mainnetAcknowledged,
                            personalNodeOracleAcknowledged
                        )
                }
            DogecoinTrustedPersonalNodeAttemptLoadResult.Unavailable -> null
        }
    }

    internal fun persistBeforeDisclosure(
        review: DogecoinTrustedPersonalNodeAttemptReviewRecord
    ): DogecoinTrustedPersonalNodeAttempt? = synchronized(STORE_LOCK) {
        when (val loaded = loadLocked()) {
            DogecoinTrustedPersonalNodeAttemptLoadResult.Empty -> {
                val attempt = runCatching {
                    DogecoinTrustedPersonalNodeAttempt.create(correlationIdFactory(), review)
                }.getOrNull() ?: return@synchronized null
                if (commitLocked(attempt)) attempt else null
            }
            is DogecoinTrustedPersonalNodeAttemptLoadResult.Available ->
                loaded.attempt.takeIf {
                    it.state != DogecoinTrustedPersonalNodeAttemptState.CLAIMED &&
                        it.review.sameAs(review)
                }
            DogecoinTrustedPersonalNodeAttemptLoadResult.Unavailable -> null
        }
    }

    /** Call synchronously immediately before invoking any RPC that receives the signed bytes. */
    fun markSubmissionUnknown(correlationId: String, localTxid: String): Boolean =
        transition(correlationId, localTxid, DogecoinTrustedPersonalNodeAttemptState.SUBMISSION_UNKNOWN)

    /** Exact Core txid acceptance is still only a claim; reservations deliberately remain present. */
    fun markClaimed(correlationId: String, exactReturnedTxid: String): Boolean =
        transition(correlationId, exactReturnedTxid, DogecoinTrustedPersonalNodeAttemptState.CLAIMED)

    /**
     * Restores only same-byte retry material for the exact profile revision. Persisted proof metadata
     * never restores process session authority; the coordinator must separately require a fresh lease.
     */
    fun retryableAttempt(
        binding: DogecoinTrustedPersonalNodeSessionBinding,
        nowMillis: Long
    ): DogecoinTrustedPersonalNodeAttempt? = synchronized(STORE_LOCK) {
        val attempt = (loadLocked() as? DogecoinTrustedPersonalNodeAttemptLoadResult.Available)
            ?.attempt ?: return@synchronized null
        if (attempt.state == DogecoinTrustedPersonalNodeAttemptState.CLAIMED) return@synchronized null
        if (attempt.review.binding != binding) return@synchronized null
        if (nowMillis < attempt.review.createdAtMillis || nowMillis > attempt.review.expiresAtMillis) {
            return@synchronized null
        }
        attempt
    }

    private fun transition(
        correlationId: String,
        localTxid: String,
        next: DogecoinTrustedPersonalNodeAttemptState
    ): Boolean = synchronized(STORE_LOCK) {
        val attempt = (loadLocked() as? DogecoinTrustedPersonalNodeAttemptLoadResult.Available)
            ?.attempt ?: return@synchronized false
        if (attempt.correlationId != correlationId || attempt.review.localTxid != localTxid) {
            return@synchronized false
        }
        if (attempt.state == next) return@synchronized true
        val updated = runCatching { attempt.withState(next) }.getOrNull()
            ?: return@synchronized false
        commitLocked(updated)
    }

    private fun loadLocked(): DogecoinTrustedPersonalNodeAttemptLoadResult {
        val serialized = runCatching { prefs.getString(KEY_LEDGER, null) }
            .getOrElse { return DogecoinTrustedPersonalNodeAttemptLoadResult.Unavailable }
            ?: return DogecoinTrustedPersonalNodeAttemptLoadResult.Empty
        if (serialized.length > MAX_LEDGER_CHARS) {
            return DogecoinTrustedPersonalNodeAttemptLoadResult.Unavailable
        }
        return runCatching {
            val ledger = JSONObject(serialized)
            ledger.requireExactKeys(KEY_SCHEMA_VERSION, KEY_ATTEMPT)
            require(ledger.requireInt(KEY_SCHEMA_VERSION) == SCHEMA_VERSION) {
                "Unsupported trusted personal node attempt schema."
            }
            val attempt = DogecoinTrustedPersonalNodeAttempt.fromJson(
                ledger.requireObject(KEY_ATTEMPT)
            )
            DogecoinTrustedPersonalNodeAttemptLoadResult.Available(attempt)
        }.getOrElse { DogecoinTrustedPersonalNodeAttemptLoadResult.Unavailable }
    }

    private fun commitLocked(attempt: DogecoinTrustedPersonalNodeAttempt): Boolean {
        val serialized = JSONObject()
            .put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            .put(KEY_ATTEMPT, attempt.toJson())
            .toString()
        return runCatching {
            prefs.edit().putString(KEY_LEDGER, serialized).commit()
        }.getOrDefault(false)
    }

    private companion object {
        val STORE_LOCK = Any()
        val SECURE_RANDOM = SecureRandom()
        const val PREFS_NAME = "dogecoin_tpn_attempts"
        const val KEY_LEDGER = "attempt_ledger"
        const val KEY_SCHEMA_VERSION = "schema_version"
        const val KEY_ATTEMPT = "attempt"
        const val SCHEMA_VERSION = 1
        const val MAX_LEDGER_CHARS = 2_100_000

        fun newCorrelationId(): String = ByteArray(16)
            .also(SECURE_RANDOM::nextBytes)
            .let(DogecoinHex::encode)

        fun encryptedPrefsProvider(context: Context): () -> SharedPreferences {
            val appContext = context.applicationContext
            return {
                val masterKey = MasterKey.Builder(appContext, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
        }
    }
}

private class AttemptRawReader(private val bytes: ByteArray) {
    private var offset = 0
    val remaining: Int get() = bytes.size - offset

    fun readBytes(count: Int): ByteArray {
        require(count >= 0 && offset <= bytes.size - count) {
            "Frozen-review signed transaction is truncated."
        }
        return bytes.copyOfRange(offset, offset + count).also { offset += count }
    }

    fun skip(count: Int) {
        readBytes(count)
    }

    fun readUnsignedLittleEndian(count: Int): Long {
        require(count in 1..8) { "Frozen-review integer width is invalid." }
        val raw = readBytes(count)
        var value = 0L
        raw.forEachIndexed { index, byte ->
            val part = byte.toLong() and 0xffL
            if (index == 7) require(part <= 0x7fL) { "Frozen-review integer is too large." }
            value = value or (part shl (8 * index))
        }
        return value
    }

    fun readCompactSize(): Long {
        val first = readUnsignedLittleEndian(1)
        return when (first) {
            in 0..0xfc -> first
            0xfdL -> readUnsignedLittleEndian(2).also { require(it >= 0xfdL) }
            0xfeL -> readUnsignedLittleEndian(4).also { require(it > 0xffffL) }
            else -> readUnsignedLittleEndian(8).also { require(it > 0xffffffffL) }
        }
    }
}

private val EXACT_LOWER_TXID = Regex("^[0-9a-f]{64}$")
private val EXACT_LOWER_HEX = Regex("^[0-9a-f]+$")

private fun JSONObject.requireExactKeys(vararg expected: String) {
    val actual = keys().asSequence().toSet()
    require(actual == expected.toSet()) { "Stored trusted personal node attempt has unexpected fields." }
}

private fun JSONObject.requireString(key: String): String {
    val value = get(key)
    require(value is String) { "Stored trusted personal node attempt field $key is not text." }
    return value
}

private fun JSONObject.requireInt(key: String): Int {
    val value = get(key)
    require(value is Int) { "Stored trusted personal node attempt field $key is not an integer." }
    return value
}

private fun JSONObject.requireCanonicalLongString(key: String): Long {
    val value = requireString(key)
    require(value == "0" ||
        (value.isNotEmpty() && value[0] in '1'..'9' && value.all(Char::isDigit))) {
        "Stored trusted personal node attempt field $key is not a canonical integer."
    }
    return value.toLong()
}

private fun JSONObject.requireBoolean(key: String): Boolean {
    val value = get(key)
    require(value is Boolean) { "Stored trusted personal node attempt field $key is not Boolean." }
    return value
}

private fun JSONObject.requireObject(key: String): JSONObject {
    val value = get(key)
    require(value is JSONObject) { "Stored trusted personal node attempt field $key is not an object." }
    return value
}

private fun JSONObject.requireArray(key: String): JSONArray {
    val value = get(key)
    require(value is JSONArray) { "Stored trusted personal node attempt field $key is not an array." }
    return value
}
