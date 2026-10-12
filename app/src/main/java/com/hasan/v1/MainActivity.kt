package com.hasan.v1

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.hasan.v1.db.HermesSession
import com.hasan.v1.ui.BackHandledScreen
import com.hasan.v1.ui.components.DrawerCallbacks
import com.hasan.v1.ui.components.DrawerSessionItem
import com.hasan.v1.ui.components.DrawerUiState
import com.hasan.v1.ui.components.HasanDrawerScaffold
import com.hasan.v1.ui.components.HasanNavItem
import com.hasan.v1.ui.components.HasanNavTab
import com.hasan.v1.ui.theme.HasanTheme
import com.hasan.v1.utils.HasanDialog
import kotlinx.coroutines.launch

/**
 * Activité racine — drawer Compose (menu tiroir) avec 6 onglets : Chat, Tâches,
 * Skills, Mémoire, Tools et Réglages (voir [HasanNavTab]), plus la liste des
 * sessions Hermes (étape 10, remplace la BottomNavigation — voir reworkui.md).
 *
 * Responsabilités :
 *  - Orchestration du drawer (ouverture/fermeture, seul endroit autorisé par
 *    .claude/rules/architecture.md — "Drawer latéral géré par MainActivity")
 *  - Swap de fragments (ChatFragment ↔ ToolsPermissionsFragment ↔ SettingsFragment)
 *  - Démarrage du service wake word si activé
 *  - Expose le ViewModel partagé aux fragments via activityViewModels()
 */
class MainActivity : AppCompatActivity() {

    val viewModel: MainViewModel by viewModels()

    private lateinit var chatFragment: ConversationFragment
    private lateinit var tasksFragment: TasksFragment
    private lateinit var kanbanFragment: KanbanFragment
    private lateinit var memoryFragment: MemoryFragment
    private lateinit var toolsPermissionsFragment: ToolsPermissionsFragment
    private lateinit var settingsFragment: SettingsFragment
    private var lightModeFragment: LightModeFragment? = null
    private var logsFragment: ActivityFragment? = null
    private var filesFragment: FilesFragment? = null

    private var selectedNavTab by mutableStateOf(HasanNavTab.CHAT)
    private var fragmentContainerRoot: View? = null

    /** Piloté depuis openDrawer() — fermé/ouvert par le Composable via son propre DrawerState. */
    private var requestOpenDrawer by mutableStateOf(false)

    /** Piloté depuis confirmQuit() — affiche HasanConfirmOverlay par-dessus tout l'écran. */
    private var showQuitConfirm by mutableStateOf(false)

    /**
     * Ferme le drawer s'il est ouvert, et dit s'il l'était — publié par le
     * Composable racine, qui seul possède le [DrawerState]. Nécessaire au
     * retour arrière : le drawer est la première couche à refermer, mais
     * l'Activity n'a aucun autre moyen de connaître son état (à la
     * différence de [requestOpenDrawer], qui ne fait que le piloter dans
     * l'autre sens). Null tant que le Composable n'est pas passé.
     */
    private var closeDrawerIfOpen: (() -> Boolean)? = null

    /**
     * Vrai quand le mode mains libres occupe le conteneur de fragments. Doublonne
     * volontairement [lightModeFragment] : ce dernier est un champ ordinaire, que Compose
     * n'observe pas — il ne déclencherait donc aucune recomposition en entrant/sortant du
     * mode. Sert à ne PAS teindre la bande de status bar en couleur de header sur cet
     * écran, le seul à n'avoir aucun header (fond BgBase plein cadre).
     */
    private var isLightModeActive by mutableStateOf(false)

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — service démarré dans onCreate de toute façon */ }

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val text = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_TEXT)
            if (!text.isNullOrBlank()) viewModel.pairFromQr(text)
        } else {
            val reason = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_ERROR)
            viewModel.reportQrScanError(reason)
        }
    }

    /** Lance le scanner QR pour le pairing relay — appelable depuis n'importe quel fragment. */
    fun scanQrForPairing() {
        qrScannerLauncher.launch(Intent(this, QrScannerActivity::class.java))
    }

    /** Ouvre le drawer — appelé depuis ConversationFragment (tap sur le hamburger du header). */
    fun openDrawer() {
        requestOpenDrawer = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Status bar système gardée visible (fidèle au mockup update/hasan-rework-mockup.html,
        // qui simule une vraie status bar avec heure/icônes système + un bandeau applicatif de
        // même couleur en dessous — voir --statusbar-h et le commentaire sur .device-statusbar).
        // Le bug précédemment corrigé ici (status bar edge-to-edge interceptant les taps du
        // bouton Menu, targetSdk 35) était dû à l'absence de gestion des WindowInsets, pas à la
        // visibilité de la status bar elle-même : chaque écran applique maintenant
        // Modifier.statusBarsPadding() (HasanHeader/HasanMinimalHeader) pour ne jamais dessiner
        // de contenu interactif sous la status bar, au lieu de la masquer entièrement.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // Icônes système claires (fond BgHeader sombre derrière la status bar) — cohérent
        // avec le thème sombre unique de l'app (HasanTheme n'a pas de variante claire).
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        // Redirige vers l'onboarding au premier lancement
        if (!viewModel.settings.onboardingCompleted) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setupFragments(savedInstanceState)
        setupDrawerRoot()
        setupBackNavigation()

        // Démarre le service wake word si activé dans les préférences ET si RECORD_AUDIO
        // est réellement accordée — les deux sont découplés (préférence utilisateur vs état
        // système), et targetSdk 35 lève une SecurityException NON rattrapable par ce
        // try/catch : startForegroundService() est asynchrone, l'exception survient plus tard
        // dans HassanWakeWordService.onCreate() (Service.startForeground() avec
        // FOREGROUND_SERVICE_TYPE_MICROPHONE sans RECORD_AUDIO), sur un thread hors de portée
        // de ce bloc — observé en crash direct au démarrage après une réinstallation où la
        // permission n'avait pas encore été (re)accordée.
        val hasRecordAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (viewModel.settings.wakeWordEnabled && hasRecordAudio) {
            try {
                startForegroundService(Intent(this, HassanWakeWordService::class.java))
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException (API 31+) — le système peut
                // encore refuser le démarrage pour d'autres raisons (app en arrière-plan,
                // restrictions batterie, etc.) ; ne doit jamais faire planter onCreate().
                android.util.Log.w("MainActivity", "Démarrage du service wake word refusé par le système", e)
            }
        } else if (viewModel.settings.wakeWordEnabled) {
            android.util.Log.w("MainActivity", "Wake word activé mais RECORD_AUDIO non accordée — service non démarré")
        }

        requestNotifPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
    }

    /**
     * launchMode="singleTask" (AndroidManifest.xml) route ici tout relaunch d'une
     * instance déjà en Task (notification wake word retapée, icône du launcher,
     * etc.) au lieu de créer une deuxième instance de MainActivity empilée dans la
     * même Task — c'était le bug derrière l'écran noir après "Quitter" : la
     * deuxième instance masquait la première, jamais mise à jour, et
     * finishAndRemoveTask() ne fermait que le sommet de la pile, révélant en
     * dessous une Activity dans un état non rafraîchi (ComposeView sans contenu
     * valide).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ─────────────────────────── Fragments ───────────────────────────────────

    private fun setupFragments(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            chatFragment = ConversationFragment()
            tasksFragment = TasksFragment()
            kanbanFragment = KanbanFragment()
            memoryFragment = MemoryFragment()
            toolsPermissionsFragment = ToolsPermissionsFragment()
            settingsFragment = SettingsFragment()
        } else {
            // Récupère les fragments existants après rotation
            chatFragment = supportFragmentManager.findFragmentByTag(TAG_CHAT) as? ConversationFragment
                ?: ConversationFragment()
            tasksFragment = supportFragmentManager.findFragmentByTag(TAG_TASKS) as? TasksFragment
                ?: TasksFragment()
            kanbanFragment = supportFragmentManager.findFragmentByTag(TAG_KANBAN) as? KanbanFragment
                ?: KanbanFragment()
            memoryFragment = supportFragmentManager.findFragmentByTag(TAG_MEMORY) as? MemoryFragment
                ?: MemoryFragment()
            toolsPermissionsFragment = supportFragmentManager.findFragmentByTag(TAG_TOOLS_PERMISSIONS) as? ToolsPermissionsFragment
                ?: ToolsPermissionsFragment()
            settingsFragment = supportFragmentManager.findFragmentByTag(TAG_SETTINGS) as? SettingsFragment
                ?: SettingsFragment()
        }
    }

    private fun attachFragmentsIfNeeded(container: View) {
        // AndroidView peut recréer sa factory (recomposition) — n'ajoute les fragments
        // qu'une fois, sinon FragmentManager lève sur un tag déjà attaché.
        if (supportFragmentManager.findFragmentByTag(TAG_CHAT) != null) return
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, chatFragment, TAG_CHAT)
            .add(R.id.fragmentContainer, tasksFragment, TAG_TASKS)
            .add(R.id.fragmentContainer, kanbanFragment, TAG_KANBAN)
            .add(R.id.fragmentContainer, memoryFragment, TAG_MEMORY)
            .add(R.id.fragmentContainer, toolsPermissionsFragment, TAG_TOOLS_PERMISSIONS)
            .add(R.id.fragmentContainer, settingsFragment, TAG_SETTINGS)
            .hide(tasksFragment)
            .hide(kanbanFragment)
            .hide(memoryFragment)
            .hide(toolsPermissionsFragment)
            .hide(settingsFragment)
            .commit()
    }

    // ─────────────────────────── Drawer racine ────────────────────────────────

    private fun setupDrawerRoot() {
        val composeView = ComposeView(this)
        setContentView(composeView)
        composeView.setContent {
            HasanTheme {
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val sessions by viewModel.sessions.collectAsState()

                if (requestOpenDrawer) {
                    requestOpenDrawer = false
                    scope.launch { drawerState.open() }
                }

                // Publie de quoi refermer le drawer vers l'Activity (retour
                // arrière) — le DrawerState n'existe qu'ici. DisposableEffect
                // pour ne pas laisser une lambda capturant un état mort
                // derrière soi si le Composable quitte la composition.
                androidx.compose.runtime.DisposableEffect(drawerState) {
                    closeDrawerIfOpen = {
                        val wasOpen = drawerState.isOpen
                        if (wasOpen) scope.launch { drawerState.close() }
                        wasOpen
                    }
                    onDispose { closeDrawerIfOpen = null }
                }

                androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                    HasanDrawerScaffold(
                        state = buildDrawerState(sessions),
                        callbacks = buildDrawerCallbacks(scope) { scope.launch { drawerState.close() } },
                        drawerState = drawerState
                    ) {
                        // windowInsetsPadding appliqué ICI (ComposeView racine, niveau 1) et
                        // PAS dans HasanHeader/HasanMinimalHeader (qui vivent 2 ComposeView plus
                        // bas, à travers AndroidView → Fragment → ComposeView) — WindowInsets
                        // Compose ne s'est pas propagé de façon fiable jusque-là (bouton Menu +
                        // titre superposés à la vraie status bar système sur device, observé sur
                        // capture). Le padding réservé ici pousse tout le contenu du fragment
                        // (dont son propre header interne, déjà sans padding insets) sous la
                        // status bar en un seul point de vérité.
                        // Bande de status bar (zone du poinçon caméra) peinte en couleur de
                        // header : sans elle, le padding d'insets ci-dessous laissait voir le
                        // fond BgBase au-dessus du header, qui semblait alors "flotter" au
                        // lieu de remonter jusqu'en haut de l'écran comme dans le mockup.
                        // Peinte ici, au même niveau racine que le padding qui la crée —
                        // et non dans HasanHeader/HasanMinimalHeader, qui vivent 2 ComposeView
                        // plus bas (AndroidView → Fragment → ComposeView) où les WindowInsets
                        // ne se propagent pas de façon fiable (voir le commentaire du padding).
                        //
                        // Exclut le mode mains libres, seul écran sans header : sa zone haute
                        // doit rester en BgBase comme le reste de son fond plein cadre.
                        if (!isLightModeActive) {
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .windowInsetsTopHeight(WindowInsets.statusBars)
                                    .background(com.hasan.v1.ui.theme.HasanColors.BgHeader)
                            )
                        }
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                // navigationBars en plus de statusBars : la barre gestuelle système
                                // (home indicator) était collée directement sous le composer sans
                                // marge, observé sur capture device — même raisonnement que pour
                                // status bar, appliqué ici au niveau racine.
                                // ime : réserve l'espace du clavier virtuel — imePadding() posé
                                // directement dans ChatScreen.kt (2 ComposeView plus bas, à travers
                                // AndroidView → Fragment → ComposeView) ne recevait pas l'inset
                                // clavier (composer resté caché sous le clavier). Appliqué ici, au
                                // niveau racine : le AndroidView entier (header + fil de messages +
                                // composer) se comprime vers le haut quand le clavier apparaît — le
                                // header, déjà en haut et de taille fixe, ne "descend" donc jamais
                                // visuellement, seul l'espace sous lui se réduit et le composer
                                // remonte au-dessus du clavier. Comportement confirmé sur device
                                // (windowSoftInputMode="adjustNothing" dans AndroidManifest.xml —
                                // seul Compose gère l'IME, pas de double compensation système).
                                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars).union(WindowInsets.ime)),
                            factory = { ctx ->
                                LayoutInflater.from(ctx).inflate(R.layout.content_fragment_container, null).also {
                                    fragmentContainerRoot = it
                                    attachFragmentsIfNeeded(it)
                                }
                            }
                        )
                    }

                    if (showQuitConfirm) {
                        com.hasan.v1.ui.components.HasanConfirmOverlay(
                            message = getString(R.string.settings_quit_confirm),
                            confirmLabel = getString(R.string.dialog_confirm),
                            cancelLabel = getString(R.string.dialog_cancel),
                            destructive = true,
                            onConfirm = { showQuitConfirm = false; quitApp() },
                            onCancel = { showQuitConfirm = false }
                        )
                    }
                }
            }
        }
    }

    private fun buildDrawerState(sessions: List<HermesSession>): DrawerUiState {
        // Pas de numérotation "01./02." — mockup ligne 1464 : juste le nom de session, un
        // point (sdot) avant le nom si active, l'âge relatif à droite (voir DrawerSessionRow).
        val items = sessions.map { session ->
            DrawerSessionItem(
                id = session.id,
                label = session.name,
                isActive = session.isActive,
                lastMessageAt = session.updatedAt
            )
        }
        return DrawerUiState(
            navItems = listOf(
                HasanNavItem(HasanNavTab.CHAT, R.drawable.ic_chat_nav, getString(R.string.nav_chat)),
                HasanNavItem(HasanNavTab.TASKS, R.drawable.ic_tasks_nav, getString(R.string.nav_tasks)),
                HasanNavItem(HasanNavTab.KANBAN, R.drawable.ic_kanban_nav, getString(R.string.nav_kanban)),
                HasanNavItem(HasanNavTab.MEMORY, R.drawable.ic_mcp_nav, getString(R.string.nav_memory)),
                HasanNavItem(HasanNavTab.TOOLS, R.drawable.ic_tools_nav, getString(R.string.nav_tools)),
                HasanNavItem(HasanNavTab.SETTINGS, R.drawable.ic_settings_nav, getString(R.string.nav_settings))
            ),
            selectedTab = selectedNavTab,
            sessions = items
        )
    }

    private fun buildDrawerCallbacks(
        scope: kotlinx.coroutines.CoroutineScope,
        closeDrawer: () -> Unit
    ) = DrawerCallbacks(
        onNavItemClick = { tab ->
            onNavTabSelected(tab)
            closeDrawer()
        },
        onSessionClick = { id ->
            viewModel.sessions.value.firstOrNull { it.id == id }?.let { viewModel.activateSession(it) }
            onNavTabSelected(HasanNavTab.CHAT)
            closeDrawer()
        },
        onSessionRename = { id ->
            viewModel.sessions.value.firstOrNull { it.id == id }?.let { session ->
                HasanDialog.input(
                    context = this,
                    title = "Renommer",
                    default = session.name,
                    hint = "Nom de la session",
                    onConfirm = { name -> if (name.isNotBlank()) viewModel.renameSession(session, name) }
                )
            }
        },
        onSessionDelete = { id ->
            viewModel.sessions.value.firstOrNull { it.id == id }?.let { session ->
                HasanDialog.confirm(
                    context = this,
                    message = if (session.isActive)
                        "\"${session.name}\" est active.\nUne nouvelle session sera créée automatiquement."
                    else
                        "Supprimer \"${session.name}\" ?",
                    confirmLabel = "Supprimer",
                    cancelLabel = "Annuler",
                    destructive = true,
                    onConfirm = { viewModel.deleteSession(session) }
                )
            }
        },
        onNewSession = {
            viewModel.startPendingSession()
            onNavTabSelected(HasanNavTab.CHAT)
            closeDrawer()
        },
        onQuit = { confirmQuit() },
        onClose = closeDrawer
    )

    private fun onNavTabSelected(tab: HasanNavTab) {
        selectedNavTab = tab
        when (tab) {
            HasanNavTab.CHAT -> showFragment(chatFragment)
            HasanNavTab.TASKS -> showFragment(tasksFragment)
            HasanNavTab.KANBAN -> showFragment(kanbanFragment)
            HasanNavTab.MEMORY -> showFragment(memoryFragment)
            HasanNavTab.TOOLS -> showFragment(toolsPermissionsFragment)
            HasanNavTab.SETTINGS -> showFragment(settingsFragment)
        }
    }

    private fun showFragment(fragment: Fragment) {
        currentFocus?.let { focused ->
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
        val transaction = supportFragmentManager.beginTransaction()
        listOf(chatFragment, tasksFragment, kanbanFragment, memoryFragment, toolsPermissionsFragment, settingsFragment).forEach { transaction.hide(it) }
        transaction.show(fragment).commit()
    }

    // ─────────────────────────── Retour arrière ───────────────────────────────

    /**
     * Retour arrière (geste de swipe ou bouton système) — enregistré une
     * fois dans onCreate.
     *
     * Sans ce callback, le comportement par défaut d'Android était de
     * terminer l'Activity : depuis n'importe quel onglet ou overlay, un
     * swipe back renvoyait directement au launcher, sans confirmation et
     * sans jamais "remonter" d'un cran (issue #3).
     *
     * L'app n'a pas de back stack fragment à dépiler : les six onglets sont
     * ajoutés une fois pour toutes puis show/hide (voir [showFragment]), et
     * les overlays (mains libres, Logs, Fichiers) sont add/remove manuels.
     * La hiérarchie de retour est donc reconstruite explicitement ici, de
     * la couche la plus superficielle à la plus profonde :
     *
     * 1. overlay de confirmation "Quitter" ouvert → le refermer ;
     * 2. drawer ouvert → le refermer ;
     * 3. overlay plein écran (mains libres / Logs / Fichiers) → revenir à
     *    l'écran qui l'a ouvert, via son propre `close*()` (chacun sait où
     *    retourner : Logs → Réglages, Fichiers/mains libres → Chat). Fichiers
     *    remonte d'abord son arborescence ([BackHandledScreen]) avant de se
     *    fermer ;
     * 4. profondeur interne à l'onglet courant ([BackHandledScreen] : éditeur
     *    de tâche, détail Kanban/Mémoire…) → refermer
     *    cette couche, l'onglet reste affiché ;
     * 5. onglet secondaire déjà à sa racine → revenir au Chat, l'onglet
     *    d'accueil ;
     * 6. page principale (Chat) → demander confirmation avant de quitter.
     *
     * Seul le cas 6 quitte réellement l'app, et jamais sans passer par
     * [confirmQuit].
     */
    /** Le Fragment de l'onglet actuellement affiché — support de [BackHandledScreen]. */
    private fun currentTabFragment(): Fragment = when (selectedNavTab) {
        HasanNavTab.CHAT -> chatFragment
        HasanNavTab.TASKS -> tasksFragment
        HasanNavTab.KANBAN -> kanbanFragment
        HasanNavTab.MEMORY -> memoryFragment
        HasanNavTab.TOOLS -> toolsPermissionsFragment
        HasanNavTab.SETTINGS -> settingsFragment
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    showQuitConfirm -> showQuitConfirm = false
                    closeDrawerIfOpen?.invoke() == true -> Unit
                    isLightModeActive -> exitLightMode()
                    logsFragment != null -> closeLogs()
                    filesFragment?.onBackPressed() == true -> Unit
                    filesFragment != null -> closeFiles()
                    (currentTabFragment() as? BackHandledScreen)?.onBackPressed() == true -> Unit
                    selectedNavTab != HasanNavTab.CHAT -> onNavTabSelected(HasanNavTab.CHAT)
                    else -> confirmQuit()
                }
            }
        })
    }

    // ─────────────────────────── Quitter l'app ────────────────────────────────

    /**
     * Extrait de l'ancien SettingsFragment.confirmQuit() — vit ici car kill process
     * et arrêt de services sont des opérations Activity, pas ViewModel/Fragment.
     * Affiche HasanConfirmOverlay (Compose, DA de l'app) plutôt qu'un AlertDialog
     * système — voir showQuitConfirm dans setupDrawerRoot().
     */
    fun confirmQuit() {
        showQuitConfirm = true
    }

    private fun quitApp() {
        viewModel.stopTts()

        // Annule la notification persistante immédiatement — l'arrêt du service
        // est asynchrone et killProcess() peut intervenir avant son traitement.
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.cancelAll()

        // ACTION_STOP (pas stopService() brut) : le service retourne explicitement
        // START_NOT_STICKY après stopForeground(STOP_FOREGROUND_REMOVE). stopService()
        // seul se contente de poster une demande d'arrêt asynchrone — si killProcess()
        // intervient avant qu'Android l'ait traitée, le système peut interpréter la mort
        // du process comme un kill mémoire externe et relancer le service en
        // START_STICKY, laissant le wake word actif en tâche de fond malgré "Quitter".
        startService(Intent(this, HassanWakeWordService::class.java).apply {
            action = HassanWakeWordService.ACTION_STOP
        })

        finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ─────────────────────────── Mode Light ─────────────────────────────────

    fun enterLightMode() {
        val fragment = LightModeFragment()
        lightModeFragment = fragment
        isLightModeActive = true
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, fragment, TAG_LIGHT)
            .hide(chatFragment)
            .hide(tasksFragment)
            .hide(kanbanFragment)
            .hide(memoryFragment)
            .hide(toolsPermissionsFragment)
            .hide(settingsFragment)
            .commit()
    }

    fun exitLightMode() {
        lightModeFragment?.let { frag ->
            supportFragmentManager.beginTransaction()
                .remove(frag)
                .show(chatFragment)
                .commit()
            lightModeFragment = null
        }
        isLightModeActive = false
        selectedNavTab = HasanNavTab.CHAT
    }

    // ─────────────────────────── Logs ────────────────────────────────────────

    /**
     * Affiche l'écran "Logs" en overlay plein écran par-dessus les fragments
     * principaux — même pattern que enterLightMode()/exitLightMode() ci-dessus.
     * Appelé depuis SettingsScreen (SettingsRow "Logs →") — pas d'onglet dédié
     * dans la sidebar, pour ne pas la charger avec un usage occasionnel/diagnostic.
     */
    fun openLogs() {
        val fragment = ActivityFragment()
        logsFragment = fragment
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, fragment, TAG_LOGS)
            .hide(chatFragment)
            .hide(tasksFragment)
            .hide(kanbanFragment)
            .hide(memoryFragment)
            .hide(toolsPermissionsFragment)
            .hide(settingsFragment)
            .commit()
    }

    fun closeLogs() {
        logsFragment?.let { frag ->
            supportFragmentManager.beginTransaction()
                .remove(frag)
                .show(settingsFragment)
                .commit()
            logsFragment = null
        }
        selectedNavTab = HasanNavTab.SETTINGS
    }

    // ─────────────────────────── Fichiers ──────────────────────────────────────

    /**
     * Affiche l'écran "Fichiers" (workspace hermes-webui, partagé entre
     * sessions dans la config par défaut — voir docs/ARCHITECTURE.md#fichiers)
     * en overlay plein écran — même pattern que openLogs()/closeLogs() : usage
     * occasionnel, pas d'onglet dédié dans la sidebar. Ouvert depuis le
     * bouton flottant de ChatScreen (haut droit, sous le header).
     */
    fun openFiles() {
        val fragment = FilesFragment()
        filesFragment = fragment
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, fragment, TAG_FILES)
            .hide(chatFragment)
            .hide(tasksFragment)
            .hide(kanbanFragment)
            .hide(memoryFragment)
            .hide(toolsPermissionsFragment)
            .hide(settingsFragment)
            .commit()
    }

    fun closeFiles() {
        filesFragment?.let { frag ->
            supportFragmentManager.beginTransaction()
                .remove(frag)
                .show(chatFragment)
                .commit()
            filesFragment = null
        }
        selectedNavTab = HasanNavTab.CHAT
    }

    companion object {
        private const val TAG_CHAT     = "chat_fragment"
        private const val TAG_TASKS    = "tasks_fragment"
        private const val TAG_KANBAN   = "kanban_fragment"
        private const val TAG_FILES    = "files_fragment"
        private const val TAG_MEMORY   = "memory_fragment"
        private const val TAG_SETTINGS = "settings_fragment"
        private const val TAG_LIGHT    = "light_fragment"
        private const val TAG_TOOLS_PERMISSIONS = "tools_permissions_fragment"
        private const val TAG_LOGS = "logs_fragment"
    }
}
