# Kairn — implementation status and engineering notes

The product description lives in [README.md](README.md). This file is the technical side: what is built
against which spec item, what is not, and the things that will bite anyone touching the code.

## What is built, by spec ID

| Built | ID |
|---|---|
| EPUB open + render via Readium | R-01 |
| Page / scroll mode | R-03 |
| Font size, line + paragraph spacing, margins, justify, hyphenation (live) | R-04 (partly) |
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
| Two-page column mode (per book, tablet width only) | R-14 |
| Covers + metadata from the file; edit title/author | L-06 |
| Grid/list, search, sort | L-07 |
| Collections, tags, status; rename and delete a collection or a tag | L-08 |
| Highlights (4 colours), notes (written and edited), bookmarks, per-book list with jump, undo on delete, Markdown export | A-01, A-02, A-03 |
| Full-text search in book, a page of results at a time | S-01 |
| Backup (one zip, optional book files) and restore (safety copy, merge or replace) | DS-01, DEC-12 |
| Bad/missing file messages, never a crash | N-04 |
| Wi-Fi transfer: a LAN-only upload page, running only while its screen is open | L-04 |
| No analytics, no crash reporting, all data in open JSON | N-01..N-02, DEC-8 |

## Not built yet

R-04 custom font import (blocked: see below) · R-12 RTL/vertical verification (partly) · R-13 image zoom ·
dictionary pop-up (DEC-13) · all v1.x items (X-01…X-20). Bundled Manrope + Literata (OFL, licence texts in
`licences/`) and the signed minified release path both exist; F-Droid supplies its own signature.

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

## R-13 image zoom: what a first attempt learned (not shipped)

Three approaches were tried on a device and none was confirmed working, so the feature is NOT in the tree.
The next attempt starts from these:

- `TapEvent` carries only a `PointF`; there is no image information in the navigator API.
- Drawing the hit image into a canvas and calling `toDataURL` is the obvious trick and it is a dead end: an
  image served from Readium's internal scheme taints the canvas and `toDataURL` throws.
- `document.elementFromPoint(x, y)` returns the PARAGRAPH box for an inline image, and `closest('img')` only
  climbs ancestors - so it can never find a picture inside a `<p>`. Use `document.elementsFromPoint` and take
  the first `IMG` in the stack (the fix that was in progress).
- The bytes should then come from the publication, not the DOM: `Url(imgSrc)` + `publication.get(url)` +
  `read(LongRange(0, size - 1))`, which is the same path the word counter uses.
- Fixtures: this test used a dark-mode phone screenshot as the book's picture, which is indistinguishable from
  the reader's own black page - build the fixture image with bright, asymmetric content so "the image is on
  screen" and "the overlay opened" are both measurable from a screenshot.

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

Tests live in `app/src/test` and run on the JVM (48): model round-trips, `Store`'s writer (coalescing,
delete-not-overtaken, flush, unchanged guards) and the restore/merge path.

`app/src/androidTest` holds 6 on-device tests, run with `:app:connectedDebugAndroidTest` on a phone or an
emulator: the store writing a record to a real filesystem and reading it back cold, a delete taking the
file and cover off the disk, the Wi-Fi server accepting an upload over a real socket, the code gate
answering 404 on a bare path, and the reader's tap zones - the RIGHT edge pages forward in an LTR book while
the LEFT edge does in an RTL one, asserted from the saved position the reader writes (not from the screen, and
not from `progress`, which is legitimately 0.0 at the start of a book - wait for the locator). They speak HTTP over a raw socket on purpose — the app's own policy refuses
cleartext (the phone only ever listens, the browser is the client) and that policy is enforced by the HTTP
stack, so an HTTP client in a test fails with "Cleartext HTTP traffic not permitted". CI has no device, so
it COMPILES the instrumented sources (`assembleDebugAndroidTest`) rather than running them.
