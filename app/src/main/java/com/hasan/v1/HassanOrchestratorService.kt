package com.hasan.v1

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Service foreground de connexion à l'orchestrateur MCP.
 *
 * Long-polling vers GET /commands : exécute les capabilities activées et
 * renvoie le résultat via POST /results. Heartbeat périodique (30s WiFi /
 * 60s 4G) avec ré-enregistrement si l'orchestrateur signale un changement
 * de capabilities.
 *
 * Limitation connue : la confirmation des commandes `auth_required` ne peut
 * pas passer par un dialog bloquant (pas d'Activity depuis un service). Elle
 * est faite via une notification à actions "Autoriser"/"Refuser" qui relance
 * ce service avec ACTION_CONFIRM_APPROVE/DENY (PendingIntent.getService —
 * un BroadcastReceiver dynamique RECEIVER_NOT_EXPORTED ne reçoit pas les
 * broadcasts émis par un PendingIntent sur Android 13+).
 */
class HassanOrchestratorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private var heartbeatJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var settings: SettingsManager
    private lateinit var apiClient: OrchestratorApiClient
    private lateinit var executor: CapabilityExecutor

    // Commandes auth_required en attente de confirmation utilisateur
    private val pendingConfirmations = mutableMapOf<String, PendingCommand>()

    // ─────────────────────────── Cycle de vie ────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(applicationContext)
        apiClient = OrchestratorApiClient(settings)
        executor = CapabilityExecutor(applicationContext)
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONFIRM_APPROVE, ACTION_CONFIRM_DENY -> {
                val commandId = intent.getStringExtra(EXTRA_COMMAND_ID) ?: return START_STICKY
                val approved = intent.action == ACTION_CONFIRM_APPROVE
                Log.d(TAG, "onStartCommand confirm: commandId=$commandId approved=$approved")
                getSystemService(NotificationManager::class.java).cancel(commandId.hashCode())
                serviceScope.launch { handleConfirmation(commandId, approved) }
                return START_STICKY
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID_PERSISTENT, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID_PERSISTENT, buildNotification())
        }
        startPolling()
        startHeartbeat()
        return START_STICKY
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        heartbeatJob?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─────────────────────────── Polling commandes ────────────────────────────

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            var backoffMs = BACKOFF_MIN_MS
            while (true) {
                if (settings.orchestratorUrl.isBlank()) {
                    delay(10_000)
                    continue
                }
                when (val result = apiClient.pollCommands(timeoutSec = 55)) {
                    is CommandResult.Ok -> {
                        backoffMs = BACKOFF_MIN_MS
                        handleCommand(result)
                    }
                    is CommandResult.Empty -> {
                        backoffMs = BACKOFF_MIN_MS
                    }
                    is CommandResult.ServerError -> {
                        Log.w(TAG, "pollCommands HTTP ${result.code} — backoff ${backoffMs}ms")
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
                    }
                    is CommandResult.NetworkError -> {
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
                    }
                }
            }
        }
    }

    private suspend fun handleCommand(command: CommandResult.Ok) {
        Log.d(TAG, "handleCommand: id=${command.commandId} action=${command.capability} authRequired=${command.authRequired} params=${command.params}")
        if (!settings.isCapabilityEnabled(command.capability)) {
            Log.w(TAG, "handleCommand: capability '${command.capability}' désactivée")
            apiClient.postResult(command.commandId, "failed", error = "capability_disabled")
            return
        }

        if (command.authRequired) {
            pendingConfirmations[command.commandId] = PendingCommand(command.capability, command.params)
            showConfirmationNotification(command.commandId, command.capability)
            return
        }

        executeAndPostResult(command.commandId, command.capability, command.params)
    }

    private suspend fun handleConfirmation(commandId: String, approved: Boolean) {
        val pending = pendingConfirmations.remove(commandId)
        if (pending == null) {
            Log.w(TAG, "handleConfirmation: commandId=$commandId introuvable dans pendingConfirmations")
            return
        }
        Log.d(TAG, "handleConfirmation: id=$commandId approved=$approved capability=${pending.capability}")
        apiClient.confirm(commandId, approved)
        if (!approved) {
            apiClient.postResult(commandId, "failed", error = "user_denied")
            return
        }
        executeAndPostResult(commandId, pending.capability, pending.params)
    }

    private suspend fun executeAndPostResult(commandId: String, capability: String, params: JSONObject) {
        val permission = PERMISSIONS_BY_CAPABILITY[capability]
        if (permission != null &&
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "executeAndPostResult: permission $permission manquante pour $capability")
            apiClient.postResult(commandId, "failed", error = "permission_denied")
            return
        }

        Log.d(TAG, "executeAndPostResult: exécution de $capability params=$params")
        when (val result = executor.execute(capability, params)) {
            is CapabilityResult.Success -> {
                Log.d(TAG, "executeAndPostResult: succès $capability data=${result.data}")
                apiClient.postResult(commandId, "done", data = result.data)
            }
            is CapabilityResult.Error -> {
                Log.w(TAG, "executeAndPostResult: erreur $capability — ${result.message}")
                apiClient.postResult(commandId, "failed", error = result.message)
            }
            is CapabilityResult.PermissionDenied -> {
                Log.w(TAG, "executeAndPostResult: permission refusée pour $capability")
                apiClient.postResult(commandId, "failed", error = "permission_denied")
            }
        }
    }

    // ─────────────────────────── Heartbeat ─────────────────────────────────

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (true) {
                delay(if (isOnWifi()) HEARTBEAT_WIFI_MS else HEARTBEAT_CELLULAR_MS)
                if (settings.orchestratorUrl.isBlank()) continue

                when (val result = apiClient.heartbeat()) {
                    is HeartbeatResult.Ok -> {
                        if (result.capabilitiesRefreshNeeded) {
                            val caps = settings.getCapabilities()
                            val register = apiClient.register(settings.orchestratorDeviceName, caps)
                            if (register is RegisterResult.Ok) {
                                register.sessionToken?.let { settings.orchestratorSessionToken = it }
                            }
                        }
                    }
                    is HeartbeatResult.ServerError -> Log.w(TAG, "heartbeat HTTP ${result.code}")
                    is HeartbeatResult.NetworkError -> Log.w(TAG, "heartbeat network error")
                }
            }
        }
    }

    private fun isOnWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    // ─────────────────────────── WakeLock ────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hasan:OrchestratorLock")
            .also { it.acquire(10 * 60 * 60 * 1_000L) }
    }

    // ─────────────────────────── Notifications ────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // Canal "écoute" partagé avec HassanWakeWordService — créé ici aussi au cas
        // où ce service démarre en premier (idempotent).
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WAKE_WORD, "Hasan — écoute", NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MCP_CONFIRM, "Hasan — confirmations MCP", NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        // Même ID et canal que HassanWakeWordService/HassanNotificationService
        // → une seule notification "En écoute…" visible dans le tiroir.
        return NotificationCompat.Builder(this, CHANNEL_WAKE_WORD)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Hasan")
            .setContentText("En écoute…")
            .setContentIntent(tap)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showConfirmationNotification(commandId: String, capability: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            // Pas de notification possible — rejette par défaut
            serviceScope.launch { handleConfirmation(commandId, approved = false) }
            return
        }

        val approveIntent = Intent(this, HassanOrchestratorService::class.java).apply {
            action = ACTION_CONFIRM_APPROVE
            putExtra(EXTRA_COMMAND_ID, commandId)
        }
        val denyIntent = Intent(this, HassanOrchestratorService::class.java).apply {
            action = ACTION_CONFIRM_DENY
            putExtra(EXTRA_COMMAND_ID, commandId)
        }
        val approvePending = PendingIntent.getService(
            this, commandId.hashCode(), approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val denyPending = PendingIntent.getService(
            this, commandId.hashCode() + 1, denyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_MCP_CONFIRM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Confirmation requise")
            .setContentText("L'orchestrateur demande : $capability")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, "Autoriser", approvePending)
            .addAction(0, "Refuser", denyPending)
            .build()

        getSystemService(NotificationManager::class.java).notify(commandId.hashCode(), notif)
    }

    private data class PendingCommand(val capability: String, val params: JSONObject)

    companion object {
        private const val TAG = "HassanOrchestrator"

        const val ACTION_STOP = "com.hasan.v1.ORCHESTRATOR_STOP"
        private const val ACTION_CONFIRM_APPROVE = "com.hasan.v1.ORCHESTRATOR_CONFIRM_APPROVE"
        private const val ACTION_CONFIRM_DENY = "com.hasan.v1.ORCHESTRATOR_CONFIRM_DENY"
        private const val EXTRA_COMMAND_ID = "command_id"

        // Même ID/canal que HassanWakeWordService et HassanNotificationService
        // → une seule notification persistante "En écoute…" dans le tiroir.
        private const val NOTIF_ID_PERSISTENT = 1001
        private const val CHANNEL_WAKE_WORD = "hasan_wake_word"
        private const val CHANNEL_MCP_CONFIRM = "hasan_mcp_confirm"

        private const val BACKOFF_MIN_MS = 5_000L
        private const val BACKOFF_MAX_MS = 60_000L
        private const val HEARTBEAT_WIFI_MS = 30_000L
        private const val HEARTBEAT_CELLULAR_MS = 60_000L

        /** Permissions runtime requises par capability — null si aucune permission spécifique. */
        val PERMISSIONS_BY_CAPABILITY: Map<String, String> = mapOf(
            "send_sms" to Manifest.permission.SEND_SMS,
            "get_location" to Manifest.permission.ACCESS_FINE_LOCATION,
            "get_contacts" to Manifest.permission.READ_CONTACTS
        )
    }
}
