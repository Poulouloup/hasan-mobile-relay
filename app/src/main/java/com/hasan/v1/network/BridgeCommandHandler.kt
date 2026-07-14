package com.hasan.v1.network

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.hasan.v1.ALL_CAPABILITIES
import com.hasan.v1.CapabilityExecutor
import com.hasan.v1.CapabilityResult
import com.hasan.v1.SettingsManager
import com.hasan.v1.network.models.Envelope
import org.json.JSONObject

/**
 * Exécute les commandes reçues sur le canal `bridge` du relay WebSocket (voir
 * server/relay/bridge_commands.py côté serveur, plugin/tools/android_tool.py
 * côté Hermes) et renvoie le résultat via la même connexion.
 *
 * S'exécute dans MainViewModel — l'app est nécessairement au premier plan ou
 * en arrière-plan récent pour qu'une commande arrive (déclenchée par une
 * conversation Hermes en cours), donc pas de confirmation par notification
 * (contrairement à l'ancien orchestrateur MCP tiers, qui tournait sans UI) :
 * une capability est simplement refusée si l'utilisateur ne l'a pas
 * explicitement activée dans les réglages (voir settings.isCapabilityEnabled).
 */
class BridgeCommandHandler(
    private val context: Context,
    private val settings: SettingsManager,
    private val send: (Envelope) -> Boolean
) {
    private val executor = CapabilityExecutor(context)

    fun handle(envelope: Envelope) {
        if (envelope.type != "command") return
        val payload = envelope.payload
        val commandId = payload.optString("command_id").takeIf { it.isNotBlank() } ?: return
        val capability = payload.optString("capability").takeIf { it.isNotBlank() }
        val params = payload.optJSONObject("params") ?: JSONObject()

        if (capability == null) {
            respond(commandId, error = "missing_capability")
            return
        }
        if (!settings.isCapabilityEnabled(capability)) {
            respond(commandId, error = "capability_disabled")
            return
        }
        val permission = ALL_CAPABILITIES.find { it.name == capability }?.permission
        if (permission != null &&
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        ) {
            respond(commandId, error = "permission_denied")
            return
        }

        when (val result = executor.execute(capability, params)) {
            is CapabilityResult.Success -> respond(commandId, data = result.data)
            is CapabilityResult.Error -> respond(commandId, error = result.message)
            CapabilityResult.PermissionDenied -> respond(commandId, error = "permission_denied")
        }
    }

    private fun respond(commandId: String, data: JSONObject? = null, error: String? = null) {
        val result = data ?: JSONObject().apply { put("error", error) }
        val envelope = Envelope(
            channel = "bridge",
            type = "command_result",
            payload = JSONObject().apply {
                put("command_id", commandId)
                put("result", result)
            }
        )
        send(envelope)
    }
}
