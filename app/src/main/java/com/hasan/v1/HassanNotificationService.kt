package com.hasan.v1

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID_PERSISTENT, buildPersistentNotification())
        startPolling()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollingJob?.cancel()
        super.onDestroy()
    }

    // ─── Polling SSE ─────────────────────────────────────────────────────────
    // L'endpoint /api/sessions/{id}/stream n'existe pas sur ce Hermes.
    // Le polling est désactivé — notifyMessage() reste utilisable depuis le ViewModel.
    private fun startPolling() {
        Log.d(TAG, "Polling SSE désactivé (endpoint non disponible sur ce serveur)")
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
        showNotification(this, content)
    }

    private fun buildPersistentNotification() =
        NotificationCompat.Builder(this, CHANNEL_WAKE_WORD)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Hasan")
            .setContentText("En écoute…")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // Canal messages uniquement — le canal wake word est géré par HassanWakeWordService
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                "Messages Hasan",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications pour les messages entrants de Hasan"
                enableVibration(true)
                enableLights(true)
            }
        )
    }

    companion object {
        private const val TAG              = "HassanNotifService"
        const val ACTION_STOP              = "com.hasan.v1.NOTIF_STOP"
        // Même ID et canal que HassanWakeWordService → une seule notif visible dans le tiroir
        private const val NOTIF_ID_PERSISTENT = 1001
        private const val CHANNEL_WAKE_WORD   = "hasan_wake_word"
        private const val NOTIF_ID_MESSAGE    = 4
        private const val CHANNEL_MESSAGES    = "hasan_messages_v2"
        private const val BACKOFF_MIN_MS      = 5_000L
        private const val BACKOFF_MAX_MS      = 60_000L

        private val _incomingMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val incomingMessage = _incomingMessage.asSharedFlow()

        /** Affiche une notification de message — appelable depuis n'importe quel contexte. */
        fun notifyMessage(context: Context, content: String) {
            showNotification(context, content)
        }

        private fun showNotification(context: Context, content: String) {
            // Vérifier la permission POST_NOTIFICATIONS (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "POST_NOTIFICATIONS permission manquante — notif ignorée")
                return
            }

            val nm = context.getSystemService(NotificationManager::class.java)

            // Créer le canal si absent (idempotent)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MESSAGES,
                    "Messages Hasan",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications pour les messages entrants de Hasan"
                    enableVibration(true)
                    enableLights(true)
                }
            )

            val body = MarkdownUtils.stripMarkdown(content).take(200)
            val tapIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Nouveau message")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(tapIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()

            nm.notify(NOTIF_ID_MESSAGE, notif)
            Log.d(TAG, "Notification envoyée : ${body.take(60)}")
        }
    }
}
