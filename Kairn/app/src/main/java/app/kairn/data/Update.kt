package app.kairn.data

/**
 * Self-update from GitHub releases, the way Mihon and Sonarar do it.
 *
 * This is the app's SECOND outgoing request and the only one that is not the user typing into a browser, so
 * it is strictly opt-in (Settings > About), it asks for a JSON document and nothing else, and it identifies
 * itself with the app name and version and no other information. Kairn still sends no identifier, no
 * install id, no usage. See README "Privacy".
 *
 * The download is only accepted when the byte count matches the release asset, and the installer is the
 * system package installer: the app never installs anything silently, and never asks for a permission it
 * does not use.
 */
object Update {

    const val REPO = "pham-noah/kairn-reader"

    data class Release(
        val version: String,      // "0.8.1", tag with the "v" removed
        val url: String,          // browser_download_url of the APK
        val size: Long,           // bytes, from the release asset
        val notes: String,        // release body, may be empty
        val page: String,         // html_url of the release
    )

    /** What one check produced. "No update" and "could not ask" are different answers, so they differ. */
    sealed interface Check {
        data class Found(val release: Release) : Check
        data object Current : Check
        data object Failed : Check
    }

    /** Null on any failure OR when there is nothing newer: for callers that only care about the release. */
    suspend fun check(currentVersion: String, timeoutMs: Int = 8000): Release? =
        (look(currentVersion, timeoutMs) as? Check.Found)?.release

    suspend fun look(currentVersion: String, timeoutMs: Int = 8000): Check =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val o = fetchJson(currentVersion, timeoutMs) ?: return@withContext Check.Failed
            val tag = o.optString("tag_name").removePrefix("v").trim()
            if (tag.isEmpty()) return@withContext Check.Failed
            if (!isNewer(tag, currentVersion)) return@withContext Check.Current
            val asset = apkAsset(o.optJSONArray("assets")) ?: return@withContext Check.Current
            Check.Found(
                Release(
                    version = tag,
                    url = asset.url,
                    size = asset.size,
                    notes = o.optString("body").orEmpty(),
                    page = o.optString("html_url").orEmpty(),
                ),
            )
        }

    /** The release JSON, or null. No exceptions out: an app that will not open because GitHub was slow is broken. */
    private fun fetchJson(currentVersion: String, timeoutMs: Int): org.json.JSONObject? = runCatching {
        val c = java.net.URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("Accept", "application/vnd.github+json")
        // The whole User-Agent, deliberately: the app and its version, and no device id, no install id.
        c.setRequestProperty("User-Agent", "Kairn/$currentVersion")
        try {
            if (c.responseCode != 200) return@runCatching null
            org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private class Asset(val url: String, val size: Long)

    /** The arm64 APK when the release has one, otherwise the first APK on it. */
    private fun apkAsset(assets: org.json.JSONArray?): Asset? {
        if (assets == null) return null
        val all = (0 until assets.length()).mapNotNull { i ->
            val a = assets.optJSONObject(i) ?: return@mapNotNull null
            val name = a.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) return@mapNotNull null
            val url = a.optString("browser_download_url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Triple(name, url, a.optLong("size", 0L))
        }
        if (all.isEmpty()) return null
        val chosen = all.firstOrNull { it.first.contains("arm64", true) } ?: all.first()
        return Asset(chosen.second, chosen.third)
    }

    /** "0.8.0" against "0.7.5": numeric on the first three parts, non-numeric parts ignored. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = semver(candidate) ?: return false
        val b = semver(current) ?: return false
        for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    private fun semver(text: String): IntArray? {
        val m = Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(text) ?: return null
        return intArrayOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    /**
     * Downloads [r] into the app's cache and returns the file only if it arrived whole. A short file is a
     * broken installer prompt, so it is deleted rather than handed to the user.
     */
    suspend fun download(
        ctx: android.content.Context,
        r: Release,
        onProgress: (Float) -> Unit = {},
    ): java.io.File? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dir = java.io.File(ctx.cacheDir, "updates").apply { mkdirs() }
        val target = java.io.File(dir, "kairn-${r.version}.apk")
        runCatching {
            val c = java.net.URL(r.url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Kairn")
            try {
                if (c.responseCode != 200) return@runCatching null
                val total = if (c.contentLengthLong > 0) c.contentLengthLong else r.size
                var written = 0L
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                var lastPct = -1
                c.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (total > 0) {
                                // Compose state is written from the UI thread only: the callback crosses back
                                // here, and only when the percentage actually moved.
                                val pct = ((written.toFloat() / total) * 100).toInt()
                                if (pct != lastPct) { lastPct = pct; main.post { onProgress(pct / 100f) } }
                            }
                        }
                    }
                }
                if (total > 0 && written != total) return@runCatching null
                target
            } finally {
                c.disconnect()
            }
        }.getOrNull().also { ok ->
            if (ok == null) runCatching { target.delete() }
        }
    }

    /**
     * Hands the APK to the system installer. Kairn does not install anything itself: the user sees the
     * normal "do you want to update" prompt, and Android only installs an APK signed by the same key.
     */
    fun install(ctx: android.content.Context, apk: java.io.File): Boolean = runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    }.getOrDefault(false)
}
