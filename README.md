# Kairn

**An EPUB reader for Android that keeps your library, your notes and your reading to yourself.**

Kairn reads the books you already own. No account, no subscription, no library to migrate into: point it
at a folder, or let it find the EPUBs already on your phone, and read.

## Why Kairn

**It cannot phone home.** Kairn is built without the INTERNET permission, so the app has no way to reach
the network at all — there is no tracking to switch off, no crash reporting to opt out of and no update
check to block. You can verify that in the app's own permissions instead of taking it on trust.

**Your data is files, not a database.** Reading positions, highlights, bookmarks, status and reading
settings are plain JSON in the app's own folder, one file per book. Nothing needs Kairn to open them.

**A moved book keeps its history.** Books are identified by their contents, not their location, so
renaming or moving a file keeps every highlight and your place in it.

**A backup that cannot leave you worse off.** Everything fits in one zip. Restore checks the archive
before it touches anything, takes a safety copy first, and says plainly what happened.

**Made for a phone screen.** Tap zones that follow the book's reading direction, optional volume-key page
turns, per-book text sizing, and a time-left estimate taken from the chapter's real word count.

## Features

**Reading** — page or scroll mode; per-book text size, line and paragraph spacing, margins, justification
and hyphenation; light, sepia, grey, dark and true-black themes; a warm-light filter; brightness that affects
this app only and never your system setting; full screen and keep-screen-on; a whole-book progress slider
with a time-left estimate for the whole book; two-page columns on a tablet only; table of contents;
footnotes in a pop-up, with a "back to where you were" chip.

**Library** — grid or list; search, sort, collections, tags and reading status; tick several books and
drop them into one collection at once; watched folders;
automatic detection of the EPUBs already on your device; covers read from the book file, with generated
covers for books that have none; "Open with" from any file manager.

**Annotations** — highlights in four colours and bookmarks, listed per book and exported as Markdown.

**Search** — full text, inside the book you are reading.

**Backup** — one zip, optionally including the book files themselves; merge or replace on restore, with a
safety copy every time.

## Privacy

No INTERNET permission, therefore no analytics, no crash reporting, no ads and no accounts. Nothing leaves
the device unless you export it yourself.

## Install

Sideload the APK for now — an F-Droid release is being prepared and the listing metadata is already in the
repository. Requires Android 8.0 or newer.

## What is coming

- Writing a note on a highlight
- Dictionary lookup for a selected word
- A choice of reading fonts, and importing your own
- Double-tap to zoom an image inside a book
- Sending books from a computer's browser over your own Wi-Fi
- Right-to-left and vertical text
- Tablet and landscape layouts
- Syncing a library between devices — the one-file-per-book format is designed for it

## Building

JDK 17+ and the Android SDK; `local.properties` needs `sdk.dir`. The Gradle wrapper is included.

    ./gradlew :app:assembleDebug        # gradlew.bat on Windows
    ./gradlew :app:testDebugUnitTest
    ./gradlew :app:assembleRelease      # unsigned; a store signs its own

The smaller details — the reader library's API traps, the performance work, and what each spec item maps
to — are in [SPEC_STATUS.md](SPEC_STATUS.md).

## Credits

Built on the [Readium Kotlin Toolkit](https://github.com/readium/kotlin-toolkit) (BSD-3-Clause) and
AndroidX (Apache-2.0). No proprietary dependencies and no Play Services.

## Licence

Apache-2.0 — see `LICENSE` and `NOTICE`.
