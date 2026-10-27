package io.github.dagezi.pitchsense.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportTest {
    private val bounds = TimeRange(0f, 10f)

    @Test
    fun insideBoundsIsUnchanged() {
        assertEquals(Viewport(2f, 3f), Viewport(2f, 3f).clampedTo(bounds))
    }

    @Test
    fun scrollingPastEitherEndStopsAtTheEdge() {
        assertEquals(Viewport(0f, 3f), Viewport(-1f, 3f).clampedTo(bounds))
        assertEquals(Viewport(7f, 3f), Viewport(9f, 3f).clampedTo(bounds))
    }

    @Test
    fun zoomIsLimitedToBoundsAndMinimumWidth() {
        assertEquals(Viewport(0f, 10f), Viewport(-5f, 20f).clampedTo(bounds))
        assertEquals(Viewport(5f, Viewport.MIN_VISIBLE_SECONDS), Viewport(5f, 0.01f).clampedTo(bounds))
    }

    @Test
    fun fullWidthWithRoundingErrorDoesNotCrash() {
        // From a crash: end - length lands just below start in float arithmetic.
        val bounds = TimeRange(-0.025718153f, 2.1f)
        val clamped = Viewport.fit(bounds).clampedTo(bounds)
        assertEquals(bounds.start, clamped.start)
    }

    @Test
    fun boundsShorterThanMinimumWidthShowEverything() {
        assertEquals(Viewport(0f, 0.05f), Viewport(0f, 0.01f).clampedTo(TimeRange(0f, 0.05f)))
    }
}
