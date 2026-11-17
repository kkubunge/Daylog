package com.aiutil.daylog.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 따뜻한 종이 다이어리 & 폴라로이드 감성 컬러 팔레트
val TerracottaPrimary = Color(0xFFC86D51)
val TerracottaContainer = Color(0xFFF7E2D9)
val SageSecondary = Color(0xFF5E8267)
val SageContainer = Color(0xFFE0EDE3)
val HoneyTertiary = Color(0xFFC98A3A)
val WarmPaperBg = Color(0xFFFAF6F0)
val WarmCardSurface = Color(0xFFFFFDF9)
val WarmSubSurface = Color(0xFFF2EAE0)
val InkText = Color(0xFF2B2523)
val MutedCocoaText = Color(0xFF786D67)
val WarmOutline = Color(0xFFE3D8CE)

private val LightColors = lightColorScheme(
    primary = TerracottaPrimary,
    onPrimary = Color.White,
    primaryContainer = TerracottaContainer,
    onPrimaryContainer = Color(0xFF4A1D10),
    secondary = SageSecondary,
    onSecondary = Color.White,
    secondaryContainer = SageContainer,
    onSecondaryContainer = Color(0xFF193520),
    tertiary = HoneyTertiary,
    onTertiary = Color.White,
    background = WarmPaperBg,
    onBackground = InkText,
    surface = WarmCardSurface,
    onSurface = InkText,
    surfaceVariant = WarmSubSurface,
    onSurfaceVariant = MutedCocoaText,
    outline = WarmOutline,
    outlineVariant = Color(0xFFEDE4DB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE6967C),
    onPrimary = Color(0xFF461B0D),
    primaryContainer = Color(0xFF662E1B),
    onPrimaryContainer = Color(0xFFFFDBD0),
    secondary = Color(0xFF8FBC99),
    onSecondary = Color(0xFF16351E),
    secondaryContainer = Color(0xFF2B4C33),
    onSecondaryContainer = Color(0xFFD5F0DA),
    tertiary = Color(0xFFE6B46C),
    background = Color(0xFF191513),
    onBackground = Color(0xFFF3ECE6),
    surface = Color(0xFF241E1B),
    onSurface = Color(0xFFF3ECE6),
    surfaceVariant = Color(0xFF332B27),
    onSurfaceVariant = Color(0xFFC7BBB4),
    outline = Color(0xFF4D423C),
    outlineVariant = Color(0xFF3A312C),
)

@Composable
fun DaylogTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // 다이어리의 따뜻한 색감을 일관되게 유지하기 위해 전용 팔레트 적용
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
