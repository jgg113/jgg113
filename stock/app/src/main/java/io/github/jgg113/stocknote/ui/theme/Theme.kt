package io.github.jgg113.stocknote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jgg113.stocknote.R

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4EDFF),
    onPrimaryContainer = Color(0xFF0B2A6F),
    secondary = Color(0xFF4E5968),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDF2FA),
    onSecondaryContainer = Color(0xFF26303F),
    tertiary = Color(0xFF7A4DE8),
    tertiaryContainer = Color(0xFFEEE7FF),
    background = Color(0xFFF2F4F7),
    onBackground = Color(0xFF191F28),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191F28),
    surfaceVariant = Color(0xFFEEF1F5),
    onSurfaceVariant = Color(0xFF6B7684),
    outline = Color(0xFFB0B8C1),
    outlineVariant = Color(0xFFE5E8EC),
    error = Color(0xFFE5323B),
    errorContainer = Color(0xFFFFECEC),
    onErrorContainer = Color(0xFF7A1218),
    // 기본값의 보랏빛 바탕을 없애고 회색 계열로 맞춘다 (하단 탭·대화상자·입력칸).
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF7F8FA),
    surfaceContainerHighest = Color(0xFFEEF1F5),
    surfaceTint = Color(0xFF2F6BFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FB4FF),
    onPrimary = Color(0xFF0A1F5C),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFFC0C7D2),
    secondaryContainer = Color(0xFF2A303B),
    onSecondaryContainer = Color(0xFFE3E7EE),
    tertiary = Color(0xFFCDB8FF),
    tertiaryContainer = Color(0xFF4C3591),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFECEFF3),
    surface = Color(0xFF181B21),
    onSurface = Color(0xFFECEFF3),
    surfaceVariant = Color(0xFF232730),
    onSurfaceVariant = Color(0xFFA3ACB9),
    outline = Color(0xFF5B6472),
    outlineVariant = Color(0xFF2F3541),
    error = Color(0xFFFF6B6B),
    errorContainer = Color(0xFF4A1C1F),
    onErrorContainer = Color(0xFFFFDADA),
    surfaceContainerLowest = Color(0xFF0F1115),
    surfaceContainerLow = Color(0xFF181B21),
    surfaceContainer = Color(0xFF181B21),
    surfaceContainerHigh = Color(0xFF20242C),
    surfaceContainerHighest = Color(0xFF2A2F39),
    surfaceTint = Color(0xFF8FB4FF),
)

/**
 * 휴대폰에 설정된 글꼴(손글씨체 등)을 따라가지 않도록 글꼴을 앱에 넣어 고정한다.
 * Pretendard Std (SIL Open Font License 1.1, licenses/Pretendard-OFL.txt)
 */
val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
    Font(R.font.pretendard_extrabold, FontWeight.ExtraBold),
)

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, spacing: Double = -0.1) =
    TextStyle(fontFamily = Pretendard, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.sp)

private val AppTypography = Typography(
    displayLarge = style(48, 56, FontWeight.Bold, -0.6),
    displayMedium = style(40, 48, FontWeight.Bold, -0.5),
    displaySmall = style(34, 42, FontWeight.Bold, -0.4),
    headlineLarge = style(30, 38, FontWeight.Bold, -0.4),
    headlineMedium = style(26, 34, FontWeight.Bold, -0.4),
    headlineSmall = style(22, 30, FontWeight.Bold, -0.3),
    titleLarge = style(19, 27, FontWeight.SemiBold, -0.3),
    titleMedium = style(16, 23, FontWeight.SemiBold, -0.2),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 24),
    bodyMedium = style(14, 21),
    bodySmall = style(12, 18, spacing = 0.0),
    labelLarge = style(14, 20, FontWeight.SemiBold),
    labelMedium = style(12, 16, FontWeight.Medium, 0.0),
    labelSmall = style(11, 15, FontWeight.Medium, 0.0),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun StockTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, typography = AppTypography, shapes = AppShapes, content = content)
}

/** 하단 탭 그림 (Material Symbols 의 선 그림, Apache 2.0) */
object AppIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()

    val Market = icon("market", "M4 10h4v10H4z M10 4h4v16h-4z M16 13h4v7h-4z")
    val Watch = icon("watch", "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z")
    val Discover = icon("discover", "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z")
    val Invest = icon("invest", "M11 2v20c-5.07-.5-9-4.79-9-10s3.93-9.5 9-10zm2.03 0v8.99H22c-.47-4.74-4.24-8.52-8.97-8.99zm0 11.01V22c4.74-.47 8.5-4.25 8.97-8.99h-8.97z")
    val Settings = icon("settings", "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z")
    val Back = icon("back", "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z")
}

/** 국내 관례대로 오르면 빨강, 내리면 파랑 */
object PriceColors {
    val up: Color @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xFFFF6B6B) else Color(0xFFF04452)
    val down: Color @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xFF6EA8FF) else Color(0xFF2F6BFF)
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
