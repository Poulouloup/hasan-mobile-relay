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
import com.hasan.v1.databinding.FragmentSkillsBinding
import com.hasan.v1.ui.screens.SkillDetailCallbacks
import com.hasan.v1.ui.screens.SkillDetailScreen
import com.hasan.v1.ui.screens.SkillsCallbacks
import com.hasan.v1.ui.screens.SkillsScreen
import com.hasan.v1.ui.screens.SkillsScreenUiState
import com.hasan.v1.ui.theme.HasanTheme

/**
 * Onglet Skills (lecture seule) — étape 4.2 de la migration webui.
 * Navigation interne liste/détail gérée par l'état du ViewModel
 * (selectedSkillName), même principe que TasksFragment.
 */
class SkillsFragment : Fragment() {

    private val viewModel: SkillsViewModel by activityViewModels()

    private var _binding: FragmentSkillsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSkillsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val composeView = binding.skillsComposeRoot as ComposeView
        composeView.setContent {
            HasanTheme {
                val state by viewModel.uiState.collectAsState()

                if (state.selectedSkillName != null) {
                    SkillDetailScreen(
                        skillName = state.selectedSkillName!!,
                        detail = state.selectedSkillDetail,
                        loading = state.detailLoading,
                        callbacks = SkillDetailCallbacks(onBack = { viewModel.closeDetail() })
                    )
                } else {
                    SkillsScreen(
                        state = SkillsScreenUiState(
                            skills = state.skills,
                            usage = state.usage,
                            loading = state.loading,
                            errorMessage = state.errorMessage
                        ),
                        callbacks = SkillsCallbacks(
                            onMenuClick = { (activity as? MainActivity)?.openDrawer() },
                            onRefresh = { viewModel.refresh() },
                            onSkillClick = { skill -> viewModel.openDetail(skill) }
                        )
                    )
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
