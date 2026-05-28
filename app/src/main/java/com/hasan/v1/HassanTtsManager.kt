package com.hasan.v1

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gère la synthèse vocale on-device (TextToSpeech Android natif).
 *
 * Initialisé une seule fois au démarrage — jamais recréé à chaque réponse.
 * Utilise QUEUE_ADD pour enchaîner les chunks streamés sans coupure.
 *
 * TODO migration Orca streaming (Picovoice) : remplacer speak() et stop()
 * en conservant la même interface.
 */
class HassanTtsManager(private val context: Context) {

    companion object {
        const val SPEECH_RATE = 1.0f
        const val SPEECH_PITCH = 1.0f
    }

    private var tts: TextToSpeech? = null
    private var isReady = false

    // Compteur d'utterances en attente — permet de détecter la fin réelle
    // quand plusieurs chunks sont enfilés via QUEUE_ADD.
    private val pendingUtterances = AtomicInteger(0)

    var onSpeakingStart: (() -> Unit)? = null
    var onAllSpeakingDone: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRENCH
                tts?.setSpeechRate(SPEECH_RATE)
                tts?.setPitch(SPEECH_PITCH)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
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
                isReady = true
            }
        }
    }

    /** Enfile un chunk de texte — le TTS enchaîne sans silence entre les chunks. */
    fun speak(text: String) {
        if (!isReady || text.isBlank()) return
        pendingUtterances.incrementAndGet()
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }

    /** Interrompt immédiatement toute synthèse en cours et vide la file. */
    fun stop() {
        pendingUtterances.set(0)
        tts?.stop()
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun setVolume(volume: Float) {
        // Volume géré au niveau de l'utterance — on met à jour le speech rate ici
        // (le volume TTS natif Android se contrôle via AudioManager ou setStreamVolume)
    }

    fun setSpeed(speed: Float) {
        tts?.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
    }

    /** Applique une voix par nom (parmi tts.voices). */
    fun setVoice(voiceName: String) {
        if (!isReady || voiceName.isBlank()) return
        val voice = tts?.voices?.firstOrNull { it.name == voiceName }
        if (voice != null) tts?.voice = voice
    }

    /** Retourne les moteurs TTS installés sur l'appareil. */
    fun getAvailableEngines(): List<TextToSpeech.EngineInfo> {
        return tts?.engines ?: emptyList()
    }

    /** Retourne le package du moteur actuellement actif. */
    fun getCurrentEngine(): String {
        return tts?.defaultEngine ?: ""
    }

    /** Retourne uniquement les voix françaises offline disponibles. */
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

    /** Recrée le moteur TTS avec un nouveau package (changement depuis les Paramètres). */
    fun changeEngine(enginePackage: String) {
        stop()
        tts?.shutdown()
        isReady = false
        tts = TextToSpeech(context.applicationContext, { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRENCH
                tts?.setSpeechRate(SPEECH_RATE)
                tts?.setPitch(SPEECH_PITCH)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
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
                isReady = true
            }
        }, enginePackage)
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
