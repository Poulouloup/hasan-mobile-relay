package com.hasan.v1.webui

import android.util.Log
import com.hasan.v1.webui.models.ModelGroup
import com.hasan.v1.webui.models.ModelOption
import com.hasan.v1.webui.models.ModelsCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client REST en lecture seule pour le catalogue de modèles LLM
 * hermes-webui (GET /api/models). Schéma vérifié en conditions réelles
 * contre le serveur (curl direct, pas seulement lecture de code) —
 * `configured_model_badges` du payload réel est un objet, pas modélisé ici
 * (metadata secondaire, pas nécessaire pour un simple picker par tour).
 *
 * Réutilise [WebUiRestClient] comme les autres clients webui/.
 */
class WebUiModelsClient(private val restClient: WebUiRestClient) {

    companion object {
        private const val TAG = "WebUiModelsClient"
    }

    private fun authedRequest(path: String) = okhttp3.Request.Builder()
        .url("${restClient.baseUrl()}$path")
        .apply { restClient.currentCookie()?.let { addHeader("Cookie", it) } }

    /** GET /api/models — catalogue complet groupé par provider. Retourne null en cas d'échec réseau (pas de valeur par défaut inventée). */
    suspend fun getModels(): ModelsCatalog? = withContext(Dispatchers.IO) {
        val request = authedRequest("/api/models").get().build()
        try {
            restClient.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "getModels: HTTP ${response.code}")
                    return@withContext null
                }
                val bodyStr = response.body?.string() ?: return@withContext null
                parseCatalog(JSONObject(bodyStr))
            }
        } catch (e: Exception) {
            Log.w(TAG, "getModels: échec réseau", e)
            null
        }
    }

    private fun parseCatalog(obj: JSONObject): ModelsCatalog {
        val groupsArr = obj.optJSONArray("groups") ?: JSONArray()
        val groups = (0 until groupsArr.length()).mapNotNull { i -> parseGroup(groupsArr.optJSONObject(i)) }
        return ModelsCatalog(
            defaultModel = obj.optString("default_model").takeIf { it.isNotBlank() },
            activeProvider = obj.optString("active_provider").takeIf { it.isNotBlank() },
            groups = groups
        )
    }

    private fun parseGroup(obj: JSONObject?): ModelGroup? {
        if (obj == null) return null
        val providerId = obj.optString("provider_id").takeIf { it.isNotBlank() } ?: return null
        val modelsArr = obj.optJSONArray("models") ?: JSONArray()
        val models = (0 until modelsArr.length()).mapNotNull { i -> parseOption(modelsArr.optJSONObject(i)) }
        return ModelGroup(
            providerLabel = obj.optString("provider", providerId),
            providerId = providerId,
            models = models
        )
    }

    private fun parseOption(obj: JSONObject?): ModelOption? {
        if (obj == null) return null
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        return ModelOption(id = id, label = obj.optString("label", id))
    }
}
