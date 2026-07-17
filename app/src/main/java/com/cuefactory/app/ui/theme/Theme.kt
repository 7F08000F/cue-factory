package com.cuefactory.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Warm amber / deep slate — tool app, not player chrome
private val Amber = Color(0xFFE8A838)
private val AmberDark = Color(0xFFC48920)
private val Ink = Color(0xFF1A1C1E)
private val Paper = Color(0xFFF7F4EF)
private val Mist = Color(0xFFE8E4DC)
private val Sea = Color(0xFF2F5D50)

private val LightColors = lightColorScheme(
    primary = Sea,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7E0D2),
    onPrimaryContainer = Color(0xFF002117),
    secondary = AmberDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE0A3),
    onSecondaryContainer = Color(0xFF271900),
    tertiary = Color(0xFF6B4F3A),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Mist,
    onSurfaceVariant = Color(0xFF4A463F),
    outline = Color(0xFF7A756C),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD0B8),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF1B4A3D),
    onPrimaryContainer = Color(0xFFB7E0D2),
    secondary = Amber,
    onSecondary = Color(0xFF402D00),
    secondaryContainer = Color(0xFF5C4200),
    onSecondaryContainer = Color(0xFFFFE0A3),
    background = Color(0xFF121416),
    onBackground = Color(0xFFE4E2DE),
    surface = Color(0xFF121416),
    onSurface = Color(0xFFE4E2DE),
    surfaceVariant = Color(0xFF343330),
    onSurfaceVariant = Color(0xFFC9C5BC),
    outline = Color(0xFF938F86),
    error = Color(0xFFF2B8B5),
)

@Composable
fun CueFactoryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
