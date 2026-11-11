# Kairn — implementation status and engineering notes

The product description lives in [README.md](../README.md). This file is the technical side: what is built
against which spec item, what is not, and the things that will bite anyone touching the code.

## What is built, by spec ID

| Built | ID |
|---|---|
| EPUB open + render via Readium | R-01 |
| Page / scroll mode | R-03 |
| Font size, line + paragraph spacing, margins, justify, hyphenation (live) | R-04 (partly) |
| Typeface: the publication's own, the bundled Literata / Manrope / Libron, or system serif, sans or mono | R-04 (partly) |
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
edition is a different paragraph. So [ClippingImport](../Kairn/app/src/main/java/app/kairn/data/ClippingImport.kt)
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
  exist. Settings' language row is no longer dead either: it lists the six shipped locales, switches the app
  in place, and keeps the choice across a restart, with "System" handing control back to the phone.

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
  results are SHIPPED, guarded by that test. The suite was 8 device tests at that point.

## Test counts, and the two hops that were unproven (measured 2026-11-04)

116 JVM tests in 14 classes (`./gradlew :app:testDebugUnitTest`), 49 on-device tests on the `neotube` AVD
(`adb shell am instrument -w app.kairn.test/androidx.test.runner.AndroidJUnitRunner` answered
`OK (49 tests)` in 162 s), lint 0 errors and 11 warnings. Every other count in this file belongs to the
release it sits under - 86 JVM / 36 device at 0.8.4, 27 device tests at 0.8.0, 8 before that - and is
history, not today's number.

**Auto-backup is proven end to end now.** It was the one thing compiled and reviewed but never observed
writing, because a SAF tree cannot be granted from adb. It can be granted from the UI, so it was:
`Download/KairnBackups` created in the picker, "Use this folder" accepted, permission allowed. Then:

- the grant wrote a zip on the spot - `kairn-auto-20261104-1451.zip`, 3 420 bytes - and stored
  `autoBackupFolder` as `content://com.android.externalstorage.documents/tree/primary%3ADownload%2FKairnBackups`;
- the zip is a real backup: nine entries, four book records and three positions, `settings.json` and its
  `.bak`, and no book files - which is what `includeBookFiles = false` means for an automatic one;
- the scheduled path runs by itself: with `lastAutoBackup` forced to 0, the next launch logged
  `KairnPerf: auto backup written` and wrote a second zip, `kairn-auto-20261104-1452.zip`;
- **prune works against a real provider.** Five dummy `kairn-auto-*` files and one real zip were in the
  folder; after the run wrote its zip, exactly three remained - the new one, the earlier real one and the
  newest dummy - so the four oldest were deleted through SAF. Every file there carried the prefix, so the
  "only ours" half of prune is still proven at unit level (`staleBackups`) rather than on a device;
- `lastAutoBackup`, `lastBackup` and `lastBackupName` all moved with it.

Not covered: a vendor SAF provider (the emulator's ExternalStorageProvider is the same code path, not the same
implementation), and a tree whose grant was revoked or whose folder was moved underneath it.

## The device suite has been mutation-checked

All 18 device tests passed on their first run, which is exactly when to distrust them. (That was the count at
the time; it is 49 now and the same rule applies.) Three behaviours were
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
- A disk cache for positions was tried once, removed for being slower than recomputing, and put back in
  0.8.5 with the cost measured rather than assumed. On the emulator, `Long Test`: first open measured
  `positions n=24 3791ms` and wrote a 415-byte file; the next open logged `positions n=24 cached` and
  `openBook` fell from 1904 ms to 649 ms. On a real Gutenberg book (Frankenstein, 78 201 words): first open
  measured `positions n=212 3359ms` into a 2.7 KB file, the second logged `n=212 cached` with `openBook`
  1001 ms against 1440 ms, and dragging the progress slider to ~65% saved position 133 of 212 at
  `totalProgression 0.6226` - so the restored locators navigate, they are not just counts. A garbage file in the cache slot falls back to measuring, opens
  normally, and is rewritten — the failure mode is slow, not broken. What the earlier attempt did
  differently is not recorded; if a real device ever measures the cached path slower, delete
  `PositionsCache` and the `FORMAT` constant is enough to retire every stored list.
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
    ui/      MainActivity, ReaderActivity (lifecycle, input, the fragment, search), ReaderSession (the open
             sequence: parse, positions, chapter titles, navigator factory and fonts), ReaderProgress (the
             save debounce), ReaderScreen (all reader UI from ReaderUiState), ReaderSheets, ReaderWriter,
             ReaderLogic, ReadiumSelection
    ui/kit/  Buttons, Controls, Surfaces, Navigation, Overlays, Cover
    ui/theme/ Tokens, Type, Surface (glass vs solid), KairnTheme
    ui/nav/  AppShell — the 3 tabs, overlays and system pickers
    ui/screens/ Library, Collections, Add books, Settings, Backup, Onboarding, Annotations

Tests live in `app/src/test` and run on the JVM (99): model round-trips, `Store`'s writer (coalescing,
delete-not-overtaken, flush, unchanged guards), the restore/merge path, the reader's chapter-title map and
save timing, and the save order in `ReaderProgress`.

`app/src/androidTest` holds 49 on-device tests, run with `:app:connectedDebugAndroidTest` on a phone or an
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

- **Read aloud with the screen off.** [SpeechService](../Kairn/app/src/main/java/app/kairn/ui/SpeechService.kt) is a
  `mediaPlayback` foreground service with a Stop action. `ReaderActivity.onPause` starts it while reading and
  stops it otherwise; `stopTts` and `onDestroy` stop it too. The service owns no audio - the voice, the page
  follow and the sleep timer all stay in the reader's process, which is exactly why the process needs holding.
  Asserted on device by `SpeechServiceDeviceTest`: backgrounding the reader while it reads leaves the service
  running, and stopping the voice ends it.
- **Automatic backup.** [AutoBackup](../Kairn/app/src/main/java/app/kairn/data/AutoBackup.kt) writes one zip per
  `autoBackupDays` into the SAF tree chosen in Settings, pruning its own `PREFIX` files to `autoBackupKeep`.
  Silent on failure by design. It was not exercised end to end at release time (a SAF tree cannot be granted
  from adb); it has been since - see "Test counts, and the two hops that were unproven".
- **Search cache.** [SearchIndex](../Kairn/app/src/main/java/app/kairn/data/SearchIndex.kt) stores one gzipped dump
  per book under `filesDir/search/`, keyed by the book file's size and mtime. Two traps it hit: `DocumentFile`
  cannot stat a `file://` book (stat the copy directly), and `Link.href` is an `Href` wrapper while `Locator`
  wants a `Url`, whose `String` constructor is deprecated to ERROR level (use the `Url(String)` factory).
  Covered by `SearchIndexDeviceTest`.
- **Positions cache.** [PositionsCache](../Kairn/app/src/main/java/app/kairn/data/PositionsCache.kt) stores the
  locator list `positions()` returns, one gzipped file per book under `filesDir/positions/`, valid against the
  same size+mtime stamp and a `FORMAT` constant that a pagination change bumps. Storing locators (rather than
  just counts) is what makes it worth it: the progress slider navigates with the restored `Locator` objects,
  via `Locator.toJSON()`/`Locator.fromJSON()`, which exist in 3.0.0. The reader's type size, margins and mode
  are deliberately not part of the key: positions come from the publication's content, so the same book gives
  212 either way, measured at 100% and at 242%. Written `.tmp`-then-rename so a kill
  mid-write cannot leave a half file. Settings' cache row counts search dumps and positions together, and
  "Clear cache" drops both. Covered by `PositionsCacheDeviceTest`: the JSON round-trip keeps hrefs, titles and
  progressions, a file that changed under the cache is rejected, rubbish reads as no cache, forget deletes,
  two saves of one book leave only the stored list behind, and prune takes departed books and any temp file a
  cancelled save left.
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
- Auto-backup decisions extracted as `autoBackupIsDue`/`staleBackups` (6 unit cases). The SAF round trip stayed unproven then: a test `DocumentsProvider` is impossible ("Provider must be
  protected by MANAGE_DOCUMENTS"). It is proven by hand on a device now - see "Test counts, and the two hops
  that were unproven".
- `TtsFollowBehindLockTest` proves a page turns while the activity is STOPPED, with a resumed control: first run
  failed at progress 0.0 because the hook fired before page positions existed - a test bug, not a product one.
- `WifiTransferTest` retried once on a socket error (flaky under a loaded machine, not a server bug).

Tests: 86 JVM, 36 on device, lint 0 errors. The tap-zone test used a fixed 3 s sleep and raced the cold start
after the SDK bump; it now polls for the saved page turn.

## 0.9.6 — released 2026-11-09

Version 23, asset `Kairn-0.9.6.apk` 6,325,512 bytes, md5 `8a5d2301…`. A hardening release: fifteen defects found by attacking the app rather
than by using it, all closed, no feature added and no screen changed.

Position merge: `mergeBook` compared records by `lastRead` but `Backup.restore` wrote every `data/pos/` sidecar
over the live one unconditionally, so a merge rollback every book to the backup's page. `mergePositionJson`
applies the same rule to the sidecar (newer `lastRead` wins; `replace` still takes the archive).

Wi-Fi transfer: the accept loop serviced one connection at a time with a 20 s socket timeout, so one idle
socket (a browser preconnect will do) stalled every upload - measured 19 695 ms before, 307 ms after. One
worker per connection now, capped at 16, with live sockets closed by `stop()`. A request or header line over
8 KB is answered 400 instead of dropped. The four-digit code throttles after 20 wrong attempts
(`codeDelayMs`: 100 ms per further attempt, cap 2 s; only four-digit paths count, so `/favicon.ico` cannot
trip it), and an upload that would leave under 64 MB free is refused with 507.

Records and archives: a record id is a file name, and `"../../escaped"` wrote and deleted outside `files/data/`.
`Book.isSafeId` (`[A-Za-z0-9_-]{1,64}`) is enforced in `Book.from`, `Store.put` and `Store.remove`. An archive
now validates only when it holds `data/books/…` or `data/settings.json` and no entry containing `..`; previously
a traversal-only archive validated, restored nothing, and the replace branch pruned the library.

Reader: `minutesLeft*` returns null for `wpm <= 0` (0 divided by zero inside composition) and `readingWpm` is
clamped 60..1200 on read. `chapterTitles` walks iteratively with a 64-level cap - a 20 000-deep nav was a
`StackOverflowError`. `ReaderPrefs.from` clamps fontScale/lineHeight/paragraphSpacing/margins/warmth/brightness/
scrollSpeed/speechRate to the sliders' ranges. `Clippings.matches` needs 4+ characters for a containment match,
so "It" no longer claims Little Women, The Hobbit and White Teeth.

Elsewhere: `Update.semver` is bounded to whole 6-digit parts inside `runCatching`, `downloadFileName` keeps a
release tag from choosing a path, `Store.writeAtomic` never throws (a directory at the `.tmp` path failed a
whole restore), and the device sweep skips exactly `Android` rather than every name starting with it.

Verified: **195 JVM tests** (13 of them Robolectric compositions of the library, settings, add books and the
reader), lint 11 warnings all of them dependency-version notices, `assembleDebug` and `assembleRelease` build,
the APK carries no test framework, same CN=Kairn certificate as 0.9.5. Not verified: no book opened on a
device for this release; the reader's rendering path is unchanged.

## 0.9.5 — released 2026-11-04

Version 22, asset `Kairn-0.9.5.apk` 6,325,512 bytes, md5 `315e98bd…`. The two places in the bar are no longer
reserved: the **first two items of the arrangement** hold them, tools included. A tool there draws as a pill and
opens its sheet; a mode pushed past the second place waits behind More and still toggles, mint while running.
`BarItem.isMode` now only says whether an item is a live toggle, never where it sits, and `BarItem.MAIN_SLOTS` is
the 2. Settings' arrows step past any **visible** neighbour - hidden items still hold list positions, so stepping
into one would look like a dead arrow.

Auto scroll is filtered out when the page mode is not scroll, so the second place falls to the next arranged
item; a device test asserted the old assumption and had to switch scroll mode on to keep the two modes in the
bar. That test failing was the model working, not a defect.

Tab changes are 85 ms (fades 70/60) instead of 170, and the nav pill 80 instead of 160. Press feedback now also
covers nav tabs (0.93), chips (0.95), book covers in grid and shelf (0.96), the segmented control (0.97), the
colour swatches (0.93) and the reader's More row (0.97). Full-width Settings and sheet rows keep ripple only.

Verified: 138 JVM tests, **60 on device**, lint 0 errors, six locales at 394 keys, asset downloaded back and md5
matched, same CN=Kairn certificate.

## 0.9.4 — released 2026-11-04

Version 21, asset `Kairn-0.9.4.apk` 6,325,388 bytes, md5 `41a853c1…`. Motion, deliberately small: nothing runs
longer than 170 ms and no animation changes a layout, so a tap never waits on one.

Press feedback is one modifier, `pressFeedback` (kit/Motion.kt): 4% scale down over 90 ms and back, on `KButton`,
`KIconButton`, the reader's mode pills and its four tool buttons. The ripple stays.

Tabs slide a tenth of the width in the direction of travel over 170 ms with a 120/140 ms fade either side, and a
horizontal flick past 110 dp changes tab - the shell's `tabSwipe`. It yields to sideways scrollers, because a
child that consumes the drag cancels the detector: the Continue reading shelf is unaffected. The nav bar's
selection fades pill and tint over 160 ms.

Verified: 138 JVM tests; the 59-test device suite run twice back to back, green both times; lint 0 errors;
swiping checked by hand on the emulator in both directions, and vertical scrolling confirmed not to change tab.

## 0.9.3 — released 2026-11-04

Version 20, asset `Kairn-0.9.3.apk` 6,309,004 bytes, md5 `e02de92f…`. The Reader bar setting arranges as well
as hides: up/down arrows per row, swapping only with a neighbour of the same kind, and the bar draws modes and
tools in that order.

"More" became a slot in the arrangement instead of a fixed left-hand cell: it sits where the tools begin, so a
tool placed first puts More at the front with the mode pills after it. It stays the flexible piece, so the pills
fit on either side; with no tools the pills share the row, and a pill label is one line.

`AppSettings.barOrder` holds the arrangement. An item missing from a saved order is **appended, never dropped**,
so a bar item cannot vanish because an older file did not list it.

Translations were read back one language at a time against each file's own vocabulary. Four were correct as
written; the Vietnamese hint had English word order ("Những gì thanh ở cuối trang hiển thị") and is now
"Các mục hiển thị trên thanh ở cuối trang". "Move up" / "Move down" added in all five; 394 keys per locale.

Verified: 138 JVM tests, 59 on device, lint 0 errors, and the published asset downloaded back and matched.

## 0.9.1 and 0.9.2 — released 2026-11-04

Two hotfixes in one evening, both from the update sheet and the reader bar.

**0.9.1** (versionCode 18, asset `Kairn-0.9.1.apk` 6,306,092 bytes, md5 `571312a7…`). The update sheet drew the
release body - Markdown from the release page - in one `Text`, so readers saw `##`, `- ` and pipe tables:
`ui/UpdateNotes.kt` parses it into headings, bullets, tables and paragraphs now, and the body scrolls in a
bounded area instead of being cut at 500 characters. The sheet also shrank when a download began, because the
action row was content-sized; it is one slot with a 68dp floor, so its top edge does not move (a device test
holds that within 1dp).

**0.9.2** (versionCode 19, asset `Kairn-0.9.2.apk` 6,307,888 bytes, md5 `8d2c032c…`). **Settings → Reader bar**
switches off any of the six things the reader's bottom bar can show: read aloud, auto scroll, contents, search,
notes, display. Stored as `AppSettings.barHidden` - what is hidden, not what is shown - so a bar item added
later still appears for anyone who never opened the screen, and an unknown name is skipped rather than failing
the settings file. Hiding read aloud or auto scroll while it runs stops it.

Both carry the same CN=Kairn signature and install in place. Verified: 135 JVM tests, 57 on device, lint 0
errors, translations checked across all five locales (392 keys each), and each published asset downloaded back
and matched by md5.

## 0.9.0 — released 2026-11-04

Version 17, `versionName` 0.9.0, signed with CN=Kairn (cert SHA-256 `69086f40…`) so it installs over 0.8.6 in
place. Release at `/releases/tag/v0.9.0`, asset `Kairn-0.9.0.apk` 6,306,088 bytes, md5
`7b0013d56f7db680686c74942efdb3cf` (downloaded back after publishing and matched).

Nothing the reader sees changed: `app/src/main/res/` is untouched in the diff. This release carries the
internal rebuild from the SOLID audit (`SOLID_AUDIT.md`) - preference application, read-aloud, auto scroll, the
backup flow and the backup folder interface extracted, `ReaderActivity` 973 -> 822 lines, `AppShell` 299 -> 244,
tests 116/49 -> 124/52.

Verified on the emulator: 0.8.6 installed and onboarded, then `adb install -r` of the 0.9.0 APK succeeded with
no uninstall, and the app opened straight to the Library - the settings and library files survived the
upgrade.

The mirror's tag `v0.9.0` points at `Kairn 0.9.0` (`a2a1e26`); the development repo's tag points at the version
commit. Tree contents are identical at that point - compared per path, blob by blob.

## Distribution state (checked 2026-11-04)

GitHub Releases is the channel the updater points at: `pham-noah/kairn-reader`, one APK asset per release,
signed with the CN=Kairn key since 0.8.2 (0.8.0 and 0.8.1 were debug-signed, which is why updating those
needed an uninstall and a rescan). Latest: **v0.9.6**, published 2026-11-09, asset `Kairn-0.9.6.apk`,
6,325,512 bytes, md5 `8a5d2301…` (0.9.5 was 6,325,512, 0.9.4 6,325,388, 0.9.3 6,309,004, 0.9.2 6,307,888, 0.9.1 6,306,092, 0.9.0 6,306,088). `data/Update.kt` compares the tag against `versionName` numerically, so 0.9.0 sorts above
0.8.6 - a string compare would put 0.10.0 first.

**Two repositories, and they are not interchangeable.**

- `pham-noah/kairn` is the development repo. It needs credentials even to read, and the release keystore
  lives beside it in `../../Kairn-keys/`.
- `pham-noah/kairn-reader` is the public mirror - what the updater and F-Droid point at, and where the store
  screenshot URLs resolve. Its history is a replay: 99 commits against the development repo's 177, the
  shared work under the same messages, and the tip commit renamed to `Kairn <version>`. **The two histories
  share no SHAs**, so no merge or fast-forward can check the mirror; the trees are compared directly instead.

Asset naming has drifted: `Kairn-release.apk` (0.8.0-0.8.3), `Kairn-release-084.apk` (0.8.4), then
`Kairn-<version>.apk` (0.8.5, 0.8.6). The updater picks its asset by pattern rather than by this convention,
so an old name still updates - it is the person reading the release page who is misled. `RELEASING.md` now
fixes the name going forward.

Tags: the mirror has carried `v0.1.0`-`v0.8.6`, one per release. The development repo carried only `v0.1.0`
and `v0.8.5` until 2026-11-04, when `v0.8.6` was cut on `a9401c2` and pushed; `v0.2.0`-`v0.8.4` still exist
only in the mirror.

The keystore moved and its properties file did not: `Kairn-keys/keystore.properties` pointed `storeFile` at
`C:\Users\Noah\Documents\Kairn-keys\kairn-release.jks`, which no longer exists - the key now sits in
`F:\App Projects\Kairn-related\Kairn-keys\`. The path is fixed as of 2026-11-04. A stale `storeFile` is
not a build error until the release build reaches signing, after the whole compile has already run. Verified after the fix: a release build on 2026-11-04 signs as CN=Kairn, certificate SHA-256
`69086f40...`, the same digest 0.8.2 introduced.

F-Droid: merge request **!52993** ("New app: Kairn (app.kairn)") was **closed by a maintainer on
2026-10-19**, and it was submitted against **v0.3.0** - the repo has since published eleven releases,
v0.6.0 through v0.8.6. Two consequences:

- **The store metadata is written and current.** `fastlane/metadata/android/en-US/` holds the title, a
  69-character short description, the full description and changelogs up to `16.txt`, plus phone
  screenshots. What is missing is only the recipe, which lives in `fdroid/app.kairn.yml` as a draft.
- **The store build must ship with `self_update=false`.** `res/values/bools.xml` carries the gate; the
  recipe sets it for the F-Droid build, or the store build offers in-app updates, which F-Droid forbids.

The draft recipe pins **0.8.5 / versionCode 15** and is therefore one release behind: versionCode, version
and commit get bumped together before resubmitting. Overriding the flag still happens by editing source in a
`prebuild` sed, which is cruder than a gradle property - that change is Noah's call.

Do not rediscover this as news: the F-Droid submission is closed and stale, not pending.
