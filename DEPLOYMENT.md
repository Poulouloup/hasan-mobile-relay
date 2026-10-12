# Deployment guide — relay + plugin on your own VPS

This guide is for someone who already has [Hermes Agent](https://github.com/ykhli/hermes)
running on a server and wants to add Hasan (the Android companion app) to it.

If you're a developer building the Android app from source instead, see
[SETUP.md](SETUP.md).

> **TLS requirement (app ≥ the "standard TLS" change, see CHANGELOG)** — the
> Android app now performs standard certificate verification only: the relay
> and hermes-webui must be reachable under a **hostname** whose certificate is
> trusted by Android (e.g. Let's Encrypt). Self-signed certificates (Caddy
> `tls internal`) and bare IP addresses are rejected, with no trust prompt.
> The bare-IP topology described under [Caddy topology](#caddy-topology) below
> therefore **no longer produces a server the app can talk to** — the
> installer has not been reworked for this yet (tracked as the next step:
> "domain name + existing Caddy" mode).

## Overview

```
[Android app] ──WSS──> [relay server, this VPS] ──HTTP──> [Hermes agent, same VPS]
                              ↑
                        [hasan_delivery plugin]
                        (installed inside Hermes)
```

Three things run on your server:
1. **The relay server** (`server/relay/`) — a small Python/aiohttp process
   that holds the WebSocket connection to the phone and bridges it to Hermes.
2. **Caddy** — TLS termination in front of the relay and (if present)
   hermes-webui, since the relay itself speaks plain WebSocket/HTTP.
3. **The `hasan_delivery` plugin** — installed inside your existing Hermes
   installation, talks to the relay over plain HTTP (both on the same
   machine/localhost in the common case).

The Android app itself is installed separately on the phone (build it from
source per [SETUP.md](SETUP.md), or install a released APK if one is
provided).

## Quick start (recommended)

```bash
git clone https://github.com/Poulouloup/hasan-mobile-relay.git
cd hasan-mobile-relay

# Optional but recommended: dry-run detection first — reports what the
# installer WILL do, without touching anything.
bash server/lib/test-detect.sh

sudo ./server/install.sh
```

`server/install.sh` is **adaptive**: it detects whether your Hermes runs
natively or in a container and provisions accordingly (see
[Hermes native vs containerised](#hermes-native-vs-containerised) below). It
also:

- **Refuses to run from inside a container** — it installs Docker and launches
  containers, which only makes sense on the host. Running it inside Hermes
  would attempt Docker-in-Docker and write only to ephemeral layers.
- **Reuses an existing Caddy** (container or systemd) instead of launching a
  second one on port 443 — avoids the diverging-Caddyfile pitfall below.
- **Can run without root** if you're in the `docker` group and Docker is
  already installed; root is only needed to install Docker itself.

> `install.sh` replaces the earlier native-only `install-bridge.sh` — it
> covers both native and containerised Hermes in one script.

It:
- Installs Docker if not already present (official `get.docker.com` script).
- Asks a few questions (your server's public IP/hostname, whether
  hermes-webui is already running here and its password if so, whether to
  expose Hermes's internal dashboard, `HERMES_HOME`).
- Generates `RELAY_ADMIN_TOKEN` and all the other secrets/config for you
  (nothing to hand-edit afterward).
- Starts the relay and Caddy as Docker containers (`network_mode: host` —
  no bridge network, no port-mapping/NAT to reason about; they talk to
  `127.0.0.1`-bound peers like hermes-webui exactly as native processes
  would).
- Installs the `hasan_delivery` plugin into your existing Hermes venv (this
  part stays outside Docker — it runs inside the Hermes gateway process
  you already have, not a new standalone service).
- Installs a diagnostic-only skill for Hermes Agent
  (`plugin/hasan_delivery/skills/hasan-bridge-diagnosis/`) describing the
  four-port topology and a known Caddy-config pitfall, so a future
  diagnosis (yours or the agent's) doesn't have to rediscover it.
- Prints a ready-to-scan pairing code JSON at the end.

Safe to re-run — it detects an existing deployment and updates it in
place. It also refuses to silently overwrite a Caddy config it doesn't
recognize (checked via a marker comment) — pass `--force` only if you're
sure you want to replace it.

### Caddy topology

One Caddy instance serves two separate needs on two separate public ports
(there's no domain on a bare IP, so no subdomain/path routing is possible):

| Public port | Routes to | What |
|---|---|---|
| `:443` | `127.0.0.1:8787` | hermes-webui (chat), if detected |
| `:8443` (opt-in) | `127.0.0.1:9119` | Hermes's internal dashboard |

Caddy uses its own self-signed local CA (`tls internal`) — consistent
with the app's TOFU certificate pinning, and there's no domain here for
ACME anyway. The rendered `server/Caddyfile` sets `default_sni` explicitly
because Android/OkHttp doesn't send SNI for a literal IP address (RFC
6066) — without it, the TLS handshake fails.

**A known pitfall**: it's possible to end up with two different Caddy
processes (one containerized, one native) both trying to claim the same
public port, with diverging configs — this happened once during this
project's own development and cost hours of confusing debugging (401s
that looked like a credentials problem but were actually requests hitting
the wrong backend). `install.sh` detects any foreign holder of port 443
(container or native process) and reuses it instead of starting a second
Caddy. See
`plugin/hasan_delivery/skills/hasan-bridge-diagnosis/SKILL.md` for the
full diagnostic writeup, including which Caddy config is canonical for
which purpose.

## Hermes native vs containerised

`install.sh` handles both, and the plugin lands in the same place either way
(the directory Hermes scans at startup) — only *how* it gets there differs.

### Native Hermes

The common VPS case. The installer:
- installs hermes-webui alongside Hermes if absent (clones
  `nesquena/hermes-webui` at `master`, installs its deps into Hermes's venv,
  starts it via its own `ctl.sh`);
- copies the plugin into `~/.hermes/plugins/` and installs `httpx` into the
  Hermes venv;
- restarts Hermes via `systemctl --user restart hermes-gateway.service`.

### Containerised Hermes (homelab)

Here the plugin's dependency (`httpx`) is already in the official image, and
webui **must live inside the Hermes container** — it imports Hermes's own code
(`agent.*`, `hermes_cli.*`) and can't run standalone. So webui is not
installed separately: it's baked into a **derived image**.

1. Build the derived image (Hermes + webui, s6-supervised) — see
   [`server/hermes-image/README.md`](server/hermes-image/README.md):
   ```bash
   docker build -t hasan-hermes server/hermes-image
   ```
2. Point your Hermes `docker-compose.yml` at `hasan-hermes` instead of
   `nousresearch/hermes-agent`, with `command: gateway run` and a
   `~/.hermes:/opt/data` volume. Bring it up.
3. Run `sudo ./server/install.sh`. It detects the container (by image), drops
   the plugin into the mounted volume via `docker cp` (the volume is owned by
   the container's uid 10000, so a plain host copy can't write there), fixes
   ownership so Hermes can read it, then restarts the container.

If Hermes runs the **bare official image** (no webui), the installer **refuses
and tells you to rebuild** with the derived image — webui can't be bolted on
afterward, and without it the app's Chat screen wouldn't work.

> **Cleanup note**: files Hermes writes to `~/.hermes` are owned by the
> container's uid 10000, so `rm -rf ~/.hermes` from the host needs sudo, or:
> `docker run --rm -v ~/.hermes:/d alpine rm -rf /d/*`.

## Manual / non-Docker path (advanced, or what the script does under the hood)

Prefer this if you don't want Docker on this host, or need a Caddy setup
different from what the script provisions.

### 1 — Deploy the relay server

```bash
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

**FCM wake-up for proactive notifications (optional)** — lets the app
receive Hermes-initiated notifications even while fully closed, without a
permanently running background service. Skip this if you're fine only
receiving proactive notifications while the app has an active WebSocket
connection (foreground or recent background) — the relay works identically
without it, just without the wake-up accelerator when the device is
offline/asleep.

1. From the same Firebase project used for `app/google-services.json` (see
   `SETUP.md` §4) — Firebase console → ⚙️ Project Settings → **Service
   accounts** tab → **Generate new private key**. This downloads a JSON
   file; it's a real secret (equivalent to a password), never commit it.
2. Copy it to the server, outside the git checkout, e.g.:
   ```bash
   sudo mkdir -p /etc/hermes-relay
   sudo cp fcm-service-account.json /etc/hermes-relay/fcm-service-account.json
   sudo chown hasanrelay:hasanrelay /etc/hermes-relay/fcm-service-account.json
   sudo chmod 600 /etc/hermes-relay/fcm-service-account.json
   ```
   (adjust the owner to whichever unprivileged user `install-relay.sh`
   created for the `hermes-relay` service — same user that owns
   `~/.hermes/hasan-relay-sessions.json`.)
3. Add `RELAY_FCM_CREDENTIALS_PATH` to the same systemd unit as
   `RELAY_ADMIN_TOKEN`:
   ```
   Environment=RELAY_FCM_CREDENTIALS_PATH=/etc/hermes-relay/fcm-service-account.json
   ```
   then `sudo systemctl restart hermes-relay`. If the path is missing or the
   file is invalid, the relay logs a warning and starts normally anyway —
   proactive notifications keep working via WebSocket, just without the FCM
   wake-up accelerator.

### 2 — Install the `hasan_delivery` plugin

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

### 3 — Pair the phone

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

### 4 — Verify everything works

- Relay: `curl https://relay.example.com/health` and `/version`.
- Plugin: `journalctl --user -u hermes-gateway.service -f | grep -i hasan_delivery`
  — look for `Connecté` after restarting the gateway.
- App: open the Chat tab, send a message, confirm you get a response.

If something doesn't connect, check in this order: relay reachable from the
phone (network/firewall/TLS) → relay reachable from the Hermes host (plugin
logs) → plugin env vars correct (`HASAN_RELAY_URL`, `HASAN_RELAY_SESSION_TOKEN`).
