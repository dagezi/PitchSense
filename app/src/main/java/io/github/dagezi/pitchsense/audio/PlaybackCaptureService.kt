package io.github.dagezi.pitchsense.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.dagezi.pitchsense.MainActivity
import io.github.dagezi.pitchsense.R
import kotlinx.coroutines.CompletableDeferred

/** Foreground service that Android requires to be running while a [MediaProjection] is in use. */
@RequiresApi(29)
class PlaybackCaptureService : Service() {
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val request = pending
        pending = null
        try {
            // The projection can only be obtained once the service is in the foreground.
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            val data = checkNotNull(IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java))
            val projection = checkNotNull(
                getSystemService(MediaProjectionManager::class.java)
                    .getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), data),
            ) { "Screen capture permission was not granted" }
            this.projection = projection
            request?.complete(projection)
        } catch (e: Exception) {
            request?.completeExceptionally(e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        projection?.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.capture_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "playback_capture"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        private var pending: CompletableDeferred<MediaProjection>? = null

        /** Starts the service and returns the projection granted by the screen capture consent result. */
        suspend fun start(context: Context, resultCode: Int, data: Intent): MediaProjection {
            val request = CompletableDeferred<MediaProjection>()
            pending = request
            ContextCompat.startForegroundService(
                context,
                Intent(context, PlaybackCaptureService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data),
            )
            return request.await()
        }

        /** Stops the service, which also ends the projection. */
        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackCaptureService::class.java))
        }
    }
}
