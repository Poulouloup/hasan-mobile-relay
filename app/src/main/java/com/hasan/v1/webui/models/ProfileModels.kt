package com.hasan.v1.webui.models

/**
 * Un profil Hermes — instance HERMES_HOME distincte (config/skills/
 * workspace séparés). Vérifié en conditions réelles contre le serveur
 * (GET /api/profiles) — sur le VPS de test, un seul profil ("default")
 * existe (single_profile_mode=false mais liste à un seul élément).
 */
data class HermesProfile(
    val name: String,
    val isActive: Boolean,
    val isDefault: Boolean,
    val model: String?,
    val provider: String?,
    val skillCount: Int
)
