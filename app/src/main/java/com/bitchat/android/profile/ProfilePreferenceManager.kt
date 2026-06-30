package com.bitchat.android.profile

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which experience this device runs.
 *
 * [POWER] is the full, terminal-styled bitchat with every knob exposed — the default and the only
 * behavior that has ever shipped, so every existing install stays POWER and sees no change.
 *
 * [SIMPLE] is the friendly, LINE-style "Family" experience for a non-technical user whose phone was
 * provisioned by a power user: a stripped, reskinned surface over the SAME engine, with curated
 * defaults (Nostr over clearnet / Tor off, proof-of-work off, an opt-in wallet locked to one network)
 * and the advanced settings hidden. A device is exactly ONE profile, chosen once at setup — the
 * profile only (a) seeds the existing app-wide settings and (b) selects which UI surface is shown; it
 * deliberately does NOT make the underlying singleton managers profile-aware.
 */
enum class AppProfile {
    POWER,
    SIMPLE;

    val isSimple: Boolean get() = this == SIMPLE
    val isPower: Boolean get() = this == POWER
}

/**
 * SharedPreferences-backed manager for the device's [AppProfile], mirroring
 * [com.bitchat.android.ui.theme.ThemePreferenceManager] (a StateFlow over the same "bitchat_settings"
 * store, no DataStore dependency).
 *
 * Defaults to [AppProfile.POWER] everywhere, so an install that has never been through the profile
 * step is unaffected. [init] is called once from [com.bitchat.android.BitchatApplication.onCreate].
 */
object ProfilePreferenceManager {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_PROFILE = "app_profile"

    private val _profileFlow = MutableStateFlow(AppProfile.POWER)
    val profileFlow: StateFlow<AppProfile> = _profileFlow

    fun init(context: Context) {
        _profileFlow.value = read(context)
    }

    fun set(context: Context, profile: AppProfile) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILE, profile.name).apply()
        _profileFlow.value = profile
    }

    fun get(context: Context): AppProfile = read(context)

    private fun read(context: Context): AppProfile {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_PROFILE, AppProfile.POWER.name)
        return runCatching { AppProfile.valueOf(saved ?: AppProfile.POWER.name) }
            .getOrDefault(AppProfile.POWER)
    }
}
