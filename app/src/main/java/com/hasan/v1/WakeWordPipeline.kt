package com.hasan.v1

import android.content.Context
import android.util.Log
import com.hasan.v1.db.Conversation
import com.hasan.v1.db.HassanDatabase
import com.hasan.v1.db.Message
import com.hasan.v1.utils.LatencyLog
import com.hasan.v1.webui.WebUiChatStream
import com.hasan.v1.webui.WebUiRestClient
import com.hasan.v1.webui.models.WebUiStreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pipeline complet wake word → STT → Hermes → TTS, exécuté entièrement
 * depuis [HassanWakeWordService] quand l'app n'est pas au premier plan
 * (sinon c'est ConversationFragment/MainViewModel qui gère l'interaction).
 *
 * Doit être piloté depuis le thread principal (SpeechRecognizer + TextToSpeech
 * l'exigent) — [scope] doit donc utiliser Dispatchers.Main.
 *
 * [webUiRestClient] est l'instance partagée (voir
 * [com.hasan.v1.webui.WebUiClientHolder]) avec MainViewModel — un client
 * HTTP + cookie n'a pas besoin d'isolation par composant contrairement à
 * l'ancienne connexion WS dédiée du bridge.
 *
 * Ne gère pas les prompts de clarification (silencieusement ignorés, comme
 * avant la migration) — pas d'UI possible en arrière-plan de toute façon ;
 * le serveur applique son propre timeout + fallback "meilleur jugement".
 */
class WakeWordPipeline(
    private val context: Context,
    private val scope: CoroutineScope,
    private val webUiRestClient: WebUiRestClient,
    private val onIdle: () -> Unit
) : SpeechRecognizerManager.SttListener {

    private val webUiChatStream = WebUiChatStream(webUiRestClient)

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

            LatencyLog.mark("SEND", activeSessionId, "user=${userText.take(80)}")
            val streamId = withContext(Dispatchers.IO) {
                webUiRestClient.startChat(activeSessionId, userText)
            }
            if (streamId == null) {
                Log.w(TAG, "startChat a échoué — message ignoré")
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
                webUiChatStream.stream(streamId, activeSessionId).collect { event ->
                    when (event) {
                        is WebUiStreamEvent.Token -> {
                            streamingBuffer.append(event.text)
                            if (settings.ttsEnabled) processTokenForTts(event.text)
                        }

                        is WebUiStreamEvent.Done -> {
                            reachedTerminal = true
                            if (settings.ttsEnabled) flushTtsBuffer()
                            val responseText = streamingBuffer.toString()
                            if (looksLikeDisguisedLlmError(responseText)) {
                                LatencyLog.mark(
                                    "SUSPECT_ERROR_AS_RESPONSE", activeSessionId,
                                    "content=${responseText.take(300)}"
                                )
                            } else {
                                LatencyLog.mark("DONE", activeSessionId, responseText.take(200))
                            }
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

                        is WebUiStreamEvent.Error -> {
                            reachedTerminal = true
                            Log.w(TAG, "Hermes error: ${event.message}")
                            onIdle()
                        }

                        // Tool/Approval/clarify (flux séparé côté hermes-webui) ignorés en
                        // arrière-plan — pas d'UI possible, comportement inchangé.
                        else -> Unit
                    }
                }
            } finally {
                if (!reachedTerminal) {
                    Log.w(TAG, "chat stream terminé sans Done/Error — wake word réarmé par filet de sécurité")
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
