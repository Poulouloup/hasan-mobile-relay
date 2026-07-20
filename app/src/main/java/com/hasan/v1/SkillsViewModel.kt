package com.hasan.v1

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hasan.v1.webui.WebUiCallResult
import com.hasan.v1.webui.WebUiClientHolder
import com.hasan.v1.webui.WebUiSkillsClient
import com.hasan.v1.webui.models.SkillDetail
import com.hasan.v1.webui.models.SkillSummary
import com.hasan.v1.webui.models.SkillUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel dédié à l'écran Skills (lecture seule) — même raison qu'un
 * TasksViewModel séparé : domaine sans rapport avec STT/TTS/chat, garde
 * MainViewModel focalisé. Plus léger que TasksViewModel (pas de polling,
 * pas d'écriture — le serveur expose save/delete/toggle mais l'écran
 * Skills reste volontairement lecture seule, voir le prompt de migration).
 */
class SkillsViewModel(application: Application) : AndroidViewModel(application) {

    private val restClient = WebUiClientHolder.get(application)
    private val skillsClient = WebUiSkillsClient(restClient)

    private val _uiState = MutableStateFlow(SkillsUiState())
    val uiState: StateFlow<SkillsUiState> = _uiState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            restClient.authStore.sessionExpired.collect {
                updateState { copy(loading = false, errorMessage = "Session hermes-webui expirée — reconnexion nécessaire") }
            }
        }
    }

    private inline fun updateState(transform: SkillsUiState.() -> SkillsUiState) {
        _uiState.value = _uiState.value.transform()
    }

    fun refresh() {
        viewModelScope.launch {
            updateState { copy(loading = true, errorMessage = null) }
            when (val skillsResult = skillsClient.listSkills()) {
                is WebUiCallResult.Ok -> {
                    val usage = (skillsClient.getUsage() as? WebUiCallResult.Ok)?.value ?: emptyMap()
                    updateState { copy(loading = false, skills = skillsResult.value, usage = usage) }
                }
                is WebUiCallResult.Unauthorized -> {
                    updateState { copy(loading = false, errorMessage = "Session hermes-webui expirée — reconnexion nécessaire") }
                }
                else -> {
                    updateState { copy(loading = false, errorMessage = "Impossible de charger les skills (hermes-webui injoignable)") }
                }
            }
        }
    }

    fun openDetail(skill: SkillSummary) {
        viewModelScope.launch {
            updateState { copy(detailLoading = true, selectedSkillName = skill.name) }
            val detail = skillsClient.getSkillDetail(skill.name)
            updateState { copy(detailLoading = false, selectedSkillDetail = detail) }
        }
    }

    fun closeDetail() {
        updateState { copy(selectedSkillName = null, selectedSkillDetail = null) }
    }

    fun clearError() {
        updateState { copy(errorMessage = null) }
    }
}

data class SkillsUiState(
    val skills: List<SkillSummary> = emptyList(),
    val usage: Map<String, SkillUsage> = emptyMap(),
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val selectedSkillName: String? = null,
    val selectedSkillDetail: SkillDetail? = null,
    val detailLoading: Boolean = false
)
