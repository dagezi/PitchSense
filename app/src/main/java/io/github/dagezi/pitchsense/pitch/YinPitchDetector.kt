package io.github.dagezi.pitchsense.pitch

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Fundamental frequency estimator based on the YIN algorithm
 * (de Cheveigné & Kawahara, 2002).
 */
class YinPitchDetector(
    private val sampleRate: Int,
    minFrequency: Float = 50f,
    maxFrequency: Float = 800f,
    private val threshold: Float = 0.15f,
) {
    private val minLag = floor(sampleRate / maxFrequency).toInt().coerceAtLeast(2)
    private val maxLag = ceil(sampleRate / minFrequency).toInt()

    /** Number of samples integrated by the difference function. */
    val windowSize = maxLag

    /** Number of samples [detect] reads starting at its offset. */
    val frameSize = windowSize + maxLag + 2

    private val diff = FloatArray(maxLag + 2)

    /** Returns F0 in Hz of the frame starting at [offset], or NaN if no periodicity is found. */
    fun detect(samples: FloatArray, offset: Int): Float {
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

        var tau = minLag
        while (tau <= maxLag) {
            if (diff[tau] < threshold) {
                while (tau + 1 <= maxLag && diff[tau + 1] < diff[tau]) tau++
                return sampleRate / refine(tau)
            }
            tau++
        }
        return Float.NaN
    }

    /** Parabolic interpolation around the local minimum at [tau]. */
    private fun refine(tau: Int): Float {
        val s0 = diff[tau - 1]
        val s1 = diff[tau]
        val s2 = diff[tau + 1]
        val denominator = s0 - 2 * s1 + s2
        return if (denominator == 0f) tau.toFloat() else tau + 0.5f * (s0 - s2) / denominator
    }
}
