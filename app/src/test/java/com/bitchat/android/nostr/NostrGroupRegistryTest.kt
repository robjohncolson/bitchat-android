package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure guards on [NostrGroupRegistry.computeGroupId] — the ONE definition of an E2E family-group thread id.
 * It is BOTH the cross-device thread identity (every member must derive the identical id with no handshake)
 * AND the receive-side integrity check (NostrDirectMessageHandler drops any group message whose parsed member
 * set doesn't hash to the claimed id). Any silent change to the algorithm — separator, case handling, dedup,
 * sort, or the 16-char truncation — would either brick every existing family group (all inbound dropped at
 * the integrity gate) or weaken membership immutability, so these tests freeze the behavior.
 */
class NostrGroupRegistryTest {

    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    @Test
    fun `pinned wire-format vector`() {
        // Freezes the exact output for a known input so an accidental algorithm change is caught. If this
        // ever legitimately changes, every already-created family group's thread id changes with it.
        assertEquals("b1da1a1755748f47", NostrGroupRegistry.computeGroupId(listOf(a, b)))
    }

    @Test
    fun `id is 16 lowercase hex chars`() {
        val id = NostrGroupRegistry.computeGroupId(listOf(a, b, c))
        assertEquals(16, id.length)
        assertTrue("id must be lowercase hex, was '$id'", id.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `order does not change the id`() {
        assertEquals(
            NostrGroupRegistry.computeGroupId(listOf(a, b, c)),
            NostrGroupRegistry.computeGroupId(listOf(c, a, b))
        )
    }

    @Test
    fun `mixed case does not change the id`() {
        assertEquals(
            NostrGroupRegistry.computeGroupId(listOf(a, b)),
            NostrGroupRegistry.computeGroupId(listOf(a.uppercase(), b.uppercase()))
        )
    }

    @Test
    fun `duplicate members do not change the id`() {
        assertEquals(
            NostrGroupRegistry.computeGroupId(listOf(a, b)),
            NostrGroupRegistry.computeGroupId(listOf(a, b, a, b))
        )
    }

    @Test
    fun `different member sets yield different ids`() {
        assertNotEquals(
            NostrGroupRegistry.computeGroupId(listOf(a, b)),
            NostrGroupRegistry.computeGroupId(listOf(a, c))
        )
        // Adding a member MUST change the id (membership is immutable per thread).
        assertNotEquals(
            NostrGroupRegistry.computeGroupId(listOf(a, b)),
            NostrGroupRegistry.computeGroupId(listOf(a, b, c))
        )
    }
}
