# Kairn 0.9.3

The Reader bar setting arranges now, not just hides.

## Order

**Settings → Reader bar** lists the six items with an up and a down arrow beside the switch. The bar draws them
in that order: the two modes as pills, the four tools behind "More", each in the arrangement you chose.

Arrows only swap an item with its neighbour **of the same kind**. A tool cannot become a pill and a pill cannot
become a tool, and swapping across the two would have moved nothing on screen while reordering a list you
cannot see - so a mode steps past modes, a tool past tools.

## "More" is part of the arrangement

It used to be pinned to the left of the pill row. It now sits **where the tools begin**: put a tool first and
"More" moves to the front, with read aloud and auto scroll after it. "More" is the flexible piece that takes the
width left over, so the pills fit on either side of it without the row overflowing. With no tools left visible,
the pills share the row between them, and a long label stays on one line instead of wrapping the pill.

## Saved shape

`AppSettings.barOrder` holds the arrangement, next to the `barHidden` set from 0.9.2. A saved order that is
missing an item - written by an older version, or one item short of a newer one - gets it **appended**, never
dropped, so a bar item cannot disappear because it was not in the list. An unknown name is skipped.

## Translations, checked one by one

I read the two new strings back in each language against that file's own vocabulary - lector, lukija, trình
đọc, リーダー, 阅读 - and found one genuine fault: the Vietnamese hint had the clause in English word order
("Những gì thanh ở cuối trang hiển thị"). It is now "Các mục hiển thị trên thanh ở cuối trang", which is how
the sentence is built in Vietnamese. Spanish, Finnish, Japanese and Chinese were already right and are
unchanged. "Move up" / "Move down" are added in all five.

## Verification

- 138 JVM tests, three new: the arrangement round-trips, an item missing from a saved order is appended, and no
  saved order means the default.
- 59 on-device tests, two new on the bar: the tools are drawn in the arranged order, and a mode placed after a
  tool still lands where the arrangement says it should, with "More" first.
- lint 0 errors, translations checked across all five locales.
- Signed with the same CN=Kairn key, so it installs over 0.9.2 in place.
