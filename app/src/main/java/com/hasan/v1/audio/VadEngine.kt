package com.hasan.v1.audio

import android.content.Context
import android.util.Log
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate

/**
 * Pur classificateur voix/silence (Silero VAD, DNN, via ONNX Runtime).
 *
 * Ne possède pas de micro — reçoit des frames déjà capturées (voir
 * [BargeInListener], qui possède l'[android.media.AudioRecord]) et répond
 * juste "c'est de la voix" ou non. Charge `silero_vad.onnx` depuis les
 * assets (fait par la lib elle-même à la construction).
 *
 * Taille de frame requise en entrée : [FRAME_SIZE_SAMPLES] échantillons
 * PCM 16-bit mono à [SAMPLE_RATE_HZ]. Une frame de taille différente est
 * rejetée par la lib sous-jacente.
 */
class VadEngine(
    context: Context,
    profile: Profile = Profile.DEFAULT,
) {
    /** Profil de sensibilité — voir [Mode] de la lib sous-jacente. Plus agressif = moins de faux positifs mais détection plus tardive. */
    enum class Profile { LOW, DEFAULT, HIGH }

    private val vad: VadSilero = Vad.builder()
        .setContext(context)
        .setSampleRate(SampleRate.SAMPLE_RATE_16K)
        .setFrameSize(FrameSize.FRAME_SIZE_512)
        .setMode(profile.toMode())
        .setSpeechDurationMs(SPEECH_DEBOUNCE_MS)
        .setSilenceDurationMs(SILENCE_DEBOUNCE_MS)
        .build()

    @Volatile
    private var closed = false

    /**
     * Classifie une frame audio. Applique en interne le debounce
     * (speech/silence duration) configuré au constructeur — un appel isolé
     * ne suffit pas forcément à basculer l'état, voir la doc de la lib
     * (`isContinuousSpeech`).
     *
     * @param frame PCM 16-bit mono, exactement [FRAME_SIZE_SAMPLES] échantillons.
     * @return true si de la voix est détectée sur cette frame (après debounce).
     */
    fun isSpeech(frame: ShortArray): Boolean {
        if (closed) return false
        require(frame.size == FRAME_SIZE_SAMPLES) {
            "VadEngine attend des frames de $FRAME_SIZE_SAMPLES échantillons, reçu ${frame.size}"
        }
        return try {
            vad.isSpeech(frame)
        } catch (e: Exception) {
            Log.w(TAG, "Erreur classification VAD: ${e.message}")
            false
        }
    }

    /** Libère la session ONNX. Après appel, [isSpeech] retourne toujours false. */
    fun release() {
        if (closed) return
        closed = true
        try {
            vad.close()
        } catch (e: Exception) {
            Log.w(TAG, "Erreur à la fermeture du VAD: ${e.message}")
        }
    }

    private fun Profile.toMode(): Mode = when (this) {
        Profile.LOW -> Mode.NORMAL
        Profile.DEFAULT -> Mode.AGGRESSIVE
        Profile.HIGH -> Mode.VERY_AGGRESSIVE
    }

    companion object {
        private const val TAG = "VadEngine"

        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_SIZE_SAMPLES = 512

        // Debounce : évite qu'un bruit bref (toux, cliquetis) ou un micro-silence
        // entre deux mots ne déclenche/coupe le barge-in prématurément.
        private const val SPEECH_DEBOUNCE_MS = 300
        private const val SILENCE_DEBOUNCE_MS = 500
    }
}
