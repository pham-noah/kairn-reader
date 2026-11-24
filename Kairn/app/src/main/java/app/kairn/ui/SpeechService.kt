package app.kairn.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.content.ContextCompat
import app.kairn.R

/**
 * A media-playback foreground service: it stops Android reclaiming the process while Kairn reads aloud with the
 * screen off and the app in the background. It owns no audio - the reader keeps reading - and reaches back into
 * it for the five things a reader does without looking at the phone: pause, resume, the chapter before, the
 * chapter after, stop.
 *
 * Those arrive by two roads, and only one of them needs the screen looked at: the notification actions, and a
 * [MediaSession] so the lock screen, a Bluetooth headset button and a car head unit work. The session is the
 * framework one, not the support shim, because nothing here needs a compatibility layer and minSdk is 26.
 */
class SpeechService : Service() {

    private var session: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "Kairn").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { SpeechService.onToggle?.invoke() }
                override fun onPause() { SpeechService.onToggle?.invoke() }
                override fun onStop() { SpeechService.onStop?.invoke() }
                override fun onSkipToNext() { SpeechService.onChapter?.invoke(1) }
                override fun onSkipToPrevious() { SpeechService.onChapter?.invoke(-1) }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { SpeechService.onStop?.invoke(); stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE -> SpeechService.onToggle?.invoke()
            ACTION_PREV -> SpeechService.onChapter?.invoke(-1)
            ACTION_NEXT -> SpeechService.onChapter?.invoke(1)
        }
        running = true
        startInForeground()
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(SpeechNotification.channel(this))
        val book = bookTitle.ifBlank { getString(R.string.s_reading_aloud) }
        val chapter = chapterTitle.ifBlank { getString(R.string.s_reading_aloud_hint) }
        session?.let { s ->
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, book)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, chapter)
                    .build()
            )
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        if (playing) 1f else 0f,
                    )
                    .build()
            )
        }
        startForeground(
            SpeechNotification.ID,
            SpeechNotification.build(
                this, book, chapter, playing, session?.sessionToken,
                open = activity(0, Intent(this, ReaderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)),
                prev = action(2, ACTION_PREV),
                toggle = action(3, ACTION_TOGGLE),
                next = action(4, ACTION_NEXT),
                stop = action(5, ACTION_STOP),
            ),
        )
    }

    private fun activity(code: Int, intent: Intent) = PendingIntent.getActivity(
        this, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(code: Int, what: String) = PendingIntent.getService(
        this, code, Intent(this, SpeechService::class.java).setAction(what),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        running = false
        session?.isActive = false
        session?.release()
        session = null
        SpeechService.onStop = null
        SpeechService.onToggle = null
        SpeechService.onChapter = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "app.kairn.STOP_SPEECH"
        private const val ACTION_TOGGLE = "app.kairn.TOGGLE_SPEECH"
        private const val ACTION_PREV = "app.kairn.SPEECH_PREV"
        private const val ACTION_NEXT = "app.kairn.SPEECH_NEXT"

        /** Set by the reader while it is alive; the notification Stop action calls it. */
        @Volatile
        var onStop: (() -> Unit)? = null

        /** Pause or resume, whichever the reader state calls for. */
        @Volatile
        var onToggle: (() -> Unit)? = null

        /** -1 for the chapter before, +1 for the chapter after. */
        @Volatile
        var onChapter: ((Int) -> Unit)? = null

        @Volatile
        private var bookTitle = ""
        @Volatile
        private var chapterTitle = ""
        @Volatile
        private var playing = true
        @Volatile
        private var running = false

        /** Idempotent: starting an already-running foreground service only re-posts the notification. */
        fun start(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, SpeechService::class.java))
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, SpeechService::class.java)) }
        }

        /**
         * What the notification and the lock screen say. Cheap when nothing moved; the notification is the only
         * thing a reader with the screen off can see, so it may not be a static title and one Stop button.
         */
        fun update(ctx: Context, book: String, chapter: String, playing: Boolean) {
            if (bookTitle == book && chapterTitle == chapter && this.playing == playing) return
            bookTitle = book
            chapterTitle = chapter
            this.playing = playing
            if (running) start(ctx)
        }
    }
}

/**
 * The notification, built apart from the service so a test can draw it: book and chapter on the two lines, four
 * transport actions, and - once there is a session - a media style that puts the first three on the lock screen
 * and in a car, instead of a single Stop.
 */
internal object SpeechNotification {

    const val ID = 7
    internal const val CHANNEL = "reading_aloud"

    fun channel(ctx: Context) =
        NotificationChannel(CHANNEL, ctx.getString(R.string.s_reading_aloud), NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false); description = ctx.getString(R.string.s_reading_aloud_hint) }

    fun build(
        ctx: Context,
        book: String,
        chapter: String,
        playing: Boolean,
        token: MediaSession.Token?,
        open: PendingIntent,
        prev: PendingIntent,
        toggle: PendingIntent,
        next: PendingIntent,
        stop: PendingIntent,
    ): Notification {
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_cairn)
            .setContentTitle(book)
            .setContentText(chapter)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .addAction(action(ctx, R.drawable.ic_skip_previous, R.string.s_previous_chapter, prev))
            .addAction(
                action(
                    ctx,
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (playing) R.string.s_pause else R.string.s_resume,
                    toggle,
                )
            )
            .addAction(action(ctx, R.drawable.ic_skip_next, R.string.s_next_chapter, next))
            .addAction(action(ctx, R.drawable.ic_stop, R.string.s_stop, stop))
        if (token != null) {
            b.setStyle(Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(0, 1, 2))
        }
        return b.build()
    }

    private fun action(ctx: Context, icon: Int, label: Int, intent: PendingIntent) =
        Notification.Action.Builder(Icon.createWithResource(ctx, icon), ctx.getString(label), intent).build()
}
