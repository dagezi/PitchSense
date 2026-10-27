package io.github.dagezi.pitchsense.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.log10

/** Records mono audio from the microphone until [stop] is called. */
class AudioRecorder(private val sampleRate: Int = SAMPLE_RATE) {
    @Volatile
    private var stopRequested = false

    @Volatile
    var elapsedSeconds: Float = 0f
        private set

    /** Peak level of the most recent buffer, mapped from [-60 dB, 0 dB] to [0, 1]. */
    @Volatile
    var level: Float = 0f
        private set

    /** Records until [stop] is called and returns the captured audio. The caller must hold RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    suspend fun record(): DecodedAudio = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "Recording at $sampleRate Hz is not supported" }
        val record = AudioRecord(
            // Less processing (AGC, noise suppression) than MIC, which keeps the pitch contour honest.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, CHUNK_FRAMES * 2 * Short.SIZE_BYTES),
        )
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Could not open the microphone" }
            record.startRecording()
            val chunk = ShortArray(CHUNK_FRAMES)
            var samples = FloatArray(sampleRate * 10)
            var size = 0
            while (!stopRequested) {
                ensureActive()
                val read = record.read(chunk, 0, chunk.size)
                check(read >= 0) { "AudioRecord.read failed: $read" }
                if (size + read > samples.size) samples = samples.copyOf(maxOf(samples.size * 2, size + read))
                var peak = 0f
                for (i in 0 until read) {
                    val value = chunk[i] / 32768f
                    samples[size + i] = value
                    peak = maxOf(peak, abs(value))
                }
                size += read
                elapsedSeconds = size.toFloat() / sampleRate
                level = if (peak <= 0f) 0f else ((20 * log10(peak) + 60) / 60).coerceIn(0f, 1f)
            }
            DecodedAudio(samples.copyOf(size), sampleRate)
        } finally {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            record.release()
        }
    }

    fun stop() {
        stopRequested = true
    }

    private companion object {
        /** 44.1 kHz is the only rate every Android device must support. */
        const val SAMPLE_RATE = 44_100
        const val CHUNK_FRAMES = 1024
    }
}
