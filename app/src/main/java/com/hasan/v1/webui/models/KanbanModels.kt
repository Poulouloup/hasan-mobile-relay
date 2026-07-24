package com.hasan.v1.webui.models

/**
 * Sous-ensemble typé du Kanban hermes-webui. Vérifié contre le code réel du
 * serveur (~/hermes-webui/api/kanban_bridge.py, dataclass Task dans
 * ~/.hermes/hermes-agent/hermes_cli/kanban_db.py) — API déjà consommée par
 * le frontend web statique de hermes-webui, jamais par l'app avant ça.
 *
 * Colonnes fixes côté serveur, identiques pour tous les boards (pas de
 * personnalisation possible) — voir BOARD_COLUMNS dans kanban_bridge.py.
 */
val KANBAN_COLUMNS = listOf("triage", "todo", "ready", "running", "blocked", "done")

/**
 * Le serveur rejette toute tentative de PATCH status="running" (HTTP 400) —
 * ce statut est réservé au protocole de claim du dispatcher, jamais un
 * déplacement manuel. Utilisé côté client pour éviter l'aller-retour réseau
 * inutile et donner un message d'erreur clair avant même d'envoyer la requête.
 */
const val KANBAN_STATUS_RUNNING = "running"

data class KanbanTask(
    val id: String,
    val title: String,
    val body: String?,
    val assignee: String?,
    val status: String,
    val priority: Int,
    val tenant: String?,
    val createdAt: Long?,
    val ageSeconds: Long?,
    val commentCount: Int,
    val parentCount: Int,
    val childCount: Int,
    val result: String?
)

data class KanbanColumn(
    val name: String,
    val tasks: List<KanbanTask>
)

/** GET /api/kanban/board — état complet d'un board. */
data class KanbanBoard(
    val columns: List<KanbanColumn>,
    val tenants: List<String>,
    val assignees: List<String>
)

/** Entrée de GET /api/kanban/boards — métadonnées d'un board, pas son contenu. */
data class KanbanBoardSummary(
    val slug: String,
    val name: String?,
    val description: String?,
    val icon: String?,
    val color: String?,
    val archived: Boolean
)
