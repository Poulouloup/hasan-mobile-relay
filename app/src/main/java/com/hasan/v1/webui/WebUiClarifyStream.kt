package com.hasan.v1.webui

import android.util.Log
import com.hasan.v1.utils.LatencyLog
import com.hasan.v1.webui.models.WebUiClarifyPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client SSE pour GET /api/clarify/stream?session_id=X (voir api/routes.py
 * `_handle_clarify_sse_stream`, api/clarify.py). Remplace fonctionnellement
 * l'ancien chat/clarify du relay WSS — mécanisme entièrement distinct côté
 * hermes-webui (connexion SSE séparée, pas une enveloppe dans le flux de
 * chat). Le serveur pousse un event `initial` immédiatement à la connexion
 * (snapshot de l'état courant), puis un event `clarify` à chaque nouveau
 * prompt ou changement (y compris `pending: null` quand le prompt est
 * résolu/expiré ailleurs — voir api/clarify.py `clear_pending`).
 */
class WebUiClarifyStream(private val restClient: WebUiRestClient) {

    companion object {
        private const val TAG = "WebUiClarifyStream"
    }

    /** Emet un [WebUiClarifyPrompt] à chaque prompt en attente, ou null quand il n'y en a plus. Flux jamais terminé côté client — fermé uniquement par l'appelant (viewModelScope) ou une coupure réseau. */
    fun stream(sessionId: String): Flow<WebUiClarifyPrompt?> = callbackFlow {
        val request = Request.Builder()
            .url("${restClient.baseUrl()}/api/clarify/stream?session_id=$sessionId")
            .apply { restClient.currentCookie()?.let { addHeader("Cookie", it) } }
            .build()

        val call = restClient.httpClient.newCall(request)

        val thread = Thread {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        LatencyLog.mark("webui_clarify_stream_http_error", sessionId, "HTTP ${response.code}")
                        close()
                        return@use
                    }
                    val source = response.body?.source() ?: run { close(); return@use }

                    var currentEvent: String? = null
                    val dataLines = StringBuilder()

                    fun flushEvent() {
                        if (currentEvent == null || dataLines.isEmpty()) {
                            currentEvent = null
                            dataLines.clear()
                            return
                        }
                        val prompt = parseEvent(currentEvent!!, dataLines.toString())
                        // 'initial' et 'clarify' partagent le même shape {pending, pending_count} —
                        // les deux poussent une mise à jour, y compris pending=null.
                        if (currentEvent == "initial" || currentEvent == "clarify") {
                            val result = trySend(prompt)
                            if (result.isFailure) {
                                LatencyLog.mark("webui_clarify_trysend_failed", sessionId, currentEvent ?: "")
                            }
                        }
                        currentEvent = null
                        dataLines.clear()
                    }

                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.startsWith(":") -> { /* keepalive comment */ }
                            line.startsWith("event:") -> currentEvent = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> {
                                if (dataLines.isNotEmpty()) dataLines.append('\n')
                                dataLines.append(line.removePrefix("data:").trim())
                            }
                            line.isBlank() -> flushEvent()
                        }
                    }
                    close()
                }
            } catch (e: Exception) {
                LatencyLog.mark("webui_clarify_stream_network_error", sessionId, e.message ?: "")
                close(e)
            }
        }
        thread.name = "webui-clarify-sse-$sessionId"
        thread.isDaemon = true
        thread.start()

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    /** {pending: {...}|null, pending_count?: N} -> WebUiClarifyPrompt?, null si pending est null (pas de prompt en attente). */
    private fun parseEvent(event: String, data: String): WebUiClarifyPrompt? {
        return try {
            val obj = JSONObject(data)
            val pending = obj.optJSONObject("pending") ?: return null
            val choicesArr = pending.optJSONArray("choices_offered") ?: JSONArray()
            WebUiClarifyPrompt(
                clarifyId = pending.optString("clarify_id"),
                question = pending.optString("question"),
                choicesOffered = (0 until choicesArr.length()).map { choicesArr.optString(it) },
                timeoutSeconds = pending.optInt("timeout_seconds", 120)
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseEvent($event): JSON invalide", e)
            null
        }
    }
}
