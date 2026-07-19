package com.hasan.v1.webui

import android.util.Log
import com.hasan.v1.utils.LatencyLog
import com.hasan.v1.webui.models.WebUiStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client SSE pour GET /api/chat/stream?stream_id=X (voir api/routes.py
 * section 4.3 "SSE Streaming Engine"). Pas de lib SSE dédiée dans le projet
 * (voir network/ChatStreamHandler.kt — seul OkHttp est disponible) : parsing
 * manuel du flux `event: <nom>\ndata: <json>\n\n`, sur le même principe que
 * le parsing d'enveloppes manuel de [com.hasan.v1.network.models.Envelope].
 *
 * Le serveur envoie un commentaire `: heartbeat\n\n` (ou `: keepalive\n\n`
 * pour /api/clarify/stream) toutes les ~30s pour maintenir la connexion à
 * travers proxies/pare-feux — ignoré ici, pas d'action requise côté client
 * (OkHttp n'a pas de timeout de lecture par défaut configuré, voir
 * [WebUiRestClient.httpClient]).
 */
class WebUiChatStream(private val restClient: WebUiRestClient) {

    companion object {
        private const val TAG = "WebUiChatStream"
    }

    /**
     * Ouvre le flux SSE pour [streamId] (obtenu via [WebUiRestClient.startChat])
     * et émet un [WebUiStreamEvent] par événement reçu. Le flux se termine
     * (complete) sur `done` ou `error`, ou se ferme avec une exception sur
     * coupure réseau — l'appelant doit traiter les deux issues (voir
     * callbackFlow + awaitClose ci-dessous, même idiome que
     * ChatStreamHandler.streamChat côté relay WSS).
     */
    fun stream(streamId: String, sessionId: String): Flow<WebUiStreamEvent> = callbackFlow {
        val request = Request.Builder()
            .url("${restClient.baseUrl()}/api/chat/stream?stream_id=$streamId")
            .apply { restClient.currentCookie()?.let { addHeader("Cookie", it) } }
            .build()

        val call = restClient.httpClient.newCall(request)

        val thread = Thread {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        LatencyLog.mark("webui_stream_http_error", sessionId, "HTTP ${response.code}")
                        trySend(WebUiStreamEvent.AppError("HTTP ${response.code}", null)).also {
                            if (it.isFailure) Log.w(TAG, "trySend échoué (HTTP error)")
                        }
                        close()
                        return@use
                    }
                    val source = response.body?.source() ?: run {
                        close()
                        return@use
                    }

                    var currentEvent: String? = null
                    val dataLines = StringBuilder()

                    fun flushEvent() {
                        if (currentEvent == null || dataLines.isEmpty()) {
                            currentEvent = null
                            dataLines.clear()
                            return
                        }
                        val data = dataLines.toString()
                        val parsed = parseEvent(currentEvent!!, data)
                        if (parsed != null) {
                            val result = trySend(parsed)
                            if (result.isFailure) {
                                LatencyLog.mark("webui_stream_trysend_failed", sessionId, currentEvent ?: "")
                                Log.w(TAG, "trySend échoué pour event=$currentEvent")
                            }
                            // Le serveur ferme sa boucle d'émission sur
                            // event in ("stream_end", "error", "cancel") —
                            // mais "error" n'est plus le nom réel (voir
                            // WebUiStreamEvent.AppError/apperror). done seul
                            // ne garantit pas la fermeture de la connexion :
                            // stream_end est le vrai signal terminal.
                            if (parsed is WebUiStreamEvent.Done ||
                                parsed is WebUiStreamEvent.AppError ||
                                parsed is WebUiStreamEvent.Cancel ||
                                parsed is WebUiStreamEvent.StreamEnd
                            ) {
                                close()
                            }
                        }
                        currentEvent = null
                        dataLines.clear()
                    }

                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.startsWith(":") -> { /* heartbeat/keepalive comment — rien à faire */ }
                            line.startsWith("event:") -> currentEvent = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> {
                                if (dataLines.isNotEmpty()) dataLines.append('\n')
                                dataLines.append(line.removePrefix("data:").trim())
                            }
                            line.isBlank() -> flushEvent()
                            // autres champs SSE (id:, retry:) — pas utilisés par ce serveur, ignorés.
                        }
                    }
                    close()
                }
            } catch (e: Exception) {
                LatencyLog.mark("webui_stream_network_error", sessionId, e.message ?: "")
                trySend(WebUiStreamEvent.AppError(e.message ?: "network error", null))
                close(e)
            }
        }
        thread.name = "webui-sse-$streamId"
        thread.isDaemon = true
        thread.start()

        awaitClose {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun parseEvent(event: String, data: String): WebUiStreamEvent? {
        return try {
            when (event) {
                "token" -> WebUiStreamEvent.Token(JSONObject(data).optString("text"))
                "tool" -> {
                    val obj = JSONObject(data)
                    WebUiStreamEvent.Tool(obj.optString("name"), obj.optString("preview"))
                }
                "tool_complete" -> {
                    val obj = JSONObject(data)
                    WebUiStreamEvent.ToolComplete(
                        name = obj.optString("name"),
                        preview = obj.optString("preview"),
                        isError = obj.optBoolean("is_error", false),
                        durationMs = if (obj.isNull("duration")) null else obj.optDouble("duration").takeIf { !it.isNaN() }
                    )
                }
                "done" -> WebUiStreamEvent.Done(JSONObject(data).optJSONObject("session"))
                "apperror" -> {
                    val obj = JSONObject(data)
                    // Payload construit par _provider_error_payload (api/streaming.py) :
                    // {message, type, hint?, details?} — PAS {error, trace} (vérifié
                    // dans le code source réel, pas deviné par convention avec
                    // d'autres endpoints). optString() sur une clé JSON `null`
                    // explicite renvoie la chaîne littérale "null", pas "" —
                    // isNull() d'abord (bug confirmé en conditions réelles côté
                    // cron jobs, voir WebUiCronClient).
                    val details = if (obj.isNull("details")) null else obj.optString("details").takeIf { it.isNotBlank() }
                    WebUiStreamEvent.AppError(obj.optString("message"), details)
                }
                "cancel" -> WebUiStreamEvent.Cancel(JSONObject(data).optString("message"))
                "stream_end" -> WebUiStreamEvent.StreamEnd
                "pending_steer_leftover" -> WebUiStreamEvent.PendingSteerLeftover(JSONObject(data).optString("text"))
                "title" -> {
                    val title = JSONObject(data).optString("title").takeIf { it.isNotBlank() }
                    if (title != null) WebUiStreamEvent.Title(title) else null
                }
                else -> {
                    // Events serveur réels mais hors périmètre (reasoning,
                    // interim_assistant, metering, context_status,
                    // compressing, compressed, warning, goal, goal_continue)
                    // — reconnus par leur vrai nom, délibérément ignorés.
                    Log.d(TAG, "Evenement SSE hors périmètre, ignoré: event=$event")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseEvent: JSON invalide pour event=$event", e)
            null
        }
    }
}
