package com.hasan.v1.webui

import com.hasan.v1.webui.models.DailyInsight
import com.hasan.v1.webui.models.DayActivity
import com.hasan.v1.webui.models.HermesMemory
import com.hasan.v1.webui.models.HourActivity
import com.hasan.v1.webui.models.InsightsSummary
import com.hasan.v1.webui.models.ModelInsight
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client REST en LECTURE SEULE pour Memory & Insights hermes-webui
 * (GET /api/memory, GET /api/insights). Schéma vérifié contre le code
 * source réel du serveur (api/routes.py `_handle_memory_read`/
 * `_handle_insights`) — le serveur expose aussi POST /api/memory/write
 * (édition), délibérément non implémenté ici (écran lecture seule pour
 * cette étape, voir le prompt de migration).
 *
 * Réutilise [WebUiRestClient] (cookie de session, TOFU, client OkHttp)
 * comme [WebUiSkillsClient]/[WebUiCronClient].
 */
class WebUiMemoryClient(private val restClient: WebUiRestClient) {

    private fun authedRequest(path: String): Request.Builder {
        val builder = Request.Builder().url("${restClient.baseUrl()}$path")
        restClient.currentCookie()?.let { builder.addHeader("Cookie", it) }
        return builder
    }

    /**
     * optString() sur une clé JSON `null` explicite renvoie la chaîne
     * littérale "null", pas "" — bug confirmé en conditions réelles côté
     * cron jobs (voir WebUiCronClient.optNullableString), même précaution
     * ici par cohérence.
     */
    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (isNull(key)) return null
        return optInt(key)
    }

    /** GET /api/memory — contenu brut de MEMORY.md/USER.md/SOUL.md. */
    suspend fun getMemory(): WebUiCallResult<HermesMemory> {
        val request = authedRequest("/api/memory").get().build()
        return restClient.executeAuthed(request) { bodyStr ->
            val obj = JSONObject(bodyStr)
            HermesMemory(
                memory = obj.optNullableString("memory") ?: "",
                user = obj.optNullableString("user") ?: "",
                soul = obj.optNullableString("soul") ?: ""
            )
        }
    }

    /** GET /api/insights?days=N — statistiques d'usage agrégées (défaut 30 jours, borné [1,365] côté serveur). */
    suspend fun getInsights(days: Int = 30): WebUiCallResult<InsightsSummary> {
        val request = authedRequest("/api/insights?days=$days").get().build()
        return restClient.executeAuthed(request) { bodyStr -> parseInsights(JSONObject(bodyStr)) }
    }

    private fun parseInsights(obj: JSONObject): InsightsSummary {
        val modelsArr = obj.optJSONArray("models") ?: JSONArray()
        val dailyArr = obj.optJSONArray("daily_tokens") ?: JSONArray()
        val dayArr = obj.optJSONArray("activity_by_day") ?: JSONArray()
        val hourArr = obj.optJSONArray("activity_by_hour") ?: JSONArray()
        return InsightsSummary(
            periodDays = obj.optInt("period_days", 30),
            totalSessions = obj.optInt("total_sessions", 0),
            totalMessages = obj.optInt("total_messages", 0),
            totalInputTokens = obj.optInt("total_input_tokens", 0),
            totalOutputTokens = obj.optInt("total_output_tokens", 0),
            totalTokens = obj.optInt("total_tokens", 0),
            totalCacheHitPercent = obj.optNullableInt("total_cache_hit_percent"),
            totalCost = obj.optDouble("total_cost", 0.0),
            models = (0 until modelsArr.length()).mapNotNull { i -> parseModelInsight(modelsArr.optJSONObject(i)) },
            dailyTokens = (0 until dailyArr.length()).mapNotNull { i -> parseDailyInsight(dailyArr.optJSONObject(i)) },
            activityByDay = (0 until dayArr.length()).mapNotNull { i -> parseDayActivity(dayArr.optJSONObject(i)) },
            activityByHour = (0 until hourArr.length()).mapNotNull { i -> parseHourActivity(hourArr.optJSONObject(i)) }
        )
    }

    private fun parseModelInsight(obj: JSONObject?): ModelInsight? {
        if (obj == null) return null
        val model = obj.optString("model").takeIf { it.isNotBlank() } ?: return null
        return ModelInsight(
            model = model,
            sessions = obj.optInt("sessions", 0),
            totalTokens = obj.optInt("total_tokens", 0),
            cost = obj.optDouble("cost", 0.0),
            costShare = obj.optInt("cost_share", 0)
        )
    }

    private fun parseDailyInsight(obj: JSONObject?): DailyInsight? {
        if (obj == null) return null
        val date = obj.optString("date").takeIf { it.isNotBlank() } ?: return null
        return DailyInsight(
            date = date,
            inputTokens = obj.optInt("input_tokens", 0),
            outputTokens = obj.optInt("output_tokens", 0),
            sessions = obj.optInt("sessions", 0),
            cost = obj.optDouble("cost", 0.0)
        )
    }

    private fun parseDayActivity(obj: JSONObject?): DayActivity? {
        if (obj == null) return null
        val day = obj.optString("day").takeIf { it.isNotBlank() } ?: return null
        return DayActivity(day = day, sessions = obj.optInt("sessions", 0))
    }

    private fun parseHourActivity(obj: JSONObject?): HourActivity? {
        if (obj == null) return null
        return HourActivity(hour = obj.optInt("hour", 0), sessions = obj.optInt("sessions", 0))
    }
}
