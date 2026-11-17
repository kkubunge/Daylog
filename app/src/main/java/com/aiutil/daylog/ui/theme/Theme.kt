package com.aiutil.daylog.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 색채를 배제한 깔끔한 무채색(모노크롬) 디자인 팔레트
val MonoCharcoal = Color(0xFF15171A)
val MonoLightBg = Color(0xFFF6F7F9)
val MonoLightSurface = Color(0xFFFFFFFF)
val MonoLightSubSurface = Color(0xFFEDF0F4)
val MonoLightText = Color(0xFF111315)
val MonoLightMutedText = Color(0xFF68707A)
val MonoLightOutline = Color(0xFFE2E5EA)
val MonoLightOutlineSubtle = Color(0xFFECEDF1)

val MonoDarkBg = Color(0xFF0F1012)
val MonoDarkSurface = Color(0xFF17191D)
val MonoDarkSubSurface = Color(0xFF22252B)
val MonoDarkText = Color(0xFFF3F4F6)
val MonoDarkMutedText = Color(0xFF9AA1AC)
val MonoDarkOutline = Color(0xFF2D3139)

private val LightColors = lightColorScheme(
    primary = MonoCharcoal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9ECEF),
    onPrimaryContainer = MonoCharcoal,
    secondary = Color(0xFF495057),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1F3F5),
    onSecondaryContainer = Color(0xFF212529),
    tertiary = Color(0xFF343A40),
    onTertiary = Color.White,
    background = MonoLightBg,
    onBackground = MonoLightText,
    surface = MonoLightSurface,
    onSurface = MonoLightText,
    surfaceVariant = MonoLightSubSurface,
    onSurfaceVariant = MonoLightMutedText,
    outline = MonoLightOutline,
    outlineVariant = MonoLightOutlineSubtle,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF1F3F5),
    onPrimary = Color(0xFF111315),
    primaryContainer = Color(0xFF2A2E35),
    onPrimaryContainer = Color(0xFFF3F4F6),
    secondary = Color(0xFFADB5BD),
    onSecondary = Color(0xFF15171A),
    secondaryContainer = Color(0xFF24282F),
    onSecondaryContainer = Color(0xFFE5E7EB),
    tertiary = Color(0xFFCED4DA),
    onTertiary = Color(0xFF111315),
    background = MonoDarkBg,
    onBackground = MonoDarkText,
    surface = MonoDarkSurface,
    onSurface = MonoDarkText,
    surfaceVariant = MonoDarkSubSurface,
    onSurfaceVariant = MonoDarkMutedText,
    outline = MonoDarkOutline,
    outlineVariant = Color(0xFF23272E),
)

@Composable
fun DaylogTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
