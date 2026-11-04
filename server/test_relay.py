#!/usr/bin/env python3
"""Server tests for the security fixes (run: python3 -m unittest server/test_relay.py -v). Standard library only;
the /id test also needs the `cryptography` package and is skipped without it."""
import base64
import hashlib
import http.server
import json
import os
import secrets
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import relay  # noqa: E402


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class Server:
    """A real relay.py on a spare port with its own database."""

    def __init__(self, *flags):
        self.dir = tempfile.mkdtemp()
        self.port = free_port()
        self.db = os.path.join(self.dir, "t.db")
        # the identity key lives next to relay.py, so run a private copy of the script
        self.script = os.path.join(self.dir, "relay.py")
        with open(os.path.join(HERE, "relay.py"), "rb") as f, open(self.script, "wb") as g:
            g.write(f.read())
        self.proc = subprocess.Popen([sys.executable, "-I", self.script, "--host", "127.0.0.1", "--port", str(self.port),
                                      "--db", self.db, *flags], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(60):
            try:
                urllib.request.urlopen("http://127.0.0.1:%d/" % self.port, timeout=1).read()
                return
            except Exception:
                time.sleep(0.2)
        raise RuntimeError("server didn't start")

    def post(self, path, obj, source=None):
        """JSON POST; [source] makes it arrive as a given Tor circuit (needs --onion-proxy-protocol)."""
        body = json.dumps(obj).encode()
        req = (b"POST " + path.encode() + b" HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nConnection: close\r\n"
               + b"Content-Length: " + str(len(body)).encode() + b"\r\n\r\n" + body)
        return self.raw(req, source)

    def raw(self, data, source=None):
        s = socket.create_connection(("127.0.0.1", self.port), timeout=10)
        if source:
            s.sendall(("PROXY TCP6 %s fc00:dead:beef:4dad::1 1234 80\r\n" % source).encode())
        s.sendall(data)
        buf = b""
        while True:
            try:
                c = s.recv(65536)
            except OSError:
                break
            if not c:
                break
            buf += c
        s.close()
        if not buf:
            return None, {}
        head, _, payload = buf.partition(b"\r\n\r\n")
        code = int(head.split(b" ")[1])
        try:
            return code, json.loads(payload or b"{}")
        except ValueError:
            return code, {}

    def stop(self):
        self.proc.terminate()
        self.proc.wait(10)


def h64():
    return secrets.token_hex(32)


def b64(n):
    return base64.b64encode(os.urandom(n)).decode()


def signup(srv, user, auth=None, kdf=None, source=None):
    auth = auth or h64()
    req = {"user": user, "auth": auth, "enc": b64(40), "sig": b64(40), "backup": b64(100), "inboxToken": h64()}
    if kdf:
        req["kdf"] = kdf
    code, r = srv.post("/signup", req, source)
    assert code == 200, (code, r)
    return auth, r["device"]


class OnionLimits(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = Server("--onion-proxy-protocol", "--signups-per-hour", "1000")

    @classmethod
    def tearDownClass(cls):
        cls.srv.stop()

    def test_limits_are_per_circuit(self):
        a, b = "fc00:dead:beef:4dad::aaaa:0001", "fc00:dead:beef:4dad::bbbb:0002"
        seen = [self.srv.post("/lookup", {"user": "nobody_here"}, a)[0] for _ in range(130)]
        self.assertIn(429, seen, "one circuit must hit its own limit")
        self.assertEqual(self.srv.post("/lookup", {"user": "nobody_here"}, b)[0], 404, "another circuit is unaffected")

    def test_new_group_mailboxes_are_rate_limited_per_circuit(self):
        a = "fc00:dead:beef:4dad::cccc:0003"
        codes = [self.srv.post("/gsend", {"to": h64(), "blob": b64(100)}, a)[0] for _ in range(25)]
        self.assertEqual(codes.count(200), 20)
        self.assertEqual(codes[-1], 429)

    def test_call_rooms_capped_per_circuit(self):
        a = "fc00:dead:beef:4dad::dddd:0004"
        socks, codes = [], []
        for _ in range(relay.PIPE_PER_SOURCE + 1):
            s = socket.create_connection(("127.0.0.1", self.srv.port), timeout=5)
            s.sendall(("PROXY TCP6 %s fc00::1 1 80\r\nPOST /pipe HTTP/1.1\r\nHost: x\r\nX-Room: %s\r\nContent-Length: 0\r\n\r\n"
                       % (a, secrets.token_hex(32))).encode())
            codes.append(s.recv(64).split(b"\r\n")[0])
            socks.append(s)
        for s in socks:
            s.close()
        self.assertEqual(codes.count(b"HTTP/1.1 200 OK"), relay.PIPE_PER_SOURCE)
        self.assertIn(b"503", codes[-1])

    def test_without_a_proxy_line_everyone_shares_loopback(self):
        s = socket.create_connection(("127.0.0.1", self.srv.port), timeout=5)
        s.sendall(b"GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        buf = b""
        while True:
            c = s.recv(500)
            if not c:
                break
            buf += c
        s.close()
        self.assertIn(b"telegramnt", buf)


class Plain(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = Server("--test-limits")

    @classmethod
    def tearDownClass(cls):
        cls.srv.stop()

    def test_type_confusion_gets_a_clean_answer(self):
        for path, obj in (("/session", {"user": ["x"], "device": "d"}), ("/backup", {"user": {"a": 1}, "device": "d", "backup": ""}),
                          ("/keycount", {"user": 5, "device": None}), ("/upgrade", {"user": [], "auth": h64(), "device": 1, "backup": b64(9)})):
            code, _ = self.srv.post(path, obj)
            self.assertIn(code, (400, 401, 404), "%s -> %s" % (path, code))

    def test_chunked_bodies_refused(self):
        code, _ = self.srv.raw(b"POST /lookup HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n0\r\n\r\n")
        self.assertEqual(code, 400)

    def test_odd_paths_are_not_routes(self):
        for p in ("/__init__", "/api_send", "/send/../fetch", "/SEND", "/"):
            self.assertIn(self.srv.post(p, {})[0], (404, 400, 200)) if p == "/" else self.assertEqual(self.srv.post(p, {})[0], 404)

    def test_group_mailbox_byte_cap(self):
        addr = h64()
        for _ in range(600):   # 600 x ~30 KB would be 18 MB; the box keeps at most 16 MB
            self.assertEqual(self.srv.post("/gsend", {"to": addr, "blob": b64(30000)})[0], 200)
        total = sqlite3.connect(self.srv.db).execute("select sum(length(blob)) from gmsgs where addr=?", (addr,)).fetchone()[0]
        self.assertLessEqual(total, relay.MAX_GROUP_BYTES)
        self.assertGreater(total, relay.MAX_GROUP_BYTES - 40000)

    def test_device_token_is_not_stored(self):
        auth, device = signup(self.srv, "hashy_user")
        row = sqlite3.connect(self.srv.db).execute("select device, dh from accounts where user='hashy_user'").fetchone()
        self.assertEqual(row[0], "")
        self.assertEqual(bytes(row[1]), hashlib.sha256(device.encode()).digest())
        self.assertEqual(self.srv.post("/session", {"user": "hashy_user", "device": device})[0], 200)
        self.assertEqual(self.srv.post("/session", {"user": "hashy_user", "device": "0" * 64})[0], 401)
        code, r = self.srv.post("/login", {"user": "hashy_user", "auth": auth})
        self.assertEqual(code, 200)
        self.assertEqual(self.srv.post("/session", {"user": "hashy_user", "device": device})[0], 401, "login signs the old phone out")
        self.assertEqual(self.srv.post("/session", {"user": "hashy_user", "device": r["device"]})[0], 200)

    def test_lockout_is_short_and_capped(self):
        auth, _ = signup(self.srv, "lock_victim")
        for _ in range(relay.FREE_TRIES):
            self.assertEqual(self.srv.post("/login", {"user": "lock_victim", "auth": h64()})[0], 403)
        # still free: the right password works after ten wrong ones
        self.assertEqual(self.srv.post("/login", {"user": "lock_victim", "auth": auth})[0], 200)
        for _ in range(relay.FREE_TRIES + 1):
            self.srv.post("/login", {"user": "lock_victim", "auth": h64()})
        code, r = self.srv.post("/login", {"user": "lock_victim", "auth": auth})
        self.assertEqual(code, 403)
        self.assertIn("too many tries", r["error"])
        locked = sqlite3.connect(self.srv.db).execute("select locked from accounts where user='lock_victim'").fetchone()[0]
        self.assertLessEqual(locked - time.time(), relay.LOCK_MAX + 1)

    def test_kdf_and_upgrade(self):
        auth, device = signup(self.srv, "old_kdf_user")          # an old app: no kdf field means PBKDF2
        self.assertEqual(self.srv.post("/kdf", {"user": "old_kdf_user"})[1], {"kdf": 1})
        self.assertEqual(self.srv.post("/kdf", {"user": "no_such_person"})[1], {"kdf": 2})
        _, new = signup(self.srv, "new_kdf_user", kdf=2)
        self.assertEqual(self.srv.post("/kdf", {"user": "new_kdf_user"})[1], {"kdf": 2})
        code, r = self.srv.post("/login", {"user": "old_kdf_user", "auth": auth})
        self.assertEqual((code, r["kdf"]), (200, 1))
        better, backup = h64(), b64(120)
        self.assertEqual(self.srv.post("/upgrade", {"user": "old_kdf_user", "auth": better, "device": "1" * 64, "backup": backup})[0], 401)
        self.assertEqual(self.srv.post("/upgrade", {"user": "old_kdf_user", "auth": better, "device": r["device"], "backup": backup})[0], 200)
        self.assertEqual(self.srv.post("/kdf", {"user": "old_kdf_user"})[1], {"kdf": 2})
        self.assertEqual(self.srv.post("/login", {"user": "old_kdf_user", "auth": auth})[0], 403, "old hash no longer works")
        code, r = self.srv.post("/login", {"user": "old_kdf_user", "auth": better})
        self.assertEqual((code, r["backup"], r["kdf"]), (200, backup, 2))

    @unittest.skipUnless(__import__("importlib").util.find_spec("cryptography"), "needs the cryptography package")
    def test_server_identity_signs_the_challenge(self):
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
        n = h64()
        code, r = self.srv.post("/id", {"n": n})
        self.assertEqual(code, 200)
        pub = Ed25519PublicKey.from_public_bytes(base64.b64decode(r["pub"]))
        pub.verify(base64.b64decode(r["sig"]), b"tellem-server-v1|" + bytes.fromhex(n))   # raises if wrong
        self.assertEqual(self.srv.post("/id", {"n": "nope"})[0], 400)


class StoreRules(unittest.TestCase):
    def store(self):
        d = tempfile.mkdtemp()
        return relay.Store(os.path.join(d, "s.db"), os.path.join(d, "files"), 10 ** 9)

    def test_unfetched_group_mailboxes_expire_but_fetched_ones_stay(self):
        s = self.store()
        s.new_boxes_limit = 10 ** 9
        spam, real = h64(), h64()
        s.gput(spam, b"x" * 50)
        s.gput(real, b"y" * 50)
        s.gtake(real, 0, 0)                          # a member polls the real one
        old = time.time() - relay.GBOX_GRACE - 10
        s.db.execute("UPDATE gboxes SET created=?", (old,))
        s.sweep()
        self.assertEqual(s.db.execute("select count(*) from gmsgs where addr=?", (spam,)).fetchone()[0], 0)
        self.assertEqual(s.db.execute("select count(*) from gmsgs where addr=?", (real,)).fetchone()[0], 1)

    def test_writes_stop_when_the_database_cap_is_hit(self):
        s = self.store()
        s.db_max = 1            # any database is over this
        s._space = (0.0, True)
        self.assertEqual(s.gput(h64(), b"z"), "server full")
        s.touch(["a" * 64], b"w", relay.Counter(60))
        self.assertEqual(s.put("a" * 64, b"z"), "server full")

    def test_old_plaintext_tokens_are_migrated(self):
        d = tempfile.mkdtemp()
        path = os.path.join(d, "old.db")
        db = sqlite3.connect(path)
        db.execute("CREATE TABLE accounts(user TEXT PRIMARY KEY, salt BLOB, auth BLOB, enc BLOB, sig BLOB, inbox TEXT,"
                   " backup BLOB, device TEXT, fails INTEGER DEFAULT 0, locked REAL DEFAULT 0)")
        db.execute("INSERT INTO accounts(user, salt, auth, enc, sig, inbox, backup, device) VALUES('olduser',x'00',x'00',x'00',x'00','i',x'00','tok123')")
        db.commit()
        db.close()
        s = relay.Store(path, os.path.join(d, "files"), 10 ** 9)
        self.assertEqual(s.q("SELECT device FROM accounts")[0][0], "")
        self.assertTrue(s.device_ok("olduser", "tok123"))
        self.assertFalse(s.device_ok("olduser", "tok124"))

    def test_lockout_forgets_old_mistakes(self):
        s = self.store()
        salt = b"s" * 16
        good = hashlib.sha256(salt + b"g" * 32).digest()
        s.db.execute("INSERT INTO accounts(user, salt, auth, enc, sig, inbox, backup, dh) VALUES('forgetful',?,?,x'00',x'00','i',x'01',x'00')", (salt, good))
        s.db.execute("UPDATE accounts SET fails=?, lastfail=? WHERE user='forgetful'", (50, time.time() - relay.FAILS_FORGET - 5))
        self.assertEqual(s.check("forgetful", b"w" * 32), "wrong username or password")
        self.assertEqual(s.db.execute("select fails, locked from accounts where user='forgetful'").fetchone(), (1, 0))


class Redirects(unittest.TestCase):
    def test_giphy_fetch_does_not_follow_redirects(self):
        class H(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(302)
                self.send_header("Location", "http://127.0.0.1:1/secret")
                self.end_headers()

            def log_message(self, *a):
                pass

        srv = http.server.HTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        with self.assertRaises(urllib.error.HTTPError) as cm:
            relay._NO_REDIRECT.open("http://127.0.0.1:%d/" % srv.server_address[1], timeout=5)
        self.assertEqual(cm.exception.code, 302)
        srv.shutdown()

    def test_only_https_giphy_hosts(self):
        for url in ("http://media.giphy.com/x.gif", "https://evil.example/x.gif", "https://giphy.com.evil.example/x.gif"):
            with self.assertRaises(ValueError):
                relay.giphy_get(url, 1000)


if __name__ == "__main__":
    unittest.main()
