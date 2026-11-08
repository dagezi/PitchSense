package io.github.dagezi.pitchsense.pitch

/** Tunable thresholds of pitch analysis. The defaults are what the tests and tools/pitch_check.py assume. */
data class PitchSettings(
    /** Frames quieter than this fraction of the peak amplitude are treated as unvoiced. */
    val silenceRatio: Float = 0.01f,
    /**
     * Frames quieter than this fraction of the peak keep only candidates cheaper than
     * [quietMaxCost], so soft speech is still tracked while low-level noise and breath are not.
     */
    val quietRatio: Float = 0.03f,
    val quietMaxCost: Float = 0.3f,
    /** Cost of calling a frame unvoiced when it has candidates; candidates cheaper than this win on their own. */
    val unvoicedCost: Float = 0.5f,
    /** Cost of switching between voiced and unvoiced. */
    val voicingSwitchCost: Float = 0.6f,
)
