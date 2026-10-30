package app.kairn.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.kairn.R

/**
 * A media-playback foreground service: it stops Android reclaiming the process while Kairn reads aloud with the
 * screen off. It owns no audio - the reader keeps reading - and only the notification's Stop action, via [onStop],
 * reaches back into it.
 */
class SpeechService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onStop?.invoke()            // the reader stops its own engine; this only ends the service
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.s_reading_aloud), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false); description = getString(R.string.s_reading_aloud_hint) }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, ReaderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SpeechService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cairn)
            .setContentTitle(getString(R.string.s_reading_aloud))
            .setContentText(getString(R.string.s_reading_aloud_hint))
            .setContentIntent(open)
            .addAction(0, getString(R.string.s_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIF_ID, n)
    }

    override fun onDestroy() {
        onStop = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "reading_aloud"
        private const val NOTIF_ID = 7
        private const val ACTION_STOP = "app.kairn.STOP_SPEECH"

        /** Set by the reader while it is alive; the notification's Stop action calls it. */
        @Volatile
        var onStop: (() -> Unit)? = null

        /** Idempotent: starting an already-running foreground service only re-posts the notification. */
        fun start(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, SpeechService::class.java))
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, SpeechService::class.java)) }
        }
    }
}
