package com.hasan.v1

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hasan.v1.audio.BargeInListener
import com.hasan.v1.auth.PairingManager
import com.hasan.v1.auth.SessionTokenStore
import com.hasan.v1.network.ActivityLog
import com.hasan.v1.network.ConnectionManager
import com.hasan.v1.network.RelayConnectionStatus
import com.hasan.v1.network.models.ErrorType
import com.hasan.v1.network.models.toolDisplayMessage
import com.hasan.v1.utils.LatencyLog
import com.hasan.v1.webui.WebUiChatStream
import com.hasan.v1.webui.WebUiClarifyStream
import com.hasan.v1.webui.WebUiClientHolder
import com.hasan.v1.webui.models.WebUiHealthResult
import com.hasan.v1.webui.models.WebUiLoginResult
import com.hasan.v1.webui.models.WebUiSteerResult
import com.hasan.v1.webui.models.WebUiStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hasan.v1.db.Conversation
import com.hasan.v1.db.HassanDatabase
import com.hasan.v1.db.HermesSession
import com.hasan.v1.db.Message
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
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
        send = { envelope -> connectionManager.send(envelope) },
        requestConfirmation = { capability, params -> requestBridgeConfirmation(capability, params) }
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

    // Chat : hermes-webui (REST/SSE), transport distinct du bridge WSS
    // ci-dessus (canaux system/proactive/bridge, inchangés). Instance
    // partagée avec HassanWakeWordService/WakeWordPipeline via
    // WebUiClientHolder — un client HTTP + cookie n'a pas besoin d'une
    // instance par composant comme la connexion WS état-pleine du bridge.
    private val webUiRestClient = WebUiClientHolder.get(application)
    private val webUiChatStream = WebUiChatStream(webUiRestClient)
    private val webUiClarifyStream = WebUiClarifyStream(webUiRestClient)
    private val webUiModelsClient = com.hasan.v1.webui.WebUiModelsClient(webUiRestClient)

    // Clarify : flux SSE séparé du chat côté hermes-webui (contrairement à
    // l'ancien chat/clarify, une enveloppe dans le même flux WS) — écouté en
    // continu pour la session active, indépendamment d'un tour de chat en
    // cours. Redémarré à chaque changement de session active (voir
    // activateSession()/ensureActiveSession()/startPendingSession()).
    private var clarifyJob: Job? = null

    private fun observeClarifyForSession(sessionId: String) {
        clarifyJob?.cancel()
        clarifyJob = viewModelScope.launch {
            webUiClarifyStream.stream(sessionId).collect { prompt ->
                updateState {
                    copy(
                        pendingClarify = prompt?.let {
                            PendingClarify(
                                sessionId = sessionId,
                                clarifyId = it.clarifyId,
                                question = it.question,
                                choices = it.choicesOffered
                            )
                        }
                    )
                }
            }
        }
    }

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

    /** stream_id du tour hermes-webui en cours, ou null si aucun run actif — alimente cancelActiveChat()/steer routing. */
    private var activeStreamId: String? = null

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
        refreshWebUiLoginState()
        viewModelScope.launch(Dispatchers.IO) { messageDao.deleteAllStreaming() }
        restoreLastConversation()
        ensureActiveSession()
        observeBackgroundConversationUpdates()
        connectionManager.connect()
        loadAvailableModels()
        syncSessionsFromServer()
    }

    /**
     * Peuple Room avec les sessions connues du serveur mais absentes
     * localement (ex: créée depuis un autre client hermes-webui) — upsert
     * idempotent via sessionDao.insert() (REPLACE), jamais de suppression
     * locale automatique pour ne pas perdre une session suite à une erreur
     * réseau transitoire. Room reste la source d'affichage du drawer ;
     * cette sync est un simple rattrapage au démarrage, pas un polling.
     */
    private fun syncSessionsFromServer() {
        viewModelScope.launch {
            val serverSessions = withContext(Dispatchers.IO) { webUiRestClient.listSessions() }
            if (serverSessions.isEmpty()) return@launch
            val localIds = sessionDao.getAll().first().map { it.id }.toSet()
            serverSessions.filter { it.sessionId !in localIds }.forEach { summary ->
                sessionDao.insert(
                    HermesSession(
                        id = summary.sessionId,
                        name = summary.title?.takeIf { it.isNotBlank() } ?: "Session",
                        isActive = false
                    )
                )
            }
        }
    }

    /** Charge le catalogue de modèles une fois au démarrage — voir WebUiModelsClient. */
    private fun loadAvailableModels() {
        viewModelScope.launch {
            val catalog = webUiModelsClient.getModels() ?: return@launch
            updateState {
                copy(
                    availableModels = catalog.groups.flatMap { it.models },
                    selectedModel = settings.webUiSelectedModel.takeIf { it.isNotBlank() }
                )
            }
        }
    }

    /** Change le modèle utilisé pour les prochains tours de chat — persisté via SettingsManager. */
    fun selectModel(modelId: String) {
        settings.webUiSelectedModel = modelId
        updateState { copy(selectedModel = modelId) }
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

    /**
     * Envoi depuis le champ texte (mode clavier). Si un tour est déjà en
     * cours (activeStreamId non-null), le texte est injecté comme steer
     * plutôt que d'attendre la fin du tour pour envoyer un nouveau message —
     * cohérent avec la sémantique serveur ("steer is active-run guidance",
     * voir WebUiRestClient.steerChat). Le steer n'interrompt pas le stream
     * en cours : aucun changement de sttStatus/UI, juste une confirmation
     * silencieuse ou un message d'erreur bref en cas de refus.
     */
    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        val streamId = activeStreamId
        val sessionId = settings.activeSessionId
        if (streamId != null && sessionId != null) {
            steerActiveChat(sessionId, text)
            return
        }
        ttsBuffer.clear()
        tokenCount = 0
        updateState { copy(transcript = text, response = "", errorMessage = null, thinkingMessage = null) }
        sendToHermes(text)
    }

    private fun steerActiveChat(sessionId: String, text: String) {
        viewModelScope.launch {
            when (val result = withContext(Dispatchers.IO) { webUiRestClient.steerChat(sessionId, text) }) {
                is WebUiSteerResult.Accepted ->
                    LatencyLog.mark("STEER_ACCEPTED", result.streamId, text.take(80))
                is WebUiSteerResult.Rejected -> {
                    LatencyLog.mark("STEER_REJECTED", sessionId, result.fallback)
                    updateState { copy(errorMessage = "Message non pris en compte (${result.fallback})") }
                }
                is WebUiSteerResult.NetworkError -> {
                    LatencyLog.mark("STEER_NETWORK_ERROR", sessionId, result.message)
                    updateState { copy(errorMessage = "Message non envoyé (hermes-webui injoignable)") }
                }
            }
        }
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

    /** Health check hermes-webui (GET /health) — utilisé par le bouton "Tester la connexion" des Réglages. */
    suspend fun checkHealthViaRelay(): WebUiHealthResult = webUiRestClient.checkHealth()

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

            // La session existe toujours déjà en Room à ce stade — créée côté
            // serveur dès "+ Nouvelle session" (voir startPendingSession()),
            // plus de création paresseuse ici (hermes-webui génère le
            // session_id serveur, contrairement à l'ancien UUID local).
            val effectiveSessionId = settings.activeSessionId ?: run {
                updateState { copy(sttStatus = SttStatus.IDLE, errorMessage = "Aucune session active") }
                return@launch
            }

            val turn = streamStartTime.toString()
            LatencyLog.mark("SEND", turn, "sessionId=$effectiveSessionId user=${userText.take(80)}")

            updateState { copy(sttStatus = SttStatus.SENDING) }

            val streamId = withContext(Dispatchers.IO) {
                webUiRestClient.startChat(
                    effectiveSessionId,
                    userText,
                    settings.webUiSelectedModel.takeIf { it.isNotBlank() }
                )
            }
            if (streamId == null) {
                updateState { copy(
                    sttStatus = SttStatus.IDLE,
                    errorMessage = "Envoi impossible (hermes-webui injoignable)",
                    errorType = ErrorType.HERMES_UNREACHABLE,
                    connectionStatus = ConnectionStatus.DISCONNECTED
                ) }
                return@launch
            }
            activeStreamId = streamId

            // Point d'insertion Room : au premier plan, juste après un
            // startChat() réussi (avant l'ancien StreamEvent.Connected, qui
            // n'a pas d'équivalent en HTTP one-shot — pas de phase
            // "connecting" séparée avec ce transport).
            val convId = getOrCreateConversation(userText)
            if (retryCount == 0) {
                messageDao.insert(Message(conversationId = convId, role = "user", content = userText))
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

            // reachedTerminal : filet de sécurité contre un flow qui se termine (fin
            // normale ou exception) sans jamais émettre Done/Error — auparavant un tel
            // silence laissait sttStatus bloqué en SENDING/STREAMING indéfiniment, sans
            // aucun message d'erreur pour l'utilisateur (voir turn=1784139600101).
            var reachedTerminal = false
            try {
            webUiChatStream.stream(streamId, effectiveSessionId).collect { event ->
                when (event) {
                    is WebUiStreamEvent.Tool ->
                        updateState { copy(thinkingMessage = toolDisplayMessage(event.name)) }

                    is WebUiStreamEvent.ToolComplete -> {
                        // Signale la fin d'un outil (succès/échec/durée) — jusqu'ici jamais
                        // reçu côté client (event tool_complete non géré avant l'étape 4.4),
                        // l'app ne savait jamais qu'un outil avait fini. Pas de nouvelle UI
                        // dédiée : on efface juste le thinkingMessage en cas d'échec pour ne
                        // pas laisser "Hasan utilise X..." affiché indéfiniment si le tour
                        // continue avec un nouvel outil ensuite (le prochain event Tool le
                        // remplacera de toute façon, mais un échec silencieux ne devrait pas
                        // laisser un message obsolète affiché plus longtemps que nécessaire).
                        LatencyLog.mark(
                            if (event.isError) "TOOL_COMPLETE_ERROR" else "TOOL_COMPLETE",
                            turn,
                            "name=${event.name} duration=${event.durationMs}"
                        )
                        if (event.isError) updateState { copy(thinkingMessage = null) }
                    }

                    is WebUiStreamEvent.PendingSteerLeftover -> {
                        // Un /steer accepté trop tard (le tour a fini avant qu'il ne soit
                        // consommé) — le serveur renvoie le texte pour qu'on le renvoie au
                        // prochain tour plutôt que de le perdre silencieusement.
                        LatencyLog.mark("STEER_LEFTOVER", turn, event.text.take(200))
                        lastUserText = event.text
                    }

                    is WebUiStreamEvent.Title -> {
                        // Titre généré par LLM en tâche de fond (voir
                        // api/streaming.py _run_background_title_update),
                        // remplace le titre local tronqué (80 premiers
                        // caractères du message utilisateur) une fois le
                        // vrai titre serveur disponible.
                        LatencyLog.mark("TITLE_GENERATED", turn, event.title)
                        conversationDao.getById(convId)?.let { conv ->
                            conversationDao.update(conv.copy(title = event.title))
                        }
                        sessionDao.getById(effectiveSessionId)?.let { session ->
                            sessionDao.update(session.copy(name = event.title))
                        }
                    }

                    is WebUiStreamEvent.Cancel -> {
                        reachedTerminal = true
                        uiUpdateJob?.cancel()
                        uiUpdateJob = null
                        // Le placeholder streaming garde le texte déjà reçu (pas de perte du
                        // partiel affiché) — juste marqué non-streaming, contrairement à
                        // AppError qui supprime le placeholder (un cancel est volontaire, pas
                        // un échec : la réponse partielle reste utile à l'utilisateur).
                        val partialText = streamingBuffer.toString()
                        if (streamingMessageId >= 0 && partialText.isNotBlank()) {
                            messageDao.update(
                                Message(
                                    id = streamingMessageId,
                                    conversationId = convId,
                                    role = "assistant",
                                    content = partialText,
                                    isStreaming = false
                                )
                            )
                        } else if (streamingMessageId >= 0) {
                            messageDao.deleteById(streamingMessageId)
                        }
                        streamingMessageId = -1
                        LatencyLog.mark("CANCEL", turn, event.message)
                        LatencyLog.clear(turn)
                        updateState { copy(sttStatus = SttStatus.IDLE, thinkingMessage = null) }
                    }

                    is WebUiStreamEvent.StreamEnd -> {
                        // Vrai signal de fermeture de connexion SSE — peut suivre Done/Cancel/
                        // AppError (déjà traités, reachedTerminal=true) ou survenir seul sur
                        // certains chemins serveur. Le garde-fou reachedTerminal évite un
                        // double traitement si Done l'a déjà marqué.
                        if (!reachedTerminal) {
                            reachedTerminal = true
                            uiUpdateJob?.cancel()
                            uiUpdateJob = null
                            updateState { copy(sttStatus = SttStatus.IDLE, thinkingMessage = null) }
                        }
                    }

                    is WebUiStreamEvent.Approval -> {
                        // Hors périmètre étape 3 (pas de mécanisme d'approbation d'outil
                        // dans l'app aujourd'hui) — à traiter dans une étape future
                        // (steer/tool-calls). Le run reste bloqué côté serveur en
                        // attendant /api/approval/respond ; on laisse juste tourner
                        // le thinkingMessage existant plutôt que de rien afficher.
                        LatencyLog.mark("APPROVAL_UNHANDLED", turn, "command=${event.command.take(80)}")
                    }

                    is WebUiStreamEvent.Token -> {
                        synchronized(streamingBuffer) { streamingBuffer.append(event.text) }
                        updateState { copy(response = response + event.text, thinkingMessage = null) }
                    }

                    is WebUiStreamEvent.Done -> {
                        reachedTerminal = true
                        uiUpdateJob?.cancel()
                        uiUpdateJob = null
                        updateState { copy(thinkingMessage = null) }
                        val responseText = streamingBuffer.toString()
                        // TTS déclenché sur le texte complet une fois le stream terminé
                        if (settings.ttsEnabled && responseText.isNotBlank()) ttsManager.speak(responseText)
                        val durationMs = System.currentTimeMillis() - streamStartTime
                        LatencyLog.mark("DONE", turn, "total=${durationMs}ms len=${responseText.length}")
                        // Hermes peut catcher sa propre erreur d'appel LLM (ex: tool_calls
                        // vide rejeté par DeepSeek) et la renvoyer comme SI c'était le texte
                        // de réponse normal — chat/done classique côté protocole, invisible
                        // dans les logs sans inspecter le contenu. Détection explicite pour
                        // ne plus dépendre d'un screenshot pour repérer ce cas (voir la
                        // série d'incidents tool_calls vide sur plusieurs sessions).
                        if (looksLikeDisguisedLlmError(responseText)) {
                            LatencyLog.mark(
                                "SUSPECT_ERROR_AS_RESPONSE", turn,
                                "sessionId=$effectiveSessionId content=${responseText.take(300)}"
                            )
                        } else {
                            LatencyLog.mark("DONE_CONTENT", turn, responseText.take(200))
                        }
                        LatencyLog.clear(turn)
                        // Pas de responseId chaîné (jamais le cas non plus avec l'ancien
                        // transport — le contexte est porté par session_id uniquement,
                        // confirmé côté hermes-webui : POST /api/chat/start {session_id,
                        // message} sans previous_response_id).
                        val inputTokens = event.sessionRaw?.optInt("input_tokens", 0) ?: 0
                        val outputTokens = event.sessionRaw?.optInt("output_tokens", 0) ?: 0
                        val metadata = if (inputTokens > 0 || outputTokens > 0) {
                            """{"input_tokens":$inputTokens,"output_tokens":$outputTokens,"duration_ms":$durationMs}"""
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

                    is WebUiStreamEvent.AppError -> {
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
                        // Pas de ErrorType distingué côté serveur hermes-webui (contrairement
                        // à l'ancien relay) — un seul type générique, NO_NETWORK reste géré
                        // localement en amont via hasNetwork().
                        LatencyLog.mark("ERROR", turn, event.message.take(200))
                        LatencyLog.clear(turn)
                        updateState { copy(
                            sttStatus = SttStatus.IDLE,
                            errorMessage = event.message,
                            errorType = ErrorType.SERVER_ERROR,
                            connectionStatus = ConnectionStatus.DISCONNECTED
                        ) }
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
                // Le tour est terminé quelle que soit l'issue (Done/AppError/
                // Cancel/StreamEnd/orphelin) — plus de run actif à annuler.
                activeStreamId = null
            }
        }
    }

    /**
     * Annule le tour hermes-webui en cours (bouton Stop du Chat, distinct de
     * stopTts() qui coupe seulement la synthèse vocale locale). L'event SSE
     * `cancel` arrive ensuite naturellement dans le flux déjà ouvert par
     * sendToHermes() — pas besoin de fermer manuellement le flow ici.
     */
    fun cancelActiveChat() {
        val streamId = activeStreamId ?: return
        viewModelScope.launch {
            val cancelled = withContext(Dispatchers.IO) { webUiRestClient.cancelChat(streamId) }
            LatencyLog.mark(if (cancelled) "CANCEL_REQUESTED_OK" else "CANCEL_REQUESTED_FAILED", streamId, "")
        }
    }

    /**
     * Répond à une clarification en cours ([UiState.pendingClarify]) — POST
     * /api/clarify/respond (suspend, contrairement à l'ancien envoi
     * fire-and-forget sur le WS relay). Le prompt disparaît de
     * [UiState.pendingClarify] via [observeClarifyForSession] (flux SSE
     * clarify séparé, pas mis à null ici directement) une fois le serveur
     * notifié de la résolution.
     */
    fun respondToClarify(response: String) {
        val pending = _uiState.value.pendingClarify ?: return
        updateState { copy(sttStatus = SttStatus.SENDING) }
        viewModelScope.launch {
            val ok = webUiRestClient.respondClarify(pending.sessionId, pending.clarifyId, response)
            if (!ok) {
                updateState { copy(
                    sttStatus = SttStatus.IDLE,
                    errorMessage = "Réponse à la clarification échouée (peut-être expirée)",
                    errorType = ErrorType.HERMES_UNREACHABLE
                ) }
            }
        }
    }

    // Une seule confirmation bridge à la fois — cohérent avec le canal bridge qui traite
    // les commandes séquentiellement (multiplexer.bridge.collect n'est pas parallélisé).
    private var pendingBridgeDeferred: CompletableDeferred<Boolean>? = null

    /**
     * Affiche une UI de confirmation pour une capability marquée "confirmation requise"
     * (send_sms, get_location, get_contacts par défaut — voir Capability.kt) et suspend
     * jusqu'à la réponse de l'utilisateur. Remplace l'ancien refus inconditionnel
     * "confirmation_required" — voir BridgeCommandHandler.kt.
     */
    private suspend fun requestBridgeConfirmation(capability: String, params: JSONObject): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingBridgeDeferred = deferred
        updateState { copy(pendingBridgeConfirmation = PendingBridgeConfirmation(capability, params)) }
        return try {
            deferred.await()
        } finally {
            pendingBridgeDeferred = null
            updateState { copy(pendingBridgeConfirmation = null) }
        }
    }

    /** Réponse utilisateur à la confirmation bridge affichée (voir requestBridgeConfirmation). */
    fun respondToBridgeConfirmation(authorized: Boolean) {
        pendingBridgeDeferred?.complete(authorized)
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

    /**
     * serverConnected ne reflète QUE la joignabilité applicative de hermes-webui
     * (GET /health, requête HTTP one-shot) — il ne doit jamais piloter
     * connectionStatus, qui est le vrai état du WebSocket (déjà géré par l'observation de
     * connectionManager.connectionStatus, voir le bloc .apply de connectionManager).
     * Avant ce fix, un simple ralentissement de Hermes (observé en pratique : Hermes peut
     * mettre plusieurs secondes, voire redémarrer côté VPS) faisait passer
     * connectionStatus à DISCONNECTED alors que le WSS était parfaitement CONNECTED,
     * désactivant à tort le champ de saisie ("Hermes indisponible") pendant qu'un envoi
     * de message aurait en fait pu réussir normalement.
     */
    private fun startHealthCheckLoop() {
        healthJob = viewModelScope.launch {
            while (true) {
                val connected = webUiRestClient.checkHealth() == WebUiHealthResult.Ok
                updateState { copy(serverConnected = connected, webUiLoggedIn = !settings.webUiSessionCookie.isNullOrBlank()) }
                delay(10_000)
            }
        }
    }

    /**
     * Resynchronise webUiLoggedIn immédiatement après une connexion réussie
     * (Réglages > connexion manuelle, ou pairing QR) — sans attendre le
     * prochain tick de startHealthCheckLoop (jusqu'à 10s de latence sinon,
     * pendant lesquelles le champ de saisie resterait à tort désactivé).
     */
    fun refreshWebUiLoginState() {
        updateState { copy(webUiLoggedIn = !settings.webUiSessionCookie.isNullOrBlank()) }
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
                observeClarifyForSession(active.id)
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
     * Point d'entrée "+ Nouvelle Session" (drawer) — création immédiate côté
     * serveur (POST /api/session/new) : hermes-webui génère le session_id,
     * contrairement à l'ancien UUID local généré paresseusement au premier
     * message (voir git history pour l'ancien mécanisme materializePendingSession).
     * Le titre par défaut est ajusté au premier message envoyé (voir
     * getOrCreateConversation, le titre Conversation reste local — hermes-webui
     * n'a pas de titrage automatique serveur, confirmé en étape 3).
     */
    fun startPendingSession() {
        streamJob?.cancel()
        currentConversationId = -1
        settings.activeSessionId = null
        updateState { copy(resumedConversationId = null, transcript = "", response = "", errorMessage = null, errorType = null) }
        viewModelScope.launch {
            val sessionId = webUiRestClient.createSession()
            if (sessionId == null) {
                LatencyLog.mark("SESSION_CREATE_FAILED", "none", "")
                updateState { copy(errorMessage = "Création de session impossible (hermes-webui injoignable)") }
                return@launch
            }
            val session = HermesSession(id = sessionId, name = "Nouvelle session", isActive = true)
            sessionDao.deactivateAll()
            sessionDao.insert(session)
            settings.activeSessionId = sessionId
            observeClarifyForSession(sessionId)
            LatencyLog.mark("SESSION_CREATED", sessionId, "")
        }
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
            observeClarifyForSession(session.id)

            val conv = conversationDao.getBySessionId(session.id)
            if (conv != null) {
                currentConversationId = conv.id
                updateState { copy(resumedConversationId = conv.id, transcript = "", response = "") }
            } else {
                startNewConversation()
            }
        }
    }

    /**
     * Room reste la source d'affichage immédiat (rename optimiste), le
     * serveur est mis à jour en tâche de fond — en cas d'échec réseau, pas
     * de rollback local (log seulement), cohérent avec le reste du client
     * qui ne fait pas de reconciliation complexe. Jusqu'ici cet appel ne
     * touchait QUE Room, jamais POST /api/session/rename — le nom changé
     * dans le drawer n'existait donc que localement (bug pré-existant,
     * corrigé étape 4.4).
     */
    fun renameSession(session: HermesSession, newName: String) {
        viewModelScope.launch {
            sessionDao.update(session.copy(name = newName))
            val ok = withContext(Dispatchers.IO) { webUiRestClient.renameSession(session.id, newName) }
            if (!ok) LatencyLog.mark("SESSION_RENAME_SERVER_FAILED", session.id, newName.take(80))
        }
    }

    /**
     * Supprime une session locale ET côté serveur — jusqu'ici cet appel ne
     * touchait QUE Room, la session continuait d'exister sur le VPS (bug
     * pré-existant, corrigé étape 4.4).
     */
    fun deleteSession(session: HermesSession) {
        viewModelScope.launch {
            sessionDao.delete(session)
            if (session.isActive) {
                val remaining = sessionDao.getActive()
                if (remaining == null) startPendingSession()
            }
            val ok = withContext(Dispatchers.IO) { webUiRestClient.deleteSession(session.id) }
            if (!ok) LatencyLog.mark("SESSION_DELETE_SERVER_FAILED", session.id, "")
        }
    }

    override fun onCleared() {
        super.onCleared()
        streamJob?.cancel()
        healthJob?.cancel()
        uiUpdateJob?.cancel()
        clarifyJob?.cancel()
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
                    // Configure hermes-webui (chat) si le QR portait aussi ces
                    // champs — absent d'un QR généré sans WEBUI_URL/WEBUI_PASSWORD
                    // côté hermes-relay.service, auquel cas le pairing bridge
                    // reste valide sans configurer le chat.
                    if (result.webUiUrl != null && result.webUiPassword != null) {
                        settings.webUiServerUrl = result.webUiUrl
                        val loginResult = webUiRestClient.login(result.webUiPassword)
                        if (loginResult !is WebUiLoginResult.Ok) {
                            Log.w(TAG, "Login hermes-webui après pairing QR échoué : $loginResult")
                            updateState { copy(errorMessage = "Pairing bridge OK, mais connexion chat hermes-webui échouée") }
                        } else {
                            refreshWebUiLoginState()
                        }
                    }
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
        private const val TAG = "MainViewModel"
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
    val relayPaired:           Boolean          = false,
    val pendingClarify:        PendingClarify?  = null,
    val pendingBridgeConfirmation: PendingBridgeConfirmation? = null,
    val availableModels:       List<com.hasan.v1.webui.models.ModelOption> = emptyList(),
    val selectedModel:         String?          = null,
    /** Cookie de session hermes-webui présent — indépendant du relay bridge (relayPaired), voir WebUiAuthStore.isLoggedIn. */
    val webUiLoggedIn:         Boolean          = false
)

/** Clarification demandée par Hermes en cours (voir StreamEvent.ClarifyPrompt). */
data class PendingClarify(
    val sessionId: String,
    val clarifyId: String,
    val question: String,
    val choices: List<String>?
)

/** Confirmation bridge en attente (voir BridgeCommandHandler.requestConfirmation). */
data class PendingBridgeConfirmation(
    val capability: String,
    val params: JSONObject
)

enum class SttStatus { IDLE, STARTING, LISTENING, PROCESSING, SENDING, STREAMING }
enum class TtsStatus  { IDLE, SPEAKING }
enum class ConnectionStatus { CONNECTED, RECONNECTING, DISCONNECTED }

/**
 * Détecte une erreur d'appel LLM (Hermes/DeepSeek) renvoyée comme contenu de réponse
 * normal plutôt que via StreamEvent.Error — observé en pratique avec des messages du
 * type "HTTP 400: Invalid 'messages[N].tool_calls': empty array". Ce n'est PAS une
 * classification exhaustive, juste un filet de détection pour que ce cas précis
 * n'échappe plus à l'observation sans inspecter un screenshot à chaque fois.
 */
internal fun looksLikeDisguisedLlmError(text: String): Boolean {
    val head = text.trimStart().take(40)
    return head.startsWith("HTTP 4", ignoreCase = true) ||
        head.startsWith("HTTP 5", ignoreCase = true) ||
        text.contains("tool_calls", ignoreCase = true) && text.contains("empty array", ignoreCase = true)
}

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



