package io.github.dagezi.pitchsense.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriterTest {
    private fun write(samples: FloatArray, sampleRate: Int = 44_100): ByteBuffer {
        val out = ByteArrayOutputStream()
        WavWriter.write(DecodedAudio(samples, sampleRate), out)
        return ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun ByteBuffer.tag(offset: Int) = String(ByteArray(4) { get(offset + it) })

    @Test
    fun writesCanonicalPcmHeader() {
        val wav = write(FloatArray(3), sampleRate = 48_000)
        assertEquals(44 + 6, wav.limit())
        assertEquals("RIFF", wav.tag(0))
        assertEquals(wav.limit() - 8, wav.getInt(4))
        assertEquals("WAVE", wav.tag(8))
        assertEquals("fmt ", wav.tag(12))
        assertEquals(1.toShort(), wav.getShort(20)) // PCM
        assertEquals(1.toShort(), wav.getShort(22)) // mono
        assertEquals(48_000, wav.getInt(24))
        assertEquals(96_000, wav.getInt(28))
        assertEquals(16.toShort(), wav.getShort(34))
        assertEquals("data", wav.tag(36))
        assertEquals(6, wav.getInt(40))
    }

    @Test
    fun convertsAndClipsSamples() {
        val wav = write(floatArrayOf(0f, 1f, -1f, 0.5f, 2f, -2f))
        val pcm = ShortArray(6) { wav.getShort(44 + it * 2) }
        assertArrayEquals(shortArrayOf(0, 32767, -32767, 16384, 32767, -32767), pcm)
    }
}
