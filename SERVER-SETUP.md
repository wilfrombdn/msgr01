# Tellem server

The server is one file, `server/relay.py`, and only needs Python (no installs).

**What it keeps** (in `server/telegramnt.db` and `server/files/`):
- usernames and the public keys friends need to find each other
- a backup of each account's keys and friend list, locked with that person's password.
  The server never gets the password, so it can't open it.
- sealed messages and attachments waiting to be picked up, deleted once delivered (or when they expire)

**What it never keeps:** message text, logs, internet addresses, or who talks to whom.

**Wake-up pings** (`server/fcm.json`, gitignored): when a phone has mail waiting but hasn't checked in for
40 seconds, the server sends an empty ping through Google's push service (Firebase project `msngr-push-d69a71`,
owner's Google account) so the closed app wakes and connects. The ping has no content. The key can only send pings.

---

## Now: DigitalOcean droplet (since 2026-11-01)

Moved off the laptop on 2026-11-01. Hetzner's cheap size was sold out, so it's on **DigitalOcean**:
droplet **quipu** (the droplet keeps its old name in the DigitalOcean dashboard), Toronto (TOR1), Basic Regular **$4/month** (1 CPU, 512 MB + 1 GB swap), Ubuntu 24.04.
IP is in `~/.ssh/config` (kept out of this public repo). Log in from the laptop: `ssh tellem` (laptop key `~/.ssh/id_ed25519`).

Layout on the droplet:
- `/opt/tellem/server/`: `relay.py`, `telegramnt.db`, `files/`, `fcm.json`, `giphy.key`, `droplet-server.sh`, owned by user `tellem`
- Onion key: `/var/lib/tor/tellem/` (owned by `debian-tor`; Ubuntu's AppArmor only lets tor read `/var/lib/tor`).
  Set in `/etc/tor/torrc`. **Same .onion as before**, so the app didn't change.
- `tellem.service` (copy in `server/tellem.service`) runs `droplet-server.sh`: the relay plus the Cloudflare tunnel for Direct.
  On every start it pushes the new tunnel address to `server.json` through a sparse clone at `/opt/tellem/page`,
  using a GitHub deploy key ("quipu droplet") with write access to `wilfrombdn.github.io` only.
- The relay listens on two local ports: **8080** for the .onion (client-address headers ignored) and **8081** for the
  Cloudflare tunnel (the only place `CF-Connecting-IP` is trusted). Both share one set of limits, call rooms and data.
- Firewall (ufw): only SSH is open. Tor and Cloudflare connect outward.

Commands (run on the droplet):
- Check: `systemctl status tellem tor`
- After changing `relay.py`: `scp server/relay.py tellem:/opt/tellem/server/ && ssh tellem systemctl restart tellem`
- Test the onion from the droplet: `curl --socks5-hostname 127.0.0.1:9050 http://$(cat /var/lib/tor/tellem/hostname)/`

The old laptop server (`systemctl --user telegramnt`) is **disabled. Never start it again.** Its database is
out of date, and two machines publishing the same onion fight each other. The laptop copy of
`~/.telegramnt-onion/hs/` is still the onion key backup: keep it.

**Backups (set up 2026-11-03):** every night the owner's PC pulls only the `accounts` and `otpk` tables over SSH and locks them with a public key before they touch its disk (nothing is stored on the droplet; the last 7 are kept). Messages, files, mailboxes and wake-up tokens are never copied. Restore steps are in the owner's `tellem-backups` folder. **To do:** keep tor up to date (Ubuntu's tor lags; consider the deb.torproject.org repo).

**Logs:** the relay keeps none. On the droplet the firewall log is off, the system journal is memory-only (1 day), and the text logs rotate daily keeping 1. SSH accepts keys only. The data files are readable only by the `tellem` user (`UMask=0077`).

### Later
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
