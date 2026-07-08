package com.hasan.v1

import android.content.Context

/**
 * Façade TTS — délègue à [AndroidNativeTtsEngine] (hors ligne) ou [EdgeTtsEngine]
 * (cloud, endpoint gratuit non officiel de Microsoft Edge) selon
 * [SettingsManager.ttsProvider].
 *
 * L'instance [AndroidNativeTtsEngine] est unique et permanente (une seule init
 * TextToSpeech pour toute la durée de vie du manager) — elle sert à la fois de
 * provider "natif" normal et de secours de fallback, pour que les allers-retours
 * entre providers dans les Réglages ne recréent jamais TextToSpeech inutilement.
 * Seul [EdgeTtsEngine] est créé/libéré dynamiquement selon le provider actif.
 *
 * Si Edge TTS est sélectionné mais échoue au moment de parler (pas de réseau,
 * endpoint bloqué/changé côté Microsoft), [speak] bascule automatiquement sur le TTS
 * natif pour cette phrase et notifie l'appelant via [onFallback], sans changer le
 * réglage persisté — au prochain `speak()`, Edge TTS est retenté.
 */
class HassanTtsManager(private val context: Context) {

    private val settings = SettingsManager(context)

    private val nativeEngine = AndroidNativeTtsEngine(context)
    private var edgeEngine: EdgeTtsEngine? = null
    private var provider: String = settings.ttsProvider

    var onSpeakingStart: (() -> Unit)? = null
        set(value) {
            field = value
            nativeEngine.onSpeakingStart = value
            edgeEngine?.onSpeakingStart = value
        }

    var onAllSpeakingDone: (() -> Unit)? = null
        set(value) {
            field = value
            nativeEngine.onAllSpeakingDone = value
            edgeEngine?.onAllSpeakingDone = value
        }

    /** Notifié quand une synthèse Edge TTS échoue et bascule sur le TTS natif. */
    var onFallback: ((reason: String) -> Unit)? = null

    init {
        nativeEngine.onSpeakingStart = onSpeakingStart
        nativeEngine.onAllSpeakingDone = onAllSpeakingDone
        if (provider == SettingsManager.TTS_PROVIDER_EDGE) ensureEdgeEngine()
    }

    private fun ensureEdgeEngine(): EdgeTtsEngine =
        edgeEngine ?: EdgeTtsEngine(context).also {
            it.setVoice(settings.ttsVoice.ifBlank { EdgeTtsEngine.DEFAULT_VOICE })
            it.onFallbackTriggered = ::handleFallback
            it.onSpeakingStart = onSpeakingStart
            it.onAllSpeakingDone = onAllSpeakingDone
            edgeEngine = it
        }

    private val activeEngine: TtsEngine
        get() = if (provider == SettingsManager.TTS_PROVIDER_EDGE) ensureEdgeEngine() else nativeEngine

    private fun handleFallback(reason: String) {
        onFallback?.invoke(reason)
    }

    /**
     * Bascule vers un autre provider ("native" ou "edge"). L'instance native n'est
     * jamais recréée ; seul EdgeTtsEngine est construit à la demande et libéré quand
     * on repasse en natif, pour ne pas garder une connexion/cache inutilisés.
     */
    fun changeProvider(newProvider: String) {
        if (newProvider == provider) return
        provider = newProvider
        if (newProvider == SettingsManager.TTS_PROVIDER_EDGE) {
            ensureEdgeEngine()
        } else {
            edgeEngine?.release()
            edgeEngine = null
        }
    }

    fun currentProvider(): String = provider

    fun isOnline(): Boolean = activeEngine.isOnline

    /**
     * Parle avec le moteur actif. Si Edge TTS est actif mais indisponible (pas de
     * réseau), la phrase part automatiquement sur le TTS natif en secours — le
     * réglage utilisateur n'est pas modifié, seul cet appel bascule ponctuellement.
     */
    fun speak(text: String) {
        val engine = activeEngine
        if (engine is EdgeTtsEngine && !isNetworkAvailable()) {
            handleFallback("Pas de connexion réseau")
            nativeEngine.speak(text)
            return
        }
        engine.speak(text)
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun stop() {
        nativeEngine.stop()
        edgeEngine?.stop()
    }

    fun isSpeaking(): Boolean = nativeEngine.isSpeaking() || (edgeEngine?.isSpeaking() == true)

    fun setVolume(volume: Float) {
        nativeEngine.setVolume(volume)
        edgeEngine?.setVolume(volume)
    }

    fun setSpeed(speed: Float) {
        nativeEngine.setSpeed(speed)
        edgeEngine?.setSpeed(speed)
    }

    /** Change de voix — voix système si natif, nom de voix Edge TTS sinon. */
    fun setVoice(voiceName: String) {
        nativeEngine.setVoice(voiceName)
        edgeEngine?.setVoice(voiceName)
    }

    fun getAvailableVoices(): List<String> = when (provider) {
        SettingsManager.TTS_PROVIDER_EDGE -> SettingsManager.EDGE_TTS_VOICES
        else -> nativeEngine.getAvailableVoices().map { it.name }
    }

    /** Moteurs Android natifs installés (Google TTS, Samsung TTS, etc.). */
    fun getAvailableEngines() = nativeEngine.getAvailableEngines()

    fun getCurrentEngine(): String = nativeEngine.getCurrentEngine()

    fun changeEngine(enginePackage: String) {
        nativeEngine.changeEngine(enginePackage)
    }

    fun release() {
        nativeEngine.release()
        edgeEngine?.release()
        edgeEngine = null
    }
}
