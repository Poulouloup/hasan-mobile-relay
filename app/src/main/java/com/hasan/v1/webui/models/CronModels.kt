package com.hasan.v1.webui.models

/**
 * Sous-ensemble typé des cron jobs hermes-webui. Vérifié contre le code
 * réel du serveur (~/.hermes/hermes-agent/cron/jobs.py `create_job`/
 * `_normalize_job_record`, api/routes.py `_cron_job_for_api`) — pas de
 * classe CronJob côté serveur, juste un dict Python normalisé. Ne modélise
 * que les champs utiles à l'écran Tasks, pas les ~30 champs internes
 * (fire_claim/run_claim, etc.).
 */
data class CronJob(
    val id: String,
    val name: String,
    val prompt: String?,
    val scheduleDisplay: String,
    val enabled: Boolean,
    val state: String,
    val deliver: String,
    val lastRunAt: String?,
    val lastStatus: String?,
    val lastError: String?,
    val nextRunAt: String?
)

/** Un run passé (métadonnées seulement, pas le contenu — voir GET /api/crons/history). */
data class CronRun(
    val filename: String,
    val sizeBytes: Int,
    val modifiedAt: Double,
    val usage: CronRunUsage?
)

/** Extrait du front-matter markdown d'un run — champs absents si non trouvés dans le fichier (peut être vide). */
data class CronRunUsage(
    val model: String?,
    val durationSeconds: Double?,
    val totalTokens: Int?,
    val estimatedCostUsd: Double?
)

/** Une option de livraison — GET /api/crons/delivery-options ("local", "origin", ou une plateforme de messagerie). */
data class DeliveryOption(val value: String, val label: String)

/** Résultat d'une opération d'écriture (create/update/delete/run/pause/resume). */
sealed class CronOpResult {
    object Ok : CronOpResult()
    data class Error(val message: String) : CronOpResult()
}
