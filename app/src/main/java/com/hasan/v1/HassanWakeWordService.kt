package com.hasan.v1

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.WakeWordModel

/**
 * Service foreground d'écoute permanente du wake word via openwakeword-android-kt.
 *
 * Zéro compte externe, zéro clé, zéro appel réseau — inférence 100% locale.
 * La librairie gère le pipeline ONNX (mel → embedding → classificateur) en interne.
 *
 * Cycle de vie :
 *   onCreate  → démarre WakeWordEngine + coroutine de collection
 *   détection → émet wakeWordDetected, cooldown 3s, puis reprise automatique
 *   ACTION_PAUSE  → engine.stop()
 *   ACTION_RESUME → engine.start()
 *   onDestroy → engine.release() + annulation coroutine + libération WakeLock
 */
class HassanWakeWordService : Service() {

    companion object {
        const val ACTION_PAUSE      = "com.hasan.v1.WAKE_WORD_PAUSE"
        const val ACTION_RESUME     = "com.hasan.v1.WAKE_WORD_RESUME"
        const val ACTION_SWAP_MODEL = "com.hasan.v1.WAKE_WORD_SWAP_MODEL"
        const val EXTRA_MODEL_PATH  = "model_path"

        private const val TAG = "HassanWakeWord"

        /** Seuil de détection — augmenter pour réduire les faux positifs. */
        private const val DETECTION_THRESHOLD = 0.5f

        /** Délai avant de reprendre l'écoute après une détection. */
        private const val COOLDOWN_MS = 3_000L

        private const val NOTIFICATION_CHANNEL_ID = "hasan_wake_word"
        private const val NOTIFICATION_ID = 1001

        // Bus d'événements wake word — collecté par MainActivity pour déclencher le STT
        private val _wakeWordDetected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val wakeWordDetected = _wakeWordDetected.asSharedFlow()
    }

    private var engine: WakeWordEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Scope dédié au service — annulé dans onDestroy
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Empêche le redémarrage du moteur quand un pause explicite est en cours
    @Volatile private var enginePaused = false

    // Job de la coroutine de collecte des détections — annulé au swap
    private var detectionJob: kotlinx.coroutines.Job? = null

    // ─────────────────────────── Cycle de vie ────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        acquireWakeLock()
        val model = getSharedPreferences("hasan_prefs", MODE_PRIVATE)
            .getString("wake_word_model", SettingsManager.DEFAULT_WAKE_WORD_MODEL)
            ?: SettingsManager.DEFAULT_WAKE_WORD_MODEL
        startEngine(model)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE  -> {
                enginePaused = true
                engine?.stop()
            }
            ACTION_RESUME -> {
                enginePaused = false
                if (hasAudioPermission()) engine?.start()
                else Log.w(TAG, "ACTION_RESUME ignoré — permission RECORD_AUDIO non accordée")
            }
            ACTION_SWAP_MODEL -> {
                val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: return START_STICKY
                swapModel(modelPath)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.release()
        engine = null
        serviceScope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    // ─────────────────────────── Moteur wake word ────────────────────────────

    private fun startEngine(modelPath: String) {
        val modelName = modelPath.removeSuffix(".onnx")
        val models = listOf(
            WakeWordModel(
                name      = modelName,
                modelPath = modelPath,
                threshold = DETECTION_THRESHOLD,
            )
        )

        engine = WakeWordEngine(
            context             = this,
            models              = models,
            detectionCooldownMs = COOLDOWN_MS,
            scope               = serviceScope,
        )

        // Collecte les détections — job annulable pour le hot-swap
        detectionJob = serviceScope.launch {
            engine!!.detections.collect { detection ->
                if (enginePaused) return@collect
                Log.i(TAG, "Wake word détecté — modèle=${detection.model.name} score=${"%.3f".format(detection.score)}")
                onWakeWordDetected()
            }
        }

        if (hasAudioPermission()) {
            engine!!.start()
            Log.i(TAG, "WakeWordEngine démarré — modèle=$modelPath seuil=$DETECTION_THRESHOLD")
        } else {
            Log.i(TAG, "WakeWordEngine initialisé — en attente de la permission RECORD_AUDIO")
        }
    }

    /** Hot-swap : libère l'engine courant et recrée avec le nouveau modèle sans tuer le service. */
    private fun swapModel(modelPath: String) {
        Log.i(TAG, "Swap modèle → $modelPath")
        detectionJob?.cancel()
        engine?.release()
        engine = null
        startEngine(modelPath)
    }

    private fun onWakeWordDetected() {
        // Émet l'événement — MainViewModel collecte ce flow et gère l'arrêt du TTS
        _wakeWordDetected.tryEmit(Unit)
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    // ─────────────────────────── WakeLock ────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        // PARTIAL_WAKE_LOCK : maintient le CPU actif, écran éteint autorisé
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hasan:WakeWordLock")
            .also { it.acquire(10 * 60 * 60 * 1_000L) }
    }

    // ─────────────────────────── Notification foreground ─────────────────────

    private fun createNotificationChannel() {
        NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Hasan — écoute",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            setSound(null, null)
        }.also {
            getSystemService(NotificationManager::class.java).createNotificationChannel(it)
        }
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Hasan")
            .setContentText("En écoute…")
            .setContentIntent(tap)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
