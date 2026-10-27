# Kairn

**An offline EPUB reader for Android. Your library, your notes and your place stay on your phone.**

Kairn reads the books you already own. No account, no subscription, nothing to import into: point it at a
folder, or let it find the EPUBs already on your device.

![Library](https://raw.githubusercontent.com/pham-noah/kairn-reader/main/fastlane/metadata/android/en-US/images/phoneScreenshots/01-library.png)
![Reading](https://raw.githubusercontent.com/pham-noah/kairn-reader/main/fastlane/metadata/android/en-US/images/phoneScreenshots/02-reader.png)

## What sets it apart

- **Nothing phones home.** The only network code is the LAN transfer server, and it only listens. No
  analytics, no crash reporting, no update check, no outgoing connection.
- **Your data is files, not a database.** Positions, highlights, notes and settings are plain JSON in the
  app's folder, one file per book. Any text editor opens them.
- **A moved book keeps its history.** Books are identified by their contents, so renaming or moving a file
  keeps every highlight and your place in it.
- **A restore cannot leave you worse off.** One zip; the archive is validated before anything is touched, a
  safety copy is taken, and the app says plainly what happened.

## Features

**Reading** — page or scroll; per-book text size, spacing, margins, justification, hyphenation; five page
tones from light to true black; a warm-light filter; brightness for this app alone or from the system; full
screen; a whole-book progress slider with an honest time left; two-page columns on a tablet; contents;
footnote pop-ups with a "back to where you were" chip. Set in Manrope and Literata, both bundled.

**Library** — grid or list; search, sort, reading status; collections and tags you can rename or delete; add
several books to one collection at once; watched folders; a scan for the EPUBs already on your device;
generated covers for books without one; "Open with" from any file manager.

**Sending books** — a page you open from a computer on the same Wi-Fi. Drop EPUBs on it and they land in the
library. The address carries a four-digit code.

**Notes and highlights** — four highlight colours, notes typed on a selection and edited later, one bookmark
per chapter, a per-book list to jump from, an undo on every delete, Markdown export.

**Search** — full text, in the book you are reading, a page of results at a time.

**Backup** — one zip, optionally with the book files inside; merge or replace on restore.

Every user-facing string is a resource (counts are plurals), so a translation is a `values-xx/strings.xml`
away. Data is never localized: book titles, the Wi-Fi upload page and the annotation kinds stay as they are.

## Privacy

Nothing touches the network unless you start the Wi-Fi transfer. Even then Kairn only listens: your
computer's browser makes the request, the server lives only while that screen is open, and it needs the code
shown on screen. No analytics, no crash reporting, no ads, no account, no update check.

`INTERNET` is requested because a socket needs it even to listen. `MANAGE_EXTERNAL_STORAGE` is optional: it
lets the scan see EPUBs already on the device, and the folder picker covers everything else.

## Install

**Not in the main F-Droid repository yet.** The submission was closed on project age — reviewers want about
three months of active development, and the app was days old when it was filed. The same merge request can be
reopened once that history exists. A self-hosted F-Droid repository is the plan for the gap.

**Today**, build it from source; the recipe is below. Android 8.0 or newer.

## Building

JDK 17+ and the Android SDK; `local.properties` needs `sdk.dir`. The Gradle wrapper is included.

    ./gradlew :app:assembleDebug
    ./gradlew :app:testDebugUnitTest          # 49 JVM tests, no device needed
    ./gradlew :app:connectedDebugAndroidTest  # 4 on-device tests, needs a phone or emulator
    ./gradlew :app:lintDebug
    ./gradlew :app:assembleRelease            # unsigned; a store signs its own

[SPEC_STATUS.md](SPEC_STATUS.md) holds the engineering detail: Readium 3.0.0 API traps, the performance work,
and which spec item maps to what.

## Roadmap

Dictionary lookup · image zoom · right-to-left and vertical text · tablet layouts · library sync between
devices.

## Credits

[Readium Kotlin Toolkit](https://github.com/readium/kotlin-toolkit) (BSD-3-Clause) and AndroidX (Apache-2.0).
No proprietary dependencies, no Play Services.

## Licence

Apache-2.0 — see `LICENSE` and `NOTICE`.
