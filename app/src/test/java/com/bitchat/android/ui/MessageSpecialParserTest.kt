package com.bitchat.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageSpecialParserTest {
    @Test
    fun `findUrls includes dogecoin uris in sorted clickable matches`() {
        val matches = MessageSpecialParser.findUrls(
            "open https://example.com and dogecoin:D8Bz2qkQy6VnY9uQkgQH7M7q6XkVQp7sWa?amount=2"
        )

        assertEquals(2, matches.size)
        assertEquals("https://example.com", matches[0].url)
        assertEquals("dogecoin:D8Bz2qkQy6VnY9uQkgQH7M7q6XkVQp7sWa?amount=2", matches[1].url)
    }

    @Test
    fun `findUrls trims wrapping punctuation from dogecoin uris`() {
        val matches = MessageSpecialParser.findUrls(
            "send (dogecoin:D8Bz2qkQy6VnY9uQkgQH7M7q6XkVQp7sWa?amount=2), please"
        )

        assertEquals(1, matches.size)
        assertEquals("dogecoin:D8Bz2qkQy6VnY9uQkgQH7M7q6XkVQp7sWa?amount=2", matches.first().url)
    }

    @Test
    fun `findUrls includes authority style dogecoin uris`() {
        val matches = MessageSpecialParser.findUrls(
            "send dogecoin://D8Bz2qkQy6VnY9uQkgQH7M7q6XkVQp7sWa?amount=2"
        )

        assertEquals(1, matches.size)
        assertEquals("dogecoin://D8Bz2qkQy6VnY9uQkgQH7M7q6XkVQp7sWa?amount=2", matches.first().url)
    }
}
