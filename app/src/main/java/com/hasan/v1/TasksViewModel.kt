package com.hasan.v1

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hasan.v1.webui.WebUiClientHolder
import com.hasan.v1.webui.WebUiCronClient
import com.hasan.v1.webui.models.CronJob
import com.hasan.v1.webui.models.CronOpResult
import com.hasan.v1.webui.models.CronRun
import com.hasan.v1.webui.models.DeliveryOption
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel dédié à l'écran Tasks (cron jobs hermes-webui) — volontairement
 * séparé de MainViewModel (déjà volumineux, domaine STT/TTS/chat/wake word
 * sans rapport avec les cron jobs). Suit le même pattern AndroidViewModel +
 * activityViewModels() côté Fragment.
 */
class TasksViewModel(application: Application) : AndroidViewModel(application) {

    private val cronClient = WebUiCronClient(WebUiClientHolder.get(application))

    private val _uiState = MutableStateFlow(TasksUiState())
    val uiState: StateFlow<TasksUiState> = _uiState.asStateFlow()

    // Un Job de polling par job_id en cours de run manuel (voir runNow()).
    private val pollingJobs = mutableMapOf<String, Job>()

    init {
        refresh()
    }

    private inline fun updateState(transform: TasksUiState.() -> TasksUiState) {
        _uiState.value = _uiState.value.transform()
    }

    fun refresh() {
        viewModelScope.launch {
            updateState { copy(loading = true, errorMessage = null) }
            val jobs = cronClient.listJobs()
            updateState { copy(loading = false, jobs = jobs) }
        }
    }

    fun createJob(prompt: String, schedule: String, name: String?, deliver: String?) {
        viewModelScope.launch {
            when (val result = cronClient.createJob(prompt, schedule, name, deliver)) {
                is CronOpResult.Ok -> {
                    updateState { copy(errorMessage = null) }
                    refresh()
                }
                is CronOpResult.Error -> updateState { copy(errorMessage = result.message) }
            }
        }
    }

    fun updateJob(job: CronJob, prompt: String, schedule: String, name: String?, deliver: String?) {
        viewModelScope.launch {
            val updates = mapOf(
                "prompt" to prompt,
                "schedule" to schedule,
                "name" to name,
                "deliver" to deliver
            )
            when (val result = cronClient.updateJob(job.id, updates)) {
                is CronOpResult.Ok -> {
                    updateState { copy(errorMessage = null) }
                    refresh()
                }
                is CronOpResult.Error -> updateState { copy(errorMessage = result.message) }
            }
        }
    }

    fun deleteJob(job: CronJob) {
        viewModelScope.launch {
            when (val result = cronClient.deleteJob(job.id)) {
                is CronOpResult.Ok -> {
                    updateState { copy(errorMessage = null) }
                    refresh()
                }
                is CronOpResult.Error -> updateState { copy(errorMessage = result.message) }
            }
        }
    }

    /** Pause si actuellement actif, resume sinon — reflète l'état courant de [job.enabled]. */
    fun toggleEnabled(job: CronJob) {
        viewModelScope.launch {
            val result = if (job.enabled) cronClient.pauseJob(job.id) else cronClient.resumeJob(job.id)
            when (result) {
                is CronOpResult.Ok -> {
                    updateState { copy(errorMessage = null) }
                    refresh()
                }
                is CronOpResult.Error -> updateState { copy(errorMessage = result.message) }
            }
        }
    }

    /**
     * Lance le job manuellement (fire-and-forget côté serveur — voir
     * WebUiCronClient.runJob) puis démarre un polling de statut toutes les
     * 3s jusqu'à ce que le run se termine, pour piloter le badge "en cours"
     * dans l'UI. Un seul polling actif par job à la fois (idempotent si déjà
     * en cours de suivi).
     */
    fun runNow(job: CronJob) {
        viewModelScope.launch {
            when (val result = cronClient.runJob(job.id)) {
                is CronOpResult.Ok -> {
                    updateState { copy(errorMessage = null, runningJobIds = runningJobIds + job.id) }
                    startPolling(job.id)
                }
                is CronOpResult.Error -> updateState { copy(errorMessage = result.message) }
            }
        }
    }

    private fun startPolling(jobId: String) {
        if (pollingJobs.containsKey(jobId)) return
        pollingJobs[jobId] = viewModelScope.launch {
            try {
                while (true) {
                    delay(3_000)
                    val (running, _) = cronClient.jobStatus(jobId)
                    if (!running) {
                        updateState { copy(runningJobIds = runningJobIds - jobId) }
                        refresh()
                        break
                    }
                }
            } finally {
                pollingJobs.remove(jobId)
            }
        }
    }

    fun loadHistory(job: CronJob) {
        viewModelScope.launch {
            val runs = cronClient.jobHistory(job.id)
            updateState { copy(selectedJobId = job.id, selectedJobHistory = runs) }
        }
    }

    fun dismissHistory() {
        updateState { copy(selectedJobId = null, selectedJobHistory = null) }
    }

    fun clearError() {
        updateState { copy(errorMessage = null) }
    }

    suspend fun deliveryOptions(): List<DeliveryOption> = cronClient.deliveryOptions()

    override fun onCleared() {
        super.onCleared()
        pollingJobs.values.forEach { it.cancel() }
    }
}

data class TasksUiState(
    val jobs: List<CronJob> = emptyList(),
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val runningJobIds: Set<String> = emptySet(),
    val selectedJobId: String? = null,
    val selectedJobHistory: List<CronRun>? = null
)
