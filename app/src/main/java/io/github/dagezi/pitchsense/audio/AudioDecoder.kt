package io.github.dagezi.pitchsense.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes any audio format supported by the platform into mono float PCM. */
object AudioDecoder {
    private const val TIMEOUT_US = 10_000L

    fun decode(context: Context, uri: Uri): DecodedAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track found")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return drain(extractor, codec, format)
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drain(extractor: MediaExtractor, codec: MediaCodec, inputFormat: MediaFormat): DecodedAudio {
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val output = FloatArrayBuilder()
        val info = MediaCodec.BufferInfo()
        var inputDone = false

        while (true) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = codec.outputFormat
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                // Decoders output 16-bit PCM unless they say otherwise.
                val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
                floatPcm = when (encoding) {
                    AudioFormat.ENCODING_PCM_16BIT -> false
                    AudioFormat.ENCODING_PCM_FLOAT -> true
                    else -> throw IOException("Unsupported PCM encoding from decoder: $encoding")
                }
            } else if (outIndex >= 0) {
                val buffer = codec.getOutputBuffer(outIndex)!!
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                appendMono(buffer.order(ByteOrder.nativeOrder()), channels, floatPcm, output)
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        return DecodedAudio(output.toArray(), sampleRate)
    }

    /** Downmixes interleaved PCM to mono by averaging channels. */
    private fun appendMono(buffer: ByteBuffer, channels: Int, floatPcm: Boolean, output: FloatArrayBuilder) {
        if (floatPcm) {
            val samples = buffer.asFloatBuffer()
            repeat(samples.remaining() / channels) {
                var sum = 0f
                repeat(channels) { sum += samples.get() }
                output.add(sum / channels)
            }
        } else {
            val samples = buffer.asShortBuffer()
            val scale = 1f / (32768f * channels)
            repeat(samples.remaining() / channels) {
                var sum = 0f
                repeat(channels) { sum += samples.get() }
                output.add(sum * scale)
            }
        }
    }

    private class FloatArrayBuilder {
        private var data = FloatArray(1 shl 16)
        private var size = 0

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
