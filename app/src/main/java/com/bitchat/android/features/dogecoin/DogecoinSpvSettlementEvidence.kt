package com.bitchat.android.features.dogecoin

/**
 * Independent provenance for a transaction learned by the on-device SPV wallet.
 *
 * There is deliberately no NODE or LOCAL value: neither the trusted personal node nor a transaction
 * inserted locally with bitcoinj's `receivePending` is settlement evidence.
 */
internal enum class DogecoinSpvEvidenceProvenance {
    NONE,
    /** Unconfirmed transaction announced by the SPV peer network. */
    PEER,
    /** Transaction included in bitcoinj's validated best chain. */
    CHAIN
}

/** Positive SPV evidence that another transaction spends one of an attempt's reserved outpoints. */
internal data class DogecoinSpvConflictingSpendEvidence(
    val reservedTxid: String,
    val reservedVout: Int,
    val spendingTxid: String,
    val depth: Int,
    val provenance: DogecoinSpvEvidenceProvenance
)

/**
 * One read-only SPV settlement sample for a TPN attempt.
 *
 * [exactTransactionDepth] is null until the exact txid is peer- or chain-derived. A depth of zero is a
 * peer observation only; positive depth is chain observation. [fullySynced] uses the existing strict
 * mainnet SPV predicate, including its peer floor and tip-lag rule. DES-1-E consumers require it before
 * advancing settlement. The node cannot construct this type through an RPC response.
 */
internal data class DogecoinSpvSettlementEvidence(
    val network: DogecoinNetwork,
    val expectedTxid: String,
    val exactTransactionDepth: Int?,
    val exactTransactionProvenance: DogecoinSpvEvidenceProvenance,
    val fullySynced: Boolean,
    val peerCount: Int,
    val peerFloorMet: Boolean,
    val chainHeight: Int,
    val bestPeerHeight: Long,
    val chainTipHash: String?,
    val conflictingSpends: List<DogecoinSpvConflictingSpendEvidence>
)

/** A minimal, bitcoinj-free transaction fact used to test the provenance policy. */
internal data class DogecoinSpvWalletTransactionFact(
    val txid: String,
    val confidence: DogecoinSpvWalletConfidence,
    val depth: Int,
    val spentOutpoints: List<Pair<String, Int>>
)

internal enum class DogecoinSpvWalletConfidence {
    NETWORK_PENDING,
    CHAIN_BUILDING,
    LOCAL_PENDING,
    DEAD_OR_CONFLICTING,
    UNKNOWN
}

/** Pure derivation policy: only peer announcements and validated-chain inclusion are independent evidence. */
internal object DogecoinSpvSettlementEvidencePolicy {
    private val TXID = Regex("^[0-9a-f]{64}$")

    fun derive(
        status: DogecoinSpvStatus,
        expectedTxid: String,
        reservedOutpoints: List<Pair<String, Int>>,
        chainTipHash: String?,
        transactions: List<DogecoinSpvWalletTransactionFact>
    ): DogecoinSpvSettlementEvidence? {
        if (status.network != DogecoinNetwork.MAINNET || !status.running) return null
        val expected = expectedTxid.trim().lowercase()
        if (!TXID.matches(expected)) return null
        if (reservedOutpoints.isEmpty()) return null

        val reserved = linkedMapOf<String, Pair<String, Int>>()
        for ((rawTxid, vout) in reservedOutpoints) {
            val txid = rawTxid.trim().lowercase()
            if (!TXID.matches(txid) || vout < 0) return null
            val key = "$txid:$vout"
            if (reserved.put(key, txid to vout) != null) return null
        }

        val exact = transactions
            .asSequence()
            .filter { it.txid.trim().equals(expected, ignoreCase = true) }
            .mapNotNull(::observation)
            .maxWithOrNull(compareBy<Pair<DogecoinSpvEvidenceProvenance, Int>> { it.second }
                .thenBy { if (it.first == DogecoinSpvEvidenceProvenance.CHAIN) 1 else 0 })

        val conflicts = transactions
            .asSequence()
            .filterNot { it.txid.trim().equals(expected, ignoreCase = true) }
            .mapNotNull { transaction -> observation(transaction)?.let { transaction to it } }
            .flatMap { (transaction, observed) ->
                val spendingTxid = transaction.txid.trim().lowercase()
                if (!TXID.matches(spendingTxid)) return@flatMap emptySequence()
                transaction.spentOutpoints.asSequence().mapNotNull { (rawTxid, vout) ->
                    val key = "${rawTxid.trim().lowercase()}:$vout"
                    val outpoint = reserved[key] ?: return@mapNotNull null
                    DogecoinSpvConflictingSpendEvidence(
                        reservedTxid = outpoint.first,
                        reservedVout = outpoint.second,
                        spendingTxid = spendingTxid,
                        depth = observed.second,
                        provenance = observed.first
                    )
                }
            }
            .distinctBy { "${it.reservedTxid}:${it.reservedVout}:${it.spendingTxid}" }
            .sortedWith(compareBy({ it.reservedTxid }, { it.reservedVout }, { it.spendingTxid }))
            .toList()

        return DogecoinSpvSettlementEvidence(
            network = DogecoinNetwork.MAINNET,
            expectedTxid = expected,
            exactTransactionDepth = exact?.second,
            exactTransactionProvenance = exact?.first ?: DogecoinSpvEvidenceProvenance.NONE,
            fullySynced = status.synced,
            peerCount = status.peerCount,
            peerFloorMet = status.peerCount >= DogecoinSpvService.MIN_PEERS,
            chainHeight = status.chainHeight,
            bestPeerHeight = status.bestPeerHeight,
            chainTipHash = chainTipHash?.trim()?.lowercase()?.takeIf(TXID::matches),
            conflictingSpends = conflicts
        )
    }

    private fun observation(
        transaction: DogecoinSpvWalletTransactionFact
    ): Pair<DogecoinSpvEvidenceProvenance, Int>? = when (transaction.confidence) {
        DogecoinSpvWalletConfidence.NETWORK_PENDING -> DogecoinSpvEvidenceProvenance.PEER to 0
        DogecoinSpvWalletConfidence.CHAIN_BUILDING ->
            transaction.depth.takeIf { it > 0 }?.let { DogecoinSpvEvidenceProvenance.CHAIN to it }
        DogecoinSpvWalletConfidence.LOCAL_PENDING,
        DogecoinSpvWalletConfidence.DEAD_OR_CONFLICTING,
        DogecoinSpvWalletConfidence.UNKNOWN -> null
    }
}
