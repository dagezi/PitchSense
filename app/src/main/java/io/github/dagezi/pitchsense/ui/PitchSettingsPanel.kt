package io.github.dagezi.pitchsense.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.dagezi.pitchsense.R
import io.github.dagezi.pitchsense.pitch.PitchSettings

/**
 * Sliders for [PitchSettings]. [onChange] fires when a slider is released, since every change
 * reanalyzes all tracks.
 */
@Composable
fun PitchSettingsPanel(
    settings: PitchSettings,
    onChange: (PitchSettings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.pitch_settings),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onChange(PitchSettings()) }, enabled = settings != PitchSettings()) {
                Text(stringResource(R.string.pitch_settings_reset))
            }
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.pitch_settings_close))
            }
        }
        SettingSlider(R.string.setting_silence_ratio, settings.silenceRatio, 0f..0.1f, percent = true) {
            onChange(settings.copy(silenceRatio = it))
        }
        SettingSlider(R.string.setting_quiet_ratio, settings.quietRatio, 0f..0.1f, percent = true) {
            onChange(settings.copy(quietRatio = it))
        }
        SettingSlider(R.string.setting_quiet_max_cost, settings.quietMaxCost, 0f..0.8f) {
            onChange(settings.copy(quietMaxCost = it))
        }
        SettingSlider(R.string.setting_unvoiced_cost, settings.unvoicedCost, 0f..1f) {
            onChange(settings.copy(unvoicedCost = it))
        }
        SettingSlider(R.string.setting_voicing_switch_cost, settings.voicingSwitchCost, 0f..2f) {
            onChange(settings.copy(voicingSwitchCost = it))
        }
    }
}

@Composable
private fun SettingSlider(
    @StringRes label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    percent: Boolean = false,
    onChange: (Float) -> Unit,
) {
    // Follows the drag locally and reports only the final value.
    var dragged by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
            Text(
                if (percent) "%.1f%%".format(dragged * 100) else "%.2f".format(dragged),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Slider(
            value = dragged,
            onValueChange = { dragged = it },
            onValueChangeFinished = { onChange(dragged) },
            valueRange = range,
        )
    }
}
