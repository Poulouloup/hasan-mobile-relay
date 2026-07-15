package com.hasan.v1

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hasan.v1.audio.BargeInListener
import com.hasan.v1.auth.PairingManager
import com.hasan.v1.auth.SessionTokenStore
import com.hasan.v1.network.ActivityLog
import com.hasan.v1.network.ConnectionManager
import com.hasan.v1.network.RelayConnectionStatus
import com.hasan.v1.network.models.ErrorType
import com.hasan.v1.network.models.HealthResult
import com.hasan.v1.network.models.StreamEvent
import com.hasan.v1.utils.LatencyLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hasan.v1.db.Conversation
import com.hasan.v1.db.HassanDatabase
import com.hasan.v1.db.HermesSession
import com.hasan.v1.db.Message
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
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
    private val sessionDao = db.sessionDao()

    /** Toutes les sessions, observables par SessionsFragment. */
    val sessions = sessionDao.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val ttsManager = HassanTtsManager(application)

    // ─────────────────────────── Barge-in ──────────────────────────────────
    // Micro ouvert uniquement pendant TtsStatus.SPEAKING (ttsManager.onSpeakingStart,
    // ci-dessous, met déjà HassanWakeWordService en PAUSE à ce moment — voir la note
    // dans BargeInListener sur la non-collision des AudioRecord). Construit avant le
    // câblage des callbacks ttsManager pour pouvoir y être référencé.
    private val bargeInListener = BargeInListener(application, ttsManager).apply {
        viewModelScope.launch {
            events.collect { event ->
                when (event) {
                    BargeInListener.Event.DuckingStarted -> Unit // pas d'état UI dédié pour l'instant
                    BargeInListener.Event.DuckingCancelled -> Unit
                    BargeInListener.Event.BargeInConfirmed -> {
                        stop() // libère le micro barge-in avant que le STT n'ouvre le sien
                        updateState { copy(ttsStatus = TtsStatus.IDLE) }
                        startListening()
                    }
                }
            }
        }
    }

    init {
        ttsManager.onSpeakingStart = {
            updateState { copy(ttsStatus = TtsStatus.SPEAKING, isListening = false, sttStatus = SttStatus.IDLE) }
            sendWakeWordIntent(HassanWakeWordService.ACTION_PAUSE)
            bargeInListener.start()
        }
        ttsManager.onAllSpeakingDone = {
            bargeInListener.stop()
            updateState { copy(ttsStatus = TtsStatus.IDLE, ttsPlayingMessageId = null) }
            viewModelScope.launch {
                delay(1_500)
                if (settings.wakeWordEnabled) {
                    sendWakeWordIntent(HassanWakeWordService.ACTION_RESUME)
                }
            }
        }
        ttsManager.onFallback = { reason ->
            updateState { copy(ttsFallbackMessage = "Voix hors ligne : $reason") }
        }
    }

    // ─────────────────────────── Relay (WebSocket) ─────────────────────────
    // Seul transport vers Hermes — connexion ouverte inconditionnellement (voir init).
    private val sessionTokenStore = SessionTokenStore(settings)
    val pairingManager = PairingManager(settings)

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

    // connectionManager doit être déclaré après _uiState : son bloc .apply lance des
    // coroutines qui collectent un StateFlow, et collect() émet la valeur courante de
    // façon synchrone dès l'abonnement — si _uiState n'était pas encore assigné, le
    // premier updateState { } de ce bloc plantait avec un NPE sur _uiState (observé au
    // premier lancement sur certains devices selon le timing de dispatch coroutine).
    val activityLog = ActivityLog()

    // Doit être déclaré avant connectionManager : son bloc .apply référence
    // bridgeCommandHandler dans le collecteur multiplexer.bridge — Kotlin
    // initialise les propriétés dans l'ordre textuel, un ordre inversé
    // laisserait bridgeCommandHandler non-initialisé (voir la note sur
    // connectionManager/_uiState juste au-dessus pour le même piège).
    private val bridgeCommandHandler: com.hasan.v1.network.BridgeCommandHandler = com.hasan.v1.network.BridgeCommandHandler(
        context = application,
        settings = settings,
        activityLog = activityLog,
        send = { envelope -> connectionManager.send(envelope) }
    )

    private val connectionManager = ConnectionManager(application, settings).apply {
        viewModelScope.launch {
            connectionStatus.collect { status ->
                updateState { copy(relayConnectionStatus = status) }
                activityLog.log(activityTitleFor(status), tag = "AUTH")
            }
        }
        viewModelScope.launch {
            certCheckEvents.collect { certCheck ->
                if (certCheck != null) {
                    updateState { copy(relayCertCheck = certCheck) }
                    activityLog.log("Nouveau certificat détecté", tag = "AUTH")
                }
            }
        }
        viewModelScope.launch {
            multiplexer.system.collect { envelope ->
                activityLog.log("Événement système : ${envelope.type}", tag = "CRON")
            }
        }
        viewModelScope.launch {
            multiplexer.proactive.collect { envelope ->
                activityLog.log("Notification proactive : ${envelope.type}", tag = "PUSH")
            }
        }
        viewModelScope.launch {
            multiplexer.bridge.collect { envelope ->
                bridgeCommandHandler.handle(envelope)
                activityLog.log("Commande bridge : ${envelope.payload.optString("capability")}", tag = "AUTH")
            }
        }
    }

    // Doit être déclaré APRÈS connectionManager (ordre inverse de
    // bridgeCommandHandler !) : bridgeCommandHandler est référencé DANS le
    // bloc .apply de connectionManager donc doit le précéder, alors que
    // chatStreamHandler dépend directement de connectionManager déjà construit
    // (connectionManager.send()/multiplexer/connectionStatus) et n'a pas
    // besoin d'être câblé dans son bloc .apply — le chat n'est écouté que
    // pendant la durée de vie d'un streamChat() en cours, pas en permanence
    // comme system/proactive/bridge (voir ChatStreamHandler.streamChat()).
    private val chatStreamHandler by lazy { com.hasan.v1.network.ChatStreamHandler(connectionManager, connectionManager.multiplexer) }

    private fun activityTitleFor(status: RelayConnectionStatus): String = when (status) {
        RelayConnectionStatus.CONNECTED -> "Connexion relay établie"
        RelayConnectionStatus.CONNECTING -> "Connexion au relay…"
        RelayConnectionStatus.RECONNECTING -> "Reconnexion au relay…"
        RelayConnectionStatus.DISCONNECTED -> "Relay déconnecté"
    }

    // Conversation Room en cours
    private var currentConversationId: Long = -1

    // Message assistant en cours de streaming (mis à jour token par token)
    private var streamingMessageId: Long = -1
    private val streamingBuffer = StringBuilder()

    private var streamJob: Job? = null
    private var streamStartTime: Long = 0L
    private var healthJob: Job? = null
    private var uiUpdateJob: Job? = null
    private var lastUserText: String = ""

    /**
     * UUID généré en mémoire au clic "+ Nouvelle Session" (voir startPendingSession()),
     * PAS encore inséré en Room — création paresseuse : aucune entrée DB tant que
     * l'utilisateur n'a pas envoyé son premier message avec succès (voir
     * materializePendingSession(), déclenchée sur StreamEvent.Connected).
     */
    private var pendingSessionId: String? = null

    init {
        HassanSoundPlayer.init(application)
        LatencyLog.init(application)
        startHealthCheckLoop()
        ttsManager.setVolume(settings.ttsVolume / 100f)
        ttsManager.setSpeed(settings.ttsSpeed)
        if (settings.ttsProvider.isNotBlank()) ttsManager.changeProvider(settings.ttsProvider)
        if (settings.ttsEngine.isNotBlank()) ttsManager.changeEngine(settings.ttsEngine)
        if (settings.ttsVoice.isNotBlank()) ttsManager.setVoice(settings.ttsVoice)
        updateState { copy(ttsOnline = ttsManager.isOnline) }
        updateState { copy(relayPaired = sessionTokenStore.isPaired) }
        viewModelScope.launch(Dispatchers.IO) { messageDao.deleteAllStreaming() }
        restoreLastConversation()
        ensureActiveSession()
        observeBackgroundConversationUpdates()
        connectionManager.connect()
    }

    /**
     * Le pipeline wake word en arrière-plan (HassanWakeWordService) écrit directement
     * en DB. On resynchronise ici l'état affiché pour que ConversationFragment
     * recharge la bonne conversation au retour au premier plan.
     */
    private fun observeBackgroundConversationUpdates() {
        viewModelScope.launch {
            HassanWakeWordService.conversationUpdated.collect { convId ->
                if (currentConversationId != convId) {
                    currentConversationId = convId
                }
                updateState { copy(resumedConversationId = convId) }
            }
        }
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
        updateState { copy(transcript = text, response = "", errorMessage = null, thinkingMessage = null) }
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
        if (message.isBlank()) {
            updateState { copy(sttStatus = SttStatus.IDLE, isListening = false) }
            if (settings.wakeWordEnabled) sendWakeWordIntent(HassanWakeWordService.ACTION_RESUME)
        } else {
            sendWakeWordIntent(HassanWakeWordService.ACTION_RESUME)
            updateState { copy(sttStatus = SttStatus.IDLE, isListening = false, errorMessage = message) }
        }
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

    /**
     * Reçoit un message poussé par Hermes via SSE (app au premier plan).
     * Persiste le message en DB et le lit via TTS si activé.
     */
    fun handlePushedMessage(content: String) {
        viewModelScope.launch {
            val convId = if (currentConversationId >= 0) currentConversationId
                         else getOrCreateConversation("(push)")
            messageDao.insert(
                Message(conversationId = convId, role = "assistant", content = content)
            )
            if (settings.ttsEnabled) ttsManager.speak(content)
        }
    }

    /** Lit un message via TTS. Si messageId fourni, le tracked pour le bouton toggle. */
    fun readAloud(text: String, messageId: Long? = null) {
        ttsManager.stop()
        updateState { copy(ttsPlayingMessageId = messageId) }
        ttsManager.speak(text)
    }

    /** Arrête le TTS immédiatement. */
    fun stopTts() {
        ttsManager.stop()
        updateState { copy(ttsStatus = TtsStatus.IDLE, ttsPlayingMessageId = null) }
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

    /** Health check applicatif Hermes via le relay (chat/health) — utilisé par le bouton "Tester la connexion" des Réglages. */
    suspend fun checkHealthViaRelay(): HealthResult = chatStreamHandler.checkHealth()

    fun setWakeWordSensitivity(value: Float) {
        settings.wakeWordSensitivity = value
        // TODO : envoyer l'intent ACTION_SET_THRESHOLD quand le service le supportera
    }

    fun changeTtsProvider(provider: String) {
        settings.ttsProvider = provider
        ttsManager.changeProvider(provider)
        updateState { copy(ttsOnline = ttsManager.isOnline) }
    }

    fun getCurrentTtsProvider() = ttsManager.currentProvider()

    fun changeTtsEngine(enginePackage: String) {
        settings.ttsEngine = enginePackage
        ttsManager.changeEngine(enginePackage)
    }

    fun changeTtsVoice(voiceName: String) {
        settings.ttsVoice = voiceName
        ttsManager.setVoice(voiceName)
    }

    fun clearTtsFallbackMessage() {
        updateState { copy(ttsFallbackMessage = null) }
    }

    fun getAvailableTtsVoices() = ttsManager.getAvailableVoices()
    fun getAvailableTtsEngines() = ttsManager.getAvailableEngines()
    fun getCurrentTtsEngine() = ttsManager.getCurrentEngine()
    fun getCurrentTtsVoice() = settings.ttsVoice

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

    /** Approuve le certificat TOFU et rejoue le dernier message utilisateur. */
    fun trustCertAndRetry(fingerprint: String) {
        settings.setTrustedCertFingerprint(
            com.hasan.v1.network.models.certStorageKey(settings.serverUrl), fingerprint
        )
        updateState { copy(errorMessage = null) }
        if (lastUserText.isNotBlank()) {
            currentConversationId = -1
            sendToHermes(lastUserText)
        }
    }

    fun clearError() {
        updateState { copy(errorMessage = null, errorType = null) }
    }

    /** Réessaye le dernier message utilisateur (bouton "Réessayer" sur bulle erreur). */
    fun retryLastMessage() {
        clearError()
        if (lastUserText.isNotBlank()) sendToHermes(lastUserText)
    }

    private fun hasNetwork(): Boolean {
        val cm = getApplication<Application>()
            .getSystemService(ConnectivityManager::class.java)
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun sendToHermes(userText: String, retryCount: Int = 0) {
        lastUserText = userText
        if (retryCount == 0) streamStartTime = System.currentTimeMillis()
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            // Vérifie le réseau avant toute tentative
            if (!hasNetwork()) {
                updateState { copy(
                    sttStatus = SttStatus.IDLE,
                    errorMessage = getApplication<Application>().getString(R.string.error_no_network),
                    errorType = ErrorType.NO_NETWORK,
                    connectionStatus = ConnectionStatus.DISCONNECTED
                ) }
                return@launch
            }

            // Session effective : active si elle existe déjà, sinon la session pending
            // (création paresseuse — voir startPendingSession()). Rien n'est encore
            // inséré en Room à ce stade pour la branche pending.
            val effectiveSessionId = settings.activeSessionId ?: pendingSessionId ?: run {
                updateState { copy(sttStatus = SttStatus.IDLE, errorMessage = "Aucune session active") }
                return@launch
            }

            val turn = streamStartTime.toString()
            LatencyLog.mark("SEND", turn)

            // convId/streamingMessageId matérialisés dans la branche Connected ci-dessous
            // (pas avant l'appel réseau) — voir materializePendingSession(). Tant que
            // Connected n'est pas reçu, aucune session/conversation/message n'est
            // persisté, pour ne rien laisser d'orphelin si le réseau échoue.
            var convId = -1L

            // reachedTerminal : filet de sécurité contre un flow qui se termine (fin
            // normale ou exception) sans jamais émettre Done/Error — auparavant un tel
            // silence laissait sttStatus bloqué en SENDING/STREAMING indéfiniment, sans
            // aucun message d'erreur pour l'utilisateur (voir turn=1784139600101).
            var reachedTerminal = false
            try {
            chatStreamHandler.streamChat(effectiveSessionId, userText).collect { event ->
                when (event) {
                    StreamEvent.Connecting ->
                        updateState { copy(sttStatus = SttStatus.SENDING) }

                    StreamEvent.Connected -> {
                        materializePendingSession(userText)
                        convId = getOrCreateConversation(userText)
                        // N'insère le message utilisateur qu'au premier essai
                        if (retryCount == 0) {
                            messageDao.insert(
                                Message(conversationId = convId, role = "user", content = userText)
                            )
                        }

                        streamingBuffer.clear()
                        if (streamingMessageId >= 0) {
                            messageDao.deleteById(streamingMessageId)
                            streamingMessageId = -1
                        }
                        streamingMessageId = messageDao.insert(
                            Message(conversationId = convId, role = "assistant", content = "", isStreaming = true)
                        )

                        // Throttle UI : flush la DB toutes les 100ms pour un scroll fluide
                        val flushConvId = convId
                        uiUpdateJob?.cancel()
                        uiUpdateJob = viewModelScope.launch(Dispatchers.IO) {
                            while (true) {
                                delay(100)
                                val msgId = streamingMessageId
                                if (msgId < 0) break
                                val snapshot = synchronized(streamingBuffer) { streamingBuffer.toString() }
                                if (snapshot.isNotEmpty()) {
                                    messageDao.update(Message(
                                        id = msgId,
                                        conversationId = flushConvId,
                                        role = "assistant",
                                        content = snapshot,
                                        isStreaming = true
                                    ))
                                    LatencyLog.mark("DB_FLUSH", turn, "len=${snapshot.length}")
                                }
                            }
                        }

                        updateState { copy(
                            sttStatus = SttStatus.STREAMING,
                            connectionStatus = ConnectionStatus.CONNECTED,
                            errorMessage = null,
                            errorType = null
                        ) }
                    }

                    is StreamEvent.Thinking ->
                        updateState { copy(thinkingMessage = event.message) }

                    is StreamEvent.Token -> {
                        synchronized(streamingBuffer) { streamingBuffer.append(event.text) }
                        updateState { copy(response = response + event.text, thinkingMessage = null) }
                    }

                    is StreamEvent.Done -> {
                        reachedTerminal = true
                        uiUpdateJob?.cancel()
                        uiUpdateJob = null
                        updateState { copy(thinkingMessage = null) }
                        val responseText = streamingBuffer.toString()
                        // TTS déclenché sur le texte complet une fois le stream terminé
                        if (settings.ttsEnabled && responseText.isNotBlank()) ttsManager.speak(responseText)
                        val durationMs = System.currentTimeMillis() - streamStartTime
                        LatencyLog.mark("DONE", turn, "total=${durationMs}ms")
                        LatencyLog.clear(turn)
                        val metadata = if (event.inputTokens > 0 || event.outputTokens > 0) {
                            """{"response_id":"${event.responseId ?: ""}","input_tokens":${event.inputTokens},"output_tokens":${event.outputTokens},"duration_ms":$durationMs}"""
                        } else null
                        if (streamingMessageId >= 0) {
                            messageDao.update(
                                Message(
                                    id = streamingMessageId,
                                    conversationId = convId,
                                    role = "assistant",
                                    content = responseText,
                                    isStreaming = false,
                                    metadata = metadata
                                )
                            )
                        }
                        conversationDao.getById(convId)?.let { conv ->
                            conversationDao.update(conv.copy(updatedAt = System.currentTimeMillis()))
                        }
                        settings.activeSessionId?.let { sessionDao.touchSession(it) }
                        streamingMessageId = -1
                        updateState { copy(sttStatus = SttStatus.IDLE) }
                        if (responseText.isNotBlank() && !isAppInForeground()) {
                            HassanNotificationService.notifyMessage(
                                getApplication(), responseText
                            )
                        }
                    }

                    is StreamEvent.Error -> {
                        reachedTerminal = true
                        uiUpdateJob?.cancel()
                        uiUpdateJob = null
                        // Nettoyage du placeholder streaming orphelin
                        if (streamingMessageId >= 0) {
                            messageDao.deleteById(streamingMessageId)
                            streamingMessageId = -1
                        }
                        // Aucun retry automatique : une erreur reste toujours visible pour
                        // l'utilisateur (bouton "Réessayer" sur la bulle) plutôt que d'être
                        // masquée par une tentative de récupération silencieuse — un ancien
                        // retry pouvait laisser le tour bloqué indéfiniment sans aucune trace
                        // ni pour l'utilisateur ni dans les logs (voir turn=1784139600101).
                        LatencyLog.mark("ERROR", turn, event.type.name)
                        LatencyLog.clear(turn)
                        updateState { copy(
                            sttStatus = SttStatus.IDLE,
                            errorMessage = event.message,
                            errorType = event.type,
                            connectionStatus = ConnectionStatus.DISCONNECTED
                        ) }
                    }

                    is StreamEvent.CertificateCheck -> {
                        reachedTerminal = true
                        updateState { copy(sttStatus = SttStatus.IDLE, errorMessage = "CERT:${event.isChanged}:${event.fingerprint}:${event.storedFingerprint ?: ""}") }
                    }
                }
            }
            } finally {
                if (!reachedTerminal) {
                    LatencyLog.mark("STREAM_ORPHANED", turn, "flow terminé sans Done/Error")
                    uiUpdateJob?.cancel()
                    uiUpdateJob = null
                    if (streamingMessageId >= 0) {
                        messageDao.deleteById(streamingMessageId)
                        streamingMessageId = -1
                    }
                    updateState { copy(
                        sttStatus = SttStatus.IDLE,
                        errorMessage = "Connexion interrompue",
                        errorType = ErrorType.STREAM_INTERRUPTED,
                        connectionStatus = ConnectionStatus.DISCONNECTED
                    ) }
                }
            }
        }
    }

    /** Crée une nouvelle conversation en DB, ou réutilise la conversation reprise. */
    private suspend fun getOrCreateConversation(firstUserText: String): Long {
        if (currentConversationId >= 0) return currentConversationId
        val sessionId = settings.activeSessionId
        val convId = conversationDao.insert(
            Conversation(
                title = firstUserText.take(80),
                sessionId = sessionId,
                type  = if (_uiState.value.isVoiceMode) "voice" else "text"
            )
        )
        currentConversationId = convId
        updateState { copy(resumedConversationId = convId) }
        return convId
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
                val connected = chatStreamHandler.checkHealth() == HealthResult.Ok
                updateState { copy(
                    serverConnected = connected,
                    connectionStatus = if (connected) ConnectionStatus.CONNECTED else connectionStatus.let {
                        if (it == ConnectionStatus.RECONNECTING) it else ConnectionStatus.DISCONNECTED
                    }
                ) }
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

    private fun isAppInForeground(): Boolean {
        val am = getApplication<android.app.Application>()
            .getSystemService(android.app.ActivityManager::class.java)
        @Suppress("DEPRECATION")
        return am.runningAppProcesses?.any {
            it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
            it.processName == getApplication<android.app.Application>().packageName
        } == true
    }

    private inline fun updateState(transform: UiState.() -> UiState) {
        _uiState.value = _uiState.value.transform()
    }

    // ─────────────────────────── Sessions Hermes ──────────────────────────

    /**
     * Garantit qu'une session active existe au démarrage.
     * Si la table est vide ou aucune session n'est marquée active,
     * crée "Session principale" et la marque active.
     */
    /**
     * Garantit qu'une session existe pour envoyer un message — sans en créer une en
     * Room tant qu'aucun message n'a été envoyé (création paresseuse, voir
     * startPendingSession()/materializePendingSession()). Aucune session "de secours"
     * automatique, y compris au tout premier lancement de l'app.
     */
    private fun ensureActiveSession() {
        viewModelScope.launch {
            val active = sessionDao.getActive()
            if (active == null) {
                startPendingSession()
            } else {
                settings.activeSessionId = active.id
                // Recharge la conversation Room liée à cette session
                val conv = conversationDao.getBySessionId(active.id)
                if (conv != null) {
                    currentConversationId = conv.id
                    updateState { copy(resumedConversationId = conv.id) }
                }
                // Sinon restoreLastConversation() (appelé dans init) a déjà chargé la dernière conv
            }
        }
    }

    /**
     * Point d'entrée "+ Nouvelle Session" (drawer) — création paresseuse : génère un
     * UUID en mémoire, n'insère RIEN en Room tant que le premier message n'a pas été
     * envoyé avec succès (voir materializePendingSession()). Annule tout tour en cours.
     */
    fun startPendingSession() {
        streamJob?.cancel()
        pendingSessionId = java.util.UUID.randomUUID().toString()
        currentConversationId = -1
        settings.activeSessionId = null
        LatencyLog.mark("PENDING_START", pendingSessionId!!)
        updateState { copy(resumedConversationId = null, transcript = "", response = "", errorMessage = null, errorType = null) }
    }

    /**
     * Matérialise pendingSessionId en une vraie session Room, déclenchée sur
     * StreamEvent.Connected (le relay a accepté le tour) — pas avant, pour ne rien
     * persister si le réseau échoue (voir sendToHermes()). Titre dérivé localement
     * du premier message (25 premiers caractères + "…" si tronqué) — confirmé auprès
     * de Hermes qu'aucun titrage automatique serveur n'est disponible via /v1/responses
     * (maybe_auto_title n'existe que côté gateway Telegram/Discord, pas l'API server ;
     * un titrage via appel LLM dédié depuis le relay ajouterait latence/coût pour un
     * gain jugé mineur par Hermes lui-même — recommandation : gérer le titre localement).
     */
    private suspend fun materializePendingSession(firstUserText: String) {
        val pending = pendingSessionId
        if (pending == null) {
            // Ne devrait pas arriver : Connected implique soit une session deja active
            // (settings.activeSessionId non-null, cf. sendToHermes), soit une session
            // pending non consommee. Si ce cas se produit, il faut le voir dans les logs.
            LatencyLog.mark("MATERIALIZE_SKIP", "none", "activeSessionId=${settings.activeSessionId}")
            return
        }
        val name = firstUserText.trim().take(25).let {
            if (firstUserText.trim().length > 25) "$it…" else it
        }.ifBlank { "Nouvelle session" }
        val session = HermesSession(id = pending, name = name, isActive = true)
        sessionDao.deactivateAll()
        sessionDao.insert(session)
        settings.activeSessionId = session.id
        LatencyLog.mark("MATERIALIZE", pending, "name=${session.name}")
        pendingSessionId = null
    }

    /** Retourne l'ID de session actif (depuis cache SharedPrefs). */
    fun getActiveSessionId(): String =
        settings.activeSessionId ?: "hasan-mobile"

    /**
     * Active une session existante.
     * Hermes gère l'historique via "conversation" — on change juste l'ID actif
     * et on recharge la conversation Room locale pour l'affichage.
     */
    fun activateSession(session: HermesSession) {
        viewModelScope.launch {
            sessionDao.deactivateAll()
            sessionDao.activateById(session.id)
            settings.activeSessionId = session.id

            val conv = conversationDao.getBySessionId(session.id)
            if (conv != null) {
                currentConversationId = conv.id
                updateState { copy(resumedConversationId = conv.id, transcript = "", response = "") }
            } else {
                startNewConversation()
            }
        }
    }

    fun renameSession(session: HermesSession, newName: String) {
        viewModelScope.launch {
            sessionDao.update(session.copy(name = newName))
        }
    }

    /** Supprime une session locale. */
    fun deleteSession(session: HermesSession) {
        viewModelScope.launch {
            sessionDao.delete(session)
            if (session.isActive) {
                val remaining = sessionDao.getActive()
                if (remaining == null) startPendingSession()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        streamJob?.cancel()
        healthJob?.cancel()
        uiUpdateJob?.cancel()
        ttsManager.release()
        HassanSoundPlayer.release()
        bargeInListener.stop()
        connectionManager.disconnect()
    }

    // ─────────────────────────── Relay (WebSocket) ─────────────────────────

    /** Approuve le certificat TOFU du relay et retente la connexion. */
    fun trustRelayCertAndRetry(fingerprint: String) {
        connectionManager.trustCertificate(fingerprint)
        updateState { copy(relayCertCheck = null) }
        connectionManager.connect()
    }

    fun dismissRelayCertCheck() {
        updateState { copy(relayCertCheck = null) }
    }

    /** Point d'entrée pairing — [qrText] est le contenu déjà décodé d'un QR (scan fait par l'appelant, voir étape 9). */
    fun pairFromQr(qrText: String) {
        viewModelScope.launch {
            when (val result = pairingManager.pairFromQrContent(qrText)) {
                is PairingManager.PairingResult.Success -> {
                    updateState { copy(relayPaired = true, errorMessage = null) }
                    connectionManager.connect()
                }
                is PairingManager.PairingResult.CertificateCheckRequired -> {
                    updateState { copy(relayCertCheck = result.certCheck) }
                }
                is PairingManager.PairingResult.InvalidQrContent -> {
                    updateState { copy(errorMessage = "QR de pairing invalide : ${result.reason}") }
                }
                is PairingManager.PairingResult.ServerRejected -> {
                    updateState { copy(errorMessage = "Pairing refusé (${result.httpCode}) : ${result.error}") }
                }
                is PairingManager.PairingResult.NetworkError -> {
                    updateState { copy(errorMessage = "Pairing impossible : ${result.message}") }
                }
            }
        }
    }

    companion object {
        private val SENTENCE_SEPARATORS = listOf(". ", "! ", "? ", ", ")
        private const val MAX_TOKENS_BEFORE_SPEAK = 5
    }
}

/** État complet de l'UI. */
data class UiState(
    val isListening:           Boolean          = false,
    val isVoiceMode:           Boolean          = true,
    val sttStatus:             SttStatus        = SttStatus.IDLE,
    val ttsStatus:             TtsStatus        = TtsStatus.IDLE,
    val transcript:            String           = "",
    val response:              String           = "",
    val serverConnected:       Boolean          = false,
    val connectionStatus:      ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val errorMessage:          String?          = null,
    val errorType:             ErrorType?       = null,
    val ttsEnabled:            Boolean          = true,
    val wakeWordEnabled:       Boolean          = true,
    val resumedConversationId: Long?            = null,
    val thinkingMessage:       String?          = null,
    val ttsPlayingMessageId:   Long?            = null,
    val ttsOnline:             Boolean          = false,
    val ttsFallbackMessage:    String?          = null,
    val relayConnectionStatus: RelayConnectionStatus = RelayConnectionStatus.DISCONNECTED,
    val relayCertCheck:        com.hasan.v1.auth.CertPinStore.CertCheckResult? = null,
    val relayPaired:           Boolean          = false
)

enum class SttStatus { IDLE, STARTING, LISTENING, PROCESSING, SENDING, STREAMING }
enum class TtsStatus  { IDLE, SPEAKING }
enum class ConnectionStatus { CONNECTED, RECONNECTING, DISCONNECTED }

/** État synthétique du pipeline vocal — dérivé de UiState pour l'affichage. */
sealed class VoiceState {
    object Idle : VoiceState()
    object WakeWordListening : VoiceState()
    object WakeWordDetected : VoiceState()
    object SttListening : VoiceState()
    object SttProcessing : VoiceState()
    object HermesThinking : VoiceState()
    data class HermesStreaming(val toolMessage: String? = null) : VoiceState()
    object TtsSpeaking : VoiceState()
    data class Error(val message: String) : VoiceState()
}

/** Dérive le VoiceState depuis l'état UI courant. */
fun UiState.voiceState(): VoiceState = when {
    errorMessage != null && !errorMessage.startsWith("CERT:") ->
        VoiceState.Error(errorMessage)
    ttsStatus == TtsStatus.SPEAKING     -> VoiceState.TtsSpeaking
    sttStatus == SttStatus.STREAMING    -> VoiceState.HermesStreaming(thinkingMessage)
    sttStatus == SttStatus.SENDING      -> VoiceState.HermesThinking
    sttStatus == SttStatus.PROCESSING   -> VoiceState.SttProcessing
    sttStatus == SttStatus.LISTENING    -> VoiceState.SttListening
    sttStatus == SttStatus.STARTING     -> VoiceState.WakeWordDetected
    wakeWordEnabled                     -> VoiceState.WakeWordListening
    else                                -> VoiceState.Idle
}



