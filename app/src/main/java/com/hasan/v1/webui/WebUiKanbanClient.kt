package com.hasan.v1.webui

import com.hasan.v1.webui.models.KANBAN_STATUS_RUNNING
import com.hasan.v1.webui.models.KanbanBoard
import com.hasan.v1.webui.models.KanbanBoardSummary
import com.hasan.v1.webui.models.KanbanColumn
import com.hasan.v1.webui.models.KanbanTask
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Client REST pour le Kanban hermes-webui (~/hermes-webui/api/kanban_bridge.py)
 * — API déjà utilisée par le frontend web statique de hermes-webui, jamais
 * consommée par l'app avant ce client. Réutilise [WebUiRestClient] (cookie
 * de session, TOFU) comme [WebUiSkillsClient].
 */
class WebUiKanbanClient(private val restClient: WebUiRestClient) {

    companion object {
        private const val JSON_MEDIA_TYPE_STR = "application/json; charset=utf-8"
    }

    private fun authedRequest(path: String): Request.Builder {
        val builder = Request.Builder().url("${restClient.baseUrl()}$path")
        restClient.currentCookie()?.let { builder.addHeader("Cookie", it) }
        return builder
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** JSONObject.optString() sur une clé JSON `null` explicite renvoie "null", pas "" — voir WebUiSkillsClient.optNullableString. */
    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNullableLong(key: String): Long? {
        if (isNull(key)) return null
        return optLong(key)
    }

    /** GET /api/kanban/board?board=<slug> — état complet d'un board. [boardSlug] null = board actif côté serveur. */
    suspend fun getBoard(boardSlug: String? = null): WebUiCallResult<KanbanBoard> {
        val path = if (boardSlug.isNullOrBlank()) "/api/kanban/board" else "/api/kanban/board?board=${urlEncode(boardSlug)}"
        val request = authedRequest(path).get().build()
        return restClient.executeAuthed(request) { bodyStr ->
            val obj = JSONObject(bodyStr)
            val columnsArr = obj.optJSONArray("columns") ?: JSONArray()
            val tenantsArr = obj.optJSONArray("tenants") ?: JSONArray()
            val assigneesArr = obj.optJSONArray("assignees") ?: JSONArray()
            KanbanBoard(
                columns = (0 until columnsArr.length()).mapNotNull { i -> parseColumn(columnsArr.optJSONObject(i)) },
                tenants = (0 until tenantsArr.length()).map { tenantsArr.optString(it) },
                assignees = (0 until assigneesArr.length()).map { assigneesArr.optString(it) }
            )
        }
    }

    /** GET /api/kanban/boards — liste des boards existants (métadonnées seules, pas leur contenu). */
    suspend fun listBoards(): WebUiCallResult<List<KanbanBoardSummary>> {
        val request = authedRequest("/api/kanban/boards").get().build()
        return restClient.executeAuthed(request) { bodyStr ->
            val arr = JSONObject(bodyStr).optJSONArray("boards") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i -> parseBoardSummary(arr.optJSONObject(i)) }
        }
    }

    /**
     * POST /api/kanban/boards {slug, name?, switch?} — création idempotente
     * (rappeler avec le même slug renvoie le board existant, pas une erreur).
     */
    suspend fun createBoard(slug: String, name: String? = null, switchTo: Boolean = false): WebUiCallResult<KanbanBoardSummary> {
        val payload = JSONObject().apply {
            put("slug", slug)
            name?.let { put("name", it) }
            if (switchTo) put("switch", true)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE_STR.toMediaType())
        val request = authedRequest("/api/kanban/boards").post(body).build()
        return restClient.executeAuthed(request) { bodyStr ->
            JSONObject(bodyStr).optJSONObject("board")?.let { parseBoardSummary(it) }
        }
    }

    /**
     * PATCH /api/kanban/tasks/<id> {status} — déplace une carte entre
     * colonnes. Le serveur rejette status="running" (HTTP 400, réservé au
     * dispatcher) : intercepté ici en amont pour éviter l'aller-retour
     * réseau et donner un message clair immédiatement.
     */
    suspend fun moveTask(taskId: String, newStatus: String): WebUiCallResult<KanbanTask> {
        if (newStatus == KANBAN_STATUS_RUNNING) {
            return WebUiCallResult.HttpError(400)
        }
        val payload = JSONObject().put("status", newStatus)
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE_STR.toMediaType())
        val request = authedRequest("/api/kanban/tasks/${urlEncode(taskId)}")
            .patch(body)
            .build()
        return restClient.executeAuthed(request) { bodyStr ->
            JSONObject(bodyStr).optJSONObject("task")?.let { parseTask(it) }
        }
    }

    /**
     * POST /api/kanban/tasks {title, body?, assignee?, priority?, tenant?} —
     * crée une nouvelle tâche (statut initial "triage" côté serveur).
     */
    suspend fun createTask(
        title: String,
        body: String? = null,
        assignee: String? = null,
        priority: Int? = null,
        tenant: String? = null,
        status: String? = null
    ): WebUiCallResult<KanbanTask> {
        if (status == KANBAN_STATUS_RUNNING) {
            return WebUiCallResult.HttpError(400)
        }
        val payload = JSONObject().apply {
            put("title", title)
            body?.let { put("body", it) }
            assignee?.let { put("assignee", it) }
            priority?.let { put("priority", it) }
            tenant?.let { put("tenant", it) }
            status?.let { put("status", it) }
        }
        val requestBody = payload.toString().toRequestBody(JSON_MEDIA_TYPE_STR.toMediaType())
        val request = authedRequest("/api/kanban/tasks").post(requestBody).build()
        return restClient.executeAuthed(request) { bodyStr ->
            JSONObject(bodyStr).optJSONObject("task")?.let { parseTask(it) }
        }
    }

    /** GET /api/kanban/tasks/<id> — détail complet (task + comments/events/links, dont seul `task` est exploité ici). */
    suspend fun getTaskDetail(taskId: String): KanbanTask? {
        val request = authedRequest("/api/kanban/tasks/${urlEncode(taskId)}").get().build()
        val result = restClient.executeAuthed(request) { bodyStr ->
            JSONObject(bodyStr).optJSONObject("task")?.let { parseTask(it) }
        }
        return (result as? WebUiCallResult.Ok)?.value
    }

    private fun parseColumn(obj: JSONObject?): KanbanColumn? {
        if (obj == null) return null
        val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return null
        val tasksArr = obj.optJSONArray("tasks") ?: JSONArray()
        return KanbanColumn(
            name = name,
            tasks = (0 until tasksArr.length()).mapNotNull { i -> parseTask(tasksArr.optJSONObject(i)) }
        )
    }

    private fun parseTask(obj: JSONObject?): KanbanTask? {
        if (obj == null) return null
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        val linkCounts = obj.optJSONObject("link_counts")
        return KanbanTask(
            id = id,
            title = obj.optNullableString("title") ?: "",
            body = obj.optNullableString("body"),
            assignee = obj.optNullableString("assignee"),
            status = obj.optNullableString("status") ?: "triage",
            priority = obj.optInt("priority", 0),
            tenant = obj.optNullableString("tenant"),
            createdAt = obj.optNullableLong("created_at"),
            ageSeconds = obj.optNullableLong("age_seconds"),
            commentCount = obj.optInt("comment_count", 0),
            parentCount = linkCounts?.optInt("parents", 0) ?: 0,
            childCount = linkCounts?.optInt("children", 0) ?: 0,
            result = obj.optNullableString("result")
        )
    }

    private fun parseBoardSummary(obj: JSONObject?): KanbanBoardSummary? {
        if (obj == null) return null
        val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: return null
        return KanbanBoardSummary(
            slug = slug,
            name = obj.optNullableString("name"),
            description = obj.optNullableString("description"),
            icon = obj.optNullableString("icon"),
            color = obj.optNullableString("color"),
            archived = obj.optBoolean("archived", false)
        )
    }
}
