package com.hasan.v1

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.hasan.v1.databinding.FragmentActivityBinding
import com.hasan.v1.ui.screens.ActivityScreen
import com.hasan.v1.ui.theme.HasanTheme

/**
 * Onglet Activité (étape 9, remplace l'ancien onglet MCP dans la bottom nav) —
 * flux d'événements (connexions relay, certificats, enveloppes system/proactive),
 * journalisé en mémoire par [MainViewModel.activityLog] (voir ActivityLog.kt, pas
 * de persistance Room : historique repart à zéro à chaque redémarrage de l'app).
 */
class ActivityFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private var _binding: FragmentActivityBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentActivityBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val composeView = binding.activityComposeRoot as ComposeView
        composeView.setContent {
            HasanTheme {
                val events by viewModel.activityLog.events.collectAsState()
                ActivityScreen(
                    events = events,
                    onMenuClick = { (activity as? MainActivity)?.openDrawer() }
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
