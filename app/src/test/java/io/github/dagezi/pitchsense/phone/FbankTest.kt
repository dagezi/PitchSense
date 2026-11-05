package io.github.dagezi.pitchsense.phone

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class FbankTest {
    @Test
    fun matchesLhotse() {
        // Not a multiple of the frame shift, so the mirrored right edge is exercised too.
        val samples = FloatArray(4567) { i ->
            val n = i.toDouble()
            (0.5 * sin(2 * PI * 220 * n / 16000) + 0.25 * sin(2 * PI * 1375 * n / 16000 + 0.3) * (i % 997) / 997)
                .toFloat()
        }
        val expected = javaClass.getResource("/fbank_reference.txt")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split(" ").map { it.toFloat() } }

        val actual = Fbank.compute(samples)

        assertEquals(expected.size, actual.size)
        var worst = 0f
        for (f in expected.indices) {
            assertEquals(Fbank.NUM_FILTERS, actual[f].size)
            for (m in 0 until Fbank.NUM_FILTERS) worst = maxOf(worst, abs(expected[f][m] - actual[f][m]))
        }
        // float32 rounding differs from PyTorch's, mostly in the weakest bands.
        assertEquals("largest difference in log energy", 0f, worst, 5e-3f)
    }

    @Test
    fun emptyInputHasNoFrames() {
        assertEquals(0, Fbank.compute(FloatArray(0)).size)
    }
}
