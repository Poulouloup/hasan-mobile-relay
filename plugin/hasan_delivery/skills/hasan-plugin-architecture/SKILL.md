---
name: hasan-plugin-architecture
description: >
  Explains how the hasan_delivery plugin works internally — its two
  independent roles (messaging via adapter.py, phone-capability tools via
  tools.py), the multi-device capability discovery mechanism, and the
  auth model each HTTP call uses. Read this before answering questions
  about "why does hasan_phone show this tool", "why did a phone action
  fail", or before touching adapter.py/tools.py. Read-only knowledge —
  never edits plugin code or restarts anything itself.
category: general
---

# Hasan Plugin Architecture

Use this skill when asked how the `hasan_delivery` plugin works, why a
`hasan_phone` tool behaves a certain way, why a capability isn't showing up
for a device, or before making changes to `adapter.py`/`tools.py`.

## 1. Two independent roles, one plugin

`hasan_delivery` registers two unrelated things with Hermes from the same
`register(ctx)` entry point (`__init__.py`):

| Role | File | Direction | Scope |
|---|---|---|---|
| Messaging platform | `adapter.py`, `ctx.register_platform` | Hermes → phone (proactive notification) | Single device: the most recently seen paired one, or `HASAN_RELAY_DEVICE_ID` |
| Function-calling tools | `tools.py`, `ctx.register_tool` per capability | Hermes → any connected device, awaits result | Multi-device |

Both authenticate to the relay with the same `HASAN_RELAY_ADMIN_TOKEN`
(messaging used to rely on a copy of the phone's session token, which broke
every time the phone refreshed its session — relay 0.2.0+ accepts the admin
token on `/phone/*`). They still use **different device scoping models**
on purpose — messaging never needed more than one fixed device (the
operator's own phone), but the tools side had to become cross-device once a
second device type (Hasan Desktop) started connecting to the same relay. If
a symptom only affects one of the two, check which token/scope it actually
uses before assuming both are broken.

## 2. Capability discovery — dynamic, not hardcoded

Tool schemas (name, description, parameters) are **never hardcoded in
`tools.py`**. They come from the Android app at runtime:

```
App connects (WS) → sends {channel:"system", type:"capabilities",
                            payload:{device_label, capabilities:[...]}}
    ↓
Relay persists per device_hash (server/relay/pairing.py::Session)
    ↓
tools.py fetches GET /devices at gateway startup → union of capability
NAMES across ALL known devices becomes the registered tool set
```

Adding a capability in the app's `Capability.kt` requires **zero edits**
here — only a `hermes gateway restart` (or it appears live, see §4).

## 3. Multi-device targeting

Every registered tool (e.g. `get_battery`) is a single tool shared across
devices — never `phone_get_battery` / `desktop_get_battery`. It gains an
optional `device_id` parameter, resolved by the **handler**, not the
schema — Hermes does not validate JSON Schema `enum`/`required` against LLM
tool-call arguments before invoking the handler, so `device_id` being
absent from `required` is not a loophole, it's the actual enforcement
point (`tools.py::_resolve_device_id`):

- Exactly one connected device exposes the capability → resolved silently,
  the caller never needs to pass `device_id` (this is what keeps a
  single-phone user's experience unchanged).
- Zero connected devices expose it → clear error, no call attempted.
- Two or more → error listing device labels, asking the caller to specify
  `device_id`.

`POST /bridge/command` (the actual action call) therefore always carries an
explicit `device_id` in its body and authenticates with the **admin
token**, not a session token — a session token is inherently scoped to one
device's identity, which can't express "any of N devices" once there's more
than one. `GET /devices` uses the same admin-token model, mirroring
`/pairing/create`'s existing pattern (503 if unconfigured, plain Bearer
compare — see `server/relay/server.py::handle_pairing_create`).

## 4. Hot refresh — additions only, never removals

`tools.py::sync_tools(ctx)` is called from a second background loop in
`adapter.py` (`_run_device_watch_loop`, started from `connect()` alongside
the existing messaging long-poll) that polls `GET /devices/watch` — a
long-poll that wakes up when any device connects/disconnects or its
capability set changes. On wake, it re-fetches `GET /devices`, recomputes
the capability-name union, and **registers any newly-appeared names**.

It never deregisters a tool whose last exposing device disappeared. This is
a deliberate constraint, not an oversight: `PluginContext` (the Hermes
runtime, outside this repo) exposes `register_tool()` but no
`deregister_tool()`, and adding one would mean modifying Hermes's own
source — out of bounds for this plugin (only pure additions to
`plugin/hasan_delivery/` itself are allowed). A tool that's become
orphaned stays visible but fails cleanly at call time via
`_resolve_device_id`'s "no connected device exposes this capability" error,
until the next natural gateway restart clears it.

## 5. `ctx.register_tool()`, never `registry.register()` directly

`tools.py` registers each tool through `ctx.register_tool(...)`
(`hermes_cli/plugins.py`), which does two things: calls the underlying
`tools.registry.registry.register()` **and** records the tool name in
Hermes's internal plugin-attribution list
(`PluginManager._plugin_tool_names`). That second part is what
`get_plugin_toolsets()`/`hermes tools enable`/`hermes tools list` read to
recognize `hasan_phone` as a valid, plugin-provided toolset.

Calling `tools.registry.registry.register()` directly (bypassing `ctx`)
still makes the tool *callable*, but the toolset stays invisible/"Unknown
toolset" to any of the tooling above — this was a real bug found and fixed
during development, not a hypothetical. If a future change to this file
ever needs to register a tool outside `register_tools()`/`sync_tools()`,
route it through `ctx.register_tool()`.

## 6. Where to look for each symptom

| Symptom | Look at |
|---|---|
| A tool never appears for any device | `GET /devices` output (relay-side data), then `_merge_capabilities` |
| Tool appears but errors "no connected device exposes..." | Device's `connected` state in `GET /devices` — WS may be down |
| Tool errors asking to specify `device_id` | Expected — 2+ connected devices expose that capability, working as designed |
| New device's capabilities never show up without restart | Check `_run_device_watch_loop` is actually running (`adapter.py`, started from `connect()`, needs `ctx` passed to the adapter factory) |
| `hasan_phone` toolset itself missing from `hermes tools list` | Registration likely bypassed `ctx.register_tool()` — see §5 |
| Messaging fails with 401 but tools work | Relay older than 0.2.0 (`GET /version`) — it only accepts the admin token on the tools routes. Update the relay |
| A message send fails with `no_paired_device` (409) | No phone has been paired yet — expected before the first QR scan |

## Non-goals

This skill is explanatory only. It never edits `adapter.py`/`tools.py`,
never restarts the gateway, and never calls `/bridge/command` or any relay
endpoint itself. For live debugging of the relay/Caddy/Docker deployment
layer (as opposed to plugin internals), see `hasan-bridge-diagnosis`
instead — the two skills cover different layers of the same system.
