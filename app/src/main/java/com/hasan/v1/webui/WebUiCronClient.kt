package com.hasan.v1.webui

import android.util.Log
import com.hasan.v1.webui.models.CronJob
import com.hasan.v1.webui.models.CronOpResult
import com.hasan.v1.webui.models.CronRun
import com.hasan.v1.webui.models.CronRunUsage
import com.hasan.v1.webui.models.DeliveryOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client REST pour les cron jobs hermes-webui (endpoints /api/crons/...).
 * Schéma vérifié
 * contre le code source réel du serveur (api/routes.py `_handle_cron_*`,
 * ~/.hermes/hermes-agent/cron/jobs.py `create_job`/`_normalize_job_record`) —
 * pas de classe CronJob côté serveur, juste un dict Python normalisé par
 * `_cron_job_for_api`.
 *
 * Réutilise le [WebUiRestClient] existant (cookie de session, TOFU, client
 * OkHttp) plutôt que de dupliquer la construction TLS — un seul transport
 * webui pour toute l'app.
 */
class WebUiCronClient(private val restClient: WebUiRestClient) {

    companion object {
        private const val TAG = "WebUiCronClient"
        private const val JSON_MEDIA_TYPE_STR = "application/json; charset=utf-8"
    }

    private fun authedRequest(path: String): Request.Builder {
        val builder = Request.Builder().url("${restClient.baseUrl()}$path")
        restClient.currentCookie()?.let { builder.addHeader("Cookie", it) }
        return builder
    }

    private fun postJson(path: String, body: JSONObject): Request =
        authedRequest(path)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE_STR.toMediaType()))
            .build()

    /** GET /api/crons — jobs du profil actif uniquement (all_profiles omis, pas nécessaire ici). */
    suspend fun listJobs(): List<CronJob> = withContext(Dispatchers.IO) {
        val request = authedRequest("/api/crons").get().build()
        try {
            restClient.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "listJobs: HTTP ${response.code}")
                    return@withContext emptyList()
                }
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val arr = JSONObject(bodyStr).optJSONArray("jobs") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i -> parseJob(arr.optJSONObject(i)) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listJobs: échec réseau", e)
            emptyList()
        }
    }

    /**
     * POST /api/crons/create {prompt, schedule, name?, deliver?} ->
     * {ok:true, job} ou 400 {error} si le format de schedule est invalide
     * (voir parse_schedule côté serveur — 4 grammaires acceptées : "every
     * <Nm/h/d>", expression cron 5 champs, horodatage ISO, durée bare "30m").
     */
    suspend fun createJob(
        prompt: String,
        schedule: String,
        name: String? = null,
        deliver: String? = null
    ): CronOpResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("prompt", prompt)
            put("schedule", schedule)
            name?.let { put("name", it) }
            deliver?.let { put("deliver", it) }
        }
        runWriteRequest(postJson("/api/crons/create", payload))
    }

    /** POST /api/crons/update {job_id, ...champs à changer} -> {ok:true, job} ou 404 si job introuvable. */
    suspend fun updateJob(jobId: String, updates: Map<String, Any?>): CronOpResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("job_id", jobId)
            for ((key, value) in updates) {
                when (value) {
                    null -> {} // le serveur ignore silencieusement les valeurs null dans updates
                    is String, is Boolean, is Int, is Double -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }
        runWriteRequest(postJson("/api/crons/update", payload))
    }

    /** POST /api/crons/delete {job_id} -> {ok:true, job_id} ou 404 si job introuvable. Accepte ID ou nom. */
    suspend fun deleteJob(jobId: String): CronOpResult = withContext(Dispatchers.IO) {
        runWriteRequest(postJson("/api/crons/delete", JSONObject().put("job_id", jobId)))
    }

    /**
     * POST /api/crons/run {job_id} -> {ok:true, status:"running"} (fire-and-
     * forget, le job tourne en arrière-plan côté serveur) ou {ok:false,
     * status:"already_running"} si déjà en cours (HTTP 200 dans les deux
     * cas — pas une erreur), ou 404 si job introuvable. L'appelant doit
     * poller [jobStatus] pour connaître la fin du run.
     */
    suspend fun runJob(jobId: String): CronOpResult = withContext(Dispatchers.IO) {
        val request = postJson("/api/crons/run", JSONObject().put("job_id", jobId))
        try {
            restClient.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string()?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
                    return@withContext CronOpResult.Error(err ?: "HTTP ${response.code}")
                }
                // ok:false + status:"already_running" est un succès HTTP, pas une erreur —
                // le job tourne déjà, l'UI doit juste continuer à poller son statut.
                CronOpResult.Ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "runJob: échec réseau", e)
            CronOpResult.Error(e.message ?: "network error")
        }
    }

    /** POST /api/crons/pause {job_id, reason?} -> {ok:true, job} (non normalisé côté serveur, voir jobStatus/listJobs pour un état à jour). */
    suspend fun pauseJob(jobId: String, reason: String? = null): CronOpResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("job_id", jobId).apply { reason?.let { put("reason", it) } }
        runWriteRequest(postJson("/api/crons/pause", payload))
    }

    /** POST /api/crons/resume {job_id} -> {ok:true, job}. Peut échouer (exception non catchée côté serveur) si le job est un one-shot déjà passé. */
    suspend fun resumeJob(jobId: String): CronOpResult = withContext(Dispatchers.IO) {
        runWriteRequest(postJson("/api/crons/resume", JSONObject().put("job_id", jobId)))
    }

    /**
     * GET /api/crons/status?job_id=X -> (running, elapsedSeconds). Suivi
     * EN MÉMOIRE côté serveur (pas durable/cross-process) — ne reflète que
     * les runs déclenchés via [runJob] sur cette même instance serveur.
     */
    suspend fun jobStatus(jobId: String): Pair<Boolean, Double> = withContext(Dispatchers.IO) {
        val request = authedRequest("/api/crons/status?job_id=$jobId").get().build()
        try {
            restClient.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false to 0.0
                val bodyStr = response.body?.string() ?: return@withContext false to 0.0
                val obj = JSONObject(bodyStr)
                obj.optBoolean("running", false) to obj.optDouble("elapsed", 0.0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "jobStatus: échec réseau", e)
            false to 0.0
        }
    }

    /** GET /api/crons/history?job_id=X&offset=&limit= — métadonnées des runs passés, pas le contenu (voir /output pour le contenu complet). */
    suspend fun jobHistory(jobId: String, offset: Int = 0, limit: Int = 20): List<CronRun> = withContext(Dispatchers.IO) {
        val request = authedRequest("/api/crons/history?job_id=$jobId&offset=$offset&limit=$limit").get().build()
        try {
            restClient.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val arr = JSONObject(bodyStr).optJSONArray("runs") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i -> parseRun(arr.optJSONObject(i)) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "jobHistory: échec réseau", e)
            emptyList()
        }
    }

    /** GET /api/crons/delivery-options — plateformes de livraison disponibles ("local", "origin", telegram, sms, email...). */
    suspend fun deliveryOptions(): List<DeliveryOption> = withContext(Dispatchers.IO) {
        val request = authedRequest("/api/crons/delivery-options").get().build()
        try {
            restClient.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val arr = JSONObject(bodyStr).optJSONArray("platforms") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    val value = obj.optString("value").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    DeliveryOption(value = value, label = obj.optString("label", value))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "deliveryOptions: échec réseau", e)
            emptyList()
        }
    }

    private fun runWriteRequest(request: Request): CronOpResult {
        return try {
            restClient.httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    CronOpResult.Ok
                } else {
                    val bodyStr = response.body?.string()
                    val err = bodyStr?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
                    CronOpResult.Error(err?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "requête cron échouée", e)
            CronOpResult.Error(e.message ?: "network error")
        }
    }

    /**
     * JSONObject.optString() sur une clé dont la valeur JSON est `null`
     * (explicite, pas absente) renvoie la chaîne littérale "null" — pas ""
     * ni une vraie absence. Le serveur envoie beaucoup de champs à `null`
     * explicite (last_error, last_run_at, etc. — voir job dict côté
     * cron/jobs.py), donc le `.takeIf { it.isNotBlank() }` seul ne suffit
     * pas à les filtrer. Bug observé en conditions réelles : "null" affiché
     * tel quel dans l'UI pour un job sans erreur.
     */
    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun parseJob(obj: JSONObject?): CronJob? {
        if (obj == null) return null
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        return CronJob(
            id = id,
            name = obj.optString("name", id),
            prompt = obj.optNullableString("prompt"),
            scheduleDisplay = obj.optString("schedule_display", "?"),
            enabled = obj.optBoolean("enabled", true),
            state = obj.optString("state", "scheduled"),
            deliver = obj.optString("deliver", "local"),
            lastRunAt = obj.optNullableString("last_run_at"),
            lastStatus = obj.optNullableString("last_status"),
            lastError = obj.optNullableString("last_error"),
            nextRunAt = obj.optNullableString("next_run_at")
        )
    }

    private fun parseRun(obj: JSONObject?): CronRun? {
        if (obj == null) return null
        val filename = obj.optString("filename").takeIf { it.isNotBlank() } ?: return null
        val usageObj = obj.optJSONObject("usage")
        val usage = usageObj?.let {
            CronRunUsage(
                model = it.optNullableString("model"),
                durationSeconds = it.optDouble("duration_seconds").takeIf { d -> !d.isNaN() },
                totalTokens = it.optInt("total_tokens", -1).takeIf { t -> t >= 0 },
                estimatedCostUsd = it.optDouble("estimated_cost_usd").takeIf { c -> !c.isNaN() }
            )
        }
        return CronRun(
            filename = filename,
            sizeBytes = obj.optInt("size", 0),
            modifiedAt = obj.optDouble("modified", 0.0),
            usage = usage
        )
    }
}
