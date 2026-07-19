package com.hasan.v1.webui

import android.util.Log
import com.hasan.v1.webui.models.McpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client REST pour la gestion des serveurs MCP configurés côté hermes-webui
 * (~/.hermes/config.yaml, mcp_servers) — GET /api/mcp/servers (liste avec
 * statut runtime) et PATCH /api/mcp/servers/{name} (toggle actif/inactif,
 * sauvegarde YAML + reload_config() côté serveur). Réutilise
 * [WebUiRestClient] comme les autres clients webui/.
 */
class WebUiMcpClient(private val restClient: WebUiRestClient) {

    companion object {
        private const val TAG = "WebUiMcpClient"
    }

    private fun authedRequest(path: String) = Request.Builder()
        .url("${restClient.baseUrl()}$path")
        .apply { restClient.currentCookie()?.let { addHeader("Cookie", it) } }

    /** GET /api/mcp/servers — liste des serveurs MCP configurés avec leur statut. */
    suspend fun listServers(): List<McpServer> = withContext(Dispatchers.IO) {
        val request = authedRequest("/api/mcp/servers").get().build()
        try {
            restClient.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "listServers: HTTP ${response.code}")
                    return@withContext emptyList()
                }
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val arr = JSONObject(bodyStr).optJSONArray("servers") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i -> parseServer(arr.optJSONObject(i)) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listServers: échec réseau", e)
            emptyList()
        }
    }

    /** PATCH /api/mcp/servers/{name} {enabled} — bascule un serveur MCP actif/inactif. Retourne false en cas d'échec. */
    suspend fun setEnabled(name: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("enabled", enabled).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = authedRequest("/api/mcp/servers/$name").patch(payload).build()
        try {
            restClient.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "setEnabled($name): HTTP ${response.code}")
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "setEnabled($name): échec réseau", e)
            false
        }
    }

    private fun parseServer(obj: JSONObject?): McpServer? {
        if (obj == null) return null
        val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return null
        return McpServer(
            name = name,
            enabled = obj.optBoolean("enabled", false),
            toggleSupported = obj.optBoolean("toggle_supported", false)
        )
    }
}
