package com.hasan.v1

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hasan.v1.utils.MarkdownUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Service de polling SSE pour les notifications push Hermes.
 *
 * Se connecte à GET /v1/sessions/[SESSION_ID]/stream et écoute les
 * événements entrants. Si l'app est au premier plan, diffuse le message
 * via [incomingMessage]. Si l'app est en arrière-plan, affiche une
 * notification Android.
 *
 * Reconnexion avec backoff exponentiel (5s → 60s) en cas d'erreur.
 */
class HassanNotificationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private lateinit var settings: SettingsManager

    // TrustManager permissif identique au client principal (TOFU géré ailleurs)
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val httpClient: OkHttpClient by lazy {
        val sslCtx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), java.security.SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(sslCtx.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIF_ID_SERVICE, buildServiceNotification())
        startPolling()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollingJob?.cancel()
        super.onDestroy()
    }

    // ─── Polling SSE ─────────────────────────────────────────────────────────

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            var backoffMs = BACKOFF_MIN_MS
            while (true) {
                val sessionId = settings.activeSessionId
                val baseUrl   = settings.serverUrl
                val token     = settings.authToken
                if (sessionId.isNullOrBlank() || baseUrl.isBlank()) {
                    delay(10_000)
                    continue
                }
                val url = "${HermesApiClient.buildRootUrl(baseUrl)}/v1/sessions/$sessionId/stream"
                Log.d(TAG, "Connecting to $url")
                try {
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("Accept", "text/event-stream")
                        .get()
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.w(TAG, "HTTP ${response.code}")
                            delay(backoffMs)
                            backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
                            return@use
                        }
                        backoffMs = BACKOFF_MIN_MS
                        val source = response.body?.source() ?: return@use
                        var pendingEvent: String? = null
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.startsWith("event: ") ->
                                    pendingEvent = line.removePrefix("event: ").trim()
                                line.startsWith("data: ") && pendingEvent == "message" -> {
                                    handleIncomingData(line.removePrefix("data: ").trim())
                                    pendingEvent = null
                                }
                                line.startsWith("data: ") -> {
                                    handleIncomingData(line.removePrefix("data: ").trim())
                                    pendingEvent = null
                                }
                                line.isBlank() -> pendingEvent = null
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SSE error: ${e.message}")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
                }
            }
        }
    }

    private fun handleIncomingData(json: String) {
        if (json == "[DONE]" || json.isBlank()) return
        val content = try {
            val obj = JSONObject(json)
            obj.optString("content").takeIf { it.isNotBlank() }
                ?: obj.optString("message").takeIf { it.isNotBlank() }
                ?: obj.optString("text").takeIf { it.isNotBlank() }
        } catch (_: Exception) { null } ?: return

        Log.d(TAG, "Incoming: ${content.take(80)}")

        if (isAppInForeground()) {
            serviceScope.launch(Dispatchers.Main) {
                _incomingMessage.emit(content)
            }
        } else {
            showMessageNotification(content)
        }
    }

    // ─── Foreground check ────────────────────────────────────────────────────

    private fun isAppInForeground(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val tasks = am.getRunningAppProcesses() ?: return false
        return tasks.any {
            it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
            it.processName == packageName
        }
    }

    // ─── Notifications ───────────────────────────────────────────────────────

    private fun showMessageNotification(content: String) {
        val body = MarkdownUtils.stripMarkdown(content).take(100)
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Hasan")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID_MESSAGE, notif)
    }

    private fun buildServiceNotification() =
        NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Hasan")
            .setContentText("En attente de messages…")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // Canal messages entrants — HIGH importance pour les heads-up
        NotificationChannel(
            CHANNEL_MESSAGES,
            "Messages Hasan",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications pour les messages entrants de Hasan"
            nm.createNotificationChannel(this)
        }
        // Canal service persistant — MIN pour rester discret
        NotificationChannel(
            CHANNEL_SERVICE,
            "Hasan — service actif",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Service de réception des messages Hasan"
            nm.createNotificationChannel(this)
        }
    }

    companion object {
        private const val TAG             = "HassanNotifService"
        const val ACTION_STOP             = "com.hasan.v1.NOTIF_STOP"
        private const val NOTIF_ID_SERVICE = 3
        private const val NOTIF_ID_MESSAGE = 4
        private const val CHANNEL_MESSAGES = "hasan_messages"
        private const val CHANNEL_SERVICE  = "hasan_notif_service"
        private const val BACKOFF_MIN_MS   = 5_000L
        private const val BACKOFF_MAX_MS   = 60_000L

        /** Messages reçus quand l'app est au premier plan — collecté par ConversationFragment. */
        private val _incomingMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val incomingMessage = _incomingMessage.asSharedFlow()
    }
}
