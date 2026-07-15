package com.hasan.v1.network

import android.util.Log
import com.hasan.v1.network.models.ErrorType
import com.hasan.v1.network.models.Envelope
import com.hasan.v1.network.models.HealthResult
import com.hasan.v1.network.models.StreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Fait transiter tout ce qui parle à Hermes par le canal `chat` du WebSocket
 * relay (voir server/relay/chat_stream.py + server.py) — seul transport
 * depuis le retrait du fallback HTTP/SSE (ancien HermesApiClient.kt).
 *
 * Contrairement à [BridgeCommandHandler] (requête/réponse synchrone déclenchée
 * par le serveur), [streamChat] est initié par ce client et produit un flux de
 * N enveloppes pour un même tour — corrélé par `session_id` (pas de request_id
 * par message) : un seul tour peut être actif à la fois par session, déjà
 * garanti côté appelant par `streamJob?.cancel()` en tête de sendToHermes()
 * — voir chat_stream.py côté serveur pour la même hypothèse tenue là-bas.
 *
 * [checkHealth]/[respondToClarify] sont des opérations ponctuelles (une
 * requête, une réponse), corrélées par `envelope.id` via [sendAndAwait] —
 * pas de session_id, ce ne sont pas des tours de conversation.
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

        // chat/health et chat/clarify_response sont des opérations ponctuelles,
        // pas de rapport avec le watchdog généreux du streaming — même valeur
        // que le timeout serveur côté chat_stream.py (CHAT_RPC_TIMEOUT_SECONDS).
        private const val RPC_TIMEOUT_MS = 10_000L
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

    /**
     * Ping applicatif vers Hermes (chat/health) — distinct de system/ping qui
     * ne vérifie que la vivacité du relay lui-même. Requête/réponse ponctuelle,
     * corrélée par envelope.id via [sendAndAwait].
     */
    suspend fun checkHealth(): HealthResult {
        val envelope = Envelope(channel = "chat", type = "health", payload = JSONObject())
        return sendAndAwait(envelope, matchType = "health_result", timeoutMs = RPC_TIMEOUT_MS) { payload ->
            when {
                payload.optBoolean("ok", false) -> HealthResult.Ok
                payload.has("http_status") -> HealthResult.ServerError(payload.optInt("http_status"))
                else -> HealthResult.NetworkError(payload.optString("message").ifBlank { "Erreur inconnue" })
            }
        } ?: HealthResult.NetworkError("Pas de réponse du relay")
    }

    /** Répond à une clarification en attente (chat/clarify_response), pendant que
     * le tour chat/send original reste ouvert en parallèle. */
    suspend fun respondToClarify(sessionId: String, clarifyId: String, response: String): Boolean {
        val envelope = Envelope(
            channel = "chat",
            type = "clarify_response",
            payload = JSONObject().apply {
                put("session_id", sessionId)
                put("clarify_id", clarifyId)
                put("response", response)
            }
        )
        return sendAndAwait(envelope, matchType = "clarify_response_result", timeoutMs = RPC_TIMEOUT_MS) { payload ->
            payload.optBoolean("ok", false)
        } ?: false
    }

    /**
     * Envoie une enveloppe corrélée par `id`, attend la réponse portant le
     * même `id` avec le type attendu sur le canal chat, sous timeout. Retourne
     * null si non connecté ou en cas de timeout — l'appelant décide du
     * comportement par défaut dans ce cas (voir call-sites).
     */
    private suspend fun <T> sendAndAwait(
        envelope: Envelope,
        matchType: String,
        timeoutMs: Long,
        mapper: (JSONObject) -> T
    ): T? {
        if (!connectionManager.send(envelope)) return null
        val response = withTimeoutOrNull(timeoutMs) {
            multiplexer.chat.filter { it.type == matchType && it.id == envelope.id }.first()
        } ?: return null
        return mapper(response.payload)
    }
}

/**
 * Reclasse une enveloppe `chat/error` brute (voir chat_stream.py, qui ne
 * classifie volontairement pas côté serveur) vers [ErrorType] — arbre de
 * décision hérité du chemin HTTP historique (400+tool_calls -> contexte
 * invalide, 401/403 -> auth, 5xx -> erreur serveur), conservé ici comme
 * unique point de classement pour un comportement UI stable.
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
