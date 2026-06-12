package com.main.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary          = AgentBlue,
    onPrimary        = Color.White,
    primaryContainer = AgentBlueDark,
    background       = AgentSurface,
    surface          = AgentSurface,
    surfaceVariant   = AgentSurface2,
    onBackground     = AgentOnSurf,
    onSurface        = AgentOnSurf,
    secondary        = AgentGreen,
    error            = AgentRed,
)

@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content     = content,
    )
}
