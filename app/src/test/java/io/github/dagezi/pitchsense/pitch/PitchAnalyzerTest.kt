package io.github.dagezi.pitchsense.pitch

import io.github.dagezi.pitchsense.audio.DecodedAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PitchAnalyzerTest {
    /** Voice-like tone: [f0] plus decaying harmonics, which is where octave errors show up. */
    private fun harmonicTone(f0: Double, sampleRate: Int, seconds: Double) =
        FloatArray((sampleRate * seconds).toInt()) { i ->
            val t = i.toDouble() / sampleRate
            (1..6).sumOf { k -> sin(2 * PI * f0 * k * t) / k }.toFloat() * 0.3f
        }

    private fun voicedMedian(track: PitchTrack): Float {
        val voiced = track.frequencies.filterNot { it.isNaN() }.sorted()
        assertTrue("no voiced frames", voiced.isNotEmpty())
        return voiced[voiced.size / 2]
    }

    @Test
    fun detectsLowMaleVoicePitch() {
        val track = PitchAnalyzer.analyze(DecodedAudio(harmonicTone(110.0, 44_100, 1.0), 44_100))
        assertEquals(110f, voicedMedian(track), 1f)
    }

    @Test
    fun detectsHighVoicePitch() {
        val track = PitchAnalyzer.analyze(DecodedAudio(harmonicTone(330.0, 48_000, 1.0), 48_000))
        assertEquals(330f, voicedMedian(track), 2f)
    }

    @Test
    fun followsGlidingPitch() {
        val sampleRate = 16_000
        // Linear glide from 150 Hz to 300 Hz over one second.
        var phase = 0.0
        val samples = FloatArray(sampleRate) { i ->
            val f = 150.0 + 150.0 * i / sampleRate
            phase += 2 * PI * f / sampleRate
            (sin(phase) * 0.5).toFloat()
        }
        val track = PitchAnalyzer.analyze(DecodedAudio(samples, sampleRate))
        track.frequencies.forEachIndexed { i, hz ->
            val expected = 150f + 150f * track.timeOf(i)
            assertEquals("frame $i", expected, hz, expected * 0.03f)
        }
    }

    @Test
    fun silenceIsUnvoiced() {
        val samples = harmonicTone(200.0, 16_000, 1.0)
        samples.fill(0f, 0, 8_000)
        val track = PitchAnalyzer.analyze(DecodedAudio(samples, 16_000))
        val silentFrames = track.frequencies.indices.filter { track.timeOf(it) < 0.45f }
        assertTrue(silentFrames.all { track.frequencies[it].isNaN() })
        assertEquals(200f, voicedMedian(track), 2f)
    }

    @Test
    fun tooShortAudioYieldsEmptyTrack() {
        val track = PitchAnalyzer.analyze(DecodedAudio(FloatArray(100), 44_100))
        assertEquals(0, track.frequencies.size)
    }
}
