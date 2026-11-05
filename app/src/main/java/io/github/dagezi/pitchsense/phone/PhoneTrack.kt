package io.github.dagezi.pitchsense.phone

/** A recognized phone in IPA, with diacritics and length marks attached. Times are in the track's own time. */
data class Phone(val symbol: String, val startSeconds: Float, val endSeconds: Float)

object CtcDecoder {
    private const val BLANK = 0

    /**
     * Greedy CTC decoding of per-frame log probabilities ([frames] x vocabulary) into phones.
     * Each phone spans the frames where it was the top token. Special tokens such as the word
     * boundary are dropped, and modifiers (ʰ, ː, combining marks, ...) are merged into the preceding phone.
     */
    fun decode(logProbs: Array<FloatArray>, vocabulary: List<String>, secondsPerFrame: Float): List<Phone> {
        val phones = mutableListOf<Phone>()
        var previous = BLANK
        // Whether the previous frame's token went into the last phone, so a repeat of it extends that phone.
        var extending = false
        for ((t, row) in logProbs.withIndex()) {
            var best = 0
            for (i in 1 until row.size) if (row[i] > row[best]) best = i
            val end = (t + 1) * secondsPerFrame
            if (best == previous) {
                if (extending) phones[phones.lastIndex] = phones.last().copy(endSeconds = end)
                continue
            }
            previous = best
            val symbol = vocabulary.getOrNull(best)
            if (best == BLANK || symbol == null || !isPhone(symbol)) {
                extending = false
                continue
            }
            extending = true
            val last = phones.lastOrNull()
            if (last != null && isModifier(symbol)) {
                phones[phones.lastIndex] = last.copy(symbol = last.symbol + symbol, endSeconds = end)
            } else {
                phones += Phone(symbol, t * secondsPerFrame, end)
            }
        }
        return phones
    }

    /** Excludes `<blk>`-style special tokens and the "▁" word boundary. */
    private fun isPhone(symbol: String) = !symbol.startsWith("<") && symbol != "▁"

    private fun isModifier(symbol: String): Boolean = when (Character.getType(symbol.codePointAt(0))) {
        Character.NON_SPACING_MARK.toInt(), Character.MODIFIER_LETTER.toInt(), Character.MODIFIER_SYMBOL.toInt() -> true
        else -> false
    }
}
