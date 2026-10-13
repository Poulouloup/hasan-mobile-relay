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

L'app maintient deux connexions serveur indépendantes, chacune avec son
propre mécanisme de session — aucun partage d'état entre les deux :

- **Relay (bridge)** — `ConnectionManager` (`network/`) tient une unique
  connexion WebSocket persistante vers le relay server, multiplexée par
  canal (`chat`/`bridge`/`system`/`proactive`) via `ChannelMultiplexer`.
  Session identifiée par un `session_token` + `refresh_token` stockés dans
  `SessionTokenStore` (`EncryptedSharedPreferences`), obtenus une fois via
  pairing (voir section suivante).
- **Chat (hermes-webui)** — `WebUiRestClient` (`webui/`) parle en HTTP/SSE
  classique (pas de WebSocket) à hermes-webui : login par mot de passe
  (`POST /api/auth/login`), cookie de session stocké par `WebUiAuthStore`.

### TLS — vérification standard, un seul code

Tous les clients réseau de l'app (`ConnectionManager`, `PairingManager`,
`WebUiRestClient` et les clients webui qui réutilisent son `httpClient`,
`HasanFirebaseMessagingService`) sont construits par
`SecureHttp.newClientBuilder()` (`network/SecureHttp.kt`), qui ne configure
**rien** côté TLS : c'est la vérification par défaut d'Android/OkHttp qui
s'applique — chaîne validée contre les autorités du système, nom d'hôte
contrôlé. Conséquences :

- Le serveur doit être servi sous un **nom de domaine** avec un certificat
  reconnu par Android (Let's Encrypt sur le homelab). Une adresse IP nue ou
  un certificat auto-signé (`tls internal` de Caddy) est refusé.
- Rien n'est enregistré ni comparé d'une connexion à l'autre : le
  renouvellement du certificat est invisible.
- Aucune invite de confiance. Un échec passe par
  `SecureHttp.describeFailure()` qui produit le message affiché (Réglages →
  Connexions, et journal d'activité) : certificat non reconnu, nom d'hôte qui
  ne correspond pas, ou serveur injoignable — ce dernier cas suggère de
  vérifier que Tailscale est actif, le serveur n'étant joignable que depuis
  le réseau de la maison ou par Tailscale.

Ce mode remplace l'ancien « certificat auto-signé + confiance au premier
usage + épinglage » (`CertPinStore`, écran « Certificats de confiance »),
supprimé sans exception. Les empreintes qu'il avait enregistrées sont
effacées au premier lancement après mise à jour
(`SettingsManager.purgeLegacyCertPins`). `SecureHttpSourceGuardTest` échoue
si un client est construit hors de `SecureHttp` ou si un gestionnaire de
confiance / vérificateur de nom d'hôte maison réapparaît.

Les deux sont exposées côté écran Réglages dans un seul panneau de statut
unifié (`ConnectionStatusPanel`, `SettingsScreen.kt`), avec un bouton
"Se connecter" qui gère les deux connexions en un seul geste
(`SettingsFragment.connectToWebUi()` : appaire le relay si nécessaire
avant de tenter le login webui) — mais elles restent des systèmes
d'authentification totalement indépendants côté implémentation.

### Topologie serveur

Côté serveur, quatre ports distincts, généralement co-hébergés sur le
même VPS :

| Port | Service | Déploiement |
|---|---|---|
| 8767 | relay server (`server/relay/`) | Docker (`network_mode: host`) via `server/install.sh` |
| 8787 | hermes-webui (chat) | **selon le mode Hermès** (voir ci-dessous) |
| 9119 | dashboard interne Hermes | fait partie de l'installation Hermès elle-même |
| 443 / 8443 | Caddy (TLS) | Docker, route `:443`→8787 et `:8443` (opt-in)→9119 |

Le relay et Caddy sont les deux seuls composants que ce repo installe et
possède entièrement. `server/install.sh` et `server/Caddyfile.template` sont
la source de vérité — voir aussi
`plugin/hasan_delivery/skills/hasan-bridge-diagnosis/SKILL.md` (skill
Hermès de diagnostic, décrit la même topologie côté agent).

### Installateur adaptatif — Hermès natif ou conteneurisé

`server/install.sh` détecte comment Hermès tourne et s'adapte. Le plugin
atterrit dans le dossier que Hermès scanne (`plugins/`) dans les deux cas —
seule la manière d'y arriver change. Structuré en deux bibliothèques
sourcées : `lib/detect.sh` (lecture seule, sans effet de bord) et
`lib/actions.sh` (écritures), plus `lib/test-detect.sh` qui affiche le
diagnostic sans rien modifier.

**Garde-fous** : refuse de s'exécuter dans un conteneur (`/.dockerenv` —
sinon Docker-dans-Docker) ; réutilise tout Caddy tiers déjà sur `:443` au
lieu d'en lancer un second qui crash-looperait ; ne remplace jamais
l'installation de Hermès.

- **Hermès natif** — hermes-webui tourne à côté dans le venv de Hermès
  (installé si absent : clone `nesquena/hermes-webui@master`). Le plugin est
  copié dans `~/.hermes/plugins/`, `httpx` installé dans le venv, Hermès
  redémarré via `systemctl --user`.

- **Hermès conteneurisé** — hermes-webui **ne peut pas** vivre à part : il
  importe le code interne de Hermès (`agent.*`, `hermes_cli.*`, >100 imports).
  Il est donc **embarqué dans une image dérivée** (`server/hermes-image/`,
  `FROM nousresearch/hermes-agent` + clone webui), supervisé comme service s6
  aux côtés de `main-hermes` et `dashboard`. Ce n'est pas un fork : webui est
  cloné depuis l'amont, jamais modifié. Le plugin est déposé dans le volume
  `/opt/data/plugins/` via `docker cp` (le volume appartient à l'uid 10000 du
  conteneur, une copie hôte directe n'y a pas accès), puis `httpx` est déjà
  présent dans l'image — rien à installer. Si l'image officielle **nue** est
  utilisée (sans webui), l'installateur **refuse** et demande de reconstruire
  avec l'image dérivée : webui ne peut pas être ajouté après coup.

## Pairing

Le pairing est une opération ponctuelle : un code (ou son équivalent QR)
généré côté serveur (`POST /pairing/create`, protégé par
`RELAY_ADMIN_TOKEN` — désormais généré automatiquement par
`install.sh`, plus besoin d'édition manuelle du fichier de service)
échangé contre un `session_token`/`refresh_token` de longue durée.

Format du QR — JSON brut, `{"relay_url", "code", "webui_url"?,
"webui_password"?}` (les deux derniers champs optionnels, présents
seulement si hermes-webui est branché côté serveur) :

1. `QrScannerActivity` scanne le QR, extrait le texte brut.
2. `PairingManager.parseQrContent()` parse le JSON en `QrPairingPayload`.
3. `PairingManager.pair()` échange `(relay_url, code)` contre un
   `session_token` via `POST /pairing/register` — un pairing manuel
   (Réglages → Configuration manuelle) fait le même appel sans passer par
   le QR.
4. En cas de succès, si le QR portait aussi `webui_url`/`webui_password`,
   `MainViewModel.pairFromQr()` enchaîne automatiquement un login
   hermes-webui avec ces valeurs — un seul scan configure les deux
   connexions.
5. Le résultat (`session_token`, `refresh_token`) est persisté dans
   `EncryptedSharedPreferences` via `SessionTokenStore` — aucune saisie
   manuelle d'URL/token nécessaire ensuite, et le code de pairing
   lui-même est à usage unique (consommé côté serveur dès la requête).

## Serveur relay + plugin hasan_delivery

`server/relay/` est un serveur aiohttp autonome (`server.py`) qui fait le pont
entre l'app Android et l'agent Hermes via WebSocket (`/ws`) et quelques routes
HTTP. Deux usages distincts côté Hermes, gérés par des mécanismes séparés :

- **Messagerie** (`plugin/hasan_delivery/adapter.py`, `ctx.register_platform`) :
  Hermes envoie des messages proactifs au téléphone (canal `chat`) et reçoit
  les réponses de l'utilisateur (long-poll `GET /phone/replies`).
- **Capabilities téléphone** (`plugin/hasan_delivery/tools.py`,
  `ctx.register_tool`) : Hermes peut appeler des actions sur le téléphone
  (SMS, batterie, localisation, etc.) via `POST /bridge/command` (canal
  `bridge`), avec confirmation utilisateur gérée entièrement côté app
  (`BridgeCommandHandler.kt`).

### Découverte dynamique des capabilities

Les schémas des capabilities (nom, description, paramètres) ne sont **pas**
codés en dur côté Python — ils sont annoncés par l'app et récupérés
dynamiquement par le plugin, pour éviter une 3ᵉ copie à synchroniser en plus
de `Capability.kt` et `CapabilityExecutor.kt` :

1. À chaque (re)connexion WebSocket, l'app envoie un envelope
   `{channel: "system", type: "capabilities"}` listant les capabilities
   activées par l'utilisateur ET dont la permission Android est accordée
   (`ConnectionManager.kt`, `CapabilitySchema.kt::capabilitiesAnnouncementJson`).
2. Le relay persiste cette liste par device dans `Session.capabilities`
   (`server/relay/pairing.py`), exposée en lecture via `GET /capabilities`
   (auth Bearer par session_token).
3. Au démarrage du gateway Hermes, `plugin/hasan_delivery/tools.py` appelle
   `GET /capabilities` et enregistre chaque capability comme un tool natif
   via `ctx.register_tool()` (toolset `hasan_phone`).

Conséquence pratique : ajouter une capability dans `Capability.kt` suffit,
aucune édition de `tools.py` n'est nécessaire — seul un `hermes gateway
restart` est requis pour que Hermes voie le changement (les tools sont
enregistrés une seule fois, au chargement du plugin, pas de rafraîchissement
à chaud).

**Point d'implémentation important** : `tools.py` doit appeler
`ctx.register_tool()` (méthode du `PluginContext` passé à `register(ctx)`),
et non `tools.registry.registry.register()` directement — les deux
enregistrent bien le tool dans le registre global (invocable dans les deux
cas), mais seul `ctx.register_tool()` alimente aussi la liste d'attribution
interne de Hermes (`PluginManager._plugin_tool_names`) que
`get_plugin_toolsets()`/`hermes tools enable` utilisent pour reconnaître le
toolset. Un appel direct au registre produit un tool fonctionnel mais un
toolset invisible ("Unknown toolset") pour tout ce qui passe par cette liste.

Ce mécanisme remplace un ancien serveur MCP externe
(`~/.hermes/phone-relay-mcp/server.js`, Node.js, désormais retiré) qui
exposait les mêmes capabilities via le protocole MCP plutôt que le registre
natif de Hermes — voir CHANGELOG.md pour le contexte de cette migration.

### Réveil FCM des notifications proactives (optionnel)

Le canal `proactive` (messagerie, voir ci-dessus) repose par défaut
uniquement sur le WebSocket — qui ne survit pas de façon fiable quand l'app
est en arrière-plan/fermée (pas de foreground service dédié, Doze mode peut
couper la connexion). Firebase Cloud Messaging (FCM) comble ce trou comme
**accélérateur optionnel**, jamais un prérequis :

1. `POST /phone/message` (Hermes → relay) : si le device n'a pas de WS actif,
   le message est bufferisé (`PushBuffer`, comme aujourd'hui) **et** un push
   FCM **data-only** est envoyé (`server.py::_send_fcm_wake`, via
   `firebase-admin`) — payload strictement `{"type": "wake"}`, jamais le
   texte du message ni le device_hash.
2. Le téléphone (app tuée ou en veille profonde) reçoit ce signal — Android
   réveille brièvement `HasanFirebaseMessagingService.onMessageReceived()`,
   sans lancer `MainActivity` ni allumer l'écran.
3. Ce service ignore tout contenu venant du payload FCM lui-même (défense en
   profondeur) et appelle `GET /phone/pending` sur le relay — canal HTTP
   privé TLS, distinct de Google — pour récupérer le vrai texte et vider le
   push buffer (`drain`, pas `peek` : un second appel immédiat renvoie une
   liste vide).
4. La notification Android réelle est affichée via `ProactiveNotifier.show()`
   (extrait de `ProactiveMessageHandler`, réutilisé par les deux chemins WS
   et FCM pour ne pas dupliquer la logique d'affichage).

**Contrat de vie privée** : Google ne voit jamais que "un réveil a été
envoyé à ce token, à cette heure" — jamais le contenu de la notification.
Le token FCM lui-même est transmis au relay via `POST /fcm-token`
(`Session.fcm_token` côté `pairing.py`), synchronisé à trois moments côté
app : juste après un pairing réussi (`PairingManager.kt`), à chaque rotation
du token (`HasanFirebaseMessagingService.onNewToken()`), et en filet de
sécurité à chaque connexion WS réussie (`ConnectionManager.kt::onOpen`).

**Dégradation gracieuse** : si `RELAY_FCM_CREDENTIALS_PATH` n'est pas
configuré côté serveur (ou si l'app n'a jamais transmis de token FCM), le
comportement reste strictement celui d'avant — WebSocket + push buffer,
sans réveil, notification perdue si l'app reste fermée trop longtemps.
Aucune des deux parties (app ou serveur) n'exige la configuration FCM de
l'autre pour fonctionner.

Voir DEPLOYMENT.md pour l'installation du relay et du plugin.

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
(`WebUiRestClient.downloadFile`, cookie de session déjà configuré) plutôt que
par un navigateur externe : l'endpoint exige le cookie `hermes_session`, qu'un
navigateur externe n'a pas — un `Intent.ACTION_VIEW` direct sur l'URL distante
tomberait sur la page de login. Le fichier est donc rapatrié dans `cacheDir/shared/`, puis
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
