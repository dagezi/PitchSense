package io.github.dagezi.pitchsense.pitch

import io.github.dagezi.pitchsense.audio.DecodedAudio
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** F0 contour sampled at a fixed hop. Unvoiced frames are NaN. */
class PitchTrack(
    val frequencies: FloatArray,
    val hopSeconds: Float,
    /** Time of the first frame; frames are stamped at the center of their integration window. */
    val startSeconds: Float,
) {
    fun timeOf(index: Int): Float = startSeconds + index * hopSeconds
}

object PitchAnalyzer {
    /** Speech F0 is well below 1 kHz, so analyzing at 16 kHz keeps YIN cheap without losing accuracy. */
    const val ANALYSIS_SAMPLE_RATE = 16_000
    private const val HOP_SECONDS = 0.01f

    /** Frames quieter than this fraction of the peak amplitude are treated as unvoiced. */
    private const val SILENCE_RATIO = 0.03f

    fun analyze(audio: DecodedAudio): PitchTrack {
        val samples = Resampler.resample(audio.samples, audio.sampleRate, ANALYSIS_SAMPLE_RATE)
        val detector = YinPitchDetector(ANALYSIS_SAMPLE_RATE)
        val hop = (ANALYSIS_SAMPLE_RATE * HOP_SECONDS).roundToInt()
        val gate = (samples.maxOfOrNull { abs(it) } ?: 0f) * SILENCE_RATIO
        val frameCount =
            if (samples.size < detector.frameSize) 0 else (samples.size - detector.frameSize) / hop + 1

        val frequencies = FloatArray(frameCount) { i ->
            val offset = i * hop
            if (rms(samples, offset, detector.windowSize) < gate) Float.NaN
            else detector.detect(samples, offset)
        }
        return PitchTrack(
            frequencies = frequencies,
            hopSeconds = hop.toFloat() / ANALYSIS_SAMPLE_RATE,
            startSeconds = detector.windowSize / 2f / ANALYSIS_SAMPLE_RATE,
        )
    }

    private fun rms(samples: FloatArray, offset: Int, length: Int): Float {
        var sum = 0f
        for (i in offset until offset + length) sum += samples[i] * samples[i]
        return sqrt(sum / length)
    }
}
