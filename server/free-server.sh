#!/usr/bin/env bash
# Free Telegramn't server: runs on this laptop, reachable from anywhere through a
# Cloudflare quick tunnel (no account, hides the home internet address).
# The tunnel address changes every start, so it's published to
# https://wilfrombdn.github.io/telegramnt/server.json, where the app looks it up.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
PAGE="$HOME/wilfrombdn.github.io"
LOG="${XDG_RUNTIME_DIR:-/tmp}/telegramnt-tunnel.log"   # RAM only, gone on reboot
PORT=8080

python3 "$HERE/relay.py" --host 127.0.0.1 --port $PORT --db "$HERE/telegramnt.db" --behind-proxy &
RELAY=$!
"$HOME/.local/bin/cloudflared" tunnel --no-autoupdate --url "http://127.0.0.1:$PORT" >"$LOG" 2>&1 &
TUNNEL=$!
trap 'kill $RELAY $TUNNEL 2>/dev/null' EXIT INT TERM

# Don't publish anything unless the server itself is answering.
for _ in $(seq 20); do curl -s --max-time 2 "http://127.0.0.1:$PORT/" | grep -q telegramnt && break; sleep 1; done
curl -s --max-time 2 "http://127.0.0.1:$PORT/" | grep -q telegramnt || { echo "Server didn't start"; exit 1; }

URL=""
for _ in $(seq 60); do
    URL=$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' "$LOG" | head -1)
    [ -n "$URL" ] && break
    sleep 1
done
if [ -z "$URL" ]; then echo "Tunnel didn't start; see $LOG"; exit 1; fi
echo "Server is at $URL"

# Publish the address. Commits only server.json, and pulls first so edits made on GitHub don't block it.
cd "$PAGE" && git pull --rebase -q
printf '{"server": "%s"}\n' "$URL" > telegramnt/server.json
if ! git diff --quiet -- telegramnt/server.json; then
    git commit -q -m "Telegramn't server address" -- telegramnt/server.json && git push -q && echo "Address published"
fi

wait -n $RELAY $TUNNEL   # stop if either one dies (systemd restarts both)
