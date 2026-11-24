#!/usr/bin/env bash
# The three Gradle loops this repo actually uses, in one place.
#
#   tools/gate.sh fast            compile only, ~10-50 s        (every edit)
#   tools/gate.sh shots a [b c]   several shots in ONE Gradle run (~25 s, +8 s each); bare `shots` = all.
#                                 A separate invocation per shot pays a Gradle start and a Robolectric boot
#                                 every time, so three shots one-by-one cost about twice the batched run.
#   tools/gate.sh test EpubMetaTest   one test class, ~10-60 s   (while iterating)
#   tools/gate.sh quick           tests only, ~1 min             (the loop; `full` packages the APK)
#   tools/gate.sh lint            lint alone, ~2 min            (iterate on findings without the suite)
#   tools/gate.sh full            tests + lint + assemble, ~3-4 min (before committing)
#   tools/gate.sh reset           stop every Gradle daemon and drop the lint cache by hand
#
#   A --profile run of the full gate: 5m29 total, of which lintAnalyzeDebug 2m51, the unit-test and
#   androidTest lint analyses 1m34 + 1m02, compile 0m40 and everything else under 0m45. Lint is the gate.
#   checkTestSources is off in app/build.gradle.kts for that reason; run `lint` while writing the commit
#   message instead of waiting on it if the change cannot touch the shipped app.
#
# Two things this fixes beyond typing: it never lets a pipe eat the exit code, and it prints the test
# count scraped from the JUnit XML, so "green" is a number rather than the absence of a failure line.
#
# A third, added after a 7-minute full gate died on a file handle: the lint cache is keyed to the toolchain
# that wrote it. Coming back from another branch (AGP 8 <-> AGP 9) reuses a cache whose migrated jars are
# still mapped by the other Gradle's daemon, and lintAnalyzeDebug fails with a FileSystemException that
# looks like a lint finding. So the gate stamps the toolchain it last ran with, stops the daemons and drops
# the cache when that has changed, and if the handle shows up anyway it clears and retries once.
set -uo pipefail

# pwd -W: this bash is MSYS, and native python3 cannot read the /f/... path that plain pwd prints.
HERE="$(cd "$(dirname "$0")" && pwd -W)"
ROOT="$(dirname "$HERE")"
MOD="$ROOT/Kairn"                                   # the Gradle root: gradlew lives one level in
# Always, not "if unset": the java on PATH here is JDK 25, which this Gradle (8.14.7) cannot launch - it fails
# with a bare "What went wrong: 25.0.5.0.2" that says nothing about the JDK, and the run costs minutes.
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
[ -x "$JAVA_HOME/bin/java" ] || { echo "no JDK at $JAVA_HOME" >&2; exit 2; }

LINT_CACHE="$MOD/app/build/intermediates/lint-cache"
STAMP="$ROOT/.git/kairn-gate-toolchain"

# Only Gradle's own daemons, matched on their command line: anything wider (Get-Process java) also takes
# out the IDE, which is the machine's problem for the rest of the afternoon.
kill_daemons() {
  ( cd "$MOD" && ./gradlew --stop >/dev/null 2>&1 )
  powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*GradleDaemon*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1
  sleep 1
}

# The signature is the toolchain, not HEAD: committing does not invalidate a cache, changing the wrapper
# or a build script does.
toolchain_sig() {
  cat "$MOD/build.gradle.kts" "$MOD/app/build.gradle.kts" "$MOD/gradle/wrapper/gradle-wrapper.properties" 2>/dev/null |
    md5sum | cut -c1-16
}

mode="${1:-fast}"; shift || true
case "$mode" in
  fast) tasks=(:app:compileDebugKotlin) ;;
  test) tasks=(:app:testDebugUnitTest); [ $# -gt 0 ] && tasks+=(--tests "$1") ;;
  # Never call ./gradlew yourself for this: a background shell does not inherit an exported JAVA_HOME, so a
  # direct gradlew run dies on the JDK 25 above with a message that says nothing.
  # shots <name> runs one shot while you iterate on it; bare `shots` runs the whole set.
  shots) export KAIRN_SHOTS=1
         tasks=(:app:testDebugUnitTest)
         if [ $# -gt 0 ]; then for n in "$@"; do tasks+=(--tests "app.kairn.Shots.$n"); done
         else tasks+=(--tests "app.kairn.Shots"); fi ;;
  # No assemble: packaging cost ~1 min of every check and produced an APK nobody installs during UI work.
  # `full` still packages one per commit, so a buildable artifact is never more than the next commit away.
  quick) tasks=(:app:testDebugUnitTest) ;;
  lint) tasks=(:app:lintDebug) ;;
  full) tasks=(:app:testDebugUnitTest :app:lintDebug :app:assembleDebug) ;;
  reset) kill_daemons; rm -rf "$LINT_CACHE"; toolchain_sig > "$STAMP"
         echo "daemons stopped, lint cache dropped"; exit 0 ;;
  *) echo "usage: tools/gate.sh [fast | test <Class> | shots [name ...] | quick | lint | full | reset]" >&2; exit 2 ;;
esac

sig="$(toolchain_sig)"
if [ -f "$STAMP" ] && [ "$(cat "$STAMP" 2>/dev/null)" != "$sig" ]; then
  echo "toolchain changed since the last gate - stopping daemons and dropping the lint cache" >&2
  kill_daemons; rm -rf "$LINT_CACHE"
fi
printf '%s' "$sig" > "$STAMP"

LOG="${TMPDIR:-/tmp}/kairn-$(date +%H%M%S)-$mode.log"
# Delete last run's XML: Gradle treats the missing outputs as work to do, so the count below can never be
# a number left over from an earlier run that the task skipped as up to date.
[ "$mode" = fast ] || rm -rf "$MOD/app/build/test-results/testDebugUnitTest"
run() { ( cd "$MOD" && ./gradlew "${tasks[@]}" --console=plain ) > "$LOG" 2>&1; }
run; code=$?

# The one failure that is not about the code: a daemon from the other toolchain still maps the cache.
if [ $code -ne 0 ] && grep -qE "being used by another process|FileSystemException" "$LOG"; then
  echo "lint cache was held by a daemon - clearing it and retrying once" | tee -a "$LOG"
  kill_daemons; rm -rf "$LINT_CACHE"
  run; code=$?
fi

grep -E "^e: |FAILED|BUILD " "$LOG" | tail -20
# `fast` compiles nothing but the app module, so the XML on disk is from whenever the suite last ran: printing a
# count there would be a number about a different build.
# Teed into the log as well as printed: a gate run that is promoted to the background hands its stdout to the
# job runner and the summary line can be lost, leaving a log that says BUILD SUCCESSFUL and nothing else.
[ "$mode" = fast ] || python3 - "$MOD" <<'PY' | tee -a "$LOG"
import glob, re, sys
t = f = k = 0
for p in glob.glob(sys.argv[1] + "/app/build/test-results/testDebugUnitTest/*.xml"):
    s = open(p, encoding="utf-8", errors="ignore").read(1500)
    if (m := re.search(r'tests="(\d+)"', s)): t += int(m.group(1))
    if (m := re.search(r'failures="(\d+)"', s)): f += int(m.group(1))
    if (m := re.search(r'skipped="(\d+)"', s)): k += int(m.group(1))
if t:
    print(f"tests={t} failures={f} skipped={k} ({t - k} actually ran)")
PY

echo "REAL_EXIT=$code log=$LOG" | tee -a "$LOG"
exit $code
