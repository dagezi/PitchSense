package io.github.dagezi.pitchsense

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dagezi.pitchsense.audio.AudioDecoder
import io.github.dagezi.pitchsense.audio.AudioRecorder
import io.github.dagezi.pitchsense.audio.DecodedAudio
import io.github.dagezi.pitchsense.audio.PcmPlayer
import io.github.dagezi.pitchsense.audio.WavWriter
import io.github.dagezi.pitchsense.pitch.PitchAnalyzer
import io.github.dagezi.pitchsense.pitch.PitchTrack
import io.github.dagezi.pitchsense.ui.PitchAxis
import io.github.dagezi.pitchsense.ui.TimeRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

const val MAX_TRACKS = 4
private const val MIN_RECORDING_SECONDS = 0.3f
private const val TAG = "MainViewModel"

class RecordingStatus(val elapsedSeconds: Float, val level: Float)

sealed interface TrackState {
    val id: Long
    val name: String

    /** Index into the track palette; stays fixed while the track exists. */
    val colorIndex: Int

    data class Loading(override val id: Long, override val name: String, override val colorIndex: Int) : TrackState

    data class Ready(
        override val id: Long,
        override val name: String,
        override val colorIndex: Int,
        val audio: DecodedAudio,
        val pitch: PitchTrack,
        val visible: Boolean = true,
        /** Where the track's time 0 sits on the shared timeline. */
        val offsetSeconds: Float = 0f,
    ) : TrackState {
        val durationSeconds: Float get() = audio.durationSeconds
    }

    data class Failed(
        override val id: Long,
        override val name: String,
        override val colorIndex: Int,
        val message: String,
    ) : TrackState
}

data class MainUiState(
    val tracks: List<TrackState> = emptyList(),
    val axis: PitchAxis = PitchAxis.Hz,
    val playingTrackId: Long? = null,
    /** Part of the timeline to play, or null to play whole tracks. */
    val selection: TimeRange? = null,
    val recording: Boolean = false,
) {
    /** A recording in progress holds a slot, so nothing else can be added meanwhile. */
    val canAddTrack: Boolean get() = !recording && tracks.size < MAX_TRACKS

    val readyTracks: List<TrackState.Ready> get() = tracks.filterIsInstance<TrackState.Ready>()

    /** Span covering every loaded track at its offset, or null when none is loaded. */
    val timeline: TimeRange?
        get() = readyTracks.takeIf { it.isNotEmpty() }?.let { ready ->
            TimeRange(
                start = minOf(0f, ready.minOf { it.offsetSeconds }),
                end = ready.maxOf { it.offsetSeconds + it.durationSeconds },
            )
        }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val loadJobs = mutableMapOf<Long, Job>()
    private var nextId = 0L

    private val player = PcmPlayer()
    private var playJob: Job? = null

    private var recorder: AudioRecorder? = null

    fun addTrack(uri: Uri) {
        if (!_uiState.value.canAddTrack) return
        addTrack(displayName(uri)) { AudioDecoder.decode(getApplication(), uri) }
    }

    /** Adds a track named [name] whose audio comes from [load], which runs off the main thread. */
    private fun addTrack(name: String, load: suspend () -> DecodedAudio) {
        val tracks = _uiState.value.tracks
        if (tracks.size >= MAX_TRACKS) return
        val id = nextId++
        val colorIndex = (0 until MAX_TRACKS).first { i -> tracks.none { it.colorIndex == i } }
        _uiState.update { it.copy(tracks = it.tracks + TrackState.Loading(id, name, colorIndex)) }

        loadJobs[id] = viewModelScope.launch {
            val loaded = try {
                withContext(Dispatchers.Default) {
                    val audio = load()
                    TrackState.Ready(id, name, colorIndex, audio, PitchAnalyzer.analyze(audio))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackState.Failed(id, name, colorIndex, e.message ?: e.toString())
            }
            loadJobs.remove(id)
            replaceTrack(id) { loaded }
        }
    }

    /** Starts recording from the microphone. The caller must already hold RECORD_AUDIO. */
    fun startRecording() {
        if (!_uiState.value.canAddTrack) return
        stopPlayback()
        val recorder = AudioRecorder().also { recorder = it }
        val startedAt = LocalDateTime.now()
        val name = "Recording " + startedAt.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        _uiState.update { it.copy(recording = true) }

        viewModelScope.launch {
            val audio = try {
                recorder.record()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Surface the failure as a failed track so the user sees what went wrong.
                addTrack(name) { throw e }
                null
            } finally {
                this@MainViewModel.recorder = null
                _uiState.update { it.copy(recording = false) }
            }
            // Ignore accidental taps on Record.
            if (audio == null || audio.durationSeconds < MIN_RECORDING_SECONDS) return@launch
            addTrack(name) {
                saveRecording(audio, startedAt)
                audio
            }
        }
    }

    fun stopRecording() {
        recorder?.stop()
    }

    /** Progress of the ongoing recording, or null when not recording. */
    fun recordingStatus(): RecordingStatus? =
        recorder?.let { RecordingStatus(it.elapsedSeconds, it.level) }

    private fun saveRecording(audio: DecodedAudio, startedAt: LocalDateTime) {
        val dir = File(getApplication<Application>().filesDir, "recordings")
        val file = File(dir, "recording-" + startedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".wav")
        try {
            dir.mkdirs()
            file.outputStream().buffered().use { WavWriter.write(audio, it) }
        } catch (e: IOException) {
            // The track is still usable from memory; only reopening it later is affected.
            Log.w(TAG, "Could not save $file", e)
        }
    }

    fun togglePlayback(id: Long) {
        if (_uiState.value.playingTrackId == id) {
            stopPlayback()
            return
        }
        val state = _uiState.value
        val track = state.readyTracks.find { it.id == id } ?: return
        // Convert the timeline selection into the track's own time.
        val from = ((state.selection?.start ?: Float.NEGATIVE_INFINITY) - track.offsetSeconds)
            .coerceAtLeast(0f)
        val to = ((state.selection?.end ?: Float.POSITIVE_INFINITY) - track.offsetSeconds)
            .coerceAtMost(track.durationSeconds)
        if (from >= to) return

        playJob?.cancel()
        _uiState.update { it.copy(playingTrackId = id) }
        playJob = viewModelScope.launch {
            try {
                player.play(track.audio, from, to)
            } finally {
                _uiState.update { if (it.playingTrackId == id) it.copy(playingTrackId = null) else it }
            }
        }
    }

    fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        _uiState.update { it.copy(playingTrackId = null) }
    }

    /** Playback position on the shared timeline, or null when nothing is playing. */
    fun playbackPositionSeconds(): Float? {
        val state = _uiState.value
        val track = state.readyTracks.find { it.id == state.playingTrackId } ?: return null
        return player.positionSeconds?.plus(track.offsetSeconds)
    }

    fun setTrackOffset(id: Long, offsetSeconds: Float) {
        replaceTrack(id) { if (it is TrackState.Ready) it.copy(offsetSeconds = offsetSeconds) else it }
    }

    /** Shifts tracks so that speech starts at the same moment in all of them. */
    fun alignOnsets() {
        _uiState.update { state ->
            val onsets = state.readyTracks.mapNotNull { track -> track.pitch.onsetSeconds()?.let { track.id to it } }
                .toMap()
            if (onsets.isEmpty()) return@update state
            // Align to the latest onset so no track needs to move before 0.
            val target = onsets.values.max()
            state.copy(
                tracks = state.tracks.map { track ->
                    val onset = onsets[track.id]
                    if (track is TrackState.Ready && onset != null) track.copy(offsetSeconds = target - onset) else track
                },
            )
        }
    }

    fun setSelection(selection: TimeRange?) {
        _uiState.update { it.copy(selection = selection) }
    }

    fun removeTrack(id: Long) {
        if (_uiState.value.playingTrackId == id) stopPlayback()
        loadJobs.remove(id)?.cancel()
        _uiState.update { state -> state.copy(tracks = state.tracks.filterNot { it.id == id }) }
    }

    fun setTrackVisible(id: Long, visible: Boolean) {
        replaceTrack(id) { if (it is TrackState.Ready) it.copy(visible = visible) else it }
    }

    fun setAxis(axis: PitchAxis) {
        _uiState.update { it.copy(axis = axis) }
    }

    private fun replaceTrack(id: Long, transform: (TrackState) -> TrackState) {
        _uiState.update { state ->
            state.copy(tracks = state.tracks.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun displayName(uri: Uri): String {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.let { return it }
            }
        return uri.lastPathSegment ?: uri.toString()
    }
}
