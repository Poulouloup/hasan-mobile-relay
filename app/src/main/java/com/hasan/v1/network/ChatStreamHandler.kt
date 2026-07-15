package com.hasan.v1.network

import android.util.Log
import com.hasan.v1.ErrorType
import com.hasan.v1.StreamEvent
import com.hasan.v1.network.models.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Fait transiter le chat texte par le canal `chat` du WebSocket relay
 * (voir server/relay/chat_stream.py), en remplacement du HTTP/SSE direct de
 * [com.hasan.v1.HermesApiClient.streamChat] quand [com.hasan.v1.SettingsManager.useWebsocketTransport]
 * est actif.
 *
 * Contrairement à [BridgeCommandHandler] (requête/réponse synchrone déclenchée
 * par le serveur), le chat est initié par ce client et produit un flux de N
 * enveloppes pour un même tour — [streamChat] doit donc rester exploitable
 * exactement comme `HermesApiClient.streamChat()` pour que le `when(event)`
 * existant dans MainViewModel.sendToHermes() reste inchangé quel que soit
 * le transport.
 *
 * Corrélation par `session_id` (pas de request_id par message) : un seul tour
 * peut être actif à la fois par session, déjà garanti côté appelant par
 * `streamJob?.cancel()` en tête de sendToHermes() — voir chat_stream.py côté
 * serveur pour la même hypothèse tenue là-bas.
 */
class ChatStreamHandler(
    private val connectionManager: ConnectionManager,
    private val multiplexer: ChannelMultiplexer
) {

    companion object {
        private const val TAG = "ChatStreamHandler"

        // Volontairement généreux, pas un timeout sur la durée du tour entier
        // (un tool call peut légitimement prendre plusieurs minutes) — watchdog
        // sur l'inactivité totale seulement, légèrement au-dessus du watchdog
        // serveur (~300s, voir chat_stream.py HERMES_WATCHDOG_TIMEOUT_SECONDS)
        // pour laisser la priorité à l'enveloppe chat/error du serveur si elle
        // arrive en premier.
        private const val WATCHDOG_TIMEOUT_MS = 320_000L
    }

    fun streamChat(sessionId: String, userText: String): Flow<StreamEvent> = callbackFlow {
        val scope = CoroutineScope(coroutineContext)
        var terminal = false
        var watchdogJob: Job? = null

        fun armWatchdog() {
            watchdogJob?.cancel()
            watchdogJob = scope.launch {
                kotlinx.coroutines.delay(WATCHDOG_TIMEOUT_MS)
                terminal = true
                trySend(StreamEvent.Error("Aucune réponse du relay (timeout)", ErrorType.TIMEOUT))
                close()
            }
        }

        // Démarre la collecte AVANT tout envoi : ChannelMultiplexer.chat utilise
        // tryEmit sans replay (voir ChannelMultiplexer.kt), une enveloppe de
        // réponse arrivée avant que ce filtre soit actif serait perdue en silence.
        multiplexer.chat
            .filter { it.payload.optString("session_id") == sessionId }
            .onEach { envelope ->
                if (terminal) return@onEach
                armWatchdog()
                when (envelope.type) {
                    "connecting" -> trySend(StreamEvent.Connecting)
                    "connected" -> trySend(StreamEvent.Connected)
                    "thinking" -> trySend(StreamEvent.Thinking(envelope.payload.optString("message")))
                    "clarify" -> {
                        val choicesArr = envelope.payload.optJSONArray("choices")
                        val choices = if (choicesArr != null) {
                            (0 until choicesArr.length()).map { choicesArr.getString(it) }
                        } else null
                        trySend(
                            StreamEvent.Clarify(
                                clarifyId = envelope.payload.optString("clarify_id"),
                                question = envelope.payload.optString("question"),
                                choices = choices
                            )
                        )
                    }
                    "token" -> trySend(StreamEvent.Token(envelope.payload.optString("text")))
                    "done" -> {
                        terminal = true
                        val responseId = envelope.payload.optString("response_id").takeIf { it.isNotBlank() }
                        trySend(
                            StreamEvent.Done(
                                responseId = responseId,
                                inputTokens = envelope.payload.optInt("input_tokens", 0),
                                outputTokens = envelope.payload.optInt("output_tokens", 0)
                            )
                        )
                        close()
                    }
                    "error" -> {
                        terminal = true
                        val reason = envelope.payload.optString("reason").takeIf { it.isNotBlank() }
                        val httpStatus = envelope.payload.optInt("http_status", -1).takeIf { it >= 0 }
                        val message = envelope.payload.optString("message")
                        if (reason != "cancelled") {
                            trySend(StreamEvent.Error(message.ifBlank { "Erreur chat" }, classifyChatError(httpStatus, reason, message)))
                        }
                        close()
                    }
                    else -> Log.d(TAG, "Enveloppe chat non gérée: type=${envelope.type}")
                }
            }
            .launchIn(scope)

        // Coupure WS pendant un chat actif : aucune enveloppe chat/error ne
        // viendra du serveur si le socket lui-même est mort — synthétise
        // localement l'équivalent de StreamEvent.Error(STREAM_INTERRUPTED).
        connectionManager.connectionStatus
            .onEach { status ->
                if (terminal) return@onEach
                if (status != RelayConnectionStatus.CONNECTED) {
                    terminal = true
                    trySend(StreamEvent.Error("Connexion relay interrompue", ErrorType.STREAM_INTERRUPTED))
                    close()
                }
            }
            .launchIn(scope)

        trySend(StreamEvent.Connecting)
        armWatchdog()

        val envelope = Envelope(
            channel = "chat",
            type = "send",
            payload = JSONObject().apply {
                put("session_id", sessionId)
                put("text", userText)
            }
        )
        if (!connectionManager.send(envelope)) {
            terminal = true
            trySend(StreamEvent.Error("Relay non connecté", ErrorType.HERMES_UNREACHABLE))
            close()
        }

        awaitClose {
            watchdogJob?.cancel()
            // Si ce flow est fermé par annulation externe (streamJob?.cancel()
            // dans sendToHermes) avant Done/Error, prévenir le serveur best-effort
            // pour qu'il arrête d'appeler Hermes pour rien — échec d'envoi ignoré
            // silencieusement (WS déjà mort, "peut être parti", cf. chat_stream.py).
            if (!terminal) {
                connectionManager.send(
                    Envelope(
                        channel = "chat",
                        type = "cancel",
                        payload = JSONObject().apply { put("session_id", sessionId) }
                    )
                )
            }
        }
    }
}

/**
 * Reclasse une enveloppe `chat/error` brute (voir chat_stream.py, qui ne
 * classifie volontairement pas côté serveur) vers [ErrorType], en réutilisant
 * l'arbre de décision déjà écrit pour le chemin HTTP dans
 * [com.hasan.v1.HermesApiClient.streamChat] (lignes ~118-122) — une seule
 * vérité de classement, pour que le comportement UI (message, retry) soit
 * identique quel que soit le transport.
 */
internal fun classifyChatError(httpStatus: Int?, reason: String?, message: String): ErrorType = when {
    reason == "connection_refused" -> ErrorType.HERMES_UNREACHABLE
    reason == "timeout" -> ErrorType.TIMEOUT
    httpStatus == 400 && (message.contains("tool_calls", ignoreCase = true) || message.contains("invalid", ignoreCase = true)) ->
        ErrorType.INVALID_CONTEXT
    httpStatus == 401 || httpStatus == 403 -> ErrorType.AUTH_FAILED
    httpStatus != null && httpStatus in 500..599 -> ErrorType.SERVER_ERROR
    else -> ErrorType.STREAM_INTERRUPTED
}
