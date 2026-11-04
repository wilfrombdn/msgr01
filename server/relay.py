#!/usr/bin/env python3
"""Tellem server (the name in the code and the "telegramnt" reply below are kept so existing apps keep working).

What it keeps (in one SQLite file):
  - accounts: @username, the public keys friends need, the address of the
    friend-request inbox, and a password-locked backup it cannot open.
    It never gets the password itself, only a key made from it, and stores
    only a salted hash of that.
  - mailboxes, sealed messages and sealed attachments (in files/) waiting for delivery. Messages are
    end-to-end encrypted on the phone; the server can't read them and
    deletes each one as soon as it's picked up.

What it never keeps: logs, internet addresses, who talks to whom, message text.

Mailboxes: the owner makes a random secret "token"; the mailbox address is
sha256(token). Senders only know the address, so they can drop messages in
but never read or delete them.

Python 3.9+ standard library only. Run:  python3 relay.py --port 8080 --db telegramnt.db
"""

import argparse
import contextlib
import concurrent.futures
import urllib.parse
import urllib.error
import urllib.request
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import shutil
import socket
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY = 256 * 1024           # biggest request we accept
MAX_BLOB = 32 * 1024            # biggest sealed message (Signal overhead + a padded avatar)
MAX_BACKUP = 96 * 1024          # biggest password-locked backup
MAX_KEY = 512                   # biggest public key
MAX_PER_BOX = 1000              # messages waiting in one mailbox
MAX_PER_GROUP = 5000           # messages kept in one group mailbox
MAX_GROUP_BYTES = 16 * 1024 * 1024   # sealed bytes kept in one group mailbox (oldest go first)
GBOX_GRACE = 6 * 3600           # a group mailbox nobody has ever fetched is dropped after this (spam never gets polled)
DB_MAX_MB = 512                 # refuse new mail when the database file is this big
MIN_FREE_MB = 300               # refuse new mail/files when the disk has less than this free
FREE_TRIES = 10                 # wrong passwords an account may collect before it starts locking
LOCK_MAX = 30 * 60              # longest a login lock lasts
FAILS_FORGET = 24 * 3600        # wrong guesses are forgotten after this long without a new one
PIPE_PER_SOURCE = 4             # call rooms one client (circuit) may hold at once
MAX_TOKENS_PER_FETCH = 500
MAX_WAIT = 30                   # seconds a fetch may wait for new mail
MSG_TTL = 30 * 24 * 3600        # undelivered messages are dropped after this
GROUP_MSG_TTL = 7 * 24 * 3600   # group mailboxes fan out (no per-reader delete), so keep them a short time
BOX_TTL = 365 * 24 * 3600       # mailboxes nobody checks for a year are dropped
RATE_PER_MIN = 120              # requests per minute from one address
GIF_RATE_PER_MIN = 240          # GIF searches/sends (own budget so GIFs never crowd out messages)
GIF_PAGE = 24
PIPE_WAIT = 120                 # seconds a caller's call room waits for the other phone
PIPE_IDLE = 60                  # a call with no audio either way for this long is dropped
PIPE_MAX_SECONDS = 4 * 3600     # longest call
PIPE_MAX_ACTIVE = 40            # call rooms open at once
ROOM = re.compile(r"^[0-9a-f]{64}$")
LOOPBACK = ("127.0.0.1", "::1")
GIF_MAX = 8 * 1024 * 1024       # a single GIF we'll pass on
GIPHY_KEY_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "giphy.key")
NEW_BOXES_PER_MIN = 20          # new mailboxes per minute from one address
PUSH_KEY_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fcm.json")   # Firebase service account (gitignored)
PUSH_QUIET = 40                 # a mailbox checked this recently belongs to a connected phone: no ping needed
PUSH_GAP = 4                    # at most one wake-up ping per phone this often
SIGNUPS_PER_HOUR = 5            # new accounts per hour from one address
MAX_CONNECTIONS = 2000
MAX_FILE = 25 * 1024 * 1024     # biggest attachment (already encrypted by the phone)
UPLOAD_PER_HOUR = 300 * 1024 * 1024  # attachment bytes per hour from one address

APP_NAME = "Tellem"
USERNAME = re.compile(r"^[a-z0-9_]{5,32}$")
HEX = set("0123456789abcdef")


def hour():
    """Stored times are rounded, so they say little about when someone was active."""
    return float(int(time.time() // 3600) * 3600)


def day():
    return float(int(time.time() // 86400) * 86400)


def is_hex32(s):
    return isinstance(s, str) and len(s) == 64 and set(s) <= HEX


def b64(s, limit):
    """Decode base64 or return None."""
    if not isinstance(s, str) or len(s) > limit * 2:
        return None
    try:
        b = base64.b64decode(s, validate=True)
    except (ValueError, TypeError):
        return None
    return b if 0 < len(b) <= limit else None


# Rate limits need to tell clients apart, but internet addresses are never kept, not even
# in memory: each one is replaced by a keyed hash, and the key is random per run and never saved.
_FORGET_KEY = secrets.token_bytes(32)


def blind(who):
    return hmac.new(_FORGET_KEY, who.encode(), hashlib.sha256).digest()[:12]


def dev_hash(device):
    """Device tokens are 256-bit random bearer tokens; only their hash is kept, so a stolen database can't sign in as anyone."""
    return hashlib.sha256(device.encode()).digest()


class Counter:
    """Per-client counts for the current time window. Memory only, never written out."""

    def __init__(self, window):
        self.window = window
        self.lock = threading.Lock()
        self.slot = 0
        self.counts = {}

    def add(self, who, n, limit):
        s = int(time.time() // self.window)
        with self.lock:
            if s != self.slot:
                self.slot, self.counts = s, {}
            total = self.counts.get(who, 0) + n
            if total > limit:
                return False
            self.counts[who] = total
            return True


class Store:
    def __init__(self, path, files_dir, files_cap):
        self.files_dir = files_dir
        self.files_cap = files_cap
        os.makedirs(files_dir, exist_ok=True)
        self.db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA secure_delete=ON")  # deleted messages are overwritten on disk
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS accounts(
                user TEXT PRIMARY KEY, salt BLOB, auth BLOB, enc BLOB, sig BLOB, inbox TEXT,
                backup BLOB, device TEXT, fails INTEGER DEFAULT 0, locked REAL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS boxes(addr TEXT PRIMARY KEY, seen REAL);
            CREATE TABLE IF NOT EXISTS msgs(id TEXT PRIMARY KEY, addr TEXT, ts REAL, blob BLOB);
            CREATE INDEX IF NOT EXISTS msgs_addr ON msgs(addr);
            CREATE TABLE IF NOT EXISTS files(id TEXT PRIMARY KEY, size INTEGER, ts REAL);
            CREATE TABLE IF NOT EXISTS otpk(user TEXT, id INTEGER, pub BLOB, PRIMARY KEY(user, id));
            CREATE TABLE IF NOT EXISTS gmsgs(seq INTEGER PRIMARY KEY AUTOINCREMENT, addr TEXT, ts REAL, blob BLOB);
            CREATE INDEX IF NOT EXISTS gmsgs_addr ON gmsgs(addr, seq);
            CREATE TABLE IF NOT EXISTS push(addr TEXT PRIMARY KEY, token TEXT);
            CREATE TABLE IF NOT EXISTS gboxes(addr TEXT PRIMARY KEY, created REAL, fetched REAL);
        """)
        # per-file retention hint (group files ask for a shorter life than 1:1); added live, so migrate in place
        try:
            self.db.execute("ALTER TABLE files ADD COLUMN ttl REAL")
        except sqlite3.OperationalError:
            pass  # column already exists
        # Signal keys: identity, registration id, signed prekey and Kyber (post-quantum) prekey
        cols = [r[1] for r in self.db.execute("PRAGMA table_info(accounts)")]
        # dh = sha256 of the device token (the token itself is never stored), kdf = which password hashing the account uses
        # (1 = PBKDF2, 2 = Argon2id), lastfail = when the last wrong password came
        for col, kind in (("ident", "BLOB"), ("reg", "INTEGER"), ("spk", "TEXT"), ("kpk", "TEXT"),
                          ("dh", "BLOB"), ("kdf", "INTEGER DEFAULT 1"), ("lastfail", "REAL DEFAULT 0")):
            if col not in cols:
                self.db.execute("ALTER TABLE accounts ADD COLUMN %s %s" % (col, kind))
        # one-time migration: keep only the hash of every device token, then blank the plaintext
        for user, device in self.db.execute("SELECT user, device FROM accounts WHERE dh IS NULL").fetchall():
            self.db.execute("UPDATE accounts SET dh=?, device='' WHERE user=?", (dev_hash(device or ""), user))
        # group mailboxes that existed before the gboxes table: treat them as in use
        now = time.time()
        self.db.execute("INSERT OR IGNORE INTO gboxes SELECT addr, ?, ? FROM (SELECT DISTINCT addr FROM gmsgs)", (now, now))
        self.db_path = path
        self.db_max = DB_MAX_MB * 1024 * 1024
        self.min_free = MIN_FREE_MB * 1024 * 1024
        self._space = (0.0, True)
        self.new_boxes_limit = NEW_BOXES_PER_MIN
        self.bundle_limit = 30
        self.lock = threading.RLock()
        self.cond = threading.Condition(self.lock)
        self.checked = {}        # mailbox -> when a phone last checked it (memory only, never saved)
        self.pusher = None       # set in main() when fcm.json exists

    def q(self, sql, *args):
        with self.lock:
            return self.db.execute(sql, args).fetchall()

    def space_ok(self, extra=0):
        """False when the database is at its cap or the disk is nearly full: new mail is refused instead of
        letting a flood fill the disk and stop the whole server. Checked at most every few seconds."""
        now = time.time()
        at, ok = self._space
        if now - at > 3:
            try:
                size = sum(os.path.getsize(self.db_path + s) for s in ("", "-wal") if os.path.exists(self.db_path + s))
                ok = size < self.db_max and shutil.disk_usage(os.path.dirname(os.path.abspath(self.db_path))).free > self.min_free
            except OSError:
                ok = False
            self._space = (now, ok)
        if not ok:
            return False
        try:
            return shutil.disk_usage(self.files_dir).free > self.min_free + extra
        except OSError:
            return False

    # ---- mailboxes ----

    def touch(self, addresses, who, new_boxes):
        now = day()
        with self.lock:
            known = set()
            for i in range(0, len(addresses), 400):
                part = addresses[i:i + 400]
                known.update(r[0] for r in self.db.execute(
                    "SELECT addr FROM boxes WHERE addr IN (%s)" % ",".join("?" * len(part)), part))
            new = [a for a in addresses if a not in known]
            if new and not new_boxes.add(who, len(new), self.new_boxes_limit):
                return "slow down"
            self.db.executemany("INSERT OR REPLACE INTO boxes VALUES(?,?)", [(a, now) for a in addresses])
            return None

    def put(self, address, blob):
        with self.cond:
            if not self.db.execute("SELECT 1 FROM boxes WHERE addr=?", (address,)).fetchone():
                return "no such mailbox"
            n = self.db.execute("SELECT COUNT(*) FROM msgs WHERE addr=?", (address,)).fetchone()[0]
            if n >= MAX_PER_BOX:
                return "mailbox full"
            if not self.space_ok(len(blob)):
                return "server full"
            self.db.execute("INSERT INTO msgs VALUES(?,?,?,?)", (secrets.token_hex(16), address, hour(), blob))
            self.cond.notify_all()
            # the phone isn't connected: wake it with an empty ping (no content, nothing about the sender)
            if self.pusher and time.time() - self.checked.get(address, 0) > PUSH_QUIET:
                row = self.db.execute("SELECT token FROM push WHERE addr=?", (address,)).fetchone()
                if row:
                    self.pusher.wake(row[0])
            return None

    def set_push(self, addresses, token):
        """Which phone to wake for these chat mailboxes ("" = stop waking it)."""
        with self.lock:
            if token:
                self.db.executemany("INSERT OR REPLACE INTO push VALUES(?,?)", [(a, token) for a in addresses])
            else:
                self.db.executemany("DELETE FROM push WHERE addr=?", [(a,) for a in addresses])

    def forget_push_token(self, token):
        with self.lock:
            self.db.execute("DELETE FROM push WHERE token=?", (token,))

    def take(self, addresses, acks, wait, who, new_boxes):
        deadline = time.time() + wait
        with self.cond:
            err = self.touch(addresses, who, new_boxes)
            if err:
                return err
            marks = ",".join("?" * len(addresses))
            if acks:
                self.db.execute("DELETE FROM msgs WHERE id IN (%s) AND addr IN (%s)"
                                % (",".join("?" * len(acks)), marks), list(acks) + addresses)
            index = {a: i for i, a in enumerate(addresses)}
            while True:
                now = time.time()
                for a in addresses:
                    self.checked[a] = now   # this phone is connected right now
                rows = self.db.execute("SELECT id, addr, blob FROM msgs WHERE addr IN (%s) ORDER BY rowid LIMIT 200"
                                       % marks, addresses).fetchall()
                left = deadline - time.time()
                if rows or left <= 0:
                    return [{"box": index[a], "id": i, "blob": base64.b64encode(b).decode()} for i, a, b in rows]
                self.cond.wait(left)

    # ---- group mailboxes (fan-out; no per-reader delete, TTL cleans up) ----

    def gput(self, address, blob, who=None, new_boxes=None):
        """Adds to a group mailbox. A mailbox seen for the first time counts against the new-mailbox limit,
        holds at most MAX_GROUP_BYTES, and is dropped after GBOX_GRACE if nobody ever fetches it."""
        with self.cond:
            known = self.db.execute("SELECT 1 FROM gboxes WHERE addr=?", (address,)).fetchone()
            if not known:
                if new_boxes is not None and not new_boxes.add(who, 1, self.new_boxes_limit):
                    return "slow down"
                if not self.space_ok(len(blob)):
                    return "server full"
                self.db.execute("INSERT OR IGNORE INTO gboxes VALUES(?,?,NULL)", (address, time.time()))
            elif not self.space_ok(len(blob)):
                return "server full"
            n = self.db.execute("SELECT COUNT(*) FROM gmsgs WHERE addr=?", (address,)).fetchone()[0]
            if n >= MAX_PER_GROUP:
                self.db.execute("DELETE FROM gmsgs WHERE seq IN (SELECT seq FROM gmsgs WHERE addr=? ORDER BY seq LIMIT ?)",
                                (address, n - MAX_PER_GROUP + 1))
            # keep the newest MAX_GROUP_BYTES: drop oldest messages until the new one fits
            used = self.db.execute("SELECT COALESCE(SUM(LENGTH(blob)), 0) FROM gmsgs WHERE addr=?", (address,)).fetchone()[0]
            if used + len(blob) > MAX_GROUP_BYTES:
                rows = self.db.execute("SELECT seq, LENGTH(blob) FROM gmsgs WHERE addr=? ORDER BY seq", (address,)).fetchall()
                for seq, size in rows:
                    if used + len(blob) <= MAX_GROUP_BYTES:
                        break
                    self.db.execute("DELETE FROM gmsgs WHERE seq=?", (seq,))
                    used -= size
            self.db.execute("INSERT INTO gmsgs(addr, ts, blob) VALUES(?,?,?)", (address, hour(), blob))
            self.cond.notify_all()
            return None

    def gtake(self, address, since, wait):
        deadline = time.time() + wait
        with self.cond:
            self.db.execute("UPDATE gboxes SET fetched=? WHERE addr=?", (time.time(), address))   # someone really reads this one
            while True:
                rows = self.db.execute("SELECT seq, blob FROM gmsgs WHERE addr=? AND seq>? ORDER BY seq LIMIT 200",
                                       (address, since)).fetchall()
                left = deadline - time.time()
                if rows or left <= 0:
                    return [{"seq": r[0], "blob": base64.b64encode(r[1]).decode()} for r in rows]
                self.cond.wait(left)

    def sweep(self):
        now = time.time()
        with self.lock:
            self.db.execute("DELETE FROM msgs WHERE ts < ?", (now - MSG_TTL,))
            self.db.execute("DELETE FROM boxes WHERE seen < ? AND addr NOT IN (SELECT inbox FROM accounts)",
                            (now - BOX_TTL,))
            self.db.execute("DELETE FROM msgs WHERE addr NOT IN (SELECT addr FROM boxes)")
            self.db.execute("DELETE FROM push WHERE addr NOT IN (SELECT addr FROM boxes)")
            self.checked = {a: t for a, t in self.checked.items() if now - t < 3600}
            # each file lives for its own hint (group files ask for less), capped at MSG_TTL
            old = [r[0] for r in self.db.execute("SELECT id FROM files WHERE ? - ts > COALESCE(ttl, ?)", (now, MSG_TTL))]
        for fid in old:
            self.drop_file(fid)
        with self.lock:
            self.db.execute("DELETE FROM gmsgs WHERE ts < ?", (now - GROUP_MSG_TTL,))
            # group mailboxes nobody ever fetched (spam, or a typo) go quickly; unused real ones after BOX_TTL
            self.db.execute("DELETE FROM gmsgs WHERE addr IN (SELECT addr FROM gboxes WHERE fetched IS NULL AND created < ?)",
                            (now - GBOX_GRACE,))
            self.db.execute("DELETE FROM gboxes WHERE fetched IS NULL AND created < ?", (now - GBOX_GRACE,))
            self.db.execute("DELETE FROM gboxes WHERE COALESCE(fetched, created) < ?", (now - BOX_TTL,))
            self.db.execute("DELETE FROM gmsgs WHERE addr NOT IN (SELECT addr FROM gboxes)")
        # half-finished uploads (the phone dropped off) never become files
        try:
            for name in os.listdir(self.files_dir):
                p = os.path.join(self.files_dir, name)
                if name.endswith(".part") and now - os.path.getmtime(p) > 3600:
                    os.remove(p)
        except OSError:
            pass

    # ---- attachments: sealed blobs the phones encrypted; the key never comes here ----

    def file_path(self, fid):
        return os.path.join(self.files_dir, fid)

    def files_used(self):
        return self.q("SELECT COALESCE(SUM(size), 0) FROM files")[0][0]

    def reserve(self, n):
        """Room for an upload of n bytes (under the files cap and the disk floor), held until [release]."""
        with self.lock:
            if self.files_used() + self._pending + n > self.files_cap or not self.space_ok(n):
                return False
            self._pending += n
            return True

    def release(self, n):
        with self.lock:
            self._pending -= n

    _pending = 0

    def drop_file(self, fid):
        with self.lock:
            self.db.execute("DELETE FROM files WHERE id=?", (fid,))
        try:
            os.remove(self.file_path(fid))
        except OSError:
            pass

    # ---- accounts ----

    def check(self, user, auth):
        """Password check with lockout. Returns the row or an error string."""
        with self.lock:
            r = self.db.execute("SELECT salt, auth, locked, fails, kdf, backup, lastfail FROM accounts WHERE user=?",
                                (user,)).fetchone()
            if not r:
                return "wrong username or password"
            salt, good, locked, fails, kdf, backup, lastfail = r
            now = time.time()
            if locked > now:
                return "too many tries, wait %d minutes" % (int(locked - now) // 60 + 1)
            if not hmac.compare_digest(hashlib.sha256(salt + auth).digest(), good):
                # Anyone can type a public username, so a lock must not last long (it would be a way to shut a
                # person out): a few free tries, then short growing waits capped at LOCK_MAX. Old guesses are forgotten.
                if now - (lastfail or 0) > FAILS_FORGET:
                    fails = 0
                fails += 1
                lock = now + min(LOCK_MAX, 60 * 2 ** min(fails - FREE_TRIES, 5)) if fails > FREE_TRIES else 0
                self.db.execute("UPDATE accounts SET fails=?, locked=?, lastfail=? WHERE user=?", (fails, lock, now, user))
                return "wrong username or password"
            self.db.execute("UPDATE accounts SET fails=0, locked=0 WHERE user=?", (user,))
            return {"kdf": kdf or 1, "backup": backup}

    def device_ok(self, user, device):
        if not isinstance(user, str) or not isinstance(device, str) or len(device) > 128:
            return False
        r = self.q("SELECT dh FROM accounts WHERE user=?", user)
        return bool(r) and r[0][0] is not None and hmac.compare_digest(r[0][0], dev_hash(device))


class Handler(BaseHTTPRequestHandler):
    server_version = "relay"
    sys_version = ""
    protocol_version = "HTTP/1.1"
    timeout = MAX_WAIT + 30

    # Never log anything: no addresses, no times, no errors with details.
    def log_message(self, *args):
        pass

    def log_error(self, *args):
        pass

    def setup(self):
        super().setup()
        self._peer = self.client_address[0]
        if self.server.proxy_protocol:
            # Tor ("HiddenServiceExportCircuitID haproxy") starts each connection with a PROXY line whose source is an
            # ID of the Tor circuit, not an internet address. It lets the limits tell onion clients apart.
            # Only local programs can reach this port, so a header here is trusted; none = everyone shares 127.0.0.1.
            try:
                if self.rfile.peek(6)[:6] == b"PROXY ":
                    parts = self.rfile.readline(256).decode("ascii", "replace").split()
                    if len(parts) >= 6 and parts[1] in ("TCP4", "TCP6"):
                        self._peer = parts[2][:64]
            except (OSError, ValueError):
                pass

    def client_id(self):
        return blind(self._address())

    def _address(self):
        # Only the listener that sits behind Cloudflare may say who the client is. Anything else (the
        # .onion comes in from 127.0.0.1 with no proxy) could put any address in these headers.
        if self.server.proxy_protocol:
            return self._peer
        if self.server.behind_proxy:
            cf = self.headers.get("CF-Connecting-IP", "")
            if cf:
                return cf.strip()
            fwd = self.headers.get("X-Forwarded-For", "")
            if fwd:
                return fwd.split(",")[-1].strip()
        return self._peer

    def reply(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/":
            self.reply(200, {"telegramnt": 2})
        else:
            self.reply(404, {"error": "not found"})

    def do_POST(self):
        srv = self.server
        who = self.client_id()
        gif = self.path in ("/gifs", "/gif", "/pipe")   # GIFs and calls have their own budget, apart from messages
        if not (srv.shared.gif_rate if gif else srv.shared.rate).add(who, 1, GIF_RATE_PER_MIN if gif else srv.shared.rate_per_min):
            return self.reply(429, {"error": "slow down"})
        if self.headers.get("Transfer-Encoding"):   # we only speak Content-Length; never guess where a body ends
            self.close_connection = True
            return self.reply(400, {"error": "bad request"})
        if self.path == "/upload":
            return self.upload(who)
        if self.path == "/pipe":
            return self.pipe()
        try:
            n = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            n = -1
        if n < 0 or n > MAX_BODY:
            self.close_connection = True
            return self.reply(413, {"error": "too big"})
        try:
            req = json.loads(self.rfile.read(n) or b"{}")
            if not isinstance(req, dict):
                raise ValueError
        except ValueError:
            return self.reply(400, {"error": "bad request"})
        route = getattr(self, "api_" + self.path.strip("/"), None) if re.match(r"^/[a-z]{2,12}$", self.path) else None
        if route is None:
            return self.reply(404, {"error": "not found"})
        try:
            route(req, who)
        except (KeyError, TypeError, ValueError, AttributeError):
            self.reply(400, {"error": "bad request"})
        except Exception:   # e.g. a database error: answer cleanly, tell nothing, keep the connection tidy
            self.close_connection = True
            self.reply(500, {"error": "server error"})

    # ---- mailboxes ----

    def api_send(self, req, who):
        to = req["to"]
        blob = b64(req["blob"], MAX_BLOB)
        if not is_hex32(to) or blob is None:
            return self.reply(400, {"error": "bad request"})
        err = self.server.shared.store.put(to, blob)
        if err:
            return self.reply(404 if err == "no such mailbox" else 507, {"error": err})
        self.reply(200, {"ok": True})

    def api_fetch(self, req, who):
        tokens = req["tokens"]
        acks = req.get("ack") or []
        wait = max(0, min(MAX_WAIT, int(req.get("wait", 0))))
        if (not isinstance(tokens, list) or not tokens or len(tokens) > MAX_TOKENS_PER_FETCH
                or not all(is_hex32(t) for t in tokens)
                or not isinstance(acks, list) or len(acks) > 500
                or not all(isinstance(a, str) and len(a) == 32 for a in acks)):
            return self.reply(400, {"error": "bad request"})
        addresses = [hashlib.sha256(bytes.fromhex(t)).hexdigest() for t in tokens]
        push = req.get("push")
        if isinstance(push, str) and len(push) <= 400 and re.match(r"^[A-Za-z0-9_:\-]*$", push):
            self.server.shared.store.set_push(addresses, push)
        msgs = self.server.shared.store.take(addresses, acks, wait, who, self.server.shared.new_boxes)
        if isinstance(msgs, str):
            return self.reply(429 if msgs == "slow down" else 507, {"error": msgs})
        self.reply(200, {"msgs": msgs})

    # ---- attachments ----

    def upload(self, who):
        """Raw body = one encrypted attachment. Returns its id."""
        srv, store = self.server, self.server.shared.store
        try:
            n = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            n = -1
        if n <= 0 or n > MAX_FILE:
            self.close_connection = True
            return self.reply(413, {"error": "file too big"})
        if not srv.shared.uploads.add(who, n, UPLOAD_PER_HOUR):
            self.close_connection = True
            return self.reply(429, {"error": "too many files, try later"})
        if not store.reserve(n):   # counts uploads still in flight, so parallel uploads can't slip past the cap
            self.close_connection = True
            return self.reply(507, {"error": "server full"})
        try:
            fid = secrets.token_hex(16)
            tmp = store.file_path(fid + ".part")
            try:
                with open(tmp, "wb") as f:
                    left = n
                    while left:
                        chunk = self.rfile.read(min(left, 256 * 1024))
                        if not chunk:
                            raise OSError("short body")
                        f.write(chunk)
                        left -= len(chunk)
                os.replace(tmp, store.file_path(fid))
            except OSError:
                try:
                    os.remove(tmp)
                except OSError:
                    pass
                self.close_connection = True
                return self.reply(400, {"error": "upload failed"})
            ttl = None  # default: MSG_TTL (handled in sweep). Clients may ask for a shorter life via X-TTL.
            try:
                hint = int(self.headers.get("X-TTL", ""))
                ttl = max(3600, min(hint, MSG_TTL))  # clamp: never longer than MSG_TTL, never under an hour
            except ValueError:
                pass
            store.q("INSERT INTO files(id, size, ts, ttl) VALUES(?,?,?,?)", fid, n, hour(), ttl)
        finally:
            store.release(n)
        self.reply(200, {"id": fid})

    def api_download(self, req, who):
        fid = req["id"]
        if not isinstance(fid, str) or len(fid) != 32 or not set(fid) <= HEX or not self.server.shared.store.q("SELECT 1 FROM files WHERE id=?", fid):
            return self.reply(404, {"error": "no such file"})
        try:   # it can be deleted (picked up / expired) between the check above and here
            f = open(self.server.shared.store.file_path(fid), "rb")
        except OSError:
            return self.reply(404, {"error": "no such file"})
        with f:
            self._send_file(f)

    def _send_file(self, f):
        size = os.fstat(f.fileno()).st_size
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(size))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        while chunk := f.read(256 * 1024):
            self.wfile.write(chunk)

    # ---- voice calls: pair the two phones by a random room id and pass their (already encrypted) audio ----

    def pipe(self):
        """One client (Tor circuit) may hold only a few call rooms, so nobody can park all of them. Then [_pipe]."""
        srv = self.server
        who = self._address()
        capped = who not in LOOPBACK   # no circuit ID (everyone looks like 127.0.0.1): a per-client cap would break calls
        if capped:
            with srv.shared.pipe_lock:
                if srv.shared.pipe_by.get(who, 0) >= PIPE_PER_SOURCE:
                    return self.reply(503, {"error": "busy"})
                srv.shared.pipe_by[who] = srv.shared.pipe_by.get(who, 0) + 1
        try:
            return self._pipe()
        finally:
            if capped:
                with srv.shared.pipe_lock:
                    left = srv.shared.pipe_by.get(who, 1) - 1
                    if left > 0:
                        srv.shared.pipe_by[who] = left
                    else:
                        srv.shared.pipe_by.pop(who, None)

    def _pipe(self):
        """The two phones in a call each POST /pipe with the same X-Room (random, from the call's encrypted
        setup). Whoever arrives first waits; once both are here each gets one 0x01 byte, then bytes flow
        straight through both ways until either hangs up. The audio is encrypted end to end by the phones,
        so this only ever sees noise, and over Tor it never learns who is calling whom."""
        srv = self.server
        room = self.headers.get("X-Room", "")
        if not ROOM.match(room):
            return self.reply(400, {"error": "bad request"})
        side = self.headers.get("X-Side", "")[:8]   # "caller" / "callee"; older apps send nothing
        me = {"sock": self.connection, "paired": threading.Event(), "done": threading.Event(), "side": side}
        with srv.shared.pipe_lock:
            partner = srv.shared.pipe_wait.pop(room, None)
            if partner is not None and side and partner.get("side") == side:
                # the same phone trying again (its earlier attempt dropped): retire that one, wait afresh
                try:
                    partner["sock"].shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                partner["paired"].set()
                partner["done"].set()
                partner = None
            if partner is None:
                if len(srv.shared.pipe_wait) + srv.shared.pipe_active >= PIPE_MAX_ACTIVE:
                    return self.reply(503, {"error": "busy"})
                srv.shared.pipe_wait[room] = me
            else:
                srv.shared.pipe_active += 1
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.flush()
        self.close_connection = True
        if partner is None:   # first one here: wait for the other phone, then let its thread do the relaying
            if not me["paired"].wait(PIPE_WAIT):
                with srv.shared.pipe_lock:
                    if srv.shared.pipe_wait.get(room) is me:
                        del srv.shared.pipe_wait[room]
                if not me["paired"].is_set():
                    return
            me["done"].wait(PIPE_MAX_SECONDS + 60)
            return
        try:
            a, b = partner["sock"], self.connection
            for sk in (a, b):
                sk.settimeout(PIPE_IDLE)
            partner["paired"].set()
            for sk in (a, b):
                sk.sendall(b"\x01")
            stop = threading.Event()
            deadline = time.time() + PIPE_MAX_SECONDS

            def copy(src, dst):
                try:
                    while not stop.is_set() and time.time() < deadline:
                        chunk = src.recv(16384)
                        if not chunk:
                            break
                        dst.sendall(chunk)
                except OSError:
                    pass
                stop.set()
                for sk in (a, b):   # one side gone: end the call for both
                    try:
                        sk.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass

            t = threading.Thread(target=copy, args=(a, b), daemon=True)
            t.start()
            copy(b, a)
            t.join(5)
        except OSError:
            pass
        finally:
            with srv.shared.pipe_lock:
                srv.shared.pipe_active -= 1
            partner["done"].set()

    # ---- GIF search (GIPHY), proxied so GIPHY never sees users' addresses and the key stays here ----

    def api_gifs(self, req, who):
        """Trending (empty q) or search results, each with a small animated preview inlined."""
        key = self.server.shared.giphy_key
        if not key:
            return self.reply(503, {"error": "GIFs aren't set up on this server"})
        q = str(req.get("q", ""))[:60].strip()
        offset = max(0, min(int(req.get("offset", 0)), 500))
        params = {"api_key": key, "limit": GIF_PAGE, "offset": offset, "rating": "pg-13"}
        if q:
            params["q"] = q
        url = "https://api.giphy.com/v1/gifs/%s?%s" % ("search" if q else "trending", urllib.parse.urlencode(params))
        try:
            data = json.loads(giphy_get(url, 1024 * 1024)).get("data", [])
        except Exception:
            return self.reply(502, {"error": "GIF search is unavailable right now"})
        items = []
        for g in data:
            im = g.get("images", {})
            # small animated WebP loop for the picker grid (~25 KB each); a still frame if there isn't one
            prev = im.get("preview_webp") or im.get("fixed_width_small_still") or {}
            full = im.get("fixed_width") or im.get("downsized") or {}
            gid = g.get("id", "")
            if not (isinstance(gid, str) and GIF_ID.match(gid) and prev.get("url") and full.get("url")):
                continue
            self.server.shared.gif_urls[gid] = full["url"]
            items.append((gid, prev["url"], int(full.get("width") or 200), int(full.get("height") or 200)))
        def fetch(it):
            try:
                return it, base64.b64encode(giphy_get(it[1], 600 * 1024)).decode()
            except Exception:
                return it, None
        out = []
        for (gid, _, w, h), b in self.server.shared.gif_pool.map(fetch, items):
            if b:
                out.append({"id": gid, "w": w, "h": h, "p": b})
        self.reply(200, {"gifs": out})

    def api_gif(self, req, who):
        """The full GIF to send (only ids we handed out in a recent search)."""
        gid = req["id"]
        url = self.server.shared.gif_urls.get(gid) if isinstance(gid, str) else None
        if not url:
            return self.reply(404, {"error": "no such GIF"})
        try:
            body = giphy_get(url, GIF_MAX)
        except Exception:
            return self.reply(502, {"error": "couldn't get that GIF"})
        self.send_response(200)
        self.send_header("Content-Type", "image/gif")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def api_done(self, req, who):
        """The receiver has the file: forget it."""
        fid = req["id"]
        if isinstance(fid, str) and len(fid) == 32 and set(fid) <= HEX:
            self.server.shared.store.drop_file(fid)
        self.reply(200, {"ok": True})

    # ---- group mailboxes ----

    def api_gsend(self, req, who):
        to = req["to"]
        blob = b64(req["blob"], MAX_BLOB)
        if not is_hex32(to) or blob is None:
            return self.reply(400, {"error": "bad request"})
        err = self.server.shared.store.gput(to, blob, who, self.server.shared.new_boxes)
        if err:
            return self.reply(429 if err == "slow down" else 507, {"error": err})
        self.reply(200, {"ok": True})

    def api_gfetch(self, req, who):
        addr = req["addr"]
        since = req.get("since", 0)
        wait = max(0, min(MAX_WAIT, int(req.get("wait", 0))))
        if not is_hex32(addr) or not isinstance(since, int) or since < 0:
            return self.reply(400, {"error": "bad request"})
        self.reply(200, {"msgs": self.server.shared.store.gtake(addr, since, wait)})

    # ---- accounts ----

    def api_signup(self, req, who):
        user = req["user"]
        auth = bytes.fromhex(req["auth"])
        enc, sig = b64(req["enc"], MAX_KEY), b64(req["sig"], MAX_KEY)
        raw_backup = req["backup"]
        backup = b"" if raw_backup == "" else b64(raw_backup, MAX_BACKUP)  # "" = user keeps no cloud backup
        inbox_token = req["inboxToken"]
        kdf = req.get("kdf", 1)   # which password hashing the phone used: 1 = PBKDF2 (old apps), 2 = Argon2id
        if (not isinstance(user, str) or not USERNAME.match(user) or len(auth) != 32
                or None in (enc, sig, backup) or not is_hex32(inbox_token) or kdf not in (1, 2)):
            return self.reply(400, {"error": "bad request"})
        if not self.server.shared.signups.add(who, 1, self.server.shared.signups_per_hour):
            return self.reply(429, {"error": "too many new accounts, try later"})
        store = self.server.shared.store
        if not store.space_ok():
            return self.reply(507, {"error": "server full"})
        salt = secrets.token_bytes(16)
        device = secrets.token_hex(32)
        inbox = hashlib.sha256(bytes.fromhex(inbox_token)).hexdigest()
        with store.lock:
            if store.db.execute("SELECT 1 FROM accounts WHERE user=?", (user,)).fetchone():
                return self.reply(409, {"error": "username taken"})
            store.db.execute("INSERT INTO accounts(user, salt, auth, enc, sig, inbox, backup, device, dh, kdf) "
                             "VALUES(?,?,?,?,?,?,?,'',?,?)",
                             (user, salt, hashlib.sha256(salt + auth).digest(), enc, sig, inbox, backup, dev_hash(device), kdf))
            store.db.execute("INSERT OR REPLACE INTO boxes VALUES(?,?)", (inbox, day()))
        self.reply(200, {"device": device})

    def api_kdf(self, req, who):
        """Which password hashing an account uses, so the phone derives the right keys before logging in.
        (Usernames are already public through /lookup, so this reveals nothing new.) Unknown names get the newest."""
        user = req["user"]
        if not isinstance(user, str) or not USERNAME.match(user):
            return self.reply(400, {"error": "bad request"})
        r = self.server.shared.store.q("SELECT kdf FROM accounts WHERE user=?", user)
        self.reply(200, {"kdf": (r[0][0] or 1) if r else 2})

    def api_upgrade(self, req, who):
        """A phone that just logged in to an old (PBKDF2) account moves it to Argon2id: new login hash and a backup
        locked with the new key. Needs the device token the login just returned."""
        user, auth = req["user"], bytes.fromhex(req["auth"])
        backup = b64(req["backup"], MAX_BACKUP)
        store = self.server.shared.store
        if len(auth) != 32 or backup is None or not store.device_ok(user, req["device"]):
            return self.reply(401, {"error": "signed in on another phone"})
        salt = secrets.token_bytes(16)
        store.q("UPDATE accounts SET salt=?, auth=?, backup=?, kdf=2, fails=0, locked=0 WHERE user=? AND kdf=1",
                salt, hashlib.sha256(salt + auth).digest(), backup, user)
        self.reply(200, {"ok": True})

    def api_id(self, req, who):
        """Proves this really is the Tellem server: signs the caller's random challenge with the server's own key.
        Direct mode needs it (the address changes and isn't authenticated by anything else); the .onion proves itself."""
        n = req["n"]
        if not is_hex32(n):
            return self.reply(400, {"error": "bad request"})
        ident = self.server.shared.identity
        if ident is None:
            return self.reply(501, {"error": "no server identity"})
        self.reply(200, {"pub": base64.b64encode(ident.public).decode(),
                         "sig": base64.b64encode(ident.sign(b"tellem-server-v1|" + bytes.fromhex(n))).decode()})

    def api_login(self, req, who):
        user, auth = req["user"], bytes.fromhex(req["auth"])
        if not isinstance(user, str) or not USERNAME.match(user) or len(auth) != 32:
            return self.reply(400, {"error": "bad request"})
        r = self.server.shared.store.check(user, auth)
        if isinstance(r, str):
            return self.reply(403, {"error": r})
        if not r["backup"]:
            # account kept no cloud backup: logging in elsewhere is impossible, and we must not
            # rotate the device token or we'd sign out the phone that still holds the only keys.
            return self.reply(403, {"error": "no cloud backup for this account"})
        # Signing in signs out the previous phone (both can't pick up the same mail).
        device = secrets.token_hex(32)
        self.server.shared.store.q("UPDATE accounts SET dh=?, device='' WHERE user=?", dev_hash(device), user)
        self.reply(200, {"device": device, "backup": base64.b64encode(r["backup"]).decode(), "kdf": r["kdf"]})

    def api_session(self, req, who):
        if self.server.shared.store.device_ok(req["user"], req["device"]):
            return self.reply(200, {"ok": True})
        self.reply(401, {"error": "signed in on another phone"})

    def api_backup(self, req, who):
        raw_backup = req["backup"]
        backup = b"" if raw_backup == "" else b64(raw_backup, MAX_BACKUP)  # "" = wipe the cloud backup
        if backup is None:
            return self.reply(400, {"error": "bad request"})
        if not self.server.shared.store.device_ok(req["user"], req["device"]):
            return self.reply(401, {"error": "signed in on another phone"})
        self.server.shared.store.q("UPDATE accounts SET backup=? WHERE user=?", backup, req["user"])
        self.reply(200, {"ok": True})

    def api_lookup(self, req, who):
        user = req["user"]
        if not isinstance(user, str) or not USERNAME.match(user):
            return self.reply(404, {"error": "no such user"})
        r = self.server.shared.store.q("SELECT enc, sig, inbox, ident FROM accounts WHERE user=?", user)
        if not r:
            return self.reply(404, {"error": "no such user"})
        enc, sig, inbox, ident = r[0]
        self.reply(200, {"user": user, "enc": base64.b64encode(enc).decode(),
                         "sig": base64.b64encode(sig).decode(), "inbox": inbox,
                         "ident": base64.b64encode(ident).decode() if ident else ""})

    # ---- Signal prekeys ----

    def api_keys(self, req, who):
        """Publish my Signal keys (needs this phone's device token)."""
        user = req["user"]
        store = self.server.shared.store
        if not store.device_ok(user, req["device"]):
            return self.reply(401, {"error": "signed in on another phone"})
        ident = b64(req["ident"], 64)
        reg = int(req["reg"])
        spk, kpk = req["spk"], req["kpk"]
        if ident is None or not 0 < reg < 16384 or not isinstance(spk, dict) or not isinstance(kpk, dict):
            return self.reply(400, {"error": "bad request"})
        for k, size in ((spk, 64), (kpk, 2048)):
            if not isinstance(k.get("id"), int) or b64(k.get("pub"), size) is None or b64(k.get("sig"), 128) is None:
                return self.reply(400, {"error": "bad request"})
        onetime = req.get("otpk") or []
        if not isinstance(onetime, list) or len(onetime) > 200:
            return self.reply(400, {"error": "bad request"})
        rows = []
        for k in onetime:
            pub = b64(k.get("pub") if isinstance(k, dict) else None, 64)
            if pub is None or not isinstance(k.get("id"), int):
                return self.reply(400, {"error": "bad request"})
            rows.append((user, k["id"], pub))
        with store.lock:
            old = store.db.execute("SELECT ident FROM accounts WHERE user=?", (user,)).fetchone()[0]
            if old and old != ident:
                return self.reply(409, {"error": "identity can't change"})
            store.db.execute("UPDATE accounts SET ident=?, reg=?, spk=?, kpk=? WHERE user=?",
                             (ident, reg, json.dumps(spk), json.dumps(kpk), user))
            if req.get("replace"):
                store.db.execute("DELETE FROM otpk WHERE user=?", (user,))
            store.db.executemany("INSERT OR REPLACE INTO otpk VALUES(?,?,?)", rows)
            store.db.execute("DELETE FROM otpk WHERE user=? AND id NOT IN "
                             "(SELECT id FROM otpk WHERE user=? ORDER BY id DESC LIMIT 200)", (user, user))
            count = store.db.execute("SELECT COUNT(*) FROM otpk WHERE user=?", (user,)).fetchone()[0]
        self.reply(200, {"count": count})

    def api_keycount(self, req, who):
        store = self.server.shared.store
        if not store.device_ok(req["user"], req["device"]):
            return self.reply(401, {"error": "signed in on another phone"})
        self.reply(200, {"count": store.q("SELECT COUNT(*) FROM otpk WHERE user=?", req["user"])[0][0]})

    def api_bundle(self, req, who):
        """What someone needs to start a Signal session with @user. Hands out one one-time key."""
        user = req["user"]
        if not isinstance(user, str) or not USERNAME.match(user):
            return self.reply(404, {"error": "no such user"})
        store = self.server.shared.store
        with store.lock:
            r = store.db.execute("SELECT ident, reg, spk, kpk FROM accounts WHERE user=?", (user,)).fetchone()
            if not r or not r[0]:
                return self.reply(404, {"error": "no keys"})
            ident, reg, spk, kpk = r
            one = None
            # stop anyone draining someone's one-time keys: a few per hour per target
            if self.server.shared.bundles.add(blind("bundle:" + user), 1, self.server.shared.store.bundle_limit):
                row = store.db.execute("SELECT id, pub FROM otpk WHERE user=? ORDER BY id LIMIT 1", (user,)).fetchone()
                if row:
                    store.db.execute("DELETE FROM otpk WHERE user=? AND id=?", (user, row[0]))
                    one = {"id": row[0], "pub": base64.b64encode(row[1]).decode()}
        self.reply(200, {"user": user, "ident": base64.b64encode(ident).decode(), "reg": reg,
                         "spk": json.loads(spk), "kpk": json.loads(kpk), "otpk": one})

    def api_delete(self, req, who):
        user, auth = req["user"], bytes.fromhex(req["auth"])
        r = self.server.shared.store.check(user, auth) if isinstance(user, str) and len(auth) == 32 else "bad"
        if isinstance(r, str):
            return self.reply(403, {"error": "wrong username or password"})
        store = self.server.shared.store
        with store.lock:
            inbox = store.db.execute("SELECT inbox FROM accounts WHERE user=?", (user,)).fetchone()[0]
            store.db.execute("DELETE FROM accounts WHERE user=?", (user,))
            store.db.execute("DELETE FROM otpk WHERE user=?", (user,))
            store.db.execute("DELETE FROM msgs WHERE addr=?", (inbox,))
            store.db.execute("DELETE FROM boxes WHERE addr=?", (inbox,))
        self.reply(200, {"ok": True})


GIF_ID = re.compile(r"^[A-Za-z0-9]{1,40}$")


def giphy_get(url, cap):
    """GET from GIPHY (only its own hosts), refusing anything larger than cap bytes."""
    p = urllib.parse.urlparse(url)
    host = p.hostname or ""
    if p.scheme != "https" or not (host == "giphy.com" or host.endswith(".giphy.com")):
        raise ValueError("not giphy")
    # never follow a redirect: it could lead anywhere, and the host check above only covers the first address
    r = _NO_REDIRECT.open(urllib.request.Request(url, headers={"User-Agent": "tellem-relay"}), timeout=15)
    if r.status != 200:
        raise ValueError("giphy said %s" % r.status)
    body = r.read(cap + 1)
    if len(body) > cap:
        raise ValueError("too big")
    return body


class _Refuse(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


_NO_REDIRECT = urllib.request.build_opener(_Refuse)


class Identity:
    """The server's own Ed25519 key (server-id.key, made on first start). Phones that know its public half can tell the
    real server from an impostor on the Direct route. Needs the `cryptography` package; without it /id answers 501."""

    def __init__(self, path):
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
        if os.path.exists(path):
            with open(path, "rb") as f:
                self.key = serialization.load_pem_private_key(f.read(), password=None)
        else:
            self.key = Ed25519PrivateKey.generate()
            pem = self.key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                         serialization.NoEncryption())
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as f:
                f.write(pem)
        self.public = self.key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)

    def sign(self, data):
        return self.key.sign(data)


def load_identity(path):
    try:
        return Identity(path)
    except Exception as e:   # no cryptography package, or an unreadable key file
        print("Server identity off: %s" % e, flush=True)
        return None


class GifUrls:
    """id -> media URL from recent searches, bounded so it can't grow forever."""
    def __init__(self, cap=5000):
        self.cap, self.d, self.lock = cap, {}, threading.Lock()

    def __setitem__(self, k, v):
        with self.lock:
            self.d.pop(k, None)
            self.d[k] = v
            while len(self.d) > self.cap:
                self.d.pop(next(iter(self.d)))

    def get(self, k):
        with self.lock:
            return self.d.get(k)


class Shared:
    """Everything the listeners have in common: data, rate limits, call rooms, GIF lookups. Each listener
    differs only in whether it may trust a proxy's client-address header."""

    def __init__(self, store):
        self.store = store
        self.rate = Counter(60)
        self.gif_rate = Counter(60)
        self.pipe_lock = threading.Lock()
        self.pipe_wait = {}
        self.pipe_active = 0
        self.pipe_by = {}          # client -> call rooms it holds right now
        self.identity = None       # set in main()
        self.gif_urls = GifUrls()
        self.gif_pool = concurrent.futures.ThreadPoolExecutor(8)
        key = os.environ.get("GIPHY_KEY", "")
        if not key and os.path.exists(GIPHY_KEY_FILE):
            with open(GIPHY_KEY_FILE) as f:
                key = f.read().strip()
        self.giphy_key = key
        self.new_boxes = Counter(60)
        self.signups = Counter(3600)
        self.uploads = Counter(3600)
        self.bundles = Counter(3600)
        self.signups_per_hour = SIGNUPS_PER_HOUR
        self.rate_per_min = RATE_PER_MIN
        self.slots = threading.BoundedSemaphore(MAX_CONNECTIONS)


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, addr, shared, behind_proxy, proxy_protocol=False):
        self.shared = shared
        self.behind_proxy = behind_proxy
        self.proxy_protocol = proxy_protocol   # connections may begin with a PROXY line from Tor (circuit ID)
        super().__init__(addr, Handler)

    def process_request(self, request, client_address):
        if not self.shared.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        super().process_request(request, client_address)

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.shared.slots.release()

    def handle_error(self, request, client_address):
        pass  # no tracebacks with client addresses in them


class Pusher:
    """Sends empty wake-up pings through Google's push service (Firebase Cloud Messaging), so a phone
    whose app is closed wakes up, connects (through Tor as usual) and picks up its mail. The ping
    carries nothing: no sender, no chat, no text. Needs fcm.json (a Firebase service account key)."""

    def __init__(self, key_file):
        with open(key_file) as f:
            k = json.load(f)
        self.project = k["project_id"]
        self.email = k["client_email"]
        self.key_pem = k["private_key"].encode()
        self.token = None
        self.token_until = 0
        self.last = {}
        self.queue = []
        self.cv = threading.Condition()
        threading.Thread(target=self.run, daemon=True).start()

    def wake(self, device):
        with self.cv:
            if time.time() - self.last.get(device, 0) < PUSH_GAP or device in self.queue:
                return
            self.last[device] = time.time()
            self.queue.append(device)
            self.cv.notify()

    def access_token(self):
        if self.token and time.time() < self.token_until:
            return self.token
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import padding
        def b64u(b):
            return base64.urlsafe_b64encode(b).rstrip(b"=").decode()
        now = int(time.time())
        head = b64u(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
        claims = b64u(json.dumps({"iss": self.email, "scope": "https://www.googleapis.com/auth/firebase.messaging",
                                  "aud": "https://oauth2.googleapis.com/token", "iat": now, "exp": now + 3600}).encode())
        key = serialization.load_pem_private_key(self.key_pem, password=None)
        sig = key.sign((head + "." + claims).encode(), padding.PKCS1v15(), hashes.SHA256())
        body = urllib.parse.urlencode({"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                                       "assertion": head + "." + claims + "." + b64u(sig)}).encode()
        with urllib.request.urlopen(urllib.request.Request("https://oauth2.googleapis.com/token", body), timeout=20) as r:
            got = json.loads(r.read())
        self.token = got["access_token"]
        self.token_until = time.time() + int(got.get("expires_in", 3600)) - 300
        return self.token

    def send(self, device):
        msg = {"message": {"token": device, "data": {"w": "1"},
                           "android": {"priority": "HIGH", "ttl": "60s", "collapse_key": "wake"}}}
        req = urllib.request.Request("https://fcm.googleapis.com/v1/projects/%s/messages:send" % self.project,
                                     json.dumps(msg).encode(), {"Content-Type": "application/json",
                                                                "Authorization": "Bearer " + self.access_token()})
        try:
            urllib.request.urlopen(req, timeout=20).read()
            return True
        except urllib.error.HTTPError as e:
            return e.code not in (400, 404)   # the phone uninstalled / token gone: stop pinging it

    def run(self):
        while True:
            with self.cv:
                while not self.queue:
                    self.cv.wait()
                device = self.queue.pop(0)
            try:
                if not self.send(device) and self.on_gone:
                    self.on_gone(device)
            except Exception:
                pass   # Google unreachable: the phone still gets its mail next time it connects

    on_gone = None


def main():
    p = argparse.ArgumentParser(description=f"{APP_NAME} server (keeps no logs)")
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--db", default="telegramnt.db", help="SQLite file for accounts and waiting messages")
    p.add_argument("--behind-proxy", action="store_true",
                   help="trust CF-Connecting-IP / X-Forwarded-For on --port (only when a proxy is the sole way in)")
    p.add_argument("--proxy-port", type=int, default=0,
                   help="a second port, for the Cloudflare tunnel only: client-address headers are trusted there and nowhere else")
    p.add_argument("--onion-proxy-protocol", action="store_true",
                   help="--port is the Tor onion listener with HiddenServiceExportCircuitID haproxy: read Tor's PROXY line "
                        "so rate limits apply per Tor circuit instead of to everyone at once")
    p.add_argument("--db-max-mb", type=int, default=DB_MAX_MB, help="refuse new mail when the database file reaches this size")
    p.add_argument("--min-free-mb", type=int, default=MIN_FREE_MB, help="refuse new mail/files when the disk has less free")
    p.add_argument("--files-gb", type=float, default=2, help="space for waiting attachments")
    p.add_argument("--signups-per-hour", type=int, default=SIGNUPS_PER_HOUR, help="new accounts per address (tests raise it)")
    p.add_argument("--test-limits", action="store_true", help="lift per-address rate limits (local tests only)")
    a = p.parse_args()

    files_dir = os.path.join(os.path.dirname(os.path.abspath(a.db)), "files")
    store = Store(a.db, files_dir, int(a.files_gb * 1024 ** 3))
    store.db_max = a.db_max_mb * 1024 * 1024
    store.min_free = a.min_free_mb * 1024 * 1024

    def sweeper():
        while True:
            time.sleep(600)
            store.sweep()

    threading.Thread(target=sweeper, daemon=True).start()
    if os.path.exists(PUSH_KEY_FILE):
        try:
            store.pusher = Pusher(PUSH_KEY_FILE)
            store.pusher.on_gone = store.forget_push_token
            print("Wake-up pings on (Firebase project %s)" % store.pusher.project, flush=True)
        except Exception as e:
            print("Wake-up pings off: %s" % e, flush=True)
    shared = Shared(store)
    shared.identity = load_identity(os.path.join(os.path.dirname(os.path.abspath(__file__)), "server-id.key"))
    shared.signups_per_hour = a.signups_per_hour
    if a.test_limits:
        shared.rate_per_min = 10 ** 9
        shared.signups_per_hour = 10 ** 9
    servers = [Server((a.host, a.port), shared, a.behind_proxy, a.onion_proxy_protocol)]
    if a.proxy_port:
        servers.append(Server((a.host, a.proxy_port), shared, True))
    if a.test_limits:
        store.new_boxes_limit = 10 ** 9
        store.bundle_limit = 10 ** 9
    for srv in servers[1:]:
        threading.Thread(target=srv.serve_forever, daemon=True).start()
    ports = ", ".join(str(srv.server_address[1]) for srv in servers)
    print(f"{APP_NAME} server on {a.host} port {ports}, data in {a.db} (no logs kept)", flush=True)
    with contextlib.suppress(KeyboardInterrupt):
        servers[0].serve_forever()


if __name__ == "__main__":
    main()
