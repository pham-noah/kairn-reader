#!/usr/bin/env python3
"""Tile the Kairn screenshots into one contact sheet.

Reviewing shots one at a time is how this session spent most of its picture time: a vision call per screen,
then another because the crop was wrong. Six screens at half scale in one image answer the questions that
matter across screens - does every title start on the same baseline, is the padding the same, does one screen
look more crowded than its neighbours - and they answer them in one look instead of six.

    python3 tools/sheet.py                       # every known shot that exists
    python3 tools/sheet.py book detail           # just those (substring match on the file name)

Writes contact.png next to the shots. Shots keep their own full-resolution files; this is only for reading.
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

# The scratch dir is shared with other projects, so the sheet is built from this project's own names.
# New shots go here, in the order a reviewer wants to walk them: shelf, then reader, then the sheets.
ORDER = [
    "onboarding", "library-narrow", "library-books", "library-wide", "library-list", "library-big", "library-empty",
    "add-books", "settings", "settings-schedule", "settings-schedule-big", "settings-wide", "settings-big", "annotations", "collections", "catalog", "backup",
    "reader-dark", "reader-light", "reader-wide",
    "sheet-display", "sheet-contents", "sheet-search", "sheet-book", "sheet-edit", "sheet-tts", "sheet-update", "sheet-update-latest", "sheet-stats", "sheet-stats-met", "sheet-stats-empty",
]
# A shot older than this many seconds than the newest one belongs to an earlier run - or to a deleted probe.
# The scratch dir is shared with every other project and keeps files for a day, which is how a settings pane
# from the previous evening was reviewed as if the renderer had just drawn it.
BATCH_WINDOW = 180
DIR = Path("C:/Users/Noah/AppData/Local/hermes/cache/scratch")

HEIGHT = 760      # tall enough to read a 12sp label, short enough that twelve fit one image
GUTTER = 14
LABEL = 22


def main() -> int:
    wanted = sys.argv[1:]
    names = [n for n in ORDER if DIR.joinpath(n + ".png").exists()]
    if wanted:
        names = [n for n in names if any(w.lower() in n.lower() for w in wanted)]
    if not names:
        print(f"no shots in {DIR} matching {wanted or 'the known names'}", file=sys.stderr)
        return 2

    newest = max((DIR / f"{n}.png").stat().st_mtime for n in names)
    stale = [n for n in names if newest - (DIR / f"{n}.png").stat().st_mtime > BATCH_WINDOW]
    names = [n for n in names if n not in stale]
    if stale:
        print(f"skipped {len(stale)} from an earlier run: {', '.join(stale)}", file=sys.stderr)
    if not names:
        print("every matching shot is stale - run tools/gate.sh shots first", file=sys.stderr)
        return 2

    images = []
    for n in names:
        im = Image.open(DIR / f"{n}.png").convert("RGB")
        w = max(1, round(im.width * HEIGHT / im.height))
        images.append((n, im.resize((w, HEIGHT), Image.Resampling.LANCZOS)))

    width = sum(w for _, im in images for w in [im.width]) + GUTTER * (len(images) + 1)
    sheet = Image.new("RGB", (width, HEIGHT + LABEL + GUTTER * 2), (0x0E, 0x11, 0x14))
    draw = ImageDraw.Draw(sheet)

    x = GUTTER
    for name, im in images:
        sheet.paste(im, (x, LABEL + GUTTER))
        draw.text((x, 6), name, fill=(0x83, 0x8D, 0x94))
        x += im.width + GUTTER

    out = DIR / "contact.png"
    sheet.save(out)
    print(f"{out}  {sheet.width}x{sheet.height}  {len(images)} shots: {', '.join(n for n, _ in images)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
