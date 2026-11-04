# Tellem server

The server is one file, `server/relay.py`, and only needs Python (no installs; the optional `cryptography` package gives it an
identity key for Direct mode, and Firebase wake-up pings use it too).

**What it keeps** (in `server/telegramnt.db` and `server/files/`):
- usernames and the public keys friends need to find each other
- a backup of each account's keys and friend list, locked with that person's password.
  The server never gets the password, so it can't open it.
- sealed messages and attachments waiting to be picked up, deleted once delivered (or when they expire)
- a hash of each phone's sign-in token (never the token itself)

**What it never keeps:** message text, logs, internet addresses, or who talks to whom.

**Wake-up pings** (`server/fcm.json`, gitignored) are **opt-in** (Settings > Calls, off by default). For a phone that turned them on,
when it has mail waiting but hasn't checked in for 40 seconds, the server sends an empty ping through Google's push service
(Firebase project `msngr-push-d69a71`, owner's Google account) so the closed app wakes and connects. The ping has no content, but
Google learns that this phone uses Tellem and when mail arrives, and the server stores that phone's ping address next to its chat
mailboxes. **The plan is to replace this with my own infrastructure** (a push path that doesn't go through Google); until then it stays
off unless a person chooses it.

---

## Now: DigitalOcean droplet (since 2026-11-01; hardened 2026-11-04, version 9.0)

Droplet **quipu** (the droplet keeps its old name in the DigitalOcean dashboard), Toronto (TOR1), Basic Regular **$4/month**
(1 CPU, 512 MB + 1 GB swap, 8.7 GB disk), Ubuntu 24.04. The IP is in `~/.ssh/config` only (kept out of this public repo).

**Logging in.** Root login is off. Two users can log in, both by key only:
- `tadmin`: the owner's admin key `~/.ssh/tellem_admin`, **protected by a passphrase**, with sudo. `ssh tellem`. The Windows ssh-agent is
  disabled on the owner's PC, so it asks for the passphrase every time; Claude can't type it, so droplet commands are run by the owner
  (or the owner enables the agent: PowerShell as administrator, `Set-Service ssh-agent -StartupType Manual; Start-Service ssh-agent; ssh-add ~/.ssh/tellem_admin`).
- `tbackup`: the nightly backup key `~/.ssh/tellem_backup`. The server forces it to run one root-owned script
  (`/usr/local/sbin/tellem-backup-dump`) and nothing else: no shell, no forwarding. `ssh tellem-backup dump`.

Settings are in `/etc/ssh/sshd_config.d/00-tellem.conf` (no root, no passwords, `AllowUsers tadmin tbackup`, no X11/agent/TCP
forwarding, 3 tries, 20 s grace). The firewall (ufw) allows only SSH, rate-limited; Tor and Cloudflare connect outward.

**Three services**, each its own user and sandboxed by systemd (`systemd-analyze security` says 1.5 "OK" for all three):
- `tellem` (user `tellem`): `relay.py` only. Listens on `127.0.0.1:8080` (the .onion; Tor tags each connection with its circuit,
  `--onion-proxy-protocol`, so rate limits are per Tor circuit instead of one bucket for everyone) and `127.0.0.1:8081` (the Cloudflare
  tunnel; the only place `CF-Connecting-IP` is trusted). Can write only `/opt/tellem/server/`. Unit copy: `server/tellem.service`.
- `tellem-tunnel` (user `tellem-tunnel`): `cloudflared` for Direct mode. Writes the tunnel address to `/run/tellem-tunnel/tunnel.log`.
  Unit copy: `server/tellem-tunnel.service`.
- `tellem-publish` (user `tellem-pub`): the **only** place that holds the GitHub deploy key (write access to `wilfrombdn.github.io`).
  It watches the tunnel log and pushes the new address to `telegramnt/server.json`. Script: `server/publish.sh`, installed as
  `/usr/local/sbin/tellem-publish` (root-owned). Key and page clone live in `/opt/tellem-pub/`, unreadable by the relay.

**Tor:** system tor service, onion key in `/var/lib/tor/tellem/` (owned by `debian-tor`; Ubuntu's AppArmor only lets tor read `/var/lib/tor`).
`/etc/tor/torrc` has `HiddenServicePort 80 127.0.0.1:8080`, `HiddenServiceExportCircuitID haproxy` and `HiddenServicePoWDefensesEnabled 1`
(proof-of-work makes flooding the onion costly). **Same .onion as before**, so the app didn't change. Keep tor and cloudflared updated.

**Limits that stop a flood from taking the server down:** at most 512 MB of database, 2 GB of attachments, 16 MB per group mailbox,
and writes are refused when less than 500 MB of disk is free. A new group mailbox counts against the new-mailbox limit; one nobody ever
fetches is deleted after 6 hours. Limits are per Tor circuit (or per address for Direct). A lockout lasts at most 30 minutes.

**Server identity key** (`/opt/tellem/server/server-id.key`, made on first start, gitignored): the public half is built into the app
(`Messenger.SERVER_ID`). In Direct mode the app asks the server to sign a fresh challenge and sends nothing to a server that can't.
It is included (as comments) in the nightly backup. Losing it means Direct mode is refused until the app is updated with the new public key.

Commands (the owner runs these; each asks for the admin passphrase):
- Check: `ssh tellem "systemctl status tellem tellem-tunnel tellem-publish tor --no-pager"`
- After changing `relay.py`: `scp server/relay.py tellem:/tmp/relay-new.py && ssh tellem "sudo install -m 644 -o tellem -g tellem /tmp/relay-new.py /opt/tellem/server/relay.py && sudo systemctl restart tellem"`
- Test the onion from the droplet: `ssh tellem 'curl --socks5-hostname 127.0.0.1:9050 http://$(sudo cat /var/lib/tor/tellem/hostname)/'`
- Server tests (run on the PC before deploying): `cd server && python -m unittest test_relay -v`
- Files in this repo must keep Unix (LF) line endings: a Windows (CRLF) `.sh` fails on Linux with "bash\r: No such file".

On 2026-11-04 the pre-change database, old unit and `droplet-server.sh` were kept in `/opt/tellem/backup-pre-9.0/` on the droplet
(owned by `tellem`, no private keys in it). Delete that folder once 9.0 has run fine for a week.

The old laptop server (`systemctl --user telegramnt`) is **disabled. Never start it again.** Its database is
out of date, and two machines publishing the same onion fight each other. The laptop copy of
`~/.telegramnt-onion/hs/` is still the onion key backup: keep it.

**Backups (nightly, from the owner's PC):** `tbackup` pulls only the `accounts` and `otpk` tables plus the server identity key, and
the PC locks them with a public key before they touch its disk (nothing is stored on the droplet; the last 7 are kept). Messages,
files, mailboxes and wake-up tokens are never copied. The script refuses to save anything unless the dump really contains the tables
(a failed login can't turn into an empty "backup"). Restore steps are in the owner's `tellem-backups/RESTORE.txt`.

**Logs:** the relay keeps none. On the droplet the firewall log is off, the system journal is memory-only (1 day), and the text logs rotate daily keeping 1.

### Later
- Direct could get a **fixed address** (the server's own https name) instead of the Cloudflare tunnel, and `server.json` could move to
  its own tiny repo so the publisher's key can't touch the app download.
- **Calls over Direct** (right now calls always go through Tor) become possible with a fixed address.
- My own push infrastructure instead of Google's (see above).
- Key rotation for the app signing key now works (v3 signing is on); keep an offline copy of the key.

---

## Honest limits

- **Unreadable:** yes. Only the two phones can open messages. That includes the server,
  Cloudflare, and your internet provider.
- **Untraceable:** mostly. There are no phone numbers, emails or logs. Through Tor, nobody (not even
  the server) sees a phone's internet address. **Direct mode gives that up**: Cloudflare and the server
  can see it, and the app says so before it switches. Usernames are public, so anyone can see that @someone has an account.
- **Who is who:** the server keeps the key directory, so a hacked server could hand out wrong keys to people who have never talked.
  Scanning a friend's QR code checks their real keys; the safety code does the same for the rest. Chats show "not verified" until then.
- **Password:** your password locks your key backup. Since 9.0 it goes through Argon2id (memory-hard, much harder to guess on graphics cards),
  and new passwords need 12+ characters and are checked against common ones. A short or common password could still be guessed by someone
  who stole the server's database. Use a long one.
- **Wake-up pings:** off unless you turn them on, because they involve Google (see above).
- **The phone itself:** if someone unlocks your phone, they can read your chats. Use a screen lock.
