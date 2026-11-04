# Kairn 0.9.2

The reader's bottom bar is yours to arrange now.

## What the new setting does

**Settings → Reader bar** lists the six things that bar can show:

| item | where it sits |
|---|---|
| Read aloud | a mode pill on the bar |
| Auto scroll | a mode pill, shown in scroll mode |
| Contents | a tool behind "More" |
| Search | a tool behind "More" |
| Notes | a tool behind "More" |
| Display | a tool behind "More" |

Switch one off and it is not drawn at all - not greyed out, not moved, gone. Hide all four tools and the "More"
row goes with them; the seek bar and whatever mode pills are left stay where they were.

Hiding **read aloud** while it is reading stops it, and hiding **auto scroll** while the page is drifting stops
the drift. A mode that runs with no control on screen is the one thing this setting must not be able to create.

## How it is stored

One field, `AppSettings.barHidden`, holding the items that are switched off and nothing else - an empty set is
exactly the bar every earlier version drew. Storing what is hidden rather than what is shown means a bar item
added in a later release still appears for everyone who never opened this screen, and a name written by a newer
version is skipped instead of failing the whole settings file.

The bar itself is built from a list of `BarItem`s now rather than four hand-written rows, so the drawing order
and the setting can never disagree.

## Verification

- 135 JVM tests, three of them new: the hidden set survives a save, an unknown item is skipped, and a settings
  file with no bar key shows everything.
- 57 on-device tests, three new on the bar itself: a hidden pill is absent, hiding all four tools takes the
  "More" row with it while the bar remains, and only the tools left unhidden are drawn.
- Signed with the same CN=Kairn key, so it installs over 0.9.1 in place.
