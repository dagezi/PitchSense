package io.github.dagezi.pitchsense

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dagezi.pitchsense.ui.GraphTrack
import io.github.dagezi.pitchsense.ui.PitchAxis
import io.github.dagezi.pitchsense.ui.PitchGraph
import io.github.dagezi.pitchsense.ui.TrackRow
import io.github.dagezi.pitchsense.ui.theme.PitchSenseTheme
import io.github.dagezi.pitchsense.ui.theme.TrackColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PitchSenseTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val openAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::addTrack)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        floatingActionButton = {
            if (uiState.canAddTrack) {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.add_track)) },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    onClick = { openAudio.launch(arrayOf("audio/*")) },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AxisSelector(uiState.axis, viewModel::setAxis)

            val readyTracks = uiState.tracks.filterIsInstance<TrackState.Ready>()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (readyTracks.isEmpty()) {
                    Text(stringResource(R.string.empty_hint))
                } else {
                    PitchGraph(
                        tracks = readyTracks
                            .filter { it.visible }
                            .map { GraphTrack(it.pitch, TrackColors[it.colorIndex]) },
                        durationSeconds = readyTracks.maxOf { it.durationSeconds },
                        axis = uiState.axis,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Column {
                for (track in uiState.tracks) {
                    TrackRow(
                        track = track,
                        color = TrackColors[track.colorIndex],
                        onVisibleChange = { viewModel.setTrackVisible(track.id, it) },
                        onRemove = { viewModel.removeTrack(track.id) },
                    )
                }
            }
            // Keep the last row clear of the floating action button.
            Spacer(Modifier.height(72.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AxisSelector(selected: PitchAxis, onSelect: (PitchAxis) -> Unit) {
    val axes = PitchAxis.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        axes.forEachIndexed { index, axis ->
            SegmentedButton(
                selected = axis == selected,
                onClick = { onSelect(axis) },
                shape = SegmentedButtonDefaults.itemShape(index, axes.size),
            ) {
                Text(
                    stringResource(
                        when (axis) {
                            PitchAxis.Hz -> R.string.axis_hz
                            PitchAxis.Semitones -> R.string.axis_semitones
                        },
                    ),
                )
            }
        }
    }
}
