package com.tammem.htmlilaapk

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val LightColors = lightColorScheme(
    primary = Color(0xFF2F6FED),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0A2A73),
    secondary = Color(0xFF15803D),
    secondaryContainer = Color(0xFFD6F5E0),
    onSecondaryContainer = Color(0xFF0B3B1E),
    tertiaryContainer = Color(0xFFFFF4D6),
    onTertiaryContainer = Color(0xFF5A4300),
    background = Color(0xFFF6F7FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFEEF1F8),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB8FF),
    onPrimary = Color(0xFF0A2A73),
    primaryContainer = Color(0xFF213F8C),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFF7EE2A0),
    secondaryContainer = Color(0xFF1C4A2D),
    onSecondaryContainer = Color(0xFFD6F5E0),
    tertiaryContainer = Color(0xFF4A3B12),
    onTertiaryContainer = Color(0xFFFFE8A8),
    background = Color(0xFF111318),
    surface = Color(0xFF1A1C22),
    surfaceVariant = Color(0xFF272B35),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
