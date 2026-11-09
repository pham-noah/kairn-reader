package app.kairn

import app.kairn.data.WifiTransfer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.Socket
import java.nio.file.Files

/** Raw-socket probes against the LAN transfer server: what a browser, or a hostile neighbour, can make it do. */
class WifiTransferAttackTest {

    private val dir: File = Files.createTempDirectory("kairn-wifi").toFile()
    private val server = WifiTransfer(dir) { f, _ -> f.name }

    private fun boot(code: String? = "1234") {
        assertTrue(server.start(0, code))
    }

    @After fun stop() = server.stop()

    /** Sends bytes, then reads the whole reply (the server closes the connection). */
    private fun raw(payload: ByteArray, readTimeoutMs: Int = 4000): String {
        Socket("127.0.0.1", server.port).use { s ->
            s.soTimeout = readTimeoutMs
            s.getOutputStream().write(payload)
            s.getOutputStream().flush()
            return runCatching { s.getInputStream().readBytes().decodeToString() }.getOrDefault("<no response>")
        }
    }

    private fun raw(text: String, readTimeoutMs: Int = 4000) = raw(text.toByteArray(), readTimeoutMs)

    private fun request(method: String, path: String, body: ByteArray = ByteArray(0)): ByteArray {
        val head = "$method $path HTTP/1.1" + CRLF + "Host: x" + CRLF + "Content-Length: ${body.size}" + CRLF + CRLF
        return head.toByteArray() + body
    }

    private fun post(name: String, length: String): String {
        val head = "POST /1234/upload?name=$name HTTP/1.1" + CRLF + "Content-Length: $length" + CRLF + CRLF
        return raw(head.toByteArray())
    }

    private fun status(r: String) = r.substringBefore('\r').ifEmpty { r.take(40) }

    @Test fun page_needs_the_code_and_uploads_land_in_the_folder() {
        boot("4321")
        val page = raw("GET /4321/ HTTP/1.1" + CRLF + CRLF)
        assertTrue("page: ${status(page)}", page.startsWith("HTTP/1.1 200"))
        assertTrue(page.contains("Send books to Kairn"))
        assertTrue(raw("GET /9999/ HTTP/1.1" + CRLF + CRLF).startsWith("HTTP/1.1 404"))
        assertTrue(raw("GET / HTTP/1.1" + CRLF + CRLF).startsWith("HTTP/1.1 404"))

        val ok = raw(request("POST", "/4321/upload?name=My%20Book.epub", "epub-bytes".toByteArray()))
        assertTrue("upload: ${status(ok)}", ok.startsWith("HTTP/1.1 200"))
        val f = File(dir, "My Book.epub")
        assertTrue("file not written", f.exists())
        assertEquals("epub-bytes", f.readText())
    }

    @Test fun a_second_upload_of_the_same_name_does_not_overwrite() {
        boot()
        val body = "one".toByteArray()
        assertTrue(raw(request("POST", "/1234/upload?name=b.epub", body)).startsWith("HTTP/1.1 200"))
        assertTrue(raw(request("POST", "/1234/upload?name=b.epub", body)).startsWith("HTTP/1.1 200"))
        assertEquals(setOf("b.epub", "b-2.epub"), dir.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun a_traversing_name_cannot_write_outside_the_folder() {
        boot()
        val ok = raw(request("POST", "/1234/upload?name=..%2F..%2Fescaped.epub", "x".toByteArray()))
        assertTrue("upload: ${status(ok)}", ok.startsWith("HTTP/1.1 200"))
        assertTrue(File(dir, "escaped.epub").exists())
        assertTrue(!File(dir.parentFile, "escaped.epub").exists())
    }

    @Test fun a_body_that_ends_early_is_answered_and_leaves_no_file() {
        boot()
        val payload = ("POST /1234/upload?name=short.epub HTTP/1.1" + CRLF + "Content-Length: 100" + CRLF + CRLF + "only-ten!!").toByteArray()
        val r = Socket("127.0.0.1", server.port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write(payload)
            s.getOutputStream().flush()
            s.shutdownOutput()                 // the sender is gone: EOF, not a wait for the socket timeout
            runCatching { s.getInputStream().readBytes().decodeToString() }.getOrDefault("<no response>")
        }
        assertTrue("a short body got [${status(r)}]", r.startsWith("HTTP/1.1 500"))
        assertTrue("a partial file was kept", dir.listFiles().orEmpty().none { it.name.startsWith("short") })
    }

    @Test fun length_limits_are_enforced() {
        boot()
        for ((length, name, want) in listOf(
            Triple("0", "a.epub", 413),
            Triple("-5", "a.epub", 413),
            Triple("999999999999", "a.epub", 413),
            Triple("nonsense", "a.epub", 411),
            Triple("10", "not-an-epub.txt", 400),
            Triple("10", "", 400),
        )) {
            val r = post(name, length)
            assertTrue("length=$length name=$name -> ${status(r)}", r.startsWith("HTTP/1.1 $want"))
        }
    }

    @Test fun a_junk_request_does_not_kill_the_server() {
        boot()
        Thread.sleep(100)
        val one = raw("garbage" + CRLF + CRLF)
        Thread.sleep(100)
        val two = raw(ByteArray(20000) { 'A'.code.toByte() } + (CRLF + CRLF).toByteArray())
        Thread.sleep(100)
        val three = raw("GET /1234/ HTTP/1.1" + CRLF + CRLF)
        assertTrue("bad request line -> [${status(one)}]", one.startsWith("HTTP/1.1 400"))
        assertTrue("20000-byte header line -> [${status(two)}]", two.startsWith("HTTP/1.1 400"))
        assertTrue("server died -> [${status(three)}]", three.startsWith("HTTP/1.1 200"))
    }

    /**
     * The accept loop handles one connection at a time, so a client that opens a socket and sends nothing
     * holds up every other upload for the whole socket timeout. A browser preconnect is enough to do it.
     */
    @Test fun a_stalled_client_does_not_freeze_the_next_upload() {
        boot()
        val stalled = Socket("127.0.0.1", server.port)      // connect, send nothing
        try {
            Thread.sleep(300)
            val t0 = System.currentTimeMillis()
            val r = raw(request("POST", "/1234/upload?name=after.epub", "x".toByteArray()), 25_000)
            val ms = System.currentTimeMillis() - t0
            assertTrue("got: ${status(r)}", r.startsWith("HTTP/1.1 200"))
            assertTrue("a stalled client delayed the next upload by ${ms}ms", ms < 2000)
        } finally {
            runCatching { stalled.close() }
        }
    }

    companion object {
        private const val CRLF = "\r\n"
    }
}
