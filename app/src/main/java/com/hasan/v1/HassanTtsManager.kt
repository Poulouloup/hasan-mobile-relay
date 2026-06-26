package com.hasan.v1

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Synthèse vocale offline via Sherpa-ONNX (Piper VITS, modèle fr_FR-upmc-medium).
 *
 * Fallback automatique sur Android TextToSpeech natif si Sherpa-ONNX ne parvient
 * pas à s'initialiser (modèle absent, OOM, appareil non supporté).
 *
 * API publique identique à l'ancien TtsManager pour ne rien casser côté appelants
 * (MainViewModel, WakeWordPipeline, SettingsFragment).
 */
class HassanTtsManager(private val context: Context) {

    companion object {
        private const val TAG = "HassanTts"
        private const val MODEL_FILE = "fr_FR-upmc-medium.onnx"
        private const val TOKENS_FILE = "fr_FR-upmc-medium.onnx.json"
    }

    // Sherpa-ONNX Piper
    private var piper: OfflineTts? = null
    private var audioTrack: AudioTrack? = null

    // Fallback Android TTS
    private var androidTts: TextToSpeech? = null
    private var androidTtsReady = false

    // Quel backend est actif
    private var useFallback = false

    // Contrôle de la génération en cours
    private val stopRequested = AtomicBoolean(false)
    private val pendingUtterances = AtomicInteger(0)
    @Volatile private var generationThread: Thread? = null

    var onSpeakingStart: (() -> Unit)? = null
    var onAllSpeakingDone: (() -> Unit)? = null

    private var currentSpeed = 1.0f

    init {
        try {
            initPiper()
            Log.i(TAG, "Sherpa-ONNX Piper initialisé — modèle $MODEL_FILE")
        } catch (e: Exception) {
            Log.w(TAG, "Sherpa-ONNX init failed, fallback to Android TTS: ${e.message}")
            useFallback = true
            initAndroidTtsFallback()
        }
    }

    /** Vrai si le moteur Piper est actif (faux = fallback Android TTS). */
    fun isPiperActive(): Boolean = !useFallback

    // ─────────────────────────── Initialisation Piper ─────────────────────────

    private fun initPiper() {
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = MODEL_FILE,
                    lexicon = "",
                    tokens = TOKENS_FILE,
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            ),
            ruleFsts = "",
            maxNumSentences = 1
        )
        // Sherpa-ONNX lit directement depuis assets — pas de copie nécessaire
        piper = OfflineTts(assetManager = context.assets, config = config)

        val sampleRate = piper!!.sampleRate()
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(sampleRate * 4) // Au moins 1s de buffer

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    // ─────────────────────────── Fallback Android TTS ─────────────────────────

    private fun initAndroidTtsFallback() {
        androidTts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                androidTts?.language = Locale.FRENCH
                androidTts?.setSpeechRate(currentSpeed)
                androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (pendingUtterances.get() > 0) onSpeakingStart?.invoke()
                    }
                    override fun onDone(utteranceId: String?) {
                        if (pendingUtterances.decrementAndGet() <= 0) {
                            pendingUtterances.set(0)
                            onAllSpeakingDone?.invoke()
                        }
                    }
                    @Deprecated("Deprecated in API 21")
                    override fun onError(utteranceId: String?) {
                        pendingUtterances.decrementAndGet().coerceAtLeast(0)
                            .also { pendingUtterances.set(it) }
                    }
                })
                androidTtsReady = true
            }
        }
    }

    // ─────────────────────────── API publique ─────────────────────────────────

    /**
     * Enfile un chunk de texte — le Markdown est nettoyé avant synthèse.
     * Piper : génère l'audio dans un thread dédié et le joue via AudioTrack.
     * Fallback : utilise QUEUE_ADD d'Android TTS.
     */
    fun speak(text: String) {
        if (text.isBlank()) return
        val clean = com.hasan.v1.utils.MarkdownUtils.stripMarkdown(text)
        if (clean.isBlank()) return

        if (useFallback) {
            speakFallback(clean)
            return
        }

        pendingUtterances.incrementAndGet()
        stopRequested.set(false)

        val localPiper = piper ?: return
        val localTrack = audioTrack ?: return

        generationThread = Thread {
            try {
                if (localTrack.state == AudioTrack.STATE_INITIALIZED) {
                    if (localTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        localTrack.play()
                    }
                }
                onSpeakingStart?.invoke()

                // callback retourne 1 = continuer, 0 = arrêter
                localPiper.generateWithCallback(
                    text = clean,
                    sid = 0,
                    speed = currentSpeed
                ) { samples ->
                    if (stopRequested.get()) return@generateWithCallback 0
                    localTrack.write(
                        samples, 0, samples.size,
                        AudioTrack.WRITE_BLOCKING
                    )
                    1
                }

                if (pendingUtterances.decrementAndGet() <= 0) {
                    pendingUtterances.set(0)
                    drainAudioTrack(localTrack)
                    onAllSpeakingDone?.invoke()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erreur génération Piper: ${e.message}")
                if (pendingUtterances.decrementAndGet() <= 0) {
                    pendingUtterances.set(0)
                    onAllSpeakingDone?.invoke()
                }
            }
        }.also { it.start() }
    }

    /** Pousse les derniers samples dans le DAC avant de signaler la fin. */
    private fun drainAudioTrack(track: AudioTrack) {
        try {
            val silence = FloatArray(track.sampleRate / 10) // 100ms
            track.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
        } catch (_: Exception) {}
    }

    private fun speakFallback(cleanText: String) {
        if (!androidTtsReady) return
        pendingUtterances.incrementAndGet()
        androidTts?.speak(cleanText, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }

    /** Interrompt immédiatement toute synthèse en cours. */
    fun stop() {
        pendingUtterances.set(0)
        if (useFallback) {
            androidTts?.stop()
            return
        }
        stopRequested.set(true)
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {}
        generationThread?.interrupt()
        generationThread = null
    }

    fun isSpeaking(): Boolean {
        if (useFallback) return androidTts?.isSpeaking == true
        return pendingUtterances.get() > 0
    }

    fun setVolume(volume: Float) {
        // Volume contrôlé au niveau système (AudioManager) — pas de setter Piper
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed.coerceIn(0.5f, 2.0f)
        androidTts?.setSpeechRate(currentSpeed)
    }

    fun release() {
        stop()
        piper?.release()
        piper = null
        try {
            audioTrack?.stop()
        } catch (_: Exception) {}
        audioTrack?.release()
        audioTrack = null
        androidTts?.shutdown()
        androidTts = null
    }
}
