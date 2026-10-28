package io.github.dagezi.pitchsense.pitch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PitchTrackerTest {
    private fun frame(vararg candidates: Pair<Float, Float>) = candidates.map { (hz, cost) -> PitchCandidate(hz, cost) }

    @Test
    fun emptyInputYieldsEmptyTrack() {
        assertEquals(0, PitchTracker.track(emptyList()).size)
    }

    @Test
    fun framesWithoutCandidatesAreUnvoiced() {
        val voiced = List(5) { frame(200f to 0.05f) }
        val result = PitchTracker.track(voiced + listOf(frame()) + voiced)
        assertArrayEquals(FloatArray(5) { 200f } + Float.NaN + FloatArray(5) { 200f }, result, 0f)
    }

    @Test
    fun weakFramesContinuingTheContourAreKept() {
        val frames = listOf(
            frame(200f to 0.05f, 100f to 0.2f),
            frame(205f to 0.6f, 100f to 0.7f),
            frame(210f to 0.6f),
            frame(215f to 0.05f, 107f to 0.2f),
        )
        assertArrayEquals(floatArrayOf(200f, 205f, 210f, 215f), PitchTracker.track(frames), 0f)
    }

    @Test
    fun avoidsOctaveJumpToSlightlyBetterCandidate() {
        val frames = listOf(
            frame(150f to 0.05f, 75f to 0.1f),
            frame(150f to 0.12f, 75f to 0.08f),
            frame(150f to 0.05f, 75f to 0.1f),
        )
        assertArrayEquals(floatArrayOf(150f, 150f, 150f), PitchTracker.track(frames), 0f)
    }

    @Test
    fun isolatedWeakFrameIsUnvoiced() {
        val result = PitchTracker.track(listOf(frame(), frame(300f to 0.45f), frame()))
        assertArrayEquals(floatArrayOf(Float.NaN, Float.NaN, Float.NaN), result, 0f)
    }
}
