package com.bitchat.android.ui

/**
 * Parser for Dogecoin payment request links.
 *
 * This parser only detects dogecoin: URIs for inline chat rendering. The
 * in-app wallet lives under features/dogecoin and remains separate from link
 * parsing.
 */
object DogecoinUri {
    private const val SCHEME = "dogecoin:"

    data class Match(val start: Int, val endExclusive: Int, val uri: String)

    fun findPaymentUris(text: String): List<Match> {
        if (text.isEmpty()) return emptyList()

        val matches = mutableListOf<Match>()
        var index = 0
        while (index < text.length) {
            val start = text.indexOf(SCHEME, startIndex = index, ignoreCase = true)
            if (start < 0) break

            if (start > 0 && isTokenPrefix(text[start - 1])) {
                index = start + SCHEME.length
                continue
            }

            var endExclusive = start + SCHEME.length
            while (endExclusive < text.length && isUriTokenChar(text[endExclusive])) {
                endExclusive++
            }

            val trimmedEnd = trimTrailingTokenDelimiters(text, start, endExclusive)
            val normalized = normalize(text.substring(start, trimmedEnd))
            if (normalized != null) {
                matches.add(Match(start, trimmedEnd, normalized))
                index = trimmedEnd
            } else {
                index = start + SCHEME.length
            }
        }

        return matches
    }

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith(SCHEME, ignoreCase = true)) return null

        val endExclusive = trimTrailingTokenDelimiters(trimmed, 0, trimmed.length)
        if (endExclusive <= SCHEME.length) return null

        val body = trimmed.substring(SCHEME.length, endExclusive)
        if (body.removePrefix("//").isBlank()) return null

        return SCHEME + body
    }

    fun isDogecoinUri(raw: String): Boolean {
        return normalize(raw) != null
    }

    fun wholeMessagePaymentUri(text: String): String? {
        val firstContentIndex = text.indexOfFirst { !it.isWhitespace() }
        if (firstContentIndex < 0) return null

        val lastContentIndex = text.indexOfLast { !it.isWhitespace() }
        val trimmed = text.substring(firstContentIndex, lastContentIndex + 1)
        val match = findPaymentUris(trimmed).singleOrNull() ?: return null
        if (match.start != 0 || match.endExclusive != trimmed.length) return null
        return match.uri
    }

    private fun isTokenPrefix(char: Char): Boolean {
        return char.isLetterOrDigit() || char == '_' || char == '-'
    }

    private fun isUriTokenChar(char: Char): Boolean {
        return !char.isWhitespace() && char !in setOf('<', '>', '"', '\'', '`')
    }

    private fun trimTrailingTokenDelimiters(text: String, start: Int, endExclusive: Int): Int {
        var end = endExclusive
        while (end > start) {
            val last = text[end - 1]
            val shouldTrim = last in setOf('.', ',', ';', ':', '!', '?', '"', '\'', '`') ||
                (last == ')' && hasUnmatchedClosing(text, start, end, '(', ')')) ||
                (last == ']' && hasUnmatchedClosing(text, start, end, '[', ']')) ||
                (last == '}' && hasUnmatchedClosing(text, start, end, '{', '}'))

            if (!shouldTrim) break
            end--
        }
        return end
    }

    private fun hasUnmatchedClosing(text: String, start: Int, endExclusive: Int, open: Char, close: Char): Boolean {
        var balance = 0
        for (i in start until endExclusive) {
            when (text[i]) {
                open -> balance++
                close -> balance--
            }
        }
        return balance < 0
    }
}

object ClickableUriResolver {
    fun resolve(raw: String): String {
        val trimmed = raw.trim()
        return DogecoinUri.normalize(trimmed)
            ?: if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "https://$trimmed"
            }
    }
}
