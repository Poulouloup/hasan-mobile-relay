# Audit UI — Cohérence visuelle

Périmètre inspecté : `app/src/main/java/com/hasan/v1/ui/theme/*.kt`,
`app/src/main/java/com/hasan/v1/ui/components/*.kt`,
`app/src/main/java/com/hasan/v1/ui/screens/*.kt` (9 écrans, ChatScreen.kt 1094
lignes, SettingsScreen.kt 994 lignes). Référence design system :
`docs/design/hasan-mockup-v2.html`, `ui/theme/{Color,Dimens,Shape,Type}.kt`.

## Bloquant

Aucun finding classé bloquant — pas d'incohérence qui casse visuellement
l'app ou contredit frontalement le design system au point de la rendre
inutilisable.

## Important

- **[app/src/main/java/com/hasan/v1/ui/screens/SettingsScreen.kt:382-403]**
  `CutCornerOutlineButton` (bouton pleine largeur contour, équivalent
  `.btn-outline` du mockup) et `CutCornerFilledButton` (lignes 348-378, équivalent
  `.btn-primary`) sont définis dans un fichier d'écran (`ui/screens/SettingsScreen.kt`),
  pas dans `ui/components/`, alors qu'ils sont réutilisés en dehors de Settings :
  `ChatScreen.kt` (ClarifyOverlay, ApprovalOverlay), `HasanDrawer.kt:224`
  (bouton "+ Nouvelle Session"), `HasanConfirmOverlay.kt:71-81`. Ces trois fichiers
  importent `com.hasan.v1.ui.screens.CutCornerOutlineButton` depuis
  `ui/components/`, ce qui inverse la direction de dépendance attendue
  (components ne devrait pas dépendre de screens). Priorité : Important.
  Justification : composant partagé mal placé — casse la séparation
  components/screens documentée implicitement par la structure du dossier, et
  crée un couplage screens→components→screens difficile à suivre ; un futur
  écran qui a besoin d'un bouton outline devra deviner qu'il faut l'importer
  depuis `ui.screens.SettingsScreen` plutôt que `ui.components`.

- **[app/src/main/java/com/hasan/v1/ui/screens/TaskEditorScreen.kt:190-206]**
  `EditorField` utilise `androidx.compose.material3.TextField` (style Material
  "filled", indicateur de soulignement) alors que **tous les autres champs de
  saisie de l'app** (`SettingsScreen.kt:276` `SettingsEditableRow`,
  `ChatScreen.kt:216` `ClarifyOverlay`, `ChatScreen.kt:990` `TextModeRow`)
  utilisent `OutlinedTextField` avec `HasanShapes.bubble()`/bordure custom.
  `TaskEditorScreen` est le seul écran de saisie qui ne suit pas ce pattern —
  pas de `shape` custom, pas de `HasanShapes`, couleurs passées via
  `TextFieldDefaults.colors(...)` (lignes 197-205) au lieu de
  `OutlinedTextFieldDefaults`. Priorité : Important. Justification : rupture
  visible de la forme des champs de saisie (soulignement vs contour+coin coupé)
  dans un formulaire plein écran — pas un détail marginal, c'est l'essentiel
  de l'écran TaskEditor.

- **[app/src/main/java/com/hasan/v1/ui/screens/ToolsPermissionsScreen.kt:138-143]**
  `CapabilityCard` utilise `androidx.compose.material3.Icon` +
  `painterResource(capability.iconRes)` avec `tint = HasanColors.Accent` —
  seul endroit du code audité qui passe par le composant Material `Icon`
  plutôt que par `Image + painterResource + ColorFilter.tint(...)`, le
  pattern utilisé partout ailleurs (`HasanHeader.kt`, `HasanDrawer.kt`,
  `ChatScreen.kt`, `MemoryScreen.kt`, etc. — cf. section Cohérence des icônes
  ci-dessous pour le détail du contraste). Priorité : Important.
  Justification : `Icon` et `Image+tint` rendent différemment sur le plan de
  la sémantique d'accessibilité (Icon a un rôle sémantique par défaut,
  contentDescription null ici) et le mélange dans un même écran par ailleurs
  cohérent (le reste de `ToolsPermissionsScreen.kt` utilise bien
  `HasanIconButton`, `CutCornerPanel`, `TagPill`) suggère un oubli plutôt
  qu'un choix délibéré.

- **[app/src/main/java/com/hasan/v1/ui/screens/MemoryScreen.kt:160-178]**
  `MemoryTabPill` (sélecteur d'onglet MEMORY/INSIGHTS, panel à coin coupé +
  couleur conditionnelle selected/unselected) réimplémente un pattern déjà
  couvert conceptuellement par `TagPill` (`CutCornerComponents.kt:92-112`,
  badge coloré texte mono) et par le pattern "choix actif/inactif avec bordure
  accent" déjà présent dans `SettingsScreen.kt:726-744` (`ProviderChoiceRow`)
  et `FreeHandScreen.kt:119-143` (`FreeHandTextButton`) — trois implémentations
  quasi identiques (fond `AccentGlowBg`/bordure `Accent` si sélectionné, sinon
  `BgSurface`/`Border`) codées séparément dans trois fichiers d'écran
  différents, aucune factorisée dans `ui/components/`. Priorité : Important.
  Justification : duplication de pattern visuel à 3 endroits distincts avec la
  même logique couleur — candidat clair pour un composant partagé
  `SelectableChip`/`SelectablePill`, actuellement absent de `ui/components/`.

## Mineur

- **[app/src/main/java/com/hasan/v1/ui/components/MarkdownText.kt:47]**
  `textSize = 15f` codé en dur sur le `TextView` Android natif embarqué
  (`AndroidView`), sans passer par `HasanDimens`. Priorité : Mineur.
  Justification : ce composant pont vers du texte non-Compose (Markwon) est
  par nature hors du système de types Compose (`TextUnit`), donc moins grave
  qu'un hardcode dans un composable pur, mais la valeur `15f` (~15sp) ne
  correspond à aucun palier `HasanDimens` existant (le plus proche est
  `TextDisplaySmall = 15.sp`) — pourrait au moins référencer
  `HasanDimens.TextDisplaySmall.value` pour rester synchronisé si la valeur
  change un jour.

- **[app/src/main/java/com/hasan/v1/ui/screens/ChatScreen.kt:388,419,434,439,447,469,483,489,543,567,611,687,698,733-734,751,759,846,853,887,893,915,925,944,987,1079-1081,1088]**
  Grand nombre de `.dp`/`.sp` hardcodés dans ChatScreen.kt à côté d'usages
  corrects de `HasanDimens` juste à côté (ex : ligne 439
  `padding(start = HasanDimens.SpacingM, end = HasanDimens.SpacingL, top = 3.dp, bottom = 3.dp)`
  mélange token et valeur en dur dans le même appel). Valeurs récurrentes non
  couvertes par `HasanDimens.Spacing*` : `2.dp`, `3.dp`, `4.dp`, `6.dp`,
  `10.dp`, `12.dp`, `14.dp`. `HasanDimens` ne définit que
  `SpacingXxs=2/XS=4/S=8/M=12/L=16/Xl=20/Xxl=24/Xxxl=32` — les valeurs
  intermédiaires (`3.dp`, `6.dp`, `10.dp`, `14.dp`) très utilisées dans
  ChatScreen n'ont pas d'alias, ce qui pousse à hardcoder plutôt qu'à
  compléter l'échelle. Priorité : Mineur. Justification : n'affecte pas le
  rendu (les valeurs sont visuellement cohérentes avec le mockup) mais nuit à
  la maintenabilité — un futur changement d'échelle de spacing ne
  propagera pas à ces call sites. Constat similaire mais moindre dans
  `SettingsScreen.kt` (ex. lignes 688, 741, 762, 850, 879 : `6.dp`/`10.dp` en
  dur pour des espacements entre éléments de type radio/slider) et
  `TasksScreen.kt`/`SkillsScreen.kt` (`6.dp` répété pour `Arrangement.spacedBy`
  dans les tag rows).

- **[app/src/main/java/com/hasan/v1/ui/screens/ChatScreen.kt:196,254 vs app/src/main/java/com/hasan/v1/ui/components/HasanConfirmOverlay.kt:50]**
  `widthIn(max = 340.dp)` dupliqué littéralement dans `ClarifyOverlay`,
  `ApprovalOverlay` (ChatScreen.kt) et `HasanConfirmOverlay` (composant
  partagé) — les trois overlays plein écran "carte centrée sur fond noir 72%"
  partagent une structure quasi identique (`Box` fond noir 0.72 alpha +
  `Column` `widthIn(max=340.dp)` + `HasanShapes.panel()` + padding
  `SpacingXxl`/`SpacingXl`) mais ne sont pas factorisés en un seul composant
  paramétrable — `HasanConfirmOverlay` existe déjà avec exactement cette
  charpente mais `ClarifyOverlay`/`ApprovalOverlay` la réimplémentent au lieu
  de l'utiliser (ils ont des besoins de contenu différents — choix multiples,
  champ libre — donc pas un simple copier-coller à corriger, mais le
  "conteneur overlay" lui-même (fond + card + shape + padding) pourrait être
  extrait). Priorité : Mineur. Justification : les trois implémentations
  restent synchronisées aujourd'hui, mais rien ne garantit qu'un futur
  changement de style d'overlay (ex. largeur max, opacité du fond) soit
  répercuté aux trois endroits.

- **[app/src/main/java/com/hasan/v1/ui/screens/SettingsScreen.kt:121]**
  `sectionTitleColor = HasanColors.Accent` — alias local redondant avec
  `HasanColors.Accent`, utilisé uniquement ligne 127. Priorité : Mineur.
  Justification : pas un problème de cohérence visuelle en soi (toujours le
  bon token), juste une indirection inutile qui pourrait faire croire à tort
  qu'il s'agit d'un token sémantique distinct.

## Hors périmètre — à signaler

- Contraste : `HasanColors.TextMuted` (2.35:1 sur `BgBase`) est documenté comme
  décoratif uniquement dans `Color.kt:24`, mais aucun grep n'a été fait pour
  vérifier qu'aucun écran ne l'utilise par erreur sur du texte lisible —
  à vérifier manuellement (accessibilité hors périmètre, mentionné seulement).
- Logique de navigation (drawer, retour, tabs) — non auditée, hors périmètre UI visuel.
- Idiomatique Kotlin / lifecycle des animations (`rememberInfiniteTransition`
  dupliqué dans `HasanHeader.kt`, `ChatScreen.kt` x3, `FreeHandScreen.kt`) —
  potentiellement factorisable mais relève de Kotlin/Compose perf, pas de la DA.
- State hoisting / recomposition (ex. `PulsingDots` recréé avec des params
  différents à 2 endroits de ChatScreen) — hors périmètre, noté pour mémoire.
