package com.hasan.v1

import android.content.ContentResolver
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hasan.v1.databinding.FragmentConversationBinding
import com.hasan.v1.network.RelayConnectionStatus
import com.hasan.v1.ui.components.ConnectionBadgeState
import com.hasan.v1.ui.components.HasanHeader
import com.hasan.v1.ui.screens.ChatClarifyUi
import com.hasan.v1.ui.screens.ChatInputUi
import com.hasan.v1.ui.screens.ChatScreen
import com.hasan.v1.ui.screens.ChatVoiceUi
import com.hasan.v1.ui.theme.HasanTheme
import com.hasan.v1.utils.HasanDialog
import com.hasan.v1.db.HassanDatabase
import com.hasan.v1.db.Message
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Fragment Chat principal — affiche le header Hasan, les bulles de messages
 * et la zone de saisie (mode vocal ou texte) via un ComposeView unique (ChatScreen).
 *
 * Ne contient aucune logique métier — délègue tout au MainViewModel.
 */
class ConversationFragment : Fragment(), SpeechRecognizerManager.SttListener {

    private var _binding: FragmentConversationBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private var sttManager: SpeechRecognizerManager? = null
    private var certDialogShown = false
    private var bridgeDialogShown = false

    private var lastVoiceState: VoiceState? = null
    private var sttVisualizerActive = false
    private var isVoiceMode = true
    private val vibrator by lazy {
        requireContext().getSystemService(Vibrator::class.java)
    }

    private val connectionBadgeState = mutableStateOf(
        ConnectionBadgeState(connected = false, readout = "")
    )

    /**
     * Sélecteur de fichiers générique (SAF) — un seul point d'entrée pour
     * photos ET documents, pas de choix galerie/fichiers séparé (décision
     * utilisateur, étape 6 migration webui). Chaque URI choisi est lu en
     * mémoire ici (content:// n'est pas un chemin fichier exploitable côté
     * OkHttp) puis uploadé via MainViewModel.uploadAttachment.
     */
    private val attachmentPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> uris.forEach { uri -> readAndUploadAttachment(uri) } }

    private fun readAndUploadAttachment(uri: android.net.Uri) {
        val resolver = requireContext().contentResolver
        val filename = queryDisplayName(resolver, uri) ?: uri.lastPathSegment ?: "fichier"
        val mimeType = resolver.getType(uri)
            ?: MimeTypeMap.getFileExtensionFromUrl(filename)?.let {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
            }
            ?: "application/octet-stream"
        try {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                Toast.makeText(requireContext(), "Lecture de \"$filename\" impossible", Toast.LENGTH_SHORT).show()
                return
            }
            viewModel.uploadAttachment(filename, mimeType, bytes)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Lecture de \"$filename\" impossible", Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: android.net.Uri): String? {
        return resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    }

    // ─────────────────────────── État Compose ─────────────────────────────

    private var messagesState by mutableStateOf<List<Message>>(emptyList())
    private var ttsPlayingMessageId by mutableStateOf<Long?>(null)
    private var voiceUiState by mutableStateOf(
        ChatVoiceUi(statusText = "", isWaveActive = false, showStopTts = false, ringLightTick = 0)
    )
    private var inputUiState by mutableStateOf(
        ChatInputUi(isVoiceMode = true, isListening = false, sttVisualizerActive = false, degraded = false, hint = "")
    )
    private var inputText by mutableStateOf("")
    private var clarifyState by mutableStateOf<ChatClarifyUi?>(null)
    private var ringLightTick = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConversationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sttManager = SpeechRecognizerManager(requireContext(), this)

        setupComposeHeader()
        setupComposeChat()
        observeUiState()
        observeMessages()
        observeWakeWord()
        observeIncomingMessages()
    }

    // ─────────────────────────── Header Compose ────────────────────────────

    private fun setupComposeHeader() {
        (binding.chatHeader as ComposeView).setContent {
            HasanTheme {
                HasanHeader(
                    connectionState = connectionBadgeState.value,
                    onMenuClick = { (activity as? MainActivity)?.openDrawer() }
                )
            }
        }
    }

    // ─────────────────────────── Chat Compose ──────────────────────────────

    private fun setupComposeChat() {
        (binding.chatComposeRoot as ComposeView).setContent {
            HasanTheme {
                ChatScreen(
                    messages = messagesState,
                    ttsPlayingMessageId = ttsPlayingMessageId,
                    voiceUi = voiceUiState,
                    inputUi = inputUiState,
                    inputText = inputText,
                    onInputTextChange = { inputText = it },
                    onSend = ::sendCurrentText,
                    onMicClick = ::onMicClick,
                    onMicLongPress = { (activity as? MainActivity)?.enterLightMode() },
                    onSwitchToText = ::switchToTextMode,
                    onStopTts = { viewModel.stopTts() },
                    onUserLongPress = { msg -> showUserMessageMenu(msg) },
                    onHasanLongPress = { msg -> showHasanMessageMenu(msg) },
                    onToggleTts = { msg -> toggleMessageTts(msg) },
                    onCopy = { msg -> copyToClipboard(msg.content) },
                    onRetry = { viewModel.retryLastMessage() },
                    clarify = clarifyState,
                    onClarifyResponse = { response -> viewModel.respondToClarify(response) },
                    onModelSelected = { modelId -> viewModel.selectModel(modelId) },
                    onCancelChat = { viewModel.cancelActiveChat() },
                    onAttachClick = { attachmentPickerLauncher.launch(arrayOf("*/*")) },
                    onRemoveAttachment = { att -> viewModel.removePendingAttachment(att) }
                )
            }
        }
    }

    private fun sendCurrentText() {
        val text = inputText.trim()
        if (text.isNotBlank()) {
            viewModel.sendTextMessage(text)
            inputText = ""
        }
    }

    private fun onMicClick() {
        val state = viewModel.uiState.value
        val listening = state.isListening || state.sttStatus == SttStatus.LISTENING || state.sttStatus == SttStatus.PROCESSING
        if (listening) {
            sttManager?.cancelAndDestroy()
            viewModel.onSttError(-1, "")
        } else {
            viewModel.toggleListening()
        }
    }

    // ─────────────────────────── Modes vocal / texte ──────────────────────

    private fun switchToTextMode() {
        isVoiceMode = false
        inputUiState = inputUiState.copy(isVoiceMode = false)
    }

    private fun switchToVoiceMode() {
        isVoiceMode = true
        inputUiState = inputUiState.copy(isVoiceMode = true)
    }

    // ─────────────────────────── Observation état UI ──────────────────────

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> renderState(state) }
            }
        }
    }

    private fun renderState(state: UiState) {
        // Indicateur de connexion dans le header — reflète l'état de la
        // connexion WebSocket relay, seul transport vers Hermes.
        updateConnectionBadge(state)

        // Mode dégradé — le chat passe entièrement par hermes-webui (REST/SSE)
        // depuis la migration, complètement indépendant du relay bridge
        // (relayConnectionStatus/RelayConnectionStatus ne concernent que le
        // canal bridge téléphone — SMS, localisation, etc., voir
        // BridgeCommandHandler). Se baser dessus ici désactivait à tort la
        // saisie tant que le relay n'était pas appairé, même quand
        // hermes-webui était parfaitement joignable et loggé (bug trouvé le
        // 2026-07-19 : connexion hermes-webui manuelle réussie dans les
        // Réglages, mais champ de saisie du Chat resté désactivé). Les deux
        // signaux nécessaires sont déjà là : serverConnected (GET /health,
        // rafraîchi toutes les 10s par startHealthCheckLoop) et
        // webUiLoggedIn (cookie de session présent, resynchronisé
        // immédiatement après login via refreshWebUiLoginState — pas
        // d'attente du prochain tick de health check).
        val degraded = !state.serverConnected || !state.webUiLoggedIn
        inputUiState = inputUiState.copy(
            degraded = degraded,
            hint = if (degraded) getString(R.string.error_hermes_readonly) else getString(R.string.hint_message),
            availableModels = state.availableModels,
            selectedModel = state.selectedModel,
            isStreaming = state.sttStatus == SttStatus.STREAMING,
            pendingAttachments = state.pendingAttachments,
            attachmentUploading = state.attachmentUploading
        )

        clarifyState = state.pendingClarify?.let { pending ->
            ChatClarifyUi(question = pending.question, choices = pending.choices)
        }

        // Certificat TOFU — dialog d'approbation si pas déjà affiché
        if (state.errorMessage?.startsWith("CERT:") == true && !certDialogShown) {
            certDialogShown = true
            // Format : "CERT:isChanged:fingerprint:storedFingerprint"
            val parts = state.errorMessage.removePrefix("CERT:").split(":", limit = 3)
            val isChanged = parts.getOrNull(0) == "true"
            val fingerprint = parts.getOrNull(1) ?: ""
            val storedFingerprint = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
            val rootUrl = com.hasan.v1.network.models.buildRootUrl(viewModel.settings.serverUrl)
            val formatted = fingerprint.chunked(24).joinToString("\n")

            if (isChanged && storedFingerprint != null) {
                val storedFmt = storedFingerprint.chunked(24).joinToString("\n")
                HasanDialog.confirm(
                    context = requireContext(),
                    title = "Certificat modifié",
                    message = "Le certificat de $rootUrl a changé.\n\nAncienne empreinte :\n$storedFmt\n\nNouvelle empreinte :\n$formatted\n\nCela peut indiquer une attaque. Réinitialiser la confiance ?",
                    confirmLabel = "Faire confiance",
                    cancelLabel = "Bloquer",
                    destructive = true,
                    onConfirm = { viewModel.trustCertAndRetry(fingerprint); certDialogShown = false },
                    onCancel  = { viewModel.clearError(); certDialogShown = false }
                )
            } else {
                HasanDialog.confirm(
                    context = requireContext(),
                    title = "Certificat non reconnu",
                    message = "Serveur : $rootUrl\n\nEmpreinte SHA-256 :\n$formatted\n\nFaire confiance à ce serveur ?",
                    confirmLabel = "Faire confiance",
                    cancelLabel = "Annuler",
                    onConfirm = { viewModel.trustCertAndRetry(fingerprint); certDialogShown = false },
                    onCancel  = { viewModel.clearError(); certDialogShown = false }
                )
            }
        } else if (state.errorMessage?.startsWith("CERT:") != true) {
            certDialogShown = false
        }

        // Confirmation bridge (send_sms, get_location, get_contacts par défaut — voir
        // Capability.kt authRequiredDefault) — dialog d'approbation d'une commande Hermes
        // qui touche à une capability sensible, avant exécution côté CapabilityExecutor.
        val pendingBridge = state.pendingBridgeConfirmation
        if (pendingBridge != null && !bridgeDialogShown) {
            bridgeDialogShown = true
            val cap = ALL_CAPABILITIES.find { it.name == pendingBridge.capability }
            val label = cap?.let { getString(it.labelRes) } ?: pendingBridge.capability
            val paramsText = pendingBridge.params.keys().asSequence()
                .joinToString("\n") { key -> "$key : ${pendingBridge.params.opt(key)}" }
            HasanDialog.confirm(
                context = requireContext(),
                title = "Hasan demande : $label",
                message = if (paramsText.isBlank()) "Autoriser cette action ?" else "$paramsText\n\nAutoriser cette action ?",
                confirmLabel = "Autoriser",
                cancelLabel = "Refuser",
                onConfirm = { viewModel.respondToBridgeConfirmation(true); bridgeDialogShown = false },
                onCancel = { viewModel.respondToBridgeConfirmation(false); bridgeDialogShown = false }
            )
        } else if (pendingBridge == null) {
            bridgeDialogShown = false
        }

        // Pipeline vocal — statut + animations + vibration via VoiceState
        val voiceState = state.voiceState()
        renderVoiceState(voiceState)

        // Synchronise l'icône play/pause du message en cours de lecture
        ttsPlayingMessageId = state.ttsPlayingMessageId

        // Fallback ElevenLabs → TTS natif (réseau/clé/quota indisponible) : notification ponctuelle
        state.ttsFallbackMessage?.let { message ->
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            viewModel.clearTtsFallbackMessage()
        }

        // Bouton micro en mode texte → stop + visualizer quand écoute active
        val listening = state.isListening || state.sttStatus == SttStatus.LISTENING || state.sttStatus == SttStatus.PROCESSING
        inputUiState = inputUiState.copy(isListening = listening)
        if (listening && !isVoiceMode && !sttVisualizerActive) {
            sttVisualizerActive = true
            inputUiState = inputUiState.copy(sttVisualizerActive = true)
        } else if (!listening && sttVisualizerActive) {
            sttVisualizerActive = false
            inputUiState = inputUiState.copy(sttVisualizerActive = false)
        }

        // Lance le STT au bon moment (arrête d'abord le précédent si actif)
        if (state.sttStatus == SttStatus.STARTING) {
            sttManager?.stopListening()
            checkPermissionAndStartStt()
        }

        // Arrête le STT si inactif → repasse en mode texte
        if (!state.isListening && state.sttStatus == SttStatus.IDLE) {
            sttManager?.cancelAndDestroy()
            if (isVoiceMode) {
                switchToTextMode()
            }
        }
    }

    private fun updateConnectionBadge(state: UiState) {
        val connected = state.relayConnectionStatus == RelayConnectionStatus.CONNECTED
        val readout = when (state.relayConnectionStatus) {
            RelayConnectionStatus.CONNECTED     -> "WSS · CONNECTÉ"
            RelayConnectionStatus.CONNECTING    -> "WSS · CONNEXION…"
            RelayConnectionStatus.RECONNECTING  -> "WSS · RECONNEXION…"
            RelayConnectionStatus.DISCONNECTED  -> "WSS · DÉCONNECTÉ"
        }
        connectionBadgeState.value = ConnectionBadgeState(connected = connected, readout = readout)
    }

    // ─────────────────────────── Messages DB ──────────────────────────────

    private fun observeMessages() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    val convId = state.resumedConversationId
                    if (convId == null) {
                        messagesState = emptyList()
                        return@collectLatest
                    }
                    val db = HassanDatabase.getInstance(requireContext())
                    db.messageDao().getMessagesForConversation(convId).collect { msgs ->
                        renderMessages(msgs, convId)
                    }
                }
            }
        }
        // Re-render when thinkingMessage changes (outil Hermes en cours / terminé).
        // distinctUntilChanged évite de recomposer la liste à chaque token de streaming
        // ou changement de ttsStatus — un submitList() trop fréquent peut recycler le
        // ViewHolder en cours de clic et avaler le MotionEvent. En Compose, la clé stable
        // sur chaque item LazyColumn (message.id) protège du même problème.
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState
                    .map { it.resumedConversationId to it.thinkingMessage }
                    .distinctUntilChanged()
                    .collect { (convId, _) ->
                        convId ?: return@collect
                        val db = HassanDatabase.getInstance(requireContext())
                        val msgs = db.messageDao().getMessagesForConversationOnce(convId)
                        renderMessages(msgs, convId)
                    }
            }
        }
    }

    private fun renderMessages(msgs: List<Message>, convId: Long) {
        val visible = msgs.filter { !it.isStreaming || it.role == "assistant" }.toMutableList()
        val state = viewModel.uiState.value

        val thinking = state.thinkingMessage
        if (thinking != null) {
            visible.add(
                Message(
                    conversationId = convId,
                    role = "thinking",
                    content = thinking
                )
            )
        }

        // Bulle d'erreur si erreur active (hors CERT qui a son propre dialog)
        val errorMsg = state.errorMessage
        if (errorMsg != null && !errorMsg.startsWith("CERT:")) {
            visible.add(
                Message(
                    conversationId = convId,
                    role = "error",
                    content = errorMsg
                )
            )
        }

        messagesState = visible.toList()
    }

    // ─────────────────────────── Wake word ────────────────────────────────

    private fun observeWakeWord() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HassanWakeWordService.wakeWordDetected.collect {
                    viewModel.onWakeWordDetected()
                }
            }
        }
    }

    // ─────────────────────────── Notifications push ───────────────────────

    private fun observeIncomingMessages() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HassanNotificationService.incomingMessage.collect { content ->
                    // Message poussé par Hermes pendant que l'app est au premier plan :
                    // l'affiche directement dans la conversation active
                    viewModel.handlePushedMessage(content)
                }
            }
        }
    }

    // ─────────────────────────── Pipeline vocal ────────────────────────────

    private fun renderVoiceState(voiceState: VoiceState) {
        val prev = lastVoiceState
        lastVoiceState = voiceState

        var statusText = ""
        var waveActive = false
        var showStopTts = false

        when (voiceState) {
            is VoiceState.Idle -> {
                statusText = getString(R.string.voice_state_idle)
            }
            is VoiceState.WakeWordListening -> {
                statusText = getString(R.string.status_wake_word)
            }
            is VoiceState.WakeWordDetected -> {
                statusText = getString(R.string.status_listening)
                triggerRingLight()
                if (prev !is VoiceState.WakeWordDetected) vibrateWakeWord()
            }
            is VoiceState.SttListening -> {
                statusText = getString(R.string.voice_state_stt_listening)
                waveActive = true
            }
            is VoiceState.SttProcessing -> {
                statusText = getString(R.string.status_transcribing)
            }
            is VoiceState.HermesThinking -> {
                statusText = getString(R.string.status_thinking)
            }
            is VoiceState.HermesStreaming -> {
                statusText = voiceState.toolMessage ?: getString(R.string.status_generating)
            }
            is VoiceState.TtsSpeaking -> {
                statusText = getString(R.string.status_speaking)
                showStopTts = true
                if (prev !is VoiceState.TtsSpeaking) vibrateTtsStart()
            }
            is VoiceState.Error -> {
                statusText = voiceState.message
                if (prev !is VoiceState.Error) vibrateError()
            }
        }

        voiceUiState = voiceUiState.copy(
            statusText = statusText,
            isWaveActive = waveActive,
            showStopTts = showStopTts
        )
    }

    private fun triggerRingLight() {
        ringLightTick++
        voiceUiState = voiceUiState.copy(ringLightTick = ringLightTick)
    }

    // ─────────────────────────── Vibration ────────────────────────────────

    private fun vibrateWakeWord() {
        vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun vibrateTtsStart() {
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 60, 30), -1))
    }

    private fun vibrateError() {
        vibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ─────────────────────────── Menu contextuel messages ─────────────────

    private fun showUserMessageMenu(message: Message) {
        HasanDialog.list(
            context = requireContext(),
            title = "",
            items = listOf(
                getString(R.string.msg_action_edit_resend),
                getString(R.string.msg_action_copy),
                getString(R.string.msg_action_delete)
            ),
            onSelect = { which ->
                when (which) {
                    0 -> {
                        switchToTextMode()
                        inputText = message.content
                    }
                    1 -> copyToClipboard(message.content)
                    2 -> viewModel.deleteMessage(message.id)
                }
            }
        )
    }

    private fun showHasanMessageMenu(message: Message) {
        HasanDialog.list(
            context = requireContext(),
            title = "",
            items = listOf(
                getString(R.string.msg_action_regenerate),
                getString(R.string.msg_action_copy)
            ),
            onSelect = { which ->
                when (which) {
                    0 -> viewModel.regenerateLastResponse()
                    1 -> copyToClipboard(message.content)
                }
            }
        )
    }

    private fun toggleMessageTts(message: Message) {
        if (viewModel.uiState.value.ttsStatus == TtsStatus.SPEAKING &&
            viewModel.uiState.value.ttsPlayingMessageId == message.id) {
            viewModel.stopTts()
        } else {
            viewModel.readAloud(message.content, message.id)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext()
            .getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
        Toast.makeText(requireContext(), "Message copié", Toast.LENGTH_SHORT).show()
    }

    // ─────────────────────────── Permissions STT ──────────────────────────

    private fun checkPermissionAndStartStt() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                requireContext(), android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            sttManager?.startListening()
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == 100 &&
            grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            sttManager?.startListening()
            viewModel.sendWakeWordIntent(HassanWakeWordService.ACTION_RESUME)
        } else {
            viewModel.onSttError(-1, "Permission microphone refusée")
        }
    }

    // ─────────────────────────── SttListener ──────────────────────────────

    override fun onReadyForSpeech()                  = requireActivity().runOnUiThread { viewModel.onSttReadyForSpeech() }
    override fun onTranscriptPartial(text: String)   = requireActivity().runOnUiThread { viewModel.onSttPartialResult(text) }
    override fun onTranscriptFinal(text: String)     = requireActivity().runOnUiThread { viewModel.onSttFinalResult(text.replaceFirstChar { it.uppercase() }) }
    override fun onError(code: Int, message: String) = requireActivity().runOnUiThread { viewModel.onSttError(code, message) }
    override fun onEndOfSpeech()                     = requireActivity().runOnUiThread { viewModel.onSttEndOfSpeech() }

    override fun onDestroyView() {
        sttManager?.destroy()
        _binding = null
        super.onDestroyView()
    }
}
