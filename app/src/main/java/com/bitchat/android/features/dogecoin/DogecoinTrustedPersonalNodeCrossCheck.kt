package com.bitchat.android.features.dogecoin

/** One bound outpoint as reported by the node at the exact snapshot tip, or null when spent/absent. */
internal data class DogecoinTrustedPersonalNodeCrossCheckTxOut(
    val amountKoinu: Long,
    val scriptPubKeyHex: String,
    val confirmations: Int
)

/**
 * The node side of one fixed read-only comparison. Expected values are the hash-committed prevouts
 * persisted before disclosure; [nodeTxOut] is only the node's current UTXO-set claim.
 */
internal data class DogecoinTrustedPersonalNodeCrossCheckOutpoint(
    val txid: String,
    val vout: Int,
    val expectedAmountKoinu: Long,
    val expectedScriptPubKeyHex: String,
    val nodeTxOut: DogecoinTrustedPersonalNodeCrossCheckTxOut?
)

internal data class DogecoinTrustedPersonalNodeCrossCheckSnapshot(
    val binding: DogecoinTrustedPersonalNodeSessionBinding,
    val expectedTxid: String,
    val tip: DogecoinTrustedPersonalNodeBlockTip,
    val outpoints: List<DogecoinTrustedPersonalNodeCrossCheckOutpoint>,
    val capturedAtMillis: Long
)

/**
 * Pure DES-1-E comparator. Node confirmations and node transaction status never corroborate the
 * attempt. Agreement requires independent, fully-synced SPV inclusion of the exact transaction and
 * a stable node view in which every input is now spent. SPV absence is deliberately inconclusive.
 */
internal object DogecoinTrustedPersonalNodeCrossCheck {
    private val EXACT_HASH = Regex("^[0-9a-f]{64}$")
    private val EXACT_HEX = Regex("^(?:[0-9a-f]{2})+$")

    fun compare(
        spv: DogecoinSpvSettlementEvidence,
        node: DogecoinTrustedPersonalNodeCrossCheckSnapshot
    ): DogecoinTrustedPersonalNodeCrossCheckEvidence {
        val fullySyncedMainnet = spv.network == DogecoinNetwork.MAINNET &&
            spv.fullySynced &&
            spv.peerFloorMet &&
            spv.chainTipHash?.let(EXACT_HASH::matches) == true
        val stableConflicts = spv.conflictingSpends.filter {
            it.provenance == DogecoinSpvEvidenceProvenance.CHAIN &&
                it.depth >= DOGECOIN_SPV_CONFIRM_TARGET
        }
        val exactDepth = spv.exactTransactionDepth
            ?.takeIf {
                spv.exactTransactionProvenance == DogecoinSpvEvidenceProvenance.CHAIN && it > 0
            }
            ?: 0
        val confirmationContextDepth = maxOf(
            exactDepth,
            stableConflicts.maxOfOrNull { it.depth } ?: 0
        )
        val shapeValid = isValidSnapshotShape(node) &&
            spv.expectedTxid == node.expectedTxid
        // UTXO spent visibility is meaningful only at the same validated chain point. "Near" heights
        // are insufficient because neither side supplies an ancestry proof for a different tip hash.
        // A moving tip therefore yields INCONCLUSIVE and the operator can take another fresh sample.
        val tipsExactlyBound = shapeValid &&
            node.tip.height == spv.chainHeight &&
            node.tip.hash == spv.chainTipHash
        val nodeInvariantConflict = shapeValid && node.outpoints.any { outpoint ->
            outpoint.nodeTxOut?.let { current ->
                current.amountKoinu != outpoint.expectedAmountKoinu ||
                    current.scriptPubKeyHex != outpoint.expectedScriptPubKeyHex
            } == true
        }
        val exactSettledBySpv = fullySyncedMainnet &&
            exactDepth >= DOGECOIN_SPV_CONFIRM_TARGET
        val spvEvidenceContradictory = exactSettledBySpv && stableConflicts.isNotEmpty()
        val nodeContradictsStableConflict = stableConflicts.any { conflict ->
            node.outpoints.any { outpoint ->
                outpoint.txid == conflict.reservedTxid &&
                    outpoint.vout == conflict.reservedVout &&
                    outpoint.nodeTxOut != null
            }
        }
        val result = when {
            !shapeValid || !tipsExactlyBound || spvEvidenceContradictory ->
                DogecoinTrustedPersonalNodeCrossCheckResult.INCONCLUSIVE
            nodeInvariantConflict -> DogecoinTrustedPersonalNodeCrossCheckResult.CONFLICT
            // A different stable SPV spender settles the attempt, but is not by itself evidence that
            // the node lied. It disputes the profile only when the same node snapshot still claims the
            // spent reserved input is unspent. If both sources say "spent", the outcome remains
            // inconclusive for profile recovery because they do not agree on the exact attempted tx.
            nodeContradictsStableConflict -> DogecoinTrustedPersonalNodeCrossCheckResult.CONFLICT
            exactSettledBySpv && node.outpoints.any { it.nodeTxOut != null } ->
                DogecoinTrustedPersonalNodeCrossCheckResult.CONFLICT
            exactSettledBySpv && node.outpoints.all { it.nodeTxOut == null } ->
                DogecoinTrustedPersonalNodeCrossCheckResult.AGREEMENT
            else -> DogecoinTrustedPersonalNodeCrossCheckResult.INCONCLUSIVE
        }
        val spvTip = spv.chainTipHash?.takeIf(EXACT_HASH::matches) ?: "0".repeat(64)
        val nodeTip = node.tip.hash.takeIf(EXACT_HASH::matches) ?: "0".repeat(64)
        return DogecoinTrustedPersonalNodeCrossCheckEvidence(
            comparisonId = "$spvTip:$nodeTip",
            result = result,
            fullySyncedMainnet = fullySyncedMainnet,
            confirmationContextDepth = confirmationContextDepth,
            hasConflictingSpend = stableConflicts.isNotEmpty(),
            capturedAtMillis = node.capturedAtMillis
        )
    }

    private fun isValidSnapshotShape(
        snapshot: DogecoinTrustedPersonalNodeCrossCheckSnapshot
    ): Boolean {
        val binding = snapshot.binding
        if (binding.network != DogecoinNetwork.MAINNET ||
            exactDogecoinTrustedPersonalNodeOriginOrNull(binding.origin) != binding.origin ||
            !DogecoinAddress.isValidP2pkhAddress(binding.androidAddress, binding.network) ||
            canonicalDogecoinTrustedPersonalNodeWalletIdOrNull(binding.coreWalletId) !=
            binding.coreWalletId ||
            binding.policyVersion != DOGECOIN_TRUSTED_PERSONAL_NODE_POLICY_VERSION ||
            binding.profileRevision <= 0L) return false
        if (!EXACT_HASH.matches(snapshot.expectedTxid) ||
            !EXACT_HASH.matches(snapshot.tip.hash) || snapshot.tip.height < 0) return false
        if (snapshot.capturedAtMillis <= 0L || snapshot.outpoints.isEmpty() ||
            snapshot.outpoints.size > DOGECOIN_TPN_MAX_PROOF_CANDIDATES) return false
        val keys = HashSet<String>()
        val boundScript = DogecoinHex.encode(
            DogecoinAddress.p2pkhScript(binding.androidAddress, binding.network)
        )
        return snapshot.outpoints.all { outpoint ->
            val key = "${outpoint.txid}:${outpoint.vout}"
            EXACT_HASH.matches(outpoint.txid) &&
                outpoint.vout >= 0 &&
                outpoint.expectedAmountKoinu in 1..DOGECOIN_TPN_MAX_MONEY_KOINU &&
                outpoint.expectedScriptPubKeyHex == boundScript &&
                keys.add(key) &&
                outpoint.nodeTxOut?.let { current ->
                    current.amountKoinu > 0L &&
                        EXACT_HEX.matches(current.scriptPubKeyHex) &&
                        current.confirmations >= 0
                } != false
        }
    }
}
