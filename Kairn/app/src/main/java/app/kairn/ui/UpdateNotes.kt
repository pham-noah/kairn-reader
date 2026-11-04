package app.kairn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType

/**
 * DS-04: a release body is Markdown, and the sheet used to print it as Markdown - `## Reading`, `- a page turn`
 * and the pipe table from the published notes, all literally. It is parsed into a handful of blocks here and
 * drawn with the app's own type, so the update reads like the rest of Kairn.
 *
 * Deliberately small: headings, bullets, tables, paragraphs, and bold or code spans inside them. That is what
 * the release notes use; anything else falls through as a paragraph rather than turning into markup on screen.
 */
internal sealed interface NoteBlock {
    data class Heading(val level: Int, val text: String) : NoteBlock
    data class Paragraph(val text: String) : NoteBlock
    data class Bullet(val text: String) : NoteBlock
    data class Table(val rows: List<List<String>>) : NoteBlock
}

/** Splits a release body into blocks. Pure, so the sheet's layout never has to be trusted with the parsing. */
internal fun noteBlocks(markdown: String): List<NoteBlock> {
    val out = mutableListOf<NoteBlock>()
    var table = mutableListOf<List<String>>()
    var inCode = false

    fun flushTable() {
        if (table.isNotEmpty()) { out += NoteBlock.Table(table.toList()); table = mutableListOf() }
    }

    markdown.lineSequence().forEach { raw ->
        val line = raw.trimEnd()
        val trimmed = line.trim()

        // Inside a fence nothing is markup: the lines are shown as they are, or a `| x |` sample would
        // come out as a table.
        if (trimmed.startsWith("```")) { inCode = !inCode; flushTable(); return@forEach }
        if (inCode) { if (trimmed.isNotEmpty()) out += NoteBlock.Paragraph(trimmed); return@forEach }

        when {
            trimmed.isEmpty() -> flushTable()

            trimmed.startsWith("|") && trimmed.endsWith("|") -> {
                val cells = trimmed.trim('|').split("|").map { it.trim() }
                // The separator row (|---|---|) is layout, not a row of text.
                if (cells.none { it.isNotEmpty() && it.all { c -> c == '-' || c == ':' } }) table += cells
            }

            trimmed.startsWith("#") -> {
                flushTable()
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
                val text = trimmed.drop(level).trim()
                if (text.isNotEmpty()) out += NoteBlock.Heading(level, text)
            }

            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                flushTable()
                out += NoteBlock.Bullet(trimmed.drop(2).trim())
            }

            else -> {
                flushTable()
                out += NoteBlock.Paragraph(trimmed)
            }
        }
    }
    flushTable()
    return out
}

/** A piece of one line: bold and code are the only inline markup a release body needs. */
internal data class NoteRun(val text: String, val bold: Boolean = false, val code: Boolean = false)

/** Splits one line into runs, **bold** and `code` included. Unterminated markers stay literal. */
internal fun noteRuns(text: String): List<NoteRun> {
    val out = mutableListOf<NoteRun>()
    var i = 0
    val plain = StringBuilder()

    fun flush() {
        if (plain.isNotEmpty()) { out += NoteRun(plain.toString()); plain.clear() }
    }

    while (i < text.length) {
        val bold = text.startsWith("**", i) && text.indexOf("**", i + 2) > i + 2
        val code = !bold && text[i] == '`' && text.indexOf('`', i + 1) > i + 1

        if (bold) {
            flush()
            val end = text.indexOf("**", i + 2)
            out += NoteRun(text.substring(i + 2, end), bold = true)
            i = end + 2
        } else if (code) {
            flush()
            val end = text.indexOf('`', i + 1)
            out += NoteRun(text.substring(i + 1, end), code = true)
            i = end + 1
        } else {
            plain.append(text[i]); i++
        }
    }
    flush()
    return out
}

internal fun annotated(text: String): AnnotatedString = buildAnnotatedString {
    noteRuns(text).forEach { run ->
        val start = length
        append(run.text)
        if (run.bold) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
        if (run.code) addStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = K.mint), start, length)
    }
}

/** Draws the blocks in the sheet's own type. No Markdown reaches the screen. */
@Composable
internal fun NoteBlocks(blocks: List<NoteBlock>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            val gap = if (index == 0) 0.dp else when (block) {
                is NoteBlock.Heading -> 14.dp
                else -> 8.dp
            }
            when (block) {
                is NoteBlock.Heading -> Text(
                    annotated(block.text),
                    style = KType.title.copy(color = K.txt),
                    modifier = Modifier.padding(top = gap),
                )

                is NoteBlock.Paragraph -> Text(
                    annotated(block.text),
                    style = KType.small.copy(color = K.mut),
                    modifier = Modifier.padding(top = gap),
                )

                is NoteBlock.Bullet -> Row(Modifier.padding(top = gap)) {
                    Text("•", style = KType.small.copy(color = K.mint), modifier = Modifier.width(14.dp))
                    Text(annotated(block.text), style = KType.small.copy(color = K.mut))
                }

                is NoteBlock.Table -> Column(Modifier.padding(top = gap)) {
                    block.rows.forEach { row ->
                        Text(
                            row.joinToString("   "),
                            style = KType.small.copy(color = K.mut, fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
