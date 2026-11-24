# Kairn 1.1.1

## A release about not having to decide

1.1.1 is the follow-up to 1.1.0's UI pass: the settings that were already there, made into choices you can see
and take in one tap, and a first open that no longer waits on arithmetic it could have done earlier.

## Newly added books arrive already measured

Opening a book shows a position count in the footer - the "12% of 340 pages" line. Those numbers come from
walking the book's text, and until now the walk happened the first time a book was opened. Every other open
was instant; the first one paused on exactly the books you had most recently added and were most likely to
open next.

Kairn now measures a book when it is imported, in the background, one at a time. A folder of two hundred books
does not become two hundred measurements at once - they queue behind the shelf while you read something else.
And if one of them fails, nothing is lost: the book is measured on open exactly as before.

## Three reading presets, drawn

The reading settings are five sliders - type size, line height, paragraph spacing, margins, and the rest. They
are good sliders. They also ask you to know what 1.34 line height looks like before you have seen it.

Above them now sit three tiles: Compact, Comfortable and Large print. Each one is a small page drawn with the
line weight and spacing it will actually give you - six tight rules, five comfortable ones, four thick ones -
so the choice is a picture rather than a sentence. One tap moves all four numbers; the sliders below then show
what that tap did, and nudging any of them simply deselects the tile. The presets describe the sliders; they do
not replace them.

## An accent that comes from the cover

Accent colour had five choices, all of them picked by hand. The sixth is the book's own: Kairn samples the
cover, keeps its hue, and holds the saturation and lightness in the same band the five hand-picked colours
occupy, so a deep navy jacket becomes a readable mint-blue rather than a smear. The library uses the cover of
the book you read last; the reader uses its own.

A cover is a picture and its honest colour is usually too dark or too grey to sit under a switch, so what you
get is the cover's *hue* at a strength that can carry text - the label on it holds a contrast ratio of 4.95 to
1 against its own ink, measured off the render.

## The bar, in three shapes

The reader bar holds six tools, two of them out front and the rest behind the More button. Most people use
two.

The bar comes in three arrangements now. Minimal puts contents and search out front and leaves voice and
display one tap further away. Standard is the bar as it has always been, with read aloud and auto-scroll where
your thumb already expects them. Voice moves read aloud and display to the front, which is what gets touched
mid-chapter while the book is speaking.

Each tile draws the bar it produces - every slot carries its tool's own mark, a list, a lens, a speaker, an A,
an arrow - so the three are told apart by the picture rather than by the caption underneath.

## Also in this release

- Both preset rows become full-width tiles above 1.5x type, where three captions across a phone ellipsised to
  "Large p...".
- The accent row is six dots with the chosen name printed once underneath, which is the only arrangement that
  holds at every text size.

Signed with the same CN=Kairn key as every release since 0.8.2, so it installs over 1.1.0 in place.
