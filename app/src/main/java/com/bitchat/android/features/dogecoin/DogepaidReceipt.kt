package com.bitchat.android.features.dogecoin

import com.bitchat.android.ui.DogecoinUri
import java.security.MessageDigest

/**
 * A machine-readable claim that a Dogecoin payment was broadcast.
 *
 * Receipt fields are never proof of settlement. Callers must use
 * [DogepaidReceiptStateResolver] and a trusted local chain observation before presenting a
 * receipt as corroborated.
 */
data class DogepaidReceipt(
    val network: DogecoinNetwork,
    val txid: String,
    val amountKoinu: Long,
    val toAddress: String,
    val requestRef: String? = null
) {
    init {
        require(LOWERCASE_TXID.matches(txid)) { "Receipt txid must be 64 lowercase hex characters." }
        require(amountKoinu > 0L) { "Receipt amount must be positive." }
        require(DogecoinAddress.isValidAddress(toAddress, network)) {
            "Receipt address does not match ${network.id}."
        }
        require(requestRef == null || REQUEST_REF.matches(requestRef)) {
            "Receipt request reference must be 16 lowercase hex characters."
        }
    }

    fun encode(): String = buildString {
        append(SCHEME)
        append(network.id)
        append(':')
        append(txid)
        append("?v=1&amount=")
        append(DogecoinAmount.formatKoinu(amountKoinu))
        append("&to=")
        append(toAddress)
        requestRef?.let {
            append("&req=")
            append(it)
        }
    }.also { encoded ->
        require(encoded.length <= MAX_MESSAGE_LENGTH) { "Receipt exceeds $MAX_MESSAGE_LENGTH characters." }
    }

    companion object {
        const val MAX_MESSAGE_LENGTH = 256

        private const val SCHEME = "dogepaid:"
        private val LOWERCASE_TXID = Regex("^[0-9a-f]{64}$")
        private val REQUEST_REF = Regex("^[0-9a-f]{16}$")
        private val QUERY_KEY = Regex("^[a-z][a-z0-9-]*$")
        // Unknown forward-compatible fields stay inert on old clients: notably no '.' (bare-domain bait),
        // URI punctuation, whitespace, or percent escapes. Only the known amount field may contain '.'.
        private val QUERY_VALUE = Regex("^[A-Za-z0-9_~-]+$")
        private val AMOUNT_QUERY_VALUE = Regex("^[0-9]+(?:\\.[0-9]{1,8})?$")
        private val FORBIDDEN_FREE_TEXT_KEYS = setOf("label", "message", "status")

        fun parse(raw: String): DogepaidReceipt? {
            if (raw.length > MAX_MESSAGE_LENGTH) return null
            val message = raw.trim()
            if (message.isEmpty() || message.length > MAX_MESSAGE_LENGTH) return null
            if (!message.startsWith(SCHEME)) return null

            val queryStart = message.indexOf('?')
            if (queryStart <= SCHEME.length || queryStart != message.lastIndexOf('?')) return null

            val identity = message.substring(SCHEME.length, queryStart).split(':')
            if (identity.size != 2) return null
            val network = DogecoinNetwork.values().singleOrNull { it.id == identity[0] } ?: return null
            val txid = identity[1]
            if (!LOWERCASE_TXID.matches(txid)) return null

            val params = parseQuery(message.substring(queryStart + 1)) ?: return null
            if (params["v"] != "1") return null

            val amount = params["amount"] ?: return null
            if (!DogecoinAmount.isValidAmount(amount)) return null
            val amountKoinu = runCatching { DogecoinAmount.toKoinu(amount) }.getOrNull() ?: return null

            val toAddress = params["to"] ?: return null
            if (!DogecoinAddress.isValidAddress(toAddress, network)) return null

            val requestRef = params["req"]
            if (requestRef != null && !REQUEST_REF.matches(requestRef)) return null

            return runCatching {
                DogepaidReceipt(
                    network = network,
                    txid = txid,
                    amountKoinu = amountKoinu,
                    toAddress = toAddress,
                    requestRef = requestRef
                )
            }.getOrNull()
        }

        fun encode(
            network: DogecoinNetwork,
            txid: String,
            amountKoinu: Long,
            toAddress: String,
            requestRef: String? = null
        ): String = DogepaidReceipt(
            network = network,
            txid = txid,
            amountKoinu = amountKoinu,
            toAddress = toAddress,
            requestRef = requestRef
        ).encode()

        /**
         * Binds a receipt to the exact whole-message payment URI without a decode/re-encode
         * round trip. The first eight SHA-256 bytes are encoded as 16 lowercase hex characters.
         */
        fun reqRef(messageContent: String): String? {
            val paymentUri = DogecoinUri.wholeMessagePaymentUri(messageContent) ?: return null
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(paymentUri.toByteArray(Charsets.UTF_8))
            return DogecoinHex.encode(digest.copyOfRange(0, 8))
        }

        private fun parseQuery(query: String): Map<String, String>? {
            if (query.isEmpty() || query.startsWith('&') || query.endsWith('&') || "&&" in query) return null

            val params = linkedMapOf<String, String>()
            for (part in query.split('&')) {
                if (part.isEmpty()) return null
                val equals = part.indexOf('=')
                if (equals <= 0 || equals != part.lastIndexOf('=') || equals == part.lastIndex) return null

                val key = part.substring(0, equals)
                val value = part.substring(equals + 1)
                if (!QUERY_KEY.matches(key)) return null
                if (key == "amount") {
                    if (!AMOUNT_QUERY_VALUE.matches(value)) return null
                } else if (!QUERY_VALUE.matches(value)) {
                    return null
                }
                if (key.startsWith("req-") || key in FORBIDDEN_FREE_TEXT_KEYS) return null
                if (params.put(key, value) != null) return null
            }
            return params
        }
    }
}

/** A read-only transaction observation supplied by a trusted local SPV or RPC view. */
data class DogepaidLocalObservation(
    val network: DogecoinNetwork,
    val txid: String,
    val walletAddress: String,
    val incoming: Boolean,
    val amountKoinu: Long,
    val confirmations: Int
)

enum class DogepaidClaimReason {
    CROSS_NETWORK,
    NOT_FOR_THIS_WALLET,
    NOT_CORROBORATED
}

sealed class DogepaidReceiptState {
    data class Claimed(val reason: DogepaidClaimReason) : DogepaidReceiptState()

    data class Corroborated(
        val observedAmountKoinu: Long,
        val confirmations: Int
    ) : DogepaidReceiptState()
}

enum class DogepaidReceiptObservationSource { NONE, SPV, RPC }

data class DogepaidReceiptCheckResult(
    val state: DogepaidReceiptState,
    val source: DogepaidReceiptObservationSource,
    val rpcNotOverTorDisclosure: Boolean = false
)

/**
 * Promotes a receipt claim only when the local wallet independently observes the exact incoming
 * transaction. Claimed amount and confirmation fields never participate in corroboration output.
 */
object DogepaidReceiptStateResolver {
    fun resolve(
        receipt: DogepaidReceipt,
        walletNetwork: DogecoinNetwork,
        walletAddress: String,
        observations: Iterable<DogepaidLocalObservation>
    ): DogepaidReceiptState {
        if (receipt.network != walletNetwork) {
            return DogepaidReceiptState.Claimed(DogepaidClaimReason.CROSS_NETWORK)
        }
        if (
            receipt.toAddress != walletAddress ||
            !DogecoinAddress.isValidAddress(walletAddress, walletNetwork)
        ) {
            return DogepaidReceiptState.Claimed(DogepaidClaimReason.NOT_FOR_THIS_WALLET)
        }

        val matches = observations.filter { observation ->
            observation.network == walletNetwork &&
                observation.txid == receipt.txid &&
                observation.walletAddress == walletAddress &&
                observation.incoming &&
                observation.amountKoinu > 0L &&
                observation.confirmations >= 0
        }
        if (matches.isEmpty()) {
            return DogepaidReceiptState.Claimed(DogepaidClaimReason.NOT_CORROBORATED)
        }

        // A trusted transaction view should expose one amount for a txid. Conflicting local rows
        // are not enough to make an honest statement, so retain claim status fail-closed.
        val observedAmounts = matches.map { it.amountKoinu }.distinct()
        if (observedAmounts.size != 1) {
            return DogepaidReceiptState.Claimed(DogepaidClaimReason.NOT_CORROBORATED)
        }

        return DogepaidReceiptState.Corroborated(
            observedAmountKoinu = observedAmounts.single(),
            confirmations = matches.maxOf { it.confirmations }
        )
    }
}
