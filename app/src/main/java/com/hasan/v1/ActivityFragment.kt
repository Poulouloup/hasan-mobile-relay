package com.hasan.v1

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.hasan.v1.databinding.FragmentActivityBinding
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanTheme

/**
 * Onglet Activité (étape 9, remplace l'ancien onglet MCP dans la bottom nav) —
 * flux d'événements (connexions relay, messages, pairing). Placeholder en 9.1,
 * contenu réel câblé en 9.2 sur les événements du ChannelMultiplexer/relay.
 */
class ActivityFragment : Fragment() {

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
        (binding.activityComposeRoot as ComposeView).setContent {
            HasanTheme { ActivityPlaceholder() }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

@Composable
private fun ActivityPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "Activité — à venir (étape 9.2)", color = HasanColors.TextSecondary)
    }
}
