# Kairn 0.9.5

## The two places in the bar are not only for read aloud and auto scroll

0.9.3 let you arrange the six items, but the two places in the bar itself stayed reserved for the modes. Now the
**first two items of your arrangement** sit in the bar, whatever they are:

- Put **Contents** first and it becomes a pill in the bar, opening its sheet on tap.
- Push **read aloud** to third and it waits behind "More" - and it is still a toggle there, mint when running,
  not a sheet opener.

So the bar reads as "the two you reach for most, plus More" instead of "the two modes, plus tools". "More" sits
after the two, and takes whatever width is left, so the pills never push it off the edge.

The arrows in Settings now step an item past **any** visible neighbour, not only one of its own kind - the
same-kind rule was there because a tool could not reach the bar, and that reason is gone.

## Twice as fast between tabs

Tab changes were 170 ms and felt like a wait before the screen arrived. They are **85 ms** now, with the fades at
70 and 60. The nav bar's pill follows at 80 ms instead of 160, so it lands with the screen rather than after it.

## More of the app answers your finger

Added to the same 90 ms sink that the buttons already had:

- **Nav tabs** (0.93) - the tab you tap settles under the thumb.
- **Chips** (0.95) - filters in the library.
- **Book covers**, grid and shelf (0.96) - the biggest targets in the app; the title sinks with the cover so the
  card answers as one piece.
- **Segmented control** (0.97) and the **colour swatches** (0.93) in the reader's Display sheet.
- The reader's **More** row (0.97).

Long full-width rows in Settings and the sheets are left alone deliberately: scaling a row the width of the
screen reads as the panel breathing, not as a press. They keep their ripple.

## Verification

Updated the two device tests that assumed the old rules and added two: a tool in the first slot opens its sheet
from the bar, and a mode pushed past the second slot still toggles from behind "More". 138 JVM and 59 on-device
tests green, lint 0 errors, translations checked in all six locales.
