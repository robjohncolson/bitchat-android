package com.bitchat.android.nostr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure guards on [KnownNpubStore] — the isolated npub-only "known contact" store the Simple UI uses for
 * tap-added group members. It deliberately works uninitialized (in-memory map only, no SharedPreferences),
 * so it is unit-testable with zero Android setup. The case-normalization invariant is load-bearing:
 * SimpleModeScreen offers "Add" for a group member only when `!contains(memberHex)`, and memberHex comes
 * from network tags, so a dropped lowercase() would let an upper-cased member look addable forever.
 */
class KnownNpubStoreTest {

    private val upper = "A".repeat(64)
    private val lower = "a".repeat(64)

    @Before
    fun setUp() = KnownNpubStore.clear()

    @After
    fun tearDown() = KnownNpubStore.clear()

    @Test
    fun `put then get and contains are case-insensitive on the key`() {
        KnownNpubStore.put(upper, "Mom")
        assertEquals("Mom", KnownNpubStore.get(lower))
        assertEquals("Mom", KnownNpubStore.get(upper))
        assertTrue(KnownNpubStore.contains(lower))
        assertTrue(KnownNpubStore.contains(upper))
    }

    @Test
    fun `snapshot keys are lowercased`() {
        KnownNpubStore.put(upper, "Mom")
        assertEquals(setOf(lower), KnownNpubStore.snapshot().keys)
    }

    @Test
    fun `second put overwrites the name`() {
        KnownNpubStore.put(lower, "Mom")
        KnownNpubStore.put(upper, "Grandma")
        assertEquals("Grandma", KnownNpubStore.get(lower))
        assertEquals(1, KnownNpubStore.snapshot().size)
    }

    @Test
    fun `remove is case-insensitive`() {
        KnownNpubStore.put(lower, "Mom")
        KnownNpubStore.remove(upper)
        assertFalse(KnownNpubStore.contains(lower))
        assertNull(KnownNpubStore.get(lower))
    }

    @Test
    fun `clear empties the store`() {
        KnownNpubStore.put(lower, "Mom")
        KnownNpubStore.put(("b".repeat(64)), "Dad")
        KnownNpubStore.clear()
        assertTrue(KnownNpubStore.snapshot().isEmpty())
        assertFalse(KnownNpubStore.contains(lower))
    }
}
