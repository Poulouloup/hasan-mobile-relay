# Hasan — Assistant vocal Android personnel

> **Hasan** (حسن) est un prénom arabe qui signifie *beau*, *bon*, *vertueux*. Ce nom a été choisi pour refléter l'ambition du projet : un assistant qui soit à la fois utile, bien conçu, et digne de confiance — sans compromis sur la vie privée.

---

## Philosophie

Hasan est né suite à la sortie et à la propagation de **[Hermes Agent](https://github.com/ykhli/hermes)**, un agent LLM local léger et open-source. L'idée de base : construire un client Android vocal qui s'y connecte, sans aucun service cloud tiers.

**Les trois principes directeurs du projet :**

1. **Sécurité** — aucun secret hardcodé, tout passe par EncryptedSharedPreferences. Le code source est auditable, sans surprise.
2. **Légèreté** — pipeline minimal : wake word ONNX → STT natif Android → SSE vers Hermes → TTS on-device. Pas de dépendance inutile, pas de SDK tiers propriétaire.
3. **Confidentialité maximale** — zéro appel vers des serveurs externes tiers. Le wake word tourne 100% localement (ONNX). Le LLM tourne sur votre propre machine via Hermes. Seul le STT dépend encore de Google Speech Services (migration Whisper ONNX prévue en V2).

---

## Architecture

```
[Permanent] HassanWakeWordService (PARTIAL_WAKE_LOCK)
  AudioRecord 16kHz → pipeline ONNX :
    melspectrogram.onnx → embedding_model.onnx → ok_hasan_*.onnx
  Score > seuil → wake word détecté
       │
       ▼
[STT]  SpeechRecognizer Android (français) → texte final
       │
       ▼
[HTTP] POST /v1/chat/completions  stream:true  (Hermes via HTTPS/SSE)
       │  tokens SSE un par un
       ▼
[UI]   Bulles chat temps réel    [TTS] parle dès le 1er chunk (< 300ms)
       │
       ▼
[Permanent] WakeWordService reprend (après fin TTS)
```

---

## Fonctionnalités

- **Wake word custom "Ok Hasan"** — modèle ONNX entraîné sur votre voix, détection 100% locale
- **Hot-swap modèle** — changer de modèle wake word sans redémarrer l'app
- **STT Android natif** — reconnaissance vocale en français
- **Streaming SSE** — les tokens Hermes s'affichent et se lisent en temps réel
- **TTS on-device** — synthèse vocale locale, choix du moteur et de la voix
- **UI dark premium** — BottomNavigationView, bulles chat, animations onde
- **Persistance Room** — historique complet des conversations
- **Aucun compte externe** — aucune clé API, aucun abonnement requis

---

## Structure du projet

```
hasanv1/
├── app/src/main/
│   ├── assets/                          # Modèles ONNX (wake word + infrastructure)
│   │   ├── melspectrogram.onnx          # Pipeline OpenWakeWord (à télécharger — voir §Setup)
│   │   ├── embedding_model.onnx         # Pipeline OpenWakeWord (à télécharger — voir §Setup)
│   │   ├── ok_hasan_last_vers.onnx      # Modèle custom "ok hasan" — dernière version
│   │   ├── ok_hasan_livekit.onnx        # Variante livekit
│   │   └── ok_hasan_v2_livekit.onnx     # Variante v2 livekit
│   └── java/com/hasan/v1/
│       ├── MainActivity.kt              # BottomNav (Chat / Réglages)
│       ├── MainViewModel.kt             # Orchestration STT → Hermes → TTS
│       ├── ConversationFragment.kt      # Écran Chat
│       ├── SettingsFragment.kt          # Écran Paramètres
│       ├── HassanWakeWordService.kt     # Service foreground wake word
│       ├── HassanTtsManager.kt          # TTS + AudioFocus
│       ├── HermesApiClient.kt           # Client HTTPS + SSE
│       ├── SpeechRecognizerManager.kt   # STT Android natif
│       ├── SettingsManager.kt           # EncryptedSharedPreferences
│       └── db/                          # Room (Conversation, Message, DAOs)
├── server/
│   ├── server.py                        # Simulateur Hermes (FastAPI + SSE)
│   └── gen_cert.py                      # Génère le certificat TLS auto-signé
└── training/                            # Entraînement du modèle "ok hasan" (Python)
    ├── run_training.py                  # Pipeline complet (~20 min sur CPU)
    ├── record_samples.py               # Enregistre 30 samples réels
    └── tools/                           # Scripts individuels par étape
```

---

## Setup

### 1 — Modèles ONNX de base

`melspectrogram.onnx` et `embedding_model.onnx` ne sont pas inclus dans le repo (trop lourds).  
Les télécharger depuis la release officielle OpenWakeWord v0.5.1 :

**Windows (PowerShell) :**
```powershell
$base = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
Invoke-WebRequest "$base/melspectrogram.onnx"  -OutFile "app\src\main\assets\melspectrogram.onnx"
Invoke-WebRequest "$base/embedding_model.onnx" -OutFile "app\src\main\assets\embedding_model.onnx"
```

**Linux / macOS :**
```bash
BASE="https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
curl -L "$BASE/melspectrogram.onnx"  -o app/src/main/assets/melspectrogram.onnx
curl -L "$BASE/embedding_model.onnx" -o app/src/main/assets/embedding_model.onnx
```

### 2 — Serveur Hermes local

```bash
pip install fastapi uvicorn cryptography
cd server
python gen_cert.py 192.168.1.100    # remplacer par votre IP locale
python server.py
```

### 3 — Configurer l'app

Ouvrir l'app → onglet **Réglages** → section **Connexion Hermes** → saisir l'URL et le token.

Valeurs par défaut dev : `https://192.168.1.100:8443` / `HASAN_DEV_TOKEN`

### 4 — Compiler et installer

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Wake word

Le service utilise [openwakeword-android-kt](https://github.com/Re-MENTIA/openwakeword-android-kt) (`xyz.rementia:openwakeword:0.1.5`).

### Modèles disponibles

Sélectionnables depuis **Réglages → Wake Word → Modèle de détection** sans redémarrer (hot-swap) :

| Fichier | Description |
|---|---|
| `ok_hasan_last_vers.onnx` | Dernière version entraînée — recommandé |
| `ok_hasan_livekit.onnx` | Variante livekit |
| `ok_hasan_v2_livekit.onnx` | Variante v2 livekit |

### Entraîner votre propre modèle

```bash
cd training
pip install pyttsx3 scipy scikit-learn skl2onnx onnxruntime
python record_samples.py   # enregistre 30 samples de votre voix
python run_training.py     # pipeline complet (~20 min sur CPU)
```

Le modèle est automatiquement copié dans `app/src/main/assets/ok_hasan.onnx`.

### Diagnostiquer via Logcat

```bash
adb logcat -s HassanWakeWord
```

---

## Stack technique

| Couche | Technologie |
|---|---|
| Langage | Kotlin 2.x, Gradle KTS |
| UI | ViewBinding, BottomNavigationView, Material 3 |
| Réactivité | Coroutines + StateFlow |
| Base de données | Room 2.6 |
| Secrets | EncryptedSharedPreferences |
| Wake word | ONNX Runtime Android 1.17 + openwakeword-android-kt 0.1.5 |
| Réseau | OkHttp 4.12 (HTTPS + SSE) |
| API min | Android 10 (API 29) |

---

## Roadmap

| Composant | État |
|---|---|
| Wake word OpenWakeWord custom "ok hasan" | ✅ |
| Hot-swap modèle wake word | ✅ |
| UI dark premium (Chat / Réglages) | ✅ |
| Bulles chat + rejouer TTS par message | ✅ |
| TTS on-device (Android natif, multi-moteur) | ✅ |
| STT Android natif | ✅ |
| Streaming HTTPS/SSE vers Hermes | ✅ |
| Simulateur Hermes (FastAPI) | ✅ |
| Persistance Room (conversations + messages) | ✅ |
| STT local hors-ligne (Whisper ONNX) | 🔜 V2 |
| TTS haute qualité | 🔜 V2 |
| Connexion Hermes Agent réel | 🔜 V3 |

---

## Dépannage

**Build échoue — modèles ONNX manquants**  
→ Refaire l'étape 1 (melspectrogram.onnx + embedding_model.onnx)

**Wake word ne se déclenche jamais**  
→ Baisser le seuil de sensibilité dans Réglages, ou vérifier la permission `RECORD_AUDIO`

**Trop de faux positifs**  
→ Augmenter le seuil de sensibilité dans Réglages, ou sélectionner un autre modèle

**Connexion Hermes échoue**  
→ Vérifier que `server.py` tourne et que le port 8443 est accessible :
```powershell
New-NetFirewallRule -DisplayName "Hasan Dev" -Direction Inbound -LocalPort 8443 -Protocol TCP -Action Allow
```

---

## Licence

MIT — voir [LICENSE](LICENSE)
