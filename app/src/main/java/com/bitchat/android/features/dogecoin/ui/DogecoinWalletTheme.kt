package com.bitchat.android.features.dogecoin.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Dieter Rams × Dogecoin palette, scoped to the wallet surface ONLY — the rest of the app keeps its
 * terminal-green theme. Design contract ("Coin" direction):
 *  - Gold (#C2A633) is the SINGLE accent and appears in exactly two roles: the confirmation ring, and the
 *    active/selected/"on" state of a control. Nothing else is gold — not the balance (that is [ink]), not
 *    body text, not borders.
 *  - Everything structural is warm ink-on-paper with two neutral grays ([muted], [faint]) carrying the layout.
 *  - [danger] is reserved for the un-backed-up-key warning and the reset action; it is NOT gold, so positive
 *    never reads as alarm.
 *
 * Read colors via [LocalDogeWalletColors] / [dogeWalletColors] for precise control. Existing Material widgets
 * inside [DogecoinWalletTheme] also remap (background=paper, onSurface=ink, primary=gold for active state).
 */
@Immutable
data class DogeWalletColors(
    val paper: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val gold: Color,
    val onGold: Color,
    val danger: Color,
)

val DogeWalletLight = DogeWalletColors(
    paper = Color(0xFFFAF8F2),   // warm off-white
    surface = Color(0xFFF3F0E8), // fields / slightly raised
    ink = Color(0xFF1C1B19),     // primary text + numbers
    muted = Color(0xFF6B6760),   // secondary text/numbers
    faint = Color(0xFFA8A399),   // small-caps labels + hairlines
    line = Color(0xFFE4DFD2),    // dividers / ring track
    gold = Color(0xFFC2A633),    // the one accent
    onGold = Color(0xFF1C1B19),
    danger = Color(0xFFB23A2E),
)

val DogeWalletDark = DogeWalletColors(
    paper = Color(0xFF0F0F0E),
    surface = Color(0xFF1A1A18),
    ink = Color(0xFFF1EDE2),
    muted = Color(0xFFA39E92),
    faint = Color(0xFF6E6A60),
    line = Color(0xFF2A2926),
    gold = Color(0xFFD7BC51),    // brighter so the accent holds contrast on near-black
    onGold = Color(0xFF1C1B19),
    danger = Color(0xFFE0796F),
)

val LocalDogeWalletColors = staticCompositionLocalOf { DogeWalletLight }

/** Mirrors `MaterialTheme.colorScheme` ergonomics for the wallet palette. */
val dogeWalletColors: DogeWalletColors
    @Composable get() = LocalDogeWalletColors.current

/**
 * Wrap the Dogecoin wallet UI in this so it adopts the Rams/Dogecoin palette (and so `MaterialTheme.*`
 * references inside resolve to paper/ink/gold instead of the app's green). [dark] defaults to the system
 * setting; callers may pass the app's effective dark/light to stay consistent with the rest of bitchat.
 */
@Composable
fun DogecoinWalletTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val c = if (dark) DogeWalletDark else DogeWalletLight
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.gold, onPrimary = c.onGold,
            secondary = c.muted, onSecondary = c.onGold,
            background = c.paper, onBackground = c.ink,
            surface = c.surface, onSurface = c.ink,
            surfaceVariant = c.surface, onSurfaceVariant = c.muted,
            error = c.danger, onError = Color(0xFF1C1B19),
            outline = c.line, outlineVariant = c.line,
        )
    } else {
        lightColorScheme(
            primary = c.gold, onPrimary = c.onGold,
            secondary = c.muted, onSecondary = c.onGold,
            background = c.paper, onBackground = c.ink,
            surface = c.surface, onSurface = c.ink,
            surfaceVariant = c.surface, onSurfaceVariant = c.muted,
            error = c.danger, onError = Color.White,
            outline = c.line, outlineVariant = c.line,
        )
    }
    CompositionLocalProvider(LocalDogeWalletColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
