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
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Service de polling SSE pour les notifications push Hermes.
 *
 * Service normal (non-foreground) — pas de notification persistante.
 * Se connecte à GET /api/sessions/[SESSION_ID]/stream et écoute les
 * événements entrants.
 *   - App au premier plan : diffuse via [incomingMessage] (SharedFlow).
 *   - App en arrière-plan : affiche une notification Android heads-up.
 *
 * Reconnexion avec backoff exponentiel (5s → 60s).
 */
class HassanNotificationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private lateinit var settings: SettingsManager

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
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startPolling()
        // START_NOT_STICKY : Android ne redémarre pas le service si tué
        return START_NOT_STICKY
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
                val url = "${HermesApiClient.buildRootUrl(baseUrl)}/api/sessions/$sessionId/stream"
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
                            Log.w(TAG, "HTTP ${response.code} — backoff ${backoffMs}ms")
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

    // ─── Détection premier plan ───────────────────────────────────────────────

    private fun isAppInForeground(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return am.runningAppProcesses?.any {
            it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
            it.processName == packageName
        } == true
    }

    // ─── Notification message ─────────────────────────────────────────────────

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
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID_MESSAGE, notif)
    }

    private fun createNotificationChannel() {
        NotificationChannel(
            CHANNEL_MESSAGES,
            "Messages Hasan",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications pour les messages entrants de Hasan"
            getSystemService(NotificationManager::class.java).createNotificationChannel(this)
        }
    }

    companion object {
        private const val TAG              = "HassanNotifService"
        const val ACTION_STOP              = "com.hasan.v1.NOTIF_STOP"
        private const val NOTIF_ID_MESSAGE = 4
        private const val CHANNEL_MESSAGES = "hasan_messages"
        private const val BACKOFF_MIN_MS   = 5_000L
        private const val BACKOFF_MAX_MS   = 60_000L

        private val _incomingMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val incomingMessage = _incomingMessage.asSharedFlow()
    }
}
