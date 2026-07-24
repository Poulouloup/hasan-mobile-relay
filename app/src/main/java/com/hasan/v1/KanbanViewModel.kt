package com.hasan.v1

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hasan.v1.webui.WebUiCallResult
import com.hasan.v1.webui.WebUiClientHolder
import com.hasan.v1.webui.WebUiKanbanClient
import com.hasan.v1.webui.models.KanbanBoard
import com.hasan.v1.webui.models.KanbanBoardSummary
import com.hasan.v1.webui.models.KanbanTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel dédié à l'écran Kanban — même raison qu'un SkillsViewModel/
 * TasksViewModel séparé : domaine sans rapport avec STT/TTS/chat, garde
 * MainViewModel focalisé. Consomme l'API Kanban de hermes-webui
 * (~/hermes-webui/api/kanban_bridge.py), jusqu'ici utilisée seulement par le
 * frontend web statique de hermes-webui.
 */
class KanbanViewModel(application: Application) : AndroidViewModel(application) {

    private val restClient = WebUiClientHolder.get(application)
    private val kanbanClient = WebUiKanbanClient(restClient)

    private val _uiState = MutableStateFlow(KanbanUiState())
    val uiState: StateFlow<KanbanUiState> = _uiState.asStateFlow()

    init {
        loadBoards()
        refresh()
        viewModelScope.launch {
            restClient.authStore.sessionExpired.collect {
                updateState { copy(loading = false, errorMessage = "Session hermes-webui expirée — reconnexion nécessaire") }
            }
        }
    }

    private inline fun updateState(transform: KanbanUiState.() -> KanbanUiState) {
        _uiState.value = _uiState.value.transform()
    }

    fun refresh() {
        viewModelScope.launch {
            updateState { copy(loading = true, errorMessage = null) }
            when (val result = kanbanClient.getBoard(_uiState.value.selectedBoardSlug)) {
                is WebUiCallResult.Ok -> updateState { copy(loading = false, board = result.value) }
                is WebUiCallResult.Unauthorized -> {
                    updateState { copy(loading = false, errorMessage = "Session hermes-webui expirée — reconnexion nécessaire") }
                }
                else -> {
                    updateState { copy(loading = false, errorMessage = "Impossible de charger le board (hermes-webui injoignable)") }
                }
            }
        }
    }

    private fun loadBoards() {
        viewModelScope.launch {
            val result = kanbanClient.listBoards()
            if (result is WebUiCallResult.Ok) {
                updateState { copy(boards = result.value) }
            }
        }
    }

    fun selectBoard(slug: String) {
        updateState { copy(selectedBoardSlug = slug) }
        refresh()
    }

    /** Déplace [task] vers [newStatus]. "running" est rejeté côté client avant tout appel réseau (voir WebUiKanbanClient.moveTask). */
    fun moveTask(task: KanbanTask, newStatus: String) {
        viewModelScope.launch {
            when (val result = kanbanClient.moveTask(task.id, newStatus)) {
                is WebUiCallResult.Ok -> {
                    updateState { copy(errorMessage = null) }
                    refresh()
                }
                is WebUiCallResult.Unauthorized -> {
                    updateState { copy(errorMessage = "Session hermes-webui expirée — reconnexion nécessaire") }
                }
                is WebUiCallResult.HttpError -> {
                    val message = if (result.code == 400 && newStatus == com.hasan.v1.webui.models.KANBAN_STATUS_RUNNING) {
                        "Impossible de déplacer une carte directement vers Running — réservé au dispatcher"
                    } else {
                        "Déplacement refusé par le serveur (${result.code})"
                    }
                    updateState { copy(errorMessage = message) }
                }
                else -> updateState { copy(errorMessage = "Déplacement impossible (hermes-webui injoignable)") }
            }
        }
    }

    fun createBoard(slug: String, name: String?) {
        viewModelScope.launch {
            when (val result = kanbanClient.createBoard(slug, name, switchTo = true)) {
                is WebUiCallResult.Ok -> {
                    updateState { copy(errorMessage = null, selectedBoardSlug = result.value.slug, showCreateBoardDialog = false) }
                    loadBoards()
                    refresh()
                }
                is WebUiCallResult.Unauthorized -> {
                    updateState { copy(errorMessage = "Session hermes-webui expirée — reconnexion nécessaire") }
                }
                else -> updateState { copy(errorMessage = "Création du board impossible (hermes-webui injoignable)") }
            }
        }
    }

    fun createTask(title: String, body: String?, status: String?) {
        viewModelScope.launch {
            when (val result = kanbanClient.createTask(title = title, body = body, status = status)) {
                is WebUiCallResult.Ok -> {
                    updateState { copy(errorMessage = null, showCreateTaskDialog = false) }
                    refresh()
                }
                is WebUiCallResult.Unauthorized -> {
                    updateState { copy(errorMessage = "Session hermes-webui expirée — reconnexion nécessaire") }
                }
                is WebUiCallResult.HttpError -> {
                    val message = if (result.code == 400 && status == com.hasan.v1.webui.models.KANBAN_STATUS_RUNNING) {
                        "Impossible de créer directement une tâche en Running — réservé au dispatcher"
                    } else {
                        "Création refusée par le serveur (${result.code})"
                    }
                    updateState { copy(errorMessage = message) }
                }
                else -> updateState { copy(errorMessage = "Création de la tâche impossible (hermes-webui injoignable)") }
            }
        }
    }

    fun showCreateTaskDialog() {
        updateState { copy(showCreateTaskDialog = true) }
    }

    fun dismissCreateTaskDialog() {
        updateState { copy(showCreateTaskDialog = false) }
    }

    fun openTaskDetail(task: KanbanTask) {
        viewModelScope.launch {
            updateState { copy(detailLoading = true, selectedTaskId = task.id) }
            val detail = kanbanClient.getTaskDetail(task.id)
            updateState { copy(detailLoading = false, selectedTaskDetail = detail) }
        }
    }

    fun closeTaskDetail() {
        updateState { copy(selectedTaskId = null, selectedTaskDetail = null) }
    }

    fun showCreateBoardDialog() {
        updateState { copy(showCreateBoardDialog = true) }
    }

    fun dismissCreateBoardDialog() {
        updateState { copy(showCreateBoardDialog = false) }
    }

    fun clearError() {
        updateState { copy(errorMessage = null) }
    }
}

data class KanbanUiState(
    val board: KanbanBoard? = null,
    val boards: List<KanbanBoardSummary> = emptyList(),
    val selectedBoardSlug: String? = null,
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val selectedTaskId: String? = null,
    val selectedTaskDetail: KanbanTask? = null,
    val detailLoading: Boolean = false,
    val showCreateBoardDialog: Boolean = false,
    val showCreateTaskDialog: Boolean = false
)
