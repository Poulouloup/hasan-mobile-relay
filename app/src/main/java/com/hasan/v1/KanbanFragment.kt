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
import com.hasan.v1.databinding.FragmentKanbanBinding
import com.hasan.v1.ui.screens.KanbanCallbacks
import com.hasan.v1.ui.screens.KanbanDetailCallbacks
import com.hasan.v1.ui.screens.KanbanScreen
import com.hasan.v1.ui.screens.KanbanScreenUiState
import com.hasan.v1.ui.screens.KanbanTaskDetailScreen
import com.hasan.v1.ui.theme.HasanTheme

/**
 * Onglet Kanban — consomme l'API Kanban de hermes-webui
 * (~/hermes-webui/api/kanban_bridge.py), jusqu'ici utilisée seulement par le
 * frontend web statique de hermes-webui. Navigation interne board/détail
 * gérée par l'état du ViewModel (selectedTaskId), même principe que Skills.
 */
class KanbanFragment : Fragment() {

    private val viewModel: KanbanViewModel by activityViewModels()

    private var _binding: FragmentKanbanBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKanbanBinding.inflate(inflater, container, false)
        return binding.root
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val composeView = binding.kanbanComposeRoot as ComposeView
        composeView.setContent {
            HasanTheme {
                val state by viewModel.uiState.collectAsState()

                if (state.selectedTaskId != null) {
                    KanbanTaskDetailScreen(
                        task = state.selectedTaskDetail,
                        loading = state.detailLoading,
                        callbacks = KanbanDetailCallbacks(onBack = { viewModel.closeTaskDetail() })
                    )
                } else {
                    KanbanScreen(
                        state = KanbanScreenUiState(
                            board = state.board,
                            boards = state.boards,
                            selectedBoardSlug = state.selectedBoardSlug,
                            loading = state.loading,
                            errorMessage = state.errorMessage,
                            showCreateBoardDialog = state.showCreateBoardDialog,
                            showCreateTaskDialog = state.showCreateTaskDialog
                        ),
                        callbacks = KanbanCallbacks(
                            onMenuClick = { (activity as? MainActivity)?.openDrawer() },
                            onRefresh = { viewModel.refresh() },
                            onTaskClick = { task -> viewModel.openTaskDetail(task) },
                            onMoveTask = { task, newStatus -> viewModel.moveTask(task, newStatus) },
                            onSelectBoard = { slug -> viewModel.selectBoard(slug) },
                            onShowCreateBoard = { viewModel.showCreateBoardDialog() },
                            onDismissCreateBoard = { viewModel.dismissCreateBoardDialog() },
                            onCreateBoard = { slug, name -> viewModel.createBoard(slug, name) },
                            onShowCreateTask = { viewModel.showCreateTaskDialog() },
                            onDismissCreateTask = { viewModel.dismissCreateTaskDialog() },
                            onCreateTask = { title, body, status -> viewModel.createTask(title, body, status) },
                            onDismissError = { viewModel.clearError() }
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
