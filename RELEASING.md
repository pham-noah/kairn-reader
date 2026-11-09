# Releasing Kairn

Everything below was run for 0.8.6 and is written from what happened, not from what should happen.

Two repositories, two jobs. `pham-noah/kairn` is where the work happens (private — it needs credentials even
to read, and the release keystore sits beside it). `pham-noah/kairn-reader` is the public mirror: the updater
points at it, F-Droid will point at it, and the store screenshots are served from it. **Their histories share
no SHAs** — the mirror is a replay, 99 commits against the development repo's 177, with its tip commit
renamed `Kairn <version>`. Nothing fast-forwards between them, so the released tree is compared directly.

## Checklist

1. Version, changelog, release notes, SPEC_STATUS.
2. `test lintDebug assembleRelease`, then prove the signature.
3. Annotated tag in the development repo, pushed.
4. Replay the tree into `kairn-reader`, commit as `Kairn X.Y.Z`, tag, push.
5. Release from the tag, asset named `Kairn-X.Y.Z.apk`; download it back and compare hashes.
6. Bump `fdroid/app.kairn.yml` if F-Droid is in play.

## 1. Version and words

- `Kairn/app/build.gradle.kts` — `versionCode` goes up by one, `versionName` is the release.
- `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` — the few lines users see. **Name the file
  after the new versionCode**, not the versionName: F-Droid matches on the code.
- `kairn-release-notes-X.Y.Z.md` — the full story, one heading per area.
- `SPEC_STATUS.md` — a section for the release, plus the `Distribution state` facts (latest tag, asset name
  and byte count).

The updater compares tags numerically (`data/Update.kt`), so 0.9.0 sorts above 0.8.6 and 0.10.0 above 0.9.0.
A string compare would get both wrong, which is why it is done in numbers.

## 2. Build, and prove the signature

    export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"     # JDK 17 or newer
    cd Kairn && ./gradlew --no-daemon test lintDebug assembleRelease

`keystore.properties` is read from `../../Kairn-keys/` (or `KAIRN_KEYSTORE_PROPS`), and its `storeFile` must
point at the `.jks` **that exists now**. That file is not in either repo and must not be lost: only this key
installs over an existing install. A stale absolute path there does not fail until signing, after the whole
compile has run — check it before a release, not during one.

Without any keystore the release is built unsigned, which is what CI and F-Droid want and what
`-PallowUnsigned` exists to allow.

`apksigner.bat` opens an interactive shell instead of running when it is called from bash, so call the jar:

    JAVA="/c/Program Files/Android/Android Studio/jbr/bin/java.exe"
    JAR="/c/Users/Noah/AppData/Local/Android/Sdk/build-tools/37.0.0/lib/apksigner.jar"
    "$JAVA" -jar "$JAR" verify --print-certs .../app-release.apk | grep -i 'SHA-256 digest'

Compare that digest with the previous release's APK. Equal digests mean this one installs over it; a different
digest means every user has to uninstall and lose their library. Size says nothing about the signer.

    "$BT/aapt" dump badging .../app-release.apk | grep -E "^package|sdkVersion|native-code"

That is the file talking, not Gradle: package, versionCode, versionName, minSdk and the ABI list. A build that
says 0.9.0 in `build.gradle.kts` but 0.8.6 here was not rebuilt.

## 3. Tag the development repo

    git tag -a vX.Y.Z -m "Kairn X.Y.Z"
    git push origin vX.Y.Z

Annotated, message `Kairn <version>` — the same shape as every tag that exists.

Historical note: until 2026-11-04 this repo carried only `v0.1.0` and `v0.8.5`; `v0.2.0`–`v0.8.4` exist only
in the mirror. Tag at release time or the gap grows.

## 4. Mirror to kairn-reader

Replay the released tree (no build output, no keys, no `.gradle`/`.kotlin`), commit it as `Kairn X.Y.Z`, tag
`vX.Y.Z`, push. Then prove the two trees match — this diff, not a merge, is what says the published source is
what was tested:

    diff -rq --exclude=.git --exclude=build --exclude=.gradle --exclude=.kotlin \
        "F:/App Projects/Kairn-related/Kairn" "F:/App Projects/Kairn-related/kairn-reader-public"

Run at 0.8.6, the only output was `Only in .../Kairn/Kairn/app/src: debug`, a gitignored directory. Expect
that plus `local.properties` and anything still unreleased since the last mirror — those are dev-only. Every
other line is a real divergence, and the mirror gets fixed before it is tagged.

## 5. Publish, then check what users actually get

Create the release from the tag with the signed APK as its only asset, **named `Kairn-X.Y.Z.apk`**. 0.8.5 and
0.8.6 set that convention; earlier releases shipped `Kairn-release.apk` and `Kairn-release-084.apk`, which
read badly on the page and leave the updater matching by pattern instead of by name.

    curl -L -o /tmp/Kairn-X.Y.Z.apk <asset url>
    md5sum /tmp/Kairn-X.Y.Z.apk app-release.apk        # equal hashes, equal byte counts

If the numbers differ, the upload is wrong, whatever the page says.

## 6. F-Droid

`fdroid/app.kairn.yml` is a draft, not a submission: MR !52993 was closed on 2026-10-19 against v0.3.0. To
resubmit:

- bump `versionCode`, `version` and `commit` in the recipe together — it currently pins 0.9.6 / 23 and drifts
  by one release every time this is forgotten;
- keep `self_update` false for the store build (`res/values/bools.xml` is the gate; the recipe sets it in a
  `prebuild` sed). F-Droid forbids an app that updates itself, and a store build that offers to is rejected;
- check the recipe parses and the build actually produces an unsigned APK before opening the MR.
