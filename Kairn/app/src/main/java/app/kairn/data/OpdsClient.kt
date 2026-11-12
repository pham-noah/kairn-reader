package app.kairn.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The only other thing in Kairn that opens a socket, and only when the reader asked for a book from a catalog.
 *
 * Two rules, both taken from the update check: nothing is accepted without a length that matches what was
 * promised, and every read is bounded. A catalog is a server someone else runs; it does not get to decide how
 * much memory this app uses.
 */
class OpdsClient(private val versionName: String) {

    /** A feed is a list of books: a few hundred kilobytes, never megabytes. */
    private val maxFeed = 8L * 1024 * 1024

    /** One EPUB. Bigger than this and it is not a book this reader is going to open. */
    private val maxBook = 1024L * 1024 * 1024

    /** The catalog's page, or null. Never throws: the caller shows "could not reach that address". */
    suspend fun fetch(url: String, user: String? = null, pass: String? = null): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val c = open(url, user, pass) ?: return@runCatching null
                try {
                    if (c.responseCode !in 200..299) return@runCatching null
                    val body = c.inputStream.use { read(it, maxFeed) } ?: return@runCatching null
                    String(body, Charsets.UTF_8)
                } finally { c.disconnect() }
            }.getOrNull()
        }

    /**
     * Writes the book to [dest]. Returns its size, or null on any failure - including a body larger than
     * [maxBook] and a connection that dies halfway, which leaves no file behind to be imported by mistake.
     */
    suspend fun download(
        url: String,
        dest: File,
        user: String? = null,
        pass: String? = null,
        onProgress: (Int) -> Unit = {},
    ): Long? = withContext(Dispatchers.IO) {
        runCatching {
            if (!hasRoom(dest.parentFile ?: return@runCatching null)) return@runCatching null
            // Leaving the catalog closes this coroutine; a blocking read cannot be interrupted, so the loop
            // checks the job itself and stops on the next 64 kB rather than downloading into a dead screen.
            val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            val c = open(url, user, pass) ?: return@runCatching null
            try {
                if (c.responseCode !in 200..299) return@runCatching null
                val total = c.contentLengthLong
                var written = 0L
                dest.outputStream().use { out ->
                    c.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            if (job?.isActive == false) return@runCatching null
                            written += n
                            if (written > maxBook) return@runCatching null
                            out.write(buf, 0, n)
                            if (total > 0) onProgress(((written * 100) / total).toInt())
                        }
                    }
                }
                if (written <= 0) return@runCatching null
                written
            } finally { c.disconnect() }
        }.onSuccess { written -> if (written == null) runCatching { dest.delete() } }
            .onFailure { runCatching { dest.delete() } }
            .getOrNull()
    }

    /**
     * A phone can be full. The transfer server refuses an upload that would leave it under 64 MB; a catalog
     * download is the same act from the other side, so it gets the same floor.
     */
    @android.annotation.SuppressLint("UsableSpace")   // the same conservative guess the upload path makes
    private fun hasRoom(dir: File, need: Long = MIN_FREE): Boolean =
        runCatching { dir.exists() && dir.usableSpace > need }.getOrDefault(false)

    private companion object { const val MIN_FREE = 64L * 1024 * 1024 }

    /** A connection that only ever speaks HTTP(S), with a timeout and a user agent that says what it is. */
    private fun open(url: String, user: String?, pass: String?): HttpURLConnection? = runCatching {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Kairn/$versionName")
        c.setRequestProperty("Accept", "application/atom+xml,application/xml,text/xml,*/*")
        if (!user.isNullOrEmpty()) {
            val token = java.util.Base64.getEncoder()
                .encodeToString("$user:${pass.orEmpty()}".toByteArray(Charsets.UTF_8))
            c.setRequestProperty("Authorization", "Basic $token")
        }
        c
    }.getOrNull()

    /** Reads at most [limit] bytes; null when the body runs past it. */
    private fun read(input: java.io.InputStream, limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
