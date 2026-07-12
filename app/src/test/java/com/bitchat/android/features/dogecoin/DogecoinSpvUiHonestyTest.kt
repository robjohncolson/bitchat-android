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

    @Test
    fun `persisted SPV owns the selected supported chain while other backends stop it`() {
        assertEquals(
            DogecoinNetwork.TESTNET,
            dogecoinSpvTargetNetwork(DogecoinBackend.SPV, DogecoinNetwork.TESTNET, supported = true)
        )
        assertEquals(
            DogecoinNetwork.MAINNET,
            dogecoinSpvTargetNetwork(DogecoinBackend.SPV, DogecoinNetwork.MAINNET, supported = true)
        )
        assertEquals(
            null,
            dogecoinSpvTargetNetwork(DogecoinBackend.RPC, DogecoinNetwork.TESTNET, supported = true)
        )
        assertEquals(
            null,
            dogecoinSpvTargetNetwork(DogecoinBackend.EXPLORER, DogecoinNetwork.MAINNET, supported = true)
        )
        assertEquals(
            null,
            dogecoinSpvTargetNetwork(DogecoinBackend.SPV, DogecoinNetwork.REGTEST, supported = false)
        )
    }

    @Test
    fun `wrong-chain process status is idle and unsynced for the selected sheet`() {
        val testnetStatus = DogecoinSpvStatus(
            network = DogecoinNetwork.TESTNET,
            running = true,
            peerCount = 4,
            chainHeight = 67_000_000,
            bestPeerHeight = 67_000_000L,
            synced = true,
            overTor = true,
            stalled = true
        )

        val projected = dogecoinSpvStatusForSelectedNetwork(testnetStatus, DogecoinNetwork.MAINNET)

        assertEquals(DogecoinNetwork.MAINNET, projected.network)
        assertFalse(projected.running)
        assertFalse(projected.synced)
        assertEquals(0, projected.peerCount)
        assertEquals(0, projected.chainHeight)
        assertFalse(projected.overTor)
        assertFalse(projected.stalled)
    }

    @Test
    fun `matching-chain process status is preserved`() {
        val status = DogecoinSpvStatus(
            network = DogecoinNetwork.MAINNET,
            running = true,
            peerCount = 6,
            chainHeight = 6_286_946,
            bestPeerHeight = 6_286_946L,
            synced = true
        )

        assertSame(status, dogecoinSpvStatusForSelectedNetwork(status, DogecoinNetwork.MAINNET))
    }
}
