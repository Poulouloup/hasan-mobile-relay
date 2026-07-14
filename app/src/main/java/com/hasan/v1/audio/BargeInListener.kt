package com.hasan.v1.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.hasan.v1.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Ouvre le micro pendant que [ttsEngine] parle, pour permettre à
 * l'utilisateur de couper la synthèse en parlant par-dessus ("barge-in").
 *
 * Cycle :
 *   1. [start] pendant que le TTS parle → ouvre l'[AudioRecord], démarre le VAD.
 *   2. Première frame de voix détectée → ducking : `ttsEngine.setVolume(DUCK_VOLUME)`.
 *   3. Voix confirmée sur [CONFIRM_SPEECH_FRAMES] frames consécutives →
 *      coupe le TTS (`ttsEngine.stop()`), émet [bargeInConfirmed], le
 *      contrôle repasse à l'appelant (mode écoute — démarrage du STT
 *      n'est PAS fait ici, c'est la responsabilité de l'appelant qui
 *      possède déjà ce pipeline dans MainViewModel).
 *   4. Silence après ducking sans confirmation → restaure le volume
 *      (`ttsEngine.setVolume(1f)`), reprend l'écoute normale.
 *
 * Doit être démarré seulement quand [com.hasan.v1.HassanWakeWordService]
 * est PAUSED (déjà le cas pendant `TtsStatus.SPEAKING`, voir
 * `MainViewModel.onSpeakingStart`) — sinon deux composants ouvriraient
 * l'AudioRecord simultanément.
 */
class BargeInListener(
    private val context: Context,
    private val ttsEngine: TtsEngine,
    private val vadProfile: VadEngine.Profile = VadEngine.Profile.DEFAULT,
) {
    sealed class Event {
        /** Ducking appliqué — première détection de voix, pas encore confirmée. */
        object DuckingStarted : Event()
        /** Retour au volume normal — la détection de voix n'a pas été confirmée (faux positif probable). */
        object DuckingCancelled : Event()
        /** Voix confirmée — TTS coupé, l'appelant doit basculer en mode écoute (démarrer le STT). */
        object BargeInConfirmed : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var listenJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var vadEngine: VadEngine? = null

    @Volatile
    private var running = false

    /** Démarre l'écoute barge-in. Sans effet si déjà démarré ou si RECORD_AUDIO n'est pas accordé. */
    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "start() ignoré — RECORD_AUDIO non accordé")
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            VadEngine.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            Log.e(TAG, "AudioRecord.getMinBufferSize a échoué ($minBufferSize) — device non supporté")
            return
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                VadEngine.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBufferSize, VadEngine.FRAME_SIZE_SAMPLES * 4),
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "AudioRecord refusé: ${e.message}")
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord non initialisé (state=${record.state})")
            record.release()
            return
        }

        audioRecord = record
        vadEngine = VadEngine(context, vadProfile)
        running = true

        record.startRecording()
        listenJob = scope.launch { listenLoop(record) }
        Log.i(TAG, "Barge-in démarré")
    }

    /** Arrête l'écoute barge-in et libère le micro. Restaure le volume TTS si un ducking était en cours. */
    fun stop() {
        if (!running) return
        running = false

        listenJob?.cancel()
        listenJob = null

        audioRecord?.let {
            try {
                it.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Erreur à l'arrêt de l'AudioRecord: ${e.message}")
            }
            it.release()
        }
        audioRecord = null

        vadEngine?.release()
        vadEngine = null

        ttsEngine.setVolume(1f)
        Log.i(TAG, "Barge-in arrêté")
    }

    private suspend fun listenLoop(record: AudioRecord) {
        val frame = ShortArray(VadEngine.FRAME_SIZE_SAMPLES)
        var consecutiveSpeechFrames = 0
        var ducked = false

        while (scope.isActive && running) {
            val read = record.read(frame, 0, frame.size)
            if (read != frame.size) {
                // Lecture partielle/erreur (device occupé, sous-run) — on retente à la frame suivante
                // plutôt que de planter toute la boucle sur un aléa transitoire du driver audio.
                continue
            }

            val isSpeech = vadEngine?.isSpeech(frame) ?: false

            if (isSpeech) {
                consecutiveSpeechFrames++

                if (!ducked && consecutiveSpeechFrames >= DUCK_TRIGGER_FRAMES) {
                    ducked = true
                    ttsEngine.setVolume(DUCK_VOLUME)
                    _events.tryEmit(Event.DuckingStarted)
                }

                if (consecutiveSpeechFrames >= CONFIRM_SPEECH_FRAMES) {
                    ttsEngine.stop()
                    _events.tryEmit(Event.BargeInConfirmed)
                    running = false // le contrôle repasse à l'appelant — stop() sera appelé pour libérer le micro
                    return
                }
            } else {
                if (ducked) {
                    ducked = false
                    ttsEngine.setVolume(1f)
                    _events.tryEmit(Event.DuckingCancelled)
                }
                consecutiveSpeechFrames = 0
            }
        }
    }

    companion object {
        private const val TAG = "BargeInListener"

        private const val DUCK_VOLUME = 0.3f

        // Nombre de frames voix consécutives (à 512 échantillons / 16kHz ≈ 32ms/frame)
        // avant de déclencher le ducking, puis avant de confirmer le barge-in.
        // Ducking rapide (réactif dès qu'on soupçonne une voix) ; confirmation plus
        // lente (évite de couper le TTS sur un bruit bref classé voix par erreur).
        private const val DUCK_TRIGGER_FRAMES = 2   // ≈ 64ms
        private const val CONFIRM_SPEECH_FRAMES = 10 // ≈ 320ms
    }
}
