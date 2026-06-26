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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.hasan.v1.databinding.ActivityMainBinding

/**
 * Activité racine — BottomNavigationView avec 3 onglets : Chat, MCP et Réglages.
 *
 * Responsabilités :
 *  - Swap de fragments (ChatFragment ↔ McpFragment ↔ SettingsFragment)
 *  - Démarrage du service wake word si activé
 *  - Expose le ViewModel partagé aux fragments via activityViewModels()
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()

    private lateinit var chatFragment: ConversationFragment
    private lateinit var mcpFragment: McpFragment
    private lateinit var settingsFragment: SettingsFragment
    private var lightModeFragment: LightModeFragment? = null

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — service démarré dans onCreate de toute façon */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        // Démarre le service orchestrateur MCP si une connexion était active
        if (viewModel.settings.orchestratorConnected) {
            startForegroundService(Intent(this, HassanOrchestratorService::class.java))
        }
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
            mcpFragment = McpFragment()
            settingsFragment = SettingsFragment()

            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, chatFragment, TAG_CHAT)
                .add(R.id.fragmentContainer, mcpFragment, TAG_MCP)
                .add(R.id.fragmentContainer, settingsFragment, TAG_SETTINGS)
                .hide(mcpFragment)
                .hide(settingsFragment)
                .commit()
        } else {
            // Récupère les fragments existants après rotation
            chatFragment = supportFragmentManager.findFragmentByTag(TAG_CHAT) as? ConversationFragment
                ?: ConversationFragment()
            mcpFragment = supportFragmentManager.findFragmentByTag(TAG_MCP) as? McpFragment
                ?: McpFragment()
            settingsFragment = supportFragmentManager.findFragmentByTag(TAG_SETTINGS) as? SettingsFragment
                ?: SettingsFragment()
        }
    }

    // ─────────────────────────── BottomNav ───────────────────────────────────

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chat -> {
                    showFragment(chatFragment)
                    true
                }
                R.id.nav_mcp -> {
                    showFragment(mcpFragment)
                    true
                }
                R.id.nav_settings -> {
                    showFragment(settingsFragment)
                    true
                }
                else -> false
            }
        }
        // Onglet Chat sélectionné par défaut
        binding.bottomNav.selectedItemId = R.id.nav_chat
    }

    private fun showFragment(fragment: Fragment) {
        currentFocus?.let { focused ->
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
        val transaction = supportFragmentManager.beginTransaction()
        listOf(chatFragment, mcpFragment, settingsFragment).forEach { transaction.hide(it) }
        transaction.show(fragment).commit()
    }

    // ─────────────────────────── Mode Light ─────────────────────────────────

    fun enterLightMode() {
        val fragment = LightModeFragment()
        lightModeFragment = fragment
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, fragment, TAG_LIGHT)
            .hide(chatFragment)
            .hide(mcpFragment)
            .hide(settingsFragment)
            .commit()
        binding.bottomNav.visibility = View.GONE
    }

    fun exitLightMode() {
        lightModeFragment?.let { frag ->
            supportFragmentManager.beginTransaction()
                .remove(frag)
                .show(chatFragment)
                .commit()
            lightModeFragment = null
        }
        binding.bottomNav.visibility = View.VISIBLE
        binding.bottomNav.selectedItemId = R.id.nav_chat
    }

    companion object {
        private const val TAG_CHAT     = "chat_fragment"
        private const val TAG_MCP      = "mcp_fragment"
        private const val TAG_SETTINGS = "settings_fragment"
        private const val TAG_LIGHT    = "light_fragment"
    }
}
