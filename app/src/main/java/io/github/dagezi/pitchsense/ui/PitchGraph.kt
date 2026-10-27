package io.github.dagezi.pitchsense.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dagezi.pitchsense.pitch.PitchTrack
import kotlin.math.ln

private const val MIN_HZ = 50f
private const val MAX_HZ = 800f
private val GRID_HZ = listOf(50, 100, 200, 400, 800)

/** Plots [track] on a logarithmic frequency axis spanning [durationSeconds] horizontally. */
@Composable
fun PitchGraph(
    track: PitchTrack,
    durationSeconds: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier) {
        fun yOf(hz: Float) = size.height * (1 - ln(hz / MIN_HZ) / ln(MAX_HZ / MIN_HZ))
        fun xOf(seconds: Float) = size.width * seconds / durationSeconds

        for (second in 1..durationSeconds.toInt()) {
            val x = xOf(second.toFloat())
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height))
        }
        for (hz in GRID_HZ) {
            val y = yOf(hz.toFloat())
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y))
            val label = textMeasurer.measure("$hz Hz", labelStyle)
            drawText(label, topLeft = Offset(4.dp.toPx(), (y - label.size.height).coerceAtLeast(0f)))
        }

        val path = Path()
        var penDown = false
        track.frequencies.forEachIndexed { i, hz ->
            if (hz.isNaN()) {
                penDown = false
            } else {
                val x = xOf(track.timeOf(i))
                val y = yOf(hz.coerceIn(MIN_HZ, MAX_HZ))
                if (penDown) path.lineTo(x, y) else path.moveTo(x, y)
                penDown = true
            }
        }
        drawPath(
            path,
            color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
