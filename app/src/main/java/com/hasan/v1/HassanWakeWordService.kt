package com.hasan.v1

import android.Manifest
import android.app.ActivityManager
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
import com.hasan.v1.network.ChatStreamHandler
import com.hasan.v1.network.ConnectionManager
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
        const val ACTION_STOP       = "com.hasan.v1.WAKE_WORD_STOP"
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

        // Émis par WakeWordPipeline (arrière-plan) avec l'ID de la conversation mise à jour —
        // collecté par MainViewModel pour resynchroniser resumedConversationId/currentConversationId
        private val _conversationUpdated = MutableSharedFlow<Long>(extraBufferCapacity = 1)
        val conversationUpdated = _conversationUpdated.asSharedFlow()

        /** Notifie qu'une conversation a été mise à jour depuis le pipeline background. */
        fun notifyConversationUpdated(conversationId: Long) {
            _conversationUpdated.tryEmit(conversationId)
        }
    }

    private var engine: WakeWordEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Scope dédié au service — annulé dans onDestroy
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Scope thread principal — requis par SpeechRecognizer/TextToSpeech (pipeline background)
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Pipeline STT → Hermes → TTS utilisé quand l'app est en arrière-plan
    private var wakeWordPipeline: WakeWordPipeline? = null

    // Connexion WS dédiée à ce service, indépendante de celle de MainViewModel
    // (contextes de vie différents — l'app peut être tuée en premier plan
    // pendant que le service continue en arrière-plan, ou l'inverse). Créée
    // paresseusement au premier wake word détecté en arrière-plan (voir
    // ensureConnection()), PAS dans onCreate() qui tourne dès le lancement de
    // l'app — sinon deux sockets WS ouvertes en permanence pour rien la
    // plupart du temps. Une fois ouverte, reste connectée tant que le service
    // vit (fermer/rouvrir un WS à chaque tour ajouterait une latence
    // perceptible à chaque interaction vocale) — fermée seulement dans
    // onDestroy().
    private var connectionManager: ConnectionManager? = null
    private var chatStreamHandler: ChatStreamHandler? = null

    private fun ensureConnection(): ChatStreamHandler {
        val existing = chatStreamHandler
        if (existing != null) {
            connectionManager?.connect() // idempotent, no-op si déjà connecté/en cours
            return existing
        }
        val cm = ConnectionManager(SettingsManager(this))
        connectionManager = cm
        cm.connect()
        return ChatStreamHandler(cm, cm.multiplexer).also { chatStreamHandler = it }
    }

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
            ACTION_STOP -> {
                // Arret propre demande explicitement — stopForeground avant stopSelf
                // pour eviter le redemarrage START_STICKY
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.release()
        engine = null
        serviceScope.cancel()
        wakeWordPipeline?.release()
        wakeWordPipeline = null
        connectionManager?.disconnect()
        connectionManager = null
        chatStreamHandler = null
        mainScope.cancel()
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
        if (isAppInForeground()) {
            // App au premier plan — ConversationFragment gère le pipeline complet
            _wakeWordDetected.tryEmit(Unit)
            return
        }

        // App en arrière-plan — pipeline autonome dans le service
        if (!hasAudioPermission()) {
            Log.w(TAG, "Wake word détecté en arrière-plan mais permission RECORD_AUDIO manquante")
            return
        }

        enginePaused = true
        engine?.stop()

        val handler = ensureConnection()

        mainScope.launch {
            val pipeline = wakeWordPipeline ?: WakeWordPipeline(
                context = this@HassanWakeWordService,
                scope   = mainScope,
                chatStreamHandler = handler,
                onIdle  = { resumeEngineAfterPipeline() }
            ).also { wakeWordPipeline = it }
            pipeline.start()
        }
    }

    private fun resumeEngineAfterPipeline() {
        enginePaused = false
        if (hasAudioPermission()) engine?.start()
    }

    private fun isAppInForeground(): Boolean {
        val am = getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        return am.runningAppProcesses?.any {
            it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                it.processName == packageName
        } == true
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


