package com.hasan.v1.webui.models

/**
 * Sous-ensemble typé des skills hermes-webui. Vérifié contre le code réel du
 * serveur (~/hermes-webui/api/routes.py `_skills_list_from_dir`,
 * `_skill_view_from_file`, agent/skill_utils.py `parse_frontmatter`) — une
 * skill est un dossier contenant un SKILL.md avec frontmatter YAML,
 * convention identique aux skills Claude Code.
 */
data class SkillSummary(
    val name: String,
    val description: String,
    val category: String?,
    val disabled: Boolean
)

/** Détail d'une skill — GET /api/skills/content?name=X (sans le paramètre file). */
data class SkillDetail(
    val name: String,
    val description: String,
    val tags: List<String>,
    val relatedSkills: List<String>,
    val content: String,
    val linkedFiles: Map<String, List<String>>
)

/**
 * Statistiques d'usage agrégées — GET /api/skills/usage. Écrites par
 * hermes-agent, lues seules par hermes-webui (et par ce client) : compteurs
 * cumulés, pas un historique d'événements horodaté.
 */
data class SkillUsage(
    val useCount: Int,
    val viewCount: Int,
    val patchCount: Int
)
