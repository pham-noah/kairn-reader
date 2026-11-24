package app.kairn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import app.kairn.ui.SpeechNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The read-aloud notification, drawn on the JVM. It used to be a static "Reading aloud" with a single Stop, which
 * is why a reader with the screen off could not pause, could not skip a chapter, and could not tell which book was
 * speaking. Every one of those is an assertion here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechNotificationTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val pi = PendingIntent.getActivity(ctx, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)

    private fun build(playing: Boolean = true) =
        SpeechNotification.build(ctx, "Piranesi", "Chapter Three", playing, null, pi, pi, pi, pi, pi)

    @Test fun `names the book and the chapter`() {
        val n = build()
        assertEquals("Piranesi", n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("Chapter Three", n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test fun `carries the four transport actions in order`() {
        val labels = build().actions.map { it.title.toString() }
        assertEquals(listOf("Previous chapter", "Pause", "Next chapter", "Stop"), labels)
    }

    @Test fun `a paused chapter offers Resume instead of Pause`() {
        assertEquals("Resume", build(playing = false).actions[1].title.toString())
    }

    @Test fun `a tap opens the reader, and the notification cannot be swiped away`() {
        val n = build()
        assertNotNull(n.contentIntent)
        assertEquals(Notification.FLAG_ONGOING_EVENT, n.flags and Notification.FLAG_ONGOING_EVENT)
    }

    @Test fun `the style is media only when there is a session to draw from`() {
        val template = build().extras.getString(Notification.EXTRA_TEMPLATE)
        assertFalse(template?.contains("MediaStyle") == true)
    }
}
