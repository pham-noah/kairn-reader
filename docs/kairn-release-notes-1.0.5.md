# Kairn 1.0.5

## A small release about things that stuck

No new features. 1.0.5 is the release that takes away four things that got in the way of reading, and puts
the build on a footing where the screens are actually checked before a release goes out.

## The message that would not leave

Bookmark a chapter and Kairn said "Bookmark added" in a small pill over the page. While the reading bars were
up it sat above them, which was right; the moment the bars slid away it stayed where it was, in the middle of
the text. Worse, a swipe over that pill did not dismiss it - the reader's own swipe-to-change-chapter took the
gesture, so the chapter moved on under a message that stayed put.

The pill now follows what is on screen: above the bar while the bar is there, down by the corner once the bars
are hidden, with the movement animated rather than a jump. And it can be swiped away like any other passing
message, in either direction, with the drag belonging to the pill and never to the page underneath. It also
goes away by itself as before - 2.6 seconds, or five when it carries an Undo.

## "Back to where you were", out of the page

The mint pill that offers to take you back after following a link or a footnote used to hang under the top
bar, over the first lines of exactly the page it was offering to return you to. It now sits with the message
at the bottom - the action above the message - and it drops with the bars like everything else. It still
expires after six seconds, and it still does not block a swipe on the page, because a swipe from the middle of
the page is what a reader expects to work.

## The footnote card, clear of the bar

A footnote card was sitting at a fixed height above the bottom edge, which put its lower edge behind the
reading bar. It is placed against the bar now, so nothing overlaps: bar, then the card, then the message, each
one clear of the last, and the message moves up rather than covering the card's Close button.

## Also

- The top bar is no longer part of the page-turn area, so a horizontal drag that starts on the back arrow or
  the bookmark button no longer changes the chapter instead of doing what you meant.
- A footnote card, a message and the way back can all be on screen at once without any of them hiding another.

## For the record

The project's test suite had stopped compiling from a clean checkout at 1.0.0, and this machine never noticed
because an incremental build kept reusing files from before. The two fakes that broke it are fixed, and the
suite is 294 tests that now really run, ten of them screens that had never been drawn in a test before. That
is the part of this release you cannot see, and it is the reason the rest of it can be trusted.

## Known

Whether the character voices sound like a cast or a stutter still depends on your phone's text-to-speech
engine, exactly as it did at 1.0.0: a voice can only be set between utterances, and some engines leave a gap
there. The switch in the read-aloud sheet, per book, remains the answer for a phone that stutters.

Signed with the same dedicated key as every release since 0.8.2, so this installs over 1.0.0 and keeps your
library, your place and your notes.
