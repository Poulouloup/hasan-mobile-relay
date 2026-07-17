package com.hasan.v1.webui.models

/**
 * Sous-ensemble typé du catalogue de modèles hermes-webui. Vérifié en
 * conditions réelles contre le serveur (GET /api/models) — attention,
 * `configured_model_badges` du payload réel est un OBJET (clé = nom de
 * modèle), pas un tableau comme une première lecture du code serveur le
 * suggérait ; non modélisé ici de toute façon (metadata secondaire, pas
 * nécessaire pour un simple picker par tour).
 */
data class ModelOption(val id: String, val label: String)

data class ModelGroup(
    val providerLabel: String,
    val providerId: String,
    val models: List<ModelOption>
)

data class ModelsCatalog(
    val defaultModel: String?,
    val activeProvider: String?,
    val groups: List<ModelGroup>
)
