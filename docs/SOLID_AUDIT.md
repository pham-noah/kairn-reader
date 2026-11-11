# Kairn — SOLID audit

Read-only review of `app/src/main`, 2026-11-04, against the tree at `1c27883` (v0.8.6 plus the docs commit).
Every number below was measured in that session, and every claim carries a file and a line.

## What was measured

| | |
|---|---|
| Main source | 50 `.kt` files, 8,018 LOC |
| JVM tests | 14 files, 1,245 LOC — 116 tests, green |
| Device tests | 18 files, 1,374 LOC — 49 tests, `OK (49 tests)` in 162 s |
| Test : source | about 1 : 3 |
| Analyzers | no detekt or ktlint config in either repo; `lintDebug` gives 0 errors, 11 warnings |
| Inheritance surface | 0 `open class`, 0 `abstract class`, 1 `sealed interface`, 1 `interface` |
| Largest files | `ReaderActivity` 973 L, `LibraryScreen` 610 L, `ReaderScreen` 395 L, `Library` 317 L, `AppShell` 299 L |
| Largest class | `ReaderActivity`: 898-line class, 61 functions, 36 fields, 30 overrides |
| Functions over 60 lines | 7 — `AppShell` 253, `ReaderScreen` 168, `openBook` 140, `CollectionsScreen` 97, `Backup.restore` 83, `DisplaySheet` 65, `AnnotationsContent` 64 |
| Layering | `data/` imports `ui/` in 0 files; `ui/` imports `data/` in 23 |

The short version: **the data layer and the pure modules follow SOLID; the two UI roots do not.** All the debt
sits in `ReaderActivity` and `AppShell`, and neither needs a rewrite to fix.

## S — single responsibility

### `ReaderActivity` (898-line class) owns nine jobs

| concern | where |
|---|---|
| navigator + publication lifecycle | `openBook:187-327`, `applyBook:179` |
| preference mapping and application | `effectiveTheme`/`toEpub` (removed 2026-11-04, see the end), `applyPrefs`, `updatePrefs` |
| touch and key input | `dispatchTouchEvent:537`, `onKeyDown:523`, swipe fields `535` |
| selection actions (copy, share, look up, highlight, note) | `575-681` |
| marks and bookmark writes | `addMark:634`, `toggleBookmark:687` |
| text-to-speech orchestration | `toggleTts:699`, `speakChapter:737`, `followVoice:764`, `advanceWithTts:809` |
| auto-scroll policy | `430-485` |
| sleep timer | `378-418` |
| notes export | launcher `125`, `exportNotes:834` |

Being a composition root justifies the wiring. It does not justify owning scroll physics, voice policy and
preference settling. **First extraction (done, see the end):** the preference block, because `ReaderPrefsMapping`
had already proven the shape works.

### `AppShell` is one 253-line composable

It holds the tab state, the onboarding overlay gate, the shelf filter, `BackupUi`, four
`rememberLauncherForActivityResult` (book picker, folder, backup, auto-backup folder), the cache-stats effect,
the update check and the clippings import. `BackupFlow` and `CacheStatsEffect` are the two self-contained
pieces; `BackupTest` already covers the logic they call.

### `ReaderUiState` is 33 states in one holder (`ReaderScreen.kt:58-107`)

Publication, marks, positions, six search states, five TTS states, chrome geometry and pacing. Three of those
are independent state machines that happen to share a holder. Grouping `SearchState` and `TtsState` inside it
keeps every call site and makes each testable alone.

### `Library` mixes six jobs and one permission

Import, id derivation and migration, relink, folder grants, scan policy, thumbnails — plus
`needsAllFilesAccess:275` and `requestAllFilesAccess:279`, an Android permission concern inside a data class.
`EpubSweeper` was the right extraction; thumbnails and the permission request are the next two.

`Store` is *not* a violation: 20 functions over six file types with one write queue is cohesion, not a god
object.

## O — open/closed

One shape is repeated and no abstraction hides it: a preference costs five edits.

1. field in `AppSettings` (`Models.kt:164-192`),
2. `toJson` (`194-207`),
3. `from` (`210-255`),
4. the Settings row,
5. for reading preferences, the same three steps in `ReaderPrefs` plus a line in `ReaderPrefsMapping`.

Checked for existing drift: **none.** 28 fields, 31 keys written, 31 read, zero asymmetry, for both
`AppSettings` and `ReaderPrefs`. `ModelsTest` and `SettingsResilienceDeviceTest` guard it. The cost is real, the
current state is correct; the risk is a future omission.

Enums are the second closed axis: `ThemeId` is referenced 38 times across 9 files, `ReaderSheet` 24 times
across 2 files with two `when` blocks (`ReaderScreen.kt:274`, `281`), `Status` 16 times across 6. A sixth theme
means edits in `Tokens`, `ReaderPrefsMapping`, Settings, onboarding and `Controls`.

The counter-example that already works is `ReaderPrefsMapping`: one pure function, its own JVM test and a device
test. That is the pattern to copy for theme to `ReaderUi`.

## L — Liskov

With zero inheritance there is nothing classic to violate, and inventing a finding here would be dishonest. The
one substitutability contract is `ReaderActions` (`ReaderScreen.kt:109`, 19 methods), implemented only by
`ReaderActivity`. The screen never touches the navigator directly, so the contract holds — but **no fake or test
double exists**; `grep ReaderActions` finds the interface and the Activity and nothing else. The seam is real
and unexercised.

One genuine contract hazard: `Store.onRemove` (`Store.kt:221`) is a mutable callback wired in `KairnApp.kt:37`
to invalidate `searchIndex` and `positionsCache`. Removal correctness depends on that field still being set, and
nothing enforces it. A `RemoveBook` use case would make it structural.

## I — interface segregation

`ReaderScreen(ui, colors, isTablet, toast, act, wpm)` taking `act: ReaderActions` instead of twenty lambdas is
the right call and the reason the screen stays stateless. The problem is width: 19 methods over navigation,
marks, voice and search, which the Activity owes as 30 overrides and any fake owes as 19 stubs. Split into
`ReaderNav`, `MarkActions`, `TtsActions`, `SearchActions`; each sheet takes the two or three it uses.

Same fatness elsewhere: `ReaderUiState`'s 33 fields, and `LibraryScreen`'s fourteen separate
`remember { mutableStateOf }` for selection state (`175-216`) that belong in one holder.

## D — dependency inversion

The good half is genuinely good: every collaborator takes its dependencies as constructor parameters
(`KairnApp.kt:30-38`), no framework, no globals. That is why `BackupTest`, `WifiTransferTest`, `StoreTest` and
`UpdateTest` run on the JVM at all.

The leak is the service locator: `application as KairnApp` at `MainActivity.kt:22` and `ReaderActivity.kt:83`,
and **67 `app.<service>` references inside `ui/`** (40 `app.store`, 11 `app.library`, 5 `positionsCache`,
4 `searchIndex`, 3 `readium`, 2 `backup`, 2 `autoBackup`). `AppShell(app: KairnApp, ...)` hands the most
concrete object in the app to the highest-level composable, and `ClippingImport(...)` is constructed inside
composition (`AppShell.kt:87`).

The abstraction that is actually missing is not a DI framework — it is `Context`/SAF, and the precise dependency
matters: `AutoBackup` owns a whole granted tree (`DocumentFile.fromTreeUri`, create, list, delete), `Backup`
streams through `contentResolver`, and `SearchIndex`/`PositionsCache` each touch SAF exactly once, to stat a
book's size and mtime (`SearchIndex.kt:44`, `PositionsCache.kt:36`) — their own storage is plain `File`. So the
extraction that pays is the backup tree, not a general `Documents` layer, and it is the one that was done.

## Optimisation

The usual wins are already taken, and that is worth stating before any criticism: filtering and sorting sit
inside `derivedStateOf` (`LibraryScreen.kt:242-259`), marks subscribe with `distinctUntilChanged`, writes go
through one serialised executor with atomic rename and equality short-circuits (`Store.kt:38-70`, `205-211`),
positions are cached behind a `FORMAT` constant, and parsing is on `Dispatchers.IO`.

What remains is complexity rather than measured time: `savePosition` does a linear `get(id)` scan plus a
whole-list `map` copy per page turn (`Store.kt:178`, `189`) — O(n) per turn, fine at 500 books, a ceiling at
5,000. An `id -> index` map beside `books` is the cheap fix, but it should wait for a benchmark from the
`benchmark` module.

## What I would not change

`ReaderLogic` (13 pure functions), `ReaderPrefsMapping`, the `Words`/`Clippings` parsers, `EpubSweeper`,
`PositionsCache`'s tmp-then-rename, `Update.Check`'s sealed `Found/Current/Failed`, and `Backup`'s
validate-before-touch ordering. `Store`'s synchronised read-modify-write with a no-op short-circuit is better
than most persistence layers.

## Priority

| # | Change | Cost |
|---|---|---|
| 1 | Extract preference application out of `ReaderActivity` | S — **done** |
| 2 | Split `ReaderActions` into nav / marks / tts / search | **the split was not worth it; the fake it was for is in** |
| 3 | Extract `BackupFlow` and `CacheStatsEffect` from `AppShell` | S — **done** |
| 4 | Group `ReaderUiState`'s search and TTS states | M — **done** |
| 5 | A `Documents` interface over SAF | M — **done for the backup tree**, which is the only place a whole tree is used |
| 6 | Move TTS orchestration and auto-scroll out of the Activity | M–L — **done** |
| 7 | Pass collaborators into `AppShell` instead of `KairnApp` | M — **done** |

## Done since the audit

**Item 1 — `ReaderPrefsApplier`** (`ui/ReaderPrefsApplier.kt`). The Activity's two mapping helpers, the 120 ms
settle job and the apply step moved into one class that takes the window, a navigator lambda, and the settings
it needs as lambdas; the pure mapping stays in `ReaderPrefsMapping` where it is tested without an Activity.
`ReaderActivity` lost the `applyJob` field, `toEpub`, `effectiveTheme` and three imports: 973 → 970 lines, but
the point is the change in reasons to change, not the count. Both suites are the gate and both are green with
the extraction in place: 116 JVM tests, 49 on device (`OK (49 tests)` in 229 s), lint still 0 errors and 11
warnings.

**Item 3 — `BackupFlow` and `rememberCacheStats`** (`ui/nav/BackupFlow.kt`, `ui/nav/CacheStats.kt`). The four
document contracts, the backup progress state, the SAF grant and the restore confirmation left `AppShell`; the
cache-stats effect became a composable that returns its state, so "Clear cache" can zero it. `AppShell`: 299 →
244 lines, and it no longer imports `KairnApp` at all.

**Item 7 — `ShellDeps`** (`AppShell.kt`). The shell takes the seven collaborators it uses instead of the
Application and its 39 `app.<service>` references; `MainActivity` builds the object once and remembers it.

**Item 4 — `ReaderSearchState` and `ReaderSpeechState`** (`ReaderScreen.kt`). Ten of `ReaderUiState`'s 33 flat
fields became two objects with their own schedules; 77 references renamed across two files, no call-site
restructuring, no behaviour.

**Item 5 — `BackupFolder`** (`data/BackupFolder.kt`, rewritten `data/AutoBackup.kt`). The granted tree is an
interface (`canWrite`, `create`, `list`, `delete`) whose handle is a `DocHandle` — a value class over String,
because `Uri.parse` is a stub in JVM tests. `AutoBackup` now takes the store, a folder factory and a writer and
nothing else, and `AutoBackupTest` exercises the whole path: create, write, prune to `keep` while a file without
the prefix survives, not-due, no folder, unopenable tree, read-only folder, and a failed write that must not be
recorded as a backup. **Six new JVM tests (116 → 122)** for a path the 0.8.3 notes listed as unproven.

**Item 6 — `ReaderAutoScroll`** (`ui/ReaderAutoScroll.kt`). The JS interval, the 1500 ms end-of-chapter watch,
the finger hold and the 700 ms resume moved out; the Activity asks `start/stop/hold/release` and keeps the
scroll-mode guard. The TTS half of item 6 is **not done** — see below.

**Item 2 — the split, or the fake?** Measured before touching it: the sheets already take narrow lambdas
(`TtsSheet(onRate, onSleep, onStop)`, `SearchSheet(onQuery, onMore, onGo)`, …) and `ReaderScreen` itself uses
all nineteen methods, so splitting `ReaderActions` four ways would add five declarations, change no call site
and buy nothing at the boundary. What the proposal was actually for - a test that drives the screen without a
navigator - is now there instead: `ReaderScreenTest` composes `ReaderScreen` against a `FakeReader` that records
what each tap asks for, and asserts the bookmark pill asks to add one when the chapter has none, asks to remove
it when it has one, and that leaving the reader is a single call. Three tests, no EPUB, no Activity, no
navigator.

**Item 6 — `ReaderSpeech`** (`ui/ReaderSpeech.kt`, 180 lines). The engine wiring, the speak-from-here rule, the
sentence painting, the page follow, the end-of-chapter advance and the test hook all moved out; the Activity
keeps what needs an Activity - the sleep timer's toasts and delay, and the foreground service. `ReaderActivity`:
929 → 822 lines. Verified by `SpeechServiceDeviceTest`, `TtsFollowBehindLockTest` and the full suite.

**Hazard closed — `Store.onBookRemoved`.** `Store.onRemove` was one nullable callback assigned from `KairnApp`;
if that assignment were ever lost, a removed book's search dump and positions stayed on disk with nothing to
say so. It is a listener list now, `fun interface RemovalListener`, and `StoreTest` holds the contract: every
registered listener is told once, before the record goes, and a removal with nothing registered is quiet.
`Store` grew 17 lines to `253 -> 270`; that is the price of the guarantee.

**Still open:** `ReaderActivity`'s remaining five jobs (navigator lifecycle, touch and keys, selection actions,
marks, search). It is 822 lines now, down from 973: preference application, auto-scroll and read-aloud left it.
