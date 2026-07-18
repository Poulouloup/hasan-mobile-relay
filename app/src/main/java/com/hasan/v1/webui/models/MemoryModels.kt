package com.hasan.v1.webui.models

/**
 * Contenu des 3 fichiers markdown qui forment le "cerveau" persistant de
 * Hermes (voir GET /api/memory, api/routes.py `_handle_memory_read`) —
 * MEMORY.md, USER.md, SOUL.md dans <HERMES_HOME>/memories/ (SOUL.md à la
 * racine du profil). Lecture seule dans cette étape — le serveur expose
 * aussi POST /api/memory/write (remplacement complet d'un fichier),
 * délibérément non implémenté ici.
 */
data class HermesMemory(
    val memory: String,
    val user: String,
    val soul: String
)

/** GET /api/insights?days=N — statistiques d'usage agrégées, lecture seule (voir api/routes.py `_handle_insights`). */
data class InsightsSummary(
    val periodDays: Int,
    val totalSessions: Int,
    val totalMessages: Int,
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val totalTokens: Int,
    val totalCacheHitPercent: Int?,
    val totalCost: Double,
    val models: List<ModelInsight>,
    val dailyTokens: List<DailyInsight>,
    val activityByDay: List<DayActivity>,
    val activityByHour: List<HourActivity>
)

/** Un élément de `models[]` — répartition d'usage par modèle LLM. */
data class ModelInsight(
    val model: String,
    val sessions: Int,
    val totalTokens: Int,
    val cost: Double,
    val costShare: Int
)

/** Un élément de `daily_tokens[]` — série temporelle par jour calendaire. */
data class DailyInsight(
    val date: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val sessions: Int,
    val cost: Double
)

/** Un élément de `activity_by_day[]` — activité par jour de la semaine (Mon..Sun). */
data class DayActivity(
    val day: String,
    val sessions: Int
)

/** Un élément de `activity_by_hour[]` — activité par heure de la journée (0-23). */
data class HourActivity(
    val hour: Int,
    val sessions: Int
)
