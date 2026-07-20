# Audit Sécurité

Périmètre couvert : TOFU/certificate pinning (CertPinStore, ConnectionManager,
PairingManager, WebUiRestClient, HassanNotificationService), stockage des
tokens (SettingsManager, SessionTokenStore, WebUiAuthStore), rate limiting
côté app, logs sensibles (LatencyLog + réseau), exposition des routes,
network_security_config.xml, révocation des certificats de confiance,
autorisation des capabilities sensibles (BridgeCommandHandler, Capability.kt,
CapabilityExecutor.kt).

Repo local uniquement, lecture seule, pas d'accès SSH au VPS (34.155.193.170).
Branche auditée : `webui-migration` (HEAD au moment de l'audit), avec deux
fichiers modifiés non commités (`AndroidManifest.xml`, `CapabilityExecutor.kt`)
— diffs examinés, voir section correspondante.

---

## Bloquant

**[app/src/main/java/com/hasan/v1/HassanNotificationService.kt:51-67]**
`HassanNotificationService` construit son propre `OkHttpClient` avec un
`X509TrustManager` nommé `trustAll` dont `checkServerTrusted()` est un no-op
total (corps vide, ligne 53), combiné à `hostnameVerifier { _, _ -> true }`
(ligne 63). Contrairement à `CertPinStore.TofuTrustManager` (qui fait un vrai
TOFU : compare un fingerprint SHA-256 stocké et lève une alerte au premier
mismatch), ce trust manager **accepte inconditionnellement n'importe quel
certificat, de n'importe quel host, sans jamais vérifier fingerprint ou
CA**. Aucun appel à `SettingsManager`/`CertPinStore` dans ce fichier — le nom
même de la variable (`trustAll`) indique que ce n'est pas un TOFU partiel
mais une désactivation complète de la validation TLS.
Priorité : **Bloquant**.
Justification : ce `httpClient` est utilisé par le service pour toute requête
réseau qu'il émettrait (SSE polling, actuellement désactivé en pratique —
voir ligne 97-99, `startPolling()` est un no-op car "l'endpoint
`/api/sessions/{id}/stream` n'existe pas sur ce Hermes"). Le risque n'est donc
pas actif tant que `startPolling()` reste un stub, mais le `httpClient` reste
instancié (`by lazy`, donc construit dès le premier accès) et prêt à l'emploi
: toute réintroduction future du polling SSE (ou tout autre usage de ce
client) exposerait l'app à un MITM total et silencieux sur ce chemin,
sans aucune UI de détection (contrairement à `certCheckEvents` côté
`ConnectionManager`). À corriger immédiatement : soit supprimer ce
`trustAll`/`httpClient` mort tant qu'inutilisé, soit le remplacer par le même
`CertPinStore` TOFU que les autres clients (namespace dédié, ex. "notif").

## Important

**[app/src/main/java/com/hasan/v1/network/ConnectionManager.kt:83]** et
**[app/src/main/java/com/hasan/v1/auth/PairingManager.kt:273]** et
**[app/src/main/java/com/hasan/v1/webui/WebUiRestClient.kt:64]**
Les trois clients HTTP "légitimes" (relay WS, pairing HTTP, webui REST)
utilisent tous `hostnameVerifier { _, _ -> true }` — la vérification du
hostname du certificat contre l'URL cible est désactivée. Ce n'est *pas* une
faille isolée comme celle de `HassanNotificationService` : la sécurité repose
ici entièrement sur le TOFU fingerprint-based de `CertPinStore` (le
hostname n'a pas besoin d'être vérifié séparément si le fingerprint exact du
certificat est pinné et vérifié à chaque connexion). C'est un design
défendable pour du TOFU auto-hébergé (cas typique : IP littérale sans nom de
domaine stable, certificat auto-signé — cohérent avec
`archive/2026-07-16-webui-migration-issues.md:19-31` qui documente que le
relay est joint par IP littérale `34.155.193.170` sans SNI).
Priorité : **Important** (pas Bloquant, car le fingerprint pinning compense
en théorie — mais c'est un point de fragilité qui mérite d'être documenté
explicitement en commentaire à chaque usage, ce qui n'est fait qu'en partie).
Justification : si un bug venait à faire échouer silencieusement l'appel à
`checkServerTrusted` (ex. exception avalée, chain vide — voir
`CertPinStore.kt:46` `if (chain.isEmpty()) return` qui laisse
`lastCheckResult` à sa valeur par défaut `TrustedBySystem` sans lever
d'exception), la désactivation totale du hostname verifier ne fournirait
aucune deuxième ligne de défense. Recommandation : ajouter un commentaire
explicite à chaque `hostnameVerifier { _, _ -> true }` légitime expliquant
pourquoi c'est sûr ici (fingerprint pinning), pour le distinguer clairement
d'un `trustAll` comme celui de `HassanNotificationService` — actuellement
rien ne différencie visuellement les deux dans le code lu isolément.

**[app/src/main/java/com/hasan/v1/auth/CertPinStore.kt:46]**
`checkServerTrusted()` : `if (chain.isEmpty()) return` — si la chaîne de
certificats reçue est vide, la fonction retourne sans lever d'exception ET
sans mettre à jour `lastCheckResult`, qui garde sa valeur initiale
`CertCheckResult.TrustedBySystem` (ligne 40). Un chaîne vide est un cas
anormal (ne devrait pas arriver après un handshake TLS réussi), mais si un
serveur malveillant ou un proxy MITM réussissait à produire ce cas, l'appelant
verrait `TrustedBySystem` — la valeur la plus permissive — sans qu'aucune
vérification n'ait réellement eu lieu.
Priorité : Important.
Justification : scénario a priori difficile à déclencher en pratique (OkHttp
ne devrait pas invoquer `checkServerTrusted` avec une chaîne vide dans un
flux TLS normal), mais le comportement par défaut du champ `lastCheckResult`
devrait être le plus restrictif possible (ex. un état "Unknown"/erreur) plutôt
que le plus permissif, par principe de sécurité "fail closed".

**[Pas de rate limiting / lockout côté app sur le pairing]**
`MainViewModel.kt:1363-1383` (`handlePairingResult`) : un
`PairingResult.ServerRejected` (code invalide/expiré, HTTP non-200) ou un
`NetworkError` affiche juste un message d'erreur (`updateState { copy
(errorMessage = ...) }`) et laisse l'app dans un état permettant de relancer
`pairFromQr()`/`pair()` immédiatement, sans délai ni compteur de tentatives
côté client. Comparer avec `ConnectionManager.scheduleReconnect()` (lignes
321-342) qui implémente un vrai backoff exponentiel — rien d'équivalent
n'existe dans `PairingManager`/`MainViewModel` pour les tentatives de pairing.
Priorité : Important.
Justification : un attacker (ou un bug UI en boucle) pourrait spammer
`/pairing/register` sans aucun frein côté client. `archive/2026-07-16-
webui-migration-issues.md:33-37` mentionne un `fail2ban` configuré côté VPS
pour `hermes-webui` (login 401/403, `maxretry=5`), mais rien de documenté
côté repo pour l'endpoint `/pairing/register` du relay lui-même — **comportement
serveur non vérifiable depuis ce repo, à confirmer par audit serveur séparé**.
Recommandation côté app : ajouter un délai croissant ou un compteur de
tentatives échouées consécutives, en défense en profondeur même si le serveur
protège déjà cette route.

## Mineur

**[app/src/main/java/com/hasan/v1/utils/LatencyLog.kt:56-69]**
`LatencyLog.mark()` écrit en clair dans Logcat (`Log.d`) ET dans un fichier
persistant `files/logs/latency.log` (accessible via `adb pull` en debug, ou
par l'app elle-même). `BridgeCommandHandler.kt:50` logue
`"capability=$capability params=$params"` — les `params` d'une capability
`send_sms` incluent le **numéro de téléphone destinataire et le contenu du
SMS en clair** (voir `Capability.kt:27-31`, params `to`/`message`), et ceux de
`get_contacts` incluent le filtre de recherche. Ligne 82 :
`LatencyLog.mark("BRIDGE_SUCCESS", ..., "data=${result.data}")` logue aussi le
résultat complet, donc pour `get_contacts` la liste de contacts
(nom+numéro) transiterait en clair dans `latency.log` si cette capability
était déclenchée.
Priorité : Mineur (pas Bloquant/Important : ce fichier reste local au
sandbox de l'app, `files/` n'est pas accessible aux autres apps sur un device
non rooté avec `targetSdk` récent, et `adb pull` nécessite un accès physique
+ debug USB activé ou root). Mais reste un risque réel en cas de device
compromis, USB debugging activé, ou vieux device avec permission
`READ_LOGS` accordée à une autre app.
Justification : contenu de conversation/PII (numéros de téléphone, contacts,
messages SMS) ne devrait idéalement jamais apparaître en clair et en
permanence dans un fichier de log, même local — surtout que le commentaire du
fichier (`LatencyLog.kt:28-29`) indique lui-même que ce mécanisme est
temporaire ("à retirer une fois la latence/jitter Hermes identifiée") mais
reste actif et non expurgé de PII.

**[app/src/main/java/com/hasan/v1/SettingsManager.kt:266-268]**
`webUiSelectedModel` (modèle LLM sélectionné) est stocké dans les
`SharedPreferences` non chiffrées (`prefs`), pas dans `encryptedPrefs`. Ce
n'est pas un secret, donc conforme à la politique documentée en tête de
fichier (ligne 8-13 : "URL serveur, tokens, certificats" chiffrés, "toggles,
sliders" non chiffrés) — signalé seulement pour täche de vérification
croisée, aucune action requise. Confirmé non sensible.

**[app/src/main/java/com/hasan/v1/auth/CertPinStore.kt:110-116]**
`storageKeyFor()` utilise MD5 (pas cryptographiquement sûr, mais utilisé ici
uniquement comme fonction de hachage pour dériver une clé de stockage stable,
pas pour une propriété de sécurité) — usage correct, MD5 est ici un simple
identifiant, pas un mécanisme de protection. Aucune action requise, mentionné
pour transparence de l'audit.

## Hors périmètre — à signaler

- Qualité générale du code non liée à la sécurité : non auditée (hors
  périmètre de cet agent).
- Le diff non commité de `AndroidManifest.xml` (ajout d'un bloc `<queries>`
  pour la visibilité des packages, capability `discover_apps`/`launch_app`)
  et de `CapabilityExecutor.kt` (passage de `getLocation()` en suspend avec
  timeout 10s + requête parallèle GPS/réseau) ont été examinés : aucun des
  deux ne touche au mécanisme d'autorisation/confirmation des capabilities
  sensibles (`BridgeCommandHandler.isCapabilityAuthRequired` reste
  inchangé et appelé en amont de `CapabilityExecutor.execute()`, voir
  `BridgeCommandHandler.kt:70-78`). Pas de régression de sécurité détectée
  dans ces deux diffs.
- Investigation du bug "bypass de confirmation SMS" documenté dans
  `.claude/CLAUDE.md` (`archive/2026-07-16-bridge-mcp-confirmation-bypass.md`)
  : **ce fichier n'existe pas dans le checkout actuel** (seul
  `archive/2026-07-16-webui-migration-issues.md` est présent — `archive/`
  n'est pas versionné dans git, confirmé par `git status archive/` =
  "nothing to commit, working tree clean" alors que CLAUDE.md le référence).
  Impossible de consulter le détail de cet incident documenté. Le code actuel
  de `BridgeCommandHandler.kt:38-94` montre en revanche un commentaire
  explicite (lignes 45-50) faisant référence à cet historique : *"le chemin
  send_sms qui semblait ne jamais atteindre ce handler"* — et ajoute un log
  exhaustif (`LatencyLog.mark("BRIDGE_COMMAND", ...)`, ligne 50) précisément
  pour diagnostiquer ce cas. La logique de confirmation elle-même (lignes
  69-78 : `isCapabilityAuthRequired` → `requestConfirmation` suspendu →
  refus si `!authorized`) est cohérente et semble correctement câblée côté
  app — voir aussi `MainViewModel.kt:1029-1044`
  (`requestBridgeConfirmation`/`respondToBridgeConfirmation`, dialog réel
  dans `ConversationFragment.kt:302-303`, pas d'auto-approve trouvé). **Le
  risque documenté portait spécifiquement sur un contournement possible côté
  serveur MCP (`~/.hermes/phone-relay-mcp/server.js`) qui court-circuiterait
  le canal `bridge` WS existant — ceci est hors de portée de ce repo Android
  et ne peut pas être confirmé ni infirmé depuis le code client seul.**

## Non vérifiable depuis ce repo (nécessite audit serveur séparé)

- **Rate limiting réel sur `/pairing/register` et `/pairing/refresh`** (relay
  WS, `server/relay/pairing.py` côté serveur, non présent dans ce repo
  Android) — le commentaire `PairingManager.kt:6-8` et
  `SessionTokenStore.kt:12-16` y fait référence mais le comportement réel
  (fail2ban, throttling applicatif) n'est pas vérifiable ici.
- **`archive/2026-07-16-bridge-mcp-confirmation-bypass.md`** — le risque de
  contournement du canal `bridge` par le serveur MCP `phone-relay-mcp/
  server.js` ne peut être confirmé/infirmé sans lire ce serveur MCP
  (hors du repo Android).
- **Exposition publique réelle des routes** — le code Android référence des
  URLs construites dynamiquement depuis `relayServerUrl`/`webUiServerUrl`
  configurés par pairing (pas d'URL "localhost" codée en dur trouvée dans le
  code de prod — seul `network_security_config.xml:7` autorise le cleartext
  pour `10.200.0.2`, une IP privée probablement utilisée en dev). Impossible
  de confirmer depuis ce repo si toutes les routes `/api/*` accessibles
  publiquement depuis `34.155.193.170` sont réellement destinées à l'être, ou
  si certaines (ex. `/pairing/create` qui nécessite `RELAY_ADMIN_TOKEN` selon
  `archive/2026-07-16-webui-migration-issues.md:653-656`) devraient être
  restreintes réseau côté serveur (Caddy/firewall) plutôt que par un simple
  token applicatif.
- **Expiration automatique des certificats TOFU approuvés** —
  `SettingsManager.getAllTrustedCerts()`/`removeTrustedCertFingerprint()`
  permettent une révocation **manuelle** (l'utilisateur doit trouver l'écran
  de gestion des certificats et agir explicitement — pas vérifié si cet écran
  existe et est accessible dans l'UI actuelle, recherche non faite car hors
  périmètre réseau strict). Aucune expiration automatique par date n'existe
  dans `CertPinStore`/`SettingsManager` — un fingerprint approuvé une fois
  reste valide indéfiniment (pas de TTL). C'est cohérent avec le modèle TOFU
  classique (SSH-like), pas une faille en soi, mais signalé car la question
  posée dans le brief demandait explicitement de vérifier ce point : **confirmé
  factuellement dans le code — pas d'expiration automatique, révocation
  disponible mais seulement manuelle via `SettingsManager`**, s'il existe un
  écran UI qui expose ces méthodes (non vérifié dans le périmètre de cet
  audit réseau/crypto).
