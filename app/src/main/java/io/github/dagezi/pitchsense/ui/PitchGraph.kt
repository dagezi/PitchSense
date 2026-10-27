package io.github.dagezi.pitchsense.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import io.github.dagezi.pitchsense.pitch.PitchTrack
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.roundToInt

enum class PitchAxis { Hz, Semitones }

class GraphTrack(val id: Long, val pitch: PitchTrack, val offsetSeconds: Float, val color: Color)

/** A vertical line marking a time, such as the playback position. */
class GraphCursor(val seconds: Float, val color: Color)

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

private val PitchAxis.scale: AxisScale
    get() = when (this) {
        PitchAxis.Hz -> HZ_SCALE
        PitchAxis.Semitones -> SEMITONE_SCALE
    }

/** Converts [hz] into axis units. Semitones are relative to the track's median pitch. */
private fun PitchAxis.valueOf(hz: Float, track: PitchTrack): Float = when (this) {
    PitchAxis.Hz -> log2(hz)
    PitchAxis.Semitones -> 12 * log2(hz / track.medianFrequency)
}

/** Candidate spacings for time grid lines, in seconds. */
private val TIME_STEPS = floatArrayOf(0.01f, 0.02f, 0.05f, 0.1f, 0.2f, 0.5f, 1f, 2f, 5f, 10f, 30f, 60f)

/** Maps between timeline/pitch values and pixels for one layout and viewport. */
private class GraphGeometry(val size: Size, val viewport: Viewport, val axis: PitchAxis) {
    private val scale = axis.scale
    val pxPerSecond: Float get() = size.width / viewport.duration

    fun xOf(seconds: Float) = (seconds - viewport.start) * pxPerSecond
    fun timeAt(x: Float) = viewport.start + x / pxPerSecond
    fun yOf(value: Float) = size.height * (scale.max - value) / (scale.max - scale.min)

    fun yOfFrame(track: GraphTrack, hz: Float) =
        yOf(axis.valueOf(hz, track.pitch).coerceIn(scale.min, scale.max))

    /** Frame index range of [track] that falls inside the viewport, padded by one frame. */
    fun visibleFrames(track: GraphTrack): IntRange {
        val pitch = track.pitch
        val first = floor((viewport.start - track.offsetSeconds - pitch.startSeconds) / pitch.hopSeconds).toInt() - 1
        val last = ceil((viewport.end - track.offsetSeconds - pitch.startSeconds) / pitch.hopSeconds).toInt() + 1
        return first.coerceAtLeast(0)..last.coerceAtMost(pitch.frequencies.size - 1)
    }

    /** The track whose contour passes within [radius] px of [position], if any. */
    fun hitTrack(tracks: List<GraphTrack>, position: Offset, radius: Float): GraphTrack? {
        var best: GraphTrack? = null
        var bestDistance = radius
        val time = timeAt(position.x)
        for (track in tracks) {
            val pitch = track.pitch
            val center = ((time - track.offsetSeconds - pitch.startSeconds) / pitch.hopSeconds).roundToInt()
            val frameRadius = (radius / (pitch.hopSeconds * pxPerSecond)).toInt().coerceAtLeast(1)
            for (i in (center - frameRadius)..(center + frameRadius)) {
                val hz = pitch.frequencies.getOrNull(i) ?: continue
                if (hz.isNaN()) continue
                val point = Offset(xOf(pitch.timeOf(i) + track.offsetSeconds), yOfFrame(track, hz))
                val distance = (point - position).getDistance()
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = track
                }
            }
        }
        return best
    }
}

/**
 * Overlays [tracks] on a shared timeline and handles its gestures:
 * drag a contour to shift that track, drag elsewhere to scroll, pinch to zoom,
 * long-press and drag to select a range, and tap to clear the selection.
 */
@Composable
fun PitchGraph(
    tracks: List<GraphTrack>,
    viewport: Viewport,
    bounds: TimeRange,
    axis: PitchAxis,
    onViewportChange: (Viewport) -> Unit,
    onTrackOffsetChange: (id: Long, offsetSeconds: Float) -> Unit,
    onSelectionChange: (TimeRange?) -> Unit,
    modifier: Modifier = Modifier,
    selection: TimeRange? = null,
    cursor: GraphCursor? = null,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val textMeasurer = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current

    // The gesture handler outlives recompositions; read the latest values through these.
    val currentTracks by rememberUpdatedState(tracks)
    val currentViewport by rememberUpdatedState(viewport)
    val currentBounds by rememberUpdatedState(bounds)
    val currentAxis by rememberUpdatedState(axis)
    val currentOnViewportChange by rememberUpdatedState(onViewportChange)
    val currentOnTrackOffsetChange by rememberUpdatedState(onTrackOffsetChange)
    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)

    val gestures = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val geometry = GraphGeometry(size.toSize(), currentViewport, currentAxis)

            // Decide what the gesture is: it becomes a long-press if the finger stays put.
            var kind: GestureKind? = null
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (kind == null) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    kind = when {
                        event.changes.count { it.pressed } >= 2 -> GestureKind.Zoom
                        change == null || !change.pressed -> GestureKind.Tap
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop ->
                            GestureKind.Drag
                        else -> null
                    }
                }
            }

            when (kind ?: GestureKind.Select) {
                GestureKind.Tap -> currentOnSelectionChange(null)

                GestureKind.Drag -> {
                    val hit = geometry.hitTrack(currentTracks, down.position, radius = 24.dp.toPx())
                    val startOffset = hit?.offsetSeconds
                    trackDrag(down) { change ->
                        val deltaSeconds = (change.position.x - down.position.x) / geometry.pxPerSecond
                        if (hit != null && startOffset != null) {
                            currentOnTrackOffsetChange(hit.id, startOffset + deltaSeconds)
                        } else {
                            currentOnViewportChange(
                                geometry.viewport.copy(start = geometry.viewport.start - deltaSeconds)
                                    .clampedTo(currentBounds),
                            )
                        }
                    }
                }

                GestureKind.Zoom -> pinchZoom(geometry, currentBounds) { currentOnViewportChange(it) }

                GestureKind.Select -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val anchor = geometry.timeAt(down.position.x).coerceIn(currentBounds.start, currentBounds.end)
                    var last = anchor
                    currentOnSelectionChange(TimeRange(anchor, anchor))
                    trackDrag(down) { change ->
                        last = geometry.timeAt(change.position.x).coerceIn(currentBounds.start, currentBounds.end)
                        currentOnSelectionChange(TimeRange(minOf(anchor, last), maxOf(anchor, last)))
                    }
                    if (abs(last - anchor) * geometry.pxPerSecond < viewConfiguration.touchSlop) {
                        currentOnSelectionChange(null)
                    }
                }
            }
        }
    }

    Canvas(modifier.then(gestures)) {
        val geometry = GraphGeometry(size, viewport, axis)

        clipRect {
            if (selection != null) {
                val left = geometry.xOf(selection.start)
                drawRect(
                    selectionColor,
                    topLeft = Offset(left, 0f),
                    size = Size(geometry.xOf(selection.end) - left, size.height),
                )
            }

            // Time grid: the finest step that keeps labels at least 64dp apart.
            val minSpacing = 64.dp.toPx()
            val step = TIME_STEPS.firstOrNull { it * geometry.pxPerSecond >= minSpacing } ?: TIME_STEPS.last()
            val decimals = when {
                step >= 1f -> 0
                step >= 0.1f -> 1
                else -> 2
            }
            for (k in ceil(viewport.start / step).toInt()..floor(viewport.end / step).toInt()) {
                val seconds = k * step
                val x = geometry.xOf(seconds)
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height))
                // The left edge belongs to the pitch labels.
                if (x < 48.dp.toPx()) continue
                val label = textMeasurer.measure("%.${decimals}f s".format(seconds), labelStyle)
                drawText(label, topLeft = Offset(x + 2.dp.toPx(), size.height - label.size.height - 2.dp.toPx()))
            }

            for ((value, text) in axis.scale.grid) {
                val y = geometry.yOf(value)
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y))
                val label = textMeasurer.measure(text, labelStyle)
                drawText(label, topLeft = Offset(4.dp.toPx(), (y - label.size.height).coerceAtLeast(0f)))
            }

            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (track in tracks) {
                val path = Path()
                var penDown = false
                for (i in geometry.visibleFrames(track)) {
                    val hz = track.pitch.frequencies[i]
                    if (hz.isNaN()) {
                        penDown = false
                    } else {
                        val x = geometry.xOf(track.pitch.timeOf(i) + track.offsetSeconds)
                        val y = geometry.yOfFrame(track, hz)
                        if (penDown) path.lineTo(x, y) else path.moveTo(x, y)
                        penDown = true
                    }
                }
                drawPath(path, track.color, style = stroke)
            }

            if (cursor != null) {
                val x = geometry.xOf(cursor.seconds)
                drawLine(cursor.color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }
    }
}

private enum class GestureKind { Tap, Drag, Zoom, Select }

/** Feeds moves of the [down] pointer to [onMove] until it is lifted. */
private suspend fun AwaitPointerEventScope.trackDrag(
    down: PointerInputChange,
    onMove: (PointerInputChange) -> Unit,
) {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return
        if (!change.pressed) return
        onMove(change)
        change.consume()
    }
}

/** Zooms horizontally around the fingers' midpoint while two pointers are down. */
private suspend fun AwaitPointerEventScope.pinchZoom(
    geometry: GraphGeometry,
    bounds: TimeRange,
    onViewportChange: (Viewport) -> Unit,
) {
    var viewport = geometry.viewport
    var previous: Pair<Float, Float>? = null // span and center x
    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        if (pressed.size < 2) return
        val (a, b) = pressed
        val span = abs(a.position.x - b.position.x).coerceAtLeast(1f)
        val center = (a.position.x + b.position.x) / 2
        previous?.let { (previousSpan, previousCenter) ->
            val width = geometry.size.width
            // Keep the time that was under the fingers' midpoint under the new midpoint.
            val anchorTime = viewport.start + previousCenter / width * viewport.duration
            val duration = viewport.duration * previousSpan / span
            viewport = Viewport(anchorTime - center / width * duration, duration).clampedTo(bounds)
            onViewportChange(viewport)
        }
        previous = span to center
        event.changes.forEach { it.consume() }
    }
}
