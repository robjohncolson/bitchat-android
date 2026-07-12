package com.bitchat.android.features.dogecoin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DogecoinWalletLifecycleTest {
    @Test
    fun `wallet io session serializes operations`() = runTest {
        val session = DogecoinWalletIoSession()
        val releaseFirst = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val first = async {
            session.serialized {
                events += "first-start"
                releaseFirst.await()
                events += "first-end"
            }
        }
        runCurrent()
        val second = async {
            session.serialized {
                events += "second"
            }
        }
        runCurrent()

        assertEquals(listOf("first-start"), events)
        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("first-start", "first-end", "second"), events)
    }

    @Test
    fun `failed wallet io operation does not poison serialization`() = runTest {
        val session = DogecoinWalletIoSession()

        val failure = runCatching {
            session.serialized<Unit> { error("expected") }
        }
        val value = session.serialized { 7 }

        assertTrue(failure.isFailure)
        assertEquals(7, value)
    }

    @Test
    fun `canceled queued wallet io does not run`() = runTest {
        val session = DogecoinWalletIoSession()
        val releaseFirst = CompletableDeferred<Unit>()
        var queuedRan = false

        val first = async { session.serialized { releaseFirst.await() } }
        runCurrent()
        val queued = async { session.serialized { queuedRan = true } }
        runCurrent()

        queued.cancelAndJoin()
        releaseFirst.complete(Unit)
        first.await()

        assertFalse(queuedRan)
    }

    @Test
    fun `invalidated wallet session rejects stale generation`() {
        val session = DogecoinWalletIoSession()
        val firstGeneration = session.captureGeneration()

        assertTrue(session.isCurrent(firstGeneration))
        session.invalidate()

        assertFalse(session.isCurrent(firstGeneration))
        assertTrue(session.isCurrent(session.captureGeneration()))
    }

    @Test
    fun `SPV status publication rejects callbacks from replaced and stopped owners`() {
        val gate = DogecoinSpvStatusPublicationGate()
        val testnetOwner = Any()
        val mainnetOwner = Any()
        val published = mutableListOf<String>()

        gate.activate(testnetOwner)
        assertTrue(gate.isCurrent(testnetOwner))
        assertTrue(gate.publishIfCurrent(testnetOwner) { published += "testnet" })

        gate.activate(mainnetOwner)
        assertFalse(gate.isCurrent(testnetOwner))
        assertFalse(gate.publishIfCurrent(testnetOwner) { published += "stale-testnet" })
        assertTrue(gate.publishIfCurrent(mainnetOwner) { published += "mainnet" })

        gate.deactivate { published += "stopped" }
        assertFalse(gate.isCurrent(mainnetOwner))
        assertFalse(gate.publishIfCurrent(mainnetOwner) { published += "resurrected-mainnet" })
        assertEquals(listOf("testnet", "mainnet", "stopped"), published)
    }
}
