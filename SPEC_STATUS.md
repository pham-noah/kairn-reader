# Kairn — implementation status and engineering notes

The product description lives in [README.md](README.md). This file is the technical side: what is built
against which spec item, what is not, and the things that will bite anyone touching the code.

## What is built, by spec ID

| Built | ID |
|---|---|
| EPUB open + render via Readium | R-01 |
| Page / scroll mode | R-03 |
| Font size, line + paragraph spacing, margins, justify, hyphenation (live) | R-04 (partly) |
| Typeface choice: the publication's own, or the system serif, sans or mono | R-04 (partly) |
| Custom page and ink pair, laid over any theme, per book | R-05 |
| Light / sepia / grey / dark / true black, dark default, follow-system option | R-05, DEC-4 |
| Warm-light overlay and in-app brightness (system settings untouched) | R-06 |
| Tap zones (reading-direction aware), volume keys, chapter list, progress slider | R-07 (partly) |
| Footnote pop-up, "jump back" chip | R-08 |
| Position saved continuously and on pause, restored on open | R-09 |
| Per-book reading settings | R-10 |
| Full-screen, keep-screen-on | R-11 |
| Watched folders (per-folder switch), missing-not-removed, picker import, "Open with" | L-01, L-02, L-03, L-05 |
| Auto-detect: sweeps the device for EPUBs, like Lithium (settings toggle) | L-01 |
| Whole-book time left, fine-grained seek (by position, real word-count estimate) | R-07 |
| Reading speed setting, so the time left uses your own pace | R-07 |
| Auto scroll at a per-book speed; holds still while a finger is on the page | R-03 |
| Read aloud: the chapter on screen from where you are, pause and resume, speed, on into the next chapter | — |
| Two-page column mode (per book, tablet width only) | R-14 |
| Covers + metadata from the file; edit title/author | L-06 |
| Grid/list, search, sort; search over titles and over the text inside every book | L-07 |
| Collections, tags, status; rename and delete a collection or a tag | L-08 |
| Highlights (six colours), notes (written and edited), bookmarks, per-book list with jump, undo on delete, Markdown export | A-01, A-02, A-03 |
| Full-text search in book, a page of results at a time | S-01 |
| Backup (one zip, optional book files) and restore (safety copy, merge or replace) | DS-01, DEC-12 |
| Bad/missing file messages, never a crash | N-04 |
| Wi-Fi transfer: a LAN-only upload page, running only while its screen is open | L-04 |
| Share a passage, and look a word up in the reader's own dictionary app | A-04 |
| Sleep timer for read aloud: 15/30/45 minutes, or the end of the chapter | R-15 |
| "Continue reading" shelf: the last eight books, newest first | L-09 |
| Reading record: minutes a day, a streak, the finished count, in `stats.json` | DS-02 |
| Kindle `My Clippings.txt` import, each quote placed by searching the book | DS-03 |
| Update check, APK download and hand-off to the system installer | DS-04 |
| Read aloud with the screen off, behind a media-playback foreground service | R-15 |
| Automatic backup on a schedule into a chosen folder, newest few kept | DS-01 |
| Search cache: book text dumped once, repeat searches grep disk | S-01 |
| No analytics, no crash reporting, all data in open JSON | N-01..N-02, DEC-8 |

## Not built yet

R-04 custom font import (blocked: see below) · R-12 RTL/vertical verification (partly) · R-13 image zoom ·
a bundled dictionary *card* (DEC-13: the selection menu hands the word to a dictionary app instead) · all
v1.x items (X-01…X-20). Bundled Manrope + Literata (OFL, licence texts in
`licences/`) and the signed minified release path both exist; F-Droid supplies its own signature.

## DS-03: Kindle clippings are placed by search, not by location

A Kindle "Location 631-633" points into a Kindle rendering of the file and means nothing inside the EPUB -
Readium would put the highlight on whatever text happens to sit at that progression, which for a different
edition is a different paragraph. So [ClippingImport](Kairn/app/src/main/java/app/kairn/data/ClippingImport.kt)
runs the reader's own `Publication.search()` per clipping, longest query first (60 chars, then 30, then 15),
and the first hit's locator becomes the mark's locator. A quote that is not found is counted and NOT placed:
an imported highlight on the wrong page is worse than one that did not arrive.

Costs, measured rather than assumed: one book open plus up to three searches per clipping, all on
`Dispatchers.IO`, with `(done, total)` reported as it goes. Deduplication is by normalised quote, so running
the same file twice adds nothing. Author matching is token-based because Kindle writes "Le Guin, Ursula K."
where an EPUB says "Ursula K. Le Guin" - an in-order substring test rejects the right book, which is exactly
what the first test run showed.

## DS-04: the update check is the only request Kairn makes on its own

`data/Update.kt` asks `api.github.com/repos/pham-noah/kairn-reader/releases/latest`, compares the tag against
`versionName` numerically (a string compare puts 0.10.0 before 0.9.0), picks the arm64 APK when the release
has one, then downloads into `cacheDir/updates` and accepts the file only when the byte count matches the
asset's published size. Installation is `ACTION_VIEW` on a FileProvider URI: Android's own installer shows
the prompt, and the app never installs anything silently.

Three consequences worth keeping in mind:

- `look()` returns Found / Current / Failed, because "you are up to date" is a different answer from "could
  not reach GitHub" and the sheet must not say the first when it means the second.
- The check runs at most once a day (`AppSettings.lastUpdateCheck`), 2.5 s after the library has loaded so it
  never delays the first paint, and offers a new release as a toast with an action - not a sheet over the
  library. The manual check in Settings opens the sheet.
- F-Droid forbids an app that updates itself. `res/values/bools.xml` carries `self_update`; a store build sets
  it false and the check, the rows and the toast all disappear - there is no request to switch off.

## R-15: the sleep timer belongs to the voice

`ReaderUiState.sleepChoice` is the single source: 0 = off, a positive number = minutes, -1 = end of chapter.
The Activity owns one coroutine for the countdown and one rule that matters - `advanceWithTts()` checks it
BEFORE paging into the next chapter, because a chapter that ends while the timer says "end of chapter" must
stop, not carry on. Stopping the voice by hand clears the timer, so a chip can never promise to stop
something that is already stopped.

## R-04 custom font import is blocked by Readium 3.0.0, not by effort

`EpubNavigatorFragment.Configuration.fontFamilyDeclarations` exists, and `EpubPreferences.fontFamily` takes a
`FontFamily` (a value class over String, which is why javap shows it as a String). But
`FontFamilyDeclaration`, `FontFaceDeclaration` and `FontFaceSource` are all **internal** in 3.0.0 - the
constructors are in the bytecode and the Kotlin compiler refuses them ("Cannot access 'data class ...' it is
internal"). So an app cannot declare its own @font-face through the API. The only route left is injecting a
`<style>` with `evaluateJavascript` on every chapter load, which means re-injecting on each chapter change and
accepting a flash of unstyled text. Do not start R-04 without choosing that route first.

## R-12 RTL: verified, with one caveat about test books

Verified on an emulator with a 7,200-word Arabic EPUB (`dir="rtl"`, `page-progression-direction="rtl"`):
it opens and renders right-to-left, `rtl` is read from `metadata.readingProgression`, and the tap zones honour
the direction - the LEFT edge goes FORWARD (30 min -> 28 -> 26 -> 24 across taps) while the same edge goes
backward in an LTR book (where the right edge pages forward: 5 h 25 -> 5 h 24 -> 5 h 22).

The caveat worth remembering: a test book whose text is one sentence repeated compresses ~27x, so Readium
reported THREE positions for the whole thing and NO tap appeared to page. That looked like a real RTL bug and
was an artefact of the fixture. Build RTL fixtures from a varied word pool - then you see 15 positions and the
pages turn. Still unverified: vertical text (not built) and two-page/scroll mode under RTL.

## R-13 image zoom: two attempts, not shipped, and what is now proven

The feature is NOT in the tree. Both attempts ran on a device; neither could be confirmed, and the second one
was not even reproducible. Start the next attempt from these facts, all measured rather than assumed:

- **`EpubNavigatorFragment.evaluateJavascript` must be called on the MAIN dispatcher.** Wrapped in
  `withContext(Dispatchers.IO)` it throws and returns `null` - which looked exactly like "the DOM found no
  image" for two full build cycles. The scroll poll never left the main thread either.
- `TapEvent` carries only a `PointF`; the navigator API has no image information.
- The canvas trick is a dead end: `toDataURL` throws on a tainted canvas, which is what an image served from
  Readium's internal scheme gives you.
- `document.elementFromPoint(x, y)` returns the PARAGRAPH box for an inline image; `closest('img')` only
  climbs ancestors and can never find a picture inside a `<p>`. `document.elementsFromPoint` DOES return the
  stack (`tags: img|p|body|html` in the probe) - but in the next identical run, at the same point, it
  returned no `img` at all, and when it did the element had an EMPTY `src` (`any:1`, `nw:0`). So the hit test
  is not stable across a restored reading position and image load timing. That instability is the real
  blocker, not the lookup mechanism.
- The bytes should come from the publication, not the DOM: `Url(imgSrc)` + `publication.get(url)` +
  `read(LongRange(0, size - 1))`, the same path the word counter uses.
- Fixtures: build the picture with bright, asymmetric content. A dark-mode screenshot used as the book's image
  is indistinguishable from the reader's own black page, which cost a cycle.

## Audit items closed by measurement, not by opinion

- **Compose skippability** (`app/build/compose_compiler/app_debug-composables.csv`, from a full Kotlin
  recompile - an incremental build only reports the files it recompiled, so delete `app/build/kotlin-classes`
  and `app/build/compose_compiler` first): **47 of 50 composables are skippable**. The three that are not are
  `Header` (a local function inside LibraryScreen), `<get-glass>` (a property getter) and `ambientWash` (a
  Modifier factory) - none of them a component with parameters to compare. The "Header cannot skip" item in
  the old audit is therefore a non-issue, and there is nothing left to win here.
- **N-05, strings**: every UI string is a resource; counts are plurals; the reader chrome, search states,
  settings groups, sort/status labels, add-books states and onboarding are all covered.
- **R-12, RTL**: verified on a device with a real Arabic EPUB - it renders right-to-left and the LEFT edge
  pages forward (30 -> 28 -> 26 -> 24 min), while an LTR book pages forward on the right edge.
- **Dead affordances**: the About sheet no longer advertises dictionary licences for a feature that does not
  exist, and Settings no longer carries a "Language - English" row with nothing behind it.

## Verifying UI on the emulator: what does NOT work (measured 2026-10-27)

Text-driven UI checks against the reader are unreliable on this AVD, which is why three UI features were
reverted rather than shipped:

- `adb shell input text "..."` puts roughly TWO characters into the reader's search field and then stops;
  `adb shell input keyevent 48/36/33` does not reach it at all. The sheet's EditText loses IME focus after the
  first keystroke, so the field never reaches the three characters a search needs.
- The Compose-test route bypasses the IME and DOES compile, but it needs three things that are easy to get
  wrong: `androidTestImplementation(composeBom)` (the BOM in `implementation` does NOT version the test
  artifacts - "Could not find androidx.compose.ui:ui-test-junit4:."), `androidx.compose.ui:ui-test-junit4` plus
  `debugImplementation("androidx.compose.ui:ui-test-manifest")`, and a `Locator` for the fixture needs
  `mediaType = MediaType.XHTML` because that parameter is NON-null ("Null cannot be a value of a non-null type
  MediaType").
- **CORRECTION:** the "Starting 0 tests ... Instrumentation run failed due to Process crashed" that ended that
  attempt is ENVIRONMENTAL, not the Compose library. It reproduces on the plain suite too: `connectedDebugAndroidTest`
  run against an AVD that has just had the app uninstalled fails at discovery, while
  `adb shell am instrument -w app.kairn.test/androidx.test.runner.AndroidJUnitRunner` on the same build answers
  `OK (6 tests)` in 17 s. Installing both APKs once (`adb install -r app-debug.apk` then
  `app-debug-androidTest.apk`) and re-running Gradle then gives 6/6 too. So the Compose test route was never
  proven broken. Retried with the prerequisites above: `SearchSheetTest` passes and the chapter-labelled search
  results are SHIPPED, guarded by that test. The suite is 8 device tests.

## The device suite has been mutation-checked

All 18 device tests passed on their first run, which is exactly when to distrust them. (That was the count at
the time; the suite is 27 now and the same rule applies.) Three behaviours were
broken on purpose, in one build, and the suite was run (mutation testing):

| broken on purpose | test that caught it |
|---|---|
| the search sheet stops printing the chapter name | `SearchSheetTest.a_result_names_the_chapter_it_is_in` |
| the library's status filter ignores `b.status` | `LibraryScreenTest.the_status_filter_keeps_only_the_matching_books` |
| the Two-pages segment writes `twoPage = it == 0` | `DisplaySheetTest.choosing_two_pages_emits_a_pref_with_two_pages` |

Result: `Tests run: 18, Failures: 3` - exactly those three, nothing else, and every other test still passed.
The mutations were reverted and the suite is `OK (18 tests)` again. Repeat this after adding tests: a test that
cannot fail is worse than no test, and a green suite on the first attempt proves nothing on its own.

## The 0.8.0 device tests were mutation-checked too

Three tests were added for the new surfaces and run on the emulator: `ContinueShelfTest` (a book being read
appears twice - shelf and grid - while a never-opened book appears once), `StatsSheetTest` (today, the week
and the streak are three different sums of the same map, so a swapped pair cannot pass) and
`ClippingImportDeviceTest`, which imports into a REAL EPUB and asserts the mark's href is the chapter the
quote is actually in.

Two behaviours were broken on purpose in one build - placement returning null, and the dedupe disabled - and
the suite answered `Tests run: 3, Failures: 2`, exactly the placement and re-import tests, with the
unmatched-book test still green. Reverted, the suite is `OK (27 tests)`.

Two traps that cost a run each: a `.txt`-less fixture lives in the TEST APK's assets, so `targetContext.assets`
answers FileNotFoundException and `InstrumentationRegistry.getInstrumentation().context.assets` is the one
that works; and `addBook` takes the title from the EPUB's metadata, not from the name it was imported under,
so a clipping has to be matched against `store.books.value.single().title` rather than the fixture's filename.

## Features added 2026-10-27 (the nine-item batch)

- **Selection mode does more than file books**: the bar also marks the picked books finished and removes them
  (behind a confirm dialog - a mis-tap would take the notes with it).
- **Sort has a direction**: an arrow beside the sort label reverses whatever the natural order of that sort is.
- **Collections are one tap from the library**: a chip row of every collection in the header sets the shelf
  filter (`onShelf`, which replaced `onClearShelf` in `LibraryScreen`'s signature).
- **Search has a scope**: "This chapter only" filters hits to the resource you are reading. It is a client-side
  `href` filter, not a second search.
- **Tap the time left** to switch between whole-book and chapter remaining (`ReaderLogic.minutesLeftInChapter`,
  from the chapter's own word count and its `locations.progression`).
- **Notes can be searched and sorted by position** instead of by date created: reading order is what reviewing a
  book wants.
- **The progress slider is labelled for TalkBack** (`KSlider(description, stateText)`); it announced nothing before.
- **A backup nudge** appears in the library when the last backup is over 30 days old, and "Later" is stored in
  `AppSettings.lastNudge` so it does not nag.

## Known gaps — read these before trusting the list above

- **N-05 is done.** Every user-facing UI string resolves through `res/values/strings.xml` (~290 entries,
  10 `<plurals>`), including the reader chrome, the search states, the settings group titles and the
  selection menu. Three things are deliberately NOT resources: a book's stored fallback title, the HTML
  upload page the Wi-Fi server serves, and the highlight palette names in `Tokens.kt` (the menu labels
  them from resources, in palette order).
- **Notes are written from the selection menu.** Select text, tap Note, type. A note is a `Mark` with
  `kind = "note"`, so it lists, jumps, deletes (with a confirm) and exports like a highlight.
- **The manifest carries `INTERNET`** (L-04). A listening socket needs it. No network request is made at
  any point unless the user starts Wi-Fi transfer, and even then the app only listens — the browser on the
  computer is the client. The server is bound only while the Add books screen is open. `ACCESS_NETWORK_STATE`
  is separate and comes from `media3-exoplayer`, pulled in by Readium's navigator.
- **The transfer server is code-gated by default.** The address the phone shows carries a random four-digit
  segment (`/4821`); every other path answers 404. Turning "Require a code" off opens it to anything on the
  network that can reach the port. Either way it exists only while the Add books screen is open.
- **Fonts are bundled** (Manrope for the interface, Literata for book text), both variable, both OFL;
  licence texts in `licences/`. Readium still renders the book with its own font unless the app passes
  one, so book text uses Literata only where the reader draws it (the quote style and covers).
- **`ensureThumbs`** in `Library` backfills thumbnails for books that lack one. Import already writes a
  thumbnail, and a cell falls back to the full cover, so this is an optimisation for older records, not a
  correctness path.

## Notes for anyone touching the reader (Readium Kotlin Toolkit 3.0.0)

Scroll-mode chapter flicks may only START on the book: the top/bottom bars are Compose content in the same
window, so an unfiltered `dispatchTouchEvent` swallowed a drag on the seek slider and paged a chapter
instead of seeking (`ReaderUiState.chromeTopPx` is the bar's top edge; nothing under it arms a swipe). The
sheets are their own Dialog window, so their sliders were never affected.

Verified against the real 3.0.0 artifacts, not from memory:

- `EpubPreferences(spread = Spread.ALWAYS)` **throws** on a reflowable EPUB. Two-page is
  `columnCount = ColumnCount.TWO` alone.
- `R2BasicWebView` overrides `onScrollChanged`, so `setOnScrollChangeListener` never fires and
  `WebView.getScrollY()` is always 0. Use `EpubNavigatorFragment.evaluateJavascript(...)` — the navigator
  exposes it as a suspend function; do not walk the view tree looking for a WebView.
- `Publication.positions()` is the slow part of opening a book and must run on `Dispatchers.IO`. So must
  the book parse in `ReadiumHelper.open`: neither `AssetRetriever` nor `PublicationOpener` switches
  dispatcher itself.
- A disk cache for positions was tried and removed — it was slower than recomputing.
- `EpubNavigatorFragment.Listener` requires `onExternalLinkActivated`.
- Readium's `TextAlign` and `Color` clash with Compose's; alias both imports.
- `Metadata.readingProgression` is the current property; `effectiveReadingProgression` is deprecated.
- `Publication.search()` returns a non-null `SearchIterator` yielding `Try<LocatorCollection>`.
- The selection menu: Readium delegates the whole `ActionMode`, so implement `onCreateActionMode`
  yourself — the platform's Copy is not added for you, and the mode must stay open until
  `currentSelection()` has been read.

## Performance work already done

- Store writes queue onto a single serialised IO executor; the public API stays non-suspending and an
  unchanged record is not rewritten.
- The library read at startup is a suspend `load()` on IO, behind an `awaitLoaded()` gate that
  `addBook`, `scan` and the reader all wait on — without the gate a cold import could overwrite a known
  book's record.
- `savePosition` debounces the disk write while updating the UI immediately.
- Search pages off ONE live iterator: "show more" pulls the next 100 hits instead of rescanning the book,
  so page five does not cost five full walks.
- The time left is the book's own word count, measured ONCE AT IMPORT: `Library.addBook` calls
  `ReadiumHelper.countWords`, one byte pass per chapter over the raw XHTML (`data/Words.kt` skips anything
  between `<` and `>`), and the number is stored in the book record. Every open after that is instant and
  exact, and there is no words-per-position ratio anywhere.
  WHY NO RATIO: Readium's position count is `ArchiveProperties.entryLength / 1024` (ceil'd), and for a
  normal EPUB that entry length behaves like the COMPRESSED size - an Arabic test book of 82 KB of XHTML
  compressed to a 3 KB file and reported THREE positions for the whole book. Compression ratio varies per
  chapter, so positions are not proportional to text, and any ratio built on them is noise. A record
  imported before this existed has `words = 0` and is counted once on first open (`KairnPerf: words=...
  (counted on open)`), then cached.
- The ambient wash and the glass shadows are built in `drawWithCache`; the glass avoids
  `Modifier.shadow` so a card does not get its own offscreen render node.
- Covers are cached in kilobytes against the heap, decoded with `inSampleSize` to ~384 px, and the grid
  points at a pre-scaled 320 px thumbnail.
- Compose stability is verified from the compiler report, not by eye:
  `app/build/compose_compiler/app_debug-composables.txt`.

Benchmark variants are not built by `assembleDebug` / `assembleRelease`:

    :app:assembleBenchmark                     the non-debuggable app the benchmarks measure
    :benchmark:assembleBenchmark               the benchmark APK
    :benchmark:connectedBenchmarkAndroidTest   requires a connected device

## Where the code is

    data/    Models (JSON round-trip), Store (single writer + StateFlows), Library (import, scan,
             content-hash ids), ReadiumHelper (thin Readium wrapper), Backup (zip, validate, merge)
    ui/      MainActivity, ReaderActivity (navigator, input, debounced save, search), ReaderScreen (all
             reader UI from ReaderUiState), ReaderSheets, ReaderWriter, ReaderLogic, ReadiumSelection
    ui/kit/  Buttons, Controls, Surfaces, Navigation, Overlays, Cover
    ui/theme/ Tokens, Type, Surface (glass vs solid), KairnTheme
    ui/nav/  AppShell — the 3 tabs, overlays and system pickers
    ui/screens/ Library, Collections, Add books, Settings, Backup, Onboarding, Annotations

Tests live in `app/src/test` and run on the JVM (73): model round-trips, `Store`'s writer (coalescing,
delete-not-overtaken, flush, unchanged guards) and the restore/merge path.

`app/src/androidTest` holds 28 on-device tests, run with `:app:connectedDebugAndroidTest` on a phone or an
emulator (and, more reliably, `adb shell am instrument -w app.kairn.test/androidx.test.runner.AndroidJUnitRunner`
- see the verification note below): the store writing a record to a real filesystem and reading it back cold, a delete taking the
file and cover off the disk, the Wi-Fi server accepting an upload over a real socket, the code gate
answering 404 on a bare path, the reader's tap zones - the RIGHT edge pages forward in an LTR book while
the LEFT edge does in an RTL one, the search sheet rendering each hit's chapter title above its snippet, and
the Display sheet's per-book writes (choosing two pages emits `twoPage = true` and touches nothing else, the
reset action reaches its callback, the follow-system-brightness action only exists once a level was set, and
Columns is absent on a phone), and the library screen - the empty state instead of a blank grid, a stored book
reaching the grid with its author, the status filter keeping only matching books, a removal reaching the store
with the grid following, the selection bar finishing the books you picked, and the search sheet's chapter filter
reporting its toggle. The screen tests compose with `createComposeRule` over a real `Store` on a temp
directory, so no Activity, WebView or IME is involved - which matters, because adb input cannot type into the
real search field. asserted from the saved position the reader writes (not from the screen, and
not from `progress`, which is legitimately 0.0 at the start of a book - wait for the locator). They speak HTTP over a raw socket on purpose — the app's own policy refuses
cleartext (the phone only ever listens, the browser is the client) and that policy is enforced by the HTTP
stack, so an HTTP client in a test fails with "Cleartext HTTP traffic not permitted". CI has no device, so
it COMPILES the instrumented sources (`assembleDebugAndroidTest`) rather than running them.

## Follow-ups from testing 0.8.0 on the phone (2026-10-30, unreleased)

Three things Noah hit while testing, two of them built and verified the same day:

- **A finished book is not something to continue.** `LibraryScreen`'s shelf filter now excludes
  `Status.FINISHED` as well as missing and broken books. A finished book stays in the grid; it just stops
  asking to be picked up. Guarded by `ContinueShelfTest.a_finished_book_is_not_something_to_continue`, which
  was mutation-checked: with the filter removed it fails, reverted it passes.
- **Share and Look up are in the selection toolbar, not behind the three dots**, and each has its own switch
  under Settings > Reading (`shareSelection`, `lookupSelection`, both default on). Off means the menu item is
  never added. Seen on the device: the toolbar reads Copy, Note, Share, Look up, then the first colour swatch
  and an overflow for the rest.

## 0.8.1 (2026-10-30)

Released on GitHub as v0.8.1, versionCode 11, signed APK md5 490319efedd8e0eca358e1def51585a9
(5,194,116 bytes), asset downloaded after publishing and checked against that md5. Changes: a finished book
leaves Continue reading, Share and Look up move into the selection toolbar with their own Settings switches,
Settings > About credits the developer with a link to github.com/pham-noah, and the store copy reads plainly.

## 0.8.2 — released 2026-10-30

Tag v0.8.2, release at /releases/tag/v0.8.2, asset Kairn-release.apk 5,092,957 bytes, md5
efa97ac42ce0fc496b49c4b60c493410 (downloaded back after publishing and matched). Adds the "Kairn on GitHub"
row in About and is the first release signed with the dedicated key.

Releases were signed with the debug keystore, which is per-machine: any APK built elsewhere or after that
keystore was regenerated cannot install over the last one, so Android demands an uninstall, and the uninstall
takes filesDir with it — library, places, highlights, settings, folder grants. That is the whole reason the
phone needed a rescan after every update.

Now: a 4096-bit RSA release key, CN=Kairn / O=Noah Pham, valid 10,000 days, kept at
`C:\Users\Noah\Documents\Kairn-keys\kairn-release.jks` with its passwords in `keystore.properties` beside it.
`app/build.gradle.kts` reads that file from `../../Kairn-keys/` (or `KAIRN_KEYSTORE_PROPS`), and signs the
release build when it is present; without it the APK is unsigned, which is what CI and F-Droid want. `*.jks`,
`*.keystore` and `keystore.properties` are in .gitignore.

Verified: `app-release.apk` is signed by CN=Kairn (cert SHA-256 69086f40...), the old 0.8.1 carries d3a627ec...
so the two cannot replace each other — installing 0.8.2 over 0.8.1 on the emulator failed with
INSTALL_FAILED_UPDATE_INCOMPATIBLE, exactly as predicted, and a clean install then ran versionCode 12. That
uninstall is a one-time cost; from 0.8.2 on, updates land in place and keep the data.

## 0.8.3 — released 2026-10-30

Tag v0.8.3, versionCode 13, signed with the same CN=Kairn key as 0.8.2 (cert SHA-256 69086f40...), so it
installs in place over 0.8.2 and keeps filesDir. compileSdk/targetSdk 36, AGP 8.11.1, Kotlin 2.1.0.

What changed, and where the sharp edges are:

- **Read aloud with the screen off.** [SpeechService](Kairn/app/src/main/java/app/kairn/ui/SpeechService.kt) is a
  `mediaPlayback` foreground service with a Stop action. `ReaderActivity.onPause` starts it while reading and
  stops it otherwise; `stopTts` and `onDestroy` stop it too. The service owns no audio - the voice, the page
  follow and the sleep timer all stay in the reader's process, which is exactly why the process needs holding.
  Asserted on device by `SpeechServiceDeviceTest`: backgrounding the reader while it reads leaves the service
  running, and stopping the voice ends it.
- **Automatic backup.** [AutoBackup](Kairn/app/src/main/java/app/kairn/data/AutoBackup.kt) writes one zip per
  `autoBackupDays` into the SAF tree chosen in Settings, pruning its own `PREFIX` files to `autoBackupKeep`.
  Silent on failure by design. **Not exercised end to end on a device** - a SAF tree cannot be granted from
  adb - so it is code-reviewed and compiled, not observed writing a zip.
- **Search cache.** [SearchIndex](Kairn/app/src/main/java/app/kairn/data/SearchIndex.kt) stores one gzipped dump
  per book under `filesDir/search/`, keyed by the book file's size and mtime. Two traps it hit: `DocumentFile`
  cannot stat a `file://` book (stat the copy directly), and `Link.href` is an `Href` wrapper while `Locator`
  wants a `Url`, whose `String` constructor is deprecated to ERROR level (use the `Url(String)` factory).
  Covered by `SearchIndexDeviceTest`.
- **Daily sweep.** The MediaStore + storage walk now runs at most once every 24 h (`settings.lastSweep`);
  watched folders stay live and "Find books on this device" still forces it.
- **Rough edges.** Release assembly fails without a keystore unless `-PallowUnsigned`; dead link taps say why;
  the update sheet explains a refused install.

Re-released the same day (same versionCode 13, new APK) after a reader who restored a backup was left needing
a scan. Three changes: a missing book makes the sweep due immediately (`sweepIsDue`, unit tested), restore
clears the inherited `lastSweep` and scans at once, and picked files are copied into the app's folder like
"Open with" with `relinkOwnedCopies()` re-pointing records whose uri died. Read Mihon's `DownloadCache` for the
pattern: it re-derives file locations from the filesystem instead of trusting a stored uri, which is why an
update never asks its users to scan.

## 0.8.4 — 2026-10-30

Seven items from the audit, each with a test:

- `sweepIsDue` throttles a missing-book sweep to once an hour (7 unit cases) instead of walking storage on
  every launch.
- Settings survive a bad entry: `shelves`/`extraTags` skip JSON nulls (`getString` threw, which cost the whole
  file and is why a restored install came up in onboarding), and `Store.readSettings` keeps an unparseable file
  as `settings.json.bak` and drops keys one at a time instead of running on defaults (`SettingsResilienceDeviceTest`).
- `relinkOwnedCopies` takes a stat-only fast path for our own copies (`RelinkDeviceTest`).
- `SearchIndex.forget` via `Store.onRemove`, `prune(known)` for orphans (`SearchPruneDeviceTest`); cache stats
  measured on IO, not in composition.
- Auto-backup decisions extracted as `autoBackupIsDue`/`staleBackups` (6 unit cases). The SAF round trip stays
  unproven: a test `DocumentsProvider` is impossible ("Provider must be protected by MANAGE_DOCUMENTS"), so
  that hop needs a phone.
- `TtsFollowBehindLockTest` proves a page turns while the activity is STOPPED, with a resumed control: first run
  failed at progress 0.0 because the hook fired before page positions existed - a test bug, not a product one.
- `WifiTransferTest` retried once on a socket error (flaky under a loaded machine, not a server bug).

Tests: 86 JVM, 36 on device, lint 0 errors. The tap-zone test used a fixed 3 s sleep and raced the cold start
after the SDK bump; it now polls for the saved page turn.

## Distribution state (checked 2026-10-30)

GitHub Releases is live and is where the updater points: `pham-noah/kairn-reader`, tag `v0.8.0`, asset
`Kairn-release.apk`, signed with the debug key. That is the whole channel today.

F-Droid: merge request **!52993** ("New app: Kairn (app.kairn)") was **closed by a maintainer on
2026-10-19**, and it was submitted against **v0.3.0** — five releases stale. Two consequences worth knowing
before anyone reopens it:

- **The store metadata is already written and current.** `fastlane/metadata/android/en-US/` holds the title,
  a 69-character short description, the full description and changelogs `1.txt` to `10.txt`, plus phone
  screenshots — all in the repo and in both histories, updated with 0.8.0. What is missing is only the
  `fdroid.yml` recipe itself, which lives in the fdroiddata merge request and nowhere in this repo.
- **The store build must ship with `self_update=false`.** `res/values/bools.xml` carries the gate, but the
  recipe needs to set it for the F-Droid build (a resource override or a small source patch) or the store
  build will offer in-app updates, which F-Droid policy forbids.

- **A draft recipe now exists in the repo:** `fdroid/app.kairn.yml`, brought up to 0.8.0 (versionCode 10,
  commit `v0.8.0`, subdir `Kairn`), written from the build files rather than guessed. It parses as YAML, its
  summary is 68 characters and the description 574, and both of its `prebuild` sed lines were exercised on a
  real build rather than assumed. Proven in a scratch copy of the repo: the two edits applied, `assembleRelease`
  succeeded in 49s, the AAPT2 resource dump inside the APK reads `bool/self_update () false`, and the APK has
  no `META-INF` signature pair (F-Droid signs it with its own key). One style question remains: overriding the
  flag by editing source is cruder than a gradle property, and that change belongs to Noah.

Do not rediscover this as news: the submission is closed and stale, not pending.
