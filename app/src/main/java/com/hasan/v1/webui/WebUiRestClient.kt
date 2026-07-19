package com.hasan.v1.webui

import android.util.Log
import com.hasan.v1.SettingsManager
import com.hasan.v1.auth.CertPinStore
import com.hasan.v1.utils.LatencyLog
import com.hasan.v1.webui.models.UploadedAttachment
import com.hasan.v1.webui.models.WebUiHealthResult
import com.hasan.v1.webui.models.WebUiLoginResult
import com.hasan.v1.webui.models.WebUiSessionSummary
import com.hasan.v1.webui.models.WebUiSteerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * Client REST vers hermes-webui (nesquena/hermes-webui) — auth par mot de
 * passe + cookie, sessions, démarrage de tour de chat, réponse aux prompts
 * de clarification. Schéma vérifié contre le code réel du serveur
 * (api/routes.py, api/auth.py, api/clarify.py) — voir notes de commit pour
 * les références précises.
 *
 * Serveur distinct du relay WSS (server/relay/) : TOFU dédié (namespace
 * "webui", voir [CertPinStore.storageKeyFor]), pas de partage de client HTTP
 * avec [com.hasan.v1.network.ConnectionManager].
 */
class WebUiRestClient(
    private val settings: SettingsManager,
    private val authStore: WebUiAuthStore
) {

    companion object {
        private const val TAG = "WebUiRestClient"
        private const val JSON_MEDIA_TYPE_STR = "application/json; charset=utf-8"
    }

    private val certPinStore = CertPinStore(settings)

    private fun certStorageKey(): String =
        CertPinStore.storageKeyFor("webui", WebUiUrlDeriver.httpBaseUrl(settings.webUiServerUrl))

    private val tofuTrustManager = certPinStore.newTrustManager(certStorageKey())

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(tofuTrustManager), java.security.SecureRandom())
    }

    /** Client HTTP nu, sans cookie automatique — chaque appel attache lui-même le cookie stocké (voir [authedRequest]). */
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, tofuTrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()

    /** URL de base hermes-webui courante — exposée pour [WebUiChatStream], qui construit sa propre requête SSE sur le même serveur/cookie. */
    fun baseUrl(): String = WebUiUrlDeriver.httpBaseUrl(settings.webUiServerUrl)

    /**
     * Header `Cookie` combiné (session + profil actif s'il y en a un) à
     * attacher à une requête, ou null si non authentifié — exposé pour
     * [WebUiChatStream]/[WebUiClarifyStream], qui construisent leur propre
     * requête SSE sur le même serveur/cookies. Les deux cookies hermes-webui
     * (hermes_session, hermes_profile) sont indépendants côté serveur — un
     * seul header Cookie standard HTTP les porte tous les deux, séparés par
     * "; " (RFC 6265).
     */
    fun currentCookie(): String? {
        val session = authStore.currentCookie ?: return null
        val profile = authStore.currentProfileCookie
        return if (profile.isNullOrBlank()) session else "$session; $profile"
    }

    /** Persiste un nouveau cookie `hermes_profile` — exposé pour [WebUiProfilesClient.switchProfile]. */
    fun storeProfileCookie(cookieHeaderValue: String) {
        authStore.storeProfileCookie(cookieHeaderValue)
    }

    private fun authedRequest(path: String): Request.Builder {
        val builder = Request.Builder().url("${baseUrl()}$path")
        currentCookie()?.let { builder.addHeader("Cookie", it) }
        return builder
    }

    /**
     * POST /api/auth/login {password} — voir api/routes.py `/api/auth/login`.
     * En cas de succès, extrait le cookie hermes_session du header Set-Cookie
     * et le persiste via [WebUiAuthStore.store].
     */
    suspend fun login(password: String): WebUiLoginResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("password", password).toString()
            .toRequestBody(JSON_MEDIA_TYPE_STR.toMediaType())
        val request = Request.Builder()
            .url("${baseUrl()}/api/auth/login")
            .post(body)
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val setCookie = response.headers("Set-Cookie").firstOrNull { it.startsWith("hermes_session=") }
                        if (setCookie == null) {
                            LatencyLog.mark("webui_login_no_cookie", "auth", "200 sans Set-Cookie")
                            return@withContext WebUiLoginResult.NetworkError("Réponse serveur invalide (pas de cookie)")
                        }
                        // Ne garde que "nom=valeur", sans les attributs (Path/HttpOnly/Secure/etc.) —
                        // ils seraient réinjectés tels quels et invalides dans un header Cookie de requête.
                        val cookiePair = setCookie.substringBefore(";")
                        authStore.store(cookiePair)
                        LatencyLog.mark("webui_login_ok", "auth", "")
                        WebUiLoginResult.Ok
                    }
                    401 -> WebUiLoginResult.InvalidPassword
                    429 -> WebUiLoginResult.RateLimited
                    else -> WebUiLoginResult.NetworkError("HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "login: échec réseau", e)
            WebUiLoginResult.NetworkError(e.message ?: "network error")
        }
    }

    /**
     * GET /health — endpoint public sans cookie, {"status":"ok","sessions":N,...}.
     * Remplace l'ancien health check via le canal WS relay (chat/health) —
     * ici une simple requête HTTP one-shot, pas de round-trip applicatif.
     */
    suspend fun checkHealth(): WebUiHealthResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrl()}/health").get().build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) WebUiHealthResult.Ok
                else WebUiHealthResult.ServerError(response.code)
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkHealth: échec réseau", e)
            WebUiHealthResult.NetworkError(e.message ?: "network error")
        }
    }

    /** GET /api/sessions — liste des sessions, triées par updated_at côté serveur (api/routes.py). */
    suspend fun listSessions(): List<WebUiSessionSummary> = withContext(Dispatchers.IO) {
        val request = authedRequest("/api/sessions").get().build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "listSessions: HTTP ${response.code}")
                    return@withContext emptyList()
                }
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val arr = JSONObject(bodyStr).optJSONArray("sessions") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i -> parseSessionSummary(arr.optJSONObject(i)) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listSessions: échec réseau", e)
            emptyList()
        }
    }

    /**
     * POST /api/session/new {model?, workspace?} — crée une nouvelle session,
     * retourne son session_id (ou null en cas d'échec). Réponse vérifiée en
     * direct contre le serveur réel : {"session": {"session_id": ..., ...}}
     * (objet Session.compact() complet imbriqué, pas un session_id à plat).
     */
    suspend fun createSession(model: String? = null, workspace: String? = null): String? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            model?.let { put("model", it) }
            workspace?.let { put("workspace", it) }
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE_STR.toMediaType())
        val request = authedRequest("/api/session/new").post(body).build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "createSession: HTTP ${response.code}")
                    return@withContext null
                }
                val bodyStr = response.body?.string() ?: return@withContext null
                JSONObject(bodyStr).optJSONObject("session")?.optString("session_id")?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "createSession: échec réseau", e)
            null
        }
    }

    /**
     * POST /api/chat/start {session_id, message, model?, workspace?} ->
     * {stream_id, session_id}. Retourne le stream_id à passer à
     * [WebUiChatStream.stream], ou null en cas d'échec (session introuvable,
     * réseau, etc. — voir logs).
     */
    suspend fun startChat(
        sessionId: String,
        message: String,
        model: String? = null,
        attachments: List<UploadedAttachment> = emptyList()
    ): String? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("message", message)
            model?.let { put("model", it) }
            if (attachments.isNotEmpty()) {
                put("attachments", JSONArray().apply {
                    attachments.forEach { att ->
                        put(JSONObject().apply {
                            put("name", att.name)
                            put("path", att.path)
                            put("mime", att.mime)
                            put("size", att.size)
                            put("is_image", att.isImage)
                        })
                    }
                })
            }
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE_STR.toMediaType())
        val request = authedRequest("/api/chat/start").post(body).build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "startChat: HTTP ${response.code} — ${response.body?.string()}")
                    LatencyLog.mark("webui_chat_start_error", sessionId, "HTTP ${response.code}")
                    return@withContext null
                }
                val bodyStr = response.body?.string() ?: return@withContext null
                val streamId = JSONObject(bodyStr).optString("stream_id").takeIf { it.isNotBlank() }
                LatencyLog.mark("webui_chat_start_ok", sessionId, streamId ?: "")
                streamId
            }
        } catch (e: Exception) {
            Log.w(TAG, "startChat: échec réseau", e)
            LatencyLog.mark("webui_chat_start_error", sessionId, e.message ?: "network error")
            null
        }
    }

    /**
     * POST /api/chat/steer {session_id, text} -> {accepted, fallback, stream_id}.
     * Injecte du texte dans le run actif (voir api/streaming.py
     * `_handle_chat_steer` — appliqué au prochain résultat d'outil, PAS
     * immédiat, le stream n'est pas interrompu). [WebUiSteerResult.Rejected]
     * porte le motif exact du serveur (no_cached_agent, not_running,
     * stream_dead, agent_lacks_steer, session_not_found, steer_error,
     * gateway_steer_queued) — le serveur est explicite : un steer refusé
     * n'autorise PAS un fallback implicite vers cancel+renvoi côté appelant.
     */
    suspend fun steerChat(sessionId: String, text: String): WebUiSteerResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("text", text)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE_STR.toMediaType())
        val request = authedRequest("/api/chat/steer").post(body).build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "steerChat: HTTP ${response.code}")
                    return@withContext WebUiSteerResult.NetworkError("HTTP ${response.code}")
                }
                val bodyStr = response.body?.string() ?: return@withContext WebUiSteerResult.NetworkError("réponse vide")
                val obj = JSONObject(bodyStr)
                val accepted = obj.optBoolean("accepted", false)
                if (accepted) {
                    val streamId = obj.optString("stream_id").takeIf { it.isNotBlank() } ?: sessionId
                    WebUiSteerResult.Accepted(streamId)
                } else {
                    WebUiSteerResult.Rejected(obj.optString("fallback").takeIf { it.isNotBlank() } ?: "unknown")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "steerChat: échec réseau", e)
            WebUiSteerResult.NetworkError(e.message ?: "network error")
        }
    }

    /**
     * POST /api/upload (multipart session_id+file) -> {filename, path, size,
     * mime, is_image} — voir api/upload.py `handle_upload`. Le fichier est
     * déposé dans l'inbox de pièces jointes de la session ; `path` est à
     * renvoyer tel quel dans `attachments[]` de [startChat]. `bytes` est lu
     * en amont côté appelant (ContentResolver — les content:// Android ne
     * sont pas des chemins fichier, donc pas de [File] direct ici).
     */
    suspend fun uploadFile(
        sessionId: String,
        filename: String,
        mimeType: String,
        bytes: ByteArray
    ): UploadedAttachment? = withContext(Dispatchers.IO) {
        val fileBody = bytes.toRequestBody(mimeType.toMediaType())
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("session_id", sessionId)
            .addFormDataPart("file", filename, fileBody)
            .build()
        val request = authedRequest("/api/upload").post(multipart).build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "uploadFile: HTTP ${response.code} — ${response.body?.string()}")
                    return@withContext null
                }
                val bodyStr = response.body?.string() ?: return@withContext null
                val obj = JSONObject(bodyStr)
                val path = obj.optString("path").takeIf { it.isNotBlank() } ?: return@withContext null
                UploadedAttachment(
                    name = obj.optString("filename").takeIf { it.isNotBlank() } ?: filename,
                    path = path,
                    size = obj.optLong("size", bytes.size.toLong()),
                    mime = obj.optString("mime").takeIf { it.isNotBlank() } ?: mimeType,
                    isImage = obj.optBoolean("is_image", false)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "uploadFile: échec réseau", e)
            null
        }
    }

    /**
     * GET /api/chat/cancel?stream_id=X -> {ok, cancelled, stream_id}. Annule
     * le run en cours — l'event SSE `cancel` arrive ensuite naturellement
     * dans le flux déjà ouvert par [WebUiChatStream.stream], pas besoin de le
     * fermer manuellement côté client.
     */
    suspend fun cancelChat(streamId: String): Boolean = withContext(Dispatchers.IO) {
        val request = authedRequest("/api/chat/cancel?stream_id=$streamId").get().build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "cancelChat: HTTP ${response.code}")
                    return@withContext false
                }
                val bodyStr = response.body?.string() ?: return@withContext false
                JSONObject(bodyStr).optBoolean("cancelled", false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cancelChat: échec réseau", e)
            false
        }
    }

    /** POST /api/session/rename {session_id, title} -> {session: compact()}. */
    suspend fun renameSession(sessionId: String, title: String): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("title", title)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE_STR.toMediaType())
        val request = authedRequest("/api/session/rename").post(body).build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) Log.w(TAG, "renameSession: HTTP ${response.code}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "renameSession: échec réseau", e)
            false
        }
    }

    /** POST /api/session/delete {session_id} -> {ok, state_db_cleanup_failed, ...}. */
    suspend fun deleteSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("session_id", sessionId)
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE_STR.toMediaType())
        val request = authedRequest("/api/session/delete").post(body).build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "deleteSession: HTTP ${response.code}")
                    return@withContext false
                }
                val bodyStr = response.body?.string() ?: return@withContext false
                JSONObject(bodyStr).optBoolean("ok", false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "deleteSession: échec réseau", e)
            false
        }
    }

    /**
     * POST /api/clarify/respond {session_id, clarify_id, response} ->
     * {ok, response} ou 409 {ok:false, stale:true} si le prompt a expiré côté
     * serveur (voir api/routes.py `_handle_clarify_respond`). Retourne false
     * dans les deux cas d'échec (réseau ou 409) — l'appelant ne distingue pas
     * pour l'instant, le tour a de toute façon avancé sans cette réponse.
     */
    suspend fun respondClarify(sessionId: String, clarifyId: String, response: String): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("clarify_id", clarifyId)
            put("response", response)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE_STR.toMediaType())
        val request = authedRequest("/api/clarify/respond").post(body).build()
        try {
            httpClient.newCall(request).execute().use { resp ->
                val ok = resp.isSuccessful
                LatencyLog.mark(if (ok) "webui_clarify_respond_ok" else "webui_clarify_respond_error", sessionId, "HTTP ${resp.code}")
                ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "respondClarify: échec réseau", e)
            false
        }
    }

    /**
     * JSONObject.optString() sur une clé dont la valeur JSON est `null`
     * (explicite, pas absente) renvoie la chaîne littérale "null", pas ""
     * — piège org.json confirmé en conditions réelles côté cron jobs (voir
     * WebUiCronClient.optNullableString). Le serveur envoie aussi `model:
     * null` pour certaines sessions, donc même correctif ici.
     */
    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun parseSessionSummary(obj: JSONObject?): WebUiSessionSummary? {
        if (obj == null) return null
        val sessionId = obj.optString("session_id").takeIf { it.isNotBlank() } ?: return null
        return WebUiSessionSummary(
            sessionId = sessionId,
            title = obj.optNullableString("title"),
            model = obj.optNullableString("model"),
            messageCount = obj.optInt("message_count", 0),
            updatedAt = obj.optDouble("updated_at", 0.0),
            pinned = obj.optBoolean("pinned", false),
            archived = obj.optBoolean("archived", false)
        )
    }
}
