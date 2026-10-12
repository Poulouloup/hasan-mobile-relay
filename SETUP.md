# Setup

## 1 — Base ONNX models

`melspectrogram.onnx` and `embedding_model.onnx` are not included in the repo (too large).  
Download them from the official OpenWakeWord v0.5.1 release:

**Windows (PowerShell):**
```powershell
$base = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
Invoke-WebRequest "$base/melspectrogram.onnx"  -OutFile "app\src\main\assets\melspectrogram.onnx"
Invoke-WebRequest "$base/embedding_model.onnx" -OutFile "app\src\main\assets\embedding_model.onnx"
```

**Linux / macOS:**
```bash
BASE="https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
curl -L "$BASE/melspectrogram.onnx"  -o app/src/main/assets/melspectrogram.onnx
curl -L "$BASE/embedding_model.onnx" -o app/src/main/assets/embedding_model.onnx
```

## 2 — A server to talk to

The app needs a deployed relay and hermes-webui, each behind `https://` on a
hostname with a certificate Android trusts — see [`DEPLOYMENT.md`](DEPLOYMENT.md).
There is no local self-signed development mode: the app performs standard TLS
verification only and refuses self-signed certificates, bare IP addresses and
plain `http://`.

## 3 — Configure the app

Open the app → **Settings** → **Connexions** → **Scanner un QR de pairing**,
and scan the pairing code printed by `server/install.sh` (or generated with
`POST /pairing/create`, see `DEPLOYMENT.md`). One scan configures both the
relay and the chat. Manual entry of the two addresses is available under
**Configuration manuelle**.

## 4 — Firebase (proactive notifications, optional)

Needed only if you want proactive notifications (e.g. cron job reminders) to
reach your phone while the app is fully closed. Skip this section if you're
fine only receiving them while the app is open — the build works without it.

Firebase Cloud Messaging (FCM) is used strictly as a **data-only wake-up
signal** — Google never sees the notification text, only "wake up this
device now". The real content is fetched afterwards straight from your own
relay server (`GET /phone/pending`, your own TLS connection). See
`docs/ARCHITECTURE.md` for the full flow.

1. Go to the [Firebase console](https://console.firebase.google.com) and
   create a project (Analytics can be left disabled, it's not needed).
2. Add an Android app to the project — package name **must** be `com.hasan.v1`.
3. Download the generated `google-services.json` and place it at
   `app/google-services.json` (gitignored — one per Firebase project, not
   shared across forks/installs).
4. That's it for the app side. The server-side counterpart (a Firebase
   service account key, needed on the relay to actually send wake-ups) is
   covered in [`DEPLOYMENT.md`](DEPLOYMENT.md) — the app builds and runs
   fine without it, it just won't receive wake-ups while closed until the
   server side is also configured.

## 5 — Build and install

```bash
./gradlew :app:testDebugUnitTest   # JVM unit tests (TLS rules, URL derivation)
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
