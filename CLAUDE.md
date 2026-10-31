# quipu: notes for Claude

- **Ship every change as an in-app update.** Follow `RELEASING.md` exactly: bump the version, build signed,
  copy the APK + write `update.json` with 2–4 brief plain-words notes in `~/wilfrombdn.github.io/telegramnt/`, push both repos,
  then run the live update check. The owner tests the updater on their phone with every release.
- Explain things to the owner in plain words, keep lists short, give one recommendation.
- While working, run only the tests related to the change. The full suite runs on GitHub Actions after each push.
- Build: `cd android && JAVA_HOME=~/opt/jdk21 ANDROID_HOME=~/Android/Sdk bash ./gradlew assembleRelease`.
  `ScreenshotTest` renders screens to `app/build/shots/` (the way to preview UI here).
  `RelayFlowTest` needs a local server: `python3 server/relay.py --port 8099 --db <tmp>/t.db --test-limits`.
- The server runs on the owner's laptop (`systemctl --user restart telegramnt` after `relay.py` changes),
  reached only through its Tor .onion address. Never add a route that bypasses Tor or exposes users' IPs
  without asking the owner first.
- **Next server step:** move it off the laptop to a Hetzner cloud server. The checklist is in `SERVER-SETUP.md` (section "NEXT STEP").
