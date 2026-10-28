package io.github.dagezi.pitchsense.pitch

import kotlin.math.abs
import kotlin.math.ln

/**
 * Chooses one F0 (or unvoiced) per frame from YIN candidates by finding the cheapest path
 * through all frames (Viterbi), similar in spirit to pYIN (Mauch & Dixon, 2014).
 *
 * A frame's own evidence is weighed against continuity: a weak candidate is accepted when it
 * continues the neighboring contour, and a strong one an octave away from it is not.
 */
object PitchTracker {
    /** Cost of calling a frame unvoiced when it has candidates; candidates cheaper than this win on their own. */
    private const val UNVOICED_COST = 0.5f

    /** Cost of switching between voiced and unvoiced. */
    private const val VOICING_SWITCH_COST = 0.6f

    private const val COST_PER_SEMITONE = 0.08f

    /** Pitch can't move further than this between frames; speech glides stay well below it. */
    private const val MAX_STEP_SEMITONES = 6f

    /**
     * Returns F0 in Hz per frame, NaN where unvoiced. A frame with no candidates is always unvoiced.
     */
    fun track(frames: List<List<PitchCandidate>>): FloatArray {
        if (frames.isEmpty()) return FloatArray(0)

        // State 0 is unvoiced; state k > 0 is candidate k - 1.
        var cost = FloatArray(frames[0].size + 1) { s -> if (s == 0) UNVOICED_COST else frames[0][s - 1].cost }
        val back = Array(frames.size) { IntArray(0) }
        for (i in 1 until frames.size) {
            val previous = frames[i - 1]
            val current = frames[i]
            val next = FloatArray(current.size + 1)
            val from = IntArray(current.size + 1)
            for (s in 0..current.size) {
                var best = Float.POSITIVE_INFINITY
                for (p in 0..previous.size) {
                    val total = cost[p] + transitionCost(previous.getOrNull(p - 1), current.getOrNull(s - 1))
                    if (total < best) {
                        best = total
                        from[s] = p
                    }
                }
                next[s] = best + if (s == 0) UNVOICED_COST else current[s - 1].cost
            }
            cost = next
            back[i] = from
        }

        val result = FloatArray(frames.size)
        var state = cost.indices.minBy { cost[it] }
        for (i in frames.indices.reversed()) {
            result[i] = if (state == 0) Float.NaN else frames[i][state - 1].frequency
            if (i > 0) state = back[i][state]
        }
        return result
    }

    private fun transitionCost(from: PitchCandidate?, to: PitchCandidate?): Float = when {
        from == null && to == null -> 0f
        from == null || to == null -> VOICING_SWITCH_COST
        else -> {
            val semitones = abs(12 * ln(to.frequency / from.frequency) / LN_2)
            if (semitones > MAX_STEP_SEMITONES) Float.POSITIVE_INFINITY else COST_PER_SEMITONE * semitones
        }
    }

    private val LN_2 = ln(2f)
}
