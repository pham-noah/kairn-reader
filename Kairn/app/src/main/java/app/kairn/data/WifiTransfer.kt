package app.kairn.data

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale

/**
 * L-04: a LAN-only HTTP server, so a computer on the same Wi-Fi can drop EPUB files onto the phone.
 * It serves one page and one upload endpoint, binds every interface because the phone's address is not known in advance, and never talks to anything itself - the only traffic is a browser on the local network talking to it.
 * Plain java.net: no HTTP library, no dependency, and nothing that needs a main thread.
 */
class WifiTransfer(
    private val dir: File,
    private val onFile: (file: File, name: String) -> Unit,
) {
    @Volatile private var server: ServerSocket? = null
    private var thread: Thread? = null
    /** The optional path code: every request has to carry it, so a stranger on the network cannot upload. */
    @Volatile private var code: String? = null
    var port: Int = 0
        private set

    val running: Boolean get() = server?.isClosed == false
    val url: String? get() = if (running) address()?.let { "http://$it:$port$prefix" } else null

    /** "" without a code, "/1234" with one. The page is served at the prefix and uploads at prefix + "/upload". */
    private val prefix: String get() = code?.let { "/$it" } ?: ""

    /**
     * Binds and starts accepting. [code] puts a path segment in front of every route, so the address the
     * phone shows is the only one that works. Returns false when no port could be opened.
     */
    fun start(preferredPort: Int = DEFAULT_PORT, code: String? = null): Boolean {
        if (running) return true
        val bound = runCatching { ServerSocket(preferredPort) }.getOrNull()
            ?: runCatching { ServerSocket(0) }.getOrNull()
            ?: return false
        this.code = code?.takeIf { it.isNotBlank() }
        server = bound
        port = bound.localPort
        thread = Thread({ acceptLoop(bound) }, "kairn-wifi").apply { isDaemon = true; start() }
        return true
    }

    fun stop() {
        val s = server ?: return
        server = null
        code = null
        runCatching { s.close() }
        thread = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client = runCatching { s.accept() }.getOrNull() ?: break
            runCatching { client.soTimeout = SOCKET_TIMEOUT_MS; handle(client) }
            runCatching { client.close() }
        }
    }

    private fun handle(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return respond(client, 400, TEXT, "Bad request")
        val method = parts[0]
        val target = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
        }

        val path = target.substringBefore('?')
        when {
            method == "GET" && (path == "$prefix/" || path == prefix || (prefix.isEmpty() && path == "/")) ->
                respond(client, 200, HTML, PAGE)
            method == "POST" && path == "$prefix/upload" -> receive(client, input, headers, target)
            else -> respond(client, 404, TEXT, "Not found")
        }
    }

    /** Writes exactly Content-Length bytes to the destination folder, then reports the file. */
    private fun receive(client: Socket, input: InputStream, headers: Map<String, String>, target: String) {
        val length = headers["content-length"]?.toLongOrNull() ?: return respond(client, 411, TEXT, "Length required")
        if (length <= 0 || length > MAX_BYTES) return respond(client, 413, TEXT, "That file is too large")
        val name = safeName(queryParam(target, "name"))
            ?: return respond(client, 400, TEXT, "Only .epub files are accepted")

        dir.mkdirs()
        val dest = uniqueFile(dir, name)
        val ok = runCatching {
            dest.outputStream().use { out -> copyExactly(input, out, length) }
            dest.length() == length
        }.getOrDefault(false)
        if (!ok) {
            runCatching { dest.delete() }
            return respond(client, 500, TEXT, "The transfer failed")
        }
        onFile(dest, dest.name)
        respond(client, 200, TEXT, "ok")
    }

    private fun copyExactly(input: InputStream, out: OutputStream, length: Long) {
        val buffer = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) throw java.io.EOFException("upload ended early")
            out.write(buffer, 0, read)
            left -= read
        }
        out.flush()
    }

    private fun respond(client: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        val head = "HTTP/1.1 $code ${statusText(code)}\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        runCatching { client.getOutputStream().use { it.write(head.toByteArray()); it.write(bytes) } }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val SOCKET_TIMEOUT_MS = 20_000
        private const val MAX_BYTES = 512L * 1024 * 1024
        private const val TEXT = "text/plain; charset=utf-8"
        private const val HTML = "text/html; charset=utf-8"

        /** The phone's LAN address, for the URL the user types on the computer. Null when offline. */
        fun address(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()

        /** A bare, printable .epub name from whatever the query string carried; null for anything else. */
        internal fun safeName(raw: String?): String? {
            val base = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\').trim()
            if (!base.endsWith(".epub", ignoreCase = true)) return null
            val stem = base.substring(0, base.length - 5)
                .map { if (it.isLetterOrDigit() || it in " .-()[]") it else '_' }
                .joinToString("").replace(Regex("_+"), "_").trim().trim('.', '_', ' ')
            if (stem.isBlank()) return null
            return stem.take(115) + ".epub"
        }

        internal fun queryParam(target: String, key: String): String? {
            val query = target.substringAfter('?', "")
            if (query.isEmpty()) return null
            return query.split('&').map { it.split('=', limit = 2) }
                .firstOrNull { it.size == 2 && it[0] == key }
                ?.get(1)?.let { decode(it) }
        }

        private fun decode(s: String): String = runCatching {
            java.net.URLDecoder.decode(s, "UTF-8")
        }.getOrDefault(s)

        private fun uniqueFile(dir: File, name: String): File {
            val base = name.removeSuffix(".epub")
            var candidate = File(dir, name)
            var i = 2
            while (candidate.exists()) { candidate = File(dir, "$base-$i.epub"); i++ }
            return candidate
        }

        private fun statusText(code: Int) = when (code) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 404 -> "Not Found"
            411 -> "Length Required"; 413 -> "Payload Too Large"; 500 -> "Internal Server Error"
            else -> "Error"
        }

        /** The upload page. Plain HTML + a few lines of script: no framework, no external assets. */
        private val PAGE = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Kairn</title>
<style>
:root{color-scheme:dark}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0E1114;color:#EEF1F3;
  font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;padding:24px}
main{width:100%;max-width:520px}
h1{font-size:28px;margin:0 0 6px}
p{color:#A2ACB3;margin:0 0 20px}
#drop{display:grid;place-items:center;gap:8px;padding:40px 20px;border:2px dashed #2A3036;
  border-radius:20px;background:#161A1E;text-align:center;cursor:pointer;transition:.15s}
#drop.hot{border-color:#7EE0C3;background:#12211C}
#drop b{font-size:17px}
#drop span{color:#838D94;font-size:14px}
#pick{display:none}
ul{list-style:none;padding:0;margin:20px 0 0}
li{display:flex;justify-content:space-between;gap:12px;padding:12px 0;border-top:1px solid #2A3036;font-size:15px}
li .n{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
li .s{color:#7EE0C3;flex:none}
li .s.bad{color:#E68B9B}
</style></head><body><main>
<h1>Send books to Kairn</h1>
<p>Drop EPUB files here, or click to choose them. They are added to your library on this phone.</p>
<div id="drop"><b>Drop your EPUBs</b><span>or click to browse</span></div>
<input id="pick" type="file" accept=".epub,application/epub+zip" multiple>
<ul id="list"></ul>
</main><script>
const drop=document.getElementById('drop'),pick=document.getElementById('pick'),list=document.getElementById('list');
drop.onclick=()=>pick.click();
pick.onchange=()=>{send(pick.files);pick.value=''};
['dragenter','dragover'].forEach(e=>drop.addEventListener(e,ev=>{ev.preventDefault();drop.classList.add('hot')}));
['dragleave','drop'].forEach(e=>drop.addEventListener(e,ev=>{ev.preventDefault();drop.classList.remove('hot')}));
drop.addEventListener('drop',ev=>send(ev.dataTransfer.files));
function send(files){[...files].forEach(f=>{
  const base=location.pathname.endsWith('/')?location.pathname:location.pathname+'/';
  const li=document.createElement('li'),n=document.createElement('div'),s=document.createElement('div');
  n.className='n';n.textContent=f.name;s.className='s';s.textContent='0%';li.append(n,s);list.append(li);
  if(!/\.epub$/i.test(f.name)){s.textContent='not an EPUB';s.classList.add('bad');return}
  const x=new XMLHttpRequest();x.open('POST',base+'upload?name='+encodeURIComponent(f.name));
  x.upload.onprogress=e=>{if(e.lengthComputable)s.textContent=Math.round(e.loaded/e.total*100)+'%'};
  x.onload=()=>{const ok=x.status===200;s.textContent=ok?'added':'failed';if(!ok)s.classList.add('bad')};
  x.onerror=()=>{s.textContent='failed';s.classList.add('bad')};
  x.send(f);
})}
</script></body></html>
""".trimIndent()
    }
}
