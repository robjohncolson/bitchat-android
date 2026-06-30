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
    private const val KEY_PROFILE_CHOSEN = "profile_chosen"

    // The onboarding-complete flag lives in PermissionManager's own prefs; we read it directly (no
    // dependency) to migrate already-onboarded installs at startup.
    private const val ONBOARDING_PREFS = "bitchat_permissions"
    private const val KEY_ONBOARDING_COMPLETE = "first_time_onboarding_complete"

    private val _profileFlow = MutableStateFlow(AppProfile.POWER)
    val profileFlow: StateFlow<AppProfile> = _profileFlow

    private val _profileChosenFlow = MutableStateFlow(false)
    /** Whether a profile has been explicitly chosen; drives the one-time onboarding picker. */
    val profileChosenFlow: StateFlow<Boolean> = _profileChosenFlow

    fun init(context: Context) {
        _profileFlow.value = read(context)
        migrateExistingInstall(context)
        _profileChosenFlow.value = isProfileChosen(context)
    }

    fun set(context: Context, profile: AppProfile) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILE, profile.name).apply()
        _profileFlow.value = profile
    }

    fun get(context: Context): AppProfile = read(context)

    fun isProfileChosen(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_PROFILE_CHOSEN, false)

    /** Record that a profile was explicitly picked, so the one-time picker never shows again. */
    fun markProfileChosen(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PROFILE_CHOSEN, true).apply()
        _profileChosenFlow.value = true
    }

    /** DEBUG/testing only: clear the 'chosen' flag so the picker re-appears (a real pick restores it). */
    fun clearChosen(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PROFILE_CHOSEN, false).apply()
        _profileChosenFlow.value = false
    }

    private fun read(context: Context): AppProfile {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_PROFILE, AppProfile.POWER.name)
        return runCatching { AppProfile.valueOf(saved ?: AppProfile.POWER.name) }
            .getOrDefault(AppProfile.POWER)
    }

    /**
     * One-time migration: an install that ALREADY finished onboarding before this profile feature existed
     * keeps POWER and is marked chosen, so it never sees the picker. A fresh install (onboarding not yet
     * complete at startup) is left UNCHOSEN, so the picker shows once onboarding finishes. Idempotent: once
     * the key exists (migrated, or explicitly picked/cleared), this no-ops.
     */
    private fun migrateExistingInstall(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_PROFILE_CHOSEN)) return
        val onboarded = context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_COMPLETE, false)
        if (onboarded) {
            prefs.edit().putBoolean(KEY_PROFILE_CHOSEN, true).apply() // keep POWER (default); skip the picker
        }
    }
}
