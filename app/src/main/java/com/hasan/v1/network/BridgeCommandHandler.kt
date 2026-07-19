package com.hasan.v1.network

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.hasan.v1.ALL_CAPABILITIES
import com.hasan.v1.CapabilityExecutor
import com.hasan.v1.CapabilityResult
import com.hasan.v1.SettingsManager
import com.hasan.v1.network.models.Envelope
import com.hasan.v1.utils.LatencyLog
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

        // Log exhaustif de chaque commande bridge reçue et de son issue — auparavant
        // seul activityLog (écran "Activité" de l'app) recevait ce log, invisible dans
        // latency.log/adb pull, ce qui a rendu très difficile le diagnostic du chemin
        // send_sms qui semblait ne jamais atteindre ce handler (voir archive/2026-07-16-
        // bridge-mcp-confirmation-bypass.md).
        LatencyLog.mark("BRIDGE_COMMAND", commandId, "capability=$capability params=$params")

        if (capability == null) {
            respond(commandId, capability = null, error = "missing_capability")
            return
        }
        if (!settings.isCapabilityEnabled(capability)) {
            LatencyLog.mark("BRIDGE_REJECTED", commandId, "capability=$capability reason=capability_disabled")
            respond(commandId, capability = capability, error = "capability_disabled_by_user")
            return
        }
        val permission = ALL_CAPABILITIES.find { it.name == capability }?.permission
        if (permission != null &&
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        ) {
            LatencyLog.mark("BRIDGE_REJECTED", commandId, "capability=$capability reason=permission_denied permission=$permission")
            respond(commandId, capability = capability, error = "permission_denied")
            return
        }
        val authRequiredDefault = ALL_CAPABILITIES.find { it.name == capability }?.authRequiredDefault ?: false
        if (settings.isCapabilityAuthRequired(capability, authRequiredDefault)) {
            LatencyLog.mark("BRIDGE_CONFIRMATION_SHOWN", commandId, "capability=$capability")
            val authorized = requestConfirmation(capability, params)
            LatencyLog.mark("BRIDGE_CONFIRMATION_RESULT", commandId, "capability=$capability authorized=$authorized")
            if (!authorized) {
                respond(commandId, capability = capability, error = "confirmation_denied")
                return
            }
        }

        when (val result = executor.execute(capability, params)) {
            is CapabilityResult.Success -> {
                LatencyLog.mark("BRIDGE_SUCCESS", commandId, "capability=$capability data=${result.data}")
                respond(commandId, capability = capability, data = result.data)
            }
            is CapabilityResult.Error -> {
                LatencyLog.mark("BRIDGE_ERROR", commandId, "capability=$capability message=${result.message}")
                respond(commandId, capability = capability, error = result.message)
            }
            CapabilityResult.PermissionDenied -> {
                LatencyLog.mark("BRIDGE_REJECTED", commandId, "capability=$capability reason=permission_denied_at_execution")
                respond(commandId, capability = capability, error = "permission_denied")
            }
        }
    }

    private fun respond(commandId: String, capability: String?, data: JSONObject? = null, error: String? = null) {
        // Un code d'erreur brut ("capability_disabled_by_user") remonte tel quel jusqu'au
        // LLM via le MCP (phone-relay-mcp/server.js fait un JSON.stringify sans
        // reformulation) — un champ "message" explicite évite que le LLM interprète à
        // tort un code ambigu comme un problème de config/permission à corriger plutôt
        // qu'un choix délibéré et permanent de l'utilisateur (Réglages → Tools & Permissions).
        val result = data ?: JSONObject().apply {
            put("error", error)
            if (error == "capability_disabled_by_user") {
                put(
                    "message",
                    "L'utilisateur a volontairement désactivé cette fonctionnalité dans les réglages de l'app. " +
                        "Ce n'est pas un problème technique — ne pas suggérer de vérifier une config ou une permission."
                )
            }
        }
        val envelope = Envelope(
            channel = "bridge",
            type = "command_result",
            payload = JSONObject().apply {
                put("command_id", commandId)
                put("result", result)
            }
        )
        val sendOk = send(envelope)
        LatencyLog.mark("BRIDGE_RESPOND", commandId, "capability=$capability error=$error sendOk=$sendOk")
        activityLog.log(activityTitleFor(capability, error), tag = "AUTH")
    }

    private fun activityTitleFor(capability: String?, error: String?): String = when (error) {
        null -> "Bridge OK : $capability"
        "missing_capability" -> "Bridge refusé : capability manquante"
        "capability_disabled_by_user" -> "Bridge refusé ($capability) : capability désactivée par l'utilisateur"
        "permission_denied" -> "Bridge refusé ($capability) : permission manquante"
        "confirmation_denied" -> "Bridge refusé ($capability) : confirmation utilisateur refusée"
        else -> "Bridge erreur ($capability) : $error"
    }
}
