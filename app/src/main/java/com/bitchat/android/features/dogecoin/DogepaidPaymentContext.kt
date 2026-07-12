package com.bitchat.android.features.dogecoin

/**
 * Immutable routing context captured when a chat payment-request's Pay button is tapped.
 *
 * A receipt destination is deliberately not a generic channel string. The only representable
 * destinations are a known private conversation or the structural Nostr identity of the member
 * who posted a request in a private group. Public/channel requests therefore have no context and
 * can never inherit whatever conversation happens to be selected when a broadcast completes.
 */
data class DogepaidPaymentContext(
    val paymentUri: String,
    val destination: DogepaidReceiptDestination
) {
    companion object {
        fun forPrivateConversation(
            request: DogecoinPaymentRequest,
            conversationKey: String
        ): DogepaidPaymentContext? {
            val key = conversationKey.trim()
            if (!isEligibleDogepaidPrivateConversationKey(key)) return null
            return DogepaidPaymentContext(
                paymentUri = request.uri,
                destination = DogepaidReceiptDestination.PrivateConversation(key)
            )
        }

        fun forGroupRequester(
            request: DogecoinPaymentRequest,
            requesterNostrPubkeyHex: String?
        ): DogepaidPaymentContext? {
            val pubkey = requesterNostrPubkeyHex?.trim()?.lowercase() ?: return null
            if (!NOSTR_PUBKEY_REGEX.matches(pubkey)) return null
            return DogepaidPaymentContext(
                paymentUri = request.uri,
                destination = DogepaidReceiptDestination.NostrRequester(pubkey)
            )
        }
    }
}

sealed interface DogepaidReceiptDestination {
    data class PrivateConversation(val conversationKey: String) : DogepaidReceiptDestination
    data class NostrRequester(val publicKeyHex: String) : DogepaidReceiptDestination
}

/** Actual, locally-signed transaction facts captured only after an existing broadcast success anchor. */
data class DogepaidBroadcastClaim(
    val network: DogecoinNetwork,
    val txid: String,
    val amountKoinu: Long,
    val recipientAddress: String
)

internal data class DogepaidReceiptDeliveryPlan(
    val receipt: DogepaidReceipt,
    val conversationKey: String,
    val requesterNostrPubkeyHex: String?
)

/** Pure, fail-closed validation used immediately before the durable send reservation. */
internal object DogepaidReceiptDeliveryPlanner {
    fun plan(
        paymentContext: DogepaidPaymentContext,
        claim: DogepaidBroadcastClaim,
        myPeerId: String,
        myNostrPubkeyHex: String?
    ): DogepaidReceiptDeliveryPlan? {
        val request = DogecoinPaymentRequest.parse(paymentContext.paymentUri) ?: return null
        if (request.network != claim.network || request.address != claim.recipientAddress) return null

        val requestRef = DogepaidReceipt.reqRef(paymentContext.paymentUri) ?: return null
        val receipt = runCatching {
            DogepaidReceipt(
                network = claim.network,
                txid = claim.txid,
                amountKoinu = claim.amountKoinu,
                toAddress = claim.recipientAddress,
                requestRef = requestRef
            )
        }.getOrNull() ?: return null

        return when (val destination = paymentContext.destination) {
            is DogepaidReceiptDestination.PrivateConversation -> {
                val key = destination.conversationKey
                if (!isEligibleDogepaidPrivateConversationKey(key) || key == myPeerId) return null
                DogepaidReceiptDeliveryPlan(receipt, key, null)
            }
            is DogepaidReceiptDestination.NostrRequester -> {
                val publicKey = destination.publicKeyHex.trim().lowercase()
                if (publicKey == myNostrPubkeyHex?.trim()?.lowercase()) return null
                val key = dogepaidRequesterConversationKey(publicKey) ?: return null
                DogepaidReceiptDeliveryPlan(receipt, key, publicKey)
            }
        }
    }
}

internal fun isEligibleDogepaidPrivateConversationKey(key: String): Boolean {
    if (key.isBlank()) return false
    if (key.startsWith("nostr_grp_")) return false
    if (key.startsWith("geo:", ignoreCase = true)) return false
    if (key.startsWith("#")) return false
    return true
}

internal fun dogepaidRequesterConversationKey(publicKeyHex: String): String? {
    val normalized = publicKeyHex.trim().lowercase()
    if (!NOSTR_PUBKEY_REGEX.matches(normalized)) return null
    return "nostr_${normalized.take(16)}"
}

private val NOSTR_PUBKEY_REGEX = Regex("^[0-9a-f]{64}$")
