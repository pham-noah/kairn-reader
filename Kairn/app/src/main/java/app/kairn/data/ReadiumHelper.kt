@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package app.kairn.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.FileOutputStream

/** Thin wrapper over Readium (DEC-5, O-05: we never write our own EPUB engine). */
class ReadiumHelper(ctx: Context) {
    private val http = DefaultHttpClient()
    private val retriever = AssetRetriever(ctx.contentResolver, http)
    private val opener = PublicationOpener(
        publicationParser = DefaultPublicationParser(ctx, httpClient = http, assetRetriever = retriever, pdfFactory = null),
    )

    /** Returns null on any failure (N-04): callers show a message instead of crashing. */
    suspend fun open(uri: Uri): Publication? = runCatching {
        val url = AbsoluteUrl(uri.toString()) ?: return null
        val asset = retriever.retrieve(url).getOrNull() ?: return null
        opener.open(asset, allowUserInteraction = false).getOrNull()
    }.getOrNull()

    /**
     * The publication's word count, from its raw resources: no Content model, no HTML parse, no regex.
     * Run at import so every later open already knows it.
     */
    suspend fun countWords(pub: Publication): Int {
        var total = 0
        for (link in pub.readingOrder) {
            val res = pub.get(link) ?: continue
            val size = runCatching { res.length() }.getOrNull()?.getOrNull() ?: continue
            if (size <= 0L) continue
            val bytes = runCatching { res.read(LongRange(0, size - 1)) }.getOrNull()?.getOrNull() ?: continue
            total += Words.count(bytes)
        }
        return total
    }

    /** Writes the embedded cover AND a small pre-scaled thumbnail for the Library grid. */
    suspend fun saveCover(pub: Publication, store: Store, id: String) {
        runCatching {
            val bmp: Bitmap = pub.cover() ?: return
            FileOutputStream(store.coverFile(id)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            writeThumb(bmp, store.thumbFile(id))
        }
    }
}

/** Pre-scales a cover to a grid-sized thumbnail on disk, so the grid never decodes a full cover. */
fun writeThumb(bmp: Bitmap, target: java.io.File, width: Int = 320) {
    runCatching {
        val scale = width.toFloat() / maxOf(bmp.width, 1)
        val h = maxOf(1, (bmp.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(bmp, width, h, true)
        java.io.FileOutputStream(target).use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        if (scaled !== bmp) scaled.recycle()
    }
}
