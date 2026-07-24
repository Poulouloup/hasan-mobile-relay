package com.hasan.v1

import android.app.Application
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hasan.v1.webui.WebUiCallResult
import com.hasan.v1.webui.WebUiClientHolder
import com.hasan.v1.webui.WebUiWorkspaceClient
import com.hasan.v1.webui.models.WorkspaceEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import android.util.Log
import java.io.File
import java.net.URLEncoder

/**
 * ViewModel dédié à l'écran Fichiers (workspace hermes-webui) —
 * volontairement séparé de MainViewModel, même raison que
 * KanbanViewModel/TasksViewModel. Consomme /api/list et /api/file/raw,
 * jusqu'ici utilisés seulement par le frontend web statique de hermes-webui.
 *
 * session_id est requis par ces endpoints mais n'isole PAS le contenu dans
 * la config par défaut de hermes-webui (toutes les sessions partagent le
 * même DEFAULT_WORKSPACE tant qu'aucun workspace différent n'a été choisi
 * explicitement) — voir docs/ARCHITECTURE.md#fichiers.
 */
class FilesViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "FilesViewModel"
    }

    private val settings = SettingsManager(application)
    private val restClient = WebUiClientHolder.get(application)
    private val workspaceClient = WebUiWorkspaceClient(restClient)

    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    private val _openFile = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    /** Event one-shot : fichier téléchargé et prêt à être ouvert — collecté côté Fragment pour lancer l'Intent.ACTION_VIEW (le ViewModel ne fait pas de startActivity, règles MVVM). */
    val openFile: SharedFlow<Uri> = _openFile.asSharedFlow()

    init {
        refresh()
        viewModelScope.launch {
            restClient.authStore.sessionExpired.collect {
                updateState { copy(loading = false, errorMessage = "Session hermes-webui expirée — reconnexion nécessaire") }
            }
        }
    }

    private inline fun updateState(transform: FilesUiState.() -> FilesUiState) {
        _uiState.value = _uiState.value.transform()
    }

    fun refresh() {
        val sessionId = settings.activeSessionId
        if (sessionId == null) {
            updateState { copy(loading = false, errorMessage = "Aucune session active") }
            return
        }
        viewModelScope.launch {
            updateState { copy(loading = true, errorMessage = null) }
            when (val result = workspaceClient.listFiles(sessionId, _uiState.value.currentPath)) {
                is WebUiCallResult.Ok -> updateState { copy(loading = false, entries = result.value) }
                is WebUiCallResult.Unauthorized -> updateState { copy(loading = false, errorMessage = "Session hermes-webui expirée — reconnexion nécessaire") }
                else -> updateState { copy(loading = false, errorMessage = "Impossible de charger le workspace (hermes-webui injoignable)") }
            }
        }
    }

    fun navigateInto(entry: WorkspaceEntry) {
        if (!entry.isDir) return
        updateState { copy(currentPath = entry.path) }
        refresh()
    }

    fun navigateUp() {
        val current = _uiState.value.currentPath
        if (current == ".") return
        val parent = current.substringBeforeLast("/", ".")
        updateState { copy(currentPath = parent) }
        refresh()
    }

    fun clearError() {
        updateState { copy(errorMessage = null) }
    }

    /**
     * Télécharge [entry] via /api/file/raw et émet son Uri FileProvider une
     * fois prêt. Le serveur est en TLS auto-signé TOFU — un navigateur
     * externe ne connaît pas ce certificat, d'où le passage par
     * webUiRestClient.httpClient (TOFU + cookie déjà configurés) plutôt
     * qu'un ACTION_VIEW direct sur l'URL distante.
     */
    fun downloadAndOpen(entry: WorkspaceEntry) {
        if (entry.isDir) return
        val sessionId = settings.activeSessionId ?: return
        val encodedSession = URLEncoder.encode(sessionId, "UTF-8")
        val encodedPath = URLEncoder.encode(entry.path, "UTF-8")
        val path = "/api/file/raw?session_id=$encodedSession&path=$encodedPath&download=1"
        viewModelScope.launch {
            val downloaded = withContext(Dispatchers.IO) { restClient.downloadFile(path, filenameHint = entry.name) }
            if (downloaded == null) {
                updateState { copy(errorMessage = "Téléchargement de \"${entry.name}\" échoué") }
                return@launch
            }
            try {
                val context = getApplication<Application>()
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                val file = File(dir, downloaded.filename)
                withContext(Dispatchers.IO) { file.writeBytes(downloaded.bytes) }
                val uri = FileProvider.getUriForFile(context, "com.hasan.v1.fileprovider", file)
                _openFile.emit(uri)
            } catch (e: Exception) {
                Log.w(TAG, "downloadAndOpen: écriture locale échouée", e)
                updateState { copy(errorMessage = "Impossible d'ouvrir \"${downloaded.filename}\"") }
            }
        }
    }
}

data class FilesUiState(
    val entries: List<WorkspaceEntry> = emptyList(),
    val currentPath: String = ".",
    val loading: Boolean = false,
    val errorMessage: String? = null
)
