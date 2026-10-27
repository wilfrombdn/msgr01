# Releasing an MSNGR update

Every release goes out the same way: as a normal **in-app update**. Phones on 4.7 or newer see an
"Update available" pop-up with a short "What's new" list, download the update through Tor and install it.
The same APK is also posted at the usual download link for new installs.

## Steps

1. **Bump the version** in `android/app/build.gradle.kts`: `versionCode` +1 (the updater compares this)
   and `versionName` (shown to people, e.g. `4.9.1`).
2. **Build the signed APK** (the release key lives in `android/keystore/`, git-ignored, never published):
   ```
   cd android
   JAVA_HOME=~/opt/jdk21 ANDROID_HOME=~/Android/Sdk bash ./gradlew assembleRelease
   ```
   Check that it's signed with the usual key. The SHA-256 must start `8a1362da10d38afa`:
   `PATH=~/opt/jdk21/bin:$PATH ~/Android/Sdk/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`
3. **Publish to the download site** (repo `~/wilfrombdn.github.io`, folder `telegramnt/`):
   - `git pull --rebase` first, because the server script also pushes to this repo.
   - Copy the APK to `telegramnt/msgr01.apk` (the download-page link) **and** to `telegramnt/msngr-<version>.apk`.
   - Remove the previous `telegramnt/msngr-*.apk` (`git rm`). Only the newest one is kept.
   - Write `telegramnt/update.json`:
     ```json
     {
      "code": 36,
      "name": "4.9.1",
      "apk": "/telegramnt/msngr-4.9.1.apk",
      "sha256": "<sha256sum of the APK>",
      "notes": ["Short plain-words line per change", "2–4 lines, no jargon"]
     }
     ```
     `notes` is the "What's new" the user sees in the pop-up. Write each line as a brief, plain description
     of what changed for them. Don't name code or technical internals.
   - Commit and push.
4. **Push the source** in this repo (`git push` to github.com/wilfrombdn/msgr01, required because it's AGPL).
   GitHub Actions runs all the tests on every push (`.github/workflows/build.yml`).
5. **Check the live update** once GitHub Pages has updated (about a minute):
   ```
   cd android && UPDATE_CHECK=1 JAVA_HOME=~/opt/jdk21 ANDROID_HOME=~/Android/Sdk bash ./gradlew testDebugUnitTest --tests '*CryptoTest.publishedUpdateIsConsistent'
   ```
   This downloads `update.json` and the APK and checks the SHA-256 matches.
6. If `server/relay.py` changed, restart the server: `systemctl --user restart telegramnt`.
   Server and app changes must stay compatible with phones that haven't updated yet.

## Rules

- Never commit `android/keystore/`, `server/giphy.key` or `server/telegramnt.db`.
- `versionCode` must always go up, or phones won't offer the update.
- The APK must be signed with the same key, or Android refuses to install it over the old one.
- In the app, Settings → "Check for updates" checks right away and shows the result.
