package com.hasan.v1

import android.animation.ObjectAnimator
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.hasan.v1.databinding.FragmentConversationBinding
import com.hasan.v1.utils.HasanDialog
import com.hasan.v1.db.HassanDatabase
import com.hasan.v1.db.Message
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Fragment Chat principal — affiche le header Hasan, les bulles de messages
 * et la zone de saisie (mode vocal ou texte).
 *
 * Ne contient aucune logique métier — délègue tout au MainViewModel.
 */
class ConversationFragment : Fragment(), SpeechRecognizerManager.SttListener {

    private var _binding: FragmentConversationBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private var sttManager: SpeechRecognizerManager? = null
    private lateinit var messageAdapter: MessageAdapter
    private var certDialogShown = false

    private val waveAnimators = mutableListOf<ObjectAnimator>()
    private var ringLightAnimator: ObjectAnimator? = null
    private var lastVoiceState: VoiceState? = null
    private val vibrator by lazy {
        requireContext().getSystemService(Vibrator::class.java)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConversationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sttManager = SpeechRecognizerManager(requireContext(), this)

        setupRecyclerView()
        setupClickListeners()
        observeUiState()
        observeMessages()
        observeWakeWord()
        observeIncomingMessages()
    }

    // ─────────────────────────── RecyclerView ─────────────────────────────

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(
            onUserLongPress  = { msg -> showUserMessageMenu(msg) },
            onHasanLongPress = { msg -> showHasanMessageMenu(msg) },
            onToggleTts      = { msg -> toggleMessageTts(msg) },
            onCopy           = { msg -> copyToClipboard(msg.content) },
            onRetry          = { viewModel.retryLastMessage() }
        )
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = messageAdapter
        }
    }

    // ─────────────────────────── Click listeners ──────────────────────────

    private fun setupClickListeners() {
        // Mode vocal → texte
        binding.btnSwitchToText.setOnClickListener { switchToTextMode() }

        // Mode texte → vocal (bouton micro dans la zone texte)
        binding.btnSwitchToVoice.setOnClickListener {
            switchToVoiceMode()
            viewModel.toggleListening()
        }

        // Envoi de message texte
        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) {
                viewModel.sendTextMessage(text)
                binding.etMessage.setText("")
            }
        }

        // Stop TTS immédiat — bouton visible uniquement pendant la lecture vocale
        binding.btnStopTts.setOnClickListener {
            viewModel.stopTts()
        }

        // Tap long sur le logo Hasan → bascule en Light Mode
        binding.ivAvatar.setOnLongClickListener {
            (activity as? MainActivity)?.enterLightMode()
            true
        }
    }

    // ─────────────────────────── Modes vocal / texte ──────────────────────

    private fun switchToTextMode() {
        binding.voiceModeLayout.visibility = View.GONE
        binding.textModeLayout.visibility  = View.VISIBLE
        stopWaveAnimation()
    }

    private fun switchToVoiceMode() {
        binding.voiceModeLayout.visibility = View.VISIBLE
        binding.textModeLayout.visibility  = View.GONE
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
        // Indicateur de connexion dans le header
        updateVpsIndicator(state.connectionStatus)
        updateMcpIndicator(state.mcpConnected)

        // Mode dégradé — désactive la saisie quand Hermes est inaccessible
        val degraded = !state.serverConnected && state.connectionStatus == ConnectionStatus.DISCONNECTED
        binding.etMessage.isEnabled = !degraded
        binding.btnSend.isEnabled = !degraded
        if (degraded) {
            binding.etMessage.hint = getString(R.string.error_hermes_readonly)
        } else {
            binding.etMessage.hint = getString(R.string.hint_message)
        }

        // Certificat TOFU — dialog d'approbation si pas déjà affiché
        if (state.errorMessage?.startsWith("CERT:") == true && !certDialogShown) {
            certDialogShown = true
            // Format : "CERT:isChanged:fingerprint:storedFingerprint"
            val parts = state.errorMessage.removePrefix("CERT:").split(":", limit = 3)
            val isChanged = parts.getOrNull(0) == "true"
            val fingerprint = parts.getOrNull(1) ?: ""
            val storedFingerprint = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
            val rootUrl = HermesApiClient.buildRootUrl(viewModel.settings.serverUrl)
            val formatted = fingerprint.chunked(24).joinToString("\n")

            if (isChanged && storedFingerprint != null) {
                val storedFmt = storedFingerprint.chunked(24).joinToString("\n")
                HasanDialog.confirm(
                    context = requireContext(),
                    title = "⚠ Certificat modifié",
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

        // Pipeline vocal — statut + animations + vibration via VoiceState
        val voiceState = state.voiceState()
        renderVoiceState(voiceState)

        // Synchronise l'icône play/pause du message en cours de lecture
        messageAdapter.ttsPlayingMessageId = state.ttsPlayingMessageId

        // Lance le STT au bon moment (arrête d'abord le précédent si actif)
        if (state.sttStatus == SttStatus.STARTING) {
            sttManager?.stopListening()
            checkPermissionAndStartStt()
        }

        // Arrête le STT si inactif → repasse en mode texte
        if (!state.isListening && state.sttStatus == SttStatus.IDLE) {
            sttManager?.stopListening()
            if (binding.voiceModeLayout.visibility == View.VISIBLE) {
                switchToTextMode()
            }
        }
    }

    private fun updateVpsIndicator(status: ConnectionStatus) {
        val (dotColor, statusText) = when (status) {
            ConnectionStatus.CONNECTED    -> R.color.hasan_success to R.string.header_vps_connected
            ConnectionStatus.RECONNECTING -> R.color.hasan_warning to R.string.header_vps_reconnecting
            ConnectionStatus.DISCONNECTED -> R.color.hasan_error   to R.string.header_vps_disconnected
        }
        binding.viewVpsDot.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), dotColor)
            )
        binding.tvVpsStatus.text = getString(statusText)

        if (status == ConnectionStatus.RECONNECTING) {
            if (binding.viewVpsDot.animation == null) {
                android.view.animation.AlphaAnimation(1f, 0.3f).apply {
                    duration = 600
                    repeatMode = android.view.animation.Animation.REVERSE
                    repeatCount = android.view.animation.Animation.INFINITE
                }.also { binding.viewVpsDot.startAnimation(it) }
            }
        } else {
            binding.viewVpsDot.clearAnimation()
        }
    }

    private fun updateMcpIndicator(connected: Boolean) {
        val dotColor = if (connected) R.color.hasan_success else R.color.hasan_error
        val statusText = if (connected) R.string.header_mcp_connected else R.string.header_mcp_disconnected
        binding.viewMcpDot.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), dotColor)
            )
        binding.tvMcpStatus.text = getString(statusText)
    }

    // ─────────────────────────── Messages DB ──────────────────────────────

    private fun observeMessages() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    val convId = state.resumedConversationId
                    if (convId == null) {
                        messageAdapter.submitList(emptyList())
                        return@collectLatest
                    }
                    val db = HassanDatabase.getInstance(requireContext())
                    db.messageDao().getMessagesForConversation(convId).collect { msgs ->
                        renderMessages(msgs, convId)
                    }
                }
            }
        }
        // Re-render when thinkingMessage changes (outil Hermes en cours / terminé)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val convId = state.resumedConversationId ?: return@collect
                    val db = HassanDatabase.getInstance(requireContext())
                    val msgs = db.messageDao().getMessagesForConversationOnce(convId)
                    renderMessages(msgs, convId)
                }
            }
        }
    }

    private fun renderMessages(msgs: List<com.hasan.v1.db.Message>, convId: Long) {
        val visible = msgs.filter { !it.isStreaming || it.content.isNotBlank() }.toMutableList()
        val state = viewModel.uiState.value

        val thinking = state.thinkingMessage
        if (thinking != null) {
            visible.add(
                com.hasan.v1.db.Message(
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
                com.hasan.v1.db.Message(
                    conversationId = convId,
                    role = "error",
                    content = errorMsg
                )
            )
        }

        val rv = binding.rvMessages
        val lm = rv.layoutManager as? LinearLayoutManager
        // On est "en bas" si le dernier item visible est le dernier de la liste
        val isAtBottom = lm != null &&
            lm.findLastVisibleItemPosition() >= (messageAdapter.itemCount - 2)

        messageAdapter.submitList(visible.toList()) {
            if (visible.isNotEmpty() && isAtBottom) {
                // Scroll instantané pendant streaming, smooth sinon
                val streaming = viewModel.uiState.value.sttStatus == SttStatus.STREAMING
                if (streaming) rv.scrollToPosition(visible.size - 1)
                else rv.smoothScrollToPosition(visible.size - 1)
            }
        }
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

        // Texte de statut + animation onde + bouton stop
        when (voiceState) {
            is VoiceState.Idle -> {
                binding.tvVoiceStatus.text = getString(R.string.voice_state_idle)
                stopWaveAnimation()
                binding.btnStopTts.visibility = View.GONE
            }
            is VoiceState.WakeWordListening -> {
                binding.tvVoiceStatus.text = getString(R.string.status_wake_word)
                stopWaveAnimation()
                binding.btnStopTts.visibility = View.GONE
            }
            is VoiceState.WakeWordDetected -> {
                binding.tvVoiceStatus.text = getString(R.string.status_listening)
                startRingLightAnimation()
                if (prev !is VoiceState.WakeWordDetected) vibrateWakeWord()
                binding.btnStopTts.visibility = View.GONE
            }
            is VoiceState.SttListening -> {
                binding.tvVoiceStatus.text = getString(R.string.voice_state_stt_listening)
                startWaveAnimation()
                binding.btnStopTts.visibility = View.GONE
            }
            is VoiceState.SttProcessing -> {
                binding.tvVoiceStatus.text = getString(R.string.status_transcribing)
                stopWaveAnimation()
                binding.btnStopTts.visibility = View.GONE
            }
            is VoiceState.HermesThinking -> {
                binding.tvVoiceStatus.text = getString(R.string.status_thinking)
                stopWaveAnimation()
                binding.btnStopTts.visibility = View.GONE
            }
            is VoiceState.HermesStreaming -> {
                binding.tvVoiceStatus.text = voiceState.toolMessage
                    ?: getString(R.string.status_generating)
                stopWaveAnimation()
                binding.btnStopTts.visibility = View.GONE
            }
            is VoiceState.TtsSpeaking -> {
                binding.tvVoiceStatus.text = getString(R.string.status_speaking)
                stopWaveAnimation()
                binding.btnStopTts.visibility = View.VISIBLE
                if (prev !is VoiceState.TtsSpeaking) vibrateTtsStart()
            }
            is VoiceState.Error -> {
                binding.tvVoiceStatus.text = "⚠️ ${voiceState.message}"
                stopWaveAnimation()
                binding.btnStopTts.visibility = View.GONE
                if (prev !is VoiceState.Error) vibrateError()
            }
        }
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

    // ─────────────────────────── Animations ───────────────────────────────

    private fun startWaveAnimation() {
        if (waveAnimators.isNotEmpty()) return
        val bars = listOf(
            binding.waveBar1, binding.waveBar2, binding.waveBar3,
            binding.waveBar4, binding.waveBar5
        )
        bars.forEachIndexed { index, bar ->
            ObjectAnimator.ofFloat(bar, "scaleY", 0.15f, 1.0f).apply {
                duration    = 380L
                repeatMode  = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                startDelay  = (index * 75).toLong()
                start()
            }.also { waveAnimators.add(it) }
        }
    }

    private fun stopWaveAnimation() {
        waveAnimators.forEach { it.cancel() }
        waveAnimators.clear()
        listOf(binding.waveBar1, binding.waveBar2, binding.waveBar3,
               binding.waveBar4, binding.waveBar5).forEach { it.scaleY = 0.15f }
    }

    private fun startRingLightAnimation() {
        ringLightAnimator?.cancel()
        ringLightAnimator = ObjectAnimator.ofFloat(
            binding.wakeWordRingLight, "alpha", 0f, 0.12f, 0f
        ).apply {
            duration    = 500L
            repeatCount = 0
            start()
        }
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
                        binding.etMessage.setText(message.content)
                        binding.etMessage.setSelection(message.content.length)
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
        stopWaveAnimation()
        ringLightAnimator?.cancel()
        sttManager?.destroy()
        _binding = null
        super.onDestroyView()
    }
}
