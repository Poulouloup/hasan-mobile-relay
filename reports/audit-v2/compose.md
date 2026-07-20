# Audit Compose — Recomposition et state

Périmètre couvert : `ui/screens/*.kt` (ChatScreen, SettingsScreen, TasksScreen,
TaskEditorScreen, ToolsPermissionsScreen, ActivityScreen, SkillsScreen,
MemoryScreen, FreeHandScreen), `ui/components/*.kt` (CutCornerComponents,
HasanHeader, HasanDrawer, HasanConfirmOverlay, MarkdownText),
`ConversationFragment.kt`, `SettingsFragment.kt`, `ToolsPermissionsFragment.kt`,
et la racine Compose de `MainActivity.kt` (setupDrawerRoot).

## Bloquant

Aucun finding classé Bloquant. Rien dans le périmètre Compose ne provoque de
crash, de fuite mémoire garantie ou de corruption d'état ; les problèmes
trouvés sont des inefficacités de recomposition ou des incohérences de
pattern, pas des défaillances fonctionnelles.

## Important

**[ConversationFragment.kt:154-200, HasanHeader.kt:56-90]** La fusion récente
de `HasanHeader` + `ChatScreen` dans un seul arbre Compose (`setupComposeChat()`)
n'introduit **pas** de recomposition inutile de `MessageList` quand le badge de
connexion change : `hermesBadgeState`/`bridgeBadgeState` sont des
`mutableStateOf` **séparés** de `messagesState`, lus uniquement par
`HasanHeader` (ligne 158-162) — Compose ne recompose que les portées qui lisent
l'état modifié (« smart recomposition » scoped au composable enfant qui lit la
valeur), donc un changement de `hermesBadgeState`/`bridgeBadgeState` ne
redéclenche que `HasanHeader`, pas `ChatScreen`/`MessageList`. Le risque
redouté par le brief ne se matérialise pas structurellement. Priorité :
Important (pas Bloquant) uniquement parce que ceci mérite vérification au
profiler pour confirmer qu'aucune recomposition en cascade n'a lieu au niveau
du `Column` parent (ligne 157) qui possède les deux enfants — en théorie
`Column` elle-même ne recompose pas non plus (ses seuls paramètres sont des
lambdas et Modifier stables), mais seul le layout inspector peut le confirmer
en conditions réelles de stream token-par-token. Justification : c'est le
point explicitement signalé comme risque dans le brief d'audit, donc à
documenter même si le résultat est rassurant — fait vérifiable statiquement,
impact réel "à vérifier manuellement" (profiling Layout Inspector recommandé
pendant un streaming actif).

**[ChatScreen.kt:844-856, ChatScreen.kt:1044-1056]** `CancelChatButton` et la
branche `showSend` de `MicOrSendButton` recréent une `Image` avec
`painterResource` + `ColorFilter.tint(...)` à chaque recomposition — non
mémoïsé, mais `painterResource`/`ColorFilter.tint` sont déjà des appels
`remember`-internes côté Compose UI (stables par construction), donc impact
réel négligeable. Mentionné pour mémoire, pas d'action requise. Priorité :
Mineur en réalité — reclassé ci-dessous.

**[SettingsFragment.kt:97-129, SettingsScreen.kt:406-438]** Le `SettingsUiState`
complet est reconstruit comme un objet neuf à *chaque* recomposition du
`ComposeView` racine (il n'y a pas de `remember`/`derivedStateOf` autour de la
construction), avec `wakeWordModels = SettingsManager.WAKE_WORD_MODELS` et
plusieurs listes (`ttsSubOptionsState`, `mcpServersState`, `hermesProfilesState`)
recréées comme de nouvelles instances de `List` à chaque mise à jour
individuelle (ex: `toggleMcpServer` ligne 302 fait `mcpServersState =
mcpServersState.map { ... }`, une **nouvelle** `List` à chaque frappe/toggle).
Comme il n'y a qu'un seul `mutableStateOf` par *catégorie* de champ (pas un
seul state monolithique), Compose ne recompose que les sous-arbres qui lisent
le champ modifié — donc l'impact est limité en pratique (fait : chaque
`mutableStateOf` est overridé indépendamment, donc `SettingsScreen` entier est
recomposé car `state: SettingsUiState` est reconstruit à neuf en un seul objet
passé en paramètre unique — cf. ligne 100 `state = SettingsUiState(...)` :
**toute** propriété de state fait qu'à chaque frappe dans **n'importe quel**
champ, en toute rigueur Compose recompose `SettingsScreen()` en entier car son
paramètre `state` est un nouvel objet `data class` à chaque frappe). C'est un
vrai state-hoisting anti-pattern : `SettingsUiState` agrège ~25 champs
disparates en un seul objet reconstruit intégralement à chaque frappe dans
n'importe quel champ texte (`onWebUiServerUrlChange`, `onRelayManualUrlChange`,
etc. réassignent un seul `var ... by mutableStateOf`, ce qui invalide l'objet
`SettingsUiState` composé dans `setContent{}` à la prochaine recomposition).
Priorité : Important. Justification : fait vérifiable statiquement (paramètre
composite reconstruit à chaque frappe, recomposition de tout l'écran Réglages
sur chaque caractère tapé dans un champ) ; l'impact perf réel dépend de la
stabilité structurelle de `SettingsUiState` (Compose peut détecter que
certains sous-composables ignorent les champs inchangés grâce à
`equals()`/skip, `SettingsUiState` étant une `data class` avec des champs
`val` — donc Compose *peut* skip certains sous-arbres via structural equality
si les valeurs individuelles sont `==`), mais en frappe de texte
(`SettingsEditableRow` ligne 240-344) c'est précisément le champ qui change à
chaque caractère, donc pas de skip possible pour la section concernée — impact
réel à vérifier manuellement avec le Layout Inspector, mais le pattern
"state god-object reconstruit à chaque frappe" est un fait.

**[ConversationFragment.kt:367-401]** `observeMessages()` déclenche une
requête Room complète (`getMessagesForConversationOnce`) à chaque changement de
`thinkingMessage` (ligne 388-400, `.map { it.resumedConversationId to
it.thinkingMessage }.distinctUntilChanged()`), en plus du `Flow` déjà collecté
en ligne 370-380 pour les messages persistés. Deux sources écrivent dans le
même `messagesState` (ligne 120, 430) : le flow Room réactif et le
recalcul ponctuel `renderMessages` déclenché par le changement de
`thinkingMessage`. Le contenu de `messagesState` change donc deux fois par
tour de conversation typique (une fois via le flow DB, une fois via
`thinkingMessage`), chacune reconstruisant `visible` comme une nouvelle
`MutableList` puis `messagesState = visible.toList()` (ligne 430) — nouvelle
`List` à chaque appel. La clé stable par item (`message.id` ou `hashCode()`,
ChatScreen.kt:331) protège la `LazyColumn` d'un re-render complet grâce au
diffing par clé, donc l'impact est amorti, mais chaque recomposition de
`MessageList` reparcourt toute la liste de messages pour recalculer les clés.
Priorité : Important. Justification : fait (double pipeline d'écriture vers le
même state, requête DB synchrone déclenchée par un `Flow` dérivé plutôt que
combiné avec `combine()`) ; impact réel (latence perçue, nombre de requêtes DB
par seconde pendant le streaming) à vérifier manuellement, mais le
`thinkingMessage` change potentiellement à chaque token/outil pendant un
streaming actif (`VoiceState.HermesStreaming.toolMessage`, ChatScreen usage
indirect), ce qui peut multiplier les requêtes Room côté IO thread. Le
commentaire du code ligne 384-387 reconnaît explicitement ce risque
("distinctUntilChanged évite de recomposer la liste à chaque token de
streaming") mais ne l'élimine que pour le *contenu textuel* streamé
(`Message.content`), pas pour les changements de `thinkingMessage` lui-même.

## Mineur

**[ChatScreen.kt:186]** `ClarifyOverlay` : `var freeText by remember(clarify)
{ mutableStateOf("") }` — la clé `remember(clarify)` est correcte
(réinitialise le champ à chaque nouvelle question de clarification), mais
`ChatClarifyUi` est une `data class` sans `equals` custom : si le serveur
renvoie deux fois la même question consécutivement avec un contenu identique,
`remember` ne re-déclenche pas (comportement attendu ici, pas un bug — noté
pour complétude).

**[ChatScreen.kt:344-362]** `rememberLazyListStateAutoScroll(itemCount: Int)` —
la clé `LaunchedEffect(itemCount)` utilise uniquement la **taille** de la
liste, pas son contenu. Un message existant dont le `content` change en place
(re-composition du même item pendant le streaming) ne redéclenche pas cet
effet, ce qui est le comportement voulu (pas de re-scroll à chaque token) —
mais cela signifie aussi qu'un scroll-to-bottom ne se déclenche jamais tant
que `itemCount` ne change pas, y compris si l'utilisateur était en bas et
qu'un message volumineux grossit hors-écran progressivement. Comportement
correct par design (le commentaire ligne 342 le documente), mais fragile si
un futur changement remplace un message en place sans changer `itemCount`
(ex: retry qui réutilise le même id) : à surveiller, pas un bug actuel.

**[ChatScreen.kt:589-601]** `buildMetadataText` parse un `JSONObject` à partir
de `message.metadata` (String) directement dans le corps du composable
`AssistantBubble` (ligne 463), sans `remember(message.metadata)`. Ce n'est pas
un side-effect au sens strict (pas d'I/O, pas de mutation d'état externe),
mais c'est un parsing JSON refait à chaque recomposition de la bulle au lieu
d'être mémoïsé. Priorité : Mineur — coût réel négligeable (petit JSON, appelé
seulement pour les bulles assistant non-pending) mais facile à corriger avec
`remember(message.metadata) { buildMetadataText(message.metadata) }`.

**[MarkdownText.kt:41-56]** `AndroidView` avec bloc `update = { tv -> ...
getMarkwon(context).setMarkdown(tv, text) }` — Markwon reparse et
re-render le Markdown à **chaque** recomposition du composable parent, même
si `text` n'a pas changé, car `update` n'est pas gardé par une comparaison de
`text`. Utilisé par `AssistantBubble` (ChatScreen.kt:444), `SkillDetailScreen`
et `MemoryFileDetailScreen` — pour ces deux derniers écrans le contenu est
statique donc l'impact est nul en pratique (peu de recompositions après
affichage initial), mais pour `AssistantBubble` pendant un streaming, si le
message parent recompose pour une autre raison (ex: `ttsPlayingMessageId`
change globalement, forçant `MessageList`/`items` à re-évaluer — protégé par
la clé stable donc en théorie isolé), le re-parsing Markdown complet peut se
déclencher inutilement. Priorité : Mineur. Fait : pas de guard sur `text` dans
`update`. Impact réel à vérifier manuellement (profiler pendant un streaming
avec formatage Markdown riche).

**[SettingsFragment.kt:276-282]** `onNativeEngineSelected` utilise
`view?.postDelayed({ populateNativeVoiceOptions() }, 1500)` — un callback posté
sur la vue plutôt qu'un `LaunchedEffect`/coroutine avec `delay()`. Ce n'est
techniquement pas dans un composable (c'est dans le Fragment, hors périmètre
strict Compose), mais le résultat (`nativeEnginesState`/`ttsSubOptionsState`)
alimente directement l'état Compose. Un `postDelayed` non annulé si le
Fragment est détruit avant les 1500ms peut définir un `mutableStateOf` d'une
vue déjà détruite — risque de fuite mineure, pas un crash (mutableStateOf
Compose ne référence pas la vue Android). Priorité : Mineur, hors cœur du
périmètre Compose (relève plutôt de l'agent Kotlin/coroutines) mais signalé
car il alimente l'état Compose.

**[MainActivity.kt:253-273]** `buildDrawerState(sessions)` construit un nouvel
objet `DrawerUiState` — y compris une nouvelle `List<HasanNavItem>`
`navItems` recréée avec 6 `getString(...)` — à **chaque** recomposition du
composable racine (`setupDrawerRoot`), déclenchée par `collectAsState()` sur
`viewModel.sessions` (ligne 214). `navItems` est statique (ne dépend que de
ressources fixes) mais reconstruit une nouvelle liste à chaque emission du
`StateFlow` sessions, ce qui invalide `DrawerUiState` dans son ensemble et
peut forcer `HasanDrawerContent` à re-parcourir tous les `DrawerNavRow`. Coût
réel négligeable (6 items statiques), mais `navItems` pourrait être hoisté
avec `remember` puisqu'il ne dépend que de ressources non réactives. Priorité :
Mineur.

**[SettingsScreen.kt:240-344, SkillsScreen.kt:92-99]** Deux patterns de state
local différents pour une fonctionnalité similaire (édition inline vs.
expand/collapse) : `SettingsEditableRow` utilise 3 `mutableStateOf` locaux
distincts (`editing`, `draft`, `secretVisible`), alors que `SkillsScreen`
utilise un seul `mutableStateMapOf<String, Boolean>` partagé pour l'état
"expanded" de toutes les catégories. Les deux choix sont défendables
localement (le state est bien possédé par le composable qui en a l'usage
exclusif, donc ni l'un ni l'autre n'est un vrai state-hoisting manqué au sens
strict — c'est du state purement local à la UI, pas du state métier), mais
l'absence de convention partagée entre écrans est une incohérence stylistique
à noter. Priorité : Mineur.

## Hors périmètre — à signaler

- **Design visuel, tokens de couleur** (HasanColors, HasanDimens, HasanShapes
  utilisés dans tous les fichiers audités) — relève de l'agent UI, non
  examiné pour sa cohérence visuelle ici.
- **Coroutines hors Compose** (`lifecycleScope.launch` dans SettingsFragment,
  `viewModelScope` dans MainViewModel, gestion des jobs SSE/WebSocket) —
  relève de l'agent Kotlin ; seuls les points où ces coroutines alimentent
  directement un `mutableStateOf` Compose ont été signalés ci-dessus (ex:
  SettingsFragment.kt:276-282).
