package com.hasan.v1

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Synthèse vocale via Android TextToSpeech natif (voix système du téléphone).
 *
 * Initialisé une seule fois au démarrage — jamais recréé à chaque réponse.
 * Utilise QUEUE_ADD pour enchaîner les chunks streamés sans coupure.
 */
class AndroidNativeTtsEngine(private val context: Context) : TtsEngine {

    companion object {
        const val SPEECH_RATE = 1.0f
        const val SPEECH_PITCH = 1.0f
    }

    override val isOnline = false

    private var tts: TextToSpeech? = null
    private var isReady = false

    private val pendingUtterances = AtomicInteger(0)

    override var onSpeakingStart: (() -> Unit)? = null
    override var onAllSpeakingDone: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRENCH
                tts?.setSpeechRate(SPEECH_RATE)
                tts?.setPitch(SPEECH_PITCH)
                tts?.setOnUtteranceProgressListener(progressListener())
                isReady = true
            }
        }
    }

    private fun progressListener() = object : UtteranceProgressListener() {
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
    }

    override fun speak(text: String) {
        if (!isReady || text.isBlank()) return
        val clean = com.hasan.v1.utils.MarkdownUtils.stripMarkdown(text)
        if (clean.isBlank()) return
        pendingUtterances.incrementAndGet()
        tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }

    override fun stop() {
        pendingUtterances.set(0)
        tts?.stop()
    }

    override fun isSpeaking(): Boolean = tts?.isSpeaking == true

    override fun setVolume(volume: Float) {
        // Volume contrôlé au niveau système (AudioManager)
    }

    override fun setSpeed(speed: Float) {
        tts?.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
    }

    fun setVoice(voiceName: String) {
        if (!isReady || voiceName.isBlank()) return
        val voice = tts?.voices?.firstOrNull { it.name == voiceName }
        if (voice != null) tts?.voice = voice
    }

    fun getAvailableEngines(): List<TextToSpeech.EngineInfo> {
        return tts?.engines ?: emptyList()
    }

    fun getCurrentEngine(): String {
        return tts?.defaultEngine ?: ""
    }

    fun getAvailableVoices(): List<android.speech.tts.Voice> {
        if (!isReady) return emptyList()
        return try {
            tts?.voices
                ?.filter { voice ->
                    !voice.isNetworkConnectionRequired &&
                    voice.locale?.language == "fr"
                }
                ?.sortedBy { it.name }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun changeEngine(enginePackage: String) {
        stop()
        tts?.shutdown()
        isReady = false
        tts = TextToSpeech(context.applicationContext, { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRENCH
                tts?.setSpeechRate(SPEECH_RATE)
                tts?.setPitch(SPEECH_PITCH)
                tts?.setOnUtteranceProgressListener(progressListener())
                isReady = true
            }
        }, enginePackage)
    }

    override fun release() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
