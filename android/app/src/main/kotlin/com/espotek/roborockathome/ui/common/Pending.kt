package com.espotek.roborockathome.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

// Both indicators start from a visible position, so a frozen first frame (tests hold infinite
// animations still) still shows that something is in progress.

/** A thin bar with a segment sweeping to and fro, along the foot of a busy control. */
@Composable
internal fun PendingBar(modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    val phase = rememberInfiniteTransition(label = "pending bar").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "phase",
    )
    Canvas(modifier.height(2.dp)) {
        val y = size.height / 2
        drawLine(color.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
        val length = size.width * 0.4f
        val start = (size.width - length) * phase.value
        drawLine(color, Offset(start, y), Offset(start + length, y), size.height, StrokeCap.Round)
    }
}

/** A small turning arc, for a control too small for a bar, such as a switch's thumb. */
@Composable
internal fun PendingSpinner(modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    val turn = rememberInfiniteTransition(label = "pending spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "turn",
    )
    Canvas(modifier.size(14.dp)) {
        val stroke = 2.dp.toPx()
        drawArc(
            color = color,
            startAngle = turn.value - 90f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = size.copy(size.width - stroke, size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}
