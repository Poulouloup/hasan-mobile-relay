# Hasan — Personal Android Voice Assistant

> **Hasan** (حسن) is an Arabic given name meaning *beautiful*, *good*, *virtuous*. The name reflects the project's ambition: an assistant that is useful, well-crafted, and trustworthy — with no compromise on privacy.

---

## Philosophy

Hasan was born following the release and spread of **[Hermes Agent](https://github.com/ykhli/hermes)**, a lightweight local LLM agent. The idea: build an Android voice client that connects to it, with no third-party cloud service involved.

**Three guiding principles:**

1. **Security** — no hardcoded secrets, everything goes through EncryptedSharedPreferences. The source code is auditable, no surprises.
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
       ▼
[STT]  Android SpeechRecognizer (French) → final text
       │
       ▼
[HTTP] POST /v1/chat/completions  stream:true  (Hermes via HTTPS/SSE)
       │  SSE tokens one by one
       ▼
[UI]   Real-time chat bubbles    [TTS] speaks from the 1st chunk (< 300ms)
       │
       ▼
[Permanent] WakeWordService resumes (after TTS ends)
```

---

## Features

- **Custom "Ok Hasan" wake word** — ONNX model trained on your voice, 100% local detection
- **Hot-swap model** — switch wake word model without restarting the app
- **Native Android STT** — voice recognition in French
- **SSE streaming** — Hermes tokens display and are read aloud in real time
- **On-device TTS** — local speech synthesis, engine and voice selection
- **Dark premium UI** — BottomNavigationView, chat bubbles, wave animations
- **Room persistence** — full conversation history
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
        ├── MainActivity.kt              # BottomNav (Chat / Settings)
        ├── MainViewModel.kt             # STT → Hermes → TTS orchestration
        ├── ConversationFragment.kt      # Chat screen
        ├── SettingsFragment.kt          # Settings screen
        ├── HassanWakeWordService.kt     # Wake word foreground service
        ├── HassanTtsManager.kt          # TTS + AudioFocus
        ├── HermesApiClient.kt           # HTTPS + SSE client
        ├── SpeechRecognizerManager.kt   # Native Android STT
        ├── SettingsManager.kt           # EncryptedSharedPreferences
        └── db/                          # Room (Conversation, Message, DAOs)
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
| Network | OkHttp 4.12 (HTTPS + SSE) |
| Min API | Android 10 (API 29) |

---

## Roadmap

| Component | Status |
|---|---|
| Custom "ok hasan" OpenWakeWord model | ✅ |
| Hot-swap wake word model | ✅ |
| Dark premium UI (Chat / Settings) | ✅ |
| Chat bubbles + per-message TTS replay | ✅ |
| On-device TTS (native Android, multi-engine) | ✅ |
| Native Android STT | ✅ |
| HTTPS/SSE streaming to Hermes | ✅ |
| Room persistence (conversations + messages) | ✅ |
| Offline local STT (Whisper ONNX) | 🔜 V2 |
| High-quality TTS | 🔜 V2 |
| Real Hermes Agent connection | 🔜 V3 |
| Secure MCP server (encrypted in-app connection) | 🔜 V3 |

---

## License

MIT — see [LICENSE](LICENSE)
