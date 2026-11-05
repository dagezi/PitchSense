package io.github.dagezi.pitchsense

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dagezi.pitchsense.audio.RecordingStore
import io.github.dagezi.pitchsense.ui.GraphCursor
import io.github.dagezi.pitchsense.ui.GraphTrack
import io.github.dagezi.pitchsense.ui.PitchAxis
import io.github.dagezi.pitchsense.ui.PitchGraph
import io.github.dagezi.pitchsense.ui.RecordingRow
import io.github.dagezi.pitchsense.ui.StopIcon
import io.github.dagezi.pitchsense.ui.TrackRow
import io.github.dagezi.pitchsense.ui.Viewport
import io.github.dagezi.pitchsense.ui.theme.PitchSenseTheme
import io.github.dagezi.pitchsense.ui.theme.TrackColors

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val openAudio = registerForActivityResult(OpenDocumentInRecordings()) { uri ->
        uri?.let(viewModel::addTrack)
    }
    private val requestMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startRecording() else showMicrophoneDenied()
    }
    private val requestScreenCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (Build.VERSION.SDK_INT >= 29 && result.resultCode == RESULT_OK && data != null) {
            viewModel.startAppAudioRecording(result.resultCode, data)
        }
    }
    private val requestMicrophoneForAppAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchScreenCaptureConsent() else showMicrophoneDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PitchSenseTheme {
                MainScreen(
                    viewModel = viewModel,
                    onAddFromFile = ::addFromFile,
                    onRecord = ::record,
                    onRecordAppAudio = ::recordAppAudio,
                )
            }
        }
        // On recreation (e.g. rotation) the intent was already handled.
        if (savedInstanceState == null) handleIncomingAudio(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingAudio(intent)
    }

    override fun onStop() {
        super.onStop()
        // Only record the microphone while the app is in the foreground.
        viewModel.stopMicrophoneRecording()
    }

    private fun addFromFile() {
        openAudio.launch(arrayOf("audio/*"))
    }

    private fun record() {
        if (hasMicrophonePermission()) viewModel.startRecording() else requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun recordAppAudio() {
        // Android gates playback capture behind the microphone permission too.
        if (hasMicrophonePermission()) launchScreenCaptureConsent() else requestMicrophoneForAppAudio.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun launchScreenCaptureConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        requestScreenCapture.launch(manager.createScreenCaptureIntent())
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun showMicrophoneDenied() {
        Toast.makeText(this, R.string.microphone_permission_denied, Toast.LENGTH_LONG).show()
    }

    /** Adds audio shared to us ("Share" or "Open with") as tracks. */
    private fun handleIncomingAudio(intent: Intent) {
        val uris = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        val added = viewModel.addTracks(uris)
        if (added < uris.size) {
            Toast.makeText(
                this,
                getString(R.string.tracks_not_added, uris.size - added, MAX_TRACKS),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

/** Starts in the recordings folder, since the picker's Audio section only lists music. */
private class OpenDocumentInRecordings : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, RecordingStore.folderDocumentUri())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onAddFromFile: () -> Unit,
    onRecord: () -> Unit,
    onRecordAppAudio: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var recordingStatus by remember { mutableStateOf<RecordingStatus?>(null) }
    LaunchedEffect(uiState.recording) {
        recordingStatus = null
        while (uiState.recording) {
            withFrameNanos { }
            recordingStatus = viewModel.recordingStatus()
        }
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
            if (uiState.recording) {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.stop_recording)) },
                    icon = { Icon(StopIcon, contentDescription = null) },
                    onClick = viewModel::stopRecording,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else if (uiState.canAddTrack) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    ExtendedFloatingActionButton(
                        text = { Text(stringResource(R.string.add_track)) },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        onClick = { menuOpen = true },
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.add_from_file)) },
                            onClick = {
                                menuOpen = false
                                onAddFromFile()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.add_from_mic)) },
                            onClick = {
                                menuOpen = false
                                onRecord()
                            },
                        )
                        // Playback capture needs Android 10.
                        if (Build.VERSION.SDK_INT >= 29) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.add_from_app_audio)) },
                                onClick = {
                                    menuOpen = false
                                    onRecordAppAudio()
                                },
                            )
                        }
                    }
                }
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
                            .map {
                                GraphTrack(
                                    it.id,
                                    it.pitch,
                                    it.offsetSeconds,
                                    TrackColors[it.colorIndex],
                                    phones = (it.phones as? PhoneState.Done)?.phones.orEmpty(),
                                )
                            },
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
                if (uiState.recording) RecordingRow(recordingStatus)
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
