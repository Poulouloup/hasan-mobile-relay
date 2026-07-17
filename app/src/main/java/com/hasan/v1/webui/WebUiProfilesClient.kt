package com.hasan.v1.webui

import android.util.Log
import com.hasan.v1.webui.models.HermesProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client REST pour les profils Hermes (instances HERMES_HOME distinctes —
 * config/skills/workspace séparés). Schéma vérifié en conditions réelles
 * contre le serveur (GET /api/profiles). Sur le VPS de test, un seul
 * profil ("default") existe — implémenté quand même pour ne pas refaire ce
 * travail quand un second profil apparaîtra.
 *
 * Réutilise [WebUiRestClient] comme les autres clients webui/.
 */
class WebUiProfilesClient(private val restClient: WebUiRestClient) {

    companion object {
        private const val TAG = "WebUiProfilesClient"
        private const val JSON_MEDIA_TYPE_STR = "application/json; charset=utf-8"
    }

    private fun authedRequest(path: String) = Request.Builder()
        .url("${restClient.baseUrl()}$path")
        .apply { restClient.currentCookie()?.let { addHeader("Cookie", it) } }

    /** GET /api/profiles — liste des profils Hermes disponibles. */
    suspend fun listProfiles(): List<HermesProfile> = withContext(Dispatchers.IO) {
        val request = authedRequest("/api/profiles").get().build()
        try {
            restClient.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "listProfiles: HTTP ${response.code}")
                    return@withContext emptyList()
                }
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val arr = JSONObject(bodyStr).optJSONArray("profiles") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i -> parseProfile(arr.optJSONObject(i)) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listProfiles: échec réseau", e)
            emptyList()
        }
    }

    /**
     * POST /api/profile/switch {name} — bascule le profil actif. Extrait le
     * cookie `hermes_profile` du header Set-Cookie et le persiste via
     * [WebUiAuthStore.storeProfileCookie]. Retourne false si 401/403/404/409
     * (session non fiable, profil lié à une autre session, profil
     * introuvable, run en cours côté serveur) ou en cas d'échec réseau.
     */
    suspend fun switchProfile(name: String): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("name", name).toString()
            .toRequestBody(JSON_MEDIA_TYPE_STR.toMediaType())
        val request = authedRequest("/api/profile/switch").post(payload).build()
        try {
            restClient.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "switchProfile: HTTP ${response.code}")
                    return@withContext false
                }
                val setCookie = response.headers("Set-Cookie").firstOrNull { it.startsWith("hermes_profile=") }
                if (setCookie != null) {
                    restClient.storeProfileCookie(setCookie.substringBefore(";"))
                }
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "switchProfile: échec réseau", e)
            false
        }
    }

    private fun parseProfile(obj: JSONObject?): HermesProfile? {
        if (obj == null) return null
        val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return null
        return HermesProfile(
            name = name,
            isActive = obj.optBoolean("is_active", false),
            isDefault = obj.optBoolean("is_default", false),
            model = obj.optString("model").takeIf { it.isNotBlank() && !obj.isNull("model") },
            provider = obj.optString("provider").takeIf { it.isNotBlank() && !obj.isNull("provider") },
            skillCount = obj.optInt("skill_count", 0)
        )
    }
}
