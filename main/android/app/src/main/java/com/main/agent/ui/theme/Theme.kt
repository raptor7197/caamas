package com.main.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val AgentColorScheme = lightColorScheme(
    primary          = AgentBlue,
    onPrimary        = AgentInk,
    primaryContainer = AgentBlueDark,
    onPrimaryContainer = AgentPaper,
    background       = AgentPaper,
    surface          = AgentPaper,
    surfaceVariant   = AgentMist,
    onBackground     = AgentOnSurf,
    onSurface        = AgentOnSurf,
    onSurfaceVariant = AgentInkSoft,
    secondary        = AgentInk,
    onSecondary      = AgentPaper,
    error            = AgentRed,
    errorContainer   = Color(0xFFFFE6DE),
    onErrorContainer = AgentInk,
    outline          = AgentLine,
)

private val AgentTypography = Typography(
    displayLarge = Typography().displayLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
    headlineMedium = Typography().headlineMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
    titleLarge = Typography().titleLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = Typography().titleMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    bodyLarge = Typography().bodyLarge.copy(fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp),
    bodyMedium = Typography().bodyMedium.copy(fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp),
    labelLarge = Typography().labelLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    labelSmall = Typography().labelSmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
)

@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AgentColorScheme,
        typography  = AgentTypography,
        content     = content,
    )
}
