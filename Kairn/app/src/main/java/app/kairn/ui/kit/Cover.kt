package app.kairn.ui.kit

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue   // required by `by` delegation; never appears as an identifier
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairn.data.Book
import app.kairn.data.Status
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KFonts
import app.kairn.ui.theme.coverPalettes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A decoded cover plus the mtime of the file it came from, so a changed file is noticed. */
private class CachedCover(val bmp: ImageBitmap, val stamp: Long)

/**
 * Decoded covers, kept so a fling never re-decodes a bitmap for an item already seen.
 * Sized in KILOBYTES (1/8 of the app heap), not entries: the old 200-entry cap meant up to ~200 x 0.6-0.9 MB
 * bitmaps (well over 100 MB) on a big library. Keyed by FILE PATH, so the grid's thumbnail and the Continue
 * card's full cover are separate entries instead of fighting over one key.
 */
private val coverCache = object : android.util.LruCache<String, CachedCover>(((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt()) {
    override fun sizeOf(key: String, value: CachedCover) = (value.bmp.width * value.bmp.height * 4 / 1024).coerceAtLeast(1)
}

/**
 * Decodes a cover downsampled to roughly the size it is drawn at (the Library grid cell is ~310px).
 * The grid points at the small pre-scaled thumbnail when one exists; otherwise the full cover is
 * sampled down here. Either way the compositor never scales a 1000px bitmap into a grid cell.
 */
private fun decodeCover(file: File, maxDim: Int = 384): ImageBitmap? = runCatching {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
}.getOrNull()

/**
 * Round 4 `.cover`: 2:3, rounded 8/12/12/8, spine highlight, drop shadow, progress strip,
 * and a status badge (mint check = finished, amber = needs attention). Uses the embedded
 * cover if there is one, otherwise a generated gradient with the title.
 */
/**
 * Cover image. Takes the cover path as a String rather than a File: `File` is an unstable type to
 * Compose, so a File parameter made EVERY cover cell unskippable - and every cell in the grid is a
 * KCover. A String is stable, so the cell can skip when nothing changed. `fallbackPath` plays the
 * same role for the full-cover fallback.
 */
@Composable
fun KCover(
    book: Book, coverPath: String, modifier: Modifier = Modifier,
    compact: Boolean = false, showBadge: Boolean = true, fallbackPath: String? = null,
) {
    val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 12.dp, bottomEnd = 12.dp, bottomStart = 8.dp)
    val coverFile = remember(coverPath) { File(coverPath) }
    val fallbackFile = remember(fallbackPath) { fallbackPath?.let { File(it) } }
    val key = book.id
    // Show a cached bitmap instantly (no IO in composition), then re-check the file OFF the main thread:
    // if its mtime changed (new cover, restore, thumbnail arriving after the full cover) decode again.
    // The grid passes the small thumbnail; until it exists we fall back to the full cover, so a cell is
    // never blank on a first run. This replaces a cache keyed only by book id, which needed a process
    // restart to pick up a changed cover.
    val hit = remember(coverPath) { coverCache.get(coverPath) }
    val bmpState by produceState<ImageBitmap?>(initialValue = hit?.bmp, key1 = coverPath) {
        val loaded = withContext(Dispatchers.IO) {
            val src = if (coverFile.exists()) coverFile else fallbackFile
            if (src == null || !src.exists()) return@withContext null
            val stamp = src.lastModified()
            if (hit != null && hit.stamp == stamp) return@withContext hit      // still current
            decodeCover(src)?.let { CachedCover(it, stamp) }
        }
        if (loaded != null) { coverCache.put(coverPath, loaded); value = loaded.bmp }
    }
    val bmp = bmpState
    val (c1, c2) = coverPalettes[(book.title.hashCode().and(0x7fffffff)) % coverPalettes.size]
    // Memoize the fallback gradient: building a Brush per recomposition, per item, per frame is pure
    // allocation churn while scrolling.
    val fallbackGrad = remember(key, c1, c2) { Brush.linearGradient(listOf(c1, c2)) }
    Box(modifier.aspectRatio(2f / 3f).clip(shape)) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            Box(Modifier.fillMaxSize().background(fallbackGrad))
            Column(Modifier.fillMaxSize().padding(if (compact) 5.dp else 10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(book.title, fontFamily = KFonts.serif, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 7.sp else 12.sp, lineHeight = if (compact) 9.sp else 14.sp,
                    color = Color.White.copy(alpha = .94f), maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (!compact) Text(book.author, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = .7f), maxLines = 1)
            }
        }
        Canvas(Modifier.fillMaxSize()) { drawRect(Color.White.copy(alpha = .12f), Offset.Zero, Size(3.dp.toPx(), size.height)) }   // spine
        if (book.progress > 0f && book.status != Status.FINISHED && !compact) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = .35f))) {
                Box(Modifier.fillMaxWidth(book.progress.coerceIn(0f, 1f)).fillMaxHeight().background(K.mint))
            }
        }
        if (showBadge && !compact) {
            val badge = when { book.missing || book.broken -> Icons.Default.ErrorOutline to K.amber; book.status == Status.FINISHED -> Icons.Default.Check to K.mint; else -> null }
            if (badge != null) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(badge.second), contentAlignment = Alignment.Center) {
                Icon(badge.first, null, tint = if (badge.second == K.amber) Color(0xFF2B1C00) else K.mintInk, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * The Kairn mark used in-app (the Library header, the empty state, onboarding).
 *
 * Drawn to match kairn-mark.svg EXACTLY: same 96-unit coordinate space, same shapes, same colours.
 * The stones are ROUNDED RECTANGLES with rx = height/2 (capsules with straight top and bottom edges),
 * not ellipses - that is what the SVG's <rect rx="height/2"> produces. Each stone is rotated about
 * the SVG's own pivot (-2 about 48,72 / 3 about 49,50 / -5 about 51,28) and carries a vertical
 * gradient over its own top..bottom, exactly as the SVG's objectBoundingBox gradients do.
 *
 *   1. kairn-mark.svg / kairn-icon.svg   (brand art)
 *   2. res/drawable/ic_cairn.xml          (launcher icon foreground)
 *   3. this composable                    (in-app Canvas)
 * All three are the same drawing; change them together.
 */
@Composable
fun CairnMark(size: Int = 30, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size.dp)) {
        val u = this.size.minDimension / 96f   // SVG viewBox is 96x96
        // x,y,w,h,r and the rotation+pivot are the SVG's own values, in SVG units.
        fun stone(x: Float, y: Float, w: Float, h: Float, r: Float, top: Color, bottom: Color, deg: Float, px: Float, py: Float) {
            val brush = Brush.verticalGradient(listOf(top, bottom), startY = y * u, endY = (y + h) * u)
            rotate(degrees = deg, pivot = Offset(px * u, py * u)) {
                drawRoundRect(brush, topLeft = Offset(x * u, y * u), size = Size(w * u, h * u), cornerRadius = CornerRadius(r * u, r * u))
            }
        }
        // ground line: rect 18,85 60x3 rx 1.5
        drawRoundRect(Color(0xFF3A4046), topLeft = Offset(18f * u, 85f * u), size = Size(60f * u, 3f * u), cornerRadius = CornerRadius(1.5f * u, 1.5f * u))
        // bottom stone: rect 14,62 68x20 rx10, kb #F1F4F2 -> #BFC8CC, rotate(-2 48 72)
        stone(14f, 62f, 68f, 20f, 10f, Color(0xFFF1F4F2), Color(0xFFBFC8CC), -2f, 48f, 72f)
        // middle stone: rect 26,40 46x19 rx9.5, km #C9D0D4 -> #8F9AA0, rotate(3 49 50)
        stone(26f, 40f, 46f, 19f, 9.5f, Color(0xFFC9D0D4), Color(0xFF8F9AA0), 3f, 49f, 50f)
        // top stone: rect 38,20 26x17 rx8.5, kt #A6F0D8 -> #5FCBAA, rotate(-5 51 28)
        stone(38f, 20f, 26f, 17f, 8.5f, Color(0xFFA6F0D8), Color(0xFF5FCBAA), -5f, 51f, 28f)
    }
}
