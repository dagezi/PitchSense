package io.github.dagezi.pitchsense

import android.app.Application
import android.content.Intent
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dagezi.pitchsense.audio.AudioDecoder
import io.github.dagezi.pitchsense.audio.AudioRecorder
import io.github.dagezi.pitchsense.audio.DecodedAudio
import io.github.dagezi.pitchsense.audio.PcmPlayer
import io.github.dagezi.pitchsense.audio.PlaybackCaptureService
import io.github.dagezi.pitchsense.audio.RecordingStore
import io.github.dagezi.pitchsense.phone.Phone
import io.github.dagezi.pitchsense.phone.PhoneRecognizer
import io.github.dagezi.pitchsense.pitch.PitchAnalyzer
import io.github.dagezi.pitchsense.pitch.PitchTrack
import io.github.dagezi.pitchsense.ui.PitchAxis
import io.github.dagezi.pitchsense.ui.TimeRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

const val MAX_TRACKS = 4
private const val MIN_RECORDING_SECONDS = 0.3f
private const val TAG = "MainViewModel"

class RecordingStatus(val elapsedSeconds: Float, val level: Float)

/** Progress of a track's phone transcription, which finishes after its pitch is shown. */
sealed interface PhoneState {
    data object Pending : PhoneState
    data class Done(val phones: List<Phone>) : PhoneState
    data class Failed(val message: String) : PhoneState
}

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
        val phones: PhoneState = PhoneState.Pending,
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

    /**
     * Part of [track] that its Play button plays, in the track's own time: the selection, or the
     * whole track when there is no selection or it misses the track.
     */
    fun playRange(track: TrackState.Ready): TimeRange {
        val from = ((selection?.start ?: Float.NEGATIVE_INFINITY) - track.offsetSeconds).coerceAtLeast(0f)
        val to = ((selection?.end ?: Float.POSITIVE_INFINITY) - track.offsetSeconds)
            .coerceAtMost(track.durationSeconds)
        return if (from < to) TimeRange(from, to) else TimeRange(0f, track.durationSeconds)
    }

    /** Trims the selection to the timeline after it shrank, dropping it if nothing is left. */
    fun withSelectionInTimeline(): MainUiState {
        val selection = selection ?: return this
        val timeline = timeline ?: return copy(selection = null)
        val start = selection.start.coerceAtLeast(timeline.start)
        val end = selection.end.coerceAtMost(timeline.end)
        return copy(selection = if (start < end) TimeRange(start, end) else null)
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
    private var recordingAppAudio = false

    private val recordingStore = RecordingStore(application)

    private val phoneJobs = mutableMapOf<Long, Job>()
    private val phoneRecognizer by lazy { PhoneRecognizer(application) }

    /** One recognition at a time: the model is large and its session is not shared across threads. */
    private val phoneDispatcher = Dispatchers.Default.limitedParallelism(1)

    fun addTrack(uri: Uri) {
        addTracks(listOf(uri))
    }

    /** Adds as many of [uris] as there are free slots; returns how many were added. */
    fun addTracks(uris: List<Uri>): Int {
        var added = 0
        for (uri in uris) {
            if (!_uiState.value.canAddTrack) break
            addTrack(displayName(uri)) { AudioDecoder.decode(getApplication(), uri) }
            added++
        }
        return added
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
            if (loaded is TrackState.Ready) recognizePhones(id, loaded.audio)
        }
    }

    private fun recognizePhones(id: Long, audio: DecodedAudio) {
        phoneJobs[id] = viewModelScope.launch {
            val result = try {
                withContext(phoneDispatcher) {
                    if (!phoneRecognizer.isModelAvailable) {
                        PhoneState.Failed(getApplication<Application>().getString(R.string.phone_model_missing))
                    } else {
                        PhoneState.Done(phoneRecognizer.recognize(audio))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Phone recognition failed", e)
                PhoneState.Failed(e.message ?: e.toString())
            }
            phoneJobs.remove(id)
            replaceTrack(id) { if (it is TrackState.Ready) it.copy(phones = result) else it }
        }
    }

    override fun onCleared() {
        // Close on the recognition dispatcher so a recognition still running finishes first.
        CoroutineScope(phoneDispatcher).launch { phoneRecognizer.close() }
    }

    /** Starts recording from the microphone. The caller must already hold RECORD_AUDIO. */
    fun startRecording() {
        launchRecording("Recording", appAudio = false) { it.record() }
    }

    /**
     * Starts recording what other apps play, using the result of the screen capture consent dialog.
     * The caller must already hold RECORD_AUDIO.
     */
    @RequiresApi(29)
    fun startAppAudioRecording(resultCode: Int, data: Intent) {
        val app = getApplication<Application>()
        launchRecording("App audio", appAudio = true) { recorder ->
            try {
                val projection = PlaybackCaptureService.start(app, resultCode, data)
                // The user can also end the capture from the system UI.
                projection.registerCallback(
                    object : MediaProjection.Callback() {
                        override fun onStop() = recorder.stop()
                    },
                    Handler(Looper.getMainLooper()),
                )
                recorder.recordPlayback(projection)
            } finally {
                PlaybackCaptureService.stop(app)
            }
        }
    }

    private fun launchRecording(
        namePrefix: String,
        appAudio: Boolean,
        record: suspend (AudioRecorder) -> DecodedAudio,
    ) {
        if (!_uiState.value.canAddTrack) return
        stopPlayback()
        val recorder = AudioRecorder().also { recorder = it }
        recordingAppAudio = appAudio
        val startedAt = LocalDateTime.now()
        val name = namePrefix + " " + startedAt.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        _uiState.update { it.copy(recording = true) }

        viewModelScope.launch {
            val audio = try {
                record(recorder)
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
                // Apps that forbid capture yield silence rather than an error.
                check(!appAudio || audio.samples.any { it != 0f }) {
                    getApplication<Application>().getString(R.string.app_audio_silent)
                }
                saveRecording(audio, startedAt)
                audio
            }
        }
    }

    fun stopRecording() {
        recorder?.stop()
    }

    /** Recording app audio has to continue in the background, since the other app is on screen then. */
    fun stopMicrophoneRecording() {
        if (!recordingAppAudio) stopRecording()
    }

    /** Progress of the ongoing recording, or null when not recording. */
    fun recordingStatus(): RecordingStatus? =
        recorder?.let { RecordingStatus(it.elapsedSeconds, it.level) }

    private fun saveRecording(audio: DecodedAudio, startedAt: LocalDateTime) {
        val name = "recording-" + startedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".wav"
        try {
            recordingStore.save(audio, name)
        } catch (e: Exception) {
            // The track is still usable from memory; only reopening it later is affected.
            Log.w(TAG, "Could not save $name", e)
        }
    }

    fun togglePlayback(id: Long) {
        if (_uiState.value.playingTrackId == id) {
            stopPlayback()
            return
        }
        val state = _uiState.value
        val track = state.readyTracks.find { it.id == id } ?: return
        val range = state.playRange(track)

        playJob?.cancel()
        _uiState.update { it.copy(playingTrackId = id) }
        playJob = viewModelScope.launch {
            try {
                player.play(track.audio, range.start, range.end)
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
        _uiState.update { it.withSelectionInTimeline() }
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
            ).withSelectionInTimeline()
        }
    }

    fun setSelection(selection: TimeRange?) {
        _uiState.update { it.copy(selection = selection) }
    }

    fun removeTrack(id: Long) {
        if (_uiState.value.playingTrackId == id) stopPlayback()
        loadJobs.remove(id)?.cancel()
        phoneJobs.remove(id)?.cancel()
        _uiState.update { state -> state.copy(tracks = state.tracks.filterNot { it.id == id }).withSelectionInTimeline() }
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
