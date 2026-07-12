package com.bitchat.android.features.dogecoin

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sheet-scoped coordinator for **sheet read** I/O (node status/balance/activity, short SPV tx/conf snapshots).
 * The mutex prevents those reads from stampeding the same resources, while [generation] prevents a
 * completion from publishing after the sheet has left composition.
 *
 * **SPV process start/stop must NOT use this mutex** — it lives on a separate process lifecycle lock so
 * home-node RPC (the fast path when SPV is behind) is never blocked behind bitcoinj startup.
 * Periodic SPV balance snapshots likewise use their own single-flight lane because the service monitor may
 * briefly wait on lifecycle work; they still use this session's [generation] to reject stale publication.
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

/**
 * Serializes ownership changes with SPV status publication without taking bitcoinj's lifecycle monitor from
 * a PeerGroup callback. Each live PeerGroup gets a unique [owner] token. A delayed callback from a stopped or
 * replaced group can still run after `PeerGroup.stopAsync()`, but it can no longer resurrect that group's
 * network/running state after a rebind.
 */
internal class DogecoinSpvStatusPublicationGate {
    private val lock = Any()
    private var activeOwner: Any? = null

    fun activate(owner: Any) = synchronized(lock) {
        activeOwner = owner
    }

    fun deactivate(updateStoppedStatus: () -> Unit) = synchronized(lock) {
        activeOwner = null
        updateStoppedStatus()
    }

    fun isCurrent(owner: Any): Boolean = synchronized(lock) {
        activeOwner === owner
    }

    fun publishIfCurrent(owner: Any, publish: () -> Unit): Boolean = synchronized(lock) {
        if (activeOwner !== owner) return@synchronized false
        publish()
        true
    }
}

internal const val DOGECOIN_WALLET_BOOTSTRAP_TIMEOUT_MS = 10_000L
internal const val DOGECOIN_SPV_START_TIMEOUT_MS = 15_000L
