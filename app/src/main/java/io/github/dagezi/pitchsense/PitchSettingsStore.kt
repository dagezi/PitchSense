package io.github.dagezi.pitchsense

import android.content.Context
import androidx.core.content.edit
import io.github.dagezi.pitchsense.pitch.PitchSettings

/** Keeps the user's [PitchSettings] across launches; missing values fall back to the defaults. */
class PitchSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("pitch_settings", Context.MODE_PRIVATE)

    fun load(): PitchSettings {
        val defaults = PitchSettings()
        return PitchSettings(
            silenceRatio = prefs.getFloat(SILENCE_RATIO, defaults.silenceRatio),
            quietRatio = prefs.getFloat(QUIET_RATIO, defaults.quietRatio),
            quietMaxCost = prefs.getFloat(QUIET_MAX_COST, defaults.quietMaxCost),
            unvoicedCost = prefs.getFloat(UNVOICED_COST, defaults.unvoicedCost),
            voicingSwitchCost = prefs.getFloat(VOICING_SWITCH_COST, defaults.voicingSwitchCost),
        )
    }

    fun save(settings: PitchSettings) {
        prefs.edit {
            putFloat(SILENCE_RATIO, settings.silenceRatio)
            putFloat(QUIET_RATIO, settings.quietRatio)
            putFloat(QUIET_MAX_COST, settings.quietMaxCost)
            putFloat(UNVOICED_COST, settings.unvoicedCost)
            putFloat(VOICING_SWITCH_COST, settings.voicingSwitchCost)
        }
    }

    private companion object {
        const val SILENCE_RATIO = "silence_ratio"
        const val QUIET_RATIO = "quiet_ratio"
        const val QUIET_MAX_COST = "quiet_max_cost"
        const val UNVOICED_COST = "unvoiced_cost"
        const val VOICING_SWITCH_COST = "voicing_switch_cost"
    }
}
