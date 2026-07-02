package com.bitchat.android.services

import android.content.Context
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.Date

/**
 * Guards for the on-disk chat-history persistence added on the simple-family-profile branch (kept separate
 * from the non-Android [AppStateStoreTest] because these need a Context.filesDir). For a family relative this
 * file IS their entire message history, so the two failure modes that matter most are: a corrupt file must
 * not silently discard-then-overwrite the only copy, and the encode/decode round-trip (incl. delivery status
 * and the per-conversation cap) must be faithful.
 *
 * [AppStateStore] is a process singleton, so each test resets it via wipePersisted() before and after.
 */
@RunWith(RobolectricTestRunner::class)
class AppStateStorePersistenceTest {
    private lateinit var context: Context
    private val historyFile get() = File(context.filesDir, "chat_history_v1.json")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        AppStateStore.init(context)
        AppStateStore.wipePersisted()
        deleteQuarantineFiles()
    }

    @After
    fun tearDown() {
        AppStateStore.wipePersisted()
        deleteQuarantineFiles()
    }

    private fun deleteQuarantineFiles() {
        context.filesDir.listFiles { f -> f.name.startsWith("chat_history_v1.json.corrupt-") }?.forEach { it.delete() }
    }

    private fun msg(id: String, content: String, status: DeliveryStatus? = null) = BitchatMessage(
        id = id,
        sender = "me",
        content = content,
        timestamp = Date(1_700_000_000_000L),
        isRelay = false,
        isPrivate = true,
        senderPeerID = "peer1",
        deliveryStatus = status
    )

    @Test
    fun `corrupt history file is quarantined, not silently discarded`() {
        val garbage = "{ this is not valid json"
        historyFile.writeText(garbage)

        AppStateStore.load()

        // Flows stay empty (started clean) ...
        assertTrue(AppStateStore.privateMessages.value.isEmpty())
        // ... the live file was renamed aside (so the first new message's persist can't clobber it) ...
        assertFalse("corrupt file should have been renamed away", historyFile.exists())
        // ... and the original bytes are preserved under a .corrupt-* name for manual recovery.
        val quarantined = context.filesDir.listFiles { f -> f.name.startsWith("chat_history_v1.json.corrupt-") }
        assertEquals(1, quarantined?.size)
        assertEquals(garbage, quarantined!!.first().readText())
    }

    @Test
    fun `round-trips a private message with delivery status through disk`() {
        AppStateStore.addPrivateMessage(
            "peer1",
            msg("M1", "hello", DeliveryStatus.Read(by = "them", at = Date(1_700_000_001_000L)))
        )
        AppStateStore.clear()   // flushes to disk (synchronous) and clears memory

        AppStateStore.load()

        val restored = AppStateStore.privateMessages.value["peer1"]
        assertEquals(1, restored?.size)
        val m = restored!!.first()
        assertEquals("M1", m.id)
        assertEquals("hello", m.content)
        assertTrue("delivery status must survive", m.deliveryStatus is DeliveryStatus.Read)
    }

    @Test
    fun `per-conversation cap keeps the newest 1000 messages`() {
        repeat(1001) { i -> AppStateStore.addPrivateMessage("peer1", msg("M$i", "body$i")) }
        AppStateStore.clear()

        AppStateStore.load()

        val restored = AppStateStore.privateMessages.value["peer1"]
        assertEquals(1000, restored?.size)
        // takeLast(1000) drops the OLDEST (M0); the first surviving message is M1, the last is M1000.
        assertEquals("M1", restored!!.first().id)
        assertEquals("M1000", restored.last().id)
    }
}
