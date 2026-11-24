#!/usr/bin/env python3
"""Adds, reads and checks a string key across all six locales at once.

Every new label in this app is six insertions. Done by hand it is six chances to drop a format argument
or mistype a name, and the failure shows up in the merge, not in the editor. The order of arguments is
fixed here so the same call always lands in the same place.

    python3 tools/strings.py add s_key "English" --es "Español" --fi "Suomi" --ja "日本語" --vi "Tiếng Việt" --zh "简体"
    python3 tools/strings.py show s_key            # the six values, to decide whether to reuse a key
    python3 tools/strings.py check                 # key parity, plural categories, format arguments

`add` refuses a key that already exists anywhere and runs `check` afterwards.
"""
import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RES = next(p for p in [REPO / "app/src/main/res", *sorted(REPO.glob("*/app/src/main/res"))] if p.is_dir())
FILES = {
    "en": RES / "values/strings.xml",
    "es": RES / "values-es/strings.xml",
    "fi": RES / "values-fi/strings.xml",
    "ja": RES / "values-ja/strings.xml",
    "vi": RES / "values-vi/strings.xml",
    "zh": RES / "values-zh-rCN/strings.xml",
}
LANG = {"es": "es", "fi": "fi", "ja": "ja", "vi": "vi", "zh": "zh"}


def values(path):
    return {el.get("name"): (el.text or "") for el in ET.parse(path).getroot() if el.tag == "string"}


def show(key):
    missing = []
    for lang, path in FILES.items():
        v = values(path).get(key)
        print(f"{lang:3} {'—' if v is None else v}")
        if v is None:
            missing.append(lang)
    return 0 if not missing else 1


def add(key, args):
    texts = {"en": args.en, **{lang: getattr(args, lang) for lang in LANG}}
    absent = [lang for lang, t in texts.items() if t is None]
    if absent:
        print(f"missing text for: {', '.join(absent)} — all six are required", file=sys.stderr)
        return 2
    for lang, path in FILES.items():
        if key in values(path):
            print(f"{key} already exists in {lang}; nothing written", file=sys.stderr)
            return 1
    for lang, path in FILES.items():
        text = texts[lang]
        # Escaped XML: a bare apostrophe or backslash in prose breaks mergeDebugResources on a neighbouring line.
        if "'" in text or "\\" in text or "&" in text.replace("&amp;", ""):
            print(f"{lang}: write ’ for an apostrophe, &amp; for an ampersand, never a bare one", file=sys.stderr)
            return 3
        body = path.read_text(encoding="utf-8")
        line = f'    <string name="{key}">{text}</string>\n'
        i = body.rindex("</resources>")
        path.write_text(body[:i] + line + body[i:], encoding="utf-8", newline="")
        print(f"  {lang:3} {text}")
    return subprocess.call([sys.executable, str(REPO / "tools/check_translations.py")])


def set_text(key, args):
    """Rewrites an existing key in all six locales, the six values passed the same way as `add`."""
    texts = {"en": args.en, **{lang: getattr(args, lang) for lang in LANG}}
    absent = [lang for lang, t in texts.items() if t is None]
    if absent:
        print(f"missing text for: {', '.join(absent)} — all six are required", file=sys.stderr)
        return 2
    for lang, path in FILES.items():
        if key not in values(path):
            print(f"{key} is not in {lang}; use add", file=sys.stderr)
            return 1
    for lang, path in FILES.items():
        text = texts[lang]
        if "'" in text or "\\" in text or "&" in text.replace("&amp;", ""):
            print(f"{lang}: write ’ for an apostrophe, &amp; for an ampersand, never a bare one", file=sys.stderr)
            return 3
        body = path.read_text(encoding="utf-8")
        body = re.sub(r'<string name="%s">.*?</string>' % re.escape(key), f'<string name="{key}">{text}</string>', body, count=1)
        path.write_text(body, encoding="utf-8", newline="")
        print(f"  {lang:3} {text}")
    return subprocess.call([sys.executable, str(REPO / "tools/check_translations.py")])


def main():
    ap = argparse.ArgumentParser(add_help=True)
    sub = ap.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("add")
    a.add_argument("key")
    a.add_argument("en")
    for lang in LANG:
        a.add_argument(f"--{lang}")
    s = sub.add_parser("show")
    s.add_argument("key")
    t = sub.add_parser("set")
    t.add_argument("key")
    t.add_argument("en")
    for lang in LANG:
        t.add_argument(f"--{lang}")
    sub.add_parser("check")
    ns = ap.parse_args()
    if ns.cmd == "add":
        return add(ns.key, ns)
    if ns.cmd == "show":
        return show(ns.key)
    if ns.cmd == "set":
        return set_text(ns.key, ns)
    return subprocess.call([sys.executable, str(REPO / "tools/check_translations.py")])


if __name__ == "__main__":
    sys.exit(main())
