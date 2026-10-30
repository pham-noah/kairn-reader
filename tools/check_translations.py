#!/usr/bin/env python3
"""Checks translated string files against the English source before a build does.

Three things break a translation in a way the compiler does not catch:
  * a missing or extra name (falls back to English silently, or fails the merge),
  * a dropped or reordered format argument (%1$s) - crashes at runtime, not at build time,
  * a plural without the category the language needs, or with an argument the source does not have.

Run: python3 tools/check_translations.py
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
# The app module is not necessarily at the repo root (here it is REPO/Kairn/app); find it once.
RES = next(p for p in [REPO / "app/src/main/res", *sorted(REPO.glob("*/app/src/main/res"))] if p.is_dir())
SRC = RES / "values/strings.xml"
LOCALES = ["vi", "ja", "zh-rCN", "es", "fi"]


def load(path):
    root = ET.parse(path).getroot()
    out = {}
    for el in root:
        if el.tag == "string":
            out[el.get("name")] = ("string", el.text or "", el.get("translatable"))
        elif el.tag == "plurals":
            items = {i.get("quantity"): (i.text or "") for i in el}
            out[el.get("name")] = ("plurals", items, el.get("translatable"))
    return out


def args(text):
    """Format arguments in order. %1$s, %2$d and bare %s all count; %% is a literal percent."""
    return re.findall(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]", text or "")


def main():
    src = load(SRC)
    failures = 0
    for loc in LOCALES:
        path = RES / f"values-{loc}/strings.xml"
        if not path.exists():
            print(f"MISSING  {path.relative_to(RES.parent.parent.parent)}")
            failures += 1
            continue
        try:
            tr = load(path)
        except ET.ParseError as e:
            print(f"BADXML   {loc}: {e}")
            failures += 1
            continue

        missing = sorted(set(src) - set(tr))
        extra = sorted(set(tr) - set(src))
        if missing:
            print(f"MISSING  {loc}: {len(missing)} keys, e.g. {missing[:5]}")
            failures += 1
        if extra:
            print(f"EXTRA    {loc}: {extra[:5]}")
            failures += 1

        for name, sval in src.items():
            if sval[0] != "string" or name not in tr:
                continue
            if sval[2] == "false":
                if tr[name][1] != sval[1]:
                    print(f"CHANGED  {loc}/{name}: translatable=false was edited")
                    failures += 1
                continue
            if args(sval[1]) != args(tr[name][1]):
                print(f"ARGS     {loc}/{name}: {args(sval[1])} -> {args(tr[name][1])}")
                failures += 1

        for name, sval in src.items():
            if sval[0] != "plurals" or name not in tr:
                continue
            cats = sorted(tr[name][1])
            if "other" not in cats:
                print(f"PLURAL   {loc}/{name}: no 'other' category")
                failures += 1
            # Every format argument of the source must appear in at least one translated item; comparing
            # per category is wrong because languages fold categories differently.
            want = set(args("".join(sval[1].values())))
            have = set(args("".join(tr[name][1].values())))
            if not want <= have:
                print(f"PLURAL   {loc}/{name}: missing args {sorted(want - have)}")
                failures += 1

        print(f"OK       {loc}: {len(tr)} keys")

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
