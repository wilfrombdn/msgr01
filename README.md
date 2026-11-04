# Tellem

Private messaging for Android. Messages are end-to-end encrypted on the phone,
and the relay server only passes along sealed boxes it can't open.

(The repo, package name, and download URLs keep the old `telegramnt`/`msgr01`
identifiers so existing links and installs don't break; only the app's display
name has changed.)

- `android/`: the app (Kotlin, plain Views, Google Tink for crypto)
- `server/relay.py`: the server (Python standard library + SQLite)
- `server/free-server.sh`: laptop + Cloudflare quick tunnel; publishes the address to server.json
- `SERVER-SETUP.md`: running the server, what it keeps, and honest limits
- `RELEASING.md`: how every update is shipped (in-app update + download link)

## Where things live (`android/app/src/main/java/com/wilfrombdn/telegramnt/`)

- **Engine (no Android UI):** `Core.kt` (chat rules, crypto, calls), `Model.kt` (Msg, Contact, Me, Group), `Crypto.kt`,
  `SignalStore.kt`, `Relay.kt`, `Net.kt`, `Tor.kt`, `Messenger.kt` (the app-wide singleton that owns the Core and the settings).
- **Texts (SMS/MMS):** `Texts.kt`, `Mms.kt`, `TextReceivers.kt`, `PhoneCalls.kt`, `Phone.kt`.
- **Screens:** `MainActivity.kt` holds the lifecycle and the state; every screen is its own file of extension functions on it
  (`ChatList.kt`, `Chat.kt`, `Groups.kt`, `Settings.kt`, `Snap.kt`, `Saved.kt`, `TextScreens.kt`, `PhoneTab.kt`, `Media.kt`, ...).
  Each file starts with a one-line description. `Screen.kt` is the list of pages, `RequestCodes.kt` the activity-result codes,
  `UiPrefs.kt` the on/off choices stored on the phone, `Ui.kt` the shared view helpers.
- **Other activities:** `CameraActivity.kt` (photo, video, snap captions, flash, timer), `CallActivity.kt`, `RoundActivity.kt`, `QrActivity.kt`.

## Build

```
cd android
JAVA_HOME=~/opt/jdk21 ANDROID_HOME=~/Android/Sdk bash ./gradlew testDebugUnitTest assembleRelease
cp app/build/outputs/apk/release/app-release.apk ../dist/Tellem.apk
```

`RelayFlowTest` runs only if a server is up: `python3 server/relay.py --port 8099 --db /tmp/t.db`,
or set `RELAY=https://…` to test through the tunnel. `ScreenshotTest` writes previews to `app/build/shots/`.
The release key is in `android/keystore/` (git-ignored). **Back it up**: without it, updates
won't install over the old app.

## How it works (2.0)

- Sign up = @username + password (12+ characters, checked against common passwords). The password goes through Argon2id
  (32 MiB, 3 passes), which gives two keys. Accounts made before 9.0 used PBKDF2 (600k rounds) and move to Argon2id the next time they
  log in. `auth` goes to the server, which stores a salted hash of it. `backup` never leaves the phone and locks
  (AES-GCM) a backup of the private keys and friend list stored on the server. Logging in on a new phone
  restores friends and signs the old phone out. Ten wrong passwords in a row start a short lock (1 minute, doubling, never over 30 minutes);
  guesses are forgotten after a day.
- Each account has an X25519 HPKE key and an Ed25519 signing key (Tink). Its public keys plus a friend-request
  inbox are listed under the username.
- Add friend: look up @user, then send a sealed, signed request to their inbox. It includes a fresh mailbox for
  their replies. The receiver checks the signature against the directory, and Accept sends back their own mailbox.
  Every friendship has its own pair of mailboxes (address = sha256(secret token)).
- Each message = HPKE(their key, Ed25519 signature + padded text), bound to the mailbox address, padded to
  512-byte steps, with replays ignored.
- Safety code = 30 digits from both signing keys. Comparing them catches a server that swapped keys.
- The app finds the server via `wilfrombdn.github.io/telegramnt/server.json` (https only), so it can move without an update.
- A foreground service keeps the connection open, so no Google is involved by default. **Wake-up pings through Google's push service
  are opt-in** (Settings > Calls, off by default; the app doesn't even start Firebase until they're turned on). I want to eventually develop
  my own infrastructure for this, so wake-ups don't have to go through Google at all.
  Screenshots are blocked, app backups are off, notification text is off until you turn it on (names can be turned off too),
  and chats on the phone are saved encrypted with an Android Keystore key.

Not yet: forward secrecy (Double Ratchet), groups, pictures, multiple phones per account.


## Who you're talking to, and the connection (9.0)

- **Tor by default.** The server's .onion address is built into the app, so the address itself proves which server you reached,
  and nobody sees your phone's internet address.
- **Direct mode is optional and less private.** It skips Tor and goes through a Cloudflare tunnel (faster, works where Tor is blocked).
  Cloudflare and the server can see your internet address. The app shows a plain warning before it switches on, checks the certificate
  name on every connection, and refuses to send anything to a server that can't prove it holds the server's own key (built into the app).
- **Add friends by QR code** to be sure of who they are: their code carries a fingerprint of their keys, and the app refuses to add them
  if the server's directory gives different keys. Chats show "not verified" until you've scanned their code or compared the safety code.
- **Group invitations are requests.** A friend can invite you, but you only join (and show your username to the group) when you tap Join.
- **Pictures from other people can't crash the app:** thumbnails and photos are size-checked before they're decoded.
- **Updates** are installed only if the file matches the checksum in the update list; Android also refuses any update not signed with
  Tellem's key (v2 + v3 signing, so the key can be rotated if it is ever lost).
- Builds check every downloaded library against `android/gradle/verification-metadata.xml`.
