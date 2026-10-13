# Deployment guide — relay + plugin on your own server

This guide is for someone who already has [Hermes Agent](https://github.com/ykhli/hermes)
running on a server and wants to add Hasan (the Android companion app) to it.

If you're a developer building the Android app from source instead, see
[SETUP.md](SETUP.md).

> **You need two domain names with a trusted certificate.** The Android app
> performs standard TLS verification only: the relay and hermes-webui must
> each be reachable as `https://<hostname>` with a certificate trusted by
> Android (e.g. Let's Encrypt). Self-signed certificates and bare IP
> addresses are rejected, with no trust prompt and no way around it. The
> server does not have to be exposed to the Internet — a home network or a
> Tailscale-only host works, as long as the names resolve and the certificate
> is a real one (typically obtained with a DNS challenge).

## Overview

```
[Android app] ──HTTPS/WSS──> [reverse proxy :443] ──> [relay :8767]  <──HTTP── [hasan_delivery plugin, inside Hermes]
                                                └──> [hermes-webui :8787] ──> [Hermes agent]
```

Four things run on your server:
1. **The relay server** (`server/relay/`) — a small Python/aiohttp process
   that holds the WebSocket connection to the phone (pairing, phone actions,
   notifications sent by Hermes).
2. **hermes-webui** — the third-party web UI whose API powers the app's Chat,
   Skills, Memory, Kanban and Files screens.
3. **A reverse proxy** (Caddy) — TLS termination in front of both, since
   neither speaks TLS itself. One hostname each.
4. **The `hasan_delivery` plugin** — installed inside your existing Hermes
   installation, talks to the relay over plain HTTP on localhost.

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
natively or in a container, and whether a reverse proxy already holds port
443, and provisions accordingly. It also:

- **Refuses to run from inside a container** — it installs Docker and launches
  containers, which only makes sense on the host.
- **Reuses an existing reverse proxy** instead of launching a second one on
  port 443, and never edits its configuration: it prints the two blocks to add.
- **Can run without root** if you're in the `docker` group and Docker is
  already installed; root is only needed to install Docker itself.
- **Never installs or modifies Hermes.**

It:
- Installs Docker if not already present (official `get.docker.com` script).
- Asks for `HERMES_HOME` and **two domain names** — one for the relay, one for
  hermes-webui (e.g. `hasan.example.net` and `hermes.example.net`). IP
  addresses, `localhost`, and anything with a scheme, port or path are
  refused. Two names, not one: both services serve their own paths from the
  root (`/health` exists on each), so they cannot share a hostname.
- Generates `RELAY_ADMIN_TOKEN` and the relay's `server/.env` (nothing to
  hand-edit afterward).
- Starts the relay as a Docker container named `hasan-relay`, published on
  the host's `127.0.0.1:8767` only.
- Installs hermes-webui if it isn't answering (native Hermes), at a pinned
  version, as a systemd user service — see below.
- Installs the `hasan_delivery` plugin, gives it the relay's address and
  admin token, enables it, and restarts Hermes.
- Installs two diagnostic skills for Hermes Agent
  (`plugin/hasan_delivery/skills/`).
- Prints a ready-to-scan pairing code JSON at the end.

Safe to re-run — it detects an existing deployment and updates it in place.

To skip the questions (scripted runs): `RELAY_HOST`, `WEBUI_HOST`,
`CADDY_NETWORK`, `WEBUI_REF` and `HERMES_WEBUI_PASSWORD` can be passed as
environment variables.

### Reverse proxy: three layouts

The installer looks at who holds port 443 and picks one of three layouts.
What changes between them is **how the proxy reaches the two services**.

| Layout | Detected when | Relay upstream | hermes-webui upstream |
|---|---|---|---|
| **Own Caddy** | port 443 is free | `127.0.0.1:8767` | `127.0.0.1:8787` |
| **Existing proxy, host network** | 443 held by a native process, or a container in `network_mode: host` | `127.0.0.1:8767` | `127.0.0.1:8787` |
| **Existing Caddy on a Docker network** | 443 held by a container on a regular Docker network | `hasan-relay:8767` | `<gateway of that network>:8787` |

**Own Caddy.** The installer starts `caddy:2-alpine` with a two-site
`server/Caddyfile`. Caddy requests a Let's Encrypt certificate for each name
by itself, which requires both names to point at this server **and ports 80
and 443 to be reachable from the Internet**. On a private server (home
network, Tailscale), that cannot work: run your own Caddy with a DNS
challenge instead, and the installer will reuse it. It refuses to overwrite a
`server/Caddyfile` it doesn't recognise (marker comment on the first line)
unless you pass `--force`.

**Existing proxy.** Nothing is launched and nothing of yours is edited. At
the end the installer prints the two blocks to add to your Caddyfile, e.g.:

```
hasan.example.net {
	reverse_proxy hasan-relay:8767 {
		flush_interval -1
	}
}

hermes.example.net {
	reverse_proxy 172.18.0.1:8787 {
		flush_interval -1
	}
}
```

No `tls` directive: both names must be covered by a certificate your Caddy
already obtains (a wildcard, typically). `flush_interval -1` matters — the
chat is a long-lived event stream and must not be buffered.

**Existing Caddy on a Docker network — why it's a separate case.** A
container on a regular Docker network cannot reach the host's `127.0.0.1`;
that address is the container's own. So the installer:

- attaches the relay container to that network (it writes
  `server/docker-compose.network.yml`), where Caddy reaches it by name;
- makes hermes-webui listen on **that network's gateway address** (the host,
  as seen from the network) instead of `127.0.0.1`;
- prints the firewall rule to add if a host firewall is active, limited to
  that Docker subnet, e.g.
  `sudo ufw allow from 172.18.0.0/16 to 172.18.0.1 port 8787 proto tcp`.

`host.docker.internal:host-gateway` is not a substitute: it resolves to the
gateway of Docker's *default* network, not of the network your Caddy is on.
If the Caddy container is on several networks, the installer asks which one
to use (or set `CADDY_NETWORK`).

**A known pitfall**: two reverse proxies (one containerised, one native) both
trying to claim port 443 with diverging configs — this happened once during
this project's own development and cost hours of confusing debugging (401s
that looked like a credentials problem but were requests hitting the wrong
backend). That is why the installer never starts a second one. See
`plugin/hasan_delivery/skills/hasan-bridge-diagnosis/SKILL.md` for the full
diagnostic writeup.

## Hermes native vs containerised

`install.sh` handles both, and the plugin lands in the same place either way
(the directory Hermes scans at startup) — only *how* it gets there differs.

### Native Hermes

Recognised by `~/.hermes/hermes-agent/run_agent.py` (the official installer's
git layout). The installer needs Hermes's own Python, and finds it the way
Hermes records it: current installs keep a managed environment under
`~/.hermes/installs/<id>/environments/<hash>/venv`, whose path **changes on
every `hermes update`** and is read from `~/.hermes/installs/<id>/facts.json`;
older installs have a plain `~/.hermes/hermes-agent/venv`. That managed
environment has **no `pip`**, and needs none — everything the plugin and
hermes-webui import is already there — so `pip` is only used when it exists
and something is actually missing.

The installer then:
- installs hermes-webui if it isn't answering: clones
  `nesquena/hermes-webui` into `~/hermes-webui` at a **pinned tag**
  (`exp-v0.52.459`, checked against its expected commit), not the tip of
  `master`. hermes-webui must be upgraded together with Hermes; that tag
  matches Hermes v0.21.6 — for another Hermes version pass
  `WEBUI_REF=<tag or commit>`;
- runs it as a **systemd user service**, `hermes-webui.service`, through
  `~/.local/share/hasan/webui-start.sh`. That launcher looks up Hermes's
  Python at every start, so a `hermes update` doesn't break it. Its password
  and listen address live in `~/.config/hasan/webui.env` (mode 600); a
  password is generated if you don't have one;
- copies the plugin into `~/.hermes/plugins/`, writes `HASAN_RELAY_URL` and
  `HASAN_RELAY_ADMIN_TOKEN` into `~/.hermes/.env` (other lines untouched), and
  runs `hermes plugins enable hasan_delivery` — third-party plugins are
  disabled by default in recent Hermes versions;
- restarts Hermes via `systemctl --user restart hermes-gateway.service`.

For the user services to start at boot without anyone logged in:
`sudo loginctl enable-linger <user>`.

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
4. Add the two plugin variables the installer prints (`HASAN_RELAY_URL`,
   `HASAN_RELAY_ADMIN_TOKEN`) to the Hermes container's environment and
   restart it — the installer does not edit your compose file.

If Hermes runs the **bare official image** (no webui), the installer **refuses
and tells you to rebuild** with the derived image — webui can't be bolted on
afterward, and without it the app's Chat screen wouldn't work.

> **Not tested**: containerised Hermes behind an existing Caddy on a Docker
> network. The installer prints what it expects (the Hermes container
> attached to that network, reached by name), but that combination has not
> been run for real.

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

Set `RELAY_ADMIN_TOKEN` (used by the plugin and to generate pairing codes) and
`RELAY_PUBLIC_URL` (the relay's public `https://` address, no port) in the systemd unit
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

Then set the required environment variables (`HASAN_RELAY_URL`, normally
`http://127.0.0.1:8767`, and `HASAN_RELAY_ADMIN_TOKEN`, the relay's
`RELAY_ADMIN_TOKEN`) and restart the gateway:

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

Nothing to copy back to the plugin after pairing: it authenticates with the
relay's admin token, not with the phone's session token (which the phone
replaces on its own every time it refreshes its session).

Pairing codes expire after `ttl_seconds` (10 minutes) — regenerate one if it
expires before you scan it.

### 4 — Verify everything works

- Relay: `curl https://relay.example.com/health` and `/version`.
- Plugin: `journalctl --user -u hermes-gateway.service -f | grep -i hasan_delivery`
  — look for `Connecté` after restarting the gateway.
- App: open the Chat tab, send a message, confirm you get a response.

If something doesn't connect, check in this order: relay reachable from the
phone (network/firewall/TLS — the app's Settings → Connections screen says
which) → relay reachable from the Hermes host (plugin logs) → plugin env vars
correct (`HASAN_RELAY_URL`, `HASAN_RELAY_ADMIN_TOKEN`) and plugin enabled
(`hermes plugins list`).
