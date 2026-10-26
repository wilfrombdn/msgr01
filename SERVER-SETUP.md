# Telegramn't server

The server is one file, `server/relay.py`, and only needs Python (no installs).

**What it keeps** (in `server/telegramnt.db`):
- usernames and the public keys friends need to find each other
- a backup of each account's keys and friend list, locked with that person's password.
  The server never gets the password, so it can't open it.
- sealed messages waiting to be picked up, deleted as soon as they're delivered

**What it never keeps:** message text, logs, internet addresses, or who talks to whom.

---

## Now: free, on this laptop

It already runs by itself whenever the laptop is on (a background service).
Cloudflare gives it a secure `https://….trycloudflare.com` address (free, no account),
which also hides your home internet address. The address changes each time it starts,
so the script posts the new one to `wilfrombdn.github.io/telegramnt/server.json`,
and the app looks it up there by itself.

- Check it:  `systemctl --user status telegramnt`
- Stop it:   `systemctl --user stop telegramnt` (`disable` stops it starting with the laptop)
- Start it:  `systemctl --user start telegramnt`

Limit: chats only work while the laptop is on and awake. Messages sent while it's
off fail and can be retried. Quick tunnels are meant for testing, not heavy use.

---

## Later: always on (free Google Cloud server)

Oracle's sign-up wasn't working (2026-10-26). Google Cloud has an "always free" e2-micro server instead.
It needs a Google account and a card for ID, and stays $0 within the free limits.

1. https://console.cloud.google.com, then Compute Engine, then Create instance:
   **e2-micro**, region **us-west1 / us-central1 / us-east1** (only these are free),
   Debian, 30 GB standard disk, and tick "Allow HTTPS" and "Allow HTTP".
2. Add your laptop's SSH key (`~/.ssh/id_ed25519.pub`) under Security > SSH keys.
3. Give Claude the server's IP address. Claude copies the database across, sets up https with Caddy,
   and points `server.json` at it. The app follows by itself, and accounts and friends carry over.

---

## Hiding even more: Tor

The server sees which internet address connects, though it doesn't save it.
To hide even that, install **Orbot** on the phone and turn on VPN mode for Telegramn't.

---

## Honest limits

- **Unreadable:** yes. Only the two phones can open messages. That includes the server,
  Cloudflare, and your internet provider.
- **Untraceable:** mostly. There are no phone numbers, emails or logs. But whoever runs the server
  could watch it *while it runs* and see a mailbox get a message at 9:02. Usernames are public,
  so anyone can see that @someone has an account. Tor hides internet addresses too.
- **Password:** your password locks your key backup. A short or common one could be guessed by
  someone who stole the server's database. Use a long one.
- **Old messages:** if someone stole a phone's secret keys, they could open old messages they had
  secretly recorded. Signal prevents this by changing keys every message, and that's the next upgrade.
- **The phone itself:** if someone unlocks your phone, they can read your chats. Use a screen lock.
- **Regular Telegram** chats were never end-to-end encrypted, only "Secret Chats" were.
  In Telegramn't, every chat works like a Secret Chat.
