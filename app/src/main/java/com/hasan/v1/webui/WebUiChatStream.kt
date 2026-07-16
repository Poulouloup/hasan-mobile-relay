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
                        trySend(WebUiStreamEvent.Error("HTTP ${response.code}", null)).also {
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
                            if (parsed is WebUiStreamEvent.Done || parsed is WebUiStreamEvent.Error) {
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
                trySend(WebUiStreamEvent.Error(e.message ?: "network error", null))
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
                "approval" -> {
                    val obj = JSONObject(data)
                    val keys = obj.optJSONArray("pattern_keys") ?: JSONArray()
                    WebUiStreamEvent.Approval(
                        command = obj.optString("command"),
                        description = obj.optString("description"),
                        patternKeys = (0 until keys.length()).map { keys.optString(it) }
                    )
                }
                "done" -> WebUiStreamEvent.Done(JSONObject(data).optJSONObject("session"))
                "error" -> {
                    val obj = JSONObject(data)
                    // optString() sur une clé JSON `null` explicite renvoie la chaîne
                    // littérale "null", pas "" — isNull() d'abord (bug confirmé en
                    // conditions réelles côté cron jobs, voir WebUiCronClient).
                    val trace = if (obj.isNull("trace")) null else obj.optString("trace").takeIf { it.isNotBlank() }
                    WebUiStreamEvent.Error(obj.optString("message"), trace)
                }
                else -> {
                    Log.d(TAG, "Evenement SSE non géré: event=$event")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseEvent: JSON invalide pour event=$event", e)
            null
        }
    }
}
