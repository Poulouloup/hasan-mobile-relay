# Changelog

Format inspiré de [Keep a Changelog](https://keepachangelog.com/). Une entrée
par fix ou upgrade notable — le "pourquoi", pas juste le "quoi" (le diff git
montre déjà le quoi).

## [Unreleased]

### Added
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

### Removed
- Outil agent `share_file` et endpoint `GET /api/attachments-out/...`
  (hors de ce repo git, sur le VPS) — introduits le 2026-07-21, retirés le
  lendemain après avoir buté sur un `combinedClickable` Compose qui
  empêchait tout clic sur le lien Markdown généré dans les bulles de chat.
  Remplacés par l'onglet Fichiers (accès direct au workspace de session, pas
  besoin d'un outil dédié pour "publier" un fichier). Détails complets :
  `archive/2026-07-21-hermes-hallucination-attachments-out.md`.

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

### Fixed
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
