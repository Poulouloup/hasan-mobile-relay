# Audit GitHub / Maintenance

Périmètre : hygiène du dépôt git, `.gitignore`, secrets historiques, dépendances,
README vs réalité, CI, fichiers de config sensibles dans l'état actuel.
Audit en lecture seule, aucune modification apportée au dépôt.

## Bloquant

Aucun finding Bloquant. Aucun secret réel confirmé committé dans l'historique git
(voir détail dans Important ci-dessous — un faux positif a été vérifié et écarté).

## Important

- **[README.md:19-57 vs commit 736ea81]** Le README décrit l'architecture "chat"
  comme reposant intégralement sur le relay WebSocket (`[WSS] ConnectionManager`,
  `[chat] ChatStreamHandler`, section "Architecture" lignes 19-55, et la ligne
  67 "WebSocket relay — single persistent connection multiplexing chat
  streaming..."). Or sur la branche courante `webui-migration`, le flux chat a
  été basculé vers un nouveau client hermes-webui (REST/SSE) : voir commit
  `3fe4c4f` "feat(webui): client REST/SSE hermes-webui isolé (étape 2
  migration)" et surtout `736ea81` "feat(chat): basculer
  MainViewModel/WakeWordPipeline vers hermes-webui (étape 3)", confirmés par
  10+ commits ultérieurs de migration (`6209256`, `64fb7bd`, `10d05ae`,
  `c758e39`, `8635340`, `420fa4b`, etc.). Le README n'a reçu aucune mise à jour
  sur cette branche (`git log master..webui-migration -- README.md` ne retourne
  rien) — il documente donc une architecture qui n'est plus celle qui s'exécute
  réellement côté chat. Priorité : Important. Justification : un contributeur ou
  un futur mainteneur lisant le README se ferait une fausse idée du flux
  principal de l'app (chat) ; le WS relay existe toujours pour `bridge`/
  `system`/`proactive` mais plus pour le chat, ce que le README ne distingue pas.

- **[SETUP.md:22-35]** Les instructions de setup pointent vers l'ancien serveur
  de simulation `server/server.py` (`python gen_cert.py` / `python server.py`)
  et vers un écran "Settings → Hermes Connection" avec URL/token en dur
  (`Dev defaults: https://192.168.1.100:8443 / HASAN_DEV_TOKEN`). D'après le
  README lui-même (section Features, ligne 65 "QR pairing — one-time pairing
  flow, no manual token/URL entry required") et les commits de la branche
  courante (`10af5e6` "étendre le QR pairing pour porter aussi la config
  hermes-webui", `10d05ae` "connexion manuelle hermes-webui (URL + mot de
  passe)"), le flux de configuration réel est désormais QR pairing (+ un mode
  connexion manuelle hermes-webui distinct, pas celui décrit). Priorité :
  Important. Justification : un nouveau contributeur suivant SETUP.md
  littéralement configurerait une app dans un état non représentatif du
  fonctionnement actuel.

- **[app/build.gradle.kts:87]** `androidx.security:security-crypto:1.1.0-alpha06`
  — une version **alpha (pré-release)** est utilisée en dépendance de
  production pour `EncryptedSharedPreferences`, composant explicitement cité
  dans le README (ligne 13) comme pilier de la stratégie "no hardcoded
  secrets". Il n'existe à ma connaissance (estimation, pas de vérification
  réseau) toujours pas de release stable 1.1.x de `androidx.security:security-
  crypto` à la date de mon entraînement — donc ce n'est pas forcément un retard
  fautif, mais le risque (API instable, bugs non résolus en alpha) mérite
  d'être noté vu le rôle sécuritaire du composant. Priorité : Important.
  Justification : dépendance alpha sur le chemin critique de sécurité (stockage
  des tokens de session/pairing).

- **[Aucune CI]** Aucun répertoire `.github/workflows/`, aucun `.gitlab-ci.yml`,
  `.circleci/`, ni autre fichier de CI trouvé nulle part dans le dépôt (recherche
  glob sur toute l'arborescence). Il n'y a donc aucune vérification automatique
  (build, lint, tests) sur push/PR — la seule mention de tests trouvée est le
  répertoire `server/relay/` (pytest, `test_server.py`, `test_chat_stream.py`)
  qui semble exécuté uniquement en local (`.pytest_cache/` présent sur disque,
  non tracké par git). Priorité : Important. Justification : pour un projet
  avec plusieurs branches de versions (`v1.1-qol` → `v1.4-stt-tts` →
  `webui-migration`) et une suite de tests Python existante, l'absence totale
  de CI signifie que les régressions ne sont détectées qu'en test manuel.

## Mineur

- **[local.properties (racine, non tracké)]** Fichier présent dans l'arborescence
  de travail actuelle, correctement **non suivi par git** (`git ls-files` ne le
  liste pas, `git log --all -- local.properties` ne retourne aucun commit — il
  n'a jamais été committé) et bien couvert par `.gitignore:4`. Contenu vérifié :
  `sdk.dir=...` (chemin SDK local) et `nvdApiKey=5c1a74b0-1be2-46e1-95d7-
  deaeb763d1a3`. Cette clé est lue par `app/build.gradle.kts:17`
  (`localProps.getProperty("nvdApiKey")`) pour le plugin OWASP
  `dependencyCheck` (scan de CVE des dépendances, `nvd.apiKey`). C'est un usage
  légitime et le fichier est bien ignoré par git — pas une fuite. Signalé pour
  mémoire uniquement : c'est une clé API réelle et active sur le poste de dev,
  à ne jamais coller ailleurs (ticket, capture d'écran, etc.). Priorité :
  Mineur. Justification : aucune fuite constatée, hygiène git correcte ; note
  de vigilance seulement.

- **[Historique git — faux positif vérifié]** La recherche
  `git log --all --full-history --diff-filter=A --name-only -- '*token*' ...`
  remonte un seul résultat : commit `72e702b` (2026-06-27, "fix(tts): modèle
  Piper repackagé Sherpa-ONNX") qui ajoute
  `app/src/main/assets/vits-piper-fr_FR-upmc-medium-tokens.txt`. Contenu vérifié
  via `git show` : il s'agit d'une table de correspondance phonème→ID pour le
  modèle de synthèse vocale Sherpa-ONNX (ex. `a 14`, `b 15`), aucun secret.
  Aucune autre correspondance pour `*.jks`, `*.keystore`, `*secret*`,
  `*password*`, `*credential*`, `*.pem`, `*.key`, `*apikey*`, `*api_key*` dans
  tout l'historique (`--all --full-history`). Priorité : Mineur (juste une
  entrée de vérification pour la traçabilité de l'audit, pas un problème).
  Justification : confirme l'absence de secret committé, à l'exception de la
  vigilance ci-dessus sur `local.properties` (jamais committé).

- **[gradle/libs.versions.toml + app/build.gradle.kts]** Versions de dépendances
  notables (estimation de ma part, sans accès réseau pour vérifier les
  dernières stables au 2026-07-20) :
  - `kotlin = "2.0.21"` (ligne 3) — des versions 2.1.x/2.2.x existaient déjà à
    ma connaissance ; retard modéré, pas critique.
  - `agp = "8.7.0"` (ligne 2) et Gradle `8.9` (gradle-wrapper.properties:3) —
    cohérents entre eux, légèrement en retard sur la ligne AGP 8.9.x/8.10.x
    connue.
  - `room = "2.6.1"` (ligne 13) — une ligne 2.7.x existait à ma connaissance ;
    écart mineur.
  - `composeBom = "2024.12.01"` (ligne 16) — plusieurs BOM plus récents
    existent probablement à la date actuelle (2026-07-20), l'écart temporel
    (~7 mois) est significatif pour Compose qui a un rythme de release rapide ;
    à vérifier par l'équipe avec accès réseau.
  - Plusieurs dépendances sont volontairement figées à une version précise
    avec justification en commentaire dans le code (ex. `app/build.gradle.kts:110`
    media3 1.9.4 "pas 1.10.x : exige compileSdk 36", `app/build.gradle.kts:114`
    camera 1.5.3 "pas 1.6.x : exige compileSdk 36 + AGP 8.9.1") — ceci est une
    dette technique documentée et assumée, pas un oubli.
  Priorité : Mineur. Justification : aucune de ces versions n'est ancienne au
  point d'être non maintenue ; à traiter dans un cycle de mise à jour normal,
  pas en urgence. Le plugin `org.owasp.dependencycheck` (root `build.gradle.kts:4`,
  version 10.0.4) étant déjà intégré au projet, l'équipe dispose déjà d'un
  outil pour suivre les CVE — voir aussi la clé `nvdApiKey` ci-dessus qui sert
  précisément à ça.

- **[Branches locales/distantes — toutes fusionnées, aucune à nettoyer]**
  `git branch -a --merged master` et `--merged webui-migration` listent
  **toutes** les branches existantes (`v1.1-qol`, `v1.2-mcp-update`,
  `v1.3-network-layer`, `v1.4-stt-tts`, `v2-relay-merge`, et leurs équivalents
  `origin/*`) comme déjà fusionnées dans master ET dans webui-migration —
  l'historique est linéaire, chaque branche de version précédente est un
  ancêtre direct de la suivante. `git branch -a --no-merged webui-migration`
  ne retourne que la branche courante elle-même (aucune branche divergente non
  fusionnée). Dates du dernier commit par branche : `v1.1-qol` 2026-06-08,
  `v1.2-mcp-update` 2026-07-06, `v1.3-network-layer` 2026-07-07,
  `v1.4-stt-tts` 2026-07-08, `v2-relay-merge` 2026-07-16 (= master actuel).
  Aucune branche obsolète divergente à signaler ; ce sont des jalons de version
  historiques, déjà intégrés — leur suppression est une pure question de
  ménage cosmétique (aucune ne contient de travail non fusionné), à la
  discrétion de l'utilisateur. Priorité : Mineur. Justification : pas de
  risque de perte de travail, juste du bruit dans `git branch -a`.

- **[Working tree — modifications non committées au moment de l'audit]**
  `git status --porcelain` montre `app/src/main/AndroidManifest.xml` et
  `app/src/main/java/com/hasan/v1/CapabilityExecutor.kt` modifiés (non
  committés), ainsi qu'un répertoire `reports/` non suivi (créé par cet audit
  multi-agents). Non lié à l'hygiène du dépôt à proprement parler, juste noté
  pour contexte temporel de l'audit. Priorité : Mineur.

## Hors périmètre — à signaler

- Qualité du code applicatif (agents Kotlin/Compose) : non auditée, hors
  périmètre de cet agent.
- CVE spécifiques aux dépendances listées ci-dessus : non recherchées
  (pas d'accès réseau) ; le projet dispose déjà d'un plugin
  `org.owasp.dependencycheck` configuré avec clé NVD pour cet usage — lancer
  `./gradlew dependencyCheckAnalyze` donnerait un rapport à jour.
