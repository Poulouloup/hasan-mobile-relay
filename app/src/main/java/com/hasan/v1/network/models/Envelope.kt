package com.hasan.v1.network.models

import org.json.JSONObject
import java.util.UUID

/**
 * Enveloppe JSON multiplexée entre l'app et le relay server.
 *
 * Format : {version, channel, type, id, payload}. `version` est obligatoire
 * dès le départ pour permettre de faire évoluer le protocole plus tard sans
 * casser silencieusement la compatibilité entre app et serveur — même
 * contrat que côté serveur (voir server/relay/envelope.py).
 */
data class Envelope(
    val channel: String,
    val type: String,
    val payload: JSONObject,
    val version: Int = PROTOCOL_VERSION,
    val id: String = UUID.randomUUID().toString()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("channel", channel)
        put("type", type)
        put("id", id)
        put("payload", payload)
    }

    override fun toString(): String = toJson().toString()

    companion object {
        const val PROTOCOL_VERSION = 1

        val CHANNELS = setOf("system", "chat", "proactive", "bridge")

        /** Parse une enveloppe reçue. Retourne null si le format est invalide (log côté appelant). */
        fun fromJson(raw: String): Envelope? {
            return try {
                val obj = JSONObject(raw)
                fromJsonObject(obj)
            } catch (_: Exception) {
                null
            }
        }

        fun fromJsonObject(obj: JSONObject): Envelope? {
            if (!obj.has("version") || obj.optInt("version", -1) != PROTOCOL_VERSION) return null

            val channel = obj.optString("channel", "")
            if (channel !in CHANNELS) return null

            val type = obj.optString("type", "")
            if (type.isBlank()) return null

            val payload = obj.optJSONObject("payload") ?: return null

            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

            return Envelope(channel = channel, type = type, payload = payload, version = PROTOCOL_VERSION, id = id)
        }
    }
}
