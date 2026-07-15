package com.hasan.v1

import android.content.Context
import android.util.Log
import com.hasan.v1.db.Conversation
import com.hasan.v1.db.HassanDatabase
import com.hasan.v1.db.Message
import com.hasan.v1.network.ChatStreamHandler
import com.hasan.v1.network.models.StreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Pipeline complet wake word → STT → Hermes → TTS, exécuté entièrement
 * depuis [HassanWakeWordService] quand l'app n'est pas au premier plan
 * (sinon c'est ConversationFragment/MainViewModel qui gère l'interaction).
 *
 * Doit être piloté depuis le thread principal (SpeechRecognizer + TextToSpeech
 * l'exigent) — [scope] doit donc utiliser Dispatchers.Main.
 *
 * [chatStreamHandler] est construit et possédé par [HassanWakeWordService]
 * (connexion WS dédiée à ce service, indépendante de celle de MainViewModel)
 * — ce pipeline ne fait que le consommer.
 */
class WakeWordPipeline(
    private val context: Context,
    private val scope: CoroutineScope,
    private val chatStreamHandler: ChatStreamHandler,
    private val onIdle: () -> Unit
) : SpeechRecognizerManager.SttListener {

    companion object {
        private const val TAG = "WakeWordPipeline"

        private val SENTENCE_SEPARATORS = listOf(". ", "! ", "? ", ".\n", "!\n", "?\n")
        private const val MAX_TOKENS_BEFORE_SPEAK = 25
    }

    private val settings = SettingsManager(context)
    private val db = HassanDatabase.getInstance(context)
    private val conversationDao = db.conversationDao()
    private val messageDao = db.messageDao()
    private val sessionDao = db.sessionDao()

    private val sttManager = SpeechRecognizerManager(context, this)

    private val ttsManager = HassanTtsManager(context).apply {
        onAllSpeakingDone = { onIdle() }
    }

    private val ttsBuffer = StringBuilder()
    private var tokenCount = 0
    private var streamJob: Job? = null

    /** Démarre le cycle : son de réveil puis écoute STT. */
    fun start() {
        HassanSoundPlayer.init(context)
        HassanSoundPlayer.playWake()
        sttManager.startListening()
    }

    /** Annule tout traitement en cours et coupe la synthèse vocale. */
    fun cancel() {
        streamJob?.cancel()
        sttManager.stopListening()
        ttsManager.stop()
    }

    fun release() {
        streamJob?.cancel()
        sttManager.destroy()
        ttsManager.release()
    }

    // ─────────────────────────── SttListener ──────────────────────────────

    override fun onReadyForSpeech() {}

    override fun onTranscriptPartial(text: String) {}

    override fun onEndOfSpeech() {
        HassanSoundPlayer.playEnd()
    }

    override fun onTranscriptFinal(text: String) {
        val userText = text.replaceFirstChar { it.uppercase() }
        sendToHermes(userText)
    }

    override fun onError(code: Int, message: String) {
        Log.w(TAG, "STT error: $message")
        onIdle()
    }

    // ─────────────────────────── Hermes ────────────────────────────────────

    private fun sendToHermes(userText: String) {
        streamJob?.cancel()
        streamJob = scope.launch {
            val activeSessionId = settings.activeSessionId
            if (activeSessionId == null) {
                Log.w(TAG, "Aucune session active — message ignoré")
                onIdle()
                return@launch
            }

            val convId = getOrCreateConversation(activeSessionId)
            HassanWakeWordService.notifyConversationUpdated(convId)
            messageDao.insert(
                Message(conversationId = convId, role = "user", content = userText)
            )

            val streamingBuffer = StringBuilder()
            var streamingMessageId = messageDao.insert(
                Message(conversationId = convId, role = "assistant", content = "", isStreaming = true)
            )

            ttsBuffer.clear()
            tokenCount = 0

            // reachedTerminal : filet de sécurité contre un flow qui se termine (fin
            // normale ou exception) sans jamais émettre Done/Error — auparavant, un tel
            // silence désactivait durablement le wake word (enginePaused ne repasse à
            // false que via onIdle(), voir HassanWakeWordService.onIdle()), sans aucune
            // UI en arrière-plan pour le signaler. On force onIdle() dans le finally si
            // aucune branche terminale n'a été atteinte.
            var reachedTerminal = false
            try {
                chatStreamHandler.streamChat(activeSessionId, userText).collect { event ->
                    when (event) {
                        is StreamEvent.Token -> {
                            streamingBuffer.append(event.text)
                            if (settings.ttsEnabled) processTokenForTts(event.text)
                        }

                        is StreamEvent.Done -> {
                            reachedTerminal = true
                            if (settings.ttsEnabled) flushTtsBuffer()
                            val responseText = streamingBuffer.toString()
                            if (streamingMessageId >= 0) {
                                messageDao.update(
                                    Message(
                                        id = streamingMessageId,
                                        conversationId = convId,
                                        role = "assistant",
                                        content = responseText,
                                        isStreaming = false
                                    )
                                )
                            }
                            conversationDao.getById(convId)?.let { conv ->
                                conversationDao.update(conv.copy(updatedAt = System.currentTimeMillis()))
                            }
                            sessionDao.touchSession(activeSessionId)
                            if (responseText.isNotBlank()) {
                                HassanNotificationService.notifyMessage(context, responseText)
                            }
                            // Si TTS désactivé, rien ne déclenchera onAllSpeakingDone
                            if (!settings.ttsEnabled) onIdle()
                        }

                        is StreamEvent.Error -> {
                            reachedTerminal = true
                            Log.w(TAG, "Hermes error: ${event.message}")
                            onIdle()
                        }

                        is StreamEvent.CertificateCheck -> {
                            // Filet défensif inatteignable en pratique : ChatStreamHandler
                            // (WS) ne produit jamais ce type — hérité du chemin HTTP
                            // historique, gardé pour un `when` exhaustif à coût nul.
                            // Pas de dialog possible ici (service en arrière-plan, pas
                            // d'UI) — un cert relay non approuvé sur cette connexion
                            // dédiée est de toute façon silencieusement accepté par le
                            // TLS handshake (TOFU signale l'événement, mais rien ne le
                            // collecte côté service), cohérent avec le modèle TOFU
                            // "accepter silencieusement, alerter seulement au
                            // changement" déjà en place partout ailleurs dans le projet.
                            reachedTerminal = true
                            Log.w(TAG, "Certificat non approuvé — ouvrez l'app pour valider la connexion")
                            onIdle()
                        }

                        else -> Unit
                    }
                }
            } finally {
                if (!reachedTerminal) {
                    Log.w(TAG, "streamChat terminé sans Done/Error/CertificateCheck — wake word réarmé par filet de sécurité")
                    onIdle()
                }
            }
        }
    }

    /** Récupère la conversation liée à la session active, ou en crée une nouvelle. */
    private suspend fun getOrCreateConversation(sessionId: String): Long {
        val existing = conversationDao.getBySessionId(sessionId)
        if (existing != null) return existing.id
        return conversationDao.insert(Conversation(sessionId = sessionId, type = "voice"))
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
}
