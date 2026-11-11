# Kairn 0.8.5

Three typefaces that ship with the app, a language switch inside Settings, and a library that gives the
books the room instead of the buttons.

**Fonts, offline**
- Literata, Manrope and Libron are now in the APK, so the reading typefaces work with no download and no
  network. Literata and Manrope carry their whole weight range in one file; Libron ships regular, bold,
  italic and bold italic.
- They sit in the existing typeface picker, alongside Default and Monospace.

**Language without leaving the app**
- A Language row in Settings switches the app between English, Español, Suomi, 日本語, Tiếng Việt and 简体中文
  on the spot - no relaunch, no system settings.
- The choice survives a restart, and "System" hands control back to your phone.

**Library and Settings, less cluttered**
- Add books and the grid/list toggle now sit with the collection row, so the header holds the title and
  search only. In Collections, "New" is the same + you already know.
- Rows in Settings are one line each: the descriptions under action rows are gone, and the Appearance card
  is label-and-control like every other group, which brings the reading settings up a screen earlier.

**Reopening is quick**
- A book is measured the first time you open it and the result is kept, so the second open skips the walk.
  On the test device that is 649 ms instead of 1904 ms; a book with more pages gains more. Delete the book and
  the measurement goes with it, and Settings' cache row counts it, so "Clear cache" drops it.

**Copy and contrast**
- "Check for updates now" instead of "Check now", in all six languages.
- Finnish says *Lukulista* for the reading list and *Nimi* for a title, everywhere it appeared.
- The accent green on the two pale themes was too light to read against the page: 4.09:1 on Light and 3.56:1
  on Sepia, under AA for the chapter name above the progress bar. Both are now 5.39:1 and 4.69:1.

**Under the hood**
- Opening a book and saving your place are split into pieces with tests of their own: 99 JVM tests, up
  from 73. No behaviour change intended, and the place you left off was checked against a restart.
- The kept measurement is covered by six device tests - a stored list comes back with its locations, a file
  that changed under it is rejected, rubbish reads as no cache, deleting a book deletes it, two saves leave one
  file, and the tidy-up takes both departed books and half-written temp files - 44 on-device tests in all.

Install over 0.8.4 as usual - same signing key, data kept.
