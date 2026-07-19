package com.hasan.v1.webui

import android.util.Log
import com.hasan.v1.webui.models.ApprovalChoice
import com.hasan.v1.webui.models.PendingApproval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client REST pour le système d'approbation de commandes sensibles
 * (tools/approval.py) — GET /api/approval/pending (snapshot ponctuel,
 * utilisé au besoin en complément du flux SSE [WebUiApprovalStream]) et
 * POST /api/approval/respond (réponse utilisateur : once/session/always/deny).
 * Réutilise [WebUiRestClient] comme les autres clients webui/.
 */
class WebUiApprovalClient(private val restClient: WebUiRestClient) {

    companion object {
        private const val TAG = "WebUiApprovalClient"
    }

    private fun authedRequest(path: String) = okhttp3.Request.Builder()
        .url("${restClient.baseUrl()}$path")
        .apply { restClient.currentCookie()?.let { addHeader("Cookie", it) } }

    /** GET /api/approval/pending?session_id= — snapshot ponctuel des approbations en attente pour la session. */
    suspend fun getPending(sessionId: String): List<PendingApproval> = withContext(Dispatchers.IO) {
        val request = authedRequest("/api/approval/pending?session_id=$sessionId").get().build()
        try {
            restClient.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "getPending: HTTP ${response.code}")
                    return@withContext emptyList()
                }
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                parsePendingList(sessionId, JSONObject(bodyStr))
            }
        } catch (e: Exception) {
            Log.w(TAG, "getPending: échec réseau", e)
            emptyList()
        }
    }

    private fun parsePendingList(sessionId: String, obj: JSONObject): List<PendingApproval> {
        val pending = obj.optJSONObject("pending") ?: return emptyList()
        val patternKeysArr = pending.optJSONArray("pattern_keys") ?: JSONArray()
        return listOf(
            PendingApproval(
                approvalId = pending.optString("approval_id"),
                sessionId = sessionId,
                command = pending.optString("command"),
                description = pending.optString("description"),
                patternKeys = (0 until patternKeysArr.length()).map { patternKeysArr.optString(it) }
            )
        )
    }

    /** POST /api/approval/respond — {session_id, approval_id, choice}. Retourne false en cas d'échec réseau ou HTTP non 2xx. */
    suspend fun respond(sessionId: String, approvalId: String, choice: ApprovalChoice): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("session_id", sessionId)
            put("approval_id", approvalId)
            put("choice", choice.wireValue)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = authedRequest("/api/approval/respond").post(body).build()
        try {
            restClient.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "respond: HTTP ${response.code}")
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "respond: échec réseau", e)
            false
        }
    }
}
