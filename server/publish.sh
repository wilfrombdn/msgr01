#!/usr/bin/env bash
# Publishes the Direct (Cloudflare tunnel) address to telegramnt/server.json on the download site whenever it changes.
# Runs as its own user (tellem-pub), the only one that can push to GitHub, so a problem in the relay can't reach the key.
set -u
LOG=/run/tellem-tunnel/tunnel.log
PAGE=/opt/tellem-pub/page
export HOME=/opt/tellem-pub
export GIT_SSH_COMMAND="ssh -i /opt/tellem-pub/.ssh/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes -o UserKnownHostsFile=/opt/tellem-pub/.ssh/known_hosts"
last=""
while true; do
    URL=$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' "$LOG" 2>/dev/null | grep -v '^https://api\.' | tail -1)   # never Cloudflare's own api. address
    if [ -n "$URL" ] && [ "$URL" != "$last" ]; then
        if cd "$PAGE" && git pull --rebase -q; then
            printf '{"server": "%s"}\n' "$URL" > telegramnt/server.json
            if ! git diff --quiet -- telegramnt/server.json; then
                git commit -q -m "Tellem server address" -- telegramnt/server.json && git push -q || { sleep 30; continue; }
            fi
            last="$URL"
        fi
    fi
    sleep 30
done
