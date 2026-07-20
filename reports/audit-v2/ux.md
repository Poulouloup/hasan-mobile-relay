# Audit UX — Flux utilisateur et accessibilité

Périmètre : pairing (QR + manuel), conversation (chat/streaming/erreurs), mode mains
libres, états manquants sur les écrans principaux, accessibilité (contentDescription,
touch targets 48dp). Le graphe Graphify (`graphify-out/GRAPH_REPORT.md`, généré
2026-07-15) est antérieur au dernier commit de refactor (`415c7c7`, 2026-07-20) qui a
déplacé la navigation en onglets Compose (Tools/Logs) — les fichiers ont donc été relus
directement plutôt que via le graphe pour la partie navigation/settings. Le mockup de
référence est `docs/design/hasan-mockup-v2.html` (447 lignes, 3 onglets CHAT/ACTIVITÉ/
RÉGLAGES + overlay mode mains libres) — l'implémentation réelle a divergé en ajoutant
des onglets Tasks/Skills/Memory/Tools non présents dans ce mockup.

## Bloquant

**[app/src/main/java/com/hasan/v1/SettingsFragment.kt:365-382]** Le pairing (QR et
manuel) ne remonte aucun feedback d'erreur visible sur l'écran Réglages. `handlePairingResult`
(MainViewModel.kt:1363-1384) écrit tous les cas d'échec — `InvalidQrContent`,
`ServerRejected` (code expiré/invalide), `NetworkError` — dans le champ partagé
`UiState.errorMessage`. Or `SettingsFragment.observeRelayState()` ne lit que
`relayPaired`, `relayConnectionStatus` et `relayCertCheck` de `viewModel.uiState` —
`errorMessage` n'est jamais consulté ici. Le seul consommateur de `errorMessage` dans
toute la codebase est `ConversationFragment.renderMessages()` (ConversationFragment.kt:419-428),
qui l'affiche comme une bulle d'erreur dans le fil de discussion — et seulement si une
conversation est déjà chargée (`resumedConversationId` non-null déclenche le rendu).
Résultat : un utilisateur qui scanne un QR expiré ou saisit un mauvais code depuis
Réglages ne voit **rien du tout** — ni message, ni changement d'état visuel, le bouton
"Appairer" redevient simplement cliquable. Priorité : Bloquant. Justification : un cas
d'erreur explicitement prévu et implémenté côté logique (4 variantes de `PairingResult`
avec messages différenciés) est rendu invisible par une mauvaise route d'affichage —
l'utilisateur ne peut pas savoir si son pairing a échoué ni pourquoi, et n'a aucune
indication pour agir (réessayer, vérifier le code, etc.).

**[app/src/main/java/com/hasan/v1/QrScannerActivity.kt:41-42,92-100]** et
**[app/src/main/java/com/hasan/v1/MainActivity.kt:75-82]** Aucun état d'erreur visuel
pour les échecs du scan QR lui-même. Trois chemins d'échec silencieux :
1. Permission caméra refusée → `finish()` immédiat (ligne 42), sans `setResult()`.
2. Échec de binding CameraX (`catch (e: Exception)` ligne 97-99) → seulement un
   `Log.e`, puis `finish()`, sans `setResult()`.
3. Dans les deux cas, `qrScannerLauncher` côté MainActivity (ligne 78) ne traite que
   `RESULT_OK` — un retour sans résultat (RESULT_CANCELED implicite) ne produit aucun
   toast, dialog ou message. L'utilisateur revient simplement sur l'écran Réglages sans
   explication.
Priorité : Bloquant. Justification : la permission caméra est nécessaire au flux de
pairing principal (QR) — un refus de permission est un cas fréquent en usage réel
(mauvaise manipulation, alerte lue trop vite) et laisse l'utilisateur bloqué sans
retour, sans savoir qu'il doit passer par le pairing manuel ou les paramètres système.

## Important

**[app/src/main/java/com/hasan/v1/ui/screens/SkillsScreen.kt:47-52]** et
**[app/src/main/java/com/hasan/v1/ui/screens/MemoryScreen.kt:43-49]** Les deux écrans
déclarent un champ `errorMessage: String?` dans leur `UiState` (alimenté respectivement
par `SkillsViewModel.kt:60` et `MemoryViewModel.kt:62`), mais aucun des deux composables
`SkillsScreen`/`MemoryScreen` ne lit ni n'affiche ce champ nulle part dans leur arbre
Compose (vérifié par recherche textuelle — `errorMessage` n'apparaît que dans la
déclaration du data class dans ces deux fichiers). Un échec réseau lors du chargement
des skills ou de la mémoire ne produit donc aucun état d'erreur visible : l'écran reste
soit sur son spinner de chargement, soit affiche silencieusement l'état vide ("Aucune
skill installée" / "Statistiques indisponibles") — indiscernable pour l'utilisateur
d'un état "vide légitime" contre "erreur serveur". `TasksScreen.kt:63-64` avec son
`TasksErrorBanner` est l'implémentation de référence à répliquer sur ces deux écrans.
Priorité : Important. Justification : l'état est déjà câblé côté ViewModel (le
travail de plomberie est fait), il manque seulement le rendu — coût de correction
faible, mais l'utilisateur ne peut aujourd'hui pas distinguer "pas de skills" de
"serveur hermes-webui injoignable".

**[app/src/main/java/com/hasan/v1/ui/screens/FreeHandScreen.kt:118-143]** Les boutons
"QUITTER" et "COUPER LE SON" du mode mains libres (`FreeHandTextButton`) n'ont pas de
taille de zone tactile explicite — le `Row` clickable dimensionne uniquement sur son
contenu (texte + `padding(horizontal = SpacingM, vertical = SpacingS)` = 12dp + 8dp,
soit une hauteur tactile totale d'environ 8+~14(texte 9sp)+8 ≈ 30dp), très en dessous
des 48dp de `HasanDimens.TouchTarget`. Priorité : Important. Justification : ce sont
les deux seuls points de sortie/contrôle du mode mains libres, un mode conçu pour un
usage sans regard soutenu sur l'écran (conduite, cuisine, etc. — cas d'usage cité dans
le nom même de la fonctionnalité) ; une zone tactile réduite y est particulièrement
pénalisante.

**[app/src/main/java/com/hasan/v1/LightModeFragment.kt:71-79]** `onMicClick` (tap
manuel sur le micro en mode mains libres) ne déclenche aucune vibration, alors que
`renderVoiceState` déclenche un retour haptique pour les transitions automatiques
`WakeWordDetected` (ligne 143-145) et `TtsSpeaking` (ligne 146-149). Incohérence de
micro-interaction : l'action manuelle explicite de l'utilisateur (la plus susceptible
d'avoir besoin d'une confirmation tactile, puisque l'écran n'est pas forcément regardé)
est celle qui n'a pas de feedback haptique. Priorité : Important. Justification :
cohérence des micro-interactions explicitement dans le périmètre de cet audit ; impact
UX modéré mais correction simple (un seul appel `vibrator?.vibrate(...)` à ajouter).

**[app/src/main/java/com/hasan/v1/ui/screens/ChatScreen.kt:139-176]** Aucun état
"conversation vide" distinct dans `ChatScreen`/`MessageList` — une conversation
nouvellement créée sans message affiche une `LazyColumn` totalement vide (pas de
placeholder, pas d'invite du type "Dites quelque chose" ou d'exemple de prompt).
Priorité : Important. Justification : écran d'entrée principal de l'app ; un écran vide
sans texte d'orientation est ambigu pour un nouvel utilisateur (bug potentiel vs. état
normal "rien à afficher encore").

## Mineur

**[app/src/main/java/com/hasan/v1/ui/screens/MemoryScreen.kt:114-122]** Bouton de
fermeture de l'écran détail fichier Memory (seul moyen de revenir à la liste) :
`Image(...).size(HasanDimens.IconSmall)` (16dp) + `.clickable` + `.padding(2.dp)` →
zone tactile ≈ 20dp, loin des 48dp cible. C'est le seul chemin de retour sur cet écran
(pas de bouton back Android géré explicitement visible dans ce composable). Priorité :
Mineur (à vérifier : le bouton système "retour" Android peut compenser selon le câblage
du Fragment, non audité ici — code source uniquement).

**[app/src/main/java/com/hasan/v1/ui/screens/SkillsScreen.kt:250-255]** Flèche de
retour "←" dans `SkillDetailScreen` : `Text("←").clickable(...).padding(end = SpacingM)`,
sans taille de zone tactile explicite — probablement bien en dessous de 48dp (dépend de
la taille de police `TextTitleMedium`=20sp, sans padding vertical dédié). Priorité :
Mineur.

**[app/src/main/java/com/hasan/v1/ui/screens/TasksScreen.kt:154-162]** Bouton de
fermeture de la bannière d'erreur (`TasksErrorBanner`) : icône 16dp cliquable sans
zone tactile élargie. Priorité : Mineur (bannière non bloquante, dismissable
autrement en résolvant l'erreur).

**[app/src/main/java/com/hasan/v1/ui/screens/ChatScreen.kt:496-516,745-771]**
`MessageIconButton` (TTS/copier/partager sur bulles assistant) utilise
`Modifier.size(HasanDimens.IconLarge)` (32dp) comme zone cliquable, et `AttachmentChip`
utilise `IconSmall` (16dp) pour son bouton de suppression — tous deux sous les 48dp de
`HasanDimens.TouchTarget`, alors que le fichier utilise déjà correctement cette
constante ailleurs (`EqualizerBars`, `CutCornerIconButton`, `MicOrSendButton`).
Incohérence interne au même fichier plutôt qu'absence générale de la convention.
Priorité : Mineur. Justification : actions secondaires peu fréquentes, mais
incohérence facile à corriger vu que le pattern correct existe déjà dans le même
fichier.

**[app/src/main/java/com/hasan/v1/ui/components/HasanDrawer.kt:272-299]**
`DrawerNavRow` (items de navigation du drawer) : hauteur tactile ≈ padding vertical
`SpacingM` (12dp)×2 + contenu texte/icône (16dp) ≈ 40dp, sous les 48dp. Priorité :
Mineur — la largeur pleine (`fillMaxWidth`) compense partiellement le risque de
mis-tap comparé à une icône isolée.

**[docs/design/hasan-mockup-v2.html vs implémentation]** Écarts constatés entre le
mockup et le code réel :
- Le mockup ne montre que 3 onglets (CHAT / ACTIVITÉ / RÉGLAGES), alors que
  l'implémentation (`HasanDrawer.kt:55` `HasanNavTab`) expose 6 entrées (CHAT, TASKS,
  SKILLS, MEMORY, TOOLS, SETTINGS) via un drawer latéral plutôt qu'une bottom nav —
  divergence de navigation majeure et assumée (le drawer remplace la bottom nav du
  mockup, cf. commentaire `HasanDrawerScaffold` citant les règles d'architecture).
  Non-régression a priori (évolution volontaire documentée dans les commits), mais le
  mockup n'a pas été mis à jour pour refléter cette réalité — à signaler pour éviter
  toute confusion future.
- Le mockup affiche un badge de connexion unique ("WSS · 42MS"), l'implémentation en
  a deux séparés (hermes-webui + relay bridge, `HasanHeader.kt:47-51,85-88`) — évolution
  cohérente avec la séparation des deux connexions (chat vs bridge téléphone) actée
  dans `SettingsScreen.kt:440-447`, mais également non reflétée dans le mockup.
- Mode mains libres : le mockup a une transition instantanée show/hide (classe CSS
  `.show`) sans animation d'anneau pulsant persistant en dehors de l'état actif ; le
  code (`FreeHandScreen.kt:145-196`) ajoute une animation d'anneau infinie
  (`rememberInfiniteTransition`) quand `isListening && !isMuted` — enrichissement,
  pas une régression.

## Hors périmètre — à signaler

- **Qualité du code (Kotlin/Compose)** : plusieurs composables dupliquent des motifs
  (ex. `ClarifyOverlay`/`ApprovalOverlay`/`HasanConfirmOverlay` quasi-identiques) —
  laissé aux agents Kotlin/Compose.
- **Sécurité** : le TOFU (Trust On First Use) pour le certificat du relay pendant le
  pairing (`PairingManager.kt:38-283`, `CertPinStore`) et le flux de confirmation des
  commandes bridge sensibles ne sont pas analysés ici au-delà de leur impact UX
  direct — laissé à l'agent Cyber (voir aussi `archive/2026-07-16-bridge-mcp-confirmation-bypass.md`
  cité dans les notes de session, non vérifié par cet audit).
- **Cohérence visuelle des couleurs/tokens** : les valeurs `HasanColors`/`HasanShapes`
  utilisées dans les fichiers audités n'ont pas été vérifiées pour cohérence de palette
  ou de contraste (juste signalé si visiblement problématique, non calculé) — laissé à
  l'agent UI.
