# Audit Feature Gaps — Attentes assistant personnel

Référence des attentes initiales : `README.md` (racine du repo), sections
Philosophy / Architecture / Features / Roadmap. Le README affirme
explicitement, ligne 47 du diagramme d'architecture :
`[UI] Real-time chat bubbles (Compose) [TTS] speaks once streaming completes`
— c'est-à-dire que le TTS n'est censé démarrer qu'à la fin du streaming, un
choix de conception assumé et documenté, pas un oubli. Le graphe Graphify
(`graphify-out/GRAPH_REPORT.md`) confirme `MainViewModel` comme god node
central (73 edges) et liste les features comme "done" dans le roadmap
(Clarify, sessions, bridge, Light Mode) sans mention de proactivité
programmée (rappels, tâches de fond) au-delà du transport `proactive`.

---

## Écarts majeurs

**[app/src/main/java/com/hasan/v1/MainViewModel.kt:867-879 vs app/src/main/java/com/hasan/v1/WakeWordPipeline.kt:157-267]**
Incohérence de latence perçue du TTS entre les deux pipelines de l'app.
Dans le pipeline **arrière-plan** (`WakeWordPipeline.kt`), le TTS est bien
streamé phrase par phrase : `processTokenForTts()` (ligne 245) accumule les
tokens et appelle `ttsManager.speak(chunk)` dès qu'une frontière de phrase
(`. `, `! `, `? `, `, `) ou un seuil de 5 tokens est atteint (ligne 157-160),
avec un `flushTtsBuffer()` final au `Done` (ligne 162-164). Dans le
pipeline **premier plan** (`MainViewModel.kt`, celui utilisé par
`ConversationFragment` et `LightModeFragment`/Light Mode, le mode
"conversation active" de l'utilisateur), les fonctions strictement
identiques `processTokenForTts()` (ligne 1062) et `flushTtsBuffer()`
(ligne 1079) existent mais **ne sont jamais appelées** — `WebUiStreamEvent.Token`
(ligne 867-870) se contente de mettre à jour l'état UI texte, et
`ttsManager.speak(responseText)` n'est déclenché qu'une seule fois, sur le
texte complet, dans la branche `WebUiStreamEvent.Done` (ligne 879).
Impact : quand l'utilisateur est devant l'app (cas d'usage le plus fréquent,
y compris en Light Mode mains libres), Hasan reste silencieux pendant
*toute* la génération de la réponse (peut durer plusieurs secondes avec un
modèle qui réfléchit/utilise des outils) puis débite la réponse entière
d'un coup — alors que la même app, en tâche de fond suite à un wake word,
le fait déjà correctement phrase par phrase. C'est l'écart le plus visible
entre "ça marche techniquement" et "ça donne l'impression d'un assistant
qui vous parle en temps réel" : le code pour le faire correctement existe
déjà dans le repo, dupliqué à l'identique, mais n'est câblé que sur un des
deux chemins.

**[app/src/main/java/com/hasan/v1/network/ProactiveMessageHandler.kt (fichier entier) — jamais instancié]**
Le mécanisme "notification proactive quand l'app est en arrière-plan" est
entièrement écrit (gère bien la distinction premier-plan/arrière-plan,
construit un `NotificationChannel` dédié `hasan_proactive_v1`, gère le tap
vers `MainActivity`) mais la classe `ProactiveMessageHandler` n'apparaît
nulle part comme instanciée (`grep -r "ProactiveMessageHandler("` ne
retourne que sa propre définition). Confirmé par grep exhaustif sur tout
`app/src/main/java/com/hasan/v1`. Le canal `proactive` du WS bridge est
bien collecté par `MainViewModel` (ligne 166-169), mais uniquement pour
écrire une ligne dans `activityLog` (`"Notification proactive : ${envelope.type}"`,
tag `PUSH`) — pas d'affichage de bulle, pas de TTS, pas de notification
Android si l'app n'est pas au premier plan sur ce chemin précis (à
distinguer du chemin séparé `HassanNotificationService.notifyMessage()`
qui, lui, notifie bien mais seulement en réponse à un tour de chat déjà en
cours, cf. écart notable ci-dessous).
Impact : si le serveur relay pousse effectivement quelque chose sur le
canal `proactive` (le endpoint `POST /phone/message` existe côté
`server/relay/server.py:202-226` et est fonctionnel), l'utilisateur ne
verra ni notification ni bulle si l'app est en arrière-plan à ce
moment-là — le message n'existe que dans le journal d'activité (écran
"Logs"), invisible tant que l'utilisateur ne va pas le consulter
explicitement.

**[absent du code — proactivité "produit" non trouvée]**
Grep exhaustif de `server/`, `plugin/`, `app/src/main/java/com/hasan/v1` pour
tout mécanisme de rappel programmé, tâche de fond planifiée (cron, WorkManager
métier, scheduler) déclenchant un message *sans sollicitation utilisateur
préalable dans la même session de conversation* : aucun trouvé. Le seul
mécanisme qui pousse vers le canal `proactive`/`phone/message` est
`plugin/hasan_delivery/adapter.py`, un adapter de type "canal de
messagerie" pour l'agent Hermes générique (`send_message_tool`,
`_standalone_send` ligne 350-385, commentaire ligne 359 : "Envoi hors-process
pour cron / send_message_tool"). C'est une infrastructure de *transport*
générique (comme un connecteur Telegram/Discord le serait), pas une
capacité produit Hasan de suivi de tâches ou de rappels. Rien dans le repo
n'implémente la logique métier qui déciderait "il faut relancer
l'utilisateur maintenant" — cette décision est entièrement déléguée à un
cron/outil externe non présent dans ce repo.
Impact : l'ambition "capacité proactive" affichée dans le brief (notifications
initiées par l'assistant) repose entièrement sur une pièce externe
(configuration cron côté Hermes, hors de ce dépôt) et n'est donc ni visible
ni testable dans le code audité — et le seul chemin d'affichage disponible
pour ces messages a un trou (voir écart ci-dessus).

---

## Écarts notables

**[app/src/main/java/com/hasan/v1/MainViewModel.kt:97-102]**
Après la fin du TTS, le wake word (service background) n'est réactivé
qu'après un `delay(1_500)` fixe (1,5 s), en plus des ~600 ms de silence déjà
appliqués lors de la détection d'un nouveau wake word par-dessus un TTS en
cours (`onWakeWordDetected()`, ligne 382). Cumulé au fait que le TTS ne
commence qu'après la fin complète du streaming (écart majeur ci-dessus),
la fenêtre entre "fin de la réponse affichée à l'écran" et "le micro
écoute à nouveau pour de vrai" peut atteindre plusieurs secondes. Le
barge-in (voir point positif ci-dessous) compense partiellement ce délai
*pendant* que Hasan parle, mais pas ce délai de 1,5 s après qu'il ait fini.
Impact : dans un usage mains libres soutenu (questions enchaînées), l'app
paraît un peu à la traîne après chaque réponse, avant de vraiment "rendre
la parole".

**[app/src/main/java/com/hasan/v1/HassanNotificationService.kt:181 + MainViewModel.kt:924-928, WakeWordPipeline.kt:189-191]**
Les seules notifications Android effectivement déclenchées par du code
utilisé (`HassanNotificationService.notifyMessage`) le sont uniquement à la
fin d'un tour de chat *déjà initié par l'utilisateur* (que ce soit via
`MainViewModel.sendToHermes` premier-plan ou via `WakeWordPipeline` en
tâche de fond) : ce n'est donc pas une notification "proactive" au sens où
le brief l'entend (assistant qui relance spontanément), mais un simple
"votre réponse est prête, revenez voir l'app" — équivalent d'un accusé de
réception différé, pas d'une initiative. À distinguer du vrai canal
`proactive` (cassé, voir écart majeur ci-dessus).
Impact : rien dans l'app aujourd'hui ne peut ressembler à "Hasan vous
relance de lui-même" sans sollicitation initiale — seulement "Hasan
répond enfin à ce que vous lui avez demandé, même si vous avez quitté
l'app entre-temps".

**[app/src/main/java/com/hasan/v1/webui/WebUiMemoryClient.kt:16-22]**
La mémoire persistante de l'assistant (`MEMORY.md`/`USER.md`/`SOUL.md`,
exposée par `GET /api/memory` côté hermes-webui) est lue et affichée dans
l'app (`MemoryScreen.kt`, `MemoryFragment.kt`, `MemoryViewModel.kt`) mais
en **lecture seule**, le commentaire du client le dit explicitement :
"le serveur expose aussi POST /api/memory/write (édition), délibérément
non implémenté ici (écran lecture seule pour cette étape)". Confirmé fait
(le endpoint d'écriture existe côté serveur, absent côté client) plutôt
que supposition — c'est écrit noir sur blanc dans le code.
Impact : l'utilisateur ne peut pas corriger ou enrichir directement depuis
son téléphone ce que Hasan "sait" de lui (son nom, ses préférences, le
"caractère" de l'assistant potentiellement stocké dans SOUL.md) — il faut
passer par l'interface web hermes-webui sur un autre appareil pour ça,
cassant l'illusion d'un compagnon autonome sur mobile.

**[app/src/main/java/com/hasan/v1/MainViewModel.kt:940-947]**
Le commentaire de la branche `WebUiStreamEvent.AppError` précise
explicitement : "Aucun retry automatique : une erreur reste toujours
visible pour l'utilisateur (bouton 'Réessayer' sur la bulle) plutôt que
d'être masquée par une tentative de récupération silencieuse". C'est un
choix assumé et documenté (pas un bug), mais du point de vue "attentes
assistant personnel", cela signifie qu'un simple accroc réseau transitoire
pendant un tour de conversation interrompt le tour et exige une action
manuelle de l'utilisateur pour reprendre, plutôt qu'une tentative de
reprise transparente.
Impact : sur une connexion mobile instable (cas d'usage réaliste pour un
assistant "de poche"), l'expérience est plus fragile qu'un assistant cloud
habitué à masquer ces accrocs.

---

## Détails cosmétiques

**Barge-in réellement fonctionnel — point positif à noter.**
`app/src/main/java/com/hasan/v1/audio/BargeInListener.kt` (fichier entier)
implémente un vrai mécanisme de coupure de parole : VAD sur `AudioRecord`
pendant que le TTS parle, ducking du volume dès ~64 ms de voix détectée
(`DUCK_TRIGGER_FRAMES`, ligne 189), confirmation et coupure effective du
TTS après ~320 ms de voix continue (`CONFIRM_SPEECH_FRAMES`, ligne 190),
avec bascule immédiate vers l'écoute STT (`MainViewModel.kt:72-86`). C'est
une vraie interaction à tour de parole naturelle façon Telegram/appel
vocal, pas juste "attendre la fin du TTS" — un des points forts constatés
de l'app par rapport à l'attente d'un simple chatbot vocal.

**[app/src/main/java/com/hasan/v1/ui/screens/FreeHandScreen.kt (fichier entier)]**
Le composant Compose du mode mains libres est purement visuel (aucune
logique de tour de parole dedans, juste `onMicClick`/`onToggleMute`
délégués) — la vraie logique d'enchaînement automatique wake word → écoute
est dans `LightModeFragment.kt:111-120`, qui collecte
`HassanWakeWordService.wakeWordDetected` en continu. Cette séparation est
saine architecturalement (UI vs logique), simplement noté ici pour clarifier
que "FreeHandScreen" à lui seul ne suffit pas à comprendre le mode mains
libres.

**[app/src/main/java/com/hasan/v1/db/HermesSession.kt:8-9]**
Le commentaire de la docstring de l'entité décrit encore l'ancien
protocole ("L'UUID est envoyé dans chaque requête POST /v1/responses sous
la clé conversation_id") alors que le code a migré vers hermes-webui
(session_id généré serveur, voir `MainViewModel.kt:1189-1196`). Sans
impact fonctionnel direct, mais un signe que la doc de code traîne derrière
la migration en cours (webui-migration), cohérent avec le nom de la
branche git actuelle.

**[app/src/main/java/com/hasan/v1/MainViewModel.kt:587]**
`// TODO : envoyer l'intent ACTION_SET_THRESHOLD quand le service le supportera`
— le réglage de sensibilité du wake word (`setWakeWordSensitivity`) est
persisté côté préférences mais jamais réellement transmis au service qui
fait la détection. Petit écart entre "le réglage existe dans l'UI" et "le
réglage a un effet réel".

---

## Hors périmètre — à signaler

- Le TODO `app/src/main/java/com/hasan/v1/SpeechRecognizerManager.kt:15`
  ("migration Moonshine : remplacer cette classe par MoonshineSTT") relève
  du choix technique STT on-device vs Google Speech Services — voir agent
  Kotlin/Protocole pour le détail, hors périmètre feature-gap pur.
- Le bug potentiel de contournement du dialog de confirmation bridge
  documenté dans `archive/2026-07-16-bridge-mcp-confirmation-bypass.md`
  (SMS envoyé sans déclencher la confirmation) est un risque sécurité, pas
  un écart d'attente produit — voir agent Sécurité/Protocole.
- L'incident historique `tool_calls: []` (erreur DeepSeek renvoyée comme
  réponse normale) documenté dans
  `archive/2026-07-16-tool-calls-empty-array.md`, et sa détection
  best-effort actuelle via `looksLikeDisguisedLlmError()`
  (`MainViewModel.kt:1475-1480`) sont un sujet de fiabilité protocole/LLM,
  pas un écart de feature — voir agent Protocole.
