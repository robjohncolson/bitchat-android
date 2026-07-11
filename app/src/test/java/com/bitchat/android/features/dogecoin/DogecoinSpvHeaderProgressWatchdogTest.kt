package com.bitchat.android.features.dogecoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DogecoinSpvHeaderProgressWatchdogTest {
    private fun watchdog() = DogecoinSpvHeaderProgressWatchdog(
        stallTimeoutMillis = 100L,
        recoveryCooldownMillis = 200L,
        maxRecoveryAttempts = 2,
        caughtUpWithinBlocks = 2L
    )

    private fun DogecoinSpvHeaderProgressWatchdog.observe(
        nowMillis: Long,
        height: Int = 100,
        bestPeerHeight: Long = 200L,
        running: Boolean = true,
        peerCount: Int = 1
    ): DogecoinSpvHeaderRecovery = observe(
        nowMillis = nowMillis,
        running = running,
        peerCount = peerCount,
        chainHeight = height,
        bestPeerHeight = bestPeerHeight
    )

    @Test
    fun `frozen far-behind height stalls then uses bounded cooldown recoveries`() {
        val watchdog = watchdog()

        assertEquals(DogecoinSpvHeaderRecovery.NONE, watchdog.observe(nowMillis = 0L))
        assertEquals(DogecoinSpvHeaderRecovery.NONE, watchdog.observe(nowMillis = 99L))
        assertFalse(watchdog.stalled)

        assertEquals(DogecoinSpvHeaderRecovery.ROTATE_DOWNLOAD_PEER, watchdog.observe(nowMillis = 100L))
        assertTrue(watchdog.stalled)
        assertEquals(0, watchdog.recoveryAttempts)
        watchdog.recordRecoveryAttempt(nowMillis = 100L)
        assertEquals(1, watchdog.recoveryAttempts)

        assertEquals(DogecoinSpvHeaderRecovery.NONE, watchdog.observe(nowMillis = 299L))
        assertEquals(DogecoinSpvHeaderRecovery.ROTATE_DOWNLOAD_PEER, watchdog.observe(nowMillis = 300L))
        watchdog.recordRecoveryAttempt(nowMillis = 300L)
        assertEquals(2, watchdog.recoveryAttempts)
        assertEquals(DogecoinSpvHeaderRecovery.NONE, watchdog.observe(nowMillis = 1_000L))
        assertTrue(watchdog.stalled)
    }

    @Test
    fun `height advance clears stall and starts a fresh recovery budget`() {
        val watchdog = watchdog()
        watchdog.observe(nowMillis = 0L)
        assertEquals(DogecoinSpvHeaderRecovery.ROTATE_DOWNLOAD_PEER, watchdog.observe(nowMillis = 100L))
        watchdog.recordRecoveryAttempt(nowMillis = 100L)

        assertEquals(DogecoinSpvHeaderRecovery.NONE, watchdog.observe(nowMillis = 110L, height = 101))
        assertFalse(watchdog.stalled)
        assertEquals(0, watchdog.recoveryAttempts)
        assertEquals(DogecoinSpvHeaderRecovery.NONE, watchdog.observe(nowMillis = 209L, height = 101))
        assertEquals(
            DogecoinSpvHeaderRecovery.ROTATE_DOWNLOAD_PEER,
            watchdog.observe(nowMillis = 210L, height = 101)
        )
    }

    @Test
    fun `peer outage is not a stall and returning peer gets a fresh window`() {
        val watchdog = watchdog()
        watchdog.observe(nowMillis = 0L)
        watchdog.observe(nowMillis = 100L)
        watchdog.recordRecoveryAttempt(nowMillis = 100L)

        assertEquals(
            DogecoinSpvHeaderRecovery.NONE,
            watchdog.observe(nowMillis = 110L, peerCount = 0, bestPeerHeight = 0L)
        )
        assertFalse(watchdog.stalled)
        assertEquals(1, watchdog.recoveryAttempts)

        assertEquals(DogecoinSpvHeaderRecovery.NONE, watchdog.observe(nowMillis = 1_000L))
        assertEquals(DogecoinSpvHeaderRecovery.NONE, watchdog.observe(nowMillis = 1_099L))
        assertEquals(DogecoinSpvHeaderRecovery.ROTATE_DOWNLOAD_PEER, watchdog.observe(nowMillis = 1_100L))
        watchdog.recordRecoveryAttempt(nowMillis = 1_100L)
        assertEquals(2, watchdog.recoveryAttempts)
    }

    @Test
    fun `inactive and caught-up states reset without recovery`() {
        val watchdog = watchdog()
        watchdog.observe(nowMillis = 0L)
        watchdog.observe(nowMillis = 100L)
        watchdog.recordRecoveryAttempt(nowMillis = 100L)

        assertEquals(
            DogecoinSpvHeaderRecovery.NONE,
            watchdog.observe(nowMillis = 1_000L, height = 200, bestPeerHeight = 200L)
        )
        assertEquals(DogecoinSpvHeaderRecovery.NONE, watchdog.observe(nowMillis = 1_100L, running = false))
        assertFalse(watchdog.stalled)
    }

    @Test
    fun `near-tip hysteresis window still watches progress until strictly caught up`() {
        val watchdog = watchdog()
        watchdog.observe(nowMillis = 0L, bestPeerHeight = 105L)

        assertEquals(
            DogecoinSpvHeaderRecovery.ROTATE_DOWNLOAD_PEER,
            watchdog.observe(nowMillis = 100L, bestPeerHeight = 105L)
        )
        assertTrue(watchdog.stalled)

        assertEquals(
            DogecoinSpvHeaderRecovery.NONE,
            watchdog.observe(nowMillis = 101L, bestPeerHeight = 102L)
        )
        assertFalse(watchdog.stalled)
    }

    @Test
    fun `managed rotation requires a strictly higher live replacement`() {
        assertFalse(hasHigherDogecoinSpvDownloadPeerReplacement(null, bestPeerHeight = 200L))
        assertFalse(hasHigherDogecoinSpvDownloadPeerReplacement(200L, bestPeerHeight = 200L))
        assertFalse(hasHigherDogecoinSpvDownloadPeerReplacement(201L, bestPeerHeight = 200L))
        assertTrue(hasHigherDogecoinSpvDownloadPeerReplacement(199L, bestPeerHeight = 200L))
    }

    @Test
    fun `unavailable recovery defers without spending the bounded budget`() {
        val watchdog = watchdog()
        watchdog.observe(nowMillis = 0L)

        assertEquals(DogecoinSpvHeaderRecovery.ROTATE_DOWNLOAD_PEER, watchdog.observe(nowMillis = 100L))
        watchdog.deferRecovery(nowMillis = 100L)
        assertEquals(0, watchdog.recoveryAttempts)
        assertEquals(DogecoinSpvHeaderRecovery.NONE, watchdog.observe(nowMillis = 299L))
        assertEquals(DogecoinSpvHeaderRecovery.ROTATE_DOWNLOAD_PEER, watchdog.observe(nowMillis = 300L))
        assertEquals(0, watchdog.recoveryAttempts)
    }

    @Test
    fun `growing peer tip does not disguise a frozen local height`() {
        val watchdog = watchdog()
        watchdog.observe(nowMillis = 0L, bestPeerHeight = 200L)

        assertEquals(
            DogecoinSpvHeaderRecovery.ROTATE_DOWNLOAD_PEER,
            watchdog.observe(nowMillis = 100L, bestPeerHeight = 300L)
        )
        assertTrue(watchdog.stalled)
    }
}
