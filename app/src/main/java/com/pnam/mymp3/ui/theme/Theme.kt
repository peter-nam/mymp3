package com.pnam.mymp3.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = Amber500,
    onPrimary = Amber900,
    primaryContainer = Amber500,
    onPrimaryContainer = Amber900,
    secondary = Amber300,
    onSecondary = Amber900,
    background = Plum950,
    onBackground = Bone,
    surface = Plum900,
    onSurface = Bone,
    surfaceContainer = Plum900,
    surfaceContainerHigh = Plum800,
    surfaceVariant = Plum800,
    onSurfaceVariant = BoneMuted,
    outline = Plum700,
    outlineVariant = Plum800,
)

/**
 * Dark, always.
 *
 * The app is designed as a dark player, so it does not follow the system light/dark setting and
 * does not opt into Material You dynamic colour — on One UI that would repaint the whole app with
 * the wallpaper palette and throw away the amber-on-plum identity the icon sets up.
 */
@Composable
fun MyMp3Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content,
    )
}
