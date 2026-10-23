package io.github.jgg113.gongmae.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F4FA3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF001A43),
    secondary = Color(0xFF0E7C66),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEFE5),
    onSecondaryContainer = Color(0xFF00201A),
    tertiary = Color(0xFFC0392B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDAD5),
    onTertiaryContainer = Color(0xFF410001),
    background = Color(0xFFF7F8FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE7EAF1),
    onSurfaceVariant = Color(0xFF4A5060),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB0C6FF),
    onPrimary = Color(0xFF002D6E),
    primaryContainer = Color(0xFF0E3E8C),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFF8BD6C2),
    onSecondary = Color(0xFF00382D),
    secondaryContainer = Color(0xFF005142),
    onSecondaryContainer = Color(0xFFCDEFE5),
    tertiary = Color(0xFFFFB4A9),
    onTertiary = Color(0xFF690002),
    tertiaryContainer = Color(0xFF8C1D12),
    onTertiaryContainer = Color(0xFFFFDAD5),
)

@Composable
fun GongmaeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
