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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
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
 * Activité racine — drawer Compose (menu tiroir) avec 3 onglets : Chat, Activité
 * et Réglages, plus la liste des sessions Hermes (étape 10, remplace la
 * BottomNavigation — voir reworkui.md).
 *
 * Responsabilités :
 *  - Orchestration du drawer (ouverture/fermeture, seul endroit autorisé par
 *    .claude/rules/architecture.md — "Drawer latéral géré par MainActivity")
 *  - Swap de fragments (ChatFragment ↔ ActivityFragment ↔ SettingsFragment)
 *  - Démarrage du service wake word si activé
 *  - Expose le ViewModel partagé aux fragments via activityViewModels()
 */
class MainActivity : AppCompatActivity() {

    val viewModel: MainViewModel by viewModels()

    private lateinit var chatFragment: ConversationFragment
    private lateinit var tasksFragment: TasksFragment
    private lateinit var skillsFragment: SkillsFragment
    private lateinit var activityFragment: ActivityFragment
    private lateinit var settingsFragment: SettingsFragment
    private var lightModeFragment: LightModeFragment? = null
    private var toolsPermissionsFragment: ToolsPermissionsFragment? = null

    private var selectedNavTab by mutableStateOf(HasanNavTab.CHAT)
    private var fragmentContainerRoot: View? = null

    /** Piloté depuis openDrawer() — fermé/ouvert par le Composable via son propre DrawerState. */
    private var requestOpenDrawer by mutableStateOf(false)

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — service démarré dans onCreate de toute façon */ }

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val text = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_TEXT)
            if (!text.isNullOrBlank()) viewModel.pairFromQr(text)
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

        // Redirige vers l'onboarding au premier lancement
        if (!viewModel.settings.onboardingCompleted) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setupFragments(savedInstanceState)
        setupDrawerRoot()

        // Démarre le service wake word si activé dans les préférences
        if (viewModel.settings.wakeWordEnabled) {
            startForegroundService(Intent(this, HassanWakeWordService::class.java))
        }

        requestNotifPermissionIfNeeded()
        startForegroundService(Intent(this, HassanNotificationService::class.java))
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
            skillsFragment = SkillsFragment()
            activityFragment = ActivityFragment()
            settingsFragment = SettingsFragment()
        } else {
            // Récupère les fragments existants après rotation
            chatFragment = supportFragmentManager.findFragmentByTag(TAG_CHAT) as? ConversationFragment
                ?: ConversationFragment()
            tasksFragment = supportFragmentManager.findFragmentByTag(TAG_TASKS) as? TasksFragment
                ?: TasksFragment()
            skillsFragment = supportFragmentManager.findFragmentByTag(TAG_SKILLS) as? SkillsFragment
                ?: SkillsFragment()
            activityFragment = supportFragmentManager.findFragmentByTag(TAG_ACTIVITY) as? ActivityFragment
                ?: ActivityFragment()
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
            .add(R.id.fragmentContainer, skillsFragment, TAG_SKILLS)
            .add(R.id.fragmentContainer, activityFragment, TAG_ACTIVITY)
            .add(R.id.fragmentContainer, settingsFragment, TAG_SETTINGS)
            .hide(tasksFragment)
            .hide(skillsFragment)
            .hide(activityFragment)
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

                HasanDrawerScaffold(
                    state = buildDrawerState(sessions),
                    callbacks = buildDrawerCallbacks(scope) { scope.launch { drawerState.close() } },
                    drawerState = drawerState
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            LayoutInflater.from(ctx).inflate(R.layout.content_fragment_container, null).also {
                                fragmentContainerRoot = it
                                attachFragmentsIfNeeded(it)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun buildDrawerState(sessions: List<HermesSession>): DrawerUiState {
        val items = sessions.mapIndexed { index, session ->
            DrawerSessionItem(
                id = session.id,
                label = "${(index + 1).toString().padStart(2, '0')}. ${session.name}",
                isActive = session.isActive
            )
        }
        return DrawerUiState(
            navItems = listOf(
                HasanNavItem(HasanNavTab.CHAT, R.drawable.ic_chat_nav, getString(R.string.nav_chat)),
                HasanNavItem(HasanNavTab.TASKS, R.drawable.ic_tasks_nav, getString(R.string.nav_tasks)),
                HasanNavItem(HasanNavTab.SKILLS, R.drawable.ic_skills_nav, getString(R.string.nav_skills)),
                HasanNavItem(HasanNavTab.ACTIVITY, R.drawable.ic_activity_nav, getString(R.string.nav_activity)),
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
            HasanNavTab.SKILLS -> showFragment(skillsFragment)
            HasanNavTab.ACTIVITY -> showFragment(activityFragment)
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
        listOf(chatFragment, tasksFragment, skillsFragment, activityFragment, settingsFragment).forEach { transaction.hide(it) }
        transaction.show(fragment).commit()
    }

    // ─────────────────────────── Quitter l'app ────────────────────────────────

    /**
     * Extrait de l'ancien SettingsFragment.confirmQuit() — vit ici car kill process
     * et arrêt de services sont des opérations Activity, pas ViewModel/Fragment.
     */
    fun confirmQuit() {
        HasanDialog.confirm(
            context = this,
            message = getString(R.string.settings_quit_confirm),
            confirmLabel = getString(R.string.dialog_confirm),
            cancelLabel = getString(R.string.dialog_cancel),
            onConfirm = {
                viewModel.stopTts()

                // Annule la notification persistante immédiatement — les ACTION_STOP
                // sont asynchrones et killProcess() peut intervenir avant leur traitement.
                val nm = getSystemService(android.app.NotificationManager::class.java)
                nm.cancelAll()

                stopService(Intent(this, HassanWakeWordService::class.java))
                stopService(Intent(this, HassanNotificationService::class.java))

                finishAndRemoveTask()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        )
    }

    // ─────────────────────────── Mode Light ─────────────────────────────────

    fun enterLightMode() {
        val fragment = LightModeFragment()
        lightModeFragment = fragment
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, fragment, TAG_LIGHT)
            .hide(chatFragment)
            .hide(tasksFragment)
            .hide(skillsFragment)
            .hide(activityFragment)
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
        selectedNavTab = HasanNavTab.CHAT
    }

    // ─────────────────────────── Tools & Permissions ────────────────────────

    /**
     * Affiche l'écran "Tools & Permissions" en overlay plein écran par-dessus les 3
     * fragments principaux — même pattern que enterLightMode()/exitLightMode() ci-dessus.
     * Appelé depuis SettingsScreen (SettingsRow "Tools & Permissions →").
     */
    fun openToolsPermissions() {
        val fragment = ToolsPermissionsFragment()
        toolsPermissionsFragment = fragment
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, fragment, TAG_TOOLS_PERMISSIONS)
            .hide(chatFragment)
            .hide(tasksFragment)
            .hide(skillsFragment)
            .hide(activityFragment)
            .hide(settingsFragment)
            .commit()
    }

    fun closeToolsPermissions() {
        toolsPermissionsFragment?.let { frag ->
            supportFragmentManager.beginTransaction()
                .remove(frag)
                .show(settingsFragment)
                .commit()
            toolsPermissionsFragment = null
        }
        selectedNavTab = HasanNavTab.SETTINGS
    }

    companion object {
        private const val TAG_CHAT     = "chat_fragment"
        private const val TAG_TASKS    = "tasks_fragment"
        private const val TAG_SKILLS   = "skills_fragment"
        private const val TAG_ACTIVITY = "activity_fragment"
        private const val TAG_SETTINGS = "settings_fragment"
        private const val TAG_LIGHT    = "light_fragment"
        private const val TAG_TOOLS_PERMISSIONS = "tools_permissions_fragment"
    }
}
