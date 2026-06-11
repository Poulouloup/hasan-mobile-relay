package com.hasan.v1

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.media.AudioManager
import android.os.BatteryManager
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exécute une capability demandée par l'orchestrateur et retourne un résultat JSON.
 * La vérification des permissions runtime est faite en amont, côté
 * [HassanOrchestratorService] — cette classe suppose les permissions déjà accordées.
 */
class CapabilityExecutor(private val context: Context) {

    fun execute(capability: String, params: JSONObject): CapabilityResult = try {
        when (capability) {
            "get_battery"        -> getBattery()
            "send_sms"           -> sendSms(params)
            "get_location"       -> getLocation()
            "send_notification"  -> sendNotification(params)
            "set_volume"         -> setVolume(params)
            "launch_app"         -> launchApp(params)
            "discover_apps"      -> discoverApps()
            else -> CapabilityResult.Error("Capability inconnue : $capability")
        }
    } catch (e: Exception) {
        CapabilityResult.Error(e.message ?: "Erreur inconnue")
    }

    // ─── get_battery ────────────────────────────────────────────────────────

    private fun getBattery(): CapabilityResult {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return CapabilityResult.Success(JSONObject().apply {
            put("level", level)
            put("charging", charging)
        })
    }

    // ─── send_sms ───────────────────────────────────────────────────────────

    private fun sendSms(params: JSONObject): CapabilityResult {
        val to = params.optString("to").takeIf { it.isNotBlank() }
            ?: return CapabilityResult.Error("Paramètre 'to' manquant")
        val message = params.optString("message").takeIf { it.isNotBlank() }
            ?: return CapabilityResult.Error("Paramètre 'message' manquant")

        val smsManager = context.getSystemService(SmsManager::class.java)
        smsManager.sendTextMessage(to, null, message, null, null)
        return CapabilityResult.Success(JSONObject().apply {
            put("status", "sent")
            put("to", to)
        })
    }

    // ─── get_location ───────────────────────────────────────────────────────

    private fun getLocation(): CapabilityResult {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: return CapabilityResult.Error("Aucune position connue")

        return CapabilityResult.Success(JSONObject().apply {
            put("lat", location.latitude)
            put("lng", location.longitude)
            put("accuracy", location.accuracy)
        })
    }

    // ─── send_notification ──────────────────────────────────────────────────

    private fun sendNotification(params: JSONObject): CapabilityResult {
        val title = params.optString("title").takeIf { it.isNotBlank() } ?: "Hasan"
        val body = params.optString("body").takeIf { it.isNotBlank() }
            ?: return CapabilityResult.Error("Paramètre 'body' manquant")

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MCP_MESSAGES,
                "Messages orchestrateur",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications envoyées par l'orchestrateur MCP"
            }
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_MCP_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(NOTIF_ID_MCP_MESSAGE, notif)

        return CapabilityResult.Success(JSONObject().put("status", "shown"))
    }

    // ─── set_volume ─────────────────────────────────────────────────────────

    private fun setVolume(params: JSONObject): CapabilityResult {
        val level = params.optInt("level", -1)
        if (level !in 0..100) return CapabilityResult.Error("Paramètre 'level' invalide (0-100 attendu)")

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVolume = (level * maxVolume / 100f).toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)

        return CapabilityResult.Success(JSONObject().put("volume", level))
    }

    // ─── launch_app ─────────────────────────────────────────────────────────

    private fun launchApp(params: JSONObject): CapabilityResult {
        val packageName = params.optString("package_name").takeIf { it.isNotBlank() }
            ?: return CapabilityResult.Error("Paramètre 'package_name' manquant")

        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return CapabilityResult.Error("Application introuvable : $packageName")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)

        return CapabilityResult.Success(JSONObject().apply {
            put("status", "launched")
            put("app", packageName)
        })
    }

    // ─── discover_apps ──────────────────────────────────────────────────────

    private fun discoverApps(): CapabilityResult {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = context.packageManager.queryIntentActivities(launcherIntent, 0)
        val array = JSONArray()
        apps.forEach { resolveInfo ->
            array.put(JSONObject().apply {
                put("package_name", resolveInfo.activityInfo.packageName)
                put("label", resolveInfo.loadLabel(context.packageManager).toString())
            })
        }
        return CapabilityResult.Success(JSONObject().put("apps", array))
    }

    companion object {
        private const val CHANNEL_MCP_MESSAGES = "hasan_mcp_messages"
        private const val NOTIF_ID_MCP_MESSAGE = 5
    }
}

sealed class CapabilityResult {
    data class Success(val data: JSONObject) : CapabilityResult()
    data class Error(val message: String) : CapabilityResult()
    object PermissionDenied : CapabilityResult()
}
