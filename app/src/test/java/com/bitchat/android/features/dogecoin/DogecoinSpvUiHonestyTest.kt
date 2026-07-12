package com.bitchat.android.features.dogecoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DogecoinSpvUiHonestyTest {
    @Test
    fun `stopped SPV reports starting even if an old peer height exists`() {
        assertSame(
            DogecoinSpvBehindLabel.Starting,
            dogecoinSpvBehindLabel(bestPeerHeight = 6_286_854L, chainHeight = 6_285_445, running = false)
        )
    }

    @Test
    fun `running SPV with unknown peer height reports finding peers not zero behind`() {
        assertSame(
            DogecoinSpvBehindLabel.FindingPeers,
            dogecoinSpvBehindLabel(bestPeerHeight = 0L, chainHeight = 6_285_445, running = true)
        )
        assertSame(
            DogecoinSpvBehindLabel.FindingPeers,
            dogecoinSpvBehindLabel(bestPeerHeight = -1L, chainHeight = 6_285_445, running = true)
        )
    }

    @Test
    fun `known peer tip reports exact nonnegative distance`() {
        assertEquals(
            DogecoinSpvBehindLabel.Behind(1_409),
            dogecoinSpvBehindLabel(bestPeerHeight = 6_286_854L, chainHeight = 6_285_445, running = true)
        )
        assertEquals(
            DogecoinSpvBehindLabel.Behind(0),
            dogecoinSpvBehindLabel(bestPeerHeight = 6_286_854L, chainHeight = 6_286_854, running = true)
        )
        assertEquals(
            DogecoinSpvBehindLabel.Behind(0),
            dogecoinSpvBehindLabel(bestPeerHeight = 6_286_854L, chainHeight = 6_286_900, running = true)
        )
    }

    @Test
    fun `unknown peer tip uses minimum ring progress instead of near complete`() {
        assertEquals(0.04f, dogecoinSpvSyncProgress(DogecoinSpvBehindLabel.FindingPeers), 0f)
        assertEquals(0.04f, dogecoinSpvSyncProgress(DogecoinSpvBehindLabel.Starting), 0f)
        assertEquals(0.5f, dogecoinSpvSyncProgress(DogecoinSpvBehindLabel.Behind(1_500)), 0f)
        assertEquals(0.97f, dogecoinSpvSyncProgress(DogecoinSpvBehindLabel.Behind(0)), 0f)
    }

    @Test
    fun `SPV balance polls until both known and synced`() {
        assertFalse(
            shouldPollDogecoinSpvBalance(
                effectiveBackend = DogecoinBackend.RPC,
                running = true,
                synced = false,
                balanceKnown = false
            )
        )
        assertFalse(
            shouldPollDogecoinSpvBalance(
                effectiveBackend = DogecoinBackend.SPV,
                running = false,
                synced = false,
                balanceKnown = false
            )
        )
        assertTrue(
            shouldPollDogecoinSpvBalance(
                effectiveBackend = DogecoinBackend.SPV,
                running = true,
                synced = false,
                balanceKnown = false
            )
        )
        assertTrue(
            shouldPollDogecoinSpvBalance(
                effectiveBackend = DogecoinBackend.SPV,
                running = true,
                synced = false,
                balanceKnown = true
            )
        )
        assertTrue(
            shouldPollDogecoinSpvBalance(
                effectiveBackend = DogecoinBackend.SPV,
                running = true,
                synced = true,
                balanceKnown = false
            )
        )
        assertFalse(
            shouldPollDogecoinSpvBalance(
                effectiveBackend = DogecoinBackend.SPV,
                running = true,
                synced = true,
                balanceKnown = true
            )
        )
    }
}
