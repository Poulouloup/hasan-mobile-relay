# Changelog

Format inspiré de [Keep a Changelog](https://keepachangelog.com/). Une entrée
par fix ou upgrade notable — le "pourquoi", pas juste le "quoi" (le diff git
montre déjà le quoi).

## [Unreleased]

### Fixed
- **2026-10-12 — messagerie du plugin en 401 après un renouvellement de
  session du téléphone.** Le plugin `hasan_delivery` s'authentifiait sur
  `/phone/*` avec une copie du jeton de session du téléphone
  (`HASAN_RELAY_SESSION_TOKEN`). Or le relay remplace ce jeton à chaque
  `POST /pairing/refresh`, que l'app déclenche toute seule : la copie devenait
  invalide sans prévenir, et il fallait aller relire le nouveau jeton sur le
  serveur et redémarrer Hermès. Le plugin utilise désormais le jeton
  d'administration du relay, comme il le faisait déjà pour les outils du
  téléphone ; le relay (0.2.0) l'accepte sur `/phone/message`,
  `/phone/replies` et `/phone/outbound`, et vise le device désigné ou, à
  défaut, le device appairé vu le plus récemment. Effet de bord utile : le
  plugin se connecte avant tout appairage, au lieu de signaler une erreur de
  configuration jusqu'au premier QR scanné. Le jeton de session reste accepté
  (ancien plugin compatible avec le nouveau relay) ; **l'inverse n'est pas
  vrai** : le nouveau plugin exige un relay 0.2.0, à mettre à jour d'abord.
- **2026-10-12 — l'installateur ne trouvait pas un Hermès natif récent**
  (« Hermes introuvable » alors qu'il tournait). Il cherchait
  `hermes-agent/venv/bin/python`, que l'installateur officiel de Hermès ne
  crée plus : l'environnement Python est maintenant dans
  `~/.hermes/installs/<id>/environments/<empreinte>/venv/`, à un chemin qui
  change à chaque `hermes update`. Hermès natif est désormais reconnu à son
  code (`hermes-agent/run_agent.py`) et son Python relu dans
  `installs/<id>/facts.json`. Cet environnement n'ayant pas `pip`, les
  `pip install` (qui auraient échoué) ne sont plus tentés que si `pip` existe
  et qu'un module manque réellement. Constaté en installant à la main sur le
  homelab.
- L'installateur n'activait pas le plugin (`hermes plugins enable
  hasan_delivery`) alors que les plugins tiers sont désactivés par défaut dans
  les Hermès récents, et n'écrivait pas ses variables d'environnement : le
  plugin était copié mais jamais chargé. Il fait maintenant les deux (mode
  natif).
- `RELAY_PUBLIC_URL` était écrit avec le port du relay (`https://<hôte>:8767`),
  une adresse que rien ne sert derrière un reverse proxy : le QR de pairing
  donnait à l'app une adresse injoignable.

### Changed
- **2026-10-12 — l'installateur exige un nom de domaine.** Depuis que l'app
  ne fait plus que de la vérification TLS standard (voir plus bas), le mode
  « adresse IP + `tls internal` » produisait un serveur que l'app refuse. Il
  est retiré : `server/install.sh` demande deux noms de domaine (relay et
  hermes-webui — ils ne peuvent pas partager un nom, chacun servant ses
  chemins depuis la racine) et refuse une adresse IP. `Caddyfile.template`
  n'a plus ni `tls internal` ni `default_sni` : le Caddy de l'installateur
  obtient un certificat Let's Encrypt par nom (ports 80/443 joignables
  d'Internet requis ; sinon, utiliser son propre Caddy avec un challenge DNS).
- **Caddy existant en réseau Docker classique** : cas désormais géré. Un tel
  conteneur ne joint pas `127.0.0.1` de l'hôte, donc les blocs affichés
  jusque-là (`reverse_proxy 127.0.0.1:…`) ne pouvaient pas fonctionner.
  L'installateur attache le relay au réseau de ce Caddy (joint par son nom,
  `hasan-relay:8767`), fait écouter hermes-webui sur la passerelle de ce
  réseau, et affiche les deux blocs correspondants ainsi que la règle de
  pare-feu à poser. Le relay n'est plus en `network_mode: host` : il est
  publié sur `127.0.0.1:8767` de l'hôte.
- **hermes-webui installé à une version épinglée** (`exp-v0.52.459`, vérifiée
  contre son commit) au lieu de la tête de `master`, en natif comme dans
  l'image dérivée — une installation doit donner le même résultat d'un jour à
  l'autre, et webui doit rester aligné sur la version de Hermès. Surchargeable
  par `WEBUI_REF`.
- hermes-webui (mode natif) tourne comme **service utilisateur systemd**
  (`server/webui/`) au lieu de `ctl.sh start` : il redémarre seul et survit
  au redémarrage du serveur. Son script de lancement relit le Python de
  Hermès à chaque démarrage (`HERMES_WEBUI_PYTHON`), faute de quoi webui
  prenait le Python du système et le chat échouait ; il pose aussi
  `HERMES_WEBUI_TRUST_FORWARDED_PROTO=1` pour que le cookie de session soit
  marqué `Secure` derrière le proxy.
- Le relay n'écrit plus `HERMES_API_BASE_URL` : le chat de l'app passe par
  hermes-webui, plus par le canal `chat` du relay.
- **2026-10-12 — TLS : vérification standard d'Android, pour tous les clients.**
  Hasan quitte le VPS (adresse IP nue, certificat auto-signé Caddy
  `tls internal`) pour un homelab servi sous un nom de domaine avec un
  certificat Let's Encrypt. L'ancien modèle n'y a plus de raison d'être et
  était devenu dangereux dans ce contexte : les quatre clients avaient
  `hostnameVerifier { _, _ -> true }`, donc avec un certificat public
  **n'importe quel certificat valide pour n'importe quel domaine** était
  accepté ; et `WebUiRestClient` — qui transporte le mot de passe webui et
  toute la conversation — ne consultait jamais le résultat de la vérification,
  donc acceptait en pratique n'importe quel certificat. Tous les clients
  (`ConnectionManager`, `PairingManager`, `WebUiRestClient`,
  `HasanFirebaseMessagingService`) sortent désormais d'un seul point,
  `SecureHttp.newClientBuilder()`, sans aucune personnalisation TLS : chaîne
  validée par le système **et** nom d'hôte contrôlé. Le renouvellement du
  certificat (tous les 60 jours) est invisible, plus rien n'étant enregistré.
  **Conséquence pour un serveur existant** : une adresse IP nue ou un
  certificat auto-signé n'est plus accepté — il faut un nom de domaine et un
  certificat reconnu.
- Un échec de connexion est maintenant expliqué (`SecureHttp.describeFailure`)
  dans Réglages → Connexions et dans le journal d'activité : certificat non
  reconnu, nom d'hôte qui ne correspond pas, ou serveur injoignable — ce
  dernier cas suggère de vérifier Tailscale, sans condition sur le réseau :
  même sur le Wi-Fi de la maison, le nom du serveur peut se résoudre vers son
  adresse Tailscale. Le badge « Chat » des Réglages n'affiche plus
  « Connecté » en vert quand hermes-webui ne répond pas (il ne regardait que
  la présence du cookie de session). Avant, un échec du WebSocket relay ne se
  voyait que comme une « reconnexion » sans fin.

### Removed
- Exposition optionnelle du dashboard interne de Hermès sur `:8443`
  (`Caddyfile.dashboard-block.template`) : elle reposait sur `tls internal`
  et sur une adresse IP, et l'app ne s'en est jamais servie. Qui veut exposer
  le dashboard ajoute un bloc à son propre Caddy.
- **2026-10-12 — plus aucun trafic en clair.** L'exception `http://` pour
  l'ancien serveur de dev (`10.200.0.2`) est retirée de
  `network_security_config.xml`, et `RelayUrlDeriver`/`WebUiUrlDeriver`
  produisent toujours `https://`/`wss://` — une adresse saisie en `http://`
  est ramenée à `https://` au lieu d'ouvrir un WebSocket `ws://` non chiffré.
  Plus rien ne justifiait un chemin en clair depuis que le serveur a un vrai
  certificat.
- Mode « certificat auto-signé, confiance au premier usage, épinglage »,
  supprimé sans exception (décision du 2026-10-12) : `CertPinStore`, l'écran
  « Gérer les certificats de confiance », les dialogues « Certificat relay non
  reconnu / modifié », `PairingResult.CertificateCheckRequired` et le
  `StreamEvent.CertificateCheck` hérité. Les empreintes déjà enregistrées sur
  un téléphone sont effacées au premier lancement après mise à jour
  (`SettingsManager.purgeLegacyCertPins`) — elles ne servent plus à rien et
  l'écran qui permettait de les supprimer a disparu.

### Added
- `server/lib/selftest.sh` : tests de la logique de l'installateur sans
  machine cible (validation des noms de domaine, détection d'un Hermès natif
  dans une arborescence factice, réseaux Docker simulés, fichiers générés),
  lancés en CI (`installer-tests.yml`). L'installateur n'avait aucun test, et
  c'est une lecture à blanc sur un vrai serveur qui a révélé qu'il ne
  détectait plus Hermès.
- Premiers tests unitaires JVM (`app/src/test/`) : `SecureHttpTest` exerce le
  client de production contre un serveur HTTPS local (certificat reconnu,
  certificat renouvelé, auto-signé refusé, mauvais nom d'hôte refusé, serveur
  injoignable → suggestion Tailscale) ; `SecureHttpSourceGuardTest` échoue si
  un client réseau est construit hors de `SecureHttp` ou si un vérificateur
  TLS maison réapparaît. Dépendances de test seulement : JUnit 4.13.2,
  `mockwebserver` et `okhttp-tls` (même version qu'OkHttp).
- Installateur adaptatif `server/install.sh` qui déploie le bridge selon le
  mode Hermès détecté — **natif ou conteneurisé** — là où l'ancien
  `install-bridge.sh` ne couvrait que le natif. Structuré en `lib/detect.sh`
  (détection sans effet de bord), `lib/actions.sh` (actions d'installation) et
  `lib/test-detect.sh` (diagnostic à blanc, à lancer en premier sur une
  machine cible). Garde-fous : refuse de tourner dans un conteneur
  (`/.dockerenv`, sinon Docker-dans-Docker) ; réutilise tout Caddy tiers déjà
  sur `:443` au lieu d'en lancer un second qui crash-looperait (le vrai risque
  du double-Caddyfile du 2026-07-28) ; tourne sans root si l'utilisateur est
  dans le groupe docker. Validé de bout en bout sur machine de test pour les
  trois cas conteneurisés (image nue → refus guidé, image dérivée sans Caddy,
  image dérivée avec Caddy tiers). Motivé par la migration à venir du VPS vers
  un homelab où Hermès sera conteneurisé.
- Image Hermès dérivée `server/hermes-image/` (`FROM nousresearch/hermes-agent`
  + hermes-webui) pour le mode conteneurisé. hermes-webui n'est pas autonome —
  il importe le code interne de Hermès (`agent.*`, `hermes_cli.*`, >100
  imports) — donc il ne peut ni tourner à part ni être ajouté après coup : il
  est gravé dans l'image comme service s6, supervisé aux côtés de
  `main-hermes` et `dashboard`. Ce n'est pas un fork : webui est cloné depuis
  l'amont (`nesquena/hermes-webui@master`, épinglable via `--build-arg
  WEBUI_REF`), jamais modifié. Le plugin, lui, reste hors image (déposé dans
  le volume, modifiable sans rebuild). Testé : build, réponse HTTP 200,
  relance après crash (< 8 s), survie à un redémarrage complet du conteneur.
- Réveil FCM (Firebase Cloud Messaging) data-only pour les notifications
  proactives — le canal `proactive` existant (WebSocket persistant) ne
  survit pas de façon fiable quand l'app est fermée/en veille (pas de
  foreground service dédié, l'utilisateur a explicitement refusé d'en
  ajouter un second — le service wake word consomme déjà trop de batterie
  avec son WakeLock + inférence ONNX continue). FCM est la seule solution
  Android pour un réveil externe sans rien faire tourner en continu.
  Payload strictement `{"type": "wake"}` — Google ne voit jamais le texte
  du message, seulement un signal opaque ; le contenu réel est récupéré
  ensuite via `GET /phone/pending` (nouveau, HTTP privé TLS vers le relay
  de l'utilisateur). Nouveau champ `Session.fcm_token`
  (`server/relay/pairing.py`), nouvel endpoint `POST /fcm-token`, nouveau
  `HasanFirebaseMessagingService` côté app (réutilise l'affichage de
  notification déjà écrit mais jamais branché pour ce canal — extrait dans
  `ProactiveNotifier` pour être partagé avec le chemin WS existant).
  Entièrement optionnel et dégradé gracieusement : sans
  `RELAY_FCM_CREDENTIALS_PATH` configuré côté serveur, comportement
  identique à avant (WebSocket + push buffer uniquement). Voir
  `docs/ARCHITECTURE.md` pour le flux complet et `SETUP.md`/`DEPLOYMENT.md`
  pour la configuration Firebase.
- `server/install-bridge.sh` — orchestrateur "une commande" pour le
  déploiement serveur du bridge Hasan (relay + Caddy en Docker Compose,
  `network_mode: host`, + plugin `hasan_delivery` côté Hermes existant).
  Wizard interactif unique (IP publique, détection hermes-webui,
  exposition optionnelle du dashboard Hermes), génère `RELAY_ADMIN_TOKEN`
  automatiquement (`hermes-relay.service` n'avait jusqu'ici aucun
  placeholder pour cette variable — édition manuelle requise à chaque
  déploiement). Motivé par les heures perdues le 2026-07-28 sur un VPS de
  dev où deux Caddyfiles divergents (un process manuel `nohup`, un
  service systemd) routaient vers des ports différents, causant des 401
  trompeurs et un ban fail2ban accidentel (voir
  `archive/2026-07-28-retrait-skillclaw-webui-down.md`, non commité).
  `install-relay.sh`/`install-plugin.sh` existants restent utilisables en
  standalone (chemin manuel documenté dans `DEPLOYMENT.md`, non déprécié).
  Nouveaux fichiers versionnés : `server/relay/Dockerfile`,
  `server/docker-compose.yml`, `server/Caddyfile.template` (+
  `Caddyfile.dashboard-block.template` optionnel).
- Skill de diagnostic pour l'agent Hermes,
  `plugin/hasan_delivery/skills/hasan-bridge-diagnosis/SKILL.md` (format
  natif Hermes avec frontmatter YAML, distinct des skills
  `.claude/skills/` de ce repo qui sont pour Claude Code uniquement) —
  documente la topologie à 4 ports (relay/webui/dashboard/Caddy) et le
  piège du double-Caddyfile découvert le 2026-07-28, pour qu'un futur
  diagnostic ne perde pas les mêmes heures. Diagnostic uniquement, ne
  redémarre/n'édite rien lui-même. Copié automatiquement par
  `install-plugin.sh` (modification additive : glob de copie étendu pour
  inclure `skills/hasan-bridge-diagnosis/`).
- Écran Fichiers dans l'app (`FilesFragment`/`FilesViewModel`/`WebUiWorkspaceClient`)
  — parcourir et télécharger le workspace hermes-webui, ouvert via un bouton
  flottant dans le Chat (pas un onglet du drawer, usage occasionnel comme
  Logs). Consomme `GET /api/list` et `GET /api/file/raw`, déjà existants côté
  hermes-webui, jusqu'ici utilisés seulement par le frontend web statique —
  aucun changement serveur nécessaire pour cette partie. Nommé "Fichiers" et
  non "Fichiers de session" : dans la config par défaut de hermes-webui,
  toutes les sessions partagent le même workspace disque, ce n'est pas un
  espace isolé par conversation (voir docs/ARCHITECTURE.md#fichiers).
- Onglet Kanban dans l'app (`KanbanFragment`/`KanbanViewModel`/`WebUiKanbanClient`)
  — consulter les boards, déplacer des cartes entre colonnes, créer des
  boards. Consomme l'API Kanban déjà existante côté hermes-webui
  (`api/kanban_bridge.py`), jusqu'ici utilisée seulement par le frontend web
  statique — aucun changement serveur nécessaire. Tags et colonnes
  personnalisées non supportés : limitation côté serveur, pas un oubli
  (voir docs/ARCHITECTURE.md#kanban).
- `GET /version` sur le relay server (expose version + commit git déployés).
- Packaging du plugin `hasan_delivery` (`requirements.txt`, `README.md`,
  `install-plugin.sh`) — jusqu'ici installé uniquement à la main.
- `DEPLOYMENT.md` — guide d'installation bout-en-bout (relay + plugin +
  pairing) pour un utilisateur qui n'a pas construit le projet depuis les
  sources.
- CI minimal : tests relay (`relay-tests.yml`) et lint plugin (`plugin-lint.yml`).
- 5 nouvelles capabilities bridge (`Capability.kt`/`CapabilityExecutor.kt`) :
  `toggle_flashlight` (lampe torche, `CameraManager.setTorchMode`, permission
  CAMERA déjà présente), `get_calendar_events` (lecture des prochains
  événements, `CalendarContract`, nouvelle permission READ_CALENDAR),
  `get_clipboard`/`set_clipboard` (presse-papier, aucune permission
  runtime), `make_call` (`ACTION_CALL`, nouvelle permission CALL_PHONE,
  même gabarit que `send_sms` déjà existant). Toutes apparaissent
  automatiquement dans Tools & Permissions et déclenchent le dialog de
  confirmation existant pour les capabilities sensibles
  (`get_calendar_events`, `get_clipboard`, `make_call` marquées
  `authRequiredDefault=true`) — aucun code UI supplémentaire nécessaire,
  seul `ALL_CAPABILITIES` a été étendu.
- Découverte dynamique des capabilities téléphone par
  `plugin/hasan_delivery/tools.py` : l'app annonce ses capabilities activées
  (envelope `system/capabilities` à chaque connexion WS,
  `CapabilitySchema.kt::capabilitiesAnnouncementJson`), le relay les
  persiste par device (`server/relay/pairing.py::Session.capabilities`,
  nouvel endpoint `GET /capabilities`), et le plugin les récupère au
  démarrage du gateway Hermes plutôt que de les coder en dur. Motivé par
  `schemaToJson()` (`CapabilitySchema.kt`) qui existait déjà avec un
  commentaire anticipant ce mécanisme mais n'était jamais appelée — et par
  le risque de recréer, avec des schémas Python codés en dur, le même
  problème de synchronisation à 3 endroits (`Capability.kt`,
  `CapabilityExecutor.kt`, `tools.py`) que la migration MCP→plugin natif
  (voir Removed) était censée simplifier. Conséquence : ajouter une
  capability dans `Capability.kt` seul suffit désormais, aucune édition
  côté serveur/plugin nécessaire (voir docs/ARCHITECTURE.md).
- Demande d'exemption de l'optimisation batterie (onboarding + Réglages) —
  absente jusqu'ici, cause la plus fréquente de "le wake word s'arrête
  tout seul" sur MIUI/EMUI/ColorOS/OneUI/OxygenOS (ces OEM tuent le service
  foreground en arrière-plan malgré `START_STICKY`). Remplace l'ancienne
  note statique Huawei sans action réelle par un vrai bouton (`ACTION_
  REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) fonctionnant sur tous les OEM,
  avec statut visible dans Réglages → Wake Word.
- Onboarding : la permission notifications (`POST_NOTIFICATIONS`) est
  désormais demandée en même temps que le micro (une seule invite groupée,
  `ActivityResultContracts.RequestMultiplePermissions`) plutôt que
  découverte plus tard dans le flux — `MainActivity` garde un filet de
  sécurité si l'une des deux est refusée à l'onboarding ou si celui-ci est
  sauté. Drawer : âge relatif affiché à droite de chaque session
  (`TimeFormat.formatRelativeSessionAge`, paliers "-1h"/"+3d"/"+2w"/"+5m"/"+1y")
  pour distinguer les sessions actives des anciennes sans ouvrir chacune ;
  nouveau logo (`hasan_logo_halo.png`) dans le header à la place du glyphe
  vectoriel `ic_hasan_brand_glyph`.

### Removed
- `plugin/tools/android_tool.py` — code mort confirmé : n'a jamais été
  déployé sur le VPS de dev (`find ~/.hermes -iname android_tool.py` vide),
  jamais référencé par `install-plugin.sh` ni aucun autre mécanisme
  d'installation. Le vrai système d'exposition des capabilities téléphone
  à Hermes (SMS, localisation, etc.) est `~/.hermes/phone-relay-mcp/server.js`
  (MCP en Node.js, hors de ce repo, 4 process actifs confirmés, modifié le
  24/07) — les deux visaient le même besoin (exposer les capabilities via
  `POST /bridge/command`), seul le second a jamais été branché en
  production. Référence CI (`plugin-lint.yml`) et commentaires pointant
  vers ce fichier mis à jour en conséquence.
- Outil agent `share_file` et endpoint `GET /api/attachments-out/...`
  (hors de ce repo git, sur le VPS) — introduits le 2026-07-21, retirés le
  lendemain après avoir buté sur un `combinedClickable` Compose qui
  empêchait tout clic sur le lien Markdown généré dans les bulles de chat.
  Remplacés par l'onglet Fichiers (accès direct au workspace de session, pas
  besoin d'un outil dédié pour "publier" un fichier). Détails complets :
  `archive/2026-07-21-hermes-hallucination-attachments-out.md`.
- SkillClaw (proxy LLM local + auto-évolution de skills, hors de ce repo
  git, sur le VPS) — remplacé par le curator natif Hermes (`hermes
  curator`, déjà présent, `enabled: true`). Périmètre différent : le
  curator natif ne gère que les skills créées dynamiquement par l'agent,
  pas la bibliothèque bundled/hub — ce n'était de toute façon pas ce que
  SkillClaw gérait non plus dans cette partie. Résidus nettoyés
  (`~/.skillclaw/`, `~/.hermes/.skillclaw_backups/`, logs temporaires).
  Détails complets : `archive/2026-07-28-retrait-skillclaw-webui-down.md`.
- `~/.hermes/phone-relay-mcp/server.js` (MCP Node.js, hors de ce repo) —
  entièrement remplacé par `plugin/hasan_delivery/tools.py` (tools natifs
  Hermes, découverte dynamique — voir Added). Retiré de
  `~/.hermes/config.yaml` (`mcp_servers.phone_relay`), processus et
  watchdogs stoppés, dossier supprimé du VPS (~27 Mo). `hermes-dashboard`
  et `hermes-webui` avaient chacun leur propre process MCP orphelin
  (démarré avant la migration) : un simple restart du gateway ne les
  arrête pas, il a fallu redémarrer aussi ces deux services pour que les
  derniers process `node server.js` disparaissent.

### Changed
- Onglet Skills retiré de la sidebar (drawer), fusionné comme troisième
  pill dans l'écran Mémoire (`MEMORY / SKILLS / INSIGHTS`, entre Memory et
  Insights) — désencombre la navigation principale (6 entrées au lieu de 7)
  en regroupant deux écrans lecture-seule conceptuellement proches, cohérent
  avec la prospective #2 de l'audit 4-volets ("Fusion Skills + Memory en
  une section Agent Insights"). `SkillsViewModel`/`SkillsScreen` inchangés
  dans leur logique (liste groupée par catégorie, détail, refresh) —
  seulement hébergés par `MemoryFragment` au lieu d'un `SkillsFragment`
  dédié (supprimé), avec le header hamburger dédupliqué
  (`SkillsScreen.showMenuHeader = false` dans ce contexte).
- Simplification de la connexion manuelle (Réglages → Connexions →
  Configuration manuelle) : le code de pairing relay est désormais masqué
  comme le mot de passe hermes-webui (`isSecret = true`, était visible en
  clair) ; éditer l'un ou l'autre champ déclenche une authentification
  biométrique/PIN de l'appareil avant de passer en mode édition (même
  garde-fou déjà utilisé pour le switch d'activation du relay,
  `BiometricAuthHelper`) ; les boutons "Appairer manuellement" et
  "Déconnecter le relay (dépairing)" sont retirés — le bouton "Se
  connecter" du panneau de statut gère maintenant le pairing relay ET la
  connexion chat en un seul geste (`SettingsFragment.connectToWebUi()`
  appelle `viewModel.pairManually()` avant le login webui si le relay
  n'est pas déjà appairé et que URL+code manuels sont renseignés).
- `buildMetadataText()` (`ChatScreen.kt`) n'omet plus la métadonnée
  "Xs · Y tok" sous une réponse quand `duration_ms`/`output_tokens` sont
  absents ou nuls — ce garde-fou avait été ajouté pour éviter un affichage
  trompeur "0s · 0 tok" pendant l'épisode SkillClaw (le proxy omettait
  `usage` en streaming, voir
  `archive/2026-07-26-tokens-duree-non-affiches-chat.md`). Devenu inutile
  depuis le retrait de SkillClaw (DeepSeek natif renvoie `usage`
  correctement) — remis au comportement simple d'origine.

### Removed
- Code mort laissé par la migration vers Compose : `DiagonalCutShape` et les
  formes `HasanShapes.diagonal`/`diagonalLarge` (reliquats de l'ancien mockup
  `hasan-mockup-v2.html`, dernier usage retiré avec la reprise du mode mains
  libres), le composable `StatusBadge` et le style `HasanMonoLabelSmall`
  (jamais appelés), 16 drawables orphelins (fonds de bulles et boutons ronds
  désormais dessinés via `CutCornerShape`, icônes de navigation remplacées par
  celles retracées depuis le mockup) et 12 imports inutilisés. Le commentaire
  d'en-tête de `Shape.kt` pointait encore vers l'ancien mockup.
  Vérifié avant suppression : aucune résolution dynamique de ressource
  (`getIdentifier`) dans le projet, donc l'analyse statique des références est
  fiable. Les layouts consommés uniquement via ViewBinding (sans `R.layout.X`
  littéral) et les imports `getValue`/`setValue` requis par la délégation
  `by remember` sont des faux positifs classiques, explicitement conservés.

### Fixed
- Bouton de lecture sous une bulle laissé dans l'état « arrêté » alors que le
  message était en cours de lecture, quand le TTS est activé dans les
  Réglages. La lecture automatique de fin de tour appelait `ttsManager.speak()`
  directement, sans jamais renseigner `ttsPlayingMessageId` — seul un appui
  manuel (`readAloud()`) armait cet état, or c'est lui qui décide de l'icône
  (`ic_volume_off` en lecture, `ic_replay` sinon, voir `ChatScreen.kt`).
  Trois autres chemins remettaient `ttsStatus` à `IDLE` sans remettre
  `ttsPlayingMessageId` à `null` (barge-in confirmé, coupure du TTS à
  l'ouverture du micro) : le bouton restait alors armé après l'arrêt réel de
  la lecture, si bien que l'appui suivant relançait au lieu d'arrêter. Les
  quatre chemins écrivent désormais les deux champs ensemble (issue #6).
- Sessions bloquées sur « Nouvelle session » dans le drawer, jamais titrées.
  Hermes titre pourtant les sessions lui-même (LLM auxiliaire), mais ce titre
  n'arrive **jamais** par le flux de chat : il est produit dans un thread de
  fond côté serveur, lancé après `done` et mutuellement exclusif avec
  `stream_end` — or `WebUiChatStream` ferme la connexion sur `stream_end`.
  Vérifié sur device : un tour complet ne voit passer ni `title` ni
  `title_status`. Le titre est en revanche exposé par `GET /api/sessions`
  (qui sert le store de sessions Hermes, et non `state.db` dont la colonne
  `title` reste vide pour ce chemin). Nouveau `resolveSessionTitle()` :
  à la fin d'un tour sur une session anonyme, scrutation de `/api/sessions`
  toutes les 1,5 s pendant 10 s max ; dès qu'un titre serveur apparaît il est
  affiché et stocké, et à l'échéance sans titre on retombe sur le premier
  message de l'utilisateur (tronqué à 80 caractères) — une session finit donc
  toujours nommée. `syncSessionsFromServer()` rattrape en plus les sessions
  **déjà connues** restées anonymes (il ne traitait que les nouvelles) : le
  titre serveur peut arriver après la fermeture de l'app. Un garde-fou commun,
  `isPlaceholderSessionName()`, empêche d'écraser un nom déjà porteur de sens
  — titre serveur arrivé plus tôt ou renommage manuel de l'utilisateur ; il
  couvre aussi les placeholders serveur (`Untitled`, `New Chat`,
  `CLI Session`). Le repli utilise `userText` et non `lastUserText`, qu'un
  `/steer` écrase en cours de tour (issue #2).
  Cause racine côté serveur, hors de ce repo, corrigée séparément dans
  `~/.hermes/config.yaml` : `auxiliary.title_generation` déclarait
  `provider: deepseek` avec `model: meta/llama-3.2-3b-instruct` (un modèle
  NVIDIA NIM), d'où un HTTP 400 avalé par le thread de fond et 0 session
  `api_server` titrée sur 74. Le correctif app fonctionne indépendamment :
  sans titre serveur, le repli prend le relais.
- Retour arrière (geste de swipe ou bouton système) qui quittait l'app depuis
  n'importe quel écran, sans confirmation. Le projet n'avait aucun
  `OnBackPressedCallback` et pas de back stack à dépiler — les six onglets
  sont ajoutés une fois puis show/hide (`MainActivity.showFragment`), les
  overlays sont add/remove manuels — donc Android appliquait son défaut :
  terminer l'Activity, d'où le retour au launcher. La hiérarchie de retour
  est désormais reconstruite explicitement (`setupBackNavigation`), de la
  couche la plus superficielle à la plus profonde : confirmation de sortie →
  drawer → overlay plein écran (mains libres/Logs/Fichiers) → profondeur
  interne à l'onglet → retour au Chat → confirmation de sortie. Seul le
  dernier niveau quitte l'app, et jamais sans passer par `confirmQuit()`.
  Nouveau contrat `BackHandledScreen` (`ui/`) pour la profondeur interne aux
  onglets (éditeur de tâche, overlay certificats, détail Kanban/Mémoire/skill,
  arborescence Fichiers) : cet état vit dans le Compose des fragments ou leurs
  ViewModels, invisible depuis l'Activity — chaque écran est seul à savoir
  s'il a une couche à refermer, plutôt que de faire fouiller MainActivity dans
  ses enfants. `TasksFragment.editorOpen`/`editingJob` remontés de `remember`
  à champs du Fragment au passage : une valeur `remember` ne vit que dans la
  composition, hors de portée du callback de retour (issue #3).
- Nombre de skills faux dans Réglages → Profil Hermes (11 affichés au lieu
  de 834, mesuré sur le VPS) — contournement côté app d'un bug serveur
  hermes-webui. Le `skill_count` de `GET /api/profiles` vient de
  `_compute_profile_skills_stats` (`api/profiles.py`), qui ne scanne que
  `<profil>/skills` et ignore les `external_dirs` déclarés dans
  `config.yaml` (ici `~/.agents/skills` et `~/.hermes/hermes-agent/skills`,
  soit 827 des 834 skills réelles). `GET /api/skills`, lui, parcourt bien
  tous les répertoires de recherche : les deux écrans de l'app affichaient
  donc des nombres incompatibles pour le même profil. `loadHermesProfiles()`
  écrase désormais le compte du profil **actif** avec celui de
  `WebUiSkillsClient.listSkills()`, déjà utilisé par l'onglet Skills.
  Correction volontairement limitée au profil actif — `/api/skills` est
  relatif au profil courant côté serveur, son total ne dit rien des autres ;
  et dégradation silencieuse sur échec (valeur serveur conservée). Le bug
  serveur reste entier : tout autre client de `/api/profiles`, dont le
  frontend web de hermes-webui, continue d'afficher le compte sous-évalué
  (issue #4).
- Bande de status bar (zone du poinçon caméra) laissée en `BgBase` sur tous
  les onglets : le padding d'insets, appliqué une seule fois au niveau du
  `AndroidView` racine (`MainActivity`), réservait bien la place de la status
  bar mais n'y peignait rien — le header semblait donc flotter au lieu de
  remonter jusqu'en haut de l'écran comme dans le mockup. Bande peinte en
  `BgHeader` au même niveau que le padding qui la crée, et non dans
  `HasanHeader`/`HasanMinimalHeader` : ces composants vivent 2 `ComposeView`
  plus bas (`AndroidView` → Fragment → `ComposeView`), où les `WindowInsets`
  Compose ne se propagent pas de façon fiable (raison déjà documentée pour le
  padding lui-même). Exclut le mode mains libres, seul écran sans header — une
  bande colorée y aurait tranché sur son fond plein cadre.
- Ligne de séparation sous le header absente sur de nombreux onglets : ni
  `HasanHeader` ni `HasanMinimalHeader` n'implémentaient le `border-bottom` du
  mockup (`.app-header`, ligne 260). Corrigé dans les deux composants partagés
  plutôt qu'écran par écran — ce qui a révélé au passage que
  `ToolsPermissionsScreen` plaçait son titre dans un `ScreenTitle` SOUS le
  header (donc sous la nouvelle bordure) avec un séparateur manuel devenu
  doublon.
- Bouton "+" du Kanban rendu en `FloatingActionButton` Material3 : cercle
  flottant hors DA (l'app est en coins coupés) et sans équivalent dans le
  mockup — la règle CSS `.fab` y existe mais n'est jamais utilisée dans le
  markup, seul le `.mic-fab` inline du composer est réel. Remplacé par un
  `HasanIconButton` posé dans la ligne du header (nouveau slot
  `trailingContent` sur `HasanMinimalHeader`), même motif que `TasksHeader`.
  La création de tâche présélectionne désormais une catégorie par défaut au
  lieu de laisser la nouvelle tâche sans statut.
- Micro du mode mains libres qui se décalait vers le bas ~1 s après le tap :
  l'anneau de pulsation (148 dp) n'était ajouté à la composition que lorsque
  `isListening` passait à vrai, et son conteneur, dimensionné sur son contenu,
  passait alors de 128 dp à 148 dp. Le délai perçu était celui du démarrage
  réel du moteur STT. Conteneur à taille fixe et anneau toujours composé
  (masqué par `alpha`) : la mesure ne dépend plus de l'état.
- Dernière ligne du transcript mains libres illisible, à moitié recouverte par
  le dégradé d'estompage de débordement. Deux approches ont échoué avant la
  bonne : une fraction de la hauteur du bloc (zone estompée proportionnelle au
  texte, de plus en plus mordante) puis un multiple de hauteur de ligne
  supposée (le rendu markdown ne garantit pas des lignes égales — un titre est
  plus haut). Une ligne vide ajoutée en fin de markdown ne marche pas non
  plus : Markwon la rend à hauteur réduite (~40 px mesurés sur device). Le
  dégradé court désormais sur une marge basse vide réservée DANS le `TextView`
  (nouveau paramètre `bottomPaddingPx` de `MarkdownText`) — déterministe, et
  invisible aux `Modifier.padding` Compose extérieurs qui ne seraient pas
  couverts par le dégradé.
- Mode mains libres jamais migré depuis l'ancien mockup
  (`hasan-mockup-v2.html`) : forme du micro en `diagonalLarge` et boutons bas
  en petites puces IBM Plex Mono au lieu des `.btn-ghost` (Chakra Petch
  capitales, `min-height` 44 px). Icône du micro portée de 34 dp à 56 dp dans
  un bouton de 128 dp — elle n'en occupait qu'un quart. Transcript rendu en
  markdown (moteur Markwon partagé avec les bulles de chat) au lieu de texte
  brut, qui affichait les marqueurs `**`/`` ` ``/`-` en plein écran vocal.
- `_send_fcm_wake` (`server/relay/server.py`) appelait
  `run_in_executor(None, messaging.send, message, fcm_app)` — le 3e argument
  positionnel atterrit sur `dry_run` (signature réelle :
  `send(message, dry_run=False, app=None)`), pas sur `app`. `bool(fcm_app)`
  étant toujours vrai, chaque envoi passait silencieusement en mode dry-run :
  `messaging.send()` retournait `projects/.../messages/fake_message_id` sans
  jamais contacter Google, donc sans jamais réveiller le device — aucune
  exception levée, `log.info("Réveil FCM envoyé...")` s'affichait quand même.
  Découvert uniquement via test réel sur device (aucun test existant
  n'exerçait le vrai appel `messaging.send`, tous mockaient `_send_fcm_wake`
  dans son ensemble). Fix : `functools.partial(messaging.send, message,
  app=fcm_app)` pour forcer `app` en keyword. Nouveau test de régression
  (`test_send_fcm_wake_passes_app_as_keyword`) qui mocke `messaging.send`
  lui-même plutôt que `_send_fcm_wake`.
- Canal `proactive` (WebSocket) jamais affiché comme notification Android
  quand l'app est en arrière-plan mais son WebSocket encore actif —
  `MainViewModel` ne faisait que logger l'événement dans `ActivityLog`
  (`tag = "PUSH"`, visible seulement via Réglages → À propos → Voir les
  logs) sans jamais appeler `ProactiveNotifier.show()`. La classe
  `ProactiveMessageHandler`, écrite précisément pour ce cas, n'était
  instanciée nulle part dans l'app — code mort depuis sa création. Découvert
  en testant le scénario "app en arrière-plan" après validation des deux
  scénarios FCM (redémarrage téléphone, fermeture via bouton Quitter), qui
  fonctionnaient déjà. Fix : `MainViewModel` appelle directement
  `ProactiveNotifier.show()` quand `!isAppInForeground()` (même pattern déjà
  utilisé pour le canal `chat`/`NotificationHelper`) ; `ProactiveMessageHandler`
  supprimé (logique dupliquée, jamais câblée).
- `PairingManager.get_session_by_device_hash()` (`server/relay/pairing.py`)
  retournait la première session trouvée pour un device (ordre d'insertion,
  donc la plus ancienne) au lieu de la plus récente — un device accumule une
  nouvelle session à chaque reconnexion WS au lieu de réutiliser une session
  existante (limitation connue, pas corrigée ici), donc `GET /capabilities`
  pouvait retourner les capabilities d'une session morte depuis des mois
  plutôt que celles de la session active. Découvert en déployant la
  découverte dynamique (voir Added) : `GET /capabilities` renvoyait
  systématiquement `[]` malgré un envelope `system/capabilities` correctement
  reçu et persisté. Fix : sélectionne la session avec `max(last_seen_at)`
  parmi celles du device.
- `plugin/hasan_delivery/tools.py` enregistrait ses tools via
  `tools.registry.registry.register()` directement plutôt que
  `ctx.register_tool()` — le tool était bien invocable, mais le toolset
  `hasan_phone` restait invisible pour `hermes tools enable`/`list`
  ("Unknown toolset"), car seul `ctx.register_tool()` alimente la liste
  d'attribution interne (`PluginManager._plugin_tool_names`) que Hermes
  utilise pour reconnaître un toolset comme fourni par un plugin. Fix :
  `tools.py` expose `register_tools(ctx)`, appelée depuis
  `__init__.py::register(ctx)` avec le `ctx` reçu du loader de plugins
  (au lieu d'un effet de bord à l'import du module, qui ne se produisait
  d'ailleurs jamais : `__init__.py` n'importait que `.adapter`, jamais
  `.tools` — confirmé en production via `hermes chat`, qui ne trouvait que
  l'ancien tool MCP `mcp__phone_relay__get_battery`, jamais de tool natif).
- Connexion WebUI (chat) impossible depuis l'app — deux causes distinctes
  côté VPS, corrigées l'une après l'autre :
  1. `hermes-webui.service` était arrêté depuis le 26/07 (dernier appel LLM
     avait échoué en boucle vers le proxy SkillClaw sur
     `127.0.0.1:30000`, entre-temps désinstallé par l'utilisateur, puis le
     service systemd n'avait jamais redémarré). Fix :
     `systemctl start hermes-webui.service`.
  2. Une fois le service relancé, le login échouait encore en 401 via
     l'URL publique alors qu'il réussissait en local (`127.0.0.1:8787`) —
     `~/.hermes/Caddyfile` routait `:443` vers le mauvais backend
     (`127.0.0.1:9119`, le dashboard interne `hermes`, pas
     `127.0.0.1:8787`). Un fichier de config correct existait déjà
     (`/tmp/Caddyfile.new`, jamais appliqué) ; appliqué + ajusté le chemin
     de log (permissions). Voir
     `archive/2026-07-28-retrait-skillclaw-webui-down.md`.
- Guard `RECORD_AUDIO` manquant sur `MainViewModel.sendWakeWordIntent()` —
  seul des quatre points d'appel au service wake word encore exposé au
  crash `SecurityException` déjà corrigé ailleurs (`swapWakeWordModel`,
  `setWakeWordSensitivity`) : `HassanWakeWordService.onCreate()` appelle
  `startForeground(MICROPHONE)` sans condition sur l'`action` reçue, donc
  n'importe quel `startService()` sur un service mort peut re-déclencher le
  crash, pas seulement `ACTION_RESUME`. Vérifié sur device (permission
  révoquée, deux taps successifs sur le toggle wake word) : plus de crash.
- Écran noir après "Quitter l'app" dans un scénario précis : lancer l'app,
  revenir au home, retaper sur la notification persistante du wake word, puis
  quitter. `MainActivity` n'avait pas de `launchMode` déclaré (défaut
  `standard`) — le `PendingIntent` de la notification
  (`FLAG_ACTIVITY_NEW_TASK`) empilait une deuxième instance de `MainActivity`
  par-dessus la première dans la même Task à chaque tap. "Quitter"
  (`finishAndRemoveTask()`) ne fermait que l'instance du sommet ; celle du
  dessous, jamais rafraîchie depuis sa mise en pause, remontait au premier
  plan avec un rendu Compose invalide (écran noir). Fix :
  `android:launchMode="singleTask"` sur `MainActivity` + `onNewIntent()` pour
  router tout relaunch vers l'instance existante au lieu d'en créer une
  nouvelle. Reproduit et vérifié via `adb` (flags identiques au
  `PendingIntent` réel) avant et après le fix.
- Bouton "Quitter l'app" (drawer) ne garantissait pas l'arrêt réel de
  `HassanWakeWordService` : `quitApp()` (`MainActivity.kt`) appelait
  `stopService()` (demande d'arrêt asynchrone) immédiatement suivi de
  `killProcess()` — si le kill intervenait avant qu'Android ait traité la
  demande, le système pouvait interpréter la mort du process comme un kill
  mémoire externe et relancer le service en `START_STICKY` (comportement
  par défaut de `onStartCommand`), laissant l'écoute wake word active en
  tâche de fond malgré "Quitter". Fix : `startService()` avec l'action
  `ACTION_STOP` déjà présente dans le service (`stopForeground` +
  `stopSelf()` + retour explicite `START_NOT_STICKY`), qui s'exécute de
  façon synchrone avant que `killProcess()` ne soit atteint.
- Cinq points de l'audit punch-hole/coins arrondis Pixel 10
  (`archive/2026-07-23-audit-boutons-masque-punch-hole-pixel10.md`) :
  - Titre "TOOLS & PERMISSIONS" chevauché à 0px par la découpe caméra —
    `ToolsPermissionsHeader` (titre + hamburger sur la même ligne, centre
    non réservé) remplacé par `HasanMinimalHeader` + `ScreenTitle` sur sa
    propre ligne (seul écran où le titre est trop long pour tenir à droite
    du hamburger sans toucher le punch-hole, centré à mi-écran).
  - Hamburger de `ToolsPermissionsScreen` décalé de 11px vers la gauche
    (`SpacingL` au lieu de `SpacingXl`, incohérent avec tous les autres
    écrans) — corrigé par le même remplacement.
  - Bouton "Joindre un fichier" (Chat) débordant de ~5px dans le coin
    arrondi bas-gauche — padding start de la barre de saisie passé de
    `SpacingL` à `SpacingXl`.
  - QR Scanner sans bouton retour visible dans l'UI (seul le bouton back
    matériel fonctionnait) — bouton retour ajouté en haut-gauche
    (`activity_qr_scanner.xml`), `finish()` sans `setResult()` explicite
    (même comportement `RESULT_CANCELED` que le bouton back).
  - Items de nav du drawer (~40dp mesurés) et icône "+" du FAB Kanban
    (décentrée verticalement, line-height typographique par défaut) sous
    ou proches du seuil tactile 48dp — `heightIn(min = TouchTarget)` sur
    `DrawerNavRow`, `lineHeight = fontSize` sur le glyphe "+" du FAB.
- Titre à droite du hamburger : incohérent selon les écrans (Fichiers/
  TaskEditor l'avaient à droite du bouton retour, Tâches/Kanban/Mémoire/
  Paramètres/Tools l'avaient soit absent soit en dessous). Uniformisé sur
  "à droite du hamburger" (préférence explicite, même emplacement que
  "HASAN" dans `HasanHeader` côté Chat) via un nouveau paramètre
  `HasanMinimalHeader(title = ...)`, sauf Tools & Permissions qui reste
  en dessous (voir point punch-hole ci-dessus, seul conflit réel constaté).
- Incohérence de header/titre entre écrans : `TaskEditorScreen` n'avait
  aucun bouton retour visible (seul un bouton "Annuler" tout en bas du
  formulaire, après scroll potentiel), `SettingsScreen` et `FilesScreen`
  n'affichaient pas le nom de l'écran. Alignés sur le pattern déjà utilisé
  par Kanban/Skills (titre stylé sous le header minimal). Audit 4-volets
  finding #8. Étendu ensuite à Tâches, Kanban et Mémoire (même trou, repéré
  après coup) via un composable partagé `ScreenTitle` (`HasanHeader.kt`).
- Slider "Sensibilité du wake word" (Réglages) sans effet réel — écrivait la
  préférence mais n'avertissait jamais `HassanWakeWordService`, qui gardait
  un seuil de détection codé en dur (0.5) ignorant totalement le réglage
  utilisateur. Fix : nouvelle action `ACTION_SET_SENSITIVITY`, réutilise le
  mécanisme de hot-swap déjà en place pour le changement de modèle
  (`WakeWordModel.threshold` de la lib `openwakeword` est immuable, seul un
  nouvel engine peut appliquer un nouveau seuil). Le service lit aussi
  désormais la sensibilité stockée au démarrage. Audit 4-volets finding #9.
- Base Room (`hasan.db`, historique complet des conversations) stockée en
  clair sur disque — chiffrement via SQLCipher
  (`net.zetetic:android-database-sqlcipher`), clé aléatoire 256 bits générée
  au premier lancement post-update et stockée dans
  `EncryptedSharedPreferences` (même fichier `hasan_secure_prefs` que les
  tokens/certificats TOFU, voir `SettingsManager.getOrCreateRoomDbKey()`).
  Migration automatique du fichier existant non chiffré vers chiffré
  (`HassanDatabase.migratePlaintextDbIfNeeded()`, via
  `sqlcipher_export` — sans réécriture manuelle table par table), avec
  backup `hasan.db.bak` conservé indéfiniment comme filet de récupération.
  Testé bout en bout sur device réel avec des données de production
  réelles : migration, lecture de l'historique existant, écriture d'un
  nouveau message, persistance après redémarrage complet de l'app. Audit
  4-volets finding #6.
- Liens Markdown non cliquables dans les bulles de chat (`MarkdownText.kt`) :
  `TextView.setTextIsSelectable(true)` réinitialise `movementMethod` en
  interne à chaque appel — comme `update()` de l'`AndroidView` interop se
  réexécute à chaque recomposition (streaming token par token en
  particulier), `LinkMovementMethod` posé une seule fois dans `factory`
  était écrasé dès la première recomposition suivant le montage. Fix :
  réappliquer `movementMethod` après `setTextIsSelectable` dans `update`.
  Trouvé lors de l'audit 4-volets (finding #3,
  `archive/2026-07-23-audit-4-volets-decouverte-ui-utilite-securite-perf.md`).
- Board Kanban visuellement illisible (colonnes horizontales sans cadre ni
  séparation visuelle, cf. audit 4-volets finding #1) — refonte en liste
  verticale groupée par colonne avec pills de navigation, sections
  collapsibles, bande de couleur par priorité sur les cartes, déplacement
  via bottom sheet (remplace le menu `⋮` minuscule et peu contrasté) et FAB
  de création de tâche (`POST /api/kanban/tasks`, endpoint déjà existant
  côté serveur mais jamais consommé par l'app jusqu'ici).
- `LatencyLog.mark()` (fichier disque `latency.log`, non chiffré, sans gate
  debug/release) loggait en clair les paramètres et résultats des
  capabilities bridge sensibles (`send_sms`, `get_location`, `get_contacts`)
  — numéro + contenu de SMS, position GPS exacte, contacts consultés. Fix :
  redaction (`[redacted]`) de `params`/`data`/`message` dans
  `BridgeCommandHandler.kt` pour les capabilities marquées
  `authRequiredDefault=true`, sans toucher au diagnostic latence du reste du
  pipeline (audit 4-volets finding #4).
- Rework du panel de statut connexions (Réglages) : un `Text` avec
  `Modifier.weight(1f)` dans une `Row` sans `fillMaxWidth()` gonflait la
  hauteur du panel de ~475px et faisait disparaître le label "Relay
  (téléphone)" du rendu.
- Guard `RECORD_AUDIO` manquant sur `MainViewModel.sendWakeWordIntent()` —
  seul des quatre points d'appel au service wake word encore exposé au
  crash `SecurityException` déjà corrigé ailleurs (`swapWakeWordModel`,
  `setWakeWordSensitivity`) : `HassanWakeWordService.onCreate()` appelle
  `startForeground(MICROPHONE)` sans condition sur l'`action` reçue, donc
  n'importe quel `startService()` sur un service mort peut re-déclencher le
  crash, pas seulement `ACTION_RESUME`. Vérifié sur device (permission
  révoquée, deux taps successifs sur le toggle wake word) : plus de crash.
