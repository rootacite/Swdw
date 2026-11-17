package com.acite.swdw.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acite.swdw.theme.*
import kotlin.math.sin

@Composable
fun VisualizerCanvas(
    bars: FloatArray,
    isPlaying: Boolean,
    sampleRate: Int,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition()
    val idlePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(176.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF120F20))
            .border(1.dp, HanaStroke, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            if (isPlaying) {
                val nBars = bars.size
                val barSpacing = 4.dp.toPx()
                val totalSpacing = barSpacing * (nBars - 1)
                val barWidth = ((w - totalSpacing - 32.dp.toPx()) / nBars).coerceAtLeast(3f)
                val startX = (w - (barWidth * nBars + totalSpacing)) / 2f

                val barBrush = Brush.verticalGradient(
                    colors = listOf(HanaPink, HanaLilac, Color(0xFF5E4598)),
                    startY = 0f,
                    endY = h
                )

                for (i in 0 until nBars) {
                    val mag = bars[i].coerceIn(0.04f, 0.95f)
                    val barHeight = h * mag * 0.85f
                    val x = startX + i * (barWidth + barSpacing)
                    val y = (h - barHeight) / 2f

                    drawRoundRect(
                        brush = barBrush,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )
                }

                // Smooth overlay wave
                val wavePath = Path()
                for (i in 0 until nBars) {
                    val x = startX + i * (barWidth + barSpacing) + barWidth / 2f
                    val y = h / 2f - (bars[i] * h * 0.4f)
                    if (i == 0) {
                        wavePath.moveTo(x, y)
                    } else {
                        wavePath.lineTo(x, y)
                    }
                }
                drawPath(
                    path = wavePath,
                    color = HanaPinkLight.copy(alpha = 0.5f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            } else {
                // Idle ambient sine wave
                val idlePath = Path()
                val steps = 60
                val stepW = w / steps
                for (i in 0..steps) {
                    val x = i * stepW
                    val y = h / 2f + sin(idlePhase + (i * 0.15f)) * 12.dp.toPx()
                    if (i == 0) idlePath.moveTo(x, y) else idlePath.lineTo(x, y)
                }
                drawPath(
                    path = idlePath,
                    brush = Brush.horizontalGradient(
                        listOf(HanaLilac.copy(alpha = 0.2f), HanaPink.copy(alpha = 0.4f), HanaMint.copy(alpha = 0.2f))
                    ),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        // Top right visualizer spec
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = if (isPlaying) "LIVE FFT · 32 BANDS" else "SPECTRUM · IDLE",
                color = if (isPlaying) HanaPinkLight else HanaTextMuted,
                fontSize = 10.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
        }

        // Idle state indicator
        if (!isPlaying) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.4f))
                    .border(1.dp, HanaStroke.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "STANDBY · 音频输出待机",
                    color = HanaTextMuted,
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
