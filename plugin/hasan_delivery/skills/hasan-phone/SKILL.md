---
name: hasan-phone
description: "Act on the user's phone: SMS, calls, location, alarms."
version: 0.1.0
author: Loup Devernay (Poulouloup), Hermes Agent
license: MIT
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [phone, android, hasan, sms, notifications, device-control]
    related_skills: [hasan-plugin-architecture, hasan-bridge-diagnosis]
---

# Hasan Phone Skill

The user's Android phone runs the Hasan app, connected to this server through
the Hasan relay. Each phone action the user has enabled in the app is a native
tool of the `hasan_phone` toolset (`get_battery`, `send_sms`, …). This skill
says which tool to pick, what the phone does on its side, and how to read the
errors. It does not cover installing or repairing the bridge (see
`hasan-bridge-diagnosis`).

## When to Use

- The user asks for something that happens **on their phone**: "envoie un SMS à…",
  "appelle…", "où suis-je ?", "mets un réveil à 7 h", "allume la lampe",
  "baisse le son", "ouvre Spotify", "qu'est-ce que j'ai dans mon agenda
  aujourd'hui ?", "il me reste combien de batterie ?", "copie ça dans mon
  presse-papier".
- You need a fact only the phone has (position, battery, network, clipboard,
  contacts, the phone's own calendar).
- You want to put a notification on the phone right now.

Don't use for: the server's own state, Google services reached through
`homelab-api`, or anything on the user's computer.

## Tools

The tool list and each schema come from the app at runtime: only the
capabilities the user enabled in the app (screen « Tools ») **and** whose
Android permission is granted exist as tools. The schema of each tool is
authoritative; this table tells you what each one is for.

| Tool | Does | Parameters | Phone asks to confirm* |
|---|---|---|---|
| `get_battery` | Battery level, charging state | — | no |
| `get_location` | GPS position | — | yes |
| `get_network_info` | Wi-Fi SSID, IP, signal | — | no |
| `get_device_info` | Model, Android version, RAM, storage | — | no |
| `get_calendar_events` | Events from the phone's calendar | `date` (YYYY-MM-DD)?, `range_days`?, `limit`? | yes |
| `get_contacts` | Search contacts (name, number) | `query`?, `limit`? | yes |
| `get_clipboard` | Read the clipboard | — | yes |
| `set_clipboard` | Write text to the clipboard | `text` | no |
| `send_notification` | Show an Android notification | `body`, `title`? | no |
| `send_sms` | Send an SMS | `to`, `message` | yes |
| `make_call` | Start a phone call | `to` | yes |
| `set_alarm` | Create an alarm | `hour`, `minute`, `label`? | no |
| `set_volume` | Set media volume | `level` (0–100) | no |
| `toggle_flashlight` | Flashlight on/off | `on` (bool) | no |
| `discover_apps` | List installed apps | — | no |
| `launch_app` | Open an app | `package_name` | no |

\* Default; the user can change it per capability in the app.

Every tool also takes an optional `device_id`. Leave it out: it is resolved
automatically when a single connected device offers the capability.

## Procedure

1. **Call the tool directly.** If the `hasan_phone` tool you need is in your
   tool list, call it with the parameters from its schema — no `tool_search`,
   no reading of plugin source first.
   Done when: the tool returned a JSON result.
2. **Resolve names to numbers first.** `send_sms` and `make_call` need a phone
   number. If the user gave a name, call `get_contacts(query=<name>)`; if
   several numbers match, ask which one before sending.
   Done when: you hold exactly one number the user meant.
3. **Announce confirmations.** For a tool marked "yes" above, the phone shows a
   confirmation dialog and the call waits for the user's answer (30 s at most).
   Before calling it, tell the user in one short sentence to confirm on the
   phone ("Confirme sur ton téléphone.").
   Done when: the result came back, or the error is one of those below.
4. **Report the outcome** in one sentence, from the JSON result. For an action
   (SMS, call, alarm…), say it was done only if the result has no `error`.

## Errors

The tool returns JSON with an `error` field. What each one means:

| `error` | Meaning | What to do |
|---|---|---|
| `confirmation_denied` | The user refused on the phone | Stop. Don't retry or rephrase the request. |
| `command_timeout` | No answer within 30 s (dialog ignored, app in the background) | Say so; retry only if the user asks. |
| `capability_disabled_by_user` | Disabled in the app | Ask the user to enable it in Hasan → « Tools ». |
| `permission_denied` | Android permission missing | Ask the user to grant it (Hasan → « Tools » prompts for it). |
| `device_not_connected`, or « aucun device connecté n'expose … » | The app's connection to the relay is down | The app must be open; away from home, Tailscale must be on. |
| « plusieurs devices connectés exposent … » | Several devices offer it | Ask which device; pass its `device_id`. |
| `relay injoignable: …`, `HASAN_RELAY_URL/… non configurés` | Server-side problem | Tell the user; diagnose with `hasan-bridge-diagnosis`. |

## Notifying the user

- **Now, while the user is around:** `send_notification(body=..., title=...)`.
  It appears immediately, but only if the app is connected.
- **Later, or from a scheduled job:** deliver the job's output to the
  `hasan_delivery` platform. The relay keeps the message until the phone
  reconnects (or wakes the app through Firebase when that is configured).

## Pitfalls

- **A tool you expect is missing from your tool list.** The capability is not
  enabled in the app, or its permission isn't granted — ask the user to enable
  it in Hasan → « Tools ». A capability enabled after this session started may
  only show up in a new session.
- **The toolset is enabled but its tools are not in your tool list.** That is a
  server-side fault, not something to work around: tell the user the phone
  tools are not loaded in this session. Do not call the relay yourself through
  `terminal` — it is slow, unusable by voice, and hides the fault.
- **Don't chain confirmations.** Several sensitive actions in a row mean several
  dialogs on the phone; group them or ask the user first.
- **Voice replies are read aloud.** Keep the outcome sentence short and plain:
  no tables, no JSON.

## Verification

- Read actions: the answer quotes values from the JSON result (e.g. `level`,
  `charging`), not a guess.
- Write actions: the result has no `error`; for an SMS or a call, the user can
  check it on the phone.
