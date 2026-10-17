# Kairn — implementation status and engineering notes

The product description lives in [README.md](README.md). This file is the technical side: what is built
against which spec item, what is not, and the things that will bite anyone touching the code.

## What is built, by spec ID

| Built | ID |
|---|---|
| EPUB open + render via Readium | R-01 |
| Page / scroll mode | R-03 |
| Font size, line + paragraph spacing, margins, justify, hyphenation (live) | R-04 (partly) |
| Light / dark / sepia / true black, dark default, follow-system option | R-05, DEC-4 |
| Warm-light overlay and in-app brightness (system settings untouched) | R-06 |
| Tap zones (reading-direction aware), volume keys, chapter list, progress slider | R-07 (partly) |
| Footnote pop-up, "jump back" chip | R-08 |
| Position saved continuously and on pause, restored on open | R-09 |
| Per-book reading settings | R-10 |
| Full-screen, keep-screen-on | R-11 |
| Watched folders (per-folder switch), missing-not-removed, picker import, "Open with" | L-01, L-02, L-03, L-05 |
| Auto-detect: sweeps the device for EPUBs, like Lithium (settings toggle) | L-01 |
| Chapter time left, fine-grained seek (by position, real word-count estimate) | R-07 |
| Two-page column mode (per book) | R-14 |
| Covers + metadata from the file; edit title/author | L-06 |
| Grid/list, search, sort | L-07 |
| Collections, tags, status | L-08 |
| Highlights (4 colours), bookmarks, per-book list with jump, Markdown export | A-01, A-02, A-03 |
| Full-text search in book | S-01 |
| Backup (one zip, optional book files) and restore (safety copy, merge or replace) | DS-01, DEC-12 |
| Bad/missing file messages, never a crash | N-04 |
| No INTERNET permission, no analytics, all data in open JSON | N-01..N-02, DEC-3, DEC-8 |

## Not built yet

R-04 custom font import · R-12 RTL/vertical verification · R-13 image zoom · L-04 Wi-Fi transfer ·
dictionary pop-up (DEC-13) · N-08 contrast audit · bundled Manrope/Literata/Libron fonts (OQ-5: licences
unchecked) · release signing (DEC-14) · all v1.x items (X-01…X-20).

## Known gaps — read these before trusting the list above

- **N-05 ("strings in resources") is not actually done.** There are ~65 user-visible string literals in
  Kotlin, and 15 of the 16 entries in `strings.xml` are unused. Nothing is translated; every string lives
  in code.
- **Notes are read-only.** A `Mark` carries a `note` field and the annotations screen renders it and
  exports it, but nothing in the app ever writes one — there is no way to type a note yet. Highlights and
  bookmarks are fully working; notes are UI waiting for an input.
- **The merged manifest carries `ACCESS_NETWORK_STATE`.** It is pulled in by `media3-exoplayer`, which
  Readium's navigator depends on for audio playback. There is still no `INTERNET` permission, so the app
  cannot make network calls — but a reviewer will see that permission and it is worth knowing why it is
  there.
- **`ensureThumbs`** in `Library` backfills thumbnails for books that lack one. Import already writes a
  thumbnail, and a cell falls back to the full cover, so this is an optimisation for older records, not a
  correctness path.

## Notes for anyone touching the reader (Readium Kotlin Toolkit 3.0.0)

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
    ui/      MainActivity, ReaderActivity, ReaderSheets, ReadiumSelection
    ui/kit/  Buttons, Controls, Surfaces, Navigation, Overlays, Cover
    ui/theme/ Tokens, Type, Surface (glass vs solid), KairnTheme
    ui/nav/  AppShell — the 3 tabs, overlays and system pickers
    ui/screens/ Library, Collections, Add books, Settings, Backup, Onboarding, Annotations

Tests live in `app/src/test` and run on the JVM: model round-trips, `Store`'s writer (coalescing,
delete-not-overtaken, flush, unchanged guards) and the restore/merge path.
