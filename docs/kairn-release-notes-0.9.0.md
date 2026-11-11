# Kairn 0.9.0

## What this release is

0.9.0 is 0.8.6 with the same behaviour and a different shape. Not one string, colour or gesture changed:
`app/src/main/res/` is untouched in the diff. The version is the marker for an internal rebuild, done after a
SOLID audit of the tree (`SOLID_AUDIT.md`), and it exists so the next release can touch one part of the reader
without risking the rest.

Read aloud, auto scroll, automatic backup, the library, search, notes and the sync of settings all behave
exactly as in 0.8.6. The APK is signed with the same CN=Kairn key, so it installs over 0.8.6 in place and keeps
every book, place, highlight and setting.

## What moved

| was | is now |
|---|---|
| preference settling, mapping and applying inside `ReaderActivity` | `ui/ReaderPrefsApplier.kt` — the 120 ms settle rule, the engine submit, the brightness, the per-book record |
| the TTS engine, the sentence painting, the page follow, the end-of-chapter advance | `ui/ReaderSpeech.kt` |
| the JS scroll interval, the finger hold, the 700 ms resume | `ui/ReaderAutoScroll.kt` |
| three document launchers, `BackupUi`, the SAF grant and the restore confirmation inside `AppShell` | `ui/nav/BackupFlow.kt` |
| the cache-stats effect in `AppShell` | `ui/nav/CacheStats.kt` |
| the shell reaching into `KairnApp` 39 times | `ShellDeps`, the seven collaborators it actually uses |
| ten loose search and speech fields on `ReaderUiState` | `ReaderSearchState` and `ReaderSpeechState` |
| `AutoBackup` walking `DocumentFile` itself | `data/BackupFolder.kt` — an interface, with `DocHandle` as the handle |
| `Store.onRemove`, one nullable callback assigned from `KairnApp` | `Store.onBookRemoved(RemovalListener)` — a list, notified before the record goes |

## Why the last two matter

`AutoBackup`'s create-write-prune path had been compiled and reviewed for three releases and never run, because
a SAF tree can only be granted from a device picker. With the folder behind an interface it runs in a JVM test:
six of them, covering a due run, a run that is not due, no folder, a tree that will not open, a read-only
folder, and a failed write that must not be recorded as a backup.

`Store.onRemove` was one slot. Lost once, a removed book's search dump and positions stayed on disk with
nothing to say so. It is a listener list now, and `StoreTest` holds the contract.

## Numbers, same tree, before and after

| | 0.8.6 | 0.9.0 |
|---|---|---|
| `ReaderActivity` | 973 lines; class 898 lines, 65 functions, 130 fields | 822 lines; class 750 lines, 54 functions, 104 fields |
| `AppShell` | 299 lines; its composable 253 | 244 lines; its composable 189 |
| largest class in the tree | 898 | 750 |
| `app.<service>` references in the UI | 67 | 35 |
| interfaces (test seams) | 1 | 4 |
| tests | 116 JVM, 49 on device | 124 JVM, 52 on device |

Main source went from 8,068 to 8,509 lines: the extraction split two large files into six documented ones, and
`Store` grew 17 lines for the listener list. Fewer reasons for a file to change, more of them under test.

## Verification

- 124 JVM tests, 52 on-device tests (`OK (52 tests)`, 223 s on the `neotube` AVD), lint 0 errors, 11 warnings.
- Three of the on-device tests compose the reader's chrome against a fake: no Activity, no EPUB, no navigator.
- The signed release APK is built with the CN=Kairn key (cert SHA-256 `69086f40…`), the same one 0.8.2
  introduced — it installs over 0.8.6 and keeps the library.
