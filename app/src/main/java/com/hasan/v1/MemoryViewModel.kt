package com.hasan.v1

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hasan.v1.webui.WebUiCallResult
import com.hasan.v1.webui.WebUiClientHolder
import com.hasan.v1.webui.WebUiMemoryClient
import com.hasan.v1.webui.models.HermesMemory
import com.hasan.v1.webui.models.InsightsSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel dédié à l'écran Memory & Insights (lecture seule) — même
 * raison qu'un SkillsViewModel séparé : domaine sans rapport avec
 * STT/TTS/chat, garde MainViewModel focalisé. Le serveur expose aussi
 * POST /api/memory/write (édition) mais l'écran reste volontairement
 * lecture seule pour cette étape (voir le prompt de migration).
 */
class MemoryViewModel(application: Application) : AndroidViewModel(application) {

    private val restClient = WebUiClientHolder.get(application)
    private val memoryClient = WebUiMemoryClient(restClient)

    private val _uiState = MutableStateFlow(MemoryUiState())
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            restClient.authStore.sessionExpired.collect {
                updateState { copy(loading = false, errorMessage = "Session hermes-webui expirée — reconnexion nécessaire") }
            }
        }
    }

    private inline fun updateState(transform: MemoryUiState.() -> MemoryUiState) {
        _uiState.value = _uiState.value.transform()
    }

    fun refresh() {
        viewModelScope.launch {
            updateState { copy(loading = true, errorMessage = null) }
            val memoryDeferred = async { memoryClient.getMemory() }
            val insightsDeferred = async { memoryClient.getInsights() }
            val memoryResult = memoryDeferred.await()
            val insightsResult = insightsDeferred.await()
            if (memoryResult is WebUiCallResult.Unauthorized || insightsResult is WebUiCallResult.Unauthorized) {
                updateState { copy(loading = false, errorMessage = "Session hermes-webui expirée — reconnexion nécessaire") }
                return@launch
            }
            val memory = (memoryResult as? WebUiCallResult.Ok)?.value
            val insights = (insightsResult as? WebUiCallResult.Ok)?.value
            val errorMessage = if (memory == null && insights == null) {
                "Impossible de charger la mémoire (hermes-webui injoignable)"
            } else null
            updateState { copy(loading = false, memory = memory, insights = insights, errorMessage = errorMessage) }
        }
    }

    fun selectTab(tab: MemoryTab) {
        updateState { copy(selectedTab = tab, selectedFile = null) }
    }

    fun openFile(file: MemoryFile) {
        updateState { copy(selectedFile = file) }
    }

    fun closeFile() {
        updateState { copy(selectedFile = null) }
    }

    fun clearError() {
        updateState { copy(errorMessage = null) }
    }
}

enum class MemoryTab { MEMORY, INSIGHTS }

enum class MemoryFile { MEMORY, USER, SOUL }

data class MemoryUiState(
    val selectedTab: MemoryTab = MemoryTab.MEMORY,
    val selectedFile: MemoryFile? = null,
    val memory: HermesMemory? = null,
    val insights: InsightsSummary? = null,
    val loading: Boolean = false,
    val errorMessage: String? = null
)
