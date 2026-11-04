package app.kairn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DS-04: the update sheet used to print the release body as Markdown - `## Reading`, `- a page turn` and the
 * pipe table all literal on screen. These hold the parser that replaced it, and the sheet draws only its blocks.
 */
class UpdateNotesTest {

    @Test fun `headings lose their hashes and keep a level`() {
        val blocks = noteBlocks("# Kairn 0.9.0\n\n## What this release is\n\n### Detail\n")
        assertEquals(3, blocks.size)
        assertEquals(NoteBlock.Heading(1, "Kairn 0.9.0"), blocks[0])
        assertEquals(NoteBlock.Heading(2, "What this release is"), blocks[1])
        assertEquals(NoteBlock.Heading(3, "Detail"), blocks[2])
    }

    @Test fun `bullets become bullets`() {
        val blocks = noteBlocks("- one\n* two\n+ three\n")
        assertEquals(listOf(NoteBlock.Bullet("one"), NoteBlock.Bullet("two"), NoteBlock.Bullet("three")), blocks)
    }

    @Test fun `a markdown table is a table, separator row and all`() {
        val body = """
            | | 0.8.6 | 0.9.0 |
            |---|---|---|
            | `ReaderActivity` | 973 lines | 822 lines |
        """.trimIndent()
        val blocks = noteBlocks(body)
        assertEquals(1, blocks.size)
        val table = blocks.single() as NoteBlock.Table
        assertEquals(2, table.rows.size)                      // the |---| row is layout, not a row
        assertEquals(listOf("", "0.8.6", "0.9.0"), table.rows[0])
        assertEquals(listOf("`ReaderActivity`", "973 lines", "822 lines"), table.rows[1])
    }

    @Test fun `prose stays prose and blank lines separate`() {
        val blocks = noteBlocks("First paragraph.\n\nSecond one,\nwrapped.\n")
        assertEquals(
            listOf(NoteBlock.Paragraph("First paragraph."), NoteBlock.Paragraph("Second one,"), NoteBlock.Paragraph("wrapped.")),
            blocks,
        )
    }

    @Test fun `code fences are markers, not content`() {
        val blocks = noteBlocks("```\n| not a table |\n```\n")
        assertTrue(blocks.none { it is NoteBlock.Table })
    }

    @Test fun `bold and code are split into runs`() {
        val runs = noteRuns("a **b** c `d` e")
        assertEquals(
            listOf(
                NoteRun("a "),
                NoteRun("b", bold = true),
                NoteRun(" c "),
                NoteRun("d", code = true),
                NoteRun(" e"),
            ),
            runs,
        )
    }

    @Test fun `an unterminated marker stays literal`() {
        assertEquals(listOf(NoteRun("**not bold")), noteRuns("**not bold"))
        assertEquals(listOf(NoteRun("a ` b")), noteRuns("a ` b"))
    }

    @Test fun `the published release body renders without a hash or a pipe`() {
        val body = """
            # Kairn 0.9.0

            ## What this release is

            0.9.0 is 0.8.6 with the same behaviour and a different shape. Not one string changed.

            ## What moved

            | was | is now |
            |---|---|
            | preference settling | `ui/ReaderPrefsApplier.kt` |

            **Item 6 - `ReaderSpeech`** (180 lines).
        """.trimIndent()
        val blocks = noteBlocks(body)
        assertTrue("no heading keeps its hashes", blocks.filterIsInstance<NoteBlock.Heading>().none { it.text.startsWith("#") })
        assertTrue("no paragraph starts with a pipe", blocks.filterIsInstance<NoteBlock.Paragraph>().none { it.text.startsWith("|") })
        assertEquals(1, blocks.filterIsInstance<NoteBlock.Table>().size)
        assertTrue(blocks.filterIsInstance<NoteBlock.Heading>().any { it.text == "What moved" })
    }
}
