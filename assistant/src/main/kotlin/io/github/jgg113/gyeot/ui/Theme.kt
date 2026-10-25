package io.github.jgg113.gyeot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 클로드비서 디자인: 따뜻한 크림색 바탕 + 흰 둥근 카드 + 코랄 포인트.
 * 토스·원 UI 처럼 큰 제목, 넉넉한 여백, 테두리 없는 카드로 깔끔하게.
 */
object Brand {
    val Coral = Color(0xFFC96442)
    val CoralSoft = Color(0xFFFBE9E1)
    val Cream = Color(0xFFF7F5F0)
    val Ink = Color(0xFF1F1E1D)
    val Muted = Color(0xFF6B6862)
    val Green = Color(0xFF2E8B57)
    val GreenSoft = Color(0xFFE2F3E8)
    val Blue = Color(0xFF3B6E8F)
    val BlueSoft = Color(0xFFE3EEF6)
    val Amber = Color(0xFFB7791F)
    val AmberSoft = Color(0xFFFFF3D6)
}

private val Light = lightColorScheme(
    primary = Brand.Coral,
    onPrimary = Color.White,
    primaryContainer = Brand.CoralSoft,
    onPrimaryContainer = Color(0xFF5A2414),
    secondary = Brand.Blue,
    onSecondary = Color.White,
    secondaryContainer = Brand.BlueSoft,
    onSecondaryContainer = Color(0xFF173246),
    tertiary = Brand.Green,
    onTertiary = Color.White,
    tertiaryContainer = Brand.GreenSoft,
    onTertiaryContainer = Color(0xFF0E3A22),
    background = Brand.Cream,
    onBackground = Brand.Ink,
    surface = Color.White,
    onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFF0EDE6),
    onSurfaceVariant = Brand.Muted,
    surfaceContainer = Color(0xFFF3F1EB),
    surfaceContainerHigh = Color(0xFFEDEAE3),
    outline = Color(0xFFB8B3AA),
    outlineVariant = Color(0xFFE6E2DA),
    error = Color(0xFFC62828),
    errorContainer = Color(0xFFFDECEA),
    onErrorContainer = Color(0xFF5F1412),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFE08A6A),
    onPrimary = Color(0xFF3A1206),
    primaryContainer = Color(0xFF5A2A1A),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFF9CC7E4),
    secondaryContainer = Color(0xFF24394A),
    onSecondaryContainer = Color(0xFFD3E8F7),
    tertiary = Color(0xFF7FD3A3),
    tertiaryContainer = Color(0xFF1F3B2B),
    onTertiaryContainer = Color(0xFFC9F0D8),
    background = Color(0xFF191817),
    onBackground = Color(0xFFEDEBE7),
    surface = Color(0xFF242322),
    onSurface = Color(0xFFEDEBE7),
    surfaceVariant = Color(0xFF2E2C2A),
    onSurfaceVariant = Color(0xFFB5B0A8),
    surfaceContainer = Color(0xFF2A2827),
    surfaceContainerHigh = Color(0xFF33312F),
    outline = Color(0xFF6E6A64),
    outlineVariant = Color(0xFF3A3836),
    errorContainer = Color(0xFF4A1C1A),
)

private val Type = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.5).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun BrandTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Type,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}

/** 작은 글씨 스타일 모음 */
object TextStyles {
    val caption: TextStyle @Composable get() = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
}
