#!/usr/bin/env bash
# Tellem server on the DigitalOcean droplet. Tor (onion) runs as the system tor service;
# this runs the relay + the Cloudflare quick tunnel for Direct mode and publishes its address.
set -u
HERE=/opt/tellem/server
PAGE=/opt/tellem/page
LOG=/run/tellem/tunnel.log
PORT=8080        # the .onion (Tor) comes in here
PROXY_PORT=8081  # the Cloudflare tunnel (Direct) comes in here; only it may say who the client is
cd "$HERE"
python3 "$HERE/relay.py" --host 127.0.0.1 --port $PORT --proxy-port $PROXY_PORT --db "$HERE/telegramnt.db" --signups-per-hour 20 &
RELAY=$!
cloudflared tunnel --no-autoupdate --url "http://127.0.0.1:$PROXY_PORT" >"$LOG" 2>&1 &
TUNNEL=$!
trap 'kill $RELAY $TUNNEL 2>/dev/null' EXIT INT TERM
for _ in $(seq 20); do curl -s --max-time 2 "http://127.0.0.1:$PORT/" | grep -q telegramnt && break; sleep 1; done
curl -s --max-time 2 "http://127.0.0.1:$PORT/" | grep -q telegramnt || { echo "Server didn't start"; exit 1; }
URL=""
for _ in $(seq 60); do
    URL=$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' "$LOG" | head -1)
    [ -n "$URL" ] && break
    sleep 1
done
if [ -n "$URL" ]; then
    echo "Direct at $URL"
    cd "$PAGE" && git pull --rebase -q
    printf '{"server": "%s"}\n' "$URL" > telegramnt/server.json
    if ! git diff --quiet -- telegramnt/server.json; then
        git commit -q -m "Tellem server address" -- telegramnt/server.json && git push -q && echo "Address published"
    fi
fi
wait -n $RELAY $TUNNEL
