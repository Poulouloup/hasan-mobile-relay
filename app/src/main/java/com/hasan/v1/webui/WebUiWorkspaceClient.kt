package com.hasan.v1.webui

import com.hasan.v1.webui.models.WorkspaceEntry
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Client REST pour le workspace hermes-webui
 * (~/hermes-webui/api/workspace.py::list_dir, routé depuis
 * ~/hermes-webui/api/routes.py) — API déjà utilisée par le frontend web
 * statique de hermes-webui, jamais consommée par l'app avant ce client.
 * Réutilise [WebUiRestClient] (cookie de session, TOFU) comme
 * [WebUiKanbanClient]/[WebUiSkillsClient].
 *
 * [listFiles] prend un `session_id` (requis par le contrat serveur) mais ce
 * paramètre n'isole PAS le contenu dans la config par défaut de
 * hermes-webui — toutes les sessions partagent le même workspace disque tant
 * qu'un workspace différent n'a pas été explicitement choisi par session.
 */
class WebUiWorkspaceClient(private val restClient: WebUiRestClient) {

    private fun authedRequest(path: String): Request.Builder {
        val builder = Request.Builder().url("${restClient.baseUrl()}$path")
        restClient.currentCookie()?.let { builder.addHeader("Cookie", it) }
        return builder
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** GET /api/list?session_id=<sid>&path=<rel> — contenu du workspace de la session. */
    suspend fun listFiles(sessionId: String, path: String = "."): WebUiCallResult<List<WorkspaceEntry>> {
        val request = authedRequest("/api/list?session_id=${urlEncode(sessionId)}&path=${urlEncode(path)}").get().build()
        return restClient.executeAuthed(request) { bodyStr ->
            val arr = JSONObject(bodyStr).optJSONArray("entries") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i -> parseEntry(arr.optJSONObject(i)) }
        }
    }

    private fun parseEntry(obj: JSONObject?): WorkspaceEntry? {
        if (obj == null) return null
        val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return null
        val path = obj.optString("path").takeIf { it.isNotBlank() } ?: return null
        return WorkspaceEntry(
            name = name,
            path = path,
            isDir = obj.optString("type") == "dir",
            size = if (obj.isNull("size")) null else obj.optLong("size"),
            mtimeNs = if (obj.isNull("mtime_ns")) null else obj.optLong("mtime_ns")
        )
    }
}
