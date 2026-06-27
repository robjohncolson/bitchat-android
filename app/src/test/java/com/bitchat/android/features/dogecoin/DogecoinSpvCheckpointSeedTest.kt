package com.bitchat.android.features.dogecoin

import org.bitcoinj.core.CheckpointManager
import org.bitcoinj.core.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
