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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dagezi.pitchsense.ui.GraphCursor
import io.github.dagezi.pitchsense.ui.GraphTrack
import io.github.dagezi.pitchsense.ui.PitchAxis
import io.github.dagezi.pitchsense.ui.PitchGraph
import io.github.dagezi.pitchsense.ui.TrackRow
import io.github.dagezi.pitchsense.ui.Viewport
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

    val readyTracks = uiState.readyTracks
    val timeline = uiState.timeline
    // Refit whenever tracks are added or removed; offsets changing keep the current view.
    var viewport by remember(readyTracks.map { it.id }) { mutableStateOf(timeline?.let(Viewport::fit)) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = viewModel::alignOnsets, enabled = readyTracks.size >= 2) {
                        Text(stringResource(R.string.align_onsets))
                    }
                    TextButton(
                        onClick = { viewport = timeline?.let(Viewport::fit) },
                        enabled = timeline != null,
                    ) {
                        Text(stringResource(R.string.fit_view))
                    }
                },
            )
        },
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

            val playingTrack = readyTracks.find { it.id == uiState.playingTrackId }
            var cursorSeconds by remember { mutableStateOf<Float?>(null) }
            LaunchedEffect(playingTrack?.id) {
                cursorSeconds = null
                if (playingTrack == null) return@LaunchedEffect
                while (true) {
                    withFrameNanos { }
                    cursorSeconds = viewModel.playbackPositionSeconds()
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (timeline == null) {
                    Text(stringResource(R.string.empty_hint))
                } else {
                    PitchGraph(
                        tracks = readyTracks
                            .filter { it.visible }
                            .map { GraphTrack(it.id, it.pitch, it.offsetSeconds, TrackColors[it.colorIndex]) },
                        viewport = (viewport ?: Viewport.fit(timeline)).clampedTo(timeline),
                        bounds = timeline,
                        axis = uiState.axis,
                        onViewportChange = { viewport = it },
                        onTrackOffsetChange = viewModel::setTrackOffset,
                        onSelectionChange = viewModel::setSelection,
                        modifier = Modifier.fillMaxSize(),
                        selection = uiState.selection,
                        cursor = cursorSeconds?.let { seconds ->
                            playingTrack?.let { GraphCursor(seconds, TrackColors[it.colorIndex]) }
                        },
                    )
                }
            }

            Column {
                for (track in uiState.tracks) {
                    TrackRow(
                        track = track,
                        color = TrackColors[track.colorIndex],
                        playing = track.id == uiState.playingTrackId,
                        onVisibleChange = { viewModel.setTrackVisible(track.id, it) },
                        onTogglePlayback = { viewModel.togglePlayback(track.id) },
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
