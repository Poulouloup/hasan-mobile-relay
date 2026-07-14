package com.hasan.v1

import org.json.JSONObject

/** Type attendu d'un paramètre de capability — reflète les types JSON Schema utilisés par hermes-relay (plugin/tools/_SCHEMAS). */
enum class ParamType { STRING, INT, FLOAT, BOOLEAN }

/** Déclaration d'un paramètre attendu par une capability, façon JSON Schema function-calling. */
data class ParamSpec(
    val name: String,
    val type: ParamType,
    val required: Boolean,
    val description: String = ""
)

/**
 * Résultat de validation des paramètres reçus contre un [ParamSpec] — permet à
 * [CapabilityExecutor] de rejeter un appel malformé avec un message uniforme
 * avant de dispatcher, au lieu du parsing manuel optString/optInt sans garde-fou.
 */
sealed class ParamValidationResult {
    object Valid : ParamValidationResult()
    data class Invalid(val message: String) : ParamValidationResult()
}

/** Valide `params` contre `schema` — vérifie présence des champs requis et type de chaque champ fourni. */
fun validateParams(schema: List<ParamSpec>, params: JSONObject): ParamValidationResult {
    for (spec in schema) {
        val has = params.has(spec.name) && !params.isNull(spec.name)
        if (spec.required && !has) {
            return ParamValidationResult.Invalid("Paramètre '${spec.name}' manquant")
        }
        if (!has) continue

        val typeOk = when (spec.type) {
            ParamType.STRING -> params.opt(spec.name) is String
            ParamType.INT -> params.opt(spec.name).let { it is Int || it is Long }
            ParamType.FLOAT -> params.opt(spec.name).let { it is Int || it is Long || it is Double || it is Float }
            ParamType.BOOLEAN -> params.opt(spec.name) is Boolean
        }
        if (!typeOk) {
            return ParamValidationResult.Invalid("Paramètre '${spec.name}' invalide (type ${spec.type.name.lowercase()} attendu)")
        }
    }
    return ParamValidationResult.Valid
}

/** Sérialise un schéma de paramètres au format JSON Schema minimal, envoyé au serveur via register()/updateCapabilities(). */
fun schemaToJson(schema: List<ParamSpec>): JSONObject {
    val properties = JSONObject()
    val required = org.json.JSONArray()
    schema.forEach { spec ->
        properties.put(spec.name, JSONObject().apply {
            put("type", when (spec.type) {
                ParamType.STRING -> "string"
                ParamType.INT -> "integer"
                ParamType.FLOAT -> "number"
                ParamType.BOOLEAN -> "boolean"
            })
            if (spec.description.isNotBlank()) put("description", spec.description)
        })
        if (spec.required) required.put(spec.name)
    }
    return JSONObject().apply {
        put("type", "object")
        put("properties", properties)
        put("required", required)
    }
}
