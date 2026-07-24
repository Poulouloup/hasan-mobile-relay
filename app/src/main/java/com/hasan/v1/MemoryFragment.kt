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
import com.hasan.v1.databinding.FragmentMemoryBinding
import com.hasan.v1.ui.screens.MemoryCallbacks
import com.hasan.v1.ui.screens.MemoryScreen
import com.hasan.v1.ui.screens.MemoryScreenUiState
import com.hasan.v1.ui.screens.SkillDetailCallbacks
import com.hasan.v1.ui.screens.SkillDetailScreen
import com.hasan.v1.ui.screens.SkillsCallbacks
import com.hasan.v1.ui.screens.SkillsScreen
import com.hasan.v1.ui.screens.SkillsScreenUiState
import com.hasan.v1.ui.theme.HasanTheme

/**
 * Onglet Memory & Insights (lecture seule) — étape 4.5 de la migration webui.
 * Trois onglets internes (MemoryTab.MEMORY / SKILLS / INSIGHTS) gérés par
 * l'état du ViewModel. SKILLS fusionné ici (ex-onglet drawer séparé,
 * SkillsFragment) pour désencombrer la sidebar — SkillsViewModel reste
 * inchangé, juste hébergé par ce Fragment en plus de MemoryViewModel.
 */
class MemoryFragment : Fragment() {

    private val viewModel: MemoryViewModel by activityViewModels()
    private val skillsViewModel: SkillsViewModel by activityViewModels()

    private var _binding: FragmentMemoryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMemoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val composeView = binding.memoryComposeRoot as ComposeView
        composeView.setContent {
            HasanTheme {
                val state by viewModel.uiState.collectAsState()
                val skillsState by skillsViewModel.uiState.collectAsState()

                MemoryScreen(
                    state = MemoryScreenUiState(
                        selectedTab = state.selectedTab,
                        selectedFile = state.selectedFile,
                        memory = state.memory,
                        insights = state.insights,
                        loading = state.loading,
                        errorMessage = state.errorMessage
                    ),
                    callbacks = MemoryCallbacks(
                        onMenuClick = { (activity as? MainActivity)?.openDrawer() },
                        onSelectTab = { tab -> viewModel.selectTab(tab) },
                        onOpenFile = { file -> viewModel.openFile(file) },
                        onCloseFile = { viewModel.closeFile() },
                        onRefresh = { viewModel.refresh() },
                        onDismissError = { viewModel.clearError() }
                    ),
                    skillsContent = {
                        if (skillsState.selectedSkillName != null) {
                            SkillDetailScreen(
                                skillName = skillsState.selectedSkillName!!,
                                detail = skillsState.selectedSkillDetail,
                                loading = skillsState.detailLoading,
                                callbacks = SkillDetailCallbacks(onBack = { skillsViewModel.closeDetail() })
                            )
                        } else {
                            SkillsScreen(
                                state = SkillsScreenUiState(
                                    skills = skillsState.skills,
                                    usage = skillsState.usage,
                                    loading = skillsState.loading,
                                    errorMessage = skillsState.errorMessage
                                ),
                                callbacks = SkillsCallbacks(
                                    onMenuClick = { (activity as? MainActivity)?.openDrawer() },
                                    onRefresh = { skillsViewModel.refresh() },
                                    onSkillClick = { skill -> skillsViewModel.openDetail(skill) },
                                    onDismissError = { skillsViewModel.clearError() }
                                ),
                                showMenuHeader = false
                            )
                        }
                    }
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
