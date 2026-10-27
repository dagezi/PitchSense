package io.github.dagezi.pitchsense.pitch

/** F0 contour sampled at a fixed hop. Unvoiced frames are NaN. */
class PitchTrack(
    val frequencies: FloatArray,
    val hopSeconds: Float,
    /** Time of the first frame; frames are stamped at the center of their integration window. */
    val startSeconds: Float,
) {
    /** Median F0 over voiced frames, or NaN if there are none. Used as the speaker's reference pitch. */
    val medianFrequency: Float = run {
        val voiced = frequencies.filterNot { it.isNaN() }.sorted()
        val mid = voiced.size / 2
        when {
            voiced.isEmpty() -> Float.NaN
            voiced.size % 2 == 1 -> voiced[mid]
            else -> (voiced[mid - 1] + voiced[mid]) / 2
        }
    }

    fun timeOf(index: Int): Float = startSeconds + index * hopSeconds
}
