# Telegramn't

Private messaging for Android. Messages are end-to-end encrypted on the phone,
and the relay server only passes along sealed boxes it can't open.

- `android/`: the app (Kotlin, plain Views, Google Tink for crypto)
- `server/relay.py`: the server (Python standard library + SQLite)
- `server/free-server.sh`: laptop + Cloudflare quick tunnel; publishes the address to server.json
- `SERVER-SETUP.md`: running the server, what it keeps, and honest limits

## Build

```
cd android
JAVA_HOME=~/opt/jdk21 ANDROID_HOME=~/Android/Sdk bash ./gradlew testDebugUnitTest assembleRelease
cp app/build/outputs/apk/release/app-release.apk ../dist/Telegramnt.apk
```

`RelayFlowTest` runs only if a server is up: `python3 server/relay.py --port 8099 --db /tmp/t.db`,
or set `RELAY=https://…` to test through the tunnel. `ScreenshotTest` writes previews to `app/build/shots/`.
The release key is in `android/keystore/` (git-ignored). **Back it up**: without it, updates
won't install over the old app.

## How it works (2.0)

- Sign up = @username + password. The password goes through PBKDF2 (600k rounds), which gives two keys.
  `auth` goes to the server, which stores a salted hash of it. `backup` never leaves the phone and locks
  (AES-GCM) a backup of the private keys and friend list stored on the server. Logging in on a new phone
  restores friends and signs the old phone out. Five wrong passwords lock the login, and the lock grows each time.
- Each account has an X25519 HPKE key and an Ed25519 signing key (Tink). Its public keys plus a friend-request
  inbox are listed under the username.
- Add friend: look up @user, then send a sealed, signed request to their inbox. It includes a fresh mailbox for
  their replies. The receiver checks the signature against the directory, and Accept sends back their own mailbox.
  Every friendship has its own pair of mailboxes (address = sha256(secret token)).
- Each message = HPKE(their key, Ed25519 signature + padded text), bound to the mailbox address, padded to
  512-byte steps, with replays ignored.
- Safety code = 30 digits from both signing keys. Comparing them catches a server that swapped keys.
- The app finds the server via `wilfrombdn.github.io/telegramnt/server.json` (https only), so it can move without an update.
- No Google push: a foreground service keeps the connection open. Screenshots are blocked, app backups are off,
  notifications say only "New message", and chats on the phone are saved encrypted with an Android Keystore key.

Not yet: forward secrecy (Double Ratchet), groups, pictures, multiple phones per account.
