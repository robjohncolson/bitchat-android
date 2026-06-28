package com.bitchat.android.features.dogecoin

import org.bitcoinj.core.CheckpointManager
import org.bitcoinj.core.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libdohj.params.DogecoinMainNetParams
import org.libdohj.params.DogecoinTestNet3Params
import java.io.File

/**
 * Pins the SPV checkpoint-seeding premise that lets DogecoinSpvService.maybeLoadCheckpoints seed via
 * CheckpointManager.getCheckpointBefore(birthdate) (NO margin) instead of the static
 * CheckpointManager.checkpoint(...,time), which pushes the seed ~7 days earlier (≈1M extra testnet
 * headers, ~50 min on-device). See docs/dogecoin-spv-phase3-plan.md.
 *
 *  - A freshly-generated key (birthdate ~now) seeds at the LATEST shipped checkpoint -> fast first sync.
 *    SAFE because no funds can predate the key's creation time.
 *  - An imported key's conservative 2021 floor precedes every shipped checkpoint -> genesis fallback,
 *    so old funds are never skipped (slow but correct).
 */
class DogecoinSpvCheckpointSeedTest {

    private val params = DogecoinTestNet3Params.get()

    private fun manager(): CheckpointManager {
        Context.propagate(Context(params))
        val asset = listOf(
            "src/main/assets/dogecoin-checkpoints-testnet.txt",
            "app/src/main/assets/dogecoin-checkpoints-testnet.txt",
        ).map(::File).firstOrNull { it.exists() }
        assertTrue("testnet checkpoint asset not found from ${File(".").absolutePath}", asset != null)
        return asset!!.inputStream().use { CheckpointManager(params, it) }
    }

    @Test
    fun `recent birthdate seeds at the latest shipped checkpoint (no margin, fast)`() {
        // Any birthdate at/after the newest checkpoint floor-selects it — the fast no-margin path.
        // (If the shipped asset gains a newer checkpoint, update this height — that is the intended signal.)
        assertEquals(65648620, manager().getCheckpointBefore(4102444800L).height) // 2100-01-01Z
    }

    @Test
    fun `imported-key 2021 birthdate floor falls back to genesis (safe, never skips old funds)`() {
        // 2021-01-01Z precedes every shipped testnet checkpoint -> getCheckpointBefore returns genesis.
        assertEquals(0, manager().getCheckpointBefore(1609459200L).height)
    }

    // ---- Mainnet (Phase 4). The asset is built from AuxPoW (merge-mined) headers; the generator stores
    // only the 80-byte BASE header. These pin that libdohj 0.14.7 PARSES that header (CheckpointManager
    // throws on a malformed/oversized record) AND reconstructs the correct block hash. ----

    private val mainnetParams = DogecoinMainNetParams.get()

    private fun mainnetManager(): CheckpointManager {
        Context.propagate(Context(mainnetParams))
        val asset = listOf(
            "src/main/assets/dogecoin-checkpoints-mainnet.txt",
            "app/src/main/assets/dogecoin-checkpoints-mainnet.txt",
        ).map(::File).firstOrNull { it.exists() }
        assertTrue("mainnet checkpoint asset not found from ${File(".").absolutePath}", asset != null)
        return asset!!.inputStream().use { CheckpointManager(mainnetParams, it) }
    }

    @Test
    fun `mainnet checkpoint parses in libdohj 0_14_7 and the seeded head hashes to the node block`() {
        // Loading the manager parses every AuxPoW 80-byte base header; a parse/length failure would throw.
        val head = mainnetManager().getCheckpointBefore(4102444800L) // 2100-01-01Z -> newest shipped cp
        assertEquals(6265104, head.height)
        // The reconstructed block hash must equal `getblockhash 6265104` on the mainnet node — proves the
        // 80-byte base header (AuxPoW tail dropped) was parsed correctly, not merely that the file loaded.
        assertEquals(
            "c5e6395c015cc7f98711906e3670cedfbbda0696f7e6eda381249d47c1c5e4a4",
            head.header.hashAsString.lowercase()
        )
    }

    @Test
    fun `mainnet 2021 floor birthdate precedes every checkpoint - genesis (fresh keys must birthdate=now)`() {
        // Oldest mainnet checkpoint is 2022-06-15, so an imported-key 2021 floor falls back to genesis.
        // A freshly GENERATED mainnet key birthdates at now (loadOrCreateWallet) and seeds at the latest cp;
        // a stale/pre-existing mainnet key on the 2021 floor would (incorrectly for a fresh key) sync from
        // genesis — the exact snag seen on-device during the Phase 4 soak.
        assertEquals(0, mainnetManager().getCheckpointBefore(1609459200L).height)
    }
}
