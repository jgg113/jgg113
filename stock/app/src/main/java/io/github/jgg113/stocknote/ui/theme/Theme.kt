package io.github.jgg113.stocknote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2B5CE6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5FF),
    onPrimaryContainer = Color(0xFF0A1A4A),
    secondary = Color(0xFF5B6478),
    secondaryContainer = Color(0xFFE3E7F0),
    onSecondaryContainer = Color(0xFF1B2232),
    tertiary = Color(0xFF8A5CF6),
    tertiaryContainer = Color(0xFFEDE4FF),
    background = Color(0xFFF6F7FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDF0F5),
    onSurfaceVariant = Color(0xFF586074),
    outlineVariant = Color(0xFFDCE0E8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFC2FF),
    onPrimary = Color(0xFF0A1F66),
    primaryContainer = Color(0xFF213C9A),
    onPrimaryContainer = Color(0xFFDDE5FF),
    secondary = Color(0xFFC0C7D8),
    secondaryContainer = Color(0xFF353C4C),
    onSecondaryContainer = Color(0xFFE3E7F0),
    tertiary = Color(0xFFCDB8FF),
    tertiaryContainer = Color(0xFF4C3591),
    background = Color(0xFF101318),
    surface = Color(0xFF171B22),
    surfaceVariant = Color(0xFF232833),
    onSurfaceVariant = Color(0xFFB7BECC),
    outlineVariant = Color(0xFF353B47),
)

@Composable
fun StockTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

/** 국내 관례대로 오르면 빨강, 내리면 파랑 */
object PriceColors {
    val up: Color @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xFFFF6B6B) else Color(0xFFE5323B)
    val down: Color @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xFF6EA8FF) else Color(0xFF1E64E6)
    val flat: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

    @Composable
    @ReadOnlyComposable
    fun color(change: Double): Color = when {
        change > 0 -> up
        change < 0 -> down
        else -> flat
    }

    /** 차트 선 색 (보조지표 colorIndex 순서) */
    val palette = listOf(
        Color(0xFFF2A007), Color(0xFF7A4DE8), Color(0xFF13A38B), Color(0xFFE5323B), Color(0xFF1E64E6),
        Color(0xFFDB2777), Color(0xFF0EA5E9), Color(0xFF65A30D), Color(0xFF8B95A7), Color(0xFF6B7280),
        Color(0xFFB45309), Color(0xFF16A34A), Color(0xFFDC2626), Color(0xFF9333EA), Color(0xFF0891B2),
        Color(0xFF64748B), Color(0xFFA16207),
    )
}
