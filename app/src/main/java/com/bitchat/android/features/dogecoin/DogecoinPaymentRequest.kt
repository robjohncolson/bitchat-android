package com.bitchat.android.features.dogecoin

import java.net.URLDecoder

data class DogecoinPaymentRequest(
    val network: DogecoinNetwork,
    val address: String,
    val amount: String? = null,
    val label: String? = null,
    val message: String? = null,
    val uri: String
) {
    companion object {
        private const val SCHEME = "dogecoin:"
        private const val BASE58_CHARS = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        private const val MIN_ADDRESS_LENGTH = 26
        private const val MAX_ADDRESS_LENGTH = 35

        fun parseAddressOrUri(raw: String): DogecoinPaymentRequest? {
            parse(raw)?.let { return it }
            findEmbeddedPaymentUri(raw)?.let { return it }

            val address = raw.trim()
            buildBareAddressRequest(address)?.let { return it }
            return findEmbeddedBareAddress(raw)
        }

        fun parse(raw: String): DogecoinPaymentRequest? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith(SCHEME, ignoreCase = true)) return null

            val body = trimmed.substring(SCHEME.length)
            if (body.isBlank()) return null

            val addressPart = body.substringBefore("?").trim().removePrefix("//")
            val network = detectNetwork(addressPart) ?: return null

            val params = runCatching {
                parseQuery(body.substringAfter("?", missingDelimiterValue = ""))
            }.getOrNull() ?: return null
            if (params.keys.any { it.startsWith("req-") }) return null
            val amount = params["amount"]?.trim()?.takeIf { it.isNotEmpty() }
            if (amount != null && !DogecoinAmount.isValidAmount(amount)) return null

            return DogecoinPaymentRequest(
                network = network,
                address = addressPart,
                amount = amount,
                label = params["label"]?.trim()?.takeIf { it.isNotEmpty() },
                message = params["message"]?.trim()?.takeIf { it.isNotEmpty() },
                uri = trimmed
            )
        }

        private fun findEmbeddedPaymentUri(raw: String): DogecoinPaymentRequest? {
            var index = 0
            while (index < raw.length) {
                val start = raw.indexOf(SCHEME, startIndex = index, ignoreCase = true)
                if (start < 0) return null

                if (start > 0 && isTokenPrefix(raw[start - 1])) {
                    index = start + SCHEME.length
                    continue
                }

                var endExclusive = start + SCHEME.length
                while (endExclusive < raw.length && isUriTokenChar(raw[endExclusive])) {
                    endExclusive++
                }

                val trimmedEnd = trimTrailingTokenDelimiters(raw, start, endExclusive)
                if (trimmedEnd > start + SCHEME.length) {
                    parse(raw.substring(start, trimmedEnd))?.let { return it }
                }
                index = start + SCHEME.length
            }
            return null
        }

        private fun findEmbeddedBareAddress(raw: String): DogecoinPaymentRequest? {
            var index = 0
            while (index < raw.length) {
                while (index < raw.length && !isBase58Char(raw[index])) {
                    index++
                }
                val start = index
                while (index < raw.length && isBase58Char(raw[index])) {
                    index++
                }
                val candidate = raw.substring(start, index)
                if (
                    candidate.length in MIN_ADDRESS_LENGTH..MAX_ADDRESS_LENGTH &&
                    hasBareAddressBoundaries(raw, start, index)
                ) {
                    buildBareAddressRequest(candidate)?.let { return it }
                }
            }
            return null
        }

        private fun buildBareAddressRequest(address: String): DogecoinPaymentRequest? {
            val network = detectNetwork(address) ?: return null
            return DogecoinPaymentRequest(
                network = network,
                address = address,
                uri = DogecoinProtocol.createPaymentUri(network, address)
            )
        }

        private fun detectNetwork(address: String): DogecoinNetwork? {
            return when {
                DogecoinAddress.isValidAddress(address, DogecoinNetwork.MAINNET) -> DogecoinNetwork.MAINNET
                DogecoinAddress.isValidAddress(address, DogecoinNetwork.TESTNET) -> DogecoinNetwork.TESTNET
                else -> null
            }
        }

        private fun parseQuery(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()

            return query.split("&")
                .mapNotNull { part ->
                    if (part.isBlank()) return@mapNotNull null
                    val key = decode(part.substringBefore("=")).lowercase()
                    val value = decode(part.substringAfter("=", missingDelimiterValue = ""))
                    key to value
                }
                .toMap()
        }

        private fun decode(value: String): String {
            return URLDecoder.decode(value, Charsets.UTF_8.name())
        }

        private fun isTokenPrefix(char: Char): Boolean {
            return char.isLetterOrDigit() || char == '_' || char == '-'
        }

        private fun isUriTokenChar(char: Char): Boolean {
            return !char.isWhitespace() && char !in setOf('<', '>', '"', '\'', '`')
        }

        private fun isBase58Char(char: Char): Boolean {
            return char in BASE58_CHARS
        }

        private fun hasBareAddressBoundaries(text: String, start: Int, endExclusive: Int): Boolean {
            val previous = text.getOrNull(start - 1)
            val next = text.getOrNull(endExclusive)
            return (previous == null || (!isTokenPrefix(previous) && previous != ':' && previous != '/')) &&
                (next == null || !isTokenPrefix(next))
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

        private fun hasUnmatchedClosing(
            text: String,
            start: Int,
            endExclusive: Int,
            open: Char,
            close: Char
        ): Boolean {
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
}
