package com.bitchat.android.features.dogecoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DogecoinTrustedPersonalNodeConnectionProfileTest {
    private val password = "throwaway-rpc-password"
    private val base = DogecoinTrustedPersonalNodeConnectionProfile(
        origin = "https://dogebox.tail1234.ts.net",
        username = "phone-user",
        coreWalletId = "bitchat-watch.dat"
    )

    @Test
    fun `canonical export without password contains connection coordinates only`() {
        val encoded = encodeDogecoinTrustedPersonalNodeConnectionProfile(base)!!
        assertEquals(base, decodeDogecoinTrustedPersonalNodeConnectionProfileOrNull(encoded))
        assertTrue(encoded.startsWith("bitchat-tpn-profile:"))
        assertFalse(encoded.startsWith("dogecoin:"))
        assertEquals(4, encoded.split('|').size)
        listOf("wif", "private", "attempt", "txid", "revision", "authorized", "rescan", "android").forEach {
            assertFalse(it, encoded.lowercase().contains(it))
        }
    }

    @Test
    fun `password is exported only when explicitly supplied and is redacted from diagnostics`() {
        val withPassword = base.copy(password = password)
        val encoded = encodeDogecoinTrustedPersonalNodeConnectionProfile(withPassword)!!
        assertFalse(encoded.contains(password))
        assertEquals(5, encoded.split('|').size)
        assertEquals(withPassword, decodeDogecoinTrustedPersonalNodeConnectionProfileOrNull(encoded))
        assertFalse(withPassword.toString().contains(password))
    }

    @Test
    fun `password-free import clears any stale draft password`() {
        val imported = decodeDogecoinTrustedPersonalNodeConnectionProfileOrNull(
            encodeDogecoinTrustedPersonalNodeConnectionProfile(base)!!
        )!!
        val draft = dogecoinTrustedPersonalNodeConnectionDraftFrom(imported)
        assertEquals("", draft.password)
        assertEquals(base.origin, draft.origin)
        assertEquals(base.username, draft.username)
        assertEquals(base.coreWalletId, draft.coreWalletId)

        val secretDraft = dogecoinTrustedPersonalNodeConnectionDraftFrom(base.copy(password = password))
        assertEquals(password, secretDraft.password)
        assertFalse(secretDraft.toString().contains(password))
    }

    @Test
    fun `decoder rejects extra sensitive fields and noncanonical input`() {
        val canonical = encodeDogecoinTrustedPersonalNodeConnectionProfile(base)!!
        val fields = canonical.split('|')
        listOf(
            "$canonical|d2lm|YXR0ZW1wdF9sZWRnZXI",
            canonical.replace('|', ':', ignoreCase = false),
            canonical.replace("bitchat-tpn-profile:1", "bitchat-tpn-profile:01"),
            canonical.replaceFirst("|", "|="),
            fields.dropLast(1).joinToString("|"),
            "$canonical|",
            listOf(fields[0], fields[2], fields[1], fields[3]).joinToString("|"),
            listOf(fields[0], "_w", fields[2], fields[3]).joinToString("|"),
            listOf(fields[0], "aHR0cHM6Ly9ycGMuZXhhbXBsZS5jb20", fields[2], fields[3]).joinToString("|"),
            canonical + "\n",
            "dogecoin:${canonical.substringAfter(':')}",
            "x".repeat(DOGECOIN_TPN_CONNECTION_PROFILE_MAX_CHARS + 1)
        ).forEach { assertNull(it, decodeDogecoinTrustedPersonalNodeConnectionProfileOrNull(it)) }
    }

    @Test
    fun `connection fields retain strict trust ceremony validators`() {
        val unpairedSurrogate = 0xD800.toChar().toString()
        listOf(
            base.copy(origin = "https://dogebox.tail1234.ts.net/"),
            base.copy(origin = "HTTPS://DOGEBOX.TAIL1234.TS.NET"),
            base.copy(origin = "https://dogebox.tail1234.ts.net.evil.example"),
            base.copy(username = "user:name"),
            base.copy(username = " user"),
            base.copy(username = "bad${unpairedSurrogate}user"),
            base.copy(coreWalletId = "wallet/name"),
            base.copy(coreWalletId = ".."),
            base.copy(coreWalletId = "bad${unpairedSurrogate}wallet"),
            base.copy(password = ""),
            base.copy(password = "bad\npassword"),
            base.copy(password = "bad${unpairedSurrogate}password"),
            base.copy(password = "x".repeat(1_025))
        ).forEach { assertNull(it.toString(), encodeDogecoinTrustedPersonalNodeConnectionProfile(it)) }
    }
}
