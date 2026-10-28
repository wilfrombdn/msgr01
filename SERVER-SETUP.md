# MSNGR server

The server is one file, `server/relay.py`, and only needs Python (no installs).

**What it keeps** (in `server/telegramnt.db` and `server/files/`):
- usernames and the public keys friends need to find each other
- a backup of each account's keys and friend list, locked with that person's password.
  The server never gets the password, so it can't open it.
- sealed messages and attachments waiting to be picked up, deleted once delivered (or when they expire)

**What it never keeps:** message text, logs, internet addresses, or who talks to whom.

---

## Now: on the laptop (temporary)

It runs as a background service whenever the laptop is on (`server/free-server.sh`):

- **Tor .onion address** (the app's normal way in). The address comes from the key in
  `~/.telegramnt-onion/hs/`. **Back that folder up: losing it means a new address and a new app build.**
- **Cloudflare quick tunnel**, used by the app's **Direct** mode (Settings > Connection). Its address
  changes on every start, so the script posts it to `wilfrombdn.github.io/telegramnt/server.json`
  and the app looks it up there.

Commands:
- Check: `systemctl --user status telegramnt`
- Restart (needed after changing `relay.py`): `systemctl --user restart telegramnt`
- Stop: `systemctl --user stop telegramnt` (`disable` stops it starting with the laptop)

**The problem:** chats only work while the laptop is on and online. Messages wait on the phones
until it's back. That's why the server is moving.

---

## NEXT STEP: move to a Hetzner cloud server

Decided 2026-10-28. Instead of the laptop: always on, cheap, and easy to make bigger later.

**Why Hetzner:** the smallest cloud server (**CX22**: 2 CPUs, 4 GB memory) is about **€4–5 a month**
(prices changed in 2026, so check https://www.hetzner.com/cloud). The server needs about 60 MB of memory,
so this has lots of room. To scale up later, pick a bigger size in the Hetzner dashboard and restart:
a few minutes down, nothing else changes.

- **Backup choice:** DigitalOcean's $4/month server is smaller but enough for now, and also easy to make bigger.
- **Not Render:** its free tier goes to sleep and can't keep files (messages wouldn't arrive and the
  database would be wiped). Paid Render is about $7/month plus storage, which costs more for less.
- **Privacy:** Hetzner may ask for ID at sign-up. The server still can't read anything: chats stay
  end-to-end encrypted and the app reaches the server through Tor.

### What the owner does (needs payment, so it has to be you)
1. Sign up at https://www.hetzner.com/cloud.
2. Create a server: **CX22**, **Ubuntu 24.04**, a location near you
   (the US locations are Ashburn or Hillsboro).
3. Under SSH keys, add the laptop's public key: `cat ~/.ssh/id_ed25519.pub`
   (if that file doesn't exist, ask Claude to make one).
4. Give Claude the server's **IP address**.

### What Claude does
1. Install `python3`, `tor` and `cloudflared` on the server, and set up a firewall
   (only SSH open; Tor and Cloudflare connect outward, so no web ports are needed).
2. Stop the laptop server: `systemctl --user stop telegramnt`, so nothing changes mid-copy.
3. Copy over: `server/relay.py`, `server/telegramnt.db`, `server/files/`, `server/giphy.key`,
   and the onion key folder `~/.telegramnt-onion/hs/` (it must keep `chmod 700`).
4. Run it as a system service (relay + Tor onion service with the same key, + Cloudflare tunnel for Direct).
   For Direct, the server needs to publish its tunnel address to `server.json`: give it a GitHub
   **deploy key** for the `wilfrombdn.github.io` repo (write access to that repo only).
5. Check from outside: the onion answers through Tor, and the Direct address answers.
6. Turn off the laptop server for good: `systemctl --user disable --now telegramnt`.

**The app doesn't change.** It's the same .onion address, so there's no update, and nobody re-adds friends.

### Later, once it's on Hetzner
- Direct could get a **fixed address** (the server's own https name) instead of the Cloudflare tunnel.
  That needs a small app change, because the app currently only accepts `*.trycloudflare.com`.
- **Calls over Direct** (right now calls always go through Tor) become possible with a fixed address.
- Video calls will run much better than they would over Tor through the laptop.

---

## Honest limits

- **Unreadable:** yes. Only the two phones can open messages. That includes the server,
  Cloudflare, and your internet provider.
- **Untraceable:** mostly. There are no phone numbers, emails or logs. Through Tor, nobody (not even
  the server) sees a phone's internet address. **Direct mode gives that up**: Cloudflare and the server
  can see it. Usernames are public, so anyone can see that @someone has an account.
- **Password:** your password locks your key backup. A short or common one could be guessed by
  someone who stole the server's database. Use a long one.
- **The phone itself:** if someone unlocks your phone, they can read your chats. Use a screen lock.
