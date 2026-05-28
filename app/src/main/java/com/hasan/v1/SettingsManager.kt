package com.hasan.v1

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Lecture et écriture de tous les paramètres de l'application.
 *
 * Les données sensibles (URL serveur, token) sont stockées dans EncryptedSharedPreferences.
 * Les préférences non sensibles (toggles, sliders) utilisent des prefs normales.
 */
class SettingsManager(context: Context) {

    companion object {
        // Valeurs par défaut
        const val DEFAULT_SERVER_URL   = "https://172.16.1.105:8443"
        const val DEFAULT_AUTH_TOKEN   = "HASAN_DEV_TOKEN"
        const val DEFAULT_MODEL        = "hermes-agent"
        const val DEFAULT_SENSITIVITY  = 0.5f
        const val DEFAULT_TTS_ENABLED  = true
        const val DEFAULT_WAKE_ENABLED = true
        const val DEFAULT_VOLUME       = 100f
        const val DEFAULT_SPEED        = 1.0f

        // Modèles wake word disponibles dans assets/
        val WAKE_WORD_MODELS = listOf(
            "ok_hasan_last_vers.onnx",
            "ok_hasan_livekit.onnx",
            "ok_hasan_v2_livekit.onnx"
        )
        const val DEFAULT_WAKE_WORD_MODEL = "ok_hasan_last_vers.onnx"

        private val MODELS = listOf(
            "hermes-agent",
            "claude-haiku",
            "claude-sonnet",
            "gpt-4o",
            "custom"
        )

        fun getAvailableModels() = MODELS
    }

    // EncryptedSharedPreferences pour token et URL (données sensibles)
    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "hasan_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // SharedPreferences normales pour les préférences UI
    private val prefs: SharedPreferences =
        context.getSharedPreferences("hasan_prefs", Context.MODE_PRIVATE)

    // ─────────────────────── Connexion (chiffrées) ───────────────────────────

    var serverUrl: String
        get() = encryptedPrefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = encryptedPrefs.edit().putString("server_url", value).apply()

    var authToken: String
        get() = encryptedPrefs.getString("auth_token", DEFAULT_AUTH_TOKEN) ?: DEFAULT_AUTH_TOKEN
        set(value) = encryptedPrefs.edit().putString("auth_token", value).apply()

    var model: String
        get() = encryptedPrefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = encryptedPrefs.edit().putString("model", value).apply()

    var customModel: String
        get() = encryptedPrefs.getString("custom_model", "") ?: ""
        set(value) = encryptedPrefs.edit().putString("custom_model", value).apply()

    // ─────────────────────── Assistant ──────────────────────────────────────

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean("wake_word_enabled", DEFAULT_WAKE_ENABLED)
        set(value) = prefs.edit().putBoolean("wake_word_enabled", value).apply()

    var wakeWordModel: String
        get() = prefs.getString("wake_word_model", DEFAULT_WAKE_WORD_MODEL) ?: DEFAULT_WAKE_WORD_MODEL
        set(value) = prefs.edit().putString("wake_word_model", value).apply()

    // Stocké en Int (1–10) pour éviter les erreurs d'arrondi float dans Material Slider
    var wakeWordSensitivity: Float
        get() = prefs.getInt("wake_word_sensitivity_int", (DEFAULT_SENSITIVITY * 10).toInt()) / 10f
        set(value) = prefs.edit().putInt("wake_word_sensitivity_int", (value * 10).toInt()).apply()

    // ─────────────────────── Voix ────────────────────────────────────────────

    var ttsEnabled: Boolean
        get() = prefs.getBoolean("tts_enabled", DEFAULT_TTS_ENABLED)
        set(value) = prefs.edit().putBoolean("tts_enabled", value).apply()

    var ttsEngine: String
        get() = prefs.getString("tts_engine", "") ?: ""
        set(value) = prefs.edit().putString("tts_engine", value).apply()

    var ttsVoice: String
        get() = prefs.getString("tts_voice", "") ?: ""
        set(value) = prefs.edit().putString("tts_voice", value).apply()

    var ttsVolume: Float
        get() = prefs.getFloat("tts_volume", DEFAULT_VOLUME)
        set(value) = prefs.edit().putFloat("tts_volume", value).apply()

    var ttsSpeed: Float
        get() = prefs.getFloat("tts_speed", DEFAULT_SPEED)
        set(value) = prefs.edit().putFloat("tts_speed", value).apply()

    /** Retourne le nom effectif du modèle (résout "custom" → valeur du champ libre). */
    fun effectiveModel(): String =
        if (model == "custom" && customModel.isNotBlank()) customModel else model
}
