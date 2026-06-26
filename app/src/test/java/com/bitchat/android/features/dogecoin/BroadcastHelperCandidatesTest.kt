package com.bitchat.android.features.dogecoin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [mergeBroadcastHelperCandidates] — the 3b.1 Nostr-fallback candidate merge. Candidates
 * are canonical Noise-key hex; mesh ranks ahead of off-mesh, and a helper present on mesh is dropped from
 * the off-mesh tier (dispatched on one transport).
 */
class BroadcastHelperCandidatesTest {

    private val keyA = "a".repeat(64)
    private val keyB = "b".repeat(64)
    private val keyC = "c".repeat(64)

    @Test
    fun `mesh candidates rank ahead of off-mesh candidates and keep their order`() {
        val result = mergeBroadcastHelperCandidates(listOf(keyA, keyB), listOf(keyC))
        assertEquals(listOf(keyA, keyB, keyC), result)
    }

    @Test
    fun `mesh-only when no off-mesh favorites`() {
        assertEquals(listOf(keyA), mergeBroadcastHelperCandidates(listOf(keyA), emptyList()))
    }

    @Test
    fun `off-mesh-only when no mesh candidates`() {
        assertEquals(listOf(keyA, keyB), mergeBroadcastHelperCandidates(emptyList(), listOf(keyA, keyB)))
    }

    @Test
    fun `a key present on mesh is dropped from the off-mesh tier`() {
        val result = mergeBroadcastHelperCandidates(listOf(keyA), listOf(keyA, keyB))
        assertEquals(listOf(keyA, keyB), result)
    }

    @Test
    fun `dedup across tiers is case-insensitive`() {
        val result = mergeBroadcastHelperCandidates(listOf(keyA.uppercase()), listOf(keyA, keyB))
        assertEquals(listOf(keyA, keyB), result)
    }

    @Test
    fun `each tier is de-duplicated within itself`() {
        assertEquals(listOf(keyA), mergeBroadcastHelperCandidates(listOf(keyA, keyA), emptyList()))
        assertEquals(listOf(keyA), mergeBroadcastHelperCandidates(emptyList(), listOf(keyA, keyA.uppercase())))
    }

    @Test
    fun `blank or whitespace keys are dropped on both sides`() {
        val result = mergeBroadcastHelperCandidates(listOf("", "   ", keyA), listOf("", "  ", keyB))
        assertEquals(listOf(keyA, keyB), result)
    }

    @Test
    fun `empty inputs yield empty output`() {
        assertEquals(emptyList<String>(), mergeBroadcastHelperCandidates(emptyList(), emptyList()))
    }
}
