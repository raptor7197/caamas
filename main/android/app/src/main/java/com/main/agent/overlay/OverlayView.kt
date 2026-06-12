package com.main.agent.overlay

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun OverlayView(
    state: OverlayState,
    onTap: () -> Unit,
) {
    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val scale by pulseAnim.animateFloat(
        initialValue = 1.0f,
        targetValue  = if (state is OverlayState.Listening) 1.2f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )

    val bgColor = when (state) {
        is OverlayState.Idle        -> Color(0xFF1A73E8)
        is OverlayState.Listening   -> Color(0xFFE53935)
        is OverlayState.Thinking    -> Color(0xFFF57C00)
        is OverlayState.Speaking    -> Color(0xFF43A047)
        is OverlayState.RunningTool -> Color(0xFF7B1FA2)
        is OverlayState.Error       -> Color(0xFFB71C1C)
    }

    val icon = when (state) {
        is OverlayState.Idle        -> Icons.Default.Mic
        is OverlayState.Listening   -> Icons.Default.MicOff
        is OverlayState.Thinking    -> Icons.Default.Autorenew
        is OverlayState.Speaking    -> Icons.Default.VolumeUp
        is OverlayState.RunningTool -> Icons.Default.Build
        is OverlayState.Error       -> Icons.Default.Error
    }

    FloatingActionButton(
        onClick           = onTap,
        modifier          = Modifier.scale(scale).size(56.dp),
        shape             = CircleShape,
        containerColor    = bgColor,
        contentColor      = Color.White,
        elevation         = FloatingActionButtonDefaults.elevation(6.dp),
    ) {
        Icon(icon, contentDescription = "Agent", modifier = Modifier.size(28.dp))
    }
}
