# Kairn

**An offline EPUB reader for Android. Your library, your notes and your place stay on your phone.**

Kairn reads the books you already own. No account, no subscription, nothing to import into: point it at a
folder, or let it find the EPUBs already on your device.

![Library](https://raw.githubusercontent.com/pham-noah/kairn-reader/main/fastlane/metadata/android/en-US/images/phoneScreenshots/01-library.png)
![Reading](https://raw.githubusercontent.com/pham-noah/kairn-reader/main/fastlane/metadata/android/en-US/images/phoneScreenshots/02-reader.png)

## Why it is built this way

- **Nothing phones home.** No analytics, no crash reporting, no accounts. The LAN transfer server only
  listens. The one request Kairn makes is the update check you can switch off, and with it off the app opens
  no outgoing connection at all.
- **Your data is files, not a database.** Positions, highlights, notes and settings are plain JSON in the
  app's folder, one file per book.
- **A moved book keeps its history.** Books are identified by their content, so renaming or moving a file
  keeps every highlight and your place in it.
- **A restore cannot leave you worse off.** One zip, validated before anything is touched, with a safety
  copy taken first and a plain statement of what happened.

## Reading

- Page or scroll, with auto scroll at a speed you set per book. Auto scroll holds still while your finger is
  on the page and picks up when you lift it.
- Read aloud from where you are, and it carries on into the next chapter. Pause and resume land on the word
  you left; speed runs 0.6x to 2.0x per book. It marks the sentence it is speaking, and in page mode the
  page turns to keep up with it. Lock the screen and it keeps reading: a media-playback service holds the
  session so nothing is cut off mid-chapter. A sleep timer stops it after 15, 30 or 45 minutes, or at the end
  of the chapter, so a book at night does not read on until morning.
- Select a passage to copy it, highlight it, write a note on it, share it as plain text, or look a word up in
  whatever dictionary app you already have. Kairn bundles no dictionary and asks no server for one. Share and
  Look up sit in the selection toolbar itself, and either can be switched off in Settings.
- Typeface, text size, line and paragraph spacing, margins, justification and hyphenation - per book, with
  one action to put a book back on your defaults.
- Five page tones from light to true black, plus any custom page and ink pair. Warm light; brightness for
  this app alone or from the system; full screen.
- A whole-book progress slider, and a time left worked out from the book's real word count at your reading
  speed. Two-page columns on a tablet; contents; footnote pop-ups with a "back to where you were" chip.

## Library

Grid or list; a "Continue reading" shelf showing each book's progress and when you were last in it; search
over titles and over the text inside every book, cached after the first pass so a repeat search is instant;
sort, reading status, collections and tags you can rename or delete; add several books to one collection at
once; watched folders; a scan for the EPUBs already on your device; generated covers for books without one;
"Open with" from any file manager.

## Notes, highlights, backup

Six highlight colours, notes typed on a selection and edited later, one bookmark per chapter, a per-book
list to jump from, an undo on every delete, Markdown export. Kindle highlights come in from the Kindle's own
`My Clippings.txt`, placed by finding each quote in the book. A reading record keeps minutes per day, a
streak and the finished count - in `stats.json`, on the phone. Backup is one zip, optionally with the book
files inside; restore merges or replaces. Point Settings at a folder once and a backup lands there every few
days on its own, keeping the newest few.

## Sending books

Open the transfer page, then drop EPUBs on it from a browser on the same Wi-Fi. The address carries a
four-digit code, and nothing else on the network can reach it.

## Privacy

Nothing touches the network unless you start the Wi-Fi transfer or leave the update check on. The transfer
server only listens: the computer makes the request, the server lives only while that screen is open, and it
will not answer without the code shown on screen.

The update check is one request a day at most, to `api.github.com`, asking
[github.com/pham-noah/kairn-reader](https://github.com/pham-noah/kairn-reader) for its latest release. It
sends the app name and version as the user agent and nothing else - no install id, no device id, no usage.
It has its own switch in Settings, and with it off Kairn makes no outgoing request of any kind.

`INTERNET` is requested because a socket needs it even to listen. `MANAGE_EXTERNAL_STORAGE` is optional: it
lets the scan see EPUBs already on the device, and the folder picker covers everything else.

## Install

**The signed APK is on the [releases page](https://github.com/pham-noah/kairn-reader/releases).** Every release
from 0.8.2 on is signed with the same dedicated key, so it installs over the one before it and your library,
places and settings survive. (0.8.1 and earlier used a debug key, so the step from 0.8.1 to 0.8.2 needs one
uninstall first.) A store-signed build cannot install over these; take a backup in the app before switching.

Once installed, Kairn can update itself: Settings > About > "Check for updates" (on by default, once a day)
offers the new APK, downloads it, checks it against the size the release publishes, and hands it to
Android's own installer. An APK that is not signed with the same key will not install over this one.

**F-Droid:** not in the main repository yet. The submission
([!52993](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/52993)) was closed on project age; reviewers
want about three months of work behind an app, and Kairn was days old when it was filed. The same merge
request can be reopened once that history exists.

Android 8.0 or newer.

## Build and test

JDK 17+ and the Android SDK; `local.properties` needs `sdk.dir`. The Gradle wrapper is included. The app
builds against SDK 36 (Android 16) with AGP 8.11 and Kotlin 2.1.

    ./gradlew :app:assembleDebug
    ./gradlew :app:testDebugUnitTest          # 73 JVM tests, no device needed
    ./gradlew :app:connectedDebugAndroidTest  # 30 on-device tests, needs a phone or emulator
    ./gradlew :app:lintDebug
    ./gradlew :app:assembleRelease            # signed with the release key when one is present
    # Signing: put keystore.properties (storeFile, storePassword, keyAlias, keyPassword) in
    # ../Kairn-keys/ next to this repo, or point KAIRN_KEYSTORE_PROPS at it. Release assembly fails without a
    # key unless -PallowUnsigned is passed, which is what CI and F-Droid build with. The key is not in the
    # repo and must not be lost: only it can install over an existing install.

Every user-facing string is a resource and every count is a plural, so a translation is a
`values-xx/strings.xml` away. [SPEC_STATUS.md](SPEC_STATUS.md) holds the engineering detail: Readium 3.0.0
API traps, the performance work, and which spec item maps to what.

## What is next

Zoom into an image in the page · vertical text · text and Word files imported alongside EPUBs · a custom
typeface from your own font files (blocked on the Readium API, see SPEC_STATUS.md).

## Credits and licence

[Readium Kotlin Toolkit](https://github.com/readium/kotlin-toolkit) (BSD-3-Clause) and AndroidX (Apache-2.0).
No proprietary dependencies, no Play Services. Apache-2.0 - see `LICENSE` and `NOTICE`.
