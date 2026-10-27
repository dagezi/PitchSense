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
import kotlin.math.log2

enum class PitchAxis { Hz, Semitones }

class GraphTrack(val pitch: PitchTrack, val color: Color)

/** Vertical range in axis units, which are linear on screen. */
private class AxisScale(val min: Float, val max: Float, val grid: List<Pair<Float, String>>)

private val HZ_SCALE = AxisScale(
    min = log2(50f),
    max = log2(800f),
    grid = listOf(50, 100, 200, 400, 800).map { log2(it.toFloat()) to "$it Hz" },
)

private val SEMITONE_SCALE = AxisScale(
    min = -12f,
    max = 12f,
    grid = listOf(-12, -6, 0, 6, 12).map { it.toFloat() to (if (it == 0) "0 st" else "%+d st".format(it)) },
)

/** Converts [hz] into axis units. Semitones are relative to the track's median pitch. */
private fun PitchAxis.valueOf(hz: Float, track: PitchTrack): Float = when (this) {
    PitchAxis.Hz -> log2(hz)
    PitchAxis.Semitones -> 12 * log2(hz / track.medianFrequency)
}

/** A vertical line marking a time, such as the playback position. */
class GraphCursor(val seconds: Float, val color: Color)

/** Overlays [tracks] spanning [durationSeconds] horizontally. */
@Composable
fun PitchGraph(
    tracks: List<GraphTrack>,
    durationSeconds: Float,
    axis: PitchAxis,
    modifier: Modifier = Modifier,
    cursor: GraphCursor? = null,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val textMeasurer = rememberTextMeasurer()
    val scale = when (axis) {
        PitchAxis.Hz -> HZ_SCALE
        PitchAxis.Semitones -> SEMITONE_SCALE
    }

    Canvas(modifier) {
        fun yOf(value: Float) = size.height * (scale.max - value) / (scale.max - scale.min)
        fun xOf(seconds: Float) = size.width * seconds / durationSeconds

        for (second in 1..durationSeconds.toInt()) {
            val x = xOf(second.toFloat())
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height))
        }
        for ((value, text) in scale.grid) {
            val y = yOf(value)
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y))
            val label = textMeasurer.measure(text, labelStyle)
            drawText(label, topLeft = Offset(4.dp.toPx(), (y - label.size.height).coerceAtLeast(0f)))
        }

        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (track in tracks) {
            val path = Path()
            var penDown = false
            track.pitch.frequencies.forEachIndexed { i, hz ->
                if (hz.isNaN()) {
                    penDown = false
                } else {
                    val x = xOf(track.pitch.timeOf(i))
                    val y = yOf(axis.valueOf(hz, track.pitch).coerceIn(scale.min, scale.max))
                    if (penDown) path.lineTo(x, y) else path.moveTo(x, y)
                    penDown = true
                }
            }
            drawPath(path, track.color, style = stroke)
        }

        if (cursor != null) {
            val x = xOf(cursor.seconds)
            drawLine(cursor.color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}
