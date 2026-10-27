package io.github.dagezi.pitchsense

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dagezi.pitchsense.audio.AudioDecoder
import io.github.dagezi.pitchsense.pitch.PitchAnalyzer
import io.github.dagezi.pitchsense.pitch.PitchTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface MainUiState {
    data object Empty : MainUiState
    data class Loading(val name: String) : MainUiState
    data class Loaded(val name: String, val track: PitchTrack, val durationSeconds: Float) : MainUiState
    data class Failed(val name: String, val message: String) : MainUiState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Empty)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    fun open(uri: Uri) {
        loadJob?.cancel()
        val name = displayName(uri)
        _uiState.value = MainUiState.Loading(name)
        loadJob = viewModelScope.launch {
            _uiState.value = try {
                withContext(Dispatchers.Default) {
                    val audio = AudioDecoder.decode(getApplication(), uri)
                    MainUiState.Loaded(name, PitchAnalyzer.analyze(audio), audio.durationSeconds)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MainUiState.Failed(name, e.message ?: e.toString())
            }
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
