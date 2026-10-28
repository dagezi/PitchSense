package io.github.dagezi.pitchsense.pitch

import kotlin.math.ceil
import kotlin.math.floor

/** A possible F0 for one frame. Lower [cost] means a more periodic signal at that frequency. */
data class PitchCandidate(val frequency: Float, val cost: Float)

/**
 * Fundamental frequency estimator based on the YIN algorithm
 * (de Cheveigné & Kawahara, 2002).
 *
 * Instead of committing to one answer per frame, it reports every dip of the cumulative mean
 * normalized difference as a candidate, and [PitchTracker] picks among them using the
 * neighboring frames. That keeps fast glides, whose dips are shallow, and avoids octave jumps.
 */
class YinPitchDetector(
    private val sampleRate: Int,
    // 65 Hz keeps 50/60 Hz mains hum out of range while still covering low male voices.
    minFrequency: Float = 65f,
    maxFrequency: Float = 800f,
    /** Dips at or above this are too aperiodic to be worth considering. */
    private val maxCost: Float = 0.8f,
) {
    private val minLag = floor(sampleRate / maxFrequency).toInt().coerceAtLeast(2)
    private val maxLag = ceil(sampleRate / minFrequency).toInt()

    /** Number of samples integrated by the difference function. */
    val windowSize = maxLag

    /** Number of samples [candidates] reads starting at its offset. */
    val frameSize = windowSize + maxLag + 2

    private val diff = FloatArray(maxLag + 2)

    /** Returns the F0 candidates of the frame starting at [offset]; empty if nothing is periodic. */
    fun candidates(samples: FloatArray, offset: Int): List<PitchCandidate> {
        require(offset >= 0 && offset + frameSize <= samples.size)

        for (tau in 1..maxLag + 1) {
            var sum = 0f
            for (j in 0 until windowSize) {
                val d = samples[offset + j] - samples[offset + j + tau]
                sum += d * d
            }
            diff[tau] = sum
        }

        // Cumulative mean normalized difference.
        diff[0] = 1f
        var runningSum = 0f
        for (tau in 1..maxLag + 1) {
            runningSum += diff[tau]
            diff[tau] = if (runningSum == 0f) 1f else diff[tau] * tau / runningSum
        }

        val result = mutableListOf<PitchCandidate>()
        for (tau in minLag..maxLag) {
            if (diff[tau] < maxCost && diff[tau] <= diff[tau - 1] && diff[tau] < diff[tau + 1]) {
                // A periodic signal dips about as deeply at every multiple of its period, so favor
                // shorter periods slightly, like YIN's choice of the first dip, to avoid octave-down errors.
                result += PitchCandidate(sampleRate / refine(tau), diff[tau] + LAG_BIAS * tau / maxLag)
            }
        }
        return result
    }

    /** Parabolic interpolation around the local minimum at [tau]. */
    private fun refine(tau: Int): Float {
        val s0 = diff[tau - 1]
        val s1 = diff[tau]
        val s2 = diff[tau + 1]
        val denominator = s0 - 2 * s1 + s2
        return if (denominator == 0f) tau.toFloat() else tau + 0.5f * (s0 - s2) / denominator
    }

    private companion object {
        const val LAG_BIAS = 0.1f
    }
}
