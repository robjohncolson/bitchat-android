package com.bitchat.android.features.dogecoin

import org.bitcoinj.core.Context
import org.bitcoinj.core.NetworkParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.libdohj.params.DogecoinMainNetParams
import org.libdohj.params.DogecoinTestNet3Params

/**
 * Phase 3 money-safety gate (docs/dogecoin-spv-phase3-plan.md). [DogecoinSpvBroadcastVerifier] is the
 * single chokepoint that must guarantee SPV peers receive the SIGNER'S EXACT BYTES — never bitcoinj's
 * own re-encoding. These pure-JVM tests pin every fail-closed branch without a PeerGroup or network.
 *
 * The happy-path round-trip is run over the SAME golden transactions as [DogecoinSignerRegressionTest]
 * (mainnet/testnet, compressed/uncompressed, single/multi-input) so that if bitcoinj re-serialized ANY
 * shape bitchat can emit even slightly differently, FC2 would (correctly) trip — proving it does not.
 */
class DogecoinSpvBroadcastVerifierTest {

    private data class Golden(
        val name: String,
        val hex: String,
        val txid: String,
        val params: NetworkParameters,
    )

    private val goldens = listOf(
        Golden("mainnet-compressed", GOLDEN_MAINNET_COMPRESSED_HEX, GOLDEN_MAINNET_COMPRESSED_TXID, DogecoinMainNetParams.get()),
        Golden("testnet-compressed", GOLDEN_TESTNET_COMPRESSED_HEX, GOLDEN_TESTNET_COMPRESSED_TXID, DogecoinTestNet3Params.get()),
        Golden("mainnet-uncompressed", GOLDEN_MAINNET_UNCOMPRESSED_HEX, GOLDEN_MAINNET_UNCOMPRESSED_TXID, DogecoinMainNetParams.get()),
        Golden("mainnet-multi", GOLDEN_MAINNET_MULTI_HEX, GOLDEN_MAINNET_MULTI_TXID, DogecoinMainNetParams.get()),
    )

    @Test
    fun `verifier round-trips every signer golden byte-for-byte and on txid`() {
        goldens.forEach { g ->
            Context.propagate(Context(g.params))
            val normalized = DogecoinRawTxValidator.normalize(g.hex)
            // Sanity: the on-device canonical txid matches the golden (independent of bitcoinj).
            assertEquals("${g.name} canonical txid", g.txid, DogecoinTransactionBuilder.transactionId(normalized))
            val tx = DogecoinSpvBroadcastVerifier.verifiedTransaction(g.params, normalized, g.txid)
            assertEquals("${g.name} bitcoinj txid agrees", g.txid, tx.hashAsString)
            assertEquals(
                "${g.name} bitcoinj re-serializes to the exact signed bytes",
                normalized,
                DogecoinHex.encode(tx.bitcoinSerialize())
            )
        }
    }

    @Test
    fun `verifier fails closed on txid divergence`() {
        val g = goldens.first()
        Context.propagate(Context(g.params))
        val normalized = DogecoinRawTxValidator.normalize(g.hex)
        assertThrows(IllegalArgumentException::class.java) {
            DogecoinSpvBroadcastVerifier.verifiedTransaction(g.params, normalized, "00".repeat(32))
        }
    }

    @Test
    fun `verifier fails closed on trailing bytes (re-serialization diverges)`() {
        val g = goldens.first()
        Context.propagate(Context(g.params))
        val normalized = DogecoinRawTxValidator.normalize(g.hex)
        val txid = DogecoinTransactionBuilder.transactionId(normalized)
        // Append a trailing byte: bitcoinj parses the tx and re-serializes WITHOUT it, so the byte-equality
        // (or bitcoinj's own parse) rejects it. Any throw means fail-closed (nothing is broadcast).
        assertThrows(Exception::class.java) {
            DogecoinSpvBroadcastVerifier.verifiedTransaction(g.params, normalized + "00", txid)
        }
    }

    @Test
    fun `verifier fails closed on segwit-marked serialization`() {
        val params = DogecoinTestNet3Params.get()
        Context.propagate(Context(params))
        // version (01000000) followed by the BIP144 witness marker 0x00 — must be rejected before parsing.
        assertThrows(IllegalArgumentException::class.java) {
            DogecoinSpvBroadcastVerifier.verifiedTransaction(params, "0100000000", "00".repeat(32))
        }
    }

    @Test
    fun `default serializer is non-retaining so the byte check is a genuine re-encode`() {
        // FC2 is only load-bearing if bitcoinj re-encodes from the parsed fields rather than echoing the
        // cached input payload. The default serializer must NOT be in parse-retain mode for that to hold.
        assertFalse(DogecoinTestNet3Params.get().defaultSerializer.isParseRetainMode)
        assertFalse(DogecoinMainNetParams.get().defaultSerializer.isParseRetainMode)
    }

    private companion object {
        // Mirrors of DogecoinSignerRegressionTest goldens (its constants are private). Stable forever
        // unless the signer changes — if these drift, the regression canary fails first; do not re-baseline.
        const val GOLDEN_MAINNET_COMPRESSED_HEX = "0100000001ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100000000006a47304402205c98e79f643a50396c3860db13b53da6853e110c06566550bd1e75582c52dca402203dc4b378340b796502eba68a7b575d7c3f5a5b3c4149c35431515f1b4c0fd5ff012103cc8704b8a60a0defa3a99a7299f2e9c3fbc395afb04ac078425ef8a1793cc030ffffffff0200c2eb0b000000001976a9149c9f2bacc8ce066e1f26296789310e8d7d41c64c88acc0c59f2f000000001976a9141020aa5e7d9f69bcfe34afd6ae5352425c30655288ac00000000"
        const val GOLDEN_MAINNET_COMPRESSED_TXID = "56f4d3a474f4495dbc0e96c31646f4caa0dbd8cf72a7886f94c71b53c8e15c30"
        const val GOLDEN_TESTNET_COMPRESSED_HEX = "01000000014444444444444444444444444444444444444444444444444444444444444444000000006a4730440220574fb96aae1852e1bd35ae276f5aaf24f8a8161610153698087452f17e0c5e2602205ef9805e01779f044c24955e6fe6536fe6d7b45ccc82b2d300d8261802fde8d8012102270f3a184b42c799a2193daec96f834c0b17cb53bba1610c6578c7410b9f20d7ffffffff0200a3e111000000001976a9146b5a4d5020e5f03f1b1577747f7566d5fc5a84ba88acc003b423000000001976a9144ef9e17998750fde16e08e0075ccb57f38d99bf988ac00000000"
        const val GOLDEN_TESTNET_COMPRESSED_TXID = "1c1b03d16e5e8fc2288b40bce9ef35cd0c2ec3eb192fcd0b1a112f9770dc4d0a"
        const val GOLDEN_MAINNET_UNCOMPRESSED_HEX = "01000000013333333333333333333333333333333333333333333333333333333333333333010000008b483045022100edb9e61442ef027ba89dbb8314f87b4eb55bd42aeae5234601b857b148da05440220135b8850588723b28f0a11025ca495dec3a8a9c7b87dd416c0a34dd742d1880b014104be2062003c51cc3004682904330e4dee7f3dcd10b01e580bf1971b04d4cad29762188bc49d61e5428573d48a74e1c655b1c61090905682a0d5558ed72dccb9bcffffffff0200c2eb0b000000001976a9140c49d761737c56f0c907ff4604fa5612d479023488acc022be1d000000001976a9149542625e56326eff5f686e37b5fbbf59f9ec311488ac00000000"
        const val GOLDEN_MAINNET_UNCOMPRESSED_TXID = "caa121c1007ce8118b6533bea85a3a869d0dffe57f83cc6d996edccea22c5c79"
        const val GOLDEN_MAINNET_MULTI_HEX = "01000000021111111111111111111111111111111111111111111111111111111111111111000000006b4830450221008cab11eb8e045b3d41953063a4c6fdedca7a078925fe65e48cf2010c6a33db2d02206e6f2e97731d9af01a5da5dbbf03c4b18b2cae998ac53fbceabb68538cbde28e01210313e87b027d8514d35939f2e6892b19922154596941888336dc3563e3b8dba942ffffffff2222222222222222222222222222222222222222222222222222222222222222030000006b483045022100908afe0072b8b00d69a09f5ac087a3df2387d9bcf2c69c23b66ca14beab7096f02204a3703106808a8207f79129a4faa7927a190c50c28b8d805f4e72245d64d9e8901210313e87b027d8514d35939f2e6892b19922154596941888336dc3563e3b8dba942ffffffff020046c323000000001976a914306a469d675abc5dc1eb4f67edbee9b877686d3488acc041c817000000001976a914d7383821e6c0049159d5e217f2644ea29f25122f88ac00000000"
        const val GOLDEN_MAINNET_MULTI_TXID = "4054fe107e78d8260b0606c727a6d93a6c94eb1ee44abc1bb72f1e25954e6cff"
    }
}
