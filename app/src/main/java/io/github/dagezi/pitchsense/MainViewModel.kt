package io.github.dagezi.pitchsense

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dagezi.pitchsense.audio.AudioDecoder
import io.github.dagezi.pitchsense.pitch.PitchAnalyzer
import io.github.dagezi.pitchsense.pitch.PitchTrack
import io.github.dagezi.pitchsense.ui.PitchAxis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val MAX_TRACKS = 4

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
        val pitch: PitchTrack,
        val durationSeconds: Float,
        val visible: Boolean = true,
    ) : TrackState

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
) {
    val canAddTrack: Boolean get() = tracks.size < MAX_TRACKS
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val loadJobs = mutableMapOf<Long, Job>()
    private var nextId = 0L

    fun addTrack(uri: Uri) {
        val tracks = _uiState.value.tracks
        if (tracks.size >= MAX_TRACKS) return
        val id = nextId++
        val name = displayName(uri)
        val colorIndex = (0 until MAX_TRACKS).first { i -> tracks.none { it.colorIndex == i } }
        _uiState.update { it.copy(tracks = it.tracks + TrackState.Loading(id, name, colorIndex)) }

        loadJobs[id] = viewModelScope.launch {
            val loaded = try {
                withContext(Dispatchers.Default) {
                    val audio = AudioDecoder.decode(getApplication(), uri)
                    TrackState.Ready(id, name, colorIndex, PitchAnalyzer.analyze(audio), audio.durationSeconds)
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

    fun removeTrack(id: Long) {
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
