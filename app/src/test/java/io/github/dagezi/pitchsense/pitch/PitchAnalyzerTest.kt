package io.github.dagezi.pitchsense.pitch

import io.github.dagezi.pitchsense.audio.DecodedAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

class PitchAnalyzerTest {
    /** Voice-like tone: [f0] plus decaying harmonics, which is where octave errors show up. */
    private fun harmonicTone(f0: Double, sampleRate: Int, seconds: Double) =
        FloatArray((sampleRate * seconds).toInt()) { i ->
            val t = i.toDouble() / sampleRate
            (1..6).sumOf { k -> sin(2 * PI * f0 * k * t) / k }.toFloat() * 0.3f
        }

    /**
     * Voice-like tone following [f0At] (Hz, or NaN for silence) at 16 kHz, plus 60 Hz mains hum
     * and white noise at the given amplitudes.
     */
    private fun voice(seconds: Double, hum: Double = 0.0, noise: Double = 0.0, f0At: (Double) -> Double): FloatArray {
        val random = Random(1)
        var phase = 0.0
        return FloatArray((16_000 * seconds).toInt()) { i ->
            val t = i / 16_000.0
            val f0 = f0At(t)
            var x = 0.0
            if (!f0.isNaN()) {
                phase += 2 * PI * f0 / 16_000
                x = (1..7).sumOf { k -> sin(k * phase) / k } * 0.3
            }
            (x + hum * sin(2 * PI * 60 * t) + noise * random.nextGaussian()).toFloat()
        }
    }

    private fun semitonesBetween(a: Float, b: Double) = abs(12 * ln(a / b) / ln(2.0))

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

    @Test
    fun followsFastFallThroughHumAndNoise() {
        // 390 -> 155 Hz in 200 ms (about 0.8 semitones per frame), like a sharply falling accent.
        val f0At = { t: Double ->
            if (t < 0.2 || t > 0.6) Double.NaN else 390 * (155.0 / 390).pow(((t - 0.3) / 0.2).coerceIn(0.0, 1.0))
        }
        val track = PitchAnalyzer.analyze(DecodedAudio(voice(0.8, hum = 0.03, noise = 0.01, f0At = f0At), 16_000))
        // Frames whose window lies entirely inside the voiced part.
        val inside = track.frequencies.indices.filter { track.timeOf(it) in 0.23f..0.57f }
        for (i in inside) {
            val hz = track.frequencies[i]
            val expected = f0At(track.timeOf(i).toDouble())
            assertTrue("frame $i unvoiced", !hz.isNaN())
            assertTrue("frame $i: $hz Hz, expected $expected", semitonesBetween(hz, expected) < 1)
        }
    }

    @Test
    fun mainsHumIsNotPitch() {
        val track = PitchAnalyzer.analyze(
            DecodedAudio(voice(0.9, hum = 0.05) { t -> if (t in 0.2..0.7) 220.0 else Double.NaN }, 16_000),
        )
        val silent = track.frequencies.indices.filter { track.timeOf(it) !in 0.17f..0.73f }
        assertTrue(silent.isNotEmpty())
        assertTrue(silent.all { track.frequencies[it].isNaN() })
        assertEquals(220f, voicedMedian(track), 2f)
    }

    @Test
    fun noiseBurstIsUnvoiced() {
        val random = Random(2)
        val samples = voice(0.75) { t -> if (t < 0.3) 200.0 else if (t >= 0.45) 180.0 else Double.NaN }
        // A fricative-like burst between the two voiced parts.
        for (i in 4_800 until 7_200) samples[i] = (0.2 * random.nextGaussian()).toFloat()
        val track = PitchAnalyzer.analyze(DecodedAudio(samples, 16_000))
        val burst = track.frequencies.indices.filter { track.timeOf(it) in 0.32f..0.43f }
        assertTrue(burst.all { track.frequencies[it].isNaN() })
    }

    @Test
    fun detectsVeryLowVoice() {
        val track = PitchAnalyzer.analyze(
            DecodedAudio(voice(1.0, noise = 0.005) { t -> if (t in 0.2..0.8) 70.0 else Double.NaN }, 16_000),
        )
        assertEquals(70f, voicedMedian(track), 1f)
    }
}
