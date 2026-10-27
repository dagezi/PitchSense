package io.github.dagezi.pitchsense.audio

/** Mono PCM audio with samples in the range [-1, 1]. */
class DecodedAudio(val samples: FloatArray, val sampleRate: Int) {
    val durationSeconds: Float get() = samples.size.toFloat() / sampleRate
}
