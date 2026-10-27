package io.github.dagezi.pitchsense.pitch

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Resampler {
    private const val HALF_TAPS = 31

    /**
     * Resamples [input] from [fromRate] to [toRate] using linear interpolation.
     * When downsampling, a windowed-sinc low-pass filter is applied first to avoid aliasing.
     */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input.copyOf()
        val ratio = fromRate.toDouble() / toRate
        val source = if (ratio > 1) lowPass(input, cutoff = 0.45 / ratio) else input
        val outSize = (input.size / ratio).toInt()
        return FloatArray(outSize) { i ->
            val pos = i * ratio
            val index = pos.toInt()
            val frac = (pos - index).toFloat()
            val a = source[index]
            val b = if (index + 1 < source.size) source[index + 1] else a
            a + (b - a) * frac
        }
    }

    /** [cutoff] is in cycles per sample (0 < cutoff < 0.5). */
    private fun lowPass(input: FloatArray, cutoff: Double): FloatArray {
        val kernel = FloatArray(2 * HALF_TAPS + 1) { i ->
            val n = i - HALF_TAPS
            val sinc = if (n == 0) 2 * cutoff else sin(2 * PI * cutoff * n) / (PI * n)
            val hamming = 0.54 - 0.46 * cos(2 * PI * i / (2 * HALF_TAPS))
            (sinc * hamming).toFloat()
        }
        val sum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= sum

        return FloatArray(input.size) { i ->
            var acc = 0f
            for (k in kernel.indices) {
                val j = i + k - HALF_TAPS
                if (j in input.indices) acc += input[j] * kernel[k]
            }
            acc
        }
    }
}
