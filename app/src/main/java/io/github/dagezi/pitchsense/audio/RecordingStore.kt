package io.github.dagezi.pitchsense.audio

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/**
 * Saves recordings where the user can see and delete them with any file manager:
 * Recordings/PitchSense in shared storage (Music/PitchSense before Android 12).
 *
 * Writing our own files through MediaStore needs no storage permission. Before Android 10 there is
 * no such route, so recordings go to the app's external files directory, which file managers can
 * still browse on those versions.
 */
class RecordingStore(private val context: Context) {
    /** Saves [audio] as a WAV file named [displayName]. */
    fun save(audio: DecodedAudio, displayName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), FOLDER)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create $dir")
            File(dir, displayName).outputStream().buffered().use { WavWriter.write(audio, it) }
            return
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val parent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Environment.DIRECTORY_RECORDINGS
            else Environment.DIRECTORY_MUSIC
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "$parent/$FOLDER")
            // Hidden from other apps until the file is complete.
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create $displayName")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open $uri")
            out.buffered().use { WavWriter.write(audio, it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private companion object {
        const val FOLDER = "PitchSense"
    }
}
