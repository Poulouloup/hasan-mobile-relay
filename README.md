# Hasan — Personal Android Voice Assistant

> **Hasan** (حسن) is an Arabic given name meaning *beautiful*, *good*, *virtuous*. The name reflects the project's ambition: an assistant that is useful, well-crafted, and trustworthy — with no compromise on privacy.

---

## Philosophy

Hasan was born following the release and spread of **[Hermes Agent](https://github.com/ykhli/hermes)**, a lightweight local LLM agent. The idea: build an Android voice client that connects to it, with no third-party cloud service involved.

**Three guiding principles:**

1. **Security** — no hardcoded secrets, everything goes through EncryptedSharedPreferences. Standard TLS verification (system-trusted certificate chain and hostname) on every connection — no self-signed exceptions, no trust prompts. The source code is auditable, no surprises.
2. **Lightness** — minimal pipeline: local ONNX wake word → native Android STT → SSE to Hermes → on-device TTS. No unnecessary dependencies, no proprietary third-party SDK.
3. **Maximum privacy** — the LLM and all message content stay on your own server. Wake word runs 100% locally (ONNX). The only external dependency is an optional, content-free wake-up signal via Firebase Cloud Messaging (Google sees only "wake up this device now", never the notification text) needed to receive proactive notifications while the app is fully closed, without a permanently running background service — disabled by default, degrades gracefully to WebSocket-only delivery if not configured (see `docs/ARCHITECTURE.md`). STT still depends on Google Speech Services (Whisper ONNX migration planned for V2).

---

## Architecture

```
[Permanent] HassanWakeWordService (PARTIAL_WAKE_LOCK)
  AudioRecord 16kHz → ONNX pipeline:
    melspectrogram.onnx → embedding_model.onnx → ok_hasan_*.onnx
  Score > threshold → wake word detected
       │
       ├─ App foreground ──→ ConversationFragment (STT via ViewModel)
       │
       └─ App background ──→ WakeWordPipeline (STT → Hermes → TTS autonome)
                                     │
                                     ▼
                              HassanNotificationService (push notification)
       │
       ▼
[STT]  Android SpeechRecognizer (French) → final text
       │
       ▼
[WSS]  ConnectionManager — single persistent WebSocket to the relay server
       │  ChannelMultiplexer routes envelopes by channel: chat / bridge / system / proactive
       │  Exponential backoff reconnect, standard TLS verification, proactive
       │  ConnectivityManager.NetworkCallback reconnect on network change
       ▼
[chat] ChatStreamHandler — streamChat() one envelope stream per turn
       │  chat/send → connecting → connected → thinking/token* → done (or error)
       │  Clarify support: chat/clarify ↔ chat/clarify_response (agent.clarify_callback)
       ▼
[UI]   Real-time chat bubbles (Compose)   [TTS] speaks once streaming completes
       │
       ▼
[Permanent] WakeWordService resumes (after TTS ends)

[bridge] BridgeCommandHandler — server-initiated device capability calls
  Confirmation dialog for sensitive capabilities (send_sms, get_location, get_contacts)
  before CapabilityExecutor runs them — see Capability.kt authRequiredDefault
```

Pairing with the relay is done once via QR code (`QrScannerActivity` → `PairingManager`), which stores a session token and refresh token in `EncryptedSharedPreferences`. No manual server URL/token entry required afterward.

---

## Features

- **Custom "Ok Hasan" wake word** — ONNX model trained on your voice, 100% local detection
- **Background wake word pipeline** — full STT → Hermes → TTS cycle runs even when the app is not in the foreground
- **Hot-swap model** — switch wake word model without restarting the app
- **Native Android STT** — voice recognition in French
- **WebSocket relay** — single persistent connection multiplexing chat streaming, device bridge commands, system events and proactive pushes; automatic reconnect with backoff and network-change detection
- **QR pairing** — one-time pairing flow, no manual token/URL entry
- **Clarify support** — Hermes can ask a clarifying question mid-turn (with choices or free text) before continuing
- **Device bridge with confirmation** — Hermes can request sensitive device actions (SMS, location, contacts); the app shows an Authorize/Deny dialog before executing
- **On-device TTS** — local speech synthesis, engine and voice selection
- **Dark premium UI** — 100% Jetpack Compose (Chat / Activity / Settings), cut-corner design system, wave animations
- **Room persistence** — full conversation history with sessions
- **Sessions** — multiple Hermes sessions, auto-titled from the first message, rename/delete from the drawer
- **Kanban board** — consult and move tasks between columns, create boards, powered by hermes-webui's existing Kanban API
- **Session files** — browse and download the active session's workspace (files the agent wrote), powered by hermes-webui's existing file-listing API
- **Push notifications** — background responses trigger Android notifications
- **FCM wake-up for proactive notifications** (optional) — receive Hermes-initiated notifications (e.g. cron job reminders) even while the app is fully closed, without a permanently running background service. Strictly data-only: Firebase only carries an opaque wake-up signal, the actual text is fetched afterwards from your own relay over TLS. Degrades gracefully to WebSocket-only delivery if not configured — see `docs/ARCHITECTURE.md` and `SETUP.md`
- **Light Mode** — full-screen hands-free interface with large mic button, TTS mute, and wake word listening
- **Standard TLS verification** — the relay and hermes-webui must present a certificate trusted by Android for their hostname (e.g. Let's Encrypt behind your own domain); renewals are transparent, and an untrusted certificate or hostname mismatch is a clear error, never a prompt. Self-signed certificates and bare IP addresses are no longer accepted
- **No external account** — no API key, no subscription required
- **Adaptive one-command deployment** — `server/install.sh` detects your Hermes install (native **or** containerised) and provisions accordingly: the relay and Caddy as Docker containers, and the `hasan_delivery` plugin dropped into wherever Hermes reads it. Refuses to run from inside a container, reuses an existing Caddy instead of colliding on port 443, and never modifies your Hermes install

---

## Project structure

```
hasanv1/
├── server/
│   ├── install.sh                       # adaptive installer (native or containerised Hermes)
│   ├── lib/detect.sh                    # infra detection (no side effects) — sourced by install.sh
│   ├── lib/actions.sh                   # install actions (writes) — sourced by install.sh
│   ├── lib/test-detect.sh              # dry-run detection report — run first on any target
│   ├── hermes-image/                    # derived Hermes image = official + hermes-webui (s6 service)
│   │   ├── Dockerfile                   # FROM nousresearch/hermes-agent + clone webui (master)
│   │   └── s6-webui/                    # s6 service files supervising webui in-container
│   └── relay/                           # Python (aiohttp) relay server — WebSocket ↔ Hermes bridge
│       ├── server.py                    # WS handling, auth, dispatch by channel
│       ├── chat_stream.py               # chat/send → Hermes /v1/responses SSE → chat/token|done|error
│       └── bridge_commands.py           # bridge/command dispatch to the paired device
└── app/src/main/
    ├── assets/                          # ONNX models (wake word + infrastructure)
    │   ├── melspectrogram.onnx          # OpenWakeWord pipeline (download — see SETUP.md)
    │   ├── embedding_model.onnx         # OpenWakeWord pipeline (download — see SETUP.md)
    │   ├── ok_hasan_last_vers.onnx      # Custom "ok hasan" model — latest version
    │   ├── ok_hasan_livekit.onnx        # Livekit variant
    │   └── ok_hasan_v2_livekit.onnx     # Livekit v2 variant
    └── java/com/hasan/v1/
        ├── MainActivity.kt              # Compose host, drawer, navigation
        ├── MainViewModel.kt             # STT → Hermes → TTS orchestration, UiState
        ├── ConversationFragment.kt      # Chat screen (foreground voice/text interaction)
        ├── SettingsFragment.kt          # Settings screen host
        ├── ToolsPermissionsFragment.kt  # Device capabilities + confirmation toggles
        ├── LightModeFragment.kt         # Full-screen hands-free Light Mode
        ├── OnboardingActivity.kt        # First-run flow
        ├── QrScannerActivity.kt         # QR pairing scanner
        ├── HassanWakeWordService.kt     # Wake word foreground service
        ├── WakeWordPipeline.kt          # Background STT → Hermes → TTS pipeline
        ├── HassanTtsManager.kt          # TTS + AudioFocus
        ├── HassanNotificationService.kt # Background push notifications
        ├── Capability.kt                # Device capability registry (permission, auth required)
        ├── CapabilityExecutor.kt        # Capability execution (SMS, location, volume, etc.)
        ├── SpeechRecognizerManager.kt   # Native Android STT
        ├── HassanSoundPlayer.kt         # Wake/done sound effects (SoundPool)
        ├── SettingsManager.kt           # EncryptedSharedPreferences + prefs
        ├── network/                     # ConnectionManager, ChannelMultiplexer, ChatStreamHandler,
        │                                 # BridgeCommandHandler, ActivityLog, models/
        ├── auth/                        # PairingManager, SessionTokenStore, BiometricAuthHelper
        ├── ui/                          # Compose: components/ (drawer, header), screens/ (Chat,
        │                                 # Activity, Settings, Tools & Permissions), theme/
        ├── db/                          # Room (Conversation, Message, HermesSession, DAOs)
        └── utils/                       # HasanDialog, MarkdownUtils, LatencyLog
```

→ See [SETUP.md](SETUP.md) to build and run the project from source.
→ See [DEPLOYMENT.md](DEPLOYMENT.md) to deploy the relay server + plugin on your own Hermes VPS.

---

## Tech stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.x, Gradle KTS |
| UI | Jetpack Compose, Material 3 |
| Reactivity | Coroutines + StateFlow |
| Database | Room 2.6 |
| Secrets | EncryptedSharedPreferences |
| Wake word | ONNX Runtime Android 1.17 + openwakeword-android-kt 0.1.5 |
| Network | OkHttp 4.12 WebSocket (single persistent connection, standard TLS via `SecureHttp`) |
| Relay server | Python 3, aiohttp (WebSocket ↔ Hermes HTTP/SSE bridge) |
| Markdown | Markwon 4.6 (chat bubble rendering) |
| Min API | Android 10 (API 29) |

---

## Roadmap

| Component | Status |
|---|---|
| Custom "ok hasan" OpenWakeWord model | ✅ |
| Hot-swap wake word model | ✅ |
| Dark premium Compose UI (Chat / Activity / Settings) | ✅ |
| Chat bubbles + per-message TTS replay | ✅ |
| On-device TTS (native Android, multi-engine) | ✅ |
| Native Android STT | ✅ |
| WebSocket relay (single persistent connection) | ✅ |
| Standard TLS verification (replaces self-signed TOFU pinning) | ✅ |
| QR pairing flow | ✅ |
| Room persistence (conversations + messages) | ✅ |
| Sessions (multiple, auto-titled, rename/delete) | ✅ |
| Background wake word pipeline (STT → Hermes → TTS) | ✅ |
| Push notifications (background responses) | ✅ |
| Clarify (mid-turn clarifying questions) | ✅ |
| Device bridge with confirmation dialog | ✅ |
| Kanban board (read + move cards + create boards) | ✅ |
| Session files (browse + download workspace) | ✅ |
| Light Mode (full-screen hands-free) | ✅ |
| One-command bridge deployment (relay + Caddy + plugin, Docker Compose) | ✅ |
| Adaptive installer — Hermes native *or* containerised, Caddy reuse, container guard | ✅ |
| Derived Hermes image bundling hermes-webui (s6-supervised) | ✅ |
| Offline local STT (Whisper ONNX) | 🔜 V2 |
| High-quality TTS (Piper) | 🔜 V2 |

---

## License

MIT — see [LICENSE](LICENSE)
