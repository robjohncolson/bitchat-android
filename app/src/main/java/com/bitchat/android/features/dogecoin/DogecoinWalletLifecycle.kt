package com.bitchat.android.features.dogecoin

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sheet-scoped coordinator for lifecycle/read I/O. The mutex prevents SPV startup, node status, and
 * balance/activity snapshots from piling onto the same wallet resources, while [generation] prevents a
 * completion from publishing after the sheet has left composition.
 *
 * Long-lived confirmation polling acquires this only for each individual observation, never for its whole
 * polling budget, so RPC confirmation progress remains responsive.
 */
internal class DogecoinWalletIoSession {
    private val generation = AtomicInteger(0)
    private val mutex = Mutex()

    fun captureGeneration(): Int = generation.get()

    fun invalidate() {
        generation.incrementAndGet()
    }

    fun isCurrent(capturedGeneration: Int): Boolean = generation.get() == capturedGeneration

    suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock { block() }
}

internal const val DOGECOIN_WALLET_BOOTSTRAP_TIMEOUT_MS = 10_000L
internal const val DOGECOIN_SPV_START_TIMEOUT_MS = 15_000L
