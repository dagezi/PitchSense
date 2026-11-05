package io.github.dagezi.pitchsense.phone

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Kaldi-style log mel filterbank, matching lhotse's `Fbank` with dither 0 and snip_edges false,
 * which is what ZIPA was trained on. Input is 16 kHz audio in [-1, 1].
 */
object Fbank {
    const val SAMPLE_RATE = 16_000
    const val NUM_FILTERS = 80
    const val HOP_SECONDS = 0.01f

    private const val FRAME_LENGTH = 400 // 25 ms
    private const val FRAME_SHIFT = 160 // 10 ms
    private const val FFT_SIZE = 512
    private const val PREEMPHASIS = 0.97f
    private const val LOW_HZ = 20.0
    private const val HIGH_HZ = SAMPLE_RATE / 2 - 400.0

    /** float32 machine epsilon, the floor lhotse applies before the log. */
    private const val ENERGY_FLOOR = 1.1920929e-7f

    /** Hann window raised to 0.85, which Kaldi calls "povey". */
    private val window = FloatArray(FRAME_LENGTH) { i ->
        (0.5 - 0.5 * cos(2 * PI * i / (FRAME_LENGTH - 1))).pow(0.85).toFloat()
    }

    /** Triangular filters over FFT bins, as (first bin, weights) per filter. */
    private val filters: List<Pair<Int, FloatArray>> = run {
        fun mel(hz: Double) = 1127.0 * ln(1 + hz / 700)
        val lowMel = mel(LOW_HZ)
        val delta = (mel(HIGH_HZ) - lowMel) / (NUM_FILTERS + 1)
        val binWidth = SAMPLE_RATE.toDouble() / FFT_SIZE
        List(NUM_FILTERS) { m ->
            val left = lowMel + m * delta
            val center = left + delta
            val right = center + delta
            // The Nyquist bin is never weighted.
            val weights = DoubleArray(FFT_SIZE / 2) { k ->
                val x = mel(k * binWidth)
                max(0.0, min((x - left) / (center - left), (right - x) / (right - center)))
            }
            val first = weights.indexOfFirst { it > 0 }.coerceAtLeast(0)
            val last = weights.indexOfLast { it > 0 }.coerceAtLeast(first)
            first to FloatArray(last - first + 1) { weights[first + it].toFloat() }
        }
    }

    /** Returns one row of [NUM_FILTERS] log energies per 10 ms frame. */
    fun compute(samples: FloatArray): Array<FloatArray> {
        val n = samples.size
        val frameCount = (n + FRAME_SHIFT / 2) / FRAME_SHIFT
        if (n == 0) return emptyArray()
        // Frames are centered on multiples of the shift; the edges are mirrored to fill them.
        val padLeft = (FRAME_LENGTH - FRAME_SHIFT) / 2
        fun sampleAt(padded: Int): Float {
            val i = padded - padLeft
            return when {
                i < 0 -> samples[min(-i - 1, n - 1)]
                i < n -> samples[i]
                else -> samples[max(2 * n - 1 - i, 0)]
            }
        }

        val frame = FloatArray(FRAME_LENGTH)
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val power = FloatArray(FFT_SIZE / 2)
        return Array(frameCount) { f ->
            val start = f * FRAME_SHIFT
            var mean = 0f
            for (i in 0 until FRAME_LENGTH) {
                frame[i] = sampleAt(start + i)
                mean += frame[i]
            }
            mean /= FRAME_LENGTH
            for (i in 0 until FRAME_LENGTH) frame[i] -= mean
            // Pre-emphasis, treating the sample before the frame as a copy of the first one.
            for (i in FRAME_LENGTH - 1 downTo 1) frame[i] -= PREEMPHASIS * frame[i - 1]
            frame[0] -= PREEMPHASIS * frame[0]

            re.fill(0f)
            im.fill(0f)
            for (i in 0 until FRAME_LENGTH) re[i] = frame[i] * window[i]
            fft(re, im)
            for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]

            FloatArray(NUM_FILTERS) { m ->
                val (first, weights) = filters[m]
                var energy = 0f
                for (j in weights.indices) energy += weights[j] * power[first + j]
                ln(max(energy, ENERGY_FLOOR))
            }
        }
    }

    /** In-place iterative radix-2 FFT; the size must be a power of two. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            for (k in 0 until len / 2) {
                val wr = cos(angle * k).toFloat()
                val wi = sin(angle * k).toFloat()
                for (s in 0 until n step len) {
                    val a = s + k
                    val b = a + len / 2
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
            }
            len = len shl 1
        }
    }
}
