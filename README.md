# Hasan — Personal Android Voice Assistant

> **Hasan** (حسن) is an Arabic given name meaning *beautiful*, *good*, *virtuous*. The name reflects the project's ambition: an assistant that is useful, well-crafted, and trustworthy — with no compromise on privacy.

---

## Philosophy

Hasan was born following the release and spread of **[Hermes Agent](https://github.com/ykhli/hermes)**, a lightweight local LLM agent. The idea: build an Android voice client that connects to it, with no third-party cloud service involved.

**Three guiding principles:**

1. **Security** — no hardcoded secrets, everything goes through EncryptedSharedPreferences. TOFU (Trust On First Use) certificate verification for self-signed servers. The source code is auditable, no surprises.
2. **Lightness** — minimal pipeline: local ONNX wake word → native Android STT → SSE to Hermes → on-device TTS. No unnecessary dependencies, no proprietary third-party SDK.
3. **Maximum privacy** — zero calls to external third-party servers. Wake word runs 100% locally (ONNX). The LLM runs on your own machine via Hermes. Only STT still depends on Google Speech Services (Whisper ONNX migration planned for V2).

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
[HTTP] POST /v1/responses  stream:true  (Hermes via HTTPS/SSE)
       │  Chunk-based SSE reading, no read timeout (tool calls can last minutes)
       ▼
[UI]   Real-time chat bubbles    [TTS] speaks from the 1st chunk (< 300ms)
       │
       ▼
[Permanent] WakeWordService resumes (after TTS ends)

[Permanent] HassanOrchestratorService (MCP)
  Long-poll /commands → execute capabilities → POST /results
  Heartbeat every 30s, auto re-register on capability change
```

---

## Features

- **Custom "Ok Hasan" wake word** — ONNX model trained on your voice, 100% local detection
- **Background wake word pipeline** — full STT → Hermes → TTS cycle runs even when the app is not in the foreground
- **Hot-swap model** — switch wake word model without restarting the app
- **Native Android STT** — voice recognition in French
- **SSE streaming** — chunk-based reading with no timeout, Hermes tokens display and are read aloud in real time
- **On-device TTS** — local speech synthesis, engine and voice selection
- **Dark premium UI** — BottomNavigationView (Chat / MCP / Settings), chat bubbles, wave animations
- **Room persistence** — full conversation history with sessions
- **Sessions** — multiple Hermes sessions, switchable from settings
- **Push notifications** — background responses trigger Android notifications
- **MCP orchestrator** — connect to a remote orchestrator for 11 device capabilities: battery, SMS, contacts, location, notifications, volume, app launch, app discovery, alarm, Wi-Fi info, device info
- **Light Mode** — full-screen hands-free interface with large mic button, TTS mute, and wake word listening
- **TOFU certificate verification** — Trust On First Use for self-signed HTTPS servers
- **No external account** — no API key, no subscription required

---

## Project structure

```
hasanv1/
└── app/src/main/
    ├── assets/                          # ONNX models (wake word + infrastructure)
    │   ├── melspectrogram.onnx          # OpenWakeWord pipeline (download — see SETUP.md)
    │   ├── embedding_model.onnx         # OpenWakeWord pipeline (download — see SETUP.md)
    │   ├── ok_hasan_last_vers.onnx      # Custom "ok hasan" model — latest version
    │   ├── ok_hasan_livekit.onnx        # Livekit variant
    │   └── ok_hasan_v2_livekit.onnx     # Livekit v2 variant
    └── java/com/hasan/v1/
        ├── MainActivity.kt              # BottomNav (Chat / MCP / Settings)
        ├── MainViewModel.kt             # STT → Hermes → TTS orchestration
        ├── ConversationFragment.kt      # Chat screen (foreground voice interaction)
        ├── SettingsFragment.kt          # Settings screen
        ├── McpFragment.kt               # MCP orchestrator connection + capabilities (grid UI)
        ├── LightModeFragment.kt         # Full-screen hands-free Light Mode
        ├── HassanWakeWordService.kt     # Wake word foreground service
        ├── WakeWordPipeline.kt          # Background STT → Hermes → TTS pipeline
        ├── HassanTtsManager.kt          # TTS + AudioFocus
        ├── HassanNotificationService.kt # Background push notifications
        ├── HassanOrchestratorService.kt # MCP orchestrator long-poll + heartbeat
        ├── HermesApiClient.kt           # HTTPS + SSE client (TOFU, chunk-based)
        ├── OrchestratorApiClient.kt     # MCP orchestrator HTTP client
        ├── CapabilityExecutor.kt        # MCP capability execution (battery, SMS, etc.)
        ├── SpeechRecognizerManager.kt   # Native Android STT
        ├── HassanSoundPlayer.kt         # Wake/done sound effects (SoundPool)
        ├── MessageAdapter.kt            # RecyclerView adapter for chat bubbles
        ├── SettingsManager.kt           # EncryptedSharedPreferences + prefs
        ├── db/                          # Room (Conversation, Message, Session, DAOs)
        └── utils/                       # HasanDialog, MarkdownUtils
```

→ See [SETUP.md](SETUP.md) to build and run the project.

---

## Tech stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.x, Gradle KTS |
| UI | ViewBinding, BottomNavigationView, Material 3 |
| Reactivity | Coroutines + StateFlow |
| Database | Room 2.6 |
| Secrets | EncryptedSharedPreferences |
| Wake word | ONNX Runtime Android 1.17 + openwakeword-android-kt 0.1.5 |
| Network | OkHttp 4.12 (HTTPS + SSE, chunk-based streaming) |
| Markdown | Markwon 4.6 (chat bubble rendering) |
| Min API | Android 10 (API 29) |

---

## Roadmap

| Component | Status |
|---|---|
| Custom "ok hasan" OpenWakeWord model | ✅ |
| Hot-swap wake word model | ✅ |
| Dark premium UI (Chat / MCP / Settings) | ✅ |
| Chat bubbles + per-message TTS replay | ✅ |
| On-device TTS (native Android, multi-engine) | ✅ |
| Native Android STT | ✅ |
| HTTPS/SSE streaming to Hermes (TOFU) | ✅ |
| Room persistence (conversations + messages) | ✅ |
| Sessions (multiple Hermes sessions) | ✅ |
| Background wake word pipeline (STT → Hermes → TTS) | ✅ |
| Push notifications (background responses) | ✅ |
| MCP orchestrator (11 capabilities) | ✅ |
| Light Mode (full-screen hands-free) | ✅ |
| STT stop button + visualizer in chat | ✅ |
| Dual VPS + MCP connection indicators | ✅ |
| Offline local STT (Whisper ONNX) | 🔜 V2 |
| High-quality TTS (Piper) | 🔜 V2 |

---

## License

MIT — see [LICENSE](LICENSE)
