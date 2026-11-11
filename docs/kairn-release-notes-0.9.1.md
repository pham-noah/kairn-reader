# Kairn 0.9.1

Two things were wrong with the update sheet, and both are fixed here.

## The notes were printed as Markdown

A release body arrives as Markdown - `## Reading`, `- a page turn`, and the `| was | is |` table the notes have
used since 0.8.6. The sheet drew that string in one `Text`, so the reader was shown the markup itself: hashes,
dashes and pipes, truncated at 500 characters mid-word.

`ui/UpdateNotes.kt` parses the body into blocks - headings, bullets, tables, paragraphs - and the sheet draws
them with the app's own type. `**bold**` and `` `code` `` are rendered as style rather than characters, tables
line up in a monospace grid, and a fenced block is shown as it is instead of being read as a table. The notes
scroll inside a bounded area, so a long body cannot push the buttons off the sheet, and nothing is cut at 500
characters any more.

## The sheet shrank when the download started

The action row was content-sized: the "Download" button is about 52dp, the progress row that replaces it about
40dp, and the sheet is anchored to the bottom edge - so the moment a download began, the sheet got shorter and
its top edge jumped down. The reader had to drag it back up to see what was happening.

The action area is one slot with a minimum height now (68dp), and the release body sits in a bounded, scrollable
area above it, so the sheet keeps its size from "Download" to "Downloading" to "Install".

## Verification

- 132 JVM tests, including eight for the parser: headings lose their hashes, bullets become bullets, a table
  keeps its rows and drops the `|---|` separator, a fence is a marker, and an unterminated `**` stays literal.
- Two on-device tests on the sheet itself: the notes contain no `## `, `|---` or `- **` anywhere on screen, and
  the sheet's top edge does not move (within 1dp) when `downloading` flips.
- Signed with the same CN=Kairn key, so it installs over 0.9.0 in place and keeps everything.
