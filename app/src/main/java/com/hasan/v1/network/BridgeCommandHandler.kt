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
 * conversation Hermes en cours). Pour les capabilities marquées "confirmation
 * requise" (voir settings.isCapabilityAuthRequired), [requestConfirmation] doit
 * afficher une UI et attendre la réponse de l'utilisateur — auparavant la
 * commande était refusée inconditionnellement avec "confirmation_required" car
 * aucune UI de confirmation n'existait, ce qui laissait Hermes croire (à tort)
 * qu'une notification système existait quelque part pour l'utilisateur.
 */
class BridgeCommandHandler(
    private val context: Context,
    private val settings: SettingsManager,
    private val activityLog: ActivityLog,
    private val send: (Envelope) -> Boolean,
    /** Affiche une UI de confirmation et suspend jusqu'à la réponse de l'utilisateur (true = autorisé). */
    private val requestConfirmation: suspend (capability: String, params: JSONObject) -> Boolean
) {
    private val executor = CapabilityExecutor(context)

    suspend fun handle(envelope: Envelope) {
        if (envelope.type != "command") return
        val payload = envelope.payload
        val commandId = payload.optString("command_id").takeIf { it.isNotBlank() } ?: return
        val capability = payload.optString("capability").takeIf { it.isNotBlank() }
        val params = payload.optJSONObject("params") ?: JSONObject()

        if (capability == null) {
            respond(commandId, capability = null, error = "missing_capability")
            return
        }
        if (!settings.isCapabilityEnabled(capability)) {
            respond(commandId, capability = capability, error = "capability_disabled")
            return
        }
        val permission = ALL_CAPABILITIES.find { it.name == capability }?.permission
        if (permission != null &&
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        ) {
            respond(commandId, capability = capability, error = "permission_denied")
            return
        }
        val authRequiredDefault = ALL_CAPABILITIES.find { it.name == capability }?.authRequiredDefault ?: false
        if (settings.isCapabilityAuthRequired(capability, authRequiredDefault)) {
            val authorized = requestConfirmation(capability, params)
            if (!authorized) {
                respond(commandId, capability = capability, error = "confirmation_denied")
                return
            }
        }

        when (val result = executor.execute(capability, params)) {
            is CapabilityResult.Success -> respond(commandId, capability = capability, data = result.data)
            is CapabilityResult.Error -> respond(commandId, capability = capability, error = result.message)
            CapabilityResult.PermissionDenied -> respond(commandId, capability = capability, error = "permission_denied")
        }
    }

    private fun respond(commandId: String, capability: String?, data: JSONObject? = null, error: String? = null) {
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
        activityLog.log(activityTitleFor(capability, error), tag = "AUTH")
    }

    private fun activityTitleFor(capability: String?, error: String?): String = when (error) {
        null -> "Bridge OK : $capability"
        "missing_capability" -> "Bridge refusé : capability manquante"
        "capability_disabled" -> "Bridge refusé ($capability) : capability désactivée"
        "permission_denied" -> "Bridge refusé ($capability) : permission manquante"
        "confirmation_denied" -> "Bridge refusé ($capability) : confirmation utilisateur refusée"
        else -> "Bridge erreur ($capability) : $error"
    }
}
