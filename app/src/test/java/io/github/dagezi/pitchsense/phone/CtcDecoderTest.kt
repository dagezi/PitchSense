package io.github.dagezi.pitchsense.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class CtcDecoderTest {
    private val vocabulary = listOf("<blk>", "<sos/eos>", "<unk>", "▁", "a", "t", "ʰ", "ː", "̃")

    /** One frame per token, each a one-hot row picking that token. */
    private fun frames(vararg tokens: String) = Array(tokens.size) { t ->
        FloatArray(vocabulary.size) { if (vocabulary[it] == tokens[t]) 0f else -10f }
    }

    private fun decode(vararg tokens: String) = CtcDecoder.decode(frames(*tokens), vocabulary, secondsPerFrame = 0.02f)

    @Test
    fun collapsesRepeatsAndDropsBlanks() {
        assertEquals(listOf("t", "a"), decode("<blk>", "t", "t", "<blk>", "a").map { it.symbol })
    }

    @Test
    fun blankSeparatesRepeatedPhones() {
        assertEquals(listOf("a", "a"), decode("a", "<blk>", "a").map { it.symbol })
    }

    @Test
    fun phoneSpansTheFramesItWasEmittedOn() {
        val phone = decode("<blk>", "a", "a", "<blk>").single()
        assertEquals(0.02f, phone.startSeconds, 1e-6f)
        assertEquals(0.06f, phone.endSeconds, 1e-6f)
    }

    @Test
    fun mergesModifiersIntoPrecedingPhone() {
        val phones = decode("t", "ʰ", "a", "̃", "ː", "ː")
        assertEquals(listOf("tʰ", "ãː"), phones.map { it.symbol })
        assertEquals(0.12f, phones[1].endSeconds, 1e-6f)
    }

    @Test
    fun dropsWordBoundaryWithoutExtendingPreviousPhone() {
        val phone = decode("a", "▁", "▁", "<blk>").single()
        assertEquals("a", phone.symbol)
        assertEquals(0.02f, phone.endSeconds, 1e-6f)
    }

    @Test
    fun leadingModifierStandsAlone() {
        assertEquals(listOf("ː", "a"), decode("ː", "a").map { it.symbol })
    }
}
