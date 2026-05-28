package com.hasan.v1

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.hasan.v1.databinding.ActivityMainBinding

/**
 * Activité racine — BottomNavigationView avec 2 onglets : Chat et Réglages.
 *
 * Responsabilités :
 *  - Swap de fragments (ChatFragment ↔ SettingsFragment)
 *  - Démarrage du service wake word si activé
 *  - Expose le ViewModel partagé aux fragments via activityViewModels()
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()

    private lateinit var chatFragment: ConversationFragment
    private lateinit var settingsFragment: SettingsFragment

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
    }

    // ─────────────────────────── Fragments ───────────────────────────────────

    private fun setupFragments(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            chatFragment = ConversationFragment()
            settingsFragment = SettingsFragment()

            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, chatFragment, TAG_CHAT)
                .add(R.id.fragmentContainer, settingsFragment, TAG_SETTINGS)
                .hide(settingsFragment)
                .commit()
        } else {
            // Récupère les fragments existants après rotation
            chatFragment = supportFragmentManager.findFragmentByTag(TAG_CHAT) as? ConversationFragment
                ?: ConversationFragment()
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
        val transaction = supportFragmentManager.beginTransaction()
        // Cache tous les fragments puis affiche celui sélectionné
        listOf(chatFragment, settingsFragment).forEach { transaction.hide(it) }
        transaction.show(fragment).commit()
    }

    companion object {
        private const val TAG_CHAT     = "chat_fragment"
        private const val TAG_SETTINGS = "settings_fragment"
    }
}
