# Kairn 1.0.0

## The version that is what it says it is

Kairn has been a good offline reader with one flat voice. This release gives the book its cast, gives the
library its series, gives every book a page of its own, and lets books arrive from your Calibre server without
a cable. Nothing here needs an account, and no book leaves the phone.

## Each character gets their own voice

Read aloud now gives each name in a chapter its own voice, and keeps the narrator for narration and for any
line the text does not attribute. The rule behind it is simple and deliberate: a wrong voice is worse than a
flat one, so it never guesses twice. Two voices in a conversation alternate; a line it cannot place stays with
the narrator.

The voices are the ones your phone already has, so nothing is downloaded, and the cast is saved with the book
- switch it off entirely, or move a name to another voice, from the sheet that is already open while it reads.
The voice changes take effect at the next line, not the next chapter.

## The library knows its series

A book from Calibre (or any EPUB 3 that says so) now says which part of a series it is - "Discworld 8" under
the author in the grid, and a Series sort order that keeps a series together in its own order and puts
everything else after it by title. Books you imported before this release are filled in on the next launch,
not on the next import.

## About this book

Long-press a book for its own page: cover, series, how far you are and how long is left, your highlights and
notes counted, the description from the file, publisher, date, language and size - and the actions that
belong to the book rather than to a menu: continue, mark finished, edit, export notes, remove.

## Catalogs: books from your Calibre server

Add your Calibre content server's address, or any OPDS catalog, and browse its shelves from the phone over
your own network. A book is downloaded only when you tap it, nothing is fetched while you read, and covers are
not fetched at all. A username and password stay on this phone and are left out of every backup - a zip you
copy to a computer or a friend does not carry your server login.

## Also

- The read-aloud sheet scrolls, so a cast of six does not push Stop off a short screen.
- The add-books screen no longer claims that everything there works without internet. Catalogs do not, when
  you ask for one.
- Image zoom and custom font files are not coming. Both were tried on a real device, neither holds up, and
  this release stops pretending they are on a roadmap.

## Known, and measured rather than assumed

Whether the voice changes sound like a cast or like a stutter depends on the text-to-speech engine on your
phone: a voice can be set only between utterances, and some engines leave a gap there. The read-aloud sheet
has a switch, per book, so a phone that stutters can read in one voice. If yours does, that is the setting to
use, and it is the honest state of the feature.

Signed with the same dedicated key as every release since 0.8.2, so this installs over 0.9.6 and keeps your
library, your place and your notes.
