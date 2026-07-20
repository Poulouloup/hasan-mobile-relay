# Audit Protocole — WebSocket relay + REST/SSE hermes-webui

Périmètre couvert : enveloppe WS relay (`network/models/Envelope.kt`), démultiplexage
(`ChannelMultiplexer.kt`), cycle de vie de connexion (`ConnectionManager.kt`), handlers de
canaux (`BridgeCommandHandler.kt`, `ProactiveMessageHandler.kt`, `ChatStreamHandler.kt` —
vestige), et le transport réel du chat aujourd'hui : REST/SSE hermes-webui
(`webui/WebUiRestClient.kt`, `webui/WebUiChatStream.kt`, `webui/WebUiClarifyStream.kt`,
`webui/WebUiApprovalStream.kt`, `webui/WebUiApprovalClient.kt`, `webui/WebUiAuthStore.kt`),
ainsi que leurs points de câblage dans `MainViewModel.kt` et `WakeWordPipeline.kt`.

## Bloquant

**[app/src/main/java/com/hasan/v1/webui/WebUiClarifyStream.kt:32-96] et
[app/src/main/java/com/hasan/v1/webui/WebUiApprovalStream.kt:31-93]** Aucune reconnexion
automatique sur ces deux flux SSE longue durée. Le corps du `Thread` fait `call.execute()`
une seule fois ; toute exception réseau (coupure WiFi/4G, timeout, RST serveur) appelle
`close(e)` et termine le `Flow` définitivement — aucun `retry()`/`retryWhen()`, aucun
`while`/backoff. Côté appelant (`MainViewModel.kt:198-241`, `observeClarifyForSession` /
`observeApprovalsForSession`), le `.collect { ... }` se termine silencieusement avec le flow ;
`clarifyJob`/`approvalJob` ne sont relancés qu'à un changement de session explicite
(`ensureActiveSession()` L1169-1177, `startPendingSession()` L1198-1215, `activateSession()`
L1229-1235) — jamais sur reprise réseau ni sur reconnexion du transport chat. Un simple blip
réseau pendant qu'une approbation ou une clarification est en attente coupe silencieusement le
canal qui aurait dû en notifier l'utilisateur ; celui-ci ne verra plus jamais la demande tant
qu'il ne change pas de session ou ne relance pas l'app. Priorité : Bloquant. Justification :
pour les approbations (commandes sensibles, `tools/approval.py` côté serveur), une perte
silencieuse de ce canal peut soit bloquer indéfiniment un agent en attente d'une confirmation
que l'utilisateur ne voit jamais, soit — pire, à rapprocher de
`archive/2026-07-16-bridge-mcp-confirmation-bypass.md` qui documente déjà un cas de
confirmation contournée — élargir la fenêtre où une capability sensible échappe au garde-fou
utilisateur. Le doc-comment de `WebUiApprovalStream.kt:22-23` ("Flux jamais terminé côté
client — fermé uniquement par l'appelant ... ou une coupure réseau") reconnaît explicitement le
cas de coupure réseau sans lui apporter de réponse.

**[app/src/main/java/com/hasan/v1/webui/WebUiAuthStore.kt:43-47]** `WebUiAuthStore.clear()`
porte la doc "à appeler sur un 401 serveur confirmé (session expirée/révoquée)" mais n'est
appelée **nulle part** dans le repo (recherche exhaustive sur `authStore.clear()` — aucune
occurrence hors la définition elle-même). Aucun des clients REST/SSE webui
(`WebUiRestClient.kt`, `WebUiApprovalClient.kt`, `WebUiClarifyStream.kt`,
`WebUiApprovalStream.kt`) ne teste `response.code == 401` pour réagir spécifiquement — un 401
est traité de façon indifférenciée dans la branche générique `!response.isSuccessful` (voir
p.ex. `WebUiRestClient.kt:236-238` pour `startChat`, `WebUiRestClient.kt:160-163` pour
`listSessions`), donc comme n'importe quelle autre erreur HTTP. Conséquence : quand le cookie
`hermes_session` expire côté serveur, **toutes** les requêtes REST et **tous** les flux SSE
échouent silencieusement en boucle (log `Log.w` uniquement) sans jamais déclencher de
ré-authentification. Pire, l'indicateur d'état affiché à l'utilisateur
(`MainViewModel.kt:1101,1114` — `webUiLoggedIn = !settings.webUiSessionCookie.isNullOrBlank()`)
ne reflète que la présence locale du cookie, pas sa validité serveur : l'UI continuera
d'afficher "connecté" alors que le serveur rejette tout. Priorité : Bloquant. Justification :
contrairement au chemin WS relay qui a un traitement explicite et testé du code de fermeture
4401 (`ConnectionManager.kt:291-297` — efface le token, force le re-pairing, statut
DISCONNECTED visible), le chemin hermes-webui n'a **aucun mécanisme équivalent** alors que
c'est le vrai transport du chat depuis la migration. Un utilisateur dont la session expire
perd le chat sans diagnostic actionnable dans l'UI.

## Important

**[app/src/main/java/com/hasan/v1/webui/WebUiChatStream.kt:42-129]** Le flux SSE de chat
(`GET /api/chat/stream`) n'a lui non plus aucune reconnexion — même schéma `Thread` +
`call.execute()` unique que les deux flux ci-dessus. La différence avec le chemin WS legacy
(`ChatStreamHandler.kt`) est notable : côté WS, une coupure réseau pendant un tour est détectée
via `connectionManager.connectionStatus.drop(1).onEach { ... }` (L152-163) — mais cette
détection s'appuie sur le cycle de vie *transport* de `ConnectionManager` (qui, lui, a un vrai
backoff de reconnexion). Côté hermes-webui, il n'existe pas d'équivalent : `WebUiChatStream` ne
partage aucun état de connectivité avec un `ConnectionManager`. En pratique la conséquence est
la même côté UX pour un tour en cours : `MainViewModel.kt:781` — la coupure fait échouer le
thread SSE, le `Flow` se termine, le bloc `finally` (L959-978) détecte l'absence de
`reachedTerminal`, logue `STREAM_ORPHANED`, supprime le message assistant partiel de Room
(`messageDao.deleteById`, L965) et affiche "Connexion interrompue" — donc pas de perte
*silencieuse* pour un tour actif (bon point, filet de sécurité déjà en place, cf. commentaire
L775-778 référençant `turn=1784139600101`). Le manque est plus fin : les tokens déjà reçus
avant la coupure sont perdus (le message streaming est supprimé, pas conservé comme partiel —
contraste avec le traitement de `WebUiStreamEvent.Cancel` qui, lui, conserve le texte partiel,
L830-834). Priorité : Important. Justification : UX dégradée (perte de la réponse partielle
déjà reçue) mais pas de blocage silencieux comme pour clarify/approval — un message d'erreur
et un bouton "Réessayer" existent (mentionné en commentaire L940-944).

**[app/src/main/java/com/hasan/v1/network/ChatStreamHandler.kt:24-26]** Le doc-comment en tête
de fichier affirme : *"Fait transiter tout ce qui parle à Hermes par le canal `chat` du
WebSocket relay ... seul transport depuis le retrait du fallback HTTP/SSE"*. C'est faux dans
l'état actuel du repo : `MainViewModel.kt:184-185` instancie `WebUiChatStream` sur
`WebUiRestClient` (REST/SSE hermes-webui) et `sendToHermes()` (L665-980) l'utilise
exclusivement — `ChatStreamHandler.streamChat()` n'est appelé nulle part dans
`MainViewModel.kt` (confirmé par recherche : aucune occurrence de `chatStreamHandler` ou de
`.streamChat(` en dehors du fichier `ChatStreamHandler.kt` lui-même côté production). Le canal
`chat` du multiplexer WS n'est donc plus consommé pour le chat réel — uniquement câblé pour
compiler (`ChatStreamHandler` reste une classe valide, potentiellement du code mort complet).
Priorité : Important. Justification : ce n'est pas un bug fonctionnel (le vrai transport
fonctionne), mais un commentaire trompeur en tête d'un fichier de ~300 lignes risque
d'égarer un futur audit ou une future contribution qui s'y fierait pour comprendre
l'architecture — exactement le type de confusion que la consigne de cette mission demandait
de corriger ("ne traite pas le canal chat comme le vrai chemin de chat").

**[app/src/main/java/com/hasan/v1/network/ChannelMultiplexer.kt:29-36]** `dispatch()` ignore
silencieusement toute enveloppe dont `channel` n'est pas dans `{system, chat, proactive,
bridge}` — pas de `else -> Log.w(...)`. En pratique ce cas est aujourd'hui inatteignable car
`Envelope.fromJsonObject()` (`Envelope.kt:49-50`) rejette déjà toute enveloppe dont `channel`
n'est pas dans `Envelope.CHANNELS` (même ensemble) en amont, donc `dispatch()` ne peut jamais
recevoir de canal hors de cette liste. Le garde de `dispatch()` est donc redondant mais
inoffensif — cité pour mémoire, pas un vrai risque avec le code actuel. Priorité : Mineur au
sens strict, mais noté ici car un futur changement de `Envelope.CHANNELS` sans mise à jour
correspondante de `ChannelMultiplexer` réintroduirait un canal reçu-mais-jamais-tracé (pas de
log) — proposer d'ajouter un `else -> Log.w(TAG, "canal inconnu: ${envelope.channel}")` par
défense en profondeur.

**[app/src/main/java/com/hasan/v1/network/models/Envelope.kt:47]** `fromJsonObject()` rejette
toute enveloppe dont `version != PROTOCOL_VERSION` (comparaison stricte, pas de tolérance vers
le bas) — sans distinguer "version supérieure envoyée par un serveur mis à jour avant l'app"
de "enveloppe corrompue". Le commentaire de classe (L9-12) annonce vouloir permettre à *"faire
évoluer le protocole plus tard sans casser silencieusement la compatibilité"*, mais le
mécanisme actuel ne fait que rejeter silencieusement (retourne `null`, `ConnectionManager.kt:
264-268` logue juste un warning tronqué à 200 caractères) sans jamais indiquer côté UI qu'une
désynchronisation de version protocole a eu lieu. Un déploiement serveur qui bump
`PROTOCOL_VERSION` avant la mise à jour de l'app romprait silencieusement toute communication
sur le canal WS (chat legacy compris) sans qu'aucun message d'erreur explicite n'atteigne
l'utilisateur — seulement des enveloppes ignorées une par une. Priorité : Important.
Justification : ce garde-fou de versioning existe mais son échec est un no-op silencieux côté
utilisateur plutôt qu'une erreur actionnable ; comportement serveur réel non vérifiable depuis
ce repo (pas d'accès à `server/relay/server.py` / `envelope.py` runtime).

**[app/src/main/java/com/hasan/v1/network/ConnectionManager.kt:212-216]** `send()` retourne
`false` si `webSocket` est `null` (non connecté), et cette valeur est bien vérifiée par les
appelants directs (`ChatStreamHandler.kt:191-195` pour l'envoi initial,
`BridgeCommandHandler.kt:120-121` juste pour logguer `sendOk`). En revanche il n'existe **aucun
buffer/file d'attente** pour les enveloppes qui échouent à l'envoi pendant une fenêtre de
reconnexion (`RECONNECTING`) : un `send()` pendant le backoff est simplement perdu, à la charge
de l'appelant de retenter ou non. Pour `BridgeCommandHandler.respond()` (L120), si l'envoi de
la réponse à une commande bridge échoue faute de connexion, la réponse au serveur relay est
perdue silencieusement (seul un `LatencyLog.mark` local en garde trace, rien n'est renvoyé au
serveur) — le serveur, de son côté, attend potentiellement une réponse qui ne viendra jamais
tant qu'il n'a pas son propre timeout (comportement serveur non vérifiable depuis ce repo).
Priorité : Important. Justification : capability bridge (SMS, localisation...) exécutée avec
succès côté app mais dont le résultat n'atteint jamais Hermes — silencieux des deux côtés du
point de vue de l'utilisateur.

## Mineur

**[app/src/main/java/com/hasan/v1/network/ConnectionManager.kt:53-58]** Constantes de backoff
cohérentes et documentées : `BACKOFF_INITIAL_MS=1s`, doublement (`shl attemptCount`,
L330) jusqu'à `BACKOFF_MAX_MS=5min` après `BACKOFF_MAX_ATTEMPTS_BEFORE_CAP=20` tentatives — la
logique elle-même (L321-342) est correcte : `attemptCount` est remis à zéro sur `onOpen`
(L251) et sur `connect()` explicite (L131), le job de reconnexion précédent est annulé avant
d'en programmer un nouveau (L337 `reconnectJob?.cancel()`), et le cas `immediate=true` (bascule
réseau proactive, L177-186) court-circuite le délai sans consommer de tentative. Aucun bug
identifié ici — mentionné seulement parce que la valeur `1_000L shl 20` (si jamais
`BACKOFF_MAX_ATTEMPTS_BEFORE_CAP` était augmentée sans revoir le `coerceAtMost`) resterait
protégée par `coerceAtMost(BACKOFF_MAX_MS)`, donc pas de risque d'overflow réel avec les
constantes actuelles.

**[app/src/main/java/com/hasan/v1/network/ConnectionManager.kt:305-311]** `onFailure` ne
distingue pas les causes d'échec (DNS, TLS/certificat rejeté, timeout, reset) avant de
programmer un `scheduleReconnect()` générique — un échec TLS permanent (ex: certificat
totalement invalide, hors du chemin TOFU normal) entraînerait un cycle de reconnexion infini à
`BACKOFF_MAX_MS` plutôt qu'un état d'erreur distinct proposant à l'utilisateur de re-pairer.
Le TOFU (`CertPinStore`) est explicitement hors périmètre de cet audit (agent Cyber), donc ce
point n'est noté qu'au niveau "boucle de reconnexion ne s'arrête jamais d'elle-même sur une
erreur non transitoire" — comportement peut-être voulu (laisser l'utilisateur couper/re-pairer
manuellement) plutôt qu'un bug.

**[app/src/main/java/com/hasan/v1/network/ProactiveMessageHandler.kt:40-54]** Le flow
`multiplexer.proactive.collect` n'a pas de gestion d'erreur explicite, mais
`MutableSharedFlow` ne "termine" jamais sur son propre chef (contrairement aux `callbackFlow`
SSE ci-dessus) — pas de risque de flow mort ici, c'est `ConnectionManager` qui gère la
reconnexion transport en amont. Aucun problème identifié, cité pour complétude (un des quatre
canaux, bien couvert).

**[app/src/main/java/com/hasan/v1/network/models/Envelope.kt:57]** Si `id` est absent ou vide
dans une enveloppe reçue, un nouvel UUID est généré côté client (`fromJsonObject`, L57) — pour
une enveloppe de *réponse* serveur, cela casserait silencieusement toute corrélation
`sendAndAwait`/`filter { it.id == envelope.id }` (`ChatStreamHandler.kt:277`). Cas
vraisemblablement jamais rencontré en pratique si le serveur echo toujours l'`id` de la requête
(comportement serveur non vérifiable depuis ce repo), mais le filet de secours masquerait le
problème plutôt que de le signaler si jamais un jour le serveur omettait ce champ.

## Hors périmètre — à signaler

- Sécurité du transport TLS/TOFU et gestion des tokens (`CertPinStore`, `SessionTokenStore`,
  pinning, chiffrement du stockage) — laissé à l'agent Cyber.
- Code Android natif hors réseau (STT/TTS, wake word, permissions runtime, cycle de vie
  Activity/Fragment) — laissé à l'agent Kotlin.
