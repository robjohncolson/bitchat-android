package com.bitchat.android.features.dogecoin

internal enum class DogecoinSpvHeaderRecovery {
    NONE,
    ROTATE_DOWNLOAD_PEER
}

/** Rotating this peer cannot lower the live best-height input used by the unchanged readiness gate. */
internal fun hasHigherDogecoinSpvDownloadPeerReplacement(
    downloadPeerHeight: Long?,
    bestPeerHeight: Long
): Boolean = downloadPeerHeight != null && downloadPeerHeight < bestPeerHeight

/**
 * Monotonic no-progress detector for the live SPV header chain. It deliberately knows nothing about bitcoinj,
 * Android, peers, stores, or transport; the service decides how to perform a bounded recovery.
 */
internal class DogecoinSpvHeaderProgressWatchdog(
    private val stallTimeoutMillis: Long,
    private val recoveryCooldownMillis: Long,
    private val maxRecoveryAttempts: Int,
    private val caughtUpWithinBlocks: Long
) {
    init {
        require(stallTimeoutMillis > 0L)
        require(recoveryCooldownMillis > 0L)
        require(maxRecoveryAttempts >= 0)
        require(caughtUpWithinBlocks >= 0L)
    }

    private var lastHeight: Int? = null
    private var progressWindowStartedAtMillis: Long? = null
    private var nextRecoveryAtMillis: Long = Long.MAX_VALUE

    @Volatile
    var stalled: Boolean = false
        private set

    var recoveryAttempts: Int = 0
        private set

    @Synchronized
    fun observe(
        nowMillis: Long,
        running: Boolean,
        peerCount: Int,
        chainHeight: Int,
        bestPeerHeight: Long
    ): DogecoinSpvHeaderRecovery {
        if (!running) {
            resetLocked()
            return DogecoinSpvHeaderRecovery.NONE
        }

        // Time without peers is not evidence of a stuck download peer. Preserve the session's bounded recovery
        // budget, but give a returning peer a fresh progress window.
        if (peerCount <= 0) {
            progressWindowStartedAtMillis = null
            nextRecoveryAtMillis = Long.MAX_VALUE
            stalled = false
            return DogecoinSpvHeaderRecovery.NONE
        }

        val blocksBehind = (bestPeerHeight - chainHeight.toLong()).coerceAtLeast(0L)
        if (blocksBehind <= caughtUpWithinBlocks) {
            resetLocked()
            return DogecoinSpvHeaderRecovery.NONE
        }

        val previousHeight = lastHeight
        if (previousHeight == null || chainHeight != previousHeight) {
            lastHeight = chainHeight
            progressWindowStartedAtMillis = nowMillis
            nextRecoveryAtMillis = nowMillis + stallTimeoutMillis
            recoveryAttempts = 0
            stalled = false
            return DogecoinSpvHeaderRecovery.NONE
        }

        if (progressWindowStartedAtMillis == null) {
            progressWindowStartedAtMillis = nowMillis
            nextRecoveryAtMillis = nowMillis + stallTimeoutMillis
            stalled = false
            return DogecoinSpvHeaderRecovery.NONE
        }

        stalled = nowMillis - progressWindowStartedAtMillis!! >= stallTimeoutMillis
        if (!stalled || recoveryAttempts >= maxRecoveryAttempts || nowMillis < nextRecoveryAtMillis) {
            return DogecoinSpvHeaderRecovery.NONE
        }

        return DogecoinSpvHeaderRecovery.ROTATE_DOWNLOAD_PEER
    }

    /** Consume one recovery only after the service successfully asks bitcoinj to rotate the managed peer. */
    @Synchronized
    fun recordRecoveryAttempt(nowMillis: Long) {
        if (!stalled || recoveryAttempts >= maxRecoveryAttempts) return
        recoveryAttempts += 1
        nextRecoveryAtMillis = nowMillis + recoveryCooldownMillis
    }

    /** Throttle an unavailable/failed recovery without burning the bounded session budget. */
    @Synchronized
    fun deferRecovery(nowMillis: Long) {
        if (stalled) nextRecoveryAtMillis = nowMillis + recoveryCooldownMillis
    }

    @Synchronized
    fun reset() {
        resetLocked()
    }

    private fun resetLocked() {
        lastHeight = null
        progressWindowStartedAtMillis = null
        nextRecoveryAtMillis = Long.MAX_VALUE
        recoveryAttempts = 0
        stalled = false
    }
}
