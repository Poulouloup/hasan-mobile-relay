package com.hasan.v1

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Exécute une capability demandée via le canal bridge et retourne un résultat JSON.
 * La vérification des permissions runtime est faite en amont, côté
 * [com.hasan.v1.network.BridgeCommandHandler] — cette classe suppose les
 * permissions déjà accordées.
 */
class CapabilityExecutor(private val context: Context) {

    suspend fun execute(capability: String, params: JSONObject): CapabilityResult {
        val schema = ALL_CAPABILITIES.find { it.name == capability }?.parameters ?: emptyList()
        when (val validation = validateParams(schema, params)) {
            is ParamValidationResult.Invalid -> return CapabilityResult.Error(validation.message)
            ParamValidationResult.Valid -> Unit
        }
        return executeInternal(capability, params)
    }

    private suspend fun executeInternal(capability: String, params: JSONObject): CapabilityResult = try {
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
            "get_network_info"   -> getNetworkInfo()
            "get_device_info"    -> getDeviceInfo()
            "toggle_flashlight"  -> toggleFlashlight(params)
            "get_calendar_events" -> getCalendarEvents(params)
            "get_clipboard"      -> getClipboard()
            "set_clipboard"      -> setClipboard(params)
            "make_call"          -> makeCall(params)
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

    /**
     * Demande une position FRAÎCHE (requestSingleUpdate, timeout 10s) plutôt que de
     * lire le cache (getLastKnownLocation) : le cache peut être vieux de plusieurs
     * heures/jours ou carrément absent (jamais sollicité depuis un redémarrage), ce
     * qui rendait "où suis-je" peu fiable côté Hermes.
     *
     * GPS et réseau sont sollicités EN PARALLÈLE plutôt que GPS seul en priorité :
     * un fix GPS matériel met couramment >10s en intérieur (satellites difficiles à
     * capter), alors que NETWORK_PROVIDER (triangulation cell/Wi-Fi, moins précis
     * mais typiquement 1-3s) répond largement dans la fenêtre de timeout — sans le
     * lancer en parallèle, un GPS indoor lent faisait systématiquement retomber sur
     * le cache (observé en conditions réelles : timeout 10s complet, fallback sur
     * une position vieille de 12h). Le premier résultat valide (n'importe lequel des
     * deux) est utilisé — network en intérieur est largement suffisant pour "où
     * suis-je approximativement".
     *
     * Fallback sur le cache uniquement si aucun des deux ne répond dans le délai
     * (dernier recours, mieux qu'une erreur sèche), avec un flag "stale" pour que
     * l'appelant sache que ce n'est pas une position à jour.
     */
    private suspend fun getLocation(): CapabilityResult {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { locationManager.isProviderEnabled(it) }

        val fresh = if (providers.isEmpty()) null else requestFreshLocation(locationManager, providers)
        if (fresh != null) {
            return CapabilityResult.Success(JSONObject().apply {
                put("lat", fresh.latitude)
                put("lng", fresh.longitude)
                put("accuracy", fresh.accuracy)
                put("stale", false)
            })
        }

        @Suppress("DEPRECATION")
        val cached = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: return CapabilityResult.Error("Aucune position connue (GPS/réseau indisponible, et aucune position en cache)")

        return CapabilityResult.Success(JSONObject().apply {
            put("lat", cached.latitude)
            put("lng", cached.longitude)
            put("accuracy", cached.accuracy)
            put("stale", true)
            put("age_ms", System.currentTimeMillis() - cached.time)
        })
    }

    /** Lance une requête sur chaque provider de [providers], retourne le premier résultat non-null. */
    private suspend fun requestFreshLocation(locationManager: LocationManager, providers: List<String>): Location? =
        withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val listeners = mutableListOf<LocationListener>()
                fun cleanup() = listeners.forEach { locationManager.removeUpdates(it) }
                cont.invokeOnCancellation { cleanup() }

                providers.forEach { provider ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            cleanup()
                            if (cont.isActive) cont.resume(location)
                        }
                    }
                    listeners.add(listener)
                    try {
                        @Suppress("DEPRECATION")
                        locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    } catch (e: SecurityException) {
                        // Continue avec les autres providers de la liste plutôt que d'abandonner.
                    }
                }
            }
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

    // ─── get_network_info ─────────────────────────────────────────────────────

    @Suppress("DEPRECATION", "MissingPermission")
    private fun getNetworkInfo(): CapabilityResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }

        if (caps == null) {
            return CapabilityResult.Success(JSONObject().apply { put("connected", false) })
        }

        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        val result = JSONObject().apply { put("connected", true) }

        when {
            isWifi -> {
                result.put("type", "wifi")
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                val ssid = wifiInfo.ssid?.removeSurrounding("\"")?.takeIf { it != "<unknown ssid>" } ?: ""
                val rssi = wifiInfo.rssi
                val ipInt = wifiInfo.ipAddress
                val ip = "%d.%d.%d.%d".format(ipInt and 0xff, (ipInt shr 8) and 0xff, (ipInt shr 16) and 0xff, (ipInt shr 24) and 0xff)
                result.put("ssid", ssid)
                result.put("ip", ip)
                result.put("rssi_dbm", rssi)
                result.put("signal_level", WifiManager.calculateSignalLevel(rssi, 5))
            }
            isCellular -> {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val networkType = when (tm.networkType) {
                    TelephonyManager.NETWORK_TYPE_NR -> "5G"
                    TelephonyManager.NETWORK_TYPE_LTE -> "4G (LTE)"
                    TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA -> "3G (HSPA)"
                    TelephonyManager.NETWORK_TYPE_UMTS -> "3G (UMTS)"
                    TelephonyManager.NETWORK_TYPE_EDGE -> "2G (EDGE)"
                    TelephonyManager.NETWORK_TYPE_GPRS -> "2G (GPRS)"
                    else -> "cellular"
                }
                result.put("type", networkType)
                result.put("operator", tm.networkOperatorName ?: "")
                result.put("roaming", tm.isNetworkRoaming)
                val signalStrength = caps.signalStrength
                if (signalStrength != Int.MIN_VALUE) result.put("signal_dbm", signalStrength)
            }
            else -> result.put("type", "other")
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

    // ─── toggle_flashlight ──────────────────────────────────────────────────

    private fun toggleFlashlight(params: JSONObject): CapabilityResult {
        if (!params.has("on")) return CapabilityResult.Error("Paramètre 'on' manquant")
        val on = params.optBoolean("on")

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return CapabilityResult.Error("Aucune lampe torche disponible sur cet appareil")

        return try {
            cameraManager.setTorchMode(cameraId, on)
            CapabilityResult.Success(JSONObject().put("on", on))
        } catch (e: CameraAccessException) {
            CapabilityResult.Error("Lampe torche indisponible (caméra utilisée par une autre app ?)")
        }
    }

    // ─── get_calendar_events ────────────────────────────────────────────────

    /**
     * Sans "date" : comportement historique, les prochains événements à partir de
     * maintenant. Avec "date" (YYYY-MM-DD) : fenêtre centrée sur cette date, largeur
     * "range_days" de chaque côté (défaut 1 jour = la journée seule, max 30) — permet
     * "qu'est-ce que j'ai autour du 15 août" plutôt que seulement "mes prochains
     * événements". "limit" reste un plafond de résultats appliqué dans les deux cas.
     */
    private fun getCalendarEvents(params: JSONObject): CapabilityResult {
        val limit = params.optInt("limit", 10).coerceIn(1, 50)
        val dateParam = params.optString("date").takeIf { it.isNotBlank() }

        val (rangeStart, rangeEnd) = if (dateParam != null) {
            val pivot = try {
                java.time.LocalDate.parse(dateParam)
            } catch (e: java.time.format.DateTimeParseException) {
                return CapabilityResult.Error("Paramètre 'date' invalide (format attendu : YYYY-MM-DD)")
            }
            val rangeDays = params.optInt("range_days", 1).coerceIn(0, 30)
            val zone = java.time.ZoneId.systemDefault()
            val start = pivot.minusDays(rangeDays.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = pivot.plusDays(rangeDays.toLong() + 1).atStartOfDay(zone).toInstant().toEpochMilli()
            start to end
        } else {
            System.currentTimeMillis() to Long.MAX_VALUE
        }

        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION
            ),
            "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ?",
            arrayOf(rangeStart.toString(), rangeEnd.toString()),
            "${CalendarContract.Events.DTSTART} ASC LIMIT $limit"
        ) ?: return CapabilityResult.Error("Impossible d'accéder au calendrier")

        val array = JSONArray()
        cursor.use {
            val titleCol = it.getColumnIndex(CalendarContract.Events.TITLE)
            val startCol = it.getColumnIndex(CalendarContract.Events.DTSTART)
            val endCol = it.getColumnIndex(CalendarContract.Events.DTEND)
            val locationCol = it.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
            while (it.moveToNext()) {
                array.put(JSONObject().apply {
                    put("title", it.getString(titleCol) ?: "")
                    put("start_ms", it.getLong(startCol))
                    put("end_ms", it.getLong(endCol))
                    put("location", it.getString(locationCol) ?: "")
                })
            }
        }
        return CapabilityResult.Success(JSONObject().apply {
            put("events", array)
            put("count", array.length())
        })
    }

    // ─── get_clipboard / set_clipboard ──────────────────────────────────────

    private fun getClipboard(): CapabilityResult {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?: ""
        return CapabilityResult.Success(JSONObject().put("text", text))
    }

    private fun setClipboard(params: JSONObject): CapabilityResult {
        val text = params.optString("text").takeIf { it.isNotBlank() }
            ?: return CapabilityResult.Error("Paramètre 'text' manquant")

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Hasan", text))
        return CapabilityResult.Success(JSONObject().put("status", "copied"))
    }

    // ─── make_call ──────────────────────────────────────────────────────────

    private fun makeCall(params: JSONObject): CapabilityResult {
        val to = params.optString("to").takeIf { it.isNotBlank() }
            ?: return CapabilityResult.Error("Paramètre 'to' manquant")

        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$to")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        return CapabilityResult.Success(JSONObject().apply {
            put("status", "calling")
            put("to", to)
        })
    }

    companion object {
        private const val CHANNEL_MCP_MESSAGES = "hasan_mcp_messages"
        private const val NOTIF_ID_MCP_MESSAGE = 5
        private const val LOCATION_TIMEOUT_MS = 10_000L
    }
}

sealed class CapabilityResult {
    data class Success(val data: JSONObject) : CapabilityResult()
    data class Error(val message: String) : CapabilityResult()
    object PermissionDenied : CapabilityResult()
}
