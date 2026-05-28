# Hasan — Règles permanentes Claude Code

## Projet
Assistant vocal Android personnel. Architecture : wake word ONNX → STT Android → Hermes Agent (SSE) → TTS Android. UI dark/premium avec BottomNavigationView et Room DB.

## Règles de code

### Langue
- Tous les commentaires en français
- Les noms de variables, fonctions et classes en anglais (convention Kotlin/Android)

### Fichiers protégés — ne jamais modifier sans permission explicite
- `HassanWakeWordService.kt` — pipeline wake word ONNX, modification = crash audio garanti
- `HassanTtsManager.kt` — gestion TTS et AudioFocus, très sensible au cycle de vie
- `HermesApiClient.kt` — parsing SSE et gestion des timeouts, ne pas toucher sans [OVERRIDE]

### Architecture MVVM stricte
- Activity/Fragment → ViewModel → Repository/DAO
- Jamais de logique métier dans un Fragment
- Jamais d'accès Room direct dans une Activity
- Toujours `activityViewModels()` pour partager le ViewModel entre fragments

### Ressources Android
- Toutes les couleurs dans `res/values/colors.xml` — jamais de `#RRGGBB` hardcodé dans un layout
- Tous les textes dans `res/values/strings.xml` — jamais de string literal dans un layout
- Toutes les dimensions dans `res/values/dimens.xml`
- Palette : fond `#0A0A0A`, accent `#CC2936`, vert `#1D9E75`

### Secrets et configuration
- Jamais d'URL, token ou secret hardcodé dans le code source
- Tout passe par `SettingsManager` (EncryptedSharedPreferences)
- Valeurs par défaut dev acceptées uniquement dans `SettingsManager.companion`

### Compatibilité
- Toujours vérifier API 29+ (Android 10 minimum)
- Utiliser `Build.VERSION.SDK_INT >= Build.VERSION_CODES.X` pour les guards
- Pas d'API dépréciée sans alternative explicite

### Tests
- ViewModel test obligatoire pour tout nouveau ViewModel avec logique non triviale
- Pas besoin de tester les fragments (UI test trop fragile)

### Format des commits
```
feat(scope): description courte
fix(scope): description courte
refactor(scope): description courte
```
Exemples : `feat(ui): ajouter le mode sombre au drawer`, `fix(stt): corriger la fuite mémoire AudioRecord`

## Stack technique
- Kotlin 2.x, Gradle KTS, kapt pour Room
- OkHttp 4.12, Coroutines + StateFlow, ViewBinding
- Room 2.6, EncryptedSharedPreferences, BottomNavigationView
- ONNX Runtime Android 1.17, openwakeword-android-kt 0.1.5
- API min 29, target 35

## Fichiers clés
| Fichier | Rôle |
|---|---|
| `MainViewModel.kt` | Orchestrateur principal STT/Hermes/TTS |
| `SettingsManager.kt` | Lecture/écriture toutes les prefs |
| `HassanWakeWordService.kt` | Service foreground wake word |
| `HermesApiClient.kt` | Client HTTP/SSE Hermes |
| `ConversationFragment.kt` | Écran principal Chat |
| `SettingsFragment.kt` | Écran Paramètres |
| `db/` | Room (Conversation, Message, DAO, Database) |

## Token Economy

- Utiliser `/hasan-caveman` pour les tâches simples (fix build, ajout couleur, tweak layout)
- Utiliser `/hasan-context` en début de nouvelle session pour restaurer le contexte
- Utiliser `/caveman` pour toute session longue (économie ~75% tokens sur les échanges)
- Fichiers à ne jamais charger entièrement sans raison :
  * `fragment_settings.xml` (600+ lignes) → lire par section avec `offset`/`limit`
  * `fragment_conversation.xml` (300+ lignes) → idem
  * `HassanDatabase.kt` → accéder aux DAOs séparément
- Toujours préférer les diffs (`Edit`) aux fichiers complets (`Write`) pour les modifications < 20 lignes
- Invoquer `/token-audit` si la session dépasse 50 échanges
