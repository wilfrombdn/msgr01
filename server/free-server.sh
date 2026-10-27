#!/usr/bin/env bash
# Free MSNGR server: runs on this laptop.
#
# Reachable two ways during the move off Cloudflare:
#   - Tor .onion (the app's only path now): a persistent hidden service in front of the relay. The
#     onion address is its own public key, so it's baked into the app; nothing is published anywhere.
#   - Cloudflare quick tunnel (legacy, for app builds older than 4.2): address published to
#     https://wilfrombdn.github.io/telegramnt/server.json. Drop this once everyone's on 4.2+.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
PAGE="$HOME/wilfrombdn.github.io"
LOG="${XDG_RUNTIME_DIR:-/tmp}/telegramnt-tunnel.log"   # RAM only, gone on reboot
PORT=8080
ONION_DIR="$HOME/.telegramnt-onion"

# --- Tor onion service (persistent; the key lives in $ONION_DIR/hs, outside the repo) ---
mkdir -p "$ONION_DIR/data"; chmod 700 "$ONION_DIR" "$ONION_DIR/data"
if [ ! -f "$ONION_DIR/torrc" ]; then
    cat > "$ONION_DIR/torrc" <<EOF
DataDirectory $ONION_DIR/data
SocksPort 0
HiddenServiceDir $ONION_DIR/hs
HiddenServicePort 80 127.0.0.1:$PORT
Log notice file $ONION_DIR/tor.log
EOF
fi

python3 "$HERE/relay.py" --host 127.0.0.1 --port $PORT --db "$HERE/telegramnt.db" --behind-proxy --signups-per-hour 20 &
RELAY=$!
tor -f "$ONION_DIR/torrc" >/dev/null 2>&1 &
TOR=$!
"$HOME/.local/bin/cloudflared" tunnel --no-autoupdate --url "http://127.0.0.1:$PORT" >"$LOG" 2>&1 &
TUNNEL=$!
trap 'kill $RELAY $TOR $TUNNEL 2>/dev/null' EXIT INT TERM

# Don't publish anything unless the server itself is answering.
for _ in $(seq 20); do curl -s --max-time 2 "http://127.0.0.1:$PORT/" | grep -q telegramnt && break; sleep 1; done
curl -s --max-time 2 "http://127.0.0.1:$PORT/" | grep -q telegramnt || { echo "Server didn't start"; exit 1; }

[ -f "$ONION_DIR/hs/hostname" ] && echo "Onion: $(cat "$ONION_DIR/hs/hostname")"

# Legacy Cloudflare address (for pre-4.2 app builds). Harmless to keep during the transition.
URL=""
for _ in $(seq 60); do
    URL=$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' "$LOG" | head -1)
    [ -n "$URL" ] && break
    sleep 1
done
if [ -n "$URL" ]; then
    echo "Cloudflare (legacy) at $URL"
    cd "$PAGE" && git pull --rebase -q
    printf '{"server": "%s"}\n' "$URL" > telegramnt/server.json
    if ! git diff --quiet -- telegramnt/server.json; then
        git commit -q -m "MSNGR legacy server address" -- telegramnt/server.json && git push -q && echo "Address published"
    fi
fi

wait -n $RELAY $TOR $TUNNEL   # stop if any one dies (systemd restarts them)
