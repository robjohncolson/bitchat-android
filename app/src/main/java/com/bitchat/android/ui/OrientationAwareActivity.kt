package com.bitchat.android.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.bitchat.android.profile.SimpleLanguage
import com.bitchat.android.utils.DeviceUtils

/**
 * Base activity that automatically sets orientation based on device type.
 * Tablets can rotate to landscape, phones are locked to portrait.
 */
abstract class OrientationAwareActivity : ComponentActivity() {

    // Apply the in-app UI language override (Simple profile) before any resources are resolved. No-op when
    // the user follows the phone's language.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(SimpleLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setOrientationBasedOnDeviceType()
    }

    private fun setOrientationBasedOnDeviceType() {
        requestedOrientation = if (DeviceUtils.isTablet(this)) {
            // Allow all orientations on tablets
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            // Lock to portrait on phones
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}
