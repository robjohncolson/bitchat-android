package com.bitchat.android.profile.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Scoped, LINE-inspired theme for the SIMPLE ("Family") surface — clean, light, rounded, with the
 * familiar LINE green as the single accent. Wraps content in a nested [MaterialTheme] + paper [Surface]
 * (the same pattern as the wallet's DogecoinWalletTheme) so the Simple screens read friendly instead of
 * inheriting the app's terminal-green/monospace look. The rest of the app keeps its own theme.
 */
private val LineGreen = Color(0xFF06C755)        // LINE brand green
private val LinePaper = Color(0xFFF7F8FA)        // app background
private val LineSurface = Color(0xFFFFFFFF)      // cards / bars
private val LineInk = Color(0xFF14171A)          // primary text
private val LineInkMuted = Color(0xFF6B7177)     // secondary text

private val LineColors = lightColorScheme(
    primary = LineGreen,
    onPrimary = Color.White,
    secondary = LineGreen,
    onSecondary = Color.White,
    background = LinePaper,
    onBackground = LineInk,
    surface = LineSurface,
    onSurface = LineInk,
    surfaceVariant = Color(0xFFEEF1F4),
    onSurfaceVariant = LineInkMuted,
    outline = Color(0xFFDDE1E6),
)

@Composable
fun LineTheme(content: @Composable () -> Unit) {
    // Default Material typography (sans-serif). A nested MaterialTheme otherwise INHERITS the app's
    // terminal monospace typeface; the Simple surface should read like a normal friendly messenger.
    MaterialTheme(colorScheme = LineColors, typography = Typography()) {
        Surface(modifier = Modifier.fillMaxSize(), color = LineColors.background) {
            content()
        }
    }
}
