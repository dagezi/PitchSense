package io.github.dagezi.pitchsense.audio

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Writes [DecodedAudio] as a 16-bit mono PCM WAV file. */
object WavWriter {
    private const val HEADER_SIZE = 44

    fun write(audio: DecodedAudio, out: OutputStream) {
        val dataSize = audio.samples.size * Short.SIZE_BYTES
        val buffer = ByteBuffer.allocate(HEADER_SIZE + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(HEADER_SIZE - 8 + dataSize).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16)
            .putShort(1) // PCM
            .putShort(1) // mono
            .putInt(audio.sampleRate)
            .putInt(audio.sampleRate * Short.SIZE_BYTES) // byte rate
            .putShort(Short.SIZE_BYTES.toShort()) // block align
            .putShort(16) // bits per sample
        buffer.put("data".toByteArray()).putInt(dataSize)
        for (sample in audio.samples) {
            buffer.putShort((sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort())
        }
        out.write(buffer.array())
    }
}
