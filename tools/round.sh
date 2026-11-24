#!/usr/bin/env bash
# One round of looking: render every shot, then tile them into one contact image.
#
# The expensive part of a UI round is the round, not the pictures - ten shots cost 29 s and one costs 36 s,
# because the JVM and Robolectric's classloader are what you pay for. So the rule is one render per batch of
# fixes, and one image per render. This is that pair as a single command, so "look again" cannot quietly turn
# back into "look at this one shot, then that one".
#
#   tools/round.sh                every shot, then the contact sheet
#   tools/round.sh library sheet  only shots matching those words, then the sheet
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd -W)"
ROOT="$(dirname "$HERE")"

bash "$HERE/gate.sh" shots || exit $?
python3 "$HERE/sheet.py" "$@"
