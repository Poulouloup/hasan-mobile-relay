# Audit Android / Plateforme

Périmètre : permissions manifest, foreground services, Doze/WakeLock, cycle de vie
arrière-plan, compatibilité SDK, edge-to-edge. Projet minSdk 29 / targetSdk 35 /
compileSdk 35 (`app/build.gradle.kts:23,27,28`).

## Bloquant

**[app/src/main/AndroidManifest.xml:78-81]** `HassanNotificationService` est déclaré
avec `android:foregroundServiceType="dataSync"`, mais son rôle réel (voir
`HassanNotificationService.kt:34-44`) est d'afficher une notification persistante
"En écoute…" en permanence (`startForeground` appelé à chaque `onStartCommand`,
`HassanNotificationService.kt:82-84`) — pas un job de synchronisation de données
borné dans le temps. Sur Android 14 (API 34+), le type `dataSync` FGS est soumis à
une limite de durée d'exécution (~6h cumulées sur 24h glissantes) imposée par le
système, après quoi `onTimeout()` est appelé et le FGS est arrêté par l'OS
(`Service.onTimeout(int, int)` introduit en API 34). Ce service n'implémente pas
`onTimeout()` et n'a aucune stratégie de reprise — au-delà de la fenêtre de quota,
la notification "En écoute…" et le service disparaîtront silencieusement sans que
l'app ne le sache ni ne relance quoi que ce soit. De plus, le polling SSE réel est
désactivé (commentaire `HassanNotificationService.kt:95-96` : "L'endpoint
/api/sessions/{id}/stream n'existe pas sur ce Hermes... Le polling est désactivé"),
donc ce FGS `dataSync` ne fait aujourd'hui *aucun* travail de synchronisation — il
ne sert qu'à garder un `Service` vivant pour dupliquer visuellement la notification
du wake word (commentaire ligne 169 : "Même ID et canal que HassanWakeWordService →
une seule notif visible"). Le type le plus proche de l'usage réel serait
`specialUse` (avec justification) ou repenser l'architecture pour ne pas maintenir
un FGS actif sans travail associé. Priorité : Bloquant. Justification : sur API 34+,
Google impose ces limites précisément pour empêcher les FGS "permanents" sans
travail réel ; ce service est un candidat direct au kill système et/ou au rejet en
review Play Store (mismatch déclaré/réel du type de FGS est un motif de rejet
documenté). À vérifier manuellement : le comportement exact du timeout dataSync en
conditions réelles longue durée (le quota et son déclenchement dépendent de l'état
d'usage de l'app — foreground/background/exempté).

**[app/src/main/java/com/hasan/v1/MainActivity.kt:120,124]** `MainActivity.onCreate()`
appelle `startForegroundService(...)` pour `HassanWakeWordService` (ligne 120, sous
condition `wakeWordEnabled`) puis pour `HassanNotificationService` (ligne 124, sans
condition). Sur Android 8+, `startForegroundService()` impose que le service appelle
`startForeground()` dans les ~5 secondes sous peine de `ANR`
(`RemoteServiceException` / crash "Context.startForegroundService() did not then
call Service.startForeground()"). `HassanWakeWordService.onCreate()`
(`HassanWakeWordService.kt:100-119`) appelle bien `startForeground()` en tout début
de `onCreate()` (ligne 104-112) — conforme. Idem pour `HassanNotificationService`
(`HassanNotificationService.kt:82`) — conforme aussi, mais seulement dans
`onStartCommand`, pas `onCreate` ; le délai entre `startForegroundService()` (appel
Activity) et l'exécution effective de `onStartCommand()` dépend du scheduler, ce
qui est généralement acceptable mais plus fragile que `onCreate()`. Point non
bloquant en soi mais couplé au premier finding : si le FGS `dataSync` est refusé/tué
par le système sur API 34+ à cause du mismatch de type (déclenchement immédiat
possible si l'OS détecte un abus de type dès le démarrage, pas seulement après le
quota horaire), `startForegroundService()` sans fallback expose l'app au crash
`ForegroundServiceStartNotAllowedException` (API 31+) si les conditions de démarrage
BG ne sont pas respectées. Priorité : Bloquant (dépendant du premier finding).
Justification : aucun `try/catch` autour de ces deux `startForegroundService()` —
une exception ici casse `MainActivity.onCreate()` intégralement (crash au
lancement).

## Important

**[app/src/main/AndroidManifest.xml:9-13]** Trois permissions foreground service
déclarées (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`,
`FOREGROUND_SERVICE_DATA_SYNC`) mais seul `HassanWakeWordService` type `microphone`
correspond à un vrai usage micro continu justifiable. Voir finding Bloquant
ci-dessus sur `dataSync` — la permission est présente mais l'usage réel ne
correspond pas au contrat du type déclaré. Priorité : Important. Justification :
conformité déclarative correcte au sens strict (permission présente pour le type
déclaré) mais le couple permission/type ne reflète pas un besoin métier réel,
symptôme du problème de fond.

**[app/src/main/java/com/hasan/v1/HassanWakeWordService.kt:121-145]**
`onStartCommand()` retourne `START_STICKY` par défaut (ligne 144), y compris pour
tous les cas non gérés explicitement (`ACTION_PAUSE`, `ACTION_RESUME`,
`ACTION_SWAP_MODEL`, ou `intent == null` après un redémarrage système). `START_STICKY`
signifie que si le système tue le process pour libérér de la mémoire, il retentera
de recréer le service en rappelant `onStartCommand(null, ...)`. Mais quand
l'utilisateur **swipe l'app hors des tâches récentes** (task removal explicite),
le comportement dépend de `onTaskRemoved()` — **non surchargé** dans
`HassanWakeWordService` ni `HassanNotificationService`. Par défaut sur la plupart
des OEM (notamment Xiaomi/MIUI, Huawei — un commentaire onboarding
`OnboardingActivity.kt` ligne ~179 "Note Huawei" suggère que l'équipe est déjà
consciente de ces divergences constructeur), un swipe des tâches récentes tue le
process complet malgré `START_STICKY`, et le service ne redémarre PAS
automatiquement sur beaucoup de configurations sans `onTaskRemoved()` explicite
relançant le service ou sans être exempté de l'optimisation batterie. Priorité :
Important. Justification : c'est un point de rupture connu et fréquent du wake
word "toujours actif" promis par le produit (assistant vocal en arrière-plan) —
fait vérifiable statiquement (absence de `onTaskRemoved()`), mais le comportement
exact dépend de l'OEM/version — à vérifier manuellement sur le device cible (P30,
selon mémoire projet, EMUI/Huawei — justement le OEM le plus agressif sur le kill
de process).

**[app/src/main/java/com/hasan/v1/HassanWakeWordService.kt:252-257]** Le
`WakeLock` `PARTIAL_WAKE_LOCK` est acquis avec un timeout fixe de 10h
(`10 * 60 * 60 * 1_000L`, ligne 256) plutôt qu'indéfiniment (`acquire()` sans
argument). C'est une bonne pratique défensive (évite un wakelock oublié qui vide la
batterie indéfiniment), mais cela signifie que **passé 10h d'écoute continue sans
redémarrage du service**, le WakeLock expire silencieusement et le CPU peut
repasser en veille profonde pendant l'écoute wake word, dégradant potentiellement
la réactivité du micro/inférence ONNX en Doze. Aucun mécanisme de renouvellement
(re-acquire) n'est visible dans le fichier. Priorité : Important. Justification :
usage réaliste (assistant vocal "always-on" sur plusieurs jours) dépasse largement
10h ; à vérifier manuellement si le wakelock expiré dégrade effectivement la
détection en conditions réelles longue durée, mais l'absence de renouvellement est
un fait vérifiable statiquement.

**[app/src/main/java/com/hasan/v1/HassanWakeWordService.kt / HassanNotificationService.kt]**
Aucun mécanisme de demande d'exemption "Ignorer les optimisations de batterie"
(`PowerManager.isIgnoringBatteryOptimizations()` /
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) n'a été trouvé dans tout le repo
(recherche exhaustive, 0 résultat). L'app compte donc uniquement sur les deux
foreground services pour survivre à Doze/App Standby, sans filet de sécurité
batterie. Sur des OEM avec gestion agressive du power management (Xiaomi, Huawei,
Oppo — cf. note Huawei déjà présente dans l'onboarding), les FGS eux-mêmes peuvent
être tués malgré leur statut foreground. Priorité : Important. Justification :
absence de code vérifiable statiquement (fait) ; l'impact réel sur la survie du
wake word en Doze prolongé ne peut être confirmé qu'en conditions réelles longue
durée sur le device cible — à vérifier manuellement.

**[app/src/main/java/com/hasan/v1/CapabilityExecutor.kt:344-353]**
`WifiManager.connectionInfo` (ligne 345) est déprécié depuis API 31 (Android 12).
Sur Android 12+, cette API peut retourner un SSID vide/masqué (`<unknown ssid>`)
selon le contexte de localisation et les nouvelles restrictions de confidentialité
Wi-Fi, sans lever d'exception — le code reste fonctionnel mais peut silencieusement
retourner des données incomplètes (SSID vide) sur les versions récentes couvertes
par minSdk 29/targetSdk 35. Le remplacement recommandé (`NetworkCallback` +
`WifiInfo` via `ConnectivityManager.getNetworkCapabilities().transportInfo`) n'est
pas utilisé. Priorité : Important. Justification : API dépréciée mais encore
fonctionnelle (pas un crash), dégradation silencieuse de fonctionnalité (capability
`get_network_info` côté MCP) plutôt qu'un blocage — fait vérifiable statiquement,
l'impact réel (SSID vide en pratique) dépend de la version Android et des
paramètres de localisation du device, à vérifier manuellement.

**[app/src/main/java/com/hasan/v1/OnboardingActivity.kt et QrScannerActivity.kt]**
Aucune gestion d'edge-to-edge (`hideSystemBars()`, `WindowInsetsController`, ou
`fitsSystemWindows`) dans ces deux Activities — seule `MainActivity.kt:106,129,139,
143-148` applique le mode immersif. Avec `targetSdk 35` (`app/build.gradle.kts:28`),
l'edge-to-edge est forcé pour **toutes** les Activities de l'app, pas seulement
MainActivity. Le layout `app/src/main/res/layout/activity_onboarding.xml:11-20`
place le bouton "Skip" avec `app:layout_constraintTop_toTopOf="parent"` sans aucun
padding/insets pour la status bar — c'est exactement le même pattern qui causait le
bug edge-to-edge déjà corrigé dans MainActivity (cf. mémoire projet
`project_hasan_edge_to_edge_bug.md`). Le bouton Skip risque d'être partiellement ou
totalement sous la status bar système et intercepté par elle, le rendant
inutilisable au premier lancement de l'app (onboarding = tout nouvel utilisateur).
`QrScannerActivity` (layout `activity_qr_scanner.xml`) est moins à risque (contenu
plein écran + hint en bas), mais reste non traité par cohérence. Priorité :
Important. Justification : régression fonctionnelle probable sur écran critique
(premier contact utilisateur), pattern de bug déjà identifié et corrigé ailleurs
dans le même projet mais pas généralisé.

## Mineur

**[app/src/main/AndroidManifest.xml:19]** `VIBRATE` est une permission "normale"
(non runtime, accordée automatiquement à l'installation), donc son usage n'a pas
d'impact utilisateur — mais confirmé consommée réellement par
`ConversationFragment.kt:67-68,519-529` et `LightModeFragment.kt:33,144-147` via
`Vibrator.vibrate(VibrationEffect...)`. Pas de sur-permissioning ici, juste noté
pour traçabilité (déclaration ↔ usage vérifiée).

**[app/src/main/java/com/hasan/v1/HassanWakeWordService.kt:136-142]** `ACTION_STOP`
retourne `START_NOT_STICKY` après `stopForeground` + `stopSelf()` — cohérent et
correct (évite un redémarrage indésirable après arrêt volontaire). Bon pattern,
mentionné pour contraste avec le `START_STICKY` par défaut du cas général (voir
finding Important ci-dessus).

**[app/src/main/java/com/hasan/v1/HassanNotificationService.kt:76-85]** Même
remarque : `ACTION_STOP` → `START_NOT_STICKY` correct, mais le cas par défaut
(ligne 84) retourne `START_STICKY` alors que ce service ne fait plus aucun travail
utile (polling désactiné, cf. finding Bloquant). Un `START_NOT_STICKY` serait plus
cohérent avec l'absence de travail réel, réduirait les redémarrages inutiles par le
système.

**[app/src/main/AndroidManifest.xml:34-39]** Le bloc `<queries>` ajouté récemment
est correctement scopé : une seule déclaration `<intent>` ciblant
`ACTION_MAIN`/`CATEGORY_LAUNCHER`, sans `QUERY_ALL_PACKAGES`. Le commentaire
associé (lignes 26-33) documente clairement le besoin (capabilities MCP
`discover_apps`/`launch_app`). Suffisant pour l'usage déclaré, pas de sur-scope
constaté. Rien à corriger — mentionné pour traçabilité de la revue demandée.

**[app/src/main/java/com/hasan/v1/HassanWakeWordService.kt:237-244 et
HassanNotificationService.kt:123-130]** `isAppInForeground()` est dupliqué à
l'identique dans les deux services, utilisant l'API dépréciée
`ActivityManager.getRunningAppProcesses()` (annotée `@Suppress("DEPRECATION")` dans
les deux cas, dépréciée depuis API 21 mais toujours fonctionnelle). Pas un problème
de compatibilité SDK actif (fonctionne toujours sur API 29-35), mais dette
technique/duplication à noter. Une alternative moderne serait
`ProcessLifecycleOwner` pour détecter le premier plan de l'app.

**[app/build.gradle.kts:27-28]** Écart minSdk 29 / targetSdk 35 large (6 versions
majeures). Cohérent avec le reste de l'audit (edge-to-edge forcé, FGS types
stricts API 34, etc.) — pas un problème en soi mais explique la surface de
compatibilité à couvrir ; toutes les branches "vieille API" du code
(`@Suppress("DEPRECATION")` sur `getLastKnownLocation`, `requestSingleUpdate`,
`getRunningAppProcesses`) doivent rester fonctionnelles jusqu'à API 29 tout en
gérant les restrictions d'API 34+.

## Hors périmètre — à signaler

- **Sécurité applicative** (agent Cyber) : `CapabilityExecutor.kt` n'effectue
  aucune vérification `checkSelfPermission()` avant d'invoquer des APIs sensibles
  (`sendSms` ligne ~91-97, `getContacts` ligne ~261-290, `getLocation` ligne
  ~126-153) — appui direct sur le fait que la permission a été accordée en amont
  sans re-vérification runtime au point d'exécution ; risque de crash
  `SecurityException` si l'utilisateur révoque la permission après coup pendant que
  l'app tourne, et sujet lié au risque déjà documenté dans
  `archive/2026-07-16-bridge-mcp-confirmation-bypass.md` (bypass du dialog de
  confirmation pour capabilities sensibles). À approfondir par l'agent Cyber.
