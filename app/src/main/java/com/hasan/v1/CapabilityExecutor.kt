package com.hasan.v1

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
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
            "get_contacts"       -> getContacts(params)
            "set_alarm"          -> setAlarm(params)
            "get_wifi_info"      -> getWifiInfo()
            "get_device_info"    -> getDeviceInfo()
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

        @Suppress("DEPRECATION")
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
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

    // ─── get_contacts ────────────────────────────────────────────────────────

    private fun getContacts(params: JSONObject): CapabilityResult {
        val query = params.optString("query").trim()
        val limit = params.optInt("limit", 20).coerceIn(1, 100)

        val selection = if (query.isNotBlank()) {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        } else null
        val selectionArgs = if (query.isNotBlank()) arrayOf("%$query%") else null

        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            selection,
            selectionArgs,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC LIMIT $limit"
        ) ?: return CapabilityResult.Error("Impossible d'accéder aux contacts")

        val array = JSONArray()
        cursor.use {
            val nameCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                array.put(JSONObject().apply {
                    put("name", it.getString(nameCol) ?: "")
                    put("number", it.getString(numberCol) ?: "")
                })
            }
        }
        return CapabilityResult.Success(JSONObject().apply {
            put("contacts", array)
            put("count", array.length())
        })
    }

    // ─── set_alarm ───────────────────────────────────────────────────────────

    private fun setAlarm(params: JSONObject): CapabilityResult {
        val hour = params.optInt("hour", -1)
        val minute = params.optInt("minute", -1)
        if (hour !in 0..23) return CapabilityResult.Error("Paramètre 'hour' invalide (0-23 attendu)")
        if (minute !in 0..59) return CapabilityResult.Error("Paramètre 'minute' invalide (0-59 attendu)")
        val label = params.optString("label").takeIf { it.isNotBlank() } ?: "Hasan"

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        return CapabilityResult.Success(JSONObject().apply {
            put("status", "set")
            put("hour", hour)
            put("minute", minute)
            put("label", label)
        })
    }

    // ─── get_wifi_info ────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun getWifiInfo(): CapabilityResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = network?.let { cm.getNetworkCapabilities(it) }
        val connected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val result = JSONObject().apply {
            put("connected", connected)
        }

        if (connected) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ssid = wifiInfo.ssid?.removeSurrounding("\"") ?: ""
            val rssi = wifiInfo.rssi
            val signalLevel = WifiManager.calculateSignalLevel(rssi, 5)
            val ipInt = wifiInfo.ipAddress
            val ip = "%d.%d.%d.%d".format(
                ipInt and 0xff,
                (ipInt shr 8) and 0xff,
                (ipInt shr 16) and 0xff,
                (ipInt shr 24) and 0xff
            )
            result.put("ssid", ssid)
            result.put("ip", ip)
            result.put("rssi_dbm", rssi)
            result.put("signal_level", signalLevel)
        }

        return CapabilityResult.Success(result)
    }

    // ─── get_device_info ──────────────────────────────────────────────────────

    private fun getDeviceInfo(): CapabilityResult {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val totalStorage = android.os.Environment.getExternalStorageDirectory().totalSpace
        val freeStorage = android.os.Environment.getExternalStorageDirectory().freeSpace

        val packageInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) { null }

        return CapabilityResult.Success(JSONObject().apply {
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android_version", Build.VERSION.RELEASE)
            put("api_level", Build.VERSION.SDK_INT)
            put("ram_total_mb", memInfo.totalMem / 1024 / 1024)
            put("ram_available_mb", memInfo.availMem / 1024 / 1024)
            put("storage_total_gb", totalStorage / 1024 / 1024 / 1024)
            put("storage_free_gb", freeStorage / 1024 / 1024 / 1024)
            put("app_version", packageInfo?.versionName ?: "unknown")
            put("interactive", pm.isInteractive)
        })
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
