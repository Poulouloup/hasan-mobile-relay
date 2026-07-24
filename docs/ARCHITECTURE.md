# Architecture

Documentation narrative des pipelines et features du projet — complète le
`README.md` (vue d'ensemble, install) et `graphify-out/GRAPH_REPORT.md`
(structure du code générée automatiquement). Mise à jour à chaque nouvelle
feature (voir `.claude/CLAUDE.md`).

## Sommaire

- [Pipeline vocal](#pipeline-vocal)
- [Bridge & capabilities](#bridge--capabilities)
- [Connexions (relay + hermes-webui)](#connexions-relay--hermes-webui)
- [Pairing](#pairing)
- [Serveur relay + plugin hasan_delivery](#serveur-relay--plugin-hasan_delivery)
- [Kanban](#kanban)
- [Fichiers](#fichiers)

---

## Pipeline vocal

_À documenter : wake word (ONNX local) → STT (Android natif) → Hermes
(SSE/WebSocket) → TTS. Voir MainViewModel.kt, WakeWordPipeline.kt,
HassanWakeWordService.kt._

## Bridge & capabilities

_À documenter : BridgeCommandHandler, Capability.kt/CapabilityExecutor.kt,
mécanisme de confirmation pour les capabilities sensibles (send_sms,
get_location, get_contacts)._

## Connexions (relay + hermes-webui)

_À documenter : ConnectionManager (WebSocket relay), WebUiRestClient
(hermes-webui), les deux mécanismes de session indépendants
(SessionTokenStore / WebUiAuthStore), l'écran Réglages unifié._

## Pairing

_À documenter : format du QR (PairingManager.parseQrContent), flux
QrScannerActivity → PairingManager → stockage EncryptedSharedPreferences._

## Serveur relay + plugin hasan_delivery

_À documenter : server/relay/ (rôle, routes principales), plugin/hasan_delivery/
(canal de messagerie Hermes ↔ relay), voir DEPLOYMENT.md pour l'installation._

## Kanban

L'onglet Kanban (`KanbanFragment.kt`, `KanbanViewModel.kt`, `ui/screens/KanbanScreen.kt`,
`webui/WebUiKanbanClient.kt`) consomme l'API Kanban de **hermes-webui**
(`~/hermes-webui/api/kanban_bridge.py`), pas le relay — même authentification
par cookie de session que les autres écrans webui (Skills, Memory, Tasks).
Cette API existait déjà côté serveur, utilisée jusqu'ici uniquement par le
frontend web statique de hermes-webui ; l'app ne fait qu'ajouter un client de
plus dessus, aucun changement serveur n'a été nécessaire.

**Portée volontairement limitée par des contraintes serveur, pas par choix app :**
- Colonnes fixes et partagées par tous les boards (`triage`, `todo`, `ready`,
  `running`, `blocked`, `done`) — `BOARD_COLUMNS` est une constante Python en
  dur côté serveur, aucun endpoint de gestion de structure de colonnes.
  Créer/renommer une colonne personnalisée n'est pas possible sans modifier
  hermes-webui d'abord.
- Pas de tags sur les tâches — aucun champ, aucun endpoint côté serveur
  (vérifié par lecture exhaustive du code et des tests). Le champ `tenant`
  (texte libre scalaire, pas multi-valeurs) est le seul équivalent partiel
  disponible, non exposé dans ce lot.
- `status="running"` ne peut pas être défini directement par un déplacement
  de carte — réservé au protocole de claim du dispatcher côté serveur
  (rejeté HTTP 400). `WebUiKanbanClient.moveTask` intercepte ce cas en amont
  pour éviter l'aller-retour réseau.
- Pas de drag-and-drop visuel — le déplacement se fait via un menu contextuel
  sur chaque carte (liste des colonnes valides).
- Pas de création de tâche depuis l'app dans ce lot — l'API le permet
  (`POST /api/kanban/tasks`), pas branché côté client pour l'instant.

Un board a plusieurs colonnes, chacune une liste de tâches ; l'app peut aussi
créer de nouveaux boards (`POST /api/kanban/boards`, idempotent sur le slug).

## Fichiers

L'écran Fichiers (`FilesFragment.kt`, `FilesViewModel.kt`,
`ui/screens/FilesScreen.kt`, `webui/WebUiWorkspaceClient.kt`) parcourt et
télécharge le **workspace** hermes-webui — le répertoire où l'agent écrit ses
fichiers via `write_file`/`execute_code`/etc. Consomme
`GET /api/list?session_id=X&path=Y` (contenu d'un répertoire) et
`GET /api/file/raw?session_id=X&path=Y&download=1` (téléchargement), tous
deux déjà utilisés par le frontend web statique de hermes-webui, jamais par
l'app avant cette feature — aucun changement serveur nécessaire pour la
lecture.

**Pas un espace isolé par conversation, malgré le paramètre `session_id`
requis par ces deux endpoints.** Dans la config par défaut de hermes-webui,
`Session.workspace` (attribut individuel côté serveur, techniquement
distinct par session) est initialisé au même `DEFAULT_WORKSPACE` global pour
toute nouvelle session, tant qu'un workspace différent n'a pas été choisi
explicitement — vérifié sur le VPS : 11 sessions réelles, toutes avec
`"workspace": "/home/loup_devernay/workspace"` identique. En pratique,
l'écran Fichiers affiche donc le même contenu quelle que soit la session
active — un vrai cloisonnement par session nécessiterait un chantier serveur
séparé (workspace par défaut dérivé du `session_id`), hors scope de cette
feature. D'où l'écran nommé "Fichiers" et non "Fichiers de session" dans
l'UI.

Ouvert depuis un bouton flottant en haut à droite de `ChatScreen` (même
cadre visuel — `CutCornerIconButton` + `HasanDimens.TouchTarget` — que le
bouton pièce jointe de la zone de saisie), pas un onglet du drawer : usage
occasionnel, même traitement que l'écran Logs (`MainActivity.openFiles()`/
`closeFiles()`, calqué sur `openLogs()`/`closeLogs()`). La liste se
rafraîchit automatiquement à chaque ouverture de l'écran (pas de refresh
temps réel pendant que le chat tourne en arrière-plan).

Le téléchargement passe par le client HTTP interne de l'app
(`WebUiRestClient.downloadFile`, TOFU + cookie déjà configurés) plutôt que
par un navigateur externe : le serveur est en TLS auto-signé TOFU
(`CertPinStore.kt`), qu'un navigateur externe ne connaît pas — un
`Intent.ACTION_VIEW` direct sur l'URL distante échouerait silencieusement au
handshake TLS. Le fichier est donc rapatrié dans `cacheDir/shared/`, puis
ouvert localement via `FileProvider` (authority `com.hasan.v1.fileprovider`).

**Remplace `share_file`/`attachments-out`** (outil agent + endpoint dédiés,
introduits puis retirés le lendemain — voir
`archive/2026-07-21-hermes-hallucination-attachments-out.md`). Cette
approche antérieure encodait un lien de téléchargement dans le texte Markdown
de la réponse ; le rendre cliquable s'est heurté à un `combinedClickable`
Compose sur la bulle de chat qui intercepte tout tap simple avant qu'il
n'atteigne le lien. L'onglet Fichiers évite ce problème structurellement : un
simple `write_file` de l'agent suffit à rendre un fichier visible/téléchargeable,
sans dépendre d'un lien caché dans le texte ni d'un outil dédié à appeler
explicitement.
