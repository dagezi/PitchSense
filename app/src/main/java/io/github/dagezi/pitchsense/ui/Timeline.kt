package io.github.dagezi.pitchsense.ui

/** A span on the shared timeline, in seconds. [start] <= [end]. */
data class TimeRange(val start: Float, val end: Float) {
    val length: Float get() = end - start
}

/** The visible part of the timeline. */
data class Viewport(val start: Float, val duration: Float) {
    val end: Float get() = start + duration

    /** Keeps the viewport inside [bounds] and not narrower than [MIN_VISIBLE_SECONDS]. */
    fun clampedTo(bounds: TimeRange): Viewport {
        val clampedDuration = duration.coerceIn(minOf(MIN_VISIBLE_SECONDS, bounds.length), bounds.length)
        return Viewport(start.coerceIn(bounds.start, bounds.end - clampedDuration), clampedDuration)
    }

    companion object {
        const val MIN_VISIBLE_SECONDS = 0.1f

        fun fit(bounds: TimeRange) = Viewport(bounds.start, bounds.length)
    }
}
