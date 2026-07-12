package com.bitchat.android.profile

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * In-app UI language override for the Simple ("Family") profile.
 *
 * The base app already ships per-language resources (values-ja, etc.) that Android auto-selects from the
 * PHONE's language. This adds an explicit in-app override so a relative can force English / 日本語 regardless
 * of the phone setting (empty tag = follow the phone). We apply it via [wrap] in every Activity's
 * attachBaseContext because our Activities extend ComponentActivity, not AppCompatActivity, so AppCompat's
 * per-app-locales machinery would not take effect on its own. Persisted in the shared settings prefs.
 *
 * The override is a single DEVICE-wide preference (not per-profile): a device is one profile, and a relative
 * wants their whole app in their language, so this intentionally applies to every Activity.
 */
object SimpleLanguage {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_LANGUAGE_TAG = "simple_language_tag"

    /** BCP-47 language tag, or "" to follow the phone's language. */
    fun getTag(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LANGUAGE_TAG, "") ?: ""

    fun setTag(context: Context, tag: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE_TAG, tag).apply()
    }

    /** Wrap [base] with the overridden locale (no-op when following the phone), for use in attachBaseContext. */
    fun wrap(base: Context): Context {
        val tag = getTag(base)
        if (tag.isBlank()) {
            // Following the phone: also RESTORE the JVM default from the (unwrapped) phone config, so a prior
            // forced Locale.setDefault doesn't leave locale-sensitive formatting stuck after switching back.
            base.resources.configuration.locales.get(0)?.let { Locale.setDefault(it) }
            return base
        }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
