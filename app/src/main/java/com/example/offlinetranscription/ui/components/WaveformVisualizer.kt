package com.example.offlinetranscription.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.offlinetranscription.theme.CyanAccent
import com.example.offlinetranscription.theme.NvidiaGreen
import kotlin.math.sin

@Composable
fun WaveformVisualizer(rmsLevel: StateFlow<Float>, isRecording: Boolean, modifier: Modifier = Modifier) {
    val level by rmsLevel.collectAsStateWithLifecycle()
    WaveformVisualizer(level, isRecording, modifier)
}

@Composable
fun WaveformVisualizer(
    rmsLevel: Float,
    isRecording: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 36
) {
    val smoothedRms = remember { Animatable(0f) }

    LaunchedEffect(rmsLevel) {
        smoothedRms.animateTo(
            targetValue = rmsLevel,
            animationSpec = tween(durationMillis = 70, easing = LinearEasing)
        )
    }

    val phase by if (isRecording) animatedPhase() else remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val barSpacing = width / barCount
        val barWidth = barSpacing * 0.55f

        val gradient = Brush.verticalGradient(
            colors = listOf(CyanAccent, NvidiaGreen),
            startY = 0f,
            endY = height
        )

        for (i in 0 until barCount) {
            val x = i * barSpacing + (barSpacing - barWidth) / 2f
            val waveFactor = sin(phase + (i.toFloat() / barCount) * 4f) * 0.5f + 0.5f
            val amplitude = smoothedRms.value * height * 0.85f
            val baseHeight = if (isRecording) (6f + amplitude * waveFactor).coerceIn(4f, height - 6f) else 4f
            drawRoundRect(brush = gradient, topLeft = Offset(x, centerY - baseHeight / 2f),
                size = Size(barWidth, baseHeight), cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                alpha = if (isRecording) 0.95f else 0.25f)
        }
    }
}

@Composable
private fun animatedPhase(): State<Float> {
    val infiniteTransition = rememberInfiniteTransition(label = "wave_anim")
    return infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

}
