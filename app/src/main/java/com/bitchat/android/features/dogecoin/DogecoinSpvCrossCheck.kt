package com.bitchat.android.features.dogecoin

/**
 * A single unspent output as reported by the node's UTXO set (`gettxout`). Read-only oracle datum for
 * the SPV cross-check; never used to sign or broadcast.
 */
data class DogecoinTxOut(
    val amountKoinu: Long,
    val scriptPubKeyHex: String,
    val confirmations: Int
)

/**
 * Read-only SPV-vs-node UTXO cross-check — the Phase 4 mainnet read-only soak validation surface
 * (docs/dogecoin-spv-integration-plan.md). For every UTXO the SPV wallet believes it can spend, the
 * node's UTXO set (`gettxout`) is the oracle: the outpoint must still be UNSPENT, with the same amount
 * and the same output script.
 *
 * This validates the SAFETY-CRITICAL direction — SPV must never present a spent/forged UTXO as
 * spendable, which would let the on-device signer build a tx the network rejects (or worse, double-spend
 * the user's own funds across backends). The reverse direction (a node UTXO the SPV missed) is the SAFE
 * direction — it only under-reports balance — and on a Dogecoin Core 1.14.x node it can't be checked
 * cheaply anyway (no `scantxoutset`; a full address rescan on the multi-million-block testnet/mainnet is
 * impractical). `gettxout` needs no wallet import and hits the chainstate directly, so it works per-
 * outpoint instantly on 1.14.x.
 *
 * Pure + deterministic: the IO (one `gettxout` per SPV UTXO) is done by the caller and handed in as a
 * map, so the comparison logic is unit-testable without a node.
 */
object DogecoinSpvCrossCheck {

    enum class Status {
        /** Node confirms the outpoint is unspent with the same amount + script. */
        MATCH,
        /** Outpoint unspent but the node reports a different amount than SPV. */
        AMOUNT_MISMATCH,
        /** Outpoint unspent but the node's output script differs from SPV's. */
        SCRIPT_MISMATCH,
        /** Node has no such unspent output (spent, never existed, or not yet seen by the node). */
        SPENT_OR_MISSING
    }

    data class Entry(
        val txid: String,
        val vout: Int,
        val spvKoinu: Long,
        val oracleKoinu: Long?,
        val oracleConfirmations: Int?,
        val status: Status
    )

    data class Report(
        val entries: List<Entry>,
        val spvTotalKoinu: Long,
        /** Sum of the amounts the node confirmed unspent (MATCH entries only). */
        val nodeConfirmedKoinu: Long,
        val allMatch: Boolean
    ) {
        val mismatches: List<Entry> get() = entries.filter { it.status != Status.MATCH }
    }

    /** Canonical outpoint key, `<lowercase-txid>:<vout>`. */
    fun outpoint(txid: String, vout: Int): String = "${txid.lowercase()}:$vout"

    /**
     * Compare the SPV unspent set against the node oracle. [oracleByOutpoint] maps [outpoint] -> the
     * node's `gettxout` result for that outpoint, or null when the node reports it spent/absent.
     */
    fun compare(
        spvUtxos: List<DogecoinUtxo>,
        oracleByOutpoint: Map<String, DogecoinTxOut?>
    ): Report {
        val entries = spvUtxos.map { u ->
            val out = oracleByOutpoint[outpoint(u.txid, u.vout)]
            val status = when {
                out == null -> Status.SPENT_OR_MISSING
                out.amountKoinu != u.amountKoinu -> Status.AMOUNT_MISMATCH
                u.scriptPubKeyHex.isNotBlank() &&
                    !out.scriptPubKeyHex.equals(u.scriptPubKeyHex, ignoreCase = true) -> Status.SCRIPT_MISMATCH
                else -> Status.MATCH
            }
            Entry(
                txid = u.txid,
                vout = u.vout,
                spvKoinu = u.amountKoinu,
                oracleKoinu = out?.amountKoinu,
                oracleConfirmations = out?.confirmations,
                status = status
            )
        }
        return Report(
            entries = entries,
            spvTotalKoinu = spvUtxos.sumOf { it.amountKoinu },
            nodeConfirmedKoinu = entries.filter { it.status == Status.MATCH }.sumOf { it.oracleKoinu ?: 0L },
            allMatch = entries.isNotEmpty() && entries.all { it.status == Status.MATCH }
        )
    }
}
