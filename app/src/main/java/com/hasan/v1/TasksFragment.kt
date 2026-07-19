package com.hasan.v1

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.hasan.v1.databinding.FragmentTasksBinding
import com.hasan.v1.ui.screens.TaskEditorScreen
import com.hasan.v1.ui.screens.TasksCallbacks
import com.hasan.v1.ui.screens.TasksScreen
import com.hasan.v1.ui.screens.TasksScreenUiState
import com.hasan.v1.ui.theme.HasanTheme
import com.hasan.v1.utils.HasanDialog
import com.hasan.v1.webui.models.CronJob
import com.hasan.v1.webui.models.DeliveryOption
import kotlinx.coroutines.launch

/**
 * Onglet Tasks (cron jobs hermes-webui) — étape 4.1 de la migration webui.
 * Navigation interne liste/éditeur gérée par état Compose local (pas de
 * second Fragment), même pattern MVVM que les autres fragments.
 */
class TasksFragment : Fragment() {

    private val viewModel: TasksViewModel by activityViewModels()

    private var _binding: FragmentTasksBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTasksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val composeView = binding.tasksComposeRoot as ComposeView
        composeView.setContent {
            HasanTheme {
                val state by viewModel.uiState.collectAsState()
                var editingJob by remember { mutableStateOf<CronJob?>(null) }
                var editorOpen by remember { mutableStateOf(false) }
                var deliveryOptions by remember { mutableStateOf<List<DeliveryOption>>(emptyList()) }

                LaunchedEffect(Unit) {
                    deliveryOptions = viewModel.deliveryOptions()
                }

                if (editorOpen) {
                    TaskEditorScreen(
                        initialJob = editingJob,
                        deliveryOptions = deliveryOptions,
                        errorMessage = state.errorMessage,
                        onSave = { prompt, schedule, name, deliver ->
                            val job = editingJob
                            if (job == null) {
                                viewModel.createJob(prompt, schedule, name, deliver)
                            } else {
                                viewModel.updateJob(job, prompt, schedule, name, deliver)
                            }
                            editorOpen = false
                            editingJob = null
                        },
                        onCancel = {
                            viewModel.clearError()
                            editorOpen = false
                            editingJob = null
                        },
                        onPickDelivery = { options, onPicked ->
                            HasanDialog.list(
                                context = requireContext(),
                                title = "Livraison",
                                items = options.map { it.label }
                            ) { index -> onPicked(options[index]) }
                        }
                    )
                } else {
                    TasksScreen(
                        state = TasksScreenUiState(
                            jobs = state.jobs,
                            loading = state.loading,
                            errorMessage = state.errorMessage,
                            runningJobIds = state.runningJobIds
                        ),
                        callbacks = TasksCallbacks(
                            onMenuClick = { (activity as? MainActivity)?.openDrawer() },
                            onRefresh = { viewModel.refresh() },
                            onNewTask = {
                                editingJob = null
                                editorOpen = true
                            },
                            onEditTask = { job ->
                                editingJob = job
                                editorOpen = true
                            },
                            onToggleEnabled = { job -> viewModel.toggleEnabled(job) },
                            onRunNow = { job -> viewModel.runNow(job) },
                            onShowHistory = { job -> showHistoryDialog(job) },
                            onDeleteTask = { job -> confirmDelete(job) },
                            onDismissError = { viewModel.clearError() }
                        )
                    )
                }
            }
        }
    }

    private fun confirmDelete(job: CronJob) {
        HasanDialog.confirm(
            context = requireContext(),
            title = "Supprimer la tâche ?",
            message = "« ${job.name} » sera définitivement supprimée, ainsi que son historique.",
            confirmLabel = "Supprimer",
            destructive = true,
            onConfirm = { viewModel.deleteJob(job) }
        )
    }

    private fun showHistoryDialog(job: CronJob) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.loadHistory(job)
            val runs = viewModel.uiState.value.selectedJobHistory.orEmpty()
            val items = if (runs.isEmpty()) {
                listOf("Aucun run enregistré")
            } else {
                runs.map { run ->
                    val duration = run.usage?.durationSeconds?.let { "%.1fs".format(it) } ?: "?"
                    "${run.filename} — $duration"
                }
            }
            HasanDialog.list(context = requireContext(), title = "Historique — ${job.name}", items = items) {}
            viewModel.dismissHistory()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
