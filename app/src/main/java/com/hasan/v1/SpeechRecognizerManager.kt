package com.hasan.v1

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Encapsule le SpeechRecognizer Android.
 *
 * Doit être instancié et utilisé sur le thread principal (contrainte Android).
 * TODO migration Moonshine : remplacer cette classe par MoonshineSTT qui implémente la même interface.
 */
class SpeechRecognizerManager(
    private val context: Context,
    private val listener: SttListener
) {
    interface SttListener {
        fun onTranscriptPartial(text: String)
        fun onTranscriptFinal(text: String)
        fun onError(code: Int, message: String)
        fun onReadyForSpeech()
        fun onEndOfSpeech()
    }

    private var recognizer: SpeechRecognizer? = null

    /** Démarre l'écoute. Crée un nouveau SpeechRecognizer à chaque session. */
    fun startListening() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(buildRecognitionListener())
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.FRENCH.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.FRENCH.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            // Résultats partiels pour affichage en temps réel
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Délai fin de parole : ~1.5s de silence → l'API interne gère ça automatiquement
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer?.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun buildRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listener.onReadyForSpeech()
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            listener.onEndOfSpeech()
        }

        override fun onError(error: Int) {
            // Silence normal (pas de parole / pas de correspondance) — on ignore
            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                listener.onError(error, "")
                return
            }
            val msg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Erreur audio"
                SpeechRecognizer.ERROR_CLIENT -> "Erreur client"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission RECORD_AUDIO manquante"
                SpeechRecognizer.ERROR_NETWORK -> "Erreur réseau (STT en ligne requis)"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Timeout réseau"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconnaissance déjà active"
                SpeechRecognizer.ERROR_SERVER -> "Erreur serveur STT"
                else -> "Erreur inconnue ($error)"
            }
            listener.onError(error, msg)
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = matches?.firstOrNull().orEmpty()
            if (best.isNotBlank()) {
                listener.onTranscriptFinal(best)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (partial.isNotBlank()) {
                listener.onTranscriptPartial(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
