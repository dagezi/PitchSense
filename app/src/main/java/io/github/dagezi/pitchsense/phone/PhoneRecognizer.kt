package io.github.dagezi.pitchsense.phone

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import io.github.dagezi.pitchsense.audio.DecodedAudio
import io.github.dagezi.pitchsense.pitch.Resampler
import java.io.File
import java.io.FileNotFoundException
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Transcribes speech into IPA phones with ZIPA (https://github.com/lingjzhu/zipa, CC BY 4.0),
 * a language-independent phone recognizer. The model is too large to bundle, so it is read from
 * the app's external files directory; see [modelFile].
 */
class PhoneRecognizer(context: Context) : AutoCloseable {
    private val modelFile = modelFile(context)
    private val vocabulary: List<String> = context.assets.open(TOKENS_ASSET).bufferedReader().useLines { lines ->
        // Each line is "<token> <id>"; ids are consecutive from 0.
        lines.filter { it.isNotBlank() }.map { it.substringBeforeLast(' ') }.toList()
    }

    private val environment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    val isModelAvailable: Boolean get() = modelFile.isFile

    /** Blocks for as long as the model takes, which is seconds for long audio. Not thread-safe. */
    fun recognize(audio: DecodedAudio): List<Phone> {
        val samples = Resampler.resample(audio.samples, audio.sampleRate, Fbank.SAMPLE_RATE)
        val features = Fbank.compute(samples)
        if (features.isEmpty()) return emptyList()

        val flat = FloatArray(features.size * Fbank.NUM_FILTERS)
        features.forEachIndexed { i, row -> row.copyInto(flat, i * Fbank.NUM_FILTERS) }
        val shape = longArrayOf(1, features.size.toLong(), Fbank.NUM_FILTERS.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(flat), shape).use { x ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(features.size.toLong())), longArrayOf(1))
                .use { xLens ->
                    openSession().run(mapOf("x" to x, "x_lens" to xLens)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val logProbs = (result.get(0).value as Array<Array<FloatArray>>)[0]
                        if (logProbs.isEmpty()) return emptyList()
                        // The encoder subsamples the 10 ms feature frames; spread its frames over the same span.
                        val secondsPerFrame = features.size * Fbank.HOP_SECONDS / logProbs.size
                        return CtcDecoder.decode(logProbs, vocabulary, secondsPerFrame)
                    }
                }
        }
    }

    private fun openSession(): OrtSession = session ?: run {
        if (!modelFile.isFile) throw FileNotFoundException("Phone model not found at $modelFile")
        environment.createSession(modelFile.path, OrtSession.SessionOptions()).also { session = it }
    }

    override fun close() {
        session?.close()
        session = null
    }

    companion object {
        private const val MODEL_NAME = "zipa-large-crctc-500k.int8.onnx"
        private const val TOKENS_ASSET = "zipa_tokens.txt"

        /**
         * Where the model is expected. During development, push it with
         * `adb push model.int8.onnx /sdcard/Android/data/<package>/files/zipa-large-crctc-500k.int8.onnx`.
         */
        fun modelFile(context: Context): File = File(context.getExternalFilesDir(null), MODEL_NAME)
    }
}
