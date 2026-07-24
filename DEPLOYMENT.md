# Deployment guide — relay + plugin on your own VPS

This guide is for someone who already has [Hermes Agent](https://github.com/ykhli/hermes)
running on a server and wants to add Hasan (the Android companion app) to it.

If you're a developer building the Android app from source instead, see
[SETUP.md](SETUP.md).

## Overview

```
[Android app] ──WSS──> [relay server, this VPS] ──HTTP──> [Hermes agent, same VPS]
                              ↑
                        [hasan_delivery plugin]
                        (installed inside Hermes)
```

Two components run on your server:
1. **The relay server** (`server/relay/`) — a small Python/aiohttp process
   that holds the WebSocket connection to the phone and bridges it to Hermes.
2. **The `hasan_delivery` plugin** — installed inside your existing Hermes
   installation, talks to the relay over plain HTTP (both on the same
   machine/localhost in the common case).

The Android app itself is installed separately on the phone (build it from
source per [SETUP.md](SETUP.md), or install a released APK if one is
provided).

## 1 — Deploy the relay server

```bash
git clone https://github.com/Poulouloup/hasan-mobile-relay.git
cd hasan-mobile-relay
sudo ./server/relay/install-relay.sh
```

This installs the relay as a systemd service (`hermes-relay`), running under
a dedicated unprivileged system user. Idempotent — safe to re-run to update
an existing install (`git pull` + dependency refresh + service restart).

**Not covered by the script** — the relay itself has no TLS, it expects a
reverse proxy in front of it:

- Set up a reverse proxy (Caddy is the simplest option — automatic TLS) in
  front of port 8767. See the script's final output for a copy-pasteable
  `Caddyfile` example, or `install-relay.sh` directly.
- Open only the reverse proxy's port (443) in your firewall — never expose
  8767 directly to the internet.

Set `RELAY_ADMIN_TOKEN` (used to generate pairing codes) and optionally
`RELAY_PUBLIC_URL` in the systemd unit
(`/etc/systemd/system/hermes-relay.service`, see the commented example
lines for `WEBUI_URL`/`WEBUI_PASSWORD` if you also run hermes-webui on the
same VPS) — then `sudo systemctl restart hermes-relay`.

Verify it's up:

```bash
curl https://relay.example.com/health
curl https://relay.example.com/version
```

## 2 — Install the `hasan_delivery` plugin

On the same server (or wherever your Hermes gateway process runs):

```bash
./plugin/hasan_delivery/install-plugin.sh
```

Then set the required environment variables (`HASAN_RELAY_URL`,
`HASAN_RELAY_SESSION_TOKEN` — the second one comes from step 3 below, so
you'll set it after pairing) and restart the gateway:

```bash
hermes gateway restart
```

Full detail, including how to verify it connected: see
[`plugin/hasan_delivery/README.md`](plugin/hasan_delivery/README.md).

## 3 — Pair the phone

The relay identifies a paired device by a session token, obtained once via a
QR code scanned from the app.

1. Generate a pairing code (requires `RELAY_ADMIN_TOKEN` from step 1):

   ```bash
   curl -X POST https://relay.example.com/pairing/create \
     -H "Authorization: Bearer <RELAY_ADMIN_TOKEN>"
   ```

   Returns `{"code": "...", "ttl_seconds": 600, "relay_url": "...", ...}`
   (also includes `webui_url`/`webui_password` if hermes-webui is configured
   on the relay, letting one QR pair both the phone bridge and the chat
   connection at once).

2. Turn that JSON into a scannable QR code. This repo doesn't ship a QR
   generator — any tool works, e.g.:

   ```bash
   curl -s -X POST https://relay.example.com/pairing/create \
     -H "Authorization: Bearer <RELAY_ADMIN_TOKEN>" | qrencode -t ansiutf8
   ```

   (`qrencode` — install via your package manager, e.g. `apt install qrencode`.
   Any QR generator that accepts raw text/JSON works the same way.)

3. In the Hasan app: **Settings → scan QR** (see `QrScannerActivity` /
   `PairingManager` in the app source). The app stores the resulting session
   token in `EncryptedSharedPreferences` — no manual URL/token entry needed
   afterward, and no further action needed on the relay/plugin side for this
   device.

4. Copy the session token issued during pairing into `HASAN_RELAY_SESSION_TOKEN`
   for the plugin (step 2) — needed for the plugin to send/receive on behalf
   of this device outside of the WebSocket the app itself holds.

Pairing codes expire after `ttl_seconds` (10 minutes) — regenerate one if it
expires before you scan it.

## 4 — Verify everything works

- Relay: `curl https://relay.example.com/health` and `/version`.
- Plugin: `journalctl --user -u hermes-gateway.service -f | grep -i hasan_delivery`
  — look for `Connecté` after restarting the gateway.
- App: open the Chat tab, send a message, confirm you get a response.

If something doesn't connect, check in this order: relay reachable from the
phone (network/firewall/TLS) → relay reachable from the Hermes host (plugin
logs) → plugin env vars correct (`HASAN_RELAY_URL`, `HASAN_RELAY_SESSION_TOKEN`).
