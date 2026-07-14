package com.hasan.v1

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.hasan.v1.databinding.ActivityMainBinding
import com.hasan.v1.ui.components.HasanBottomNav
import com.hasan.v1.ui.components.HasanNavItem
import com.hasan.v1.ui.components.HasanNavTab
import com.hasan.v1.ui.theme.HasanTheme

/**
 * Activité racine — BottomNavigationView avec 3 onglets : Chat, Activité et Réglages
 * (étape 9, mockup V2 — remplace l'ancien onglet MCP ; le contenu de connexion
 * orchestrateur de McpFragment migre dans SettingsFragment > section Connexion).
 *
 * Responsabilités :
 *  - Swap de fragments (ChatFragment ↔ ActivityFragment ↔ SettingsFragment)
 *  - Démarrage du service wake word si activé
 *  - Expose le ViewModel partagé aux fragments via activityViewModels()
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()

    private lateinit var chatFragment: ConversationFragment
    private lateinit var activityFragment: ActivityFragment
    private lateinit var settingsFragment: SettingsFragment
    private var lightModeFragment: LightModeFragment? = null

    private var selectedNavTab by mutableStateOf(HasanNavTab.CHAT)

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Redirige vers l'onboarding au premier lancement
        if (!viewModel.settings.onboardingCompleted) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFragments(savedInstanceState)
        setupBottomNav()

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
            activityFragment = ActivityFragment()
            settingsFragment = SettingsFragment()

            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, chatFragment, TAG_CHAT)
                .add(R.id.fragmentContainer, activityFragment, TAG_ACTIVITY)
                .add(R.id.fragmentContainer, settingsFragment, TAG_SETTINGS)
                .hide(activityFragment)
                .hide(settingsFragment)
                .commit()
        } else {
            // Récupère les fragments existants après rotation
            chatFragment = supportFragmentManager.findFragmentByTag(TAG_CHAT) as? ConversationFragment
                ?: ConversationFragment()
            activityFragment = supportFragmentManager.findFragmentByTag(TAG_ACTIVITY) as? ActivityFragment
                ?: ActivityFragment()
            settingsFragment = supportFragmentManager.findFragmentByTag(TAG_SETTINGS) as? SettingsFragment
                ?: SettingsFragment()
        }
    }

    // ─────────────────────────── BottomNav ───────────────────────────────────

    private fun setupBottomNav() {
        (binding.bottomNavCompose as ComposeView).setContent {
            HasanTheme {
                HasanBottomNav(
                    items = listOf(
                        HasanNavItem(HasanNavTab.CHAT, R.drawable.ic_chat_nav, getString(R.string.nav_chat)),
                        HasanNavItem(HasanNavTab.ACTIVITY, R.drawable.ic_activity_nav, getString(R.string.nav_activity)),
                        HasanNavItem(HasanNavTab.SETTINGS, R.drawable.ic_settings_nav, getString(R.string.nav_settings))
                    ),
                    selected = selectedNavTab,
                    onSelect = ::onNavTabSelected
                )
            }
        }
    }

    private fun onNavTabSelected(tab: HasanNavTab) {
        selectedNavTab = tab
        when (tab) {
            HasanNavTab.CHAT -> showFragment(chatFragment)
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
        listOf(chatFragment, activityFragment, settingsFragment).forEach { transaction.hide(it) }
        transaction.show(fragment).commit()
    }

    // ─────────────────────────── Mode Light ─────────────────────────────────

    fun enterLightMode() {
        val fragment = LightModeFragment()
        lightModeFragment = fragment
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, fragment, TAG_LIGHT)
            .hide(chatFragment)
            .hide(activityFragment)
            .hide(settingsFragment)
            .commit()
        binding.bottomNavCompose.visibility = View.GONE
    }

    fun exitLightMode() {
        lightModeFragment?.let { frag ->
            supportFragmentManager.beginTransaction()
                .remove(frag)
                .show(chatFragment)
                .commit()
            lightModeFragment = null
        }
        binding.bottomNavCompose.visibility = View.VISIBLE
        selectedNavTab = HasanNavTab.CHAT
    }

    companion object {
        private const val TAG_CHAT     = "chat_fragment"
        private const val TAG_ACTIVITY = "activity_fragment"
        private const val TAG_SETTINGS = "settings_fragment"
        private const val TAG_LIGHT    = "light_fragment"
    }
}
