package app.kairn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.Socket
import java.net.URLEncoder

/**
 * L-04 runs a real socket server, so it is tested the way a computer uses it: over a socket, against a
 * temp dir. No device and no Robolectric - the class has no Android dependency on purpose.
 */
class WifiTransferTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun withServer(code: String? = null, block: (wifi: WifiTransfer, dir: File, received: MutableList<String>) -> Unit) {
        val dir = tmp.newFolder("inbox")
        val received = mutableListOf<String>()
        val wifi = WifiTransfer(dir) { f, n -> received += n + ":" + f.length() }
        assertTrue("server did not start", wifi.start(0, code))
        try { block(wifi, dir, received) } finally { wifi.stop() }
    }

    /**
     * One request, one response; returns status and body. Retried once on a socket error: the server answers
     * and closes, so on a loaded machine the client can lose the race between writing and reading the reply.
     * Nothing about the response changes, so a retry cannot hide a wrong answer.
     */
    private fun request(port: Int, head: String, body: ByteArray = ByteArray(0)): Pair<Int, String> {
        var last: Exception? = null
        repeat(2) {
            try {
                return Socket("127.0.0.1", port).use { s ->
                    s.soTimeout = 5_000
                    s.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
                    val raw = s.getInputStream().readBytes().toString(Charsets.UTF_8)
                    val status = raw.substringAfter(' ').substringBefore(' ').toIntOrNull() ?: -1
                    status to raw.substringAfter("\r\n\r\n", "")
                }
            } catch (e: java.net.SocketException) {
                last = e
                Thread.sleep(50)
            }
        }
        throw last!!
    }

    private fun upload(port: Int, name: String, bytes: ByteArray, path: String = "/upload?name="): Pair<Int, String> {
        val query = URLEncoder.encode(name, "UTF-8")
        return request(
            port,
            "POST $path$query HTTP/1.1\r\nHost: x\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n",
            bytes,
        )
    }

    @Test fun a_code_is_the_only_path_that_works() = withServer(code = "4821") { wifi, dir, received ->
        // The address the phone shows carries the code; anything else on the same network gets a 404.
        assertTrue(wifi.url!!.endsWith("/4821"))
        assertEquals(404, request(wifi.port, "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").first)
        assertEquals(404, request(wifi.port, "GET /4822 HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").first)
        assertEquals(404, upload(wifi.port, "x.epub", "x".toByteArray()).first)

        val page = request(wifi.port, "GET /4821 HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        assertEquals(200, page.first)
        assertTrue(page.second.contains("Send books to Kairn"))

        val bytes = "coded upload".toByteArray()
        assertEquals(200, upload(wifi.port, "Coded.epub", bytes, "/4821/upload?name=").first)
        assertEquals(bytes.size.toLong(), File(dir, "Coded.epub").length())
        assertEquals(1, received.size)
    }

    @Test fun without_a_code_the_plain_address_works() = withServer(code = null) { wifi, dir, _ ->
        assertFalse(wifi.url!!.matches(Regex(""".*/\d{4}$""")))
        assertEquals(200, request(wifi.port, "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").first)
        assertEquals(200, upload(wifi.port, "Plain.epub", "p".toByteArray()).first)
        assertTrue(File(dir, "Plain.epub").exists())
    }

    @Test fun serves_the_upload_page() = withServer { wifi, _, _ ->
        val (status, body) = request(wifi.port, "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        assertEquals(200, status)
        assertTrue(body.contains("Send books to Kairn"))
    }

    @Test fun a_posted_epub_lands_in_the_folder_and_is_reported() = withServer { wifi, dir, received ->
        val bytes = "not really an epub, but the bytes must survive".toByteArray()
        val (status, _) = upload(wifi.port, "My Book.epub", bytes)
        assertEquals(200, status)
        val file = File(dir, "My Book.epub")
        assertTrue(file.exists())
        assertEquals(bytes.size.toLong(), file.length())
        assertEquals(listOf("My Book.epub:${bytes.size}"), received)
    }

    @Test fun a_second_copy_gets_its_own_name() = withServer { wifi, dir, _ ->
        assertEquals(200, upload(wifi.port, "Dune.epub", "a".toByteArray()).first)
        assertEquals(200, upload(wifi.port, "Dune.epub", "bb".toByteArray()).first)
        assertEquals("a", File(dir, "Dune.epub").readText())
        assertEquals("bb", File(dir, "Dune-2.epub").readText())
    }

    @Test fun a_path_in_the_name_cannot_escape_the_folder() = withServer { wifi, dir, _ ->
        val (status, _) = upload(wifi.port, "../../evil.epub", "x".toByteArray())
        assertEquals(200, status)
        assertTrue(File(dir, "evil.epub").exists())
        assertFalse(File(dir.parentFile, "evil.epub").exists())
    }

    @Test fun non_epub_uploads_are_refused() = withServer { wifi, dir, received ->
        val (status, _) = upload(wifi.port, "notes.txt", "x".toByteArray())
        assertEquals(400, status)
        assertTrue(received.isEmpty())
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test fun a_body_longer_than_the_content_length_is_not_trusted() = withServer { wifi, dir, received ->
        // Declares 4 bytes, sends 9: only the declared bytes are read, so no trailing garbage is stored.
        val (status, _) = request(
            wifi.port,
            "POST /upload?name=a.epub HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\nConnection: close\r\n\r\n",
            "123456789".toByteArray(),
        )
        assertEquals(200, status)
        assertEquals("1234", File(dir, "a.epub").readText())
        assertEquals(1, received.size)
    }

    @Test fun a_truncated_body_leaves_no_file() = withServer { wifi, dir, received ->
        // The client vanishes mid-upload: the declared 20 bytes never arrive, so nothing may be kept.
        Socket("127.0.0.1", wifi.port).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().apply {
                write("POST /upload?name=b.epub HTTP/1.1\r\nHost: x\r\nContent-Length: 20\r\nConnection: close\r\n\r\n".toByteArray())
                write("short".toByteArray()); flush()
                s.shutdownOutput()
            }
            val raw = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertEquals(500, raw.substringAfter(' ').substringBefore(' ').toIntOrNull())
        }
        assertFalse(File(dir, "b.epub").exists())
        assertTrue(received.isEmpty())
    }

    @Test fun names_are_reduced_to_a_safe_epub_file_name() {
        assertEquals("Dune.epub", WifiTransfer.safeName("Dune.epub"))
        assertEquals("Dune.epub", WifiTransfer.safeName("C:\\books\\Dune.epub"))
        assertEquals("Dune.epub", WifiTransfer.safeName("/sdcard/Dune.epub"))
        assertEquals("a_b.epub", WifiTransfer.safeName("a<b>.epub"))
        assertEquals("Dune.epub", WifiTransfer.safeName("Dune.EPUB"))
        assertNull(WifiTransfer.safeName("Dune.txt"))
        assertNull(WifiTransfer.safeName(""))
        assertNull(WifiTransfer.safeName(null))
        assertNull(WifiTransfer.safeName(".epub"))
    }

    @Test fun the_upload_url_is_decoded() {
        assertEquals("My Book.epub", WifiTransfer.queryParam("/upload?name=My%20Book.epub", "name"))
        assertEquals("a&b.epub", WifiTransfer.queryParam("/upload?name=a%26b.epub&x=1", "name"))
        assertNull(WifiTransfer.queryParam("/upload", "name"))
    }
}
