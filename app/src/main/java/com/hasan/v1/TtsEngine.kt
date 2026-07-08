package com.hasan.v1

/**
 * Interface commune aux deux moteurs TTS disponibles (Android natif hors ligne et
 * ElevenLabs en ligne), pour que [HassanTtsManager] puisse basculer de l'un à l'autre
 * sans changer son API publique.
 */
interface TtsEngine {
    /** true si ce moteur nécessite une connexion réseau pour fonctionner. */
    val isOnline: Boolean

    var onSpeakingStart: (() -> Unit)?
    var onAllSpeakingDone: (() -> Unit)?

    fun speak(text: String)
    fun stop()
    fun isSpeaking(): Boolean
    fun setVolume(volume: Float)
    fun setSpeed(speed: Float)
    fun release()
}
