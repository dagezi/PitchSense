package io.github.dagezi.pitchsense.pitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchTrackTest {
    private fun track(vararg hz: Float) = PitchTrack(hz, hopSeconds = 0.01f, startSeconds = 0f)

    @Test
    fun medianIgnoresUnvoicedFrames() {
        assertEquals(200f, track(Float.NaN, 100f, 200f, Float.NaN, 300f).medianFrequency, 0f)
    }

    @Test
    fun medianOfEvenCountAveragesMiddleValues() {
        assertEquals(150f, track(100f, 200f, Float.NaN).medianFrequency, 0f)
    }

    @Test
    fun onsetSkipsIsolatedVoicedFrames() {
        val n = Float.NaN
        val t = track(n, 100f, n, n, 100f, 100f, 100f, n)
        assertEquals(t.timeOf(4), t.onsetSeconds(minRun = 3))
    }

    @Test
    fun onsetIsNullWithoutLongEnoughRun() {
        assertEquals(null, track(100f, Float.NaN, 100f, 100f).onsetSeconds(minRun = 3))
    }

    @Test
    fun medianOfUnvoicedTrackIsNaN() {
        assertTrue(track(Float.NaN, Float.NaN).medianFrequency.isNaN())
        assertTrue(track().medianFrequency.isNaN())
    }
}
