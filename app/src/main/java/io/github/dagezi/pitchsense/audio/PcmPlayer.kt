package io.github.dagezi.pitchsense.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Streams [DecodedAudio] to an [AudioTrack]. Playing the same samples that were analyzed keeps the
 * playback cursor exactly aligned with the pitch contour.
 */
class PcmPlayer {
    private class Session(val track: AudioTrack, val startFrame: Int, val sampleRate: Int)

    @Volatile
    private var session: Session? = null

    /** Current playback position in seconds, or null when nothing is playing. */
    val positionSeconds: Float?
        get() = session?.let { (it.startFrame + it.track.playbackHeadPosition).toFloat() / it.sampleRate }

    /** Plays [audio] between the given times. Returns when playback finishes; cancel to stop. */
    suspend fun play(
        audio: DecodedAudio,
        fromSeconds: Float = 0f,
        toSeconds: Float = audio.durationSeconds,
    ) = withContext(Dispatchers.IO) {
        val startFrame = (fromSeconds * audio.sampleRate).toInt().coerceIn(0, audio.samples.size)
        val endFrame = (toSeconds * audio.sampleRate).toInt().coerceIn(startFrame, audio.samples.size)
        val track = createTrack(audio.sampleRate)
        session = Session(track, startFrame, audio.sampleRate)
        try {
            track.play()
            var offset = startFrame
            while (offset < endFrame) {
                ensureActive()
                val count = min(CHUNK_FRAMES, endFrame - offset)
                val written = track.write(audio.samples, offset, count, AudioTrack.WRITE_BLOCKING)
                check(written >= 0) { "AudioTrack.write failed: $written" }
                offset += written
            }
            // Wait for buffered data to play out before stopping: in streaming mode the head
            // position resets to 0 once a stopped track drains, so it can't be polled after stop().
            val totalFrames = offset - startFrame
            var lastHead = -1
            var stalledMillis = 0L
            while (true) {
                val head = track.playbackHeadPosition
                if (head >= totalFrames) break
                stalledMillis = if (head == lastHead) stalledMillis + POLL_MILLIS else 0
                if (stalledMillis >= STALL_TIMEOUT_MILLIS) break
                lastHead = head
                delay(POLL_MILLIS)
            }
            track.stop()
        } finally {
            // A newer play() may already have replaced the session.
            if (session?.track === track) session = null
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
        }
    }

    private fun createTrack(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_FRAMES * 2 * Float.SIZE_BYTES))
            .build()
    }

    private companion object {
        const val CHUNK_FRAMES = 2048
        const val POLL_MILLIS = 20L

        /** Give up waiting for the drain if the head stops advancing (e.g. output device lost). */
        const val STALL_TIMEOUT_MILLIS = 500L
    }
}
