package com.bitchat.android.res

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-3 guard: every Dogecoin wallet user string in the default locale must have a Japanese translation
 * (values-ja) with the SAME set of positional format specifiers. A missing key would fall back to English
 * mid-UI; a differing/renumbered format spec (e.g. dropping %2$s) would crash at format time with an
 * IllegalFormatException. This test reads the shipped resources directly (no Android/Robolectric needed).
 */
class DogecoinStringsParityTest {

    private val keyRegex = Regex("""name="(dogecoin_[a-z0-9_]+)"\s*>(.*?)</string>""")
    private val specRegex = Regex("""%\d+\$[.\d]*[sdf]|%%""")

    private fun resFile(locale: String): File {
        val rel = "src/main/res/$locale/strings.xml"
        // Unit tests may run with the module dir or the repo root as CWD; accept either.
        return listOf(File(rel), File("app/$rel")).firstOrNull { it.exists() }
            ?: error("Could not locate $rel (cwd=${File(".").absolutePath})")
    }

    private fun parse(locale: String): Map<String, List<String>> =
        resFile(locale).readText().let { text ->
            keyRegex.findAll(text).associate { m ->
                m.groupValues[1] to specRegex.findAll(m.groupValues[2]).map { it.value }.sorted().toList()
            }
        }

    @Test
    fun `every dogecoin string has a JA translation with matching format specifiers`() {
        val en = parse("values")
        val ja = parse("values-ja")

        assertTrue("expected many dogecoin_ strings in the default locale", en.size > 100)

        val missing = (en.keys - ja.keys).sorted()
        assertEquals("dogecoin_ strings missing a JA translation: $missing", emptyList<String>(), missing)

        val mismatched = en.keys.filter { it in ja && en[it] != ja[it] }
            .map { "$it EN=${en[it]} JA=${ja[it]}" }
        assertEquals("format-specifier mismatches EN vs JA: $mismatched", emptyList<String>(), mismatched)
    }
}
