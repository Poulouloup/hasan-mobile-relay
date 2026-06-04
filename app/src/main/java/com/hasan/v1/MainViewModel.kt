package com.hasan.v1

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hasan.v1.db.Conversation
import com.hasan.v1.db.HassanDatabase
import com.hasan.v1.db.Message
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel principal — orchestre STT, Hermes et TTS.
 * L'UI observe les StateFlow et ne contient aucune logique métier.
 *
 * Flux complet :
 *  Wake word → [onWakeWordDetected] → STT → [onSttFinalResult] → Hermes
 *  → tokens SSE → TTS chunk par chunk → [onAllSpeakingDone]
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val settings = SettingsManager(application)

    private val db = HassanDatabase.getInstance(application)
    private val conversationDao = db.conversationDao()
    private val messageDao = db.messageDao()

    private val hermesConfig get() = HermesConfig(
        baseUrl   = settings.serverUrl,
        authToken = settings.authToken,
        model     = settings.effectiveModel()
    )

    private val hermesClient get() = HermesApiClient(hermesConfig, settings)

    private val ttsManager = HassanTtsManager(application).apply {
        onSpeakingStart = {
            updateState { copy(ttsStatus = TtsStatus.SPEAKING, isListening = false, sttStatus = SttStatus.IDLE) }
            sendWakeWordIntent(HassanWakeWordService.ACTION_PAUSE)
        }
        onAllSpeakingDone = {
            updateState { copy(ttsStatus = TtsStatus.IDLE) }
            viewModelScope.launch {
                delay(1_500)
                if (settings.wakeWordEnabled) {
                    sendWakeWordIntent(HassanWakeWordService.ACTION_RESUME)
                }
            }
        }
    }

    private val ttsBuffer  = StringBuilder()
    private var tokenCount = 0

    // --- État observable ---
    private val _uiState = MutableStateFlow(
        UiState(
            ttsEnabled     = settings.ttsEnabled,
            wakeWordEnabled = settings.wakeWordEnabled
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Conversation Room en cours
    private var currentConversationId: Long = -1

    // Message assistant en cours de streaming (mis à jour token par token)
    private var streamingMessageId: Long = -1
    private val streamingBuffer = StringBuilder()

    private var streamJob: Job? = null
    private var healthJob: Job? = null

    init {
        HassanSoundPlayer.init(application)
        startHealthCheckLoop()
        ttsManager.setVolume(settings.ttsVolume / 100f)
        ttsManager.setSpeed(settings.ttsSpeed)
        if (settings.ttsEngine.isNotBlank()) ttsManager.changeEngine(settings.ttsEngine)
        if (settings.ttsVoice.isNotBlank()) ttsManager.setVoice(settings.ttsVoice)
        restoreLastConversation()
    }

    // ─────────────────────────── Wake word ────────────────────────────────

    fun onWakeWordDetected() {
        val current = _uiState.value

        // Ignorer si on est en plein traitement ou streaming — pas une vraie demande utilisateur
        if (current.sttStatus == SttStatus.SENDING || current.sttStatus == SttStatus.STREAMING) return

        // Si déjà en écoute STT, annuler et relancer (reset complet)
        if (current.sttStatus != SttStatus.IDLE || current.isListening) {
            updateState { copy(isListening = false, sttStatus = SttStatus.IDLE) }
        }

        if (ttsManager.isSpeaking()) {
            ttsManager.stop()
            updateState { copy(ttsStatus = TtsStatus.IDLE) }
            // Délai pour laisser le haut-parleur se taire avant d'ouvrir le micro
            viewModelScope.launch {
                delay(600)
                playWakeSound()
                startListening()
            }
            return
        }

        playWakeSound()
        startListening()
    }

    /** Son PICKUP/COIN — wake word détecté. */
    private fun playWakeSound() {
        HassanSoundPlayer.playWake()
    }

    /** Son BLIP/SELECT — fin de parole détectée. */
    fun playEndSound() {
        HassanSoundPlayer.playEnd()
    }

    // ─────────────────────────── Bouton / mode texte ──────────────────────

    fun toggleListening() {
        if (_uiState.value.isListening) stopListening() else startListening()
    }

    /** Envoi depuis le champ texte (mode clavier). */
    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        ttsBuffer.clear()
        tokenCount = 0
        updateState { copy(transcript = text, response = "", errorMessage = null) }
        sendToHermes(text)
    }

    // ─────────────────────────── STT callbacks ────────────────────────────

    fun onSttReadyForSpeech() = updateState { copy(sttStatus = SttStatus.LISTENING) }
    fun onSttEndOfSpeech() {
        playEndSound()
        updateState { copy(sttStatus = SttStatus.PROCESSING) }
    }
    fun onSttPartialResult(text: String) = updateState { copy(transcript = text) }

    fun onSttFinalResult(text: String) {
        sendWakeWordIntent(HassanWakeWordService.ACTION_RESUME)
        updateState {
            copy(transcript = text, sttStatus = SttStatus.IDLE, isListening = false, response = "")
        }
        ttsBuffer.clear()
        tokenCount = 0
        sendToHermes(text)
    }

    fun onSttError(code: Int, message: String) {
        sendWakeWordIntent(HassanWakeWordService.ACTION_RESUME)
        updateState { copy(sttStatus = SttStatus.IDLE, isListening = false, errorMessage = message) }
    }

    // ─────────────────────────── Historique / reprise ─────────────────────

    /**
     * Reprend une conversation depuis l'historique :
     * - charge les messages en DB
     * - les affiche dans la RecyclerView
     * - le prochain message envoyé à Hermes inclura tout cet historique
     */
    fun resumeConversation(conversationId: Long) {
        currentConversationId = conversationId
        updateState { copy(resumedConversationId = conversationId) }
    }

    /** Régénère la dernière réponse de Hasan. */
    fun regenerateLastResponse() {
        val lastUserMsg = _uiState.value.transcript
        if (lastUserMsg.isNotBlank()) sendToHermes(lastUserMsg)
    }

    /** Supprime un message par son id. */
    fun deleteMessage(messageId: Long) {
        viewModelScope.launch { messageDao.deleteById(messageId) }
    }

    // ─────────────────────────── Toggle TTS / Wake word ───────────────────

    fun toggleTts() {
        val newVal = !settings.ttsEnabled
        settings.ttsEnabled = newVal
        updateState { copy(ttsEnabled = newVal) }
    }

    fun toggleWakeWord() {
        val newVal = !settings.wakeWordEnabled
        settings.wakeWordEnabled = newVal
        updateState { copy(wakeWordEnabled = newVal) }
        if (newVal) sendWakeWordIntent(HassanWakeWordService.ACTION_RESUME)
        else        sendWakeWordIntent(HassanWakeWordService.ACTION_PAUSE)
    }

    /** Lit un texte directement via TTS sans passer par Hermes. */
    fun readAloud(text: String) {
        ttsManager.stop()
        ttsManager.speak(text)
    }

    /** Arrête le TTS immédiatement — utilisé par le bouton quitter et le stop TTS. */
    fun stopTts() {
        ttsManager.stop()
        updateState { copy(ttsStatus = TtsStatus.IDLE) }
    }

    fun swapWakeWordModel(modelPath: String) {
        settings.wakeWordModel = modelPath
        getApplication<Application>().startService(
            Intent(getApplication(), HassanWakeWordService::class.java).apply {
                action = HassanWakeWordService.ACTION_SWAP_MODEL
                putExtra(HassanWakeWordService.EXTRA_MODEL_PATH, modelPath)
            }
        )
    }

    fun setWakeWordSensitivity(value: Float) {
        settings.wakeWordSensitivity = value
        // TODO : envoyer l'intent ACTION_SET_THRESHOLD quand le service le supportera
    }

    fun changeTtsEngine(enginePackage: String) {
        settings.ttsEngine = enginePackage
        ttsManager.changeEngine(enginePackage)
    }

    fun changeTtsVoice(voiceName: String) {
        settings.ttsVoice = voiceName
        ttsManager.setVoice(voiceName)
    }

    fun getAvailableTtsVoices() = ttsManager.getAvailableVoices()
    fun getAvailableTtsEngines() = ttsManager.getAvailableEngines()
    fun getCurrentTtsEngine() = ttsManager.getCurrentEngine()

    fun setTtsVolume(volume: Float) {
        settings.ttsVolume = volume
        ttsManager.setVolume(volume / 100f)
    }

    fun setTtsSpeed(speed: Float) {
        settings.ttsSpeed = speed
        ttsManager.setSpeed(speed)
    }

    fun applyConnectionSettings(url: String, token: String, model: String, customModel: String) {
        settings.serverUrl   = url
        settings.authToken   = token
        settings.model       = model
        settings.customModel = customModel
    }

    // ─────────────────────────── Logique interne ──────────────────────────

    private fun startListening() {
        sendWakeWordIntent(HassanWakeWordService.ACTION_PAUSE)
        updateState {
            copy(
                isListening  = true,
                sttStatus    = SttStatus.STARTING,
                transcript   = "",
                response     = "",
                errorMessage = null,
                ttsStatus    = TtsStatus.IDLE
            )
        }
    }

    private fun stopListening() {
        sendWakeWordIntent(HassanWakeWordService.ACTION_RESUME)
        updateState { copy(isListening = false, sttStatus = SttStatus.IDLE) }
    }

    private fun sendToHermes(userText: String) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            // Persiste le message utilisateur en DB
            val convId = getOrCreateConversation(userText)
            val userMsgId = messageDao.insert(
                Message(conversationId = convId, role = "user", content = userText)
            )

            // Crée le placeholder de la réponse en streaming
            streamingBuffer.clear()
            streamingMessageId = messageDao.insert(
                Message(conversationId = convId, role = "assistant", content = "", isStreaming = true)
            )

            // Construit l'historique complet si on reprend une conversation
            val messages = buildMessagesWithHistory(convId, userText)

            hermesClient.streamCompletionWithHistory(messages).collect { event ->
                when (event) {
                    StreamEvent.Connecting ->
                        updateState { copy(sttStatus = SttStatus.SENDING) }

                    StreamEvent.Connected ->
                        updateState { copy(sttStatus = SttStatus.STREAMING) }

                    is StreamEvent.Token -> {
                        updateState { copy(response = response + event.text) }
                        streamingBuffer.append(event.text)
                        // Mise à jour en DB toutes les N tokens pour éviter les écritures excessives
                        if (settings.ttsEnabled) processTokenForTts(event.text)
                    }

                    StreamEvent.Done -> {
                        if (settings.ttsEnabled) flushTtsBuffer()
                        // Finalise le message assistant en DB (retire le flag isStreaming)
                        if (streamingMessageId >= 0) {
                            messageDao.update(
                                Message(
                                    id = streamingMessageId,
                                    conversationId = convId,
                                    role = "assistant",
                                    content = streamingBuffer.toString(),
                                    isStreaming = false
                                )
                            )
                        }
                        // Met à jour le timestamp de la conversation
                        conversationDao.getById(convId)?.let { conv ->
                            conversationDao.update(conv.copy(updatedAt = System.currentTimeMillis()))
                        }
                        updateState { copy(sttStatus = SttStatus.IDLE) }
                    }

                    is StreamEvent.Error ->
                        updateState { copy(sttStatus = SttStatus.IDLE, errorMessage = event.message) }

                    // Certificat inconnu ou modifié — émis par le client TOFU,
                    // géré côté UI par ConversationFragment via le StateFlow errorMessage.
                    // On transmet le fingerprint dans errorMessage pour que l'UI
                    // puisse afficher la dialog d'approbation si nécessaire.
                    is StreamEvent.CertificateCheck ->
                        updateState { copy(sttStatus = SttStatus.IDLE, errorMessage = "CERT:${event.isChanged}:${event.fingerprint}:${event.storedFingerprint ?: ""}") }
                }
            }
        }
    }

    /** Crée une nouvelle conversation en DB, ou réutilise la conversation reprise. */
    private suspend fun getOrCreateConversation(firstUserText: String): Long {
        if (currentConversationId >= 0) return currentConversationId
        val convId = conversationDao.insert(
            Conversation(
                title = firstUserText.take(80),
                type  = if (_uiState.value.isVoiceMode) "voice" else "text"
            )
        )
        currentConversationId = convId
        // Déclenche l'observation des messages dans ConversationFragment
        updateState { copy(resumedConversationId = convId) }
        return convId
    }

    /**
     * Construit la liste de messages pour Hermes.
     * Si on reprend une conversation, inclut l'historique complet sans le dernier message
     * utilisateur (déjà ajouté en fin de liste).
     */
    private suspend fun buildMessagesWithHistory(
        convId: Long,
        latestUserText: String
    ): List<ChatMessage> {
        val history = messageDao.getMessagesForConversationOnce(convId)
        // On exclut le dernier message utilisateur (vient d'être inséré) et
        // le placeholder assistant (vide, isStreaming=true)
        val historicalMessages = history.filter { !it.isStreaming && it.content.isNotBlank() }
            .dropLast(1) // retire le message user qu'on vient d'insérer pour ne pas le dupliquer
        return historicalMessages.map { ChatMessage(it.role, it.content) } +
               ChatMessage("user", latestUserText)
    }

    private fun processTokenForTts(token: String) {
        ttsBuffer.append(token)
        tokenCount++
        val text = ttsBuffer.toString()
        val boundary = SENTENCE_SEPARATORS.firstOrNull { text.contains(it) }
        if (boundary != null || tokenCount >= MAX_TOKENS_BEFORE_SPEAK) {
            val cutAt = if (boundary != null)
                text.lastIndexOf(boundary) + boundary.length
            else text.length
            val chunk = text.substring(0, cutAt).trim()
            if (chunk.isNotEmpty()) ttsManager.speak(chunk)
            ttsBuffer.clear()
            ttsBuffer.append(text.substring(cutAt))
            tokenCount = 0
        }
    }

    private fun flushTtsBuffer() {
        val remaining = ttsBuffer.toString().trim()
        if (remaining.isNotEmpty()) ttsManager.speak(remaining)
        ttsBuffer.clear()
        tokenCount = 0
    }

    private fun startHealthCheckLoop() {
        healthJob = viewModelScope.launch {
            while (true) {
                val connected = withContext(Dispatchers.IO) {
                    try { HermesApiClient(hermesConfig, settings).checkHealth() == HealthResult.Ok } catch (_: Exception) { false }
                }
                updateState { copy(serverConnected = connected) }
                delay(10_000)
            }
        }
    }

    fun sendWakeWordIntent(action: String) {
        getApplication<Application>().startService(
            Intent(getApplication(), HassanWakeWordService::class.java).apply {
                this.action = action
            }
        )
    }

    /** Démarre une nouvelle conversation (réinitialise l'ID courant). */
    fun startNewConversation() {
        currentConversationId = -1
        updateState { copy(resumedConversationId = null, transcript = "", response = "") }
    }

    /** Restaure la dernière conversation ouverte au lancement de l'app. */
    private fun restoreLastConversation() {
        viewModelScope.launch {
            val last = conversationDao.getMostRecent()
            if (last != null) {
                currentConversationId = last.id
                updateState { copy(resumedConversationId = last.id) }
            }
        }
    }

    private inline fun updateState(transform: UiState.() -> UiState) {
        _uiState.value = _uiState.value.transform()
    }

    override fun onCleared() {
        super.onCleared()
        streamJob?.cancel()
        healthJob?.cancel()
        ttsManager.release()
        HassanSoundPlayer.release()
    }

    companion object {
        private val SENTENCE_SEPARATORS = listOf(". ", "! ", "? ", ", ")
        private const val MAX_TOKENS_BEFORE_SPEAK = 5
    }
}

/** État complet de l'UI. */
data class UiState(
    val isListening:           Boolean   = false,
    val isVoiceMode:           Boolean   = true,
    val sttStatus:             SttStatus = SttStatus.IDLE,
    val ttsStatus:             TtsStatus = TtsStatus.IDLE,
    val transcript:            String    = "",
    val response:              String    = "",
    val serverConnected:       Boolean   = false,
    val errorMessage:          String?   = null,
    val ttsEnabled:            Boolean   = true,
    val wakeWordEnabled:       Boolean   = true,
    val resumedConversationId: Long?     = null
)

enum class SttStatus { IDLE, STARTING, LISTENING, PROCESSING, SENDING, STREAMING }
enum class TtsStatus  { IDLE, SPEAKING }
