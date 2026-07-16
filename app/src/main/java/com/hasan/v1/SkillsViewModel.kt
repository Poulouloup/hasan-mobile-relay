package com.hasan.v1

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    private val skillsClient = WebUiSkillsClient(WebUiClientHolder.get(application))

    private val _uiState = MutableStateFlow(SkillsUiState())
    val uiState: StateFlow<SkillsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    private inline fun updateState(transform: SkillsUiState.() -> SkillsUiState) {
        _uiState.value = _uiState.value.transform()
    }

    fun refresh() {
        viewModelScope.launch {
            updateState { copy(loading = true, errorMessage = null) }
            val skills = skillsClient.listSkills()
            val usage = skillsClient.getUsage()
            updateState { copy(loading = false, skills = skills, usage = usage) }
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
