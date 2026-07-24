package com.hasan.v1

import android.Manifest

/**
 * Modèle et registre des capabilities exposées par cet appareil au LLM Hermes via le
 * canal `bridge` du relay WebSocket (voir BridgeCommandHandler.kt / CapabilityExecutor.kt).
 * Anciennement défini dans McpFragment.kt (retiré à l'étape "Tools & Permissions" — l'UI
 * est désormais 100% Compose, voir ToolsPermissionsFragment.kt / ToolsPermissionsScreen.kt),
 * déplacé ici pour ne plus dépendre d'un Fragment particulier.
 */
data class Capability(
    val name: String,
    val iconRes: Int,
    val labelRes: Int,
    val descriptionRes: Int,
    val authRequiredDefault: Boolean,
    val permission: String?,
    val enabled: Boolean = false,
    /** Schéma des paramètres attendus — voir CapabilitySchema.kt. Vide si la capability ne prend aucun paramètre. */
    val parameters: List<ParamSpec> = emptyList()
)

/** Accessible aussi depuis CapabilityExecutor pour la validation des paramètres avant exécution. */
val ALL_CAPABILITIES = listOf(
    Capability("get_battery",      R.drawable.ic_cap_battery,      R.string.mcp_cap_get_battery_label,      R.string.mcp_cap_get_battery_desc,      false, null),
    Capability("send_sms",         R.drawable.ic_cap_sms,          R.string.mcp_cap_send_sms_label,         R.string.mcp_cap_send_sms_desc,         true,  Manifest.permission.SEND_SMS,
        parameters = listOf(
            ParamSpec("to", ParamType.STRING, required = true, description = "Numéro de téléphone du destinataire"),
            ParamSpec("message", ParamType.STRING, required = true, description = "Contenu du SMS")
        )),
    Capability("get_location",     R.drawable.ic_cap_location,     R.string.mcp_cap_get_location_label,     R.string.mcp_cap_get_location_desc,     true,  Manifest.permission.ACCESS_FINE_LOCATION),
    Capability("send_notification",R.drawable.ic_cap_notification, R.string.mcp_cap_send_notification_label,R.string.mcp_cap_send_notification_desc, false, null,
        parameters = listOf(
            ParamSpec("title", ParamType.STRING, required = false, description = "Titre de la notification (défaut: Hasan)"),
            ParamSpec("body", ParamType.STRING, required = true, description = "Texte de la notification")
        )),
    Capability("set_volume",       R.drawable.ic_cap_volume,       R.string.mcp_cap_set_volume_label,       R.string.mcp_cap_set_volume_desc,       false, null,
        parameters = listOf(
            ParamSpec("level", ParamType.INT, required = true, description = "Volume cible, 0 à 100")
        )),
    Capability("launch_app",       R.drawable.ic_cap_launch_app,   R.string.mcp_cap_launch_app_label,       R.string.mcp_cap_launch_app_desc,       false, null,
        parameters = listOf(
            ParamSpec("package_name", ParamType.STRING, required = true, description = "Nom de package Android à lancer")
        )),
    Capability("discover_apps",    R.drawable.ic_cap_discover_apps,R.string.mcp_cap_discover_apps_label,    R.string.mcp_cap_discover_apps_desc,    false, null),
    Capability("get_contacts",     R.drawable.ic_cap_contacts,     R.string.mcp_cap_get_contacts_label,     R.string.mcp_cap_get_contacts_desc,     true,  Manifest.permission.READ_CONTACTS,
        parameters = listOf(
            ParamSpec("query", ParamType.STRING, required = false, description = "Filtre sur le nom du contact"),
            ParamSpec("limit", ParamType.INT, required = false, description = "Nombre maximum de résultats (défaut 20, max 100)")
        )),
    Capability("set_alarm",        R.drawable.ic_cap_alarm,        R.string.mcp_cap_set_alarm_label,        R.string.mcp_cap_set_alarm_desc,        false, null,
        parameters = listOf(
            ParamSpec("hour", ParamType.INT, required = true, description = "Heure (0-23)"),
            ParamSpec("minute", ParamType.INT, required = true, description = "Minute (0-59)"),
            ParamSpec("label", ParamType.STRING, required = false, description = "Libellé de l'alarme (défaut: Hasan)")
        )),
    Capability("get_network_info",    R.drawable.ic_cap_wifi,         R.string.mcp_cap_get_network_info_label,    R.string.mcp_cap_get_network_info_desc,    false, null),
    Capability("get_device_info",  R.drawable.ic_cap_device_info,  R.string.mcp_cap_get_device_info_label,  R.string.mcp_cap_get_device_info_desc,  false, null),
    Capability("toggle_flashlight",R.drawable.ic_cap_flashlight,   R.string.mcp_cap_toggle_flashlight_label,R.string.mcp_cap_toggle_flashlight_desc, false, Manifest.permission.CAMERA,
        parameters = listOf(
            ParamSpec("on", ParamType.BOOLEAN, required = true, description = "true pour allumer, false pour éteindre")
        )),
    Capability("get_calendar_events", R.drawable.ic_cap_calendar,  R.string.mcp_cap_get_calendar_events_label, R.string.mcp_cap_get_calendar_events_desc, true, Manifest.permission.READ_CALENDAR,
        parameters = listOf(
            ParamSpec("date", ParamType.STRING, required = false, description = "Date pivot au format YYYY-MM-DD (défaut : maintenant, liste les prochains événements)"),
            ParamSpec("range_days", ParamType.INT, required = false, description = "Nombre de jours avant/après la date pivot à inclure (défaut 1 = la journée seule, max 30 — ignoré sans 'date')"),
            ParamSpec("limit", ParamType.INT, required = false, description = "Nombre maximum d'événements (défaut 10, max 50)")
        )),
    Capability("get_clipboard",    R.drawable.ic_cap_clipboard,    R.string.mcp_cap_get_clipboard_label,    R.string.mcp_cap_get_clipboard_desc,    true,  null),
    Capability("set_clipboard",    R.drawable.ic_cap_clipboard,    R.string.mcp_cap_set_clipboard_label,    R.string.mcp_cap_set_clipboard_desc,    false, null,
        parameters = listOf(
            ParamSpec("text", ParamType.STRING, required = true, description = "Texte à copier dans le presse-papier")
        )),
    Capability("make_call",        R.drawable.ic_cap_call,         R.string.mcp_cap_make_call_label,        R.string.mcp_cap_make_call_desc,        true,  Manifest.permission.CALL_PHONE,
        parameters = listOf(
            ParamSpec("to", ParamType.STRING, required = true, description = "Numéro de téléphone à appeler")
        ))
)
