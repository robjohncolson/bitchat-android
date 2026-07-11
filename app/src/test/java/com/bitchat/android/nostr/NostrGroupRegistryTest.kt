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

    // --- bgm tag privacy (spec Workstream A / R-A0: local pet-names must never ride out in bgm) ---

    private fun bgmTags(tags: List<List<String>>) = tags.filter { it.firstOrNull() == "bgm" }

    @Test
    fun `bgm tag omits the name for a member with no self-asserted name`() {
        // Non-self members are constructed with a null name (see ChatViewModel.startNostrGroup); the tag
        // builder must then emit hex-only bgm — no third element — so a renamed favorite's local pet-name
        // can never leak to the group.
        val tags = NostrGroupRegistry.buildGroupTags(
            groupId = "deadbeefdeadbeef",
            subject = null,
            members = listOf(NostrGroupRegistry.GroupMember(a, name = null))
        )
        val bgm = bgmTags(tags).single()
        assertEquals(listOf("bgm", a), bgm)
        assertEquals("hex-only bgm carries exactly two elements (no name)", 2, bgm.size)
    }

    @Test
    fun `bgm includes only the self-asserted name and never a pet-name`() {
        // Realistic outbound shape: self carries its OWN announced nickname; the two other members carry
        // null (their local pet-names, e.g. "grandma", stay on this device only). Assert no pet-name string
        // appears anywhere in the outbound tags, and only the self hex gets a name.
        val selfNick = "MyPhone"
        val petNameA = "grandma"
        val petNameB = "the tablet"
        val tags = NostrGroupRegistry.buildGroupTags(
            groupId = "feedface12345678",
            subject = "Family",
            members = listOf(
                NostrGroupRegistry.GroupMember(c, name = selfNick), // self
                NostrGroupRegistry.GroupMember(a, name = null),      // other (locally renamed "grandma")
                NostrGroupRegistry.GroupMember(b, name = null)       // other (locally renamed "the tablet")
            )
        )
        val flat = tags.flatten()
        assertTrue("a local pet-name must never appear in outbound tags", petNameA !in flat && petNameB !in flat)
        // Exactly one bgm tag carries a name, and it is the self member's hex + announced nickname.
        val named = bgmTags(tags).filter { it.size == 3 }
        assertEquals(1, named.size)
        assertEquals(listOf("bgm", c, selfNick), named.single())
    }

    @Test
    fun `buildGroupTags leads with bg and carries an optional subject`() {
        // Behavior-preservation guard for the extracted (formerly inline) tag builder.
        val withSubject = NostrGroupRegistry.buildGroupTags("abc123", "Trip", listOf(NostrGroupRegistry.GroupMember(a)))
        assertEquals(listOf("bg", "abc123"), withSubject.first())
        assertTrue(withSubject.any { it == listOf("subject", "Trip") })

        val blankSubject = NostrGroupRegistry.buildGroupTags("abc123", "  ", listOf(NostrGroupRegistry.GroupMember(a)))
        assertTrue("blank subject must be omitted", blankSubject.none { it.firstOrNull() == "subject" })
    }
}
