package app.kairn.ui

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import org.readium.r2.navigator.util.BaseActionModeCallback

/**
 * The text-selection menu (the little toolbar after a long-press).
 * Readium delegates the whole menu to the app - BaseActionModeCallback leaves onCreateActionMode abstract - so this supplies Copy *and* Kairn's highlight colours.
 * The callback must leave the ActionMode open until [onHighlight] has read the selection, because finishing the mode clears the WebView selection.
 */
class HighlightActionModeCallback(
    private val colors: List<Pair<String, Int>>,          // (label, ARGB)
    private val copyLabel: String,
    private val noteLabel: String,
    private val onCopy: () -> Unit,
    private val onHighlight: (color: Int, done: () -> Unit) -> Unit,
    private val onNote: (done: () -> Unit) -> Unit,
) : BaseActionModeCallback() {

    private val byItemId = HashMap<Int, Int>()            // menu item id -> ARGB

    /** A small rounded colour swatch, so the menu shows the actual colours, not just names. */
    private class Swatch(private val color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        override fun draw(canvas: Canvas) {
            val r = RectF(bounds)
            val radius = minOf(r.width(), r.height()) * 0.28f
            canvas.drawRoundRect(r, radius, radius, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth() = 66
        override fun getIntrinsicHeight() = 66
    }

    // SHOW_AS_ACTION_ALWAYS is the point: the colour swatches must be visible without the overflow menu.
    @SuppressLint("AlwaysShowAction")
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.add(Menu.NONE, COPY_ID, 0, copyLabel).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        // A note is the other thing worth doing to a selection, so it sits with Copy rather than in an
        // overflow menu the reader would have to find.
        menu.add(Menu.NONE, NOTE_ID, 1, noteLabel).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        colors.forEachIndexed { i, (label, argb) ->
            val id = Menu.FIRST + 100 + i
            byItemId[id] = argb
            // The label carries the real colour (not just a name): the text is drawn on its own
            // highlight tint, so the menu shows the actual colour even where icons are ignored.
            val title = android.text.SpannableString(label).apply {
                setSpan(android.text.style.BackgroundColorSpan(argb), 0, label.length, 0)
                setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.BLACK), 0, label.length, 0)
            }
            val item = menu.add(Menu.NONE, id, 100 + i, title)
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            item.icon = Swatch(argb)
        }
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.itemId == COPY_ID) {                        // copy, then close the menu
            onCopy(); mode.finish(); return true
        }
        if (item.itemId == NOTE_ID) {                        // open the editor, then close the menu
            onNote { mode.finish() }; return true
        }
        val argb = byItemId[item.itemId] ?: return false
        onHighlight(argb) { mode.finish() }
        return true
    }

    private companion object { const val COPY_ID = 0x4B410001; const val NOTE_ID = 0x4B410002 }

}
