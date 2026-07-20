# Audit Kotlin — Code métier

Périmètre couvert : `MainViewModel.kt`, `CapabilityExecutor.kt`, `ConnectionManager.kt`,
`BridgeCommandHandler.kt`, `PairingManager.kt`, `SessionTokenStore.kt`,
`HassanWakeWordService.kt`, `HassanNotificationService.kt`, `HassanTtsManager.kt`,
`EdgeTtsEngine.kt`, `BargeInListener.kt`, `VoicePlayer.kt`, `SpeechRecognizerManager.kt`,
`WebUiRestClient.kt`, `WebUiChatStream.kt`, `WebUiClarifyStream.kt`, `WebUiApprovalStream.kt`,
`WebUiClientHolder.kt`, `MainActivity.kt`, `ConversationFragment.kt`, `SettingsFragment.kt`,
`QrScannerActivity.kt`, et grep exhaustif `!!` / `lateinit var` / `catch` vides sur tout le
package `com.hasan.v1`.

## Bloquant

Aucun trouvé. Le cœur métier (MainViewModel, ConnectionManager, PairingManager,
SessionTokenStore, CapabilityExecutor, BridgeCommandHandler) est propre : StateFlow partout,
`onCleared()`/`onDestroy()` correctement implémentés, pas de fuite de coroutine active
identifiée dans les chemins critiques.

## Important

**[app/src/main/java/com/hasan/v1/CapabilityExecutor.kt:156-179]** `requestFreshLocation` /
`get_location` : les `LocationListener` sont bien retirés via `cleanup()` sur réception
d'une position OU sur annulation (`cont.invokeOnCancellation`), mais **pas sur le timeout
implicite du `withTimeoutOrNull`** dans le cas où celui-ci ne déclenche PAS l'annulation de
la coroutine interne avant que `requestSingleUpdate` n'ait fini de s'enregistrer (fenêtre de
course théorique entre l'expiration du timeout et l'enregistrement effectif du listener côté
`LocationManager`, sur un provider lent à répondre). En pratique `withTimeoutOrNull` annule
bien la coroutine interne à l'expiration, ce qui déclenche `invokeOnCancellation` → `cleanup()`
→ tous les listeners sont retirés correctement. **Pas un bug confirmé** après relecture
attentive — dégradé en `Important` uniquement parce que get_location vient d'être ajouté et
mérite un test manuel en conditions réelles (provider GPS qui répond juste après un timeout,
sur un device réel) pour confirmer qu'aucun listener ne reste enregistré au-delà de l'appel.
Priorité : Important. Justification : composant neuf touchant une ressource système
(LocationManager) dans un service qui peut tourner longtemps en fond — à vérifier
manuellement plutôt que de faire confiance à la seule lecture de code sur ce point précis.

**[app/src/main/java/com/hasan/v1/SettingsFragment.kt:287,295,303,311,336]** Plusieurs
`lifecycleScope.launch { ... }` (scope du **Fragment**, pas de la vue) enveloppent des appels
suspend one-shot (`profilesClient.listProfiles()`, `mcpClient.listServers()`,
`webUiRestClient.login()`, etc.) dont le résultat est écrit dans des `mutableStateOf` qui
alimentent un `ComposeView` lié à `viewLifecycleOwner`. Si la vue du Fragment est détruite
(navigation vers un autre onglet la garde en fait vivante ici via `hide()`, mais un futur
refactor vers `replace()` ou un retour arrière la détruirait) pendant qu'une de ces coroutines
est en vol, l'écriture sur l'état Compose survient après la destruction de la vue — pas un
crash immédiat (mutableStateOf ne référence pas la vue), mais un état incohérent est possible
si le Fragment est recréé entre-temps. Le reste du fichier utilise correctement
`viewLifecycleOwner.lifecycleScope` + `repeatOnLifecycle` (ligne 366-368) pour les flows.
Priorité : Important. Justification : incohérence de pattern au sein du même fichier —
`.claude/rules/android.md` n'impose `repeatOnLifecycle` que pour les flows, donc ce n'est pas
une violation stricte de la règle écrite, mais l'usage de `lifecycleScope` (Fragment) au lieu
de `viewLifecycleOwner.lifecycleScope` pour du code qui touche l'UI est l'anti-pattern classique
documenté par Google lui-même.

## Mineur

**[app/src/main/java/com/hasan/v1/audio/BargeInListener.kt:58,115-137]** Le `scope =
CoroutineScope(Dispatchers.Default + SupervisorJob())` construit à l'initialisation de la
classe n'est jamais annulé (`scope.cancel()`) — seul le `listenJob` enfant l'est dans `stop()`.
Comme `BargeInListener` est une propriété unique de `MainViewModel` (durée de vie = durée de
vie du ViewModel), ce n'est pas une fuite mémoire réelle (rien ne retient de référence
supplémentaire au `scope` au-delà de la durée de vie de l'objet, donc le GC le récupère
normalement à la destruction du ViewModel) — mais le `Job` racine reste actif tant que
l'objet vit, ce qui est incohérent avec le commentaire de code sous-entendant un cycle de vie
géré. Même motif exact dans **[EdgeTtsEngine.kt:53,198-207]** (`scope` jamais cancel(), seul
`pipelineJob` l'est dans `release()`) et **[HassanNotificationService.kt:47,89-92]**
(`serviceScope` jamais cancel() dans `onDestroy()`, seul `pollingJob` l'est — sans
conséquence pratique ici car `startPolling()` est un no-op, voir Hors périmètre ci-dessous
n'applique pas, c'est bien du Kotlin métier mort). Priorité : Mineur. Justification : pas de
fuite active confirmée (aucune référence externe ne retient ces scopes après libération de
l'objet propriétaire), mais absence de `scope.cancel()` explicite est un pattern à corriger par
hygiène — un futur ajout de coroutine lancée directement sur `scope` (plutôt que sur le
`Job` suivi) échapperait silencieusement à l'arrêt.

**[app/src/main/java/com/hasan/v1/HassanWakeWordService.kt:179,187]** `engine!!.detections.collect`
et `engine!!.start()` dans `startEngine()` : le `!!` est sûr ici (assignation `engine = ...`
juste au-dessus, même bloc), mais casse le style "pas de `!!` non justifié" du reste du fichier.
`engine?.let { ... }` ou capture locale (`val e = engine; e.detections...`) éviterait le `!!`.
Priorité : Mineur. Justification : cosmétique, aucun risque de NPE réel dans le flux actuel.

**[app/src/main/java/com/hasan/v1/SkillsFragment.kt:48]** `state.selectedSkillName!!` à
l'intérieur d'un `if (state.selectedSkillName != null)` — le smart-cast Kotlin devrait
normalement suffire sans `!!` explicite (`state` est un `val` local stable venant de
`collectAsState()`). Redondant mais sans risque. Priorité : Mineur.

**[app/src/main/java/com/hasan/v1/webui/WebUiChatStream.kt:50-124]**,
**[WebUiApprovalStream.kt:39-90]**, **[WebUiClarifyStream.kt:40-93]** : les trois clients SSE
démarrent un `Thread` brut (`isDaemon = true`) pour faire la lecture bloquante OkHttp plutôt
que d'utiliser un `Dispatchers.IO` coroutine-natif (ex: lecture bloquante dans un
`withContext(Dispatchers.IO)` directement dans le `callbackFlow`, sans Thread manuel). Le
pattern actuel fonctionne (le thread est daemon, `awaitClose { call.cancel() }` débloque bien
la lecture en fermant la connexion HTTP sous-jacente) mais duplique la même mécanique de
parsing SSE ligne par ligne trois fois quasi identiquement (voir aussi la note "Qualité
générale" ci-dessous). Priorité : Mineur. Justification : pas un bug, mais un thread manuel
par flux ouvert (potentiellement plusieurs simultanés : chat + clarify + approval par session)
alors que le pool `Dispatchers.IO` existe déjà pour ça.

**[Duplication : WebUiChatStream.kt / WebUiApprovalStream.kt / WebUiClarifyStream.kt]** Les
trois fichiers dupliquent presque à l'identique : construction de la requête SSE avec cookie,
lancement du Thread daemon, boucle de parsing `event:`/`data:`/ligne vide, gestion
`awaitClose { call.cancel() }`. Seul le `parseEvent()` final diffère. Une factorisation dans
une classe utilitaire commune (`SseStreamReader` générique paramétré par le parseur d'événement)
réduirait la surface de maintenance — un bugfix sur le parsing SSE générique (ex: gestion d'un
`retry:` ou d'un multi-line `data:`) doit actuellement être répété trois fois. Priorité :
Mineur. Justification : duplication de logique confirmée par lecture directe, pas un risque
fonctionnel immédiat.

**[Gestion d'erreur incohérente, plusieurs fichiers]** Le style d'erreur est globalement
cohérent (retour de sealed class `Result`/`null` + `Log.w`/`LatencyLog.mark`, jamais
d'exception avalée sans trace), mais quelques asymétries mineures :
- `PairingManager.kt` et `WebUiRestClient.kt` utilisent systématiquement
  `catch (e: Exception) { Log.w/e(...); <ErrorResult> }` avec message détaillé.
- `CapabilityExecutor.kt:64-66` (`executeInternal`) utilise un unique `catch (e: Exception)`
  générique pour les 11 capabilities, qui remonte `e.message` brut au LLM via le bridge — cohérent
  avec le reste (pas de perte du message), mais aucune capability individuelle ne peut
  distinguer son erreur (ex: `SecurityException` sur `sendSms` vs `IOException` sur
  `getContacts` remontent la même forme `CapabilityResult.Error(message)`). Fonctionnellement
  correct, juste moins granulaire que les sealed classes utilisées ailleurs (ex:
  `PairingResult`, `WebUiSteerResult`).
Priorité : Mineur. Justification : cohérence generale bonne, écart mineur de granularité sans
impact utilisateur observé.

**[app/src/main/java/com/hasan/v1/HassanNotificationService.kt:57-68,95-99]** `httpClient`
(lazy, avec un `X509TrustManager` `trustAll` qui accepte tout certificat sans vérification) est
construit mais jamais utilisé : `startPolling()` est un no-op explicite (commentaire ligne 95-96
: "L'endpoint /api/sessions/{id}/stream n'existe pas sur ce Hermes. Le polling est désactivé").
Code mort avec, accessoirement, un `TrustManager` qui désactive toute vérification TLS s'il
venait à être réactivé sans revue. Priorité : Mineur (code mort, hors service actuellement) —
signalé ici plutôt qu'en Important car aucun chemin d'exécution actuel ne l'atteint, mais à
garder en tête si le polling est un jour réactivé (le `trustAll` devrait alors être remplacé
par le même TOFU que `ConnectionManager`/`WebUiRestClient`).

## Hors périmètre — à signaler

- Composants visuels Compose (recomposition, state hoisting, structure des `Screen`/`*Ui`
  data classes dans `ui/screens/`, `ui/components/`) — non audités, relèvent de l'agent Compose.
- Design visuel (thème, couleurs, formes `HasanShapes`/`HasanColors`) — non audité, relève de
  l'agent UI.
- Sécurité (TrustManager `trustAll` de `HassanNotificationService`, hostname verifier `{ _, _
  -> true }` répété dans `ConnectionManager`/`PairingManager`/`WebUiRestClient` — TOFU
  volontaire documenté, mais mérite un regard sécurité dédié) — mentionné ci-dessus car
  rencontré en cours de lecture, mais l'analyse sécurité complète n'est pas dans le périmètre
  de cet agent Kotlin/qualité.
