package com.hasan.v1.webui.models

/**
 * Demande d'approbation en attente côté serveur (tools/approval.py) — le
 * serveur bloque l'exécution d'une commande sensible jusqu'à réponse via
 * POST /api/approval/respond. Source unique : GET /api/approval/stream
 * (voir [com.hasan.v1.webui.WebUiApprovalStream]), remplace l'event
 * `approval` autrefois reçu inline dans /api/chat/stream (retiré, faisait
 * doublon).
 */
data class PendingApproval(
    val approvalId: String,
    val sessionId: String,
    val command: String,
    val description: String,
    val patternKeys: List<String>
)

/** Choix possibles pour POST /api/approval/respond (tools/approval.py). */
enum class ApprovalChoice(val wireValue: String) {
    ONCE("once"),
    SESSION("session"),
    ALWAYS("always"),
    DENY("deny")
}
