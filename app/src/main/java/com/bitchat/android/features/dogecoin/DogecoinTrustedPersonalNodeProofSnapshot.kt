package com.bitchat.android.features.dogecoin

/** One exact node-reported active-chain tip used to bind a proof collection. */
internal data class DogecoinTrustedPersonalNodeBlockTip(
    val height: Int,
    val hash: String
)

/** A selectable candidate only after its proof and final-tip unspent check both completed. */
internal class DogecoinTrustedPersonalNodeProofCandidate private constructor(
    val verifiedPrevout: DogecoinVerifiedPrevout,
    val finalConfirmations: Int,
    val finalBestBlockHash: String
) {
    companion object {
        fun verifiedAtTip(
            verifiedPrevout: DogecoinVerifiedPrevout,
            finalConfirmations: Int,
            finalBestBlockHash: String
        ): DogecoinTrustedPersonalNodeProofCandidate {
            require(finalConfirmations >= DOGECOIN_TPN_MIN_INPUT_CONFIRMATIONS) {
                "Trusted personal node proof candidate lacks the minimum confirmation depth."
            }
            require(Regex("^[0-9a-f]{64}$").matches(finalBestBlockHash)) {
                "Trusted personal node proof candidate has an invalid final tip hash."
            }
            return DogecoinTrustedPersonalNodeProofCandidate(
                verifiedPrevout = verifiedPrevout,
                finalConfirmations = finalConfirmations,
                finalBestBlockHash = finalBestBlockHash
            )
        }
    }
}

/**
 * Complete DES-1-C proof result. Construction is centralized so callers can never expose the
 * successfully verified prefix of an incomplete collection. This remains a read-only type: it has no
 * conversion to [DogecoinUtxo] and grants no signing or broadcast authority.
 */
internal class DogecoinTrustedPersonalNodeProofSnapshot private constructor(
    val binding: DogecoinTrustedPersonalNodeSessionBinding,
    val capturedAtMonotonicMillis: Long,
    val startTip: DogecoinTrustedPersonalNodeBlockTip,
    val endTip: DogecoinTrustedPersonalNodeBlockTip,
    proofCandidates: List<DogecoinTrustedPersonalNodeProofCandidate>,
    val totalProofBytes: Int
) {
    val proofCandidates: List<DogecoinTrustedPersonalNodeProofCandidate> = proofCandidates.toList()
    val verifiedPrevouts: List<DogecoinVerifiedPrevout> =
        this.proofCandidates.map { it.verifiedPrevout }

    companion object {
        fun complete(
            binding: DogecoinTrustedPersonalNodeSessionBinding,
            capturedAtMonotonicMillis: Long,
            startTip: DogecoinTrustedPersonalNodeBlockTip,
            endTip: DogecoinTrustedPersonalNodeBlockTip,
            proofCandidates: List<DogecoinTrustedPersonalNodeProofCandidate>,
            totalProofBytes: Int
        ): DogecoinTrustedPersonalNodeProofSnapshot {
            require(binding.network == DogecoinNetwork.MAINNET) {
                "Trusted personal node proof snapshots are mainnet-only."
            }
            require(exactDogecoinTrustedPersonalNodeOriginOrNull(binding.origin) == binding.origin) {
                "Trusted personal node proof snapshot origin is invalid."
            }
            require(DogecoinAddress.isValidP2pkhAddress(binding.androidAddress, binding.network)) {
                "Trusted personal node proof snapshot address is invalid."
            }
            require(canonicalDogecoinTrustedPersonalNodeWalletIdOrNull(binding.coreWalletId) == binding.coreWalletId) {
                "Trusted personal node proof snapshot wallet identity is invalid."
            }
            require(
                binding.policyVersion == DOGECOIN_TRUSTED_PERSONAL_NODE_POLICY_VERSION &&
                    binding.profileRevision > 0L
            ) {
                "Trusted personal node proof snapshot policy binding is invalid."
            }
            require(capturedAtMonotonicMillis >= 0L) {
                "Trusted personal node proof snapshot time must be monotonic and non-negative."
            }
            require(isValidTip(startTip) && isValidTip(endTip)) {
                "Trusted personal node proof snapshot contains an invalid block tip."
            }
            require(endTip.height >= startTip.height) {
                "Trusted personal node proof snapshot tip regressed."
            }
            require(endTip.height - startTip.height <= DOGECOIN_TPN_MAX_SNAPSHOT_TIP_EXTENSION) {
                "Trusted personal node proof snapshot tip advanced beyond the allowed bound."
            }
            if (endTip.height == startTip.height) {
                require(endTip.hash == startTip.hash) {
                    "Trusted personal node proof snapshot tip was replaced at the same height."
                }
            }
            require(proofCandidates.size <= DOGECOIN_TPN_MAX_PROOF_CANDIDATES) {
                "Trusted personal node proof snapshot has too many candidates."
            }
            require(
                proofCandidates.distinctBy {
                    "${it.verifiedPrevout.txid}:${it.verifiedPrevout.vout}"
                }.size == proofCandidates.size
            ) {
                "Trusted personal node proof snapshot contains a duplicate outpoint."
            }
            val boundScriptPubKeyHex = DogecoinHex.encode(
                DogecoinAddress.p2pkhScript(binding.androidAddress, binding.network)
            )
            require(
                proofCandidates.all {
                    it.verifiedPrevout.scriptPubKeyHex == boundScriptPubKeyHex
                }
            ) {
                "Trusted personal node proof candidate is not owned by the bound Android address."
            }
            require(proofCandidates.all { it.finalBestBlockHash == endTip.hash }) {
                "Trusted personal node proof candidates are not bound to the snapshot end tip."
            }
            val exactTotal = proofCandidates.fold(0L) { sum, candidate ->
                Math.addExact(sum, candidate.verifiedPrevout.previousTransactionByteCount.toLong())
            }
            require(exactTotal == totalProofBytes.toLong()) {
                "Trusted personal node proof snapshot byte total is inconsistent."
            }
            require(totalProofBytes in 0..DOGECOIN_TPN_MAX_SNAPSHOT_PROOF_BYTES) {
                "Trusted personal node proof snapshot exceeds its aggregate byte limit."
            }
            return DogecoinTrustedPersonalNodeProofSnapshot(
                binding = binding,
                capturedAtMonotonicMillis = capturedAtMonotonicMillis,
                startTip = startTip,
                endTip = endTip,
                proofCandidates = proofCandidates,
                totalProofBytes = totalProofBytes
            )
        }

        private fun isValidTip(tip: DogecoinTrustedPersonalNodeBlockTip): Boolean =
            tip.height >= 0 && EXACT_LOWER_TXID.matches(tip.hash)

        private val EXACT_LOWER_TXID = Regex("^[0-9a-f]{64}$")
    }
}

internal const val DOGECOIN_TPN_MIN_INPUT_CONFIRMATIONS = 6
internal const val DOGECOIN_TPN_MAX_PROOF_CANDIDATES = 64
internal const val DOGECOIN_TPN_MAX_SNAPSHOT_TIP_EXTENSION = 2
