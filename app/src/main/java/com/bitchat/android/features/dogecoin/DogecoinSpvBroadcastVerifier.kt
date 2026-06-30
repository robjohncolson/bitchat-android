package com.bitchat.android.features.dogecoin

import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction

/**
 * Phase 3 fail-closed cross-check for SPV broadcast (Option B).
 *
 * bitcoinj/libdohj is ONLY a broadcast sink — [DogecoinTransactionBuilder] is the sole signer and its
 * bytes are canonical. Before any tx is handed to `PeerGroup.broadcastTransaction`, this verifier proves
 * bitcoinj will put the SIGNER'S EXACT BYTES on the wire (never bitcoinj's own re-encoding):
 *
 *  - FC4: the tx carries no witness/segwit data (Dogecoin is legacy-only).
 *  - FC2 (load-bearing): bitcoinj re-serializes byte-for-byte identical to the signed input. This is a
 *    GENUINE re-encode, not a cached-payload echo: `Transaction(params, bytes)` uses
 *    `params.getDefaultSerializer()`, whose parse-retain mode is OFF, so bitcoinj nulls the cached
 *    payload after parsing and `bitcoinSerialize()` re-encodes from the parsed fields. (A test pins this
 *    non-retaining assumption so a future refactor can't silently weaken FC2 to a tautology.)
 *  - FC3 (cheap redundant cross-guard): bitcoinj's txid equals the canonical on-device txid. Not an
 *    independent implementation — bitcoinj derives the id from the same serialization — but a cheap
 *    backstop.
 *
 * ANY divergence THROWS. Callers must NEVER broadcast unless this returns. The returned [Transaction] is
 * the verified, immutable object to broadcast; it must not be re-signed or mutated before the wire.
 *
 * Stateless and PeerGroup-free so every fail-closed branch is unit-testable on the JVM. The caller is
 * responsible for `Context.propagate(...)` and for supplying the libdohj [NetworkParameters] for the
 * target network. The input MUST already be normalized via [DogecoinRawTxValidator.normalize].
 */
internal object DogecoinSpvBroadcastVerifier {

    /**
     * @param params libdohj network params (e.g. `DogecoinTestNet3Params.get()`).
     * @param normalizedHex output of [DogecoinRawTxValidator.normalize] (lowercase, structurally valid).
     * @param expectedTxid canonical txid from [DogecoinTransactionBuilder.transactionId] of [normalizedHex].
     * @return the parsed, verified [Transaction]; throws [IllegalArgumentException] on ANY divergence.
     */
    fun verifiedTransaction(
        params: NetworkParameters,
        normalizedHex: String,
        expectedTxid: String,
    ): Transaction {
        val inputBytes = DogecoinHex.decode(normalizedHex)
        // FC4: reject any segwit/witness serialization. Per BIP144 a witness tx carries a 0x00 marker byte
        // immediately after the 4-byte version; a legacy tx has its (>=1) input-count varint there, whose
        // first byte is always non-zero. Dogecoin is legacy-only and the signer never emits witness data.
        // (normalize() also rejects this framing structurally; this keeps the verifier self-contained.)
        require(inputBytes.size >= 5 && (inputBytes[4].toInt() and 0xff) != 0x00) {
            "SPV broadcast aborted: segwit/witness serialization (Dogecoin is legacy-only)."
        }
        val tx = Transaction(params, inputBytes)
        require(tx.bitcoinSerialize().contentEquals(inputBytes)) {
            "SPV broadcast aborted: bitcoinj re-serialization diverged from the signed bytes."
        }
        require(tx.hashAsString.equals(expectedTxid.trim(), ignoreCase = true)) {
            "SPV broadcast aborted: bitcoinj txid did not match the signed transaction."
        }
        return tx
    }
}
