package com.hasan.v1.network

import android.util.Log
import com.hasan.v1.network.models.ErrorType
import com.hasan.v1.network.models.Envelope
import com.hasan.v1.network.models.HealthResult
import com.hasan.v1.network.models.StreamEvent
import com.hasan.v1.utils.LatencyLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.drop
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
 * [checkHealth] est une opération ponctuelle (une requête, une réponse),
 * corrélée par `envelope.id` via [sendAndAwait] — pas de session_id, ce
 * n'est pas un tour de conversation.
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

        // chat/health est une opération ponctuelle, pas de rapport avec le
        // watchdog généreux du streaming — même valeur que le timeout serveur
        // côté chat_stream.py (CHAT_RPC_TIMEOUT_SECONDS).
        private const val RPC_TIMEOUT_MS = 10_000L
    }

    fun streamChat(sessionId: String, userText: String): Flow<StreamEvent> = callbackFlow {
        val scope = CoroutineScope(coroutineContext)
        var terminal = false
        var watchdogJob: Job? = null

        // Tout trySend() raté (canal déjà fermé, buffer plein) doit être visible dans les
        // logs — un StreamEvent qui n'atteint jamais MainViewModel est le mécanisme exact
        // du bug du tour bloqué silencieusement (voir investigation LatencyLog turn=1784139600101).
        fun sendOrLog(event: StreamEvent) {
            val result = trySend(event)
            if (result.isFailure) {
                LatencyLog.mark("TRYSEND_FAILED", sessionId, "event=${event::class.simpleName} closed=${result.isClosed}")
            }
        }

        fun armWatchdog() {
            watchdogJob?.cancel()
            watchdogJob = scope.launch {
                kotlinx.coroutines.delay(WATCHDOG_TIMEOUT_MS)
                terminal = true
                sendOrLog(StreamEvent.Error("Aucune réponse du relay (timeout)", ErrorType.TIMEOUT))
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
                    "connecting" -> sendOrLog(StreamEvent.Connecting)
                    "connected" -> sendOrLog(StreamEvent.Connected)
                    "thinking" -> sendOrLog(StreamEvent.Thinking(envelope.payload.optString("message")))
                    "token" -> {
                        val text = envelope.payload.optString("text")
                        LatencyLog.mark("TOKEN_PARSED", sessionId, "len=${text.length}")
                        sendOrLog(StreamEvent.Token(text))
                    }
                    "clarify" -> {
                        // Le tour reste ouvert (pas de terminal=true) : le serveur attend
                        // la réponse de l'utilisateur via POST .../clarify-response, avec
                        // un keep-alive SSE 240s qui réarme déjà ce watchdog client via
                        // armWatchdog() ci-dessus à chaque enveloppe reçue.
                        val clarifyId = envelope.payload.optString("clarify_id")
                        val question = envelope.payload.optString("question")
                        val choices = envelope.payload.optJSONArray("choices")?.let { arr ->
                            (0 until arr.length()).map { arr.optString(it) }
                        }
                        LatencyLog.mark("CLARIFY_PROMPT", sessionId, "clarifyId=$clarifyId")
                        sendOrLog(StreamEvent.ClarifyPrompt(clarifyId, question, choices))
                    }
                    "done" -> {
                        terminal = true
                        val responseId = envelope.payload.optString("response_id").takeIf { it.isNotBlank() }
                        sendOrLog(
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
                            sendOrLog(StreamEvent.Error(message.ifBlank { "Erreur chat" }, classifyChatError(httpStatus, reason, message)))
                        }
                        close()
                    }
                    else -> Log.d(TAG, "Enveloppe chat non gérée: type=${envelope.type}")
                }
            }
            .launchIn(scope)

        // Coupure WS pendant un chat actif : aucune enveloppe chat/error ne viendra du
        // serveur si le socket lui-même est mort — synthétise localement l'équivalent de
        // StreamEvent.Error(STREAM_INTERRUPTED). drop(1) est essentiel : connectionStatus
        // est un StateFlow qui émet SA VALEUR COURANTE dès la souscription — sans ce drop,
        // un streamChat() démarré pendant une fenêtre RECONNECTING (ex: juste après un
        // retry) se fermait immédiatement en interne, avant même l'envoi de l'enveloppe
        // chat/send, sans qu'aucun StreamEvent n'atteigne jamais le collecteur (bug du
        // tour bloqué silencieusement, turn=1784139600101). On ne réagit donc qu'aux
        // VRAIES transitions survenant après le démarrage de ce tour.
        connectionManager.connectionStatus
            .drop(1)
            .onEach { status ->
                if (terminal) return@onEach
                if (status != RelayConnectionStatus.CONNECTED) {
                    terminal = true
                    LatencyLog.mark("STREAM_CONNECTION_LOST", sessionId, "status=$status")
                    sendOrLog(StreamEvent.Error("Connexion relay interrompue", ErrorType.STREAM_INTERRUPTED))
                    close()
                }
            }
            .launchIn(scope)

        // Le tour ne démarre que si la connexion est déjà stable — sinon, plutôt que de
        // tenter un envoi voué à l'échec (webSocket == null), on remonte tout de suite une
        // erreur explicite. Décision produit : plus aucune erreur ne doit rester muette.
        val initialStatus = connectionManager.connectionStatus.value
        if (initialStatus != RelayConnectionStatus.CONNECTED) {
            terminal = true
            LatencyLog.mark("STREAM_NOT_CONNECTED", sessionId, "status=$initialStatus")
            sendOrLog(StreamEvent.Error("Relay non connecté (${initialStatus})", ErrorType.STREAM_INTERRUPTED))
            close()
            awaitClose { watchdogJob?.cancel() }
            return@callbackFlow
        }

        sendOrLog(StreamEvent.Connecting)
        armWatchdog()

        val envelope = Envelope(
            channel = "chat",
            type = "send",
            payload = JSONObject().apply {
                put("session_id", sessionId)
                put("text", userText)
            }
        )
        val sendOk = connectionManager.send(envelope)
        LatencyLog.mark("STREAM_SEND_ENVELOPE", sessionId, "ok=$sendOk connStatus=${connectionManager.connectionStatus.value}")
        if (!sendOk) {
            terminal = true
            sendOrLog(StreamEvent.Error("Relay non connecté", ErrorType.HERMES_UNREACHABLE))
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
     * Répond à une [StreamEvent.ClarifyPrompt] en cours — le tour de chat associé (même
     * [sessionId]) reste géré par le [streamChat] déjà en collecte : soit les tokens
     * reprennent normalement (relay a reçu 200 de Hermes), soit une StreamEvent.Error
     * arrive (reason "clarify_expired" si le délai de clarification a expiré côté
     * gateway) — pas de mécanisme de réponse dédié, ce flow existant couvre les deux
     * cas (voir chat_stream.py côté serveur, chat/clarify_response → chat/error si 4xx).
     * Retourne false si l'envoi échoue immédiatement (WS non connecté).
     */
    fun sendClarifyResponse(sessionId: String, clarifyId: String, response: String): Boolean {
        val envelope = Envelope(
            channel = "chat",
            type = "clarify_response",
            payload = JSONObject().apply {
                put("session_id", sessionId)
                put("clarify_id", clarifyId)
                put("response", response)
            }
        )
        val sendOk = connectionManager.send(envelope)
        LatencyLog.mark("CLARIFY_RESPONSE_SEND", sessionId, "clarifyId=$clarifyId ok=$sendOk")
        return sendOk
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
        // Même clé de corrélation que ConnectionManager.onMessage pour les enveloppes
        // sans session_id ("health:<envelope.id>") — permet de suivre un RPC ponctuel
        // (ex: chat/health) de bout en bout dans les logs malgré l'absence de session_id.
        val turn = "${envelope.type}:${envelope.id}"
        LatencyLog.mark("RPC_SEND", turn, envelope.type)
        if (!connectionManager.send(envelope)) {
            LatencyLog.mark("RPC_SEND_FAILED", turn, "relay non connecté")
            return null
        }
        val response = withTimeoutOrNull(timeoutMs) {
            multiplexer.chat.filter { it.type == matchType && it.id == envelope.id }.first()
        } ?: run {
            LatencyLog.mark("RPC_TIMEOUT", turn)
            return null
        }
        LatencyLog.clear(turn)
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
