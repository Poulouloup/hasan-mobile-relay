package com.hasan.v1.webui

import android.util.Log
import com.hasan.v1.webui.models.SkillDetail
import com.hasan.v1.webui.models.SkillSummary
import com.hasan.v1.webui.models.SkillUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Client REST en LECTURE SEULE pour les skills hermes-webui (endpoints
 * GET /api/skills, /api/skills/usage, /api/skills/content). Schéma vérifié
 * contre le code source réel du serveur — le serveur expose aussi des
 * endpoints d'écriture (save/delete/toggle) délibérément non implémentés
 * ici (écran Skills en lecture seule, voir le prompt de migration).
 *
 * Réutilise [WebUiRestClient] (cookie de session, TOFU, client OkHttp)
 * comme [WebUiCronClient].
 */
class WebUiSkillsClient(private val restClient: WebUiRestClient) {

    companion object {
        private const val TAG = "WebUiSkillsClient"
    }

    private fun authedRequest(path: String): Request.Builder {
        val builder = Request.Builder().url("${restClient.baseUrl()}$path")
        restClient.currentCookie()?.let { builder.addHeader("Cookie", it) }
        return builder
    }

    /** Les noms de skills peuvent contenir espaces/accents/etc. (contrairement aux job_id cron, garantis hex) — encodage explicite requis. */
    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * JSONObject.optString() sur une clé JSON `null` explicite renvoie la
     * chaîne littérale "null", pas "" — bug confirmé en conditions réelles
     * côté cron jobs (voir WebUiCronClient.optNullableString), même
     * précaution ici par cohérence.
     */
    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    /** GET /api/skills?category= — liste triée (catégorie, nom) côté serveur. [category] optionnel, filtre exact. */
    suspend fun listSkills(category: String? = null): WebUiCallResult<List<SkillSummary>> {
        val path = if (category.isNullOrBlank()) "/api/skills" else "/api/skills?category=${urlEncode(category)}"
        val request = authedRequest(path).get().build()
        return restClient.executeAuthed(request) { bodyStr ->
            val arr = JSONObject(bodyStr).optJSONArray("skills") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i -> parseSkillSummary(arr.optJSONObject(i)) }
        }
    }

    /**
     * GET /api/skills/content?name=X — détail d'une skill (SKILL.md complet
     * + métadonnées). Retourne null si non trouvée ou en erreur — le
     * serveur renvoie {"success": false, ...} en HTTP 200 pour ce cas
     * précis (pas un vrai code d'erreur HTTP), donc on vérifie "success"
     * dans le corps plutôt que le status de la réponse.
     */
    suspend fun getSkillDetail(name: String): SkillDetail? = withContext(Dispatchers.IO) {
        val request = authedRequest("/api/skills/content?name=${urlEncode(name)}").get().build()
        try {
            restClient.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "getSkillDetail: HTTP ${response.code}")
                    return@withContext null
                }
                val bodyStr = response.body?.string() ?: return@withContext null
                val obj = JSONObject(bodyStr)
                if (!obj.optBoolean("success", false)) {
                    Log.w(TAG, "getSkillDetail: success=false — ${obj.optNullableString("error")}")
                    return@withContext null
                }
                parseSkillDetail(obj)
            }
        } catch (e: Exception) {
            Log.w(TAG, "getSkillDetail: échec réseau", e)
            null
        }
    }

    /**
     * GET /api/skills/usage — compteurs cumulés par skill (use/view/patch),
     * écrits par hermes-agent, lus seuls ici. Pas un historique d'événements.
     */
    suspend fun getUsage(): WebUiCallResult<Map<String, SkillUsage>> {
        val request = authedRequest("/api/skills/usage").get().build()
        return restClient.executeAuthed(request) { bodyStr ->
            val usageObj = JSONObject(bodyStr).optJSONObject("usage") ?: JSONObject()
            val result = mutableMapOf<String, SkillUsage>()
            usageObj.keys().forEach { name ->
                val entry = usageObj.optJSONObject(name) ?: return@forEach
                result[name] = SkillUsage(
                    useCount = entry.optInt("use_count", 0),
                    viewCount = entry.optInt("view_count", 0),
                    patchCount = entry.optInt("patch_count", 0)
                )
            }
            result
        }
    }

    private fun parseSkillSummary(obj: JSONObject?): SkillSummary? {
        if (obj == null) return null
        val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return null
        return SkillSummary(
            name = name,
            description = obj.optNullableString("description") ?: "",
            category = obj.optNullableString("category"),
            disabled = obj.optBoolean("disabled", false)
        )
    }

    private fun parseSkillDetail(obj: JSONObject): SkillDetail {
        val tagsArr = obj.optJSONArray("tags") ?: JSONArray()
        val relatedArr = obj.optJSONArray("related_skills") ?: JSONArray()
        val linkedFilesObj = obj.optJSONObject("linked_files")
        val linkedFiles = mutableMapOf<String, List<String>>()
        linkedFilesObj?.keys()?.forEach { key ->
            val filesArr = linkedFilesObj.optJSONArray(key) ?: return@forEach
            linkedFiles[key] = (0 until filesArr.length()).map { filesArr.optString(it) }
        }
        return SkillDetail(
            name = obj.optString("name"),
            description = obj.optNullableString("description") ?: "",
            tags = (0 until tagsArr.length()).map { tagsArr.optString(it) },
            relatedSkills = (0 until relatedArr.length()).map { relatedArr.optString(it) },
            content = obj.optString("content"),
            linkedFiles = linkedFiles
        )
    }
}
