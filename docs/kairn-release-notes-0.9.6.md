# Kairn 0.9.6

## This release is about not losing anything

Nothing new to learn, no new screen. Fifteen defects, found by attacking the app on purpose - corrupt files,
hostile names, a stalled network client, a restored archive that had no business being one - and each one is
closed. What follows is what you would actually notice.

## A merge restore no longer undoes your reading

"Merge with what is here" compared book records by which was read more recently, but wrote the reading
position straight over yours. Restore a week-old backup that way and every book you had read since jumped back
to the page it was on a week ago. Marks and notes were safe; your place was not. A merge now keeps whichever
side read more recently, and replace still takes the backup exactly as it is.

## The transfer page no longer freezes

One idle connection - a browser opening a socket before you drop a file is enough - held up the whole server
for twenty seconds, so an upload could appear to hang. Each connection now gets its own worker. A slow or
broken upload is answered instead of left waiting, and an upload that would leave the phone under 64 MB free is
refused before it starts rather than filling the disk the app lives on.

The four-digit code is also no longer worth guessing: after twenty wrong addresses each further one waits a
little longer, so walking all 10 000 takes tens of minutes instead of about one. A mistyped address costs
nothing, and a correct one resets it.

## Kindle clippings land on the right book

A book called "It" claimed clippings from Little Women, The Hobbit and White Teeth: the match was a plain
substring on the title, and "it" sits inside most titles. A short title now has to match exactly; longer ones
still match each other. Clippings that belong to no book in your library are still left alone.

## Three ways a file could reach past the library

- A restored record whose id was `../../something` wrote, and deleted, outside the app's data folder. Ids are
  content hashes; anything that is not one is now refused at the read and again at the write.
- A "backup" whose entries all pointed outside the archive validated, restored nothing, and then pruned the
  library as a replace. An archive now has to contain a real book record or settings, and no path that leaves
  the folder.
- A release tag is remote text and chose the download's file name. It no longer can.

## Things that could have crashed the reader

- A reading speed of 0 (only reachable from a hand-edited settings file) divided by zero inside the reader's
  own layout. Zero and negative speeds now mean "no estimate", and the speed is clamped when it is read.
- A table of contents nested tens of thousands deep took the process down with a stack overflow. The walk is
  iterative now and stops at 64 levels, which no real book approaches.
- Font size, line spacing, margins, warmth, brightness and speeds are clamped to what the sliders can produce,
  so a foreign record cannot ask the renderer for a 100 000 000 % font.
- A version tag of `9.9.99999999999999999` threw out of the update check; it now reads as "not newer".
- A write that could not land (a directory left where a temporary file goes) no longer fails a whole restore.

## Verification

195 JVM tests, 13 of them drawing the real screens - the library empty and with books, settings, add books,
and the reader in its normal, loading, failed-open, scroll and two-page states - so a screen that throws while
laying out fails the build. Lint is clean apart from newer-version notices for dependencies. Fifteen new
regression tests cover the fixes above, and the ones that reproduce a defect are named after it.

Not verified: no book was opened on a phone for this release. The reading experience itself - Readium's
rendering, read aloud, rotation - is unchanged in this version, but it was not exercised here.
