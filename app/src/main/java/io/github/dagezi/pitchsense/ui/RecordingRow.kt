package io.github.dagezi.pitchsense.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dagezi.pitchsense.R
import io.github.dagezi.pitchsense.RecordingStatus

/** Placeholder row for the track being recorded, with elapsed time and an input level meter. */
@Composable
fun RecordingRow(status: RecordingStatus?, modifier: Modifier = Modifier) {
    val seconds = status?.elapsedSeconds?.toInt() ?: 0
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(RecordIcon, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.recording_elapsed, seconds / 60, seconds % 60))
            LinearProgressIndicator(
                progress = { status?.level ?: 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
