# Synthèse — Audit multi-agents Hasan (2026-07-20)

9 agents en lecture seule (UI, UX, Kotlin, Compose, Protocole, Android/Plateforme,
GitHub/Maintenance, Sécurité, Feature Gaps). Aucune correction appliquée. Rapports
individuels complets dans `reports/audit-v2/*.md`.

---

## 1. Bloquant — tous agents confondus

| # | Fichier | Agent | Constat |
|---|---|---|---|
| B1 | `HassanNotificationService.kt:51-67` | Sécurité (+ noté Kotlin) | `X509TrustManager` nommé `trustAll` — `checkServerTrusted()` vide, `hostnameVerifier { true }` : accepte **n'importe quel certificat sans vérification**. Dormant (le `startPolling()` qui l'utiliserait est un no-op), mais le `httpClient` `by lazy` est déjà construit et prêt à servir. Toute réintroduction future du polling exposerait à un MITM silencieux, sans aucune UI de détection. |
| B2 | `SettingsFragment.kt:365-382` (émission : `MainViewModel.kt:1363-1384`) | UX | Les 4 variantes d'échec de pairing (`InvalidQrContent`, `ServerRejected`, `NetworkError`, etc.) sont écrites dans `UiState.errorMessage`, mais l'écran Réglages ne lit jamais ce champ — seul `ConversationFragment` l'affiche, et seulement si une conversation est déjà chargée. Un QR expiré ou un code manuel invalide saisi depuis Réglages ne produit **aucun** feedback visible. |
| B3 | `QrScannerActivity.kt:41-42,92-100` + `MainActivity.kt:75-82` | UX | Permission caméra refusée ou échec de binding CameraX → `finish()` sans `setResult()` ; le launcher côté `MainActivity` ne traite que `RESULT_OK`. Retour totalement silencieux — l'utilisateur revient sur Réglages sans savoir pourquoi. |
| B4 | `AndroidManifest.xml:78-81` + `HassanNotificationService.kt:34-44,82-84` | Android/Plateforme | `foregroundServiceType="dataSync"` déclaré, mais le service ne fait plus aucun travail de sync réel (`startPolling()` est un no-op) — il ne sert qu'à dupliquer visuellement la notification du wake word. Sur API 34+, le quota `dataSync` (~6h/24h) tuera ce service sans que l'app ne le sache ; mismatch déclaré/réel = motif de rejet Play Store documenté. |
| B5 | `MainActivity.kt:120,124` | Android/Plateforme | Deux `startForegroundService()` (wake word + notification) sans aucun `try/catch`. Une `ForegroundServiceStartNotAllowedException` (API 31+) ou une `RemoteServiceException` casserait `onCreate()` en entier — crash au lancement. Couplé à B4 : si le système refuse le FGS `dataSync` mal typé, ce chemin devient le déclencheur réel. |
| B6 | `WebUiClarifyStream.kt:32-96` + `WebUiApprovalStream.kt:31-93` | Protocole (+ écho Sécurité/UX sur le sujet confirmation) | Aucune reconnexion sur ces 2 flux SSE longue durée. Toute coupure réseau termine le `Flow` définitivement ; `clarifyJob`/`approvalJob` ne sont relancés que sur changement de session explicite, jamais sur reprise réseau. **Une demande d'approbation de commande sensible peut ne plus jamais atteindre l'utilisateur après un simple blip réseau.** |
| B7 | `WebUiAuthStore.kt:43-47` (recherche exhaustive : 0 appelant) | Protocole | `clear()` — censée s'exécuter sur un 401 serveur confirmé — n'est appelée **nulle part**. Aucun client REST/SSE webui ne teste `response.code == 401` spécifiquement. Une session hermes-webui expirée fait échouer tout le chat en boucle silencieuse, **pendant que l'UI continue d'afficher "connecté"** (l'indicateur ne teste que la présence locale du cookie, pas sa validité serveur). |

**Total : 7 findings Bloquant, répartis sur 4 agents (Sécurité, UX, Android/Plateforme, Protocole).**

---

## 2. Important — groupés par thème

### Thème A — Feedback d'erreur manquant côté UI (état câblé, jamais rendu)
*Sources : UX, Feature Gaps*
- `SkillsScreen.kt`/`MemoryScreen.kt` déclarent un `errorMessage` dans leur `UiState` mais ne le rendent jamais (UX) — `TasksScreen.kt` fait ça bien, sert de référence.
- Recoupe directement B2/B3 ci-dessus : c'est un **pattern répété** dans ce projet — l'état d'erreur est correctement modélisé côté logique mais la route d'affichage est incomplète ou absente. Ce n'est pas un cas isolé, c'est une classe de bug entière à traiter d'un coup plutôt qu'écran par écran.

### Thème B — Reconnexion réseau incomplète / pertes silencieuses
*Sources : Protocole (dominant), Sécurité (rate limiting), UX (mentionne le lien avec confirmation bypass)*
- `WebUiChatStream.kt` (chat lui-même) : pas de reconnexion non plus, mais un filet de sécurité existe déjà (message "Connexion interrompue" + bouton Réessayer) — moins grave que B6 car pas silencieux, juste perte des tokens déjà reçus.
- `ChannelMultiplexer.kt:29-36` : canal inconnu ignoré sans log — inoffensif aujourd'hui (garde-fou redondant avec `Envelope.fromJsonObject`) mais fragile si `Envelope.CHANNELS` change un jour sans mise à jour correspondante.
- `Envelope.kt:47` : mismatch de version protocole rejeté silencieusement, sans signal UI — un déploiement serveur qui bump la version avant l'app romprait toute communication WS sans erreur actionnable.
- `ConnectionManager.kt:212-216` : pas de buffer pour les envois échoués pendant une reconnexion — une réponse `BridgeCommandHandler` (résultat d'une capability déjà exécutée avec succès côté téléphone) peut être perdue si le WS n'est pas connecté au moment de répondre.
- Pas de rate limiting/backoff côté app sur les tentatives de pairing (Sécurité) — contraste avec le vrai backoff exponentiel déjà en place pour la reconnexion WS.

### Thème C — Survie en arrière-plan / Doze / OEM agressifs
*Source : Android/Plateforme*
- Absence de `onTaskRemoved()` sur les deux services — combiné à `START_STICKY` par défaut, le comportement après swipe des tâches récentes est incertain sur OEM agressifs (Huawei/Xiaomi, déjà noté dans le code d'onboarding du projet lui-même).
- WakeLock à timeout fixe 10h sans renouvellement — un usage réaliste "assistant always-on" dépasse largement cette fenêtre.
- Aucun mécanisme de demande d'exemption batterie (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) nulle part dans le repo — l'app compte uniquement sur les FGS pour survivre à Doze.

### Thème D — Edge-to-edge non généralisé
*Source : Android/Plateforme*
- `OnboardingActivity`/`QrScannerActivity` n'ont aucune gestion edge-to-edge alors que `targetSdk 35` la force partout. Le bouton "Skip" de l'onboarding est positionné sans insets pour la status bar — **c'est exactement le même bug déjà identifié et corrigé dans `MainActivity`** (mémoire projet `project_hasan_edge_to_edge_bug.md`), mais jamais généralisé aux autres Activities. Régression probable sur l'écran de premier contact utilisateur.

### Thème E — TTS non streamé en premier plan (écart fonctionnel majeur, pas un bug classique)
*Source : Feature Gaps*
- Le TTS incrémental phrase-par-phrase est **implémenté et actif** dans le pipeline arrière-plan (`WakeWordPipeline.kt`), mais le code strictement identique dans `MainViewModel.kt` (premier plan — chat, Light Mode) **n'est jamais appelé**. Résultat : Hasan reste muet pendant toute la génération quand l'utilisateur est devant l'app, puis débite la réponse d'un coup — alors que le même mécanisme fonctionne déjà correctement ailleurs dans le même repo. Pas une limitation d'architecture : le code existe, il suffit de le câbler.
- À noter : le README documente ce comportement (TTS après streaming complet) comme un choix assumé — mais l'agent Feature Gaps a trouvé la preuve que ce n'était pas voulu comme ça partout, juste incohérent entre les deux pipelines. **Contradiction potentielle entre l'intention documentée et l'état réel du code — à trancher (section 4).**

### Thème F — Capacité proactive non fonctionnelle malgré l'infrastructure
*Source : Feature Gaps*
- `ProactiveMessageHandler.kt` est entièrement écrit (notification Android dédiée, canal, tap handler) mais **jamais instancié** — grep exhaustif confirmé. Le canal `proactive` du WS est bien reçu côté `MainViewModel` mais seulement pour écrire une ligne dans le journal d'activité (`Logs`), invisible tant que l'utilisateur ne va pas le consulter explicitement.
- Aucune logique de rappel/tâche programmée dans le repo — la "proactivité" dépend entièrement d'un cron externe non présent ici (`plugin/hasan_delivery/adapter.py` est un transport générique, pas une décision produit).

### Thème G — State hoisting / recomposition Settings
*Source : Compose*
- `SettingsUiState` (~25 champs) est reconstruit comme objet neuf à chaque frappe dans n'importe quel champ texte de Réglages — recompose potentiellement tout l'écran à chaque caractère tapé. Pattern statiquement confirmé ; impact réel (perceptible ou non) à vérifier au profiler.
- `ConversationFragment.observeMessages()` : double pipeline d'écriture vers `messagesState` (flow Room + recalcul sur changement de `thinkingMessage`), potentiellement plusieurs requêtes Room par seconde pendant un streaming actif.

### Thème H — Duplication de composants UI
*Source : UI (+ écho Kotlin sur la duplication SSE, dans un thème différent)*
- `CutCornerOutlineButton`/`CutCornerFilledButton` définis dans `SettingsScreen.kt` (un écran) mais réutilisés par 3 autres fichiers — inverse la direction de dépendance attendue components→screens.
- Pattern "pill sélectionnable" réimplémenté indépendamment à 3 endroits (`MemoryScreen`, `SettingsScreen`, `FreeHandScreen`) sans jamais être factorisé dans `ui/components/`.
- `TaskEditorScreen.kt` est le seul écran de saisie à utiliser le style Material "filled" au lieu du style contour+coin coupé partagé partout ailleurs — rupture visible dans un formulaire plein écran.

### Thème I — Documentation périmée (README/SETUP/commentaires de code)
*Source : GitHub/Maintenance (+ écho Protocole sur un commentaire spécifique)*
- `README.md` décrit encore l'architecture chat comme reposant sur le WS relay, alors que la branche `webui-migration` a basculé vers REST/SSE hermes-webui depuis 10+ commits.
- `SETUP.md` documente l'ancien serveur de simulation et la config manuelle URL/token, alors que le flux réel est QR pairing + connexion hermes-webui.
- `ChatStreamHandler.kt:24-26` (Protocole) : le doc-comment en tête de fichier affirme être "seul transport" — faux, c'est un vestige jamais appelé en production. Même symptôme que le README : la doc n'a pas suivi la migration.

### Thème J — Autres Important isolés (un seul agent, pas de recoupement)
- `WifiManager.connectionInfo` déprécié API 31, peut retourner un SSID vide silencieusement (Android/Plateforme).
- Dépendance `androidx.security:security-crypto:1.1.0-alpha06` (version alpha) sur le chemin critique de sécurité — stockage des tokens (GitHub/Maintenance).
- Aucune CI dans le repo malgré une suite de tests pytest existante côté serveur (GitHub/Maintenance).
- `hostnameVerifier { true }` dans les 3 clients HTTP légitimes (relay, pairing, webui) — défendable car compensé par le TOFU fingerprint, mais rien ne le distingue visuellement du vrai `trustAll` dangereux de B1 (Sécurité).
- `CertPinStore.kt:46` : chaîne de certificats vide → retourne silencieusement au lieu de fail-closed (Sécurité).
- `get_location` (`CapabilityExecutor.kt:156-179`) : nettoyage des `LocationListener` semble correct après relecture attentive, mais assez neuf pour mériter un test manuel en conditions réelles (Kotlin).
- `SettingsFragment.kt:287-336` : plusieurs `lifecycleScope.launch` (scope Fragment, pas `viewLifecycleOwner`) écrivent dans l'état Compose — incohérent avec le pattern correct utilisé ailleurs dans le même fichier (Kotlin).
- Mémoire (MEMORY/USER/SOUL) en lecture seule côté mobile alors que l'écriture existe côté serveur — l'utilisateur ne peut pas corriger ce que Hasan "sait" de lui depuis son téléphone (Feature Gaps).

---

## 3. Mineur — liste compacte

- **UI** : `MarkdownText.kt` `textSize` en dur ; ~30 occurrences `.dp`/`.sp` hardcodés dans `ChatScreen.kt` pour des valeurs non couvertes par l'échelle `HasanDimens` ; structure d'overlay dupliquée 3x (`ClarifyOverlay`/`ApprovalOverlay`/`HasanConfirmOverlay`) ; alias couleur redondant dans `SettingsScreen.kt`.
- **UX** : zones tactiles sous 48dp à plusieurs endroits (fermeture Memory, flèche retour Skills, bannière erreur Tasks, boutons message chat, items nav drawer) ; boutons mode mains libres sous 48dp ; pas de vibration sur tap manuel du micro (incohérent avec les transitions automatiques) ; pas d'état "conversation vide" dans le Chat ; mockup HTML non mis à jour (3 onglets vs 6 réels, badge connexion unique vs double).
- **Kotlin** : `!!` cosmétiques sans risque réel (`HassanWakeWordService.kt`, `SkillsFragment.kt`) ; 3 clients SSE dupliquant la même mécanique de parsing (candidat à factoriser) ; granularité d'erreur un peu faible dans `CapabilityExecutor` (une seule forme d'erreur pour 11 capabilities) ; scopes `CoroutineScope` racine jamais explicitement `cancel()` (pas de fuite réelle confirmée, juste hygiène).
- **Compose** : parsing JSON non mémoïsé dans `AssistantBubble` ; Markwon reparse à chaque recomposition même si le texte n'a pas changé ; `postDelayed` sans guard de cycle de vie qui alimente l'état Compose ; `navItems` statique reconstruit à chaque recomposition ; conventions de state local incohérentes entre écrans (`SettingsEditableRow` vs `SkillsScreen`).
- **Protocole** : backoff exponentiel WS bien implémenté (mentionné positivement) ; pas de distinction cause d'échec (transitoire vs permanent) avant reconnexion ; génération d'UUID de secours sur `Envelope.id` manquant pourrait masquer une désynchronisation de corrélation.
- **Android/Plateforme** : duplication de `isAppInForeground()` (API dépréciée `getRunningAppProcesses`) dans 2 services ; `START_STICKY` incohérent sur un service qui ne fait plus rien ; écart minSdk/targetSdk large (6 versions) mais assumé et documenté ; bloc `<queries>` récent bien scopé (point positif).
- **GitHub/Maintenance** : clé API NVD réelle dans `local.properties` (non committée, usage légitime, juste vigilance) ; dépendances légèrement en retard (Kotlin 2.0.21, AGP 8.7.0, Room 2.6.1, Compose BOM 2024.12.01) sans urgence ; branches historiques toutes déjà fusionnées, aucune à nettoyer (juste du bruit cosmétique dans `git branch -a`).
- **Sécurité** : `LatencyLog`/`latency.log` loguent en clair des paramètres de capabilities sensibles (numéros SMS, contacts) — fichier local au sandbox, risque réel seulement si device compromis/USB debug ; usage de MD5 pour dériver une clé de stockage (non sécuritaire mais pas un usage sécuritaire non plus, correct).
- **Feature Gaps** : délai fixe de 1,5s avant réarmement du wake word après une réponse ; notifications actuelles = accusé de réception différé, pas une vraie initiative proactive ; TODO `ACTION_SET_THRESHOLD` jamais implémenté (réglage sensibilité wake word sans effet réel) ; commentaire de doc obsolète dans `HermesSession.kt` référençant l'ancien protocole.

---

## 4. Contradictions entre agents — à arbitrer avec l'utilisateur

1. **TTS "après streaming complet" : choix assumé ou bug ?**
   Le README affirme explicitement que le TTS ne démarre qu'à la fin du streaming — présenté comme un choix de conception documenté. L'agent Feature Gaps a trouvé que ce n'est *pas* uniformément vrai : le pipeline arrière-plan (`WakeWordPipeline.kt`) *streame déjà* le TTS phrase par phrase avec un code quasi identique à celui, présent mais mort, dans `MainViewModel.kt`. **Question à trancher : le README documente-t-il l'intention originale (TTS non-streamé) et le pipeline arrière-plan est l'anomalie à corriger (revenir à un TTS non streamé partout), ou l'inverse (le pipeline arrière-plan a raison, il faut aligner le premier plan dessus et mettre à jour le README) ?** Ce n'est pas une question technique — c'est un choix produit qui détermine le sens de la correction.

2. **`hostnameVerifier { true }` : pattern acceptable ou symptôme d'un défaut culturel ?**
   L'agent Sécurité classe les 3 usages "légitimes" (relay, pairing, webui) en **Important** (pas Bloquant), en acceptant l'argument TOFU fingerprint-based comme compensation suffisante. Mais il note lui-même que rien ne distingue visuellement ces usages "sûrs" du vrai `trustAll` dangereux de `HassanNotificationService` (Bloquant) — un futur développeur lisant le code isolément ne peut pas savoir lequel est sûr. **Pas une vraie contradiction entre agents, mais un point de jugement à valider : est-ce que 3 usages "acceptables mais non documentés comme tels" du même pattern dangereux sont vraiment moins risqués qu'un seul cas isolé, du point de vue de la maintenabilité à long terme ?**

3. **Fusion du ComposeView Chat (récente) : risque confirmé ou non ?**
   Le brief de l'agent Compose demandait explicitement de vérifier si la fusion récente `HasanHeader`+`ChatScreen` en un seul arbre Compose avait introduit une recomposition inutile de la liste de messages. L'agent conclut que **non, structurellement** (states séparés, scoping Compose standard), mais classe quand même le finding en **Important plutôt que de le clore**, uniquement parce que la confirmation finale nécessite un profiling Layout Inspector en conditions réelles de streaming. Ce n'est pas une contradiction avec un autre agent, mais un point où l'agent lui-même hésite entre "pas de problème" et "à vérifier" — à traiter comme **information rassurante nécessitant juste une confirmation légère**, pas comme un vrai Important actionnable.

Aucune contradiction directe agent-vs-agent au sens strict (ex. deux agents jugeant différemment le même fichier) n'a été trouvée dans les 9 rapports — la seule vraie tension est entre le README (intention documentée) et le code réel (Feature Gaps), point 1 ci-dessus.

---

## 5. Recommandation d'ordre de traitement

Pas de correction entamée — ceci est un ordre suggéré pour la session de correction séparée à venir.

**Étape 1 — Bloquants, dans cet ordre :**
1. **B1 (trustAll)** en premier : c'est un fix isolé, mécanique, sans dépendance (supprimer le code mort ou le remplacer par le TOFU existant) — élimine le seul vrai risque de sécurité confirmé du lot en quelques minutes.
2. **B4 + B5 (foreground service)** ensemble, car couplés : redéfinir le rôle réel de `HassanNotificationService` (soit lui donner un vrai type `specialUse` justifié, soit le fusionner/supprimer puisqu'il ne fait plus de travail réel) résout B4, et ajouter un `try/catch` autour des deux `startForegroundService()` (B5) est un filet de sécurité à ajouter dans la foulée.
3. **B2 + B3 (pairing/QR silencieux)** ensemble : les deux sont le même thème (erreur backend correctement modélisée, jamais affichée) — traiter en une passe avec le Thème A (Skills/Memory `errorMessage` non rendu), puisque c'est visiblement un pattern récurrent dans ce projet plutôt que 4 bugs isolés.
4. **B6 + B7 (SSE sans reconnexion / session expirée invisible)** ensemble en dernier parmi les bloquants : plus gros chantier (ajouter une vraie logique de retry/reconnexion aux flux SSE, câbler la détection 401), mais c'est le thème le plus critique du point de vue produit — une approbation de commande sensible qui n'arrive jamais, ou un chat qui semble "connecté" mais ne répond plus, sont les deux pires expériences possibles pour un assistant personnel de confiance.

**Étape 2 — Important, groupé par thème (pas par agent) :**
- Traiter le **Thème A** (feedback d'erreur manquant) en une seule passe transversale sur tous les écrans (Skills, Memory, et vérifier s'il y en a d'autres) plutôt qu'écran par écran — c'est là que le retour sur investissement est le plus élevé pour un effort modéré (la plomberie ViewModel existe déjà dans la plupart des cas).
- **Thème E (TTS non streamé)** seulement après avoir tranché la Contradiction #1 avec l'utilisateur — inutile de coder avant de savoir dans quel sens.
- **Thème D (edge-to-edge)** est rapide à corriger (pattern déjà résolu ailleurs dans le même repo, juste à généraliser) — bon candidat "quick win" à faire tôt.
- **Thème C (survie background/Doze)** et **Thème F (proactivité)** sont les plus gros chantiers d'ingénierie du lot (nouveaux mécanismes, pas des fixes ponctuels) — à planifier comme des tâches à part entière, pas à glisser dans une session de correction générale.
- **Thème G (recomposition Settings)** et **Thème H (duplication composants)** sont de la dette technique pure, sans impact utilisateur immédiat confirmé — à traiter quand le temps le permet, pas prioritaire.
- **Thème I (doc périmée)** : mise à jour texte simple, à faire en une passe courte, n'importe quand.

**Étape 3 — Mineurs :** backlog, à picorer opportunistement (ex. en même temps qu'on touche un fichier pour une autre raison), pas de session dédiée nécessaire.

**Après lecture de cette synthèse :** valider avec moi les 3 points de la section 4 (en particulier la Contradiction #1, qui a un vrai impact produit) avant de démarrer la session de correction.
