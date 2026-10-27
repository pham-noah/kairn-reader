package app.kairn.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The JVM test drives the transfer server against a temp dir on the host. This one binds a REAL socket on
 * the device and speaks HTTP to it over loopback, which is exactly what the browser on the computer does.
 *
 * Raw sockets, not an HTTP client: the app's own network policy refuses cleartext (it never makes an
 * outbound request - the phone only listens), and the policy is enforced by the HTTP stack, not the socket.
 */
@RunWith(AndroidJUnit4::class)
class WifiTransferDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun request(port: Int, method: String, path: String, body: ByteArray? = null): String =
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            val out = s.getOutputStream()
            val head = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                if (body != null) append("Content-Length: ${body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray()); body?.let { out.write(it) }; out.flush()
            s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    @Test fun an_upload_over_a_real_socket_lands_in_the_folder() {
        val dir = File(ctx.cacheDir, "wifi-${System.nanoTime()}")
        val arrived = CountDownLatch(1)
        var received: String? = null
        val server = WifiTransfer(dir) { _, name -> received = name; arrived.countDown() }
        try {
            assertTrue("server did not bind", server.start(code = "4821"))
            val payload = "epub-bytes".toByteArray()
            val response = request(server.port, "POST", "/4821/upload?name=Device.epub", payload)
            assertTrue("unexpected response: ${response.take(40)}", response.startsWith("HTTP/1.1 200"))
            assertTrue("callback never fired", arrived.await(5, TimeUnit.SECONDS))
            assertEquals("Device.epub", received)
            assertEquals(payload.size.toLong(), File(dir, "Device.epub").length())
        } finally {
            server.stop()
        }
    }

    @Test fun the_page_is_served_only_under_the_code_and_anything_else_is_404() {
        val server = WifiTransfer(File(ctx.cacheDir, "wifi-b-${System.nanoTime()}")) { _, _ -> }
        try {
            assertTrue(server.start(code = "1111"))
            val page = request(server.port, "GET", "/1111/")
            assertTrue(page.startsWith("HTTP/1.1 200"))
            assertTrue("upload page is missing its form", page.contains("Send books to Kairn"))
            val bare = request(server.port, "GET", "/")
            assertTrue("the bare root answered ${bare.take(40)}", bare.startsWith("HTTP/1.1 404"))
        } finally {
            server.stop()
        }
    }
}
