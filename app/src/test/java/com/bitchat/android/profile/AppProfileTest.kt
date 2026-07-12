package com.bitchat.android.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure (no-Android) guards on the profile enum. The SharedPreferences I/O in
 * [ProfilePreferenceManager] mirrors the proven ThemePreferenceManager pattern verbatim and is left to
 * the build; what matters here is that POWER stays the safe default and the helpers don't drift.
 */
class AppProfileTest {

    @Test
    fun `power is the safe default and is not simple`() {
        assertTrue(AppProfile.POWER.isPower)
        assertFalse(AppProfile.POWER.isSimple)
    }

    @Test
    fun `simple is simple and not power`() {
        assertTrue(AppProfile.SIMPLE.isSimple)
        assertFalse(AppProfile.SIMPLE.isPower)
    }

    @Test
    fun `valueOf round-trips both profiles by name`() {
        assertEquals(AppProfile.POWER, AppProfile.valueOf("POWER"))
        assertEquals(AppProfile.SIMPLE, AppProfile.valueOf("SIMPLE"))
    }
}
