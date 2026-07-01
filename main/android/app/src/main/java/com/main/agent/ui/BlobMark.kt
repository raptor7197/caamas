package com.main.agent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import com.main.agent.ui.theme.AgentInk
import com.main.agent.ui.theme.AgentPaper
import com.main.agent.ui.theme.AgentPeach

@Composable
fun BlobMark(
    modifier: Modifier = Modifier,
    ink: Color = AgentInk,
    paper: Color = AgentPaper,
    accent: Color = AgentPeach,
) {
    Canvas(modifier.aspectRatio(1f)) {
        val w = size.width
        val h = size.height
        val blob = Path().apply {
            moveTo(w * 0.18f, h * 0.62f)
            cubicTo(w * 0.12f, h * 0.28f, w * 0.38f, h * 0.12f, w * 0.47f, h * 0.3f)
            cubicTo(w * 0.61f, h * 0.06f, w * 0.91f, h * 0.2f, w * 0.79f, h * 0.53f)
            cubicTo(w * 0.93f, h * 0.73f, w * 0.69f, h * 0.91f, w * 0.47f, h * 0.84f)
            cubicTo(w * 0.31f, h * 0.92f, w * 0.16f, h * 0.8f, w * 0.18f, h * 0.62f)
            close()
        }

        drawPath(blob, ink)
        drawCircle(paper, radius = w * 0.09f, center = Offset(w * 0.39f, h * 0.48f))
        drawCircle(paper, radius = w * 0.09f, center = Offset(w * 0.62f, h * 0.45f))
        drawCircle(ink, radius = w * 0.028f, center = Offset(w * 0.41f, h * 0.5f))
        drawCircle(ink, radius = w * 0.028f, center = Offset(w * 0.64f, h * 0.47f))
        drawCircle(accent, radius = w * 0.04f, center = Offset(w * 0.72f, h * 0.66f))
        drawLine(
            color = ink,
            start = Offset(w * 0.14f, h * 0.49f),
            end = Offset(w * 0.02f, h * 0.42f),
            strokeWidth = w * 0.025f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = ink,
            start = Offset(w * 0.17f, h * 0.6f),
            end = Offset(w * 0.03f, h * 0.61f),
            strokeWidth = w * 0.025f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = ink,
            start = Offset(w * 0.79f, h * 0.49f),
            end = Offset(w * 0.93f, h * 0.42f),
            strokeWidth = w * 0.025f,
            cap = StrokeCap.Round,
        )
    }
}
