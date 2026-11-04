#!/usr/bin/env bash
# One-command Tellem release (the steps of RELEASING.md). Usage:
#   ./release.sh 8.1 "Short plain-words note" "Another note" ...      (2-4 notes, no jargon)
# Bumps versionCode +1 and versionName, builds ONE signed release APK (no local tests: GitHub Actions runs them on the push),
# checks the signing key and version, publishes to ~/wilfrombdn.github.io/telegramnt, pushes both repos, then runs the live check.
# Run it alone: never start another Gradle build while it runs (they lock each other's files).
set -euo pipefail
NAME="${1:?version name, e.g. 8.1}"; shift
[ $# -ge 1 ] || { echo "give 1-4 short notes after the version"; exit 1; }
REPO="$(cd "$(dirname "$0")" && pwd)"; SITE="$HOME/wilfrombdn.github.io"; PUB="$SITE/telegramnt"
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot}"; [ -d "$HOME/opt/jdk21" ] && export JAVA_HOME="$HOME/opt/jdk21"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
APKSIGNER="$ANDROID_HOME/build-tools/36.0.0/apksigner"; [ -f "$APKSIGNER.bat" ] && APKSIGNER="$APKSIGNER.bat"
cd "$REPO/android"
OLD=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | grep -oE '[0-9]+'); CODE=$((OLD+1))
sed -i "s/versionCode = $OLD/versionCode = $CODE/; s/versionName = \"[^\"]*\"/versionName = \"$NAME\"/" app/build.gradle.kts
echo "== building $NAME (code $CODE)"
bash ./gradlew assembleRelease -q
APK=app/build/outputs/apk/release/app-release.apk
SIGN=$(PATH="$JAVA_HOME/bin:$PATH" "$APKSIGNER" verify --print-certs "$APK")
grep -q "8a1362da10d38afa" <<< "$SIGN" || { echo "WRONG SIGNING KEY, stopping"; exit 1; }
BADGE=$("$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging "$APK")
grep -q "versionCode='$CODE' versionName='$NAME'" <<< "$BADGE" || { echo "APK version mismatch, stopping"; exit 1; }
echo "== publishing"
# one step per line: with set -e a failed pull (no internet) must stop the release, not carry on in the wrong folder
cd "$SITE"
git pull --rebase -q
cd "$PUB"
cp "$REPO/android/$APK" msgr01.apk && cp msgr01.apk "tellem-$NAME.apk"
for f in msngr-*.apk tellem-*.apk; do [ "$f" = "tellem-$NAME.apk" ] || git rm -q -f "$f" 2>/dev/null || true; done
SHA=$(sha256sum "tellem-$NAME.apk" | cut -d' ' -f1)
{ printf '{\n "code": %s,\n "name": "%s",\n "apk": "/telegramnt/tellem-%s.apk",\n "sha256": "%s",\n "notes": [\n' "$CODE" "$NAME" "$NAME" "$SHA"
  n=$#; i=0; for note in "$@"; do i=$((i+1)); printf '  "%s"%s\n' "${note//\"/\\\"}" "$([ $i -lt $n ] && echo ,)"; done
  printf ' ]\n}\n'; } > update.json
cd "$SITE" && git add telegramnt && git commit -q -m "Tellem $NAME" && git push -q
cd "$REPO" && git add -A && git commit -q -m "$NAME" && git push -q
echo "== live check"
until curl -s https://wilfrombdn.github.io/telegramnt/update.json | grep -q "\"$NAME\""; do sleep 5; done
cd android && UPDATE_CHECK=1 bash ./gradlew testDebugUnitTest --tests '*CryptoTest.publishedUpdateIsConsistent' -q && echo "== $NAME is live and verified"
