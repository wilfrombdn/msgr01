# Tellem: notes for Claude

- **Don't build or publish an update until the owner says so.** Batch fixes; when asked to release, follow
  `RELEASING.md` exactly: bump the version, build signed, copy the APK + write `update.json` with 2–4 brief plain-words
  notes in `~/wilfrombdn.github.io/telegramnt/`, push both repos, then run the live update check.
  Keep the list below of what's waiting, and use it for the update notes.
- Explain things to the owner in plain words, keep lists short, give one recommendation.
- While working, run only the tests related to the change. The full suite runs on GitHub Actions after each push.
- Build: `cd android && JAVA_HOME=~/opt/jdk21 ANDROID_HOME=~/Android/Sdk bash ./gradlew assembleRelease`.
  `ScreenshotTest` renders screens to `app/build/shots/` (the way to preview UI here).
  `RelayFlowTest` needs a local server: `python3 server/relay.py --port 8099 --db <tmp>/t.db --test-limits`.
- The server runs on a DigitalOcean droplet. After `relay.py` changes: `scp server/relay.py tellem:/opt/tellem/server/ && ssh tellem systemctl restart tellem`. Details in `SERVER-SETUP.md`.
  The app reaches it only through its Tor .onion address (plus the optional Direct mode). Never add a route that bypasses Tor or exposes users' IPs
  without asking the owner first.
- Server backups run nightly from the owner's PC (accounts only, encrypted; see `SERVER-SETUP.md`). Keep the server holding as little as possible.

## Waiting for the next release (since 7.0)
- Nothing yet. 7.0 (phone calls: InCallService + DialerActivity in PhoneCalls.kt) has not been tested on a real phone, so expect fixes.

## Setting up another computer
- Clone this repo and `git@github.com:wilfrombdn/wilfrombdn.github.io.git` to `~/wilfrombdn.github.io` (download page + update.json).
- Signing key: unzip `app-signing-keys-2026-10-26.zip` from Google Drive folder "App signing keys (private)" so that
  `android/keystore/release.jks` and `android/keystore/keystore.properties` exist. Never commit them (this repo is public).
  Without them builds are debug-signed and can't update phones.
- Server login: an SSH config entry `Host tellem` → `root@159.89.125.221`, and an SSH key the droplet accepts.
  `server/giphy.key` and `server/fcm.json` live only on the server; they aren't needed to build the app.
