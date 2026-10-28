package io.github.dagezi.pitchsense.pitch

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Second-order Butterworth high-pass (RBJ biquad). */
object HighPassFilter {
    fun apply(samples: FloatArray, sampleRate: Int, cutoffHz: Float): FloatArray {
        val w0 = 2 * PI * cutoffHz / sampleRate
        val alpha = sin(w0) / (2 * Q)
        val c = cos(w0)
        val a0 = 1 + alpha
        val b0 = ((1 + c) / 2 / a0).toFloat()
        val b1 = (-(1 + c) / a0).toFloat()
        val a1 = (-2 * c / a0).toFloat()
        val a2 = ((1 - alpha) / a0).toFloat()

        val out = FloatArray(samples.size)
        var x1 = 0f
        var x2 = 0f
        var y1 = 0f
        var y2 = 0f
        for (i in samples.indices) {
            val x = samples[i]
            // b2 equals b0 for a high-pass.
            val y = b0 * x + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            out[i] = y
        }
        return out
    }

    private const val Q = 0.7071
}
