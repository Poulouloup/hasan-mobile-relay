package com.hasan.v1.webui

import android.util.Log
import com.hasan.v1.utils.LatencyLog
import com.hasan.v1.webui.models.PendingApproval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client SSE pour GET /api/approval/stream?session_id=X — source unique des
 * demandes d'approbation d'outils sensibles (tools/approval.py), remplace
 * l'event `approval` autrefois reçu inline dans /api/chat/stream. Même
 * shape que [WebUiClarifyStream] : event `initial` au moment de la connexion
 * (snapshot), puis `approval` à chaque nouvelle demande ou résolution
 * (pending: null quand plus rien n'attend). Flux jamais terminé côté
 * client — fermé uniquement par l'appelant (viewModelScope) ou une coupure
 * réseau.
 */
class WebUiApprovalStream(private val restClient: WebUiRestClient) {

    companion object {
        private const val TAG = "WebUiApprovalStream"
    }

    fun stream(sessionId: String): Flow<PendingApproval?> = callbackFlow {
        val request = Request.Builder()
            .url("${restClient.baseUrl()}/api/approval/stream?session_id=$sessionId")
            .apply { restClient.currentCookie()?.let { addHeader("Cookie", it) } }
            .build()

        val call = restClient.httpClient.newCall(request)

        val thread = Thread {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        LatencyLog.mark("webui_approval_stream_http_error", sessionId, "HTTP ${response.code}")
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
                        val approval = parseEvent(sessionId, currentEvent!!, dataLines.toString())
                        if (currentEvent == "initial" || currentEvent == "approval") {
                            val result = trySend(approval)
                            if (result.isFailure) {
                                LatencyLog.mark("webui_approval_trysend_failed", sessionId, currentEvent ?: "")
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
                LatencyLog.mark("webui_approval_stream_network_error", sessionId, e.message ?: "")
                close(e)
            }
        }
        thread.name = "webui-approval-sse-$sessionId"
        thread.isDaemon = true
        thread.start()

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    /** {pending: {...}|null, pending_count?: N} -> PendingApproval?, null si pending est null. */
    private fun parseEvent(sessionId: String, event: String, data: String): PendingApproval? {
        return try {
            val obj = JSONObject(data)
            val pending = obj.optJSONObject("pending") ?: return null
            val patternKeysArr = pending.optJSONArray("pattern_keys") ?: JSONArray()
            PendingApproval(
                approvalId = pending.optString("approval_id"),
                sessionId = sessionId,
                command = pending.optString("command"),
                description = pending.optString("description"),
                patternKeys = (0 until patternKeysArr.length()).map { patternKeysArr.optString(it) }
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseEvent($event): JSON invalide", e)
            null
        }
    }
}
