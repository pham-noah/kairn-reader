# Kairn 0.8.6

## Reading
- A page turn writes only your place now, not the whole book's notes and highlights, so turning pages stays
  quick in a heavily annotated book.
- The reading bar redraws less: the time left and the slider no longer pull the whole screen with them.

## Your books
- Importing a file whose name matches a book you already have gives it its own copy. Before, the second file
  could replace the first book's file in place, and the library would show the old title over the new text.
- Restore reads a backup whole - every entry, checked against its own checksum - before it removes anything.
  A backup cut short by a full disk or a closed app is refused instead of deleting records it cannot put back.
- A "replace" restore removes only the records the backup does not contain, and never before the restore
  succeeded. Book files inside a backup are streamed to disk instead of being held in memory.

## Search and notes
- A library search reads each book's text once per query instead of two or three times.
- In a book with hundreds of highlights, adding or removing one no longer re-reads every highlight's position.
- Read aloud follows the voice without rebuilding the page map three times a second.

## Settings and reach
- Page tones scroll, so the darkest tone is reachable on a narrow phone, and every tone is named for a screen
  reader instead of being an unlabelled circle.
- The time-left label is a proper button; small buttons and the toast action meet the 48dp touch minimum.
- The selection bar says how many books you picked on its own line instead of clipping it, and a bulk action
  can no longer reach a book your filter hid.
- A book with no table of contents says so, the notes list tells you when a filter matched nothing, and the
  Backup screen opens on the actions instead of last run's card.

The APK is signed with Kairn's release key, so it installs over 0.8.5 and keeps your library. An F-Droid build
signed with their key cannot install over it.
