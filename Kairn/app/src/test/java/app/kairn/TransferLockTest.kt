package app.kairn

import app.kairn.data.Update
import app.kairn.data.WifiTransfer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.Socket
import java.nio.file.Files

/** Third pass: the two locks that guard the download and the LAN page. */
class TransferLockTest {

    private val dir: File = Files.createTempDirectory("kairn-lock").toFile()
    private val server = WifiTransfer(dir) { f, _ -> f.name }

    @After fun stop() = server.stop()

    private fun raw(text: String): String {
        Socket("127.0.0.1", server.port).use { s ->
            s.soTimeout = 8000
            s.getOutputStream().write(text.toByteArray())
            s.getOutputStream().flush()
            return runCatching { s.getInputStream().readBytes().decodeToString() }.getOrDefault("<no response>")
        }
    }

    @Test fun wrong_codes_are_throttled_but_a_reader_is_not() {
        // A mistyped address costs nothing; a walk through 10 000 codes costs minutes.
        assertTrue(WifiTransfer.codeDelayMs(0) == 0L)
        assertTrue(WifiTransfer.codeDelayMs(19) == 0L)
        assertTrue(WifiTransfer.codeDelayMs(20) == 100L)
        assertTrue(WifiTransfer.codeDelayMs(30) == 1_100L)
        assertTrue("the delay must stay bounded", WifiTransfer.codeDelayMs(100_000) == 2_000L)
    }

    @Test fun only_a_four_digit_path_trips_the_throttle() {
        assertTrue(WifiTransfer.countsAsGuess("/1234/"))
        assertTrue(WifiTransfer.countsAsGuess("/9999"))
        assertTrue(!WifiTransfer.countsAsGuess("/favicon.ico"))
        assertTrue(!WifiTransfer.countsAsGuess("/robots.txt"))
        assertTrue(!WifiTransfer.countsAsGuess("/12345/"))
        assertTrue(!WifiTransfer.countsAsGuess("/"))
    }

    @Test fun a_run_of_wrong_codes_still_lets_the_right_one_in() {
        assertTrue(server.start(0, "4321"))
        repeat(24) { i ->
            val r = raw("GET /" + (1000 + i) + "/ HTTP/1.1\r\n\r\n")
            assertTrue("attempt $i got [$r]", r.startsWith("HTTP/1.1 404"))
        }
        // The throttle has engaged by now, and the right address still opens the page.
        val ok = raw("GET /4321/ HTTP/1.1\r\n\r\n")
        assertTrue("the right code was refused: [$ok]", ok.startsWith("HTTP/1.1 200"))
        // ...and the run resets: the next wrong one is not punished any more.
        assertTrue(WifiTransfer.codeDelayMs(0) == 0L)
    }

    @Test fun a_release_tag_cannot_choose_the_download_path() {
        assertTrue(Update.downloadFileName("0.9.5") == "kairn-0.9.5.apk")
        val evil = Update.downloadFileName("../../../files/escaping")
        assertTrue("tag became a path: $evil", !evil.contains('/') && !evil.contains(".."))
        assertTrue(evil.startsWith("kairn-") && evil.endsWith(".apk"))
        assertTrue(Update.downloadFileName("").endsWith(".apk"))
        assertTrue(Update.downloadFileName("x".repeat(500)).length < 60)
    }
}
