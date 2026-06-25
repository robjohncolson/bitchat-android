package com.bitchat.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DogecoinUriTest {
    @Test
    fun `findPaymentUris detects dogecoin payment request and trims sentence punctuation`() {
        val matches = DogecoinUri.findPaymentUris(
            "tip me dogecoin:D8Bz2qkQy6VnY9uQkgQH7M7q6XkVQp7sWa?amount=12.5&label=coffee."
        )

        assertEquals(1, matches.size)
        assertEquals(
            "dogecoin:D8Bz2qkQy6VnY9uQkgQH7M7q6XkVQp7sWa?amount=12.5&label=coffee",
            matches.first().uri
        )
    }

    @Test
    fun `findPaymentUris is case insensitive and normalizes scheme`() {
        val matches = DogecoinUri.findPaymentUris("DOGECOIN:D8Bz2qkQy6VnY9uQkgQH7M7q6XkVQp7sWa?amount=1")

        assertEquals(1, matches.size)
        assertEquals("dogecoin:D8Bz2qkQy6VnY9uQkgQH7M7q6XkVQp7sWa?amount=1", matches.first().uri)
    }

    @Test
    fun `findPaymentUris accepts authority style dogecoin uri`() {
        val matches = DogecoinUri.findPaymentUris("tip dogecoin://D8Bz2qkQy6VnY9uQkgQH7M7q6XkVQp7sWa?amount=1")

        assertEquals(1, matches.size)
        assertEquals("dogecoin://D8Bz2qkQy6VnY9uQkgQH7M7q6XkVQp7sWa?amount=1", matches.first().uri)
    }

    @Test
    fun `findPaymentUris ignores scheme embedded in another token`() {
        val matches = DogecoinUri.findPaymentUris("notadogecoin:D8Bz2qkQy6VnY9uQkgQH7M7q6XkVQp7sWa")

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `isDogecoinUri rejects empty payment request`() {
        assertFalse(DogecoinUri.isDogecoinUri("dogecoin:"))
        assertFalse(DogecoinUri.isDogecoinUri("dogecoin://"))
    }

    @Test
    fun `click resolver keeps dogecoin uri for wallet handoff`() {
        val resolved = ClickableUriResolver.resolve("dogecoin:D8Bz2qkQy6VnY9uQkgQH7M7q6XkVQp7sWa?amount=1.25")

        assertEquals("dogecoin:D8Bz2qkQy6VnY9uQkgQH7M7q6XkVQp7sWa?amount=1.25", resolved)
    }

    @Test
    fun `click resolver keeps authority style dogecoin uri for wallet handoff`() {
        val resolved = ClickableUriResolver.resolve("dogecoin://D8Bz2qkQy6VnY9uQkgQH7M7q6XkVQp7sWa?amount=1.25")

        assertEquals("dogecoin://D8Bz2qkQy6VnY9uQkgQH7M7q6XkVQp7sWa?amount=1.25", resolved)
    }

    @Test
    fun `click resolver still adds https for web domains`() {
        assertEquals("https://example.com", ClickableUriResolver.resolve("example.com"))
    }
}
