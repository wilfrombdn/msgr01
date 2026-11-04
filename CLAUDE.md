# Tellem: notes for Claude

- **Don't build or publish an update until the owner says so.** Batch fixes; when asked to release, follow
  `RELEASING.md` exactly: bump the version, build signed, copy the APK + write `update.json` with 2–4 brief plain-words
  notes in `~/wilfrombdn.github.io/telegramnt/`, push both repos, then run the live update check.
  Keep the list below of what's waiting, and use it for the update notes.
- **Releasing is one command: `./release.sh <version> "note" "note" ...`** (2–4 plain-words notes). It does every step of
  `RELEASING.md`: bump, ONE signed build, signing-key + version check, publish, push both repos, live check. Use it instead of doing the steps by hand.
- **Keep builds fast:** do not run the full test suite or `ScreenshotTest` before a release (GitHub Actions runs everything on the push);
  run only tests related to the change while working. Never run two Gradle builds at once (they lock each other's files and one fails);
  wait for the first to finish. Leave the Gradle daemon running between builds (don't `--stop` it). On this Windows PC use
  `JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"` (there is no `~/opt/jdk21`).
- The work lives in `~/msgr01` (the live repo). `~/Desktop/tellem` is an old copy: don't edit or release from it.
- Explain things to the owner in plain words, keep lists short, give one recommendation.
- While working, run only the tests related to the change. The full suite runs on GitHub Actions after each push.
- Build: `cd android && JAVA_HOME=~/opt/jdk21 ANDROID_HOME=~/Android/Sdk bash ./gradlew assembleRelease`.
  `ScreenshotTest` renders screens to `app/build/shots/` (the way to preview UI here).
  `RelayFlowTest` needs a local server: `python3 server/relay.py --port 8099 --db <tmp>/t.db --test-limits`.
- The server runs on a DigitalOcean droplet. After `relay.py` changes: `scp server/relay.py tellem:/opt/tellem/server/ && ssh tellem systemctl restart tellem`. Details in `SERVER-SETUP.md`.
  The app reaches it only through its Tor .onion address (plus the optional Direct mode). Never add a route that bypasses Tor or exposes users' IPs
  without asking the owner first.
- Server backups run nightly from the owner's PC (accounts only, encrypted; see `SERVER-SETUP.md`). Keep the server holding as little as possible.

## Waiting for the next release (since 7.7)
- Nothing yet (7.8 shipped the pencil/phone/plus round button with hold-to-big-camera). 7.5 (Snap, Saved Messages), 7.6 (full-screen camera, flash/timer, cleanup into feature files) and 7.7 (hold-and-release camera button, bottom Tellem|Phone switch) and 7.0 (phone calls: InCallService + DialerActivity in PhoneCalls.kt) have not been tested on a real phone, so expect fixes.

## Checking a refactor
- Screens live in feature files (see README "Where things live"); `ScreenshotTest` writes a `.tree.txt` (view tree: types, text, sizes, positions) next to each PNG in `app/build/shots/`. Keep a copy before a refactor and diff afterwards: identical trees = same UI. Five call/download screens have live timers and always differ.
- `RelayFlowTest` is skipped when no server is running (this PC has no Python), so push and let GitHub Actions run it before trusting changes to `Core.kt`, `Relay.kt` or `Messenger.kt`.
- Camera shutter timing: `adb logcat -s TellemPerf` prints how long each photo took from the tap to saved. Capture mode is MINIMIZE_LATENCY on purpose (MAXIMIZE_QUALITY made the shutter feel slow).
- `./gradlew lintDebug` should stay at 0 errors. Do not move the launcher icon out of `mipmap-anydpi-v26` (release build breaks).

## Setting up another computer
- Clone this repo and `git@github.com:wilfrombdn/wilfrombdn.github.io.git` to `~/wilfrombdn.github.io` (download page + update.json).
- Signing key: unzip `app-signing-keys-2026-10-26.zip` from Google Drive folder "App signing keys (private)" so that
  `android/keystore/release.jks` and `android/keystore/keystore.properties` exist. Never commit them (this repo is public).
  Without them builds are debug-signed and can't update phones.
- Server login: an SSH config entry `Host tellem` → `root@159.89.125.221`, and an SSH key the droplet accepts.
  `server/giphy.key` and `server/fcm.json` live only on the server; they aren't needed to build the app.
