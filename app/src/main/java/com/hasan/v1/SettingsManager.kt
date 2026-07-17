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

    private val appContext = context.applicationContext

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

        // Voix Edge TTS françaises (endpoint non officiel "Lire à voix haute" de Microsoft Edge)
        val EDGE_TTS_VOICES = listOf(
            "fr-FR-HenriNeural",
            "fr-FR-DeniseNeural",
            "fr-FR-VivienneMultilingualNeural",
            "fr-FR-RemyMultilingualNeural",
            "fr-CA-ThierryNeural"
        )
        const val DEFAULT_TTS_VOICE = "fr-FR-HenriNeural"

        // Provider TTS : "native" (Android TextToSpeech système) ou "edge" (Edge TTS cloud gratuit)
        const val TTS_PROVIDER_NATIVE = "native"
        const val TTS_PROVIDER_EDGE   = "edge"
        const val DEFAULT_TTS_PROVIDER = TTS_PROVIDER_NATIVE

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
        createEncryptedPrefs(context)
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return try {
            EncryptedSharedPreferences.create(
                context,
                "hasan_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Clé Keystore corrompue ou invalidée (ex: réinstallation) — effacer et recréer
            android.util.Log.w("SettingsManager", "EncryptedPrefs corrompues, reset : ${e.message}")
            context.deleteSharedPreferences("hasan_secure_prefs")
            EncryptedSharedPreferences.create(
                context,
                "hasan_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
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

    // ─────────────────────── Onboarding ────────────────────────────────────

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean("onboarding_completed", false)
        set(value) = prefs.edit().putBoolean("onboarding_completed", value).apply()

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

    /** "native" (Android TextToSpeech système) ou "elevenlabs" (API cloud). */
    var ttsProvider: String
        get() = prefs.getString("tts_provider", DEFAULT_TTS_PROVIDER) ?: DEFAULT_TTS_PROVIDER
        set(value) = prefs.edit().putString("tts_provider", value).apply()

    /** Package du moteur TTS Android natif choisi (ex: com.google.android.tts). */
    var ttsEngine: String
        get() = prefs.getString("tts_engine", "") ?: ""
        set(value) = prefs.edit().putString("tts_engine", value).apply()

    /** Voix sélectionnée — nom de voix système si natif, nom de voix Edge TTS sinon. */
    var ttsVoice: String
        get() = prefs.getString("tts_voice", DEFAULT_TTS_VOICE) ?: DEFAULT_TTS_VOICE
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

    // ─────────────────────── Certificats de confiance TOFU ──────────────────

    /**
     * Retourne le fingerprint SHA-256 stocké pour une clé de serveur,
     * ou null si ce serveur n'a jamais été approuvé.
     * La clé est générée par [com.hasan.v1.network.models.certStorageKey].
     */
    fun getTrustedCertFingerprint(key: String): String? =
        encryptedPrefs.getString(key, null)

    /**
     * Stocke le fingerprint SHA-256 d'un certificat approuvé par l'utilisateur.
     * Chiffré dans EncryptedSharedPreferences.
     */
    fun setTrustedCertFingerprint(key: String, fingerprint: String) =
        encryptedPrefs.edit().putString(key, fingerprint).apply()

    /**
     * Supprime la confiance accordée à un serveur.
     * Après cet appel, la prochaine connexion déclenchera à nouveau la dialog TOFU.
     */
    fun removeTrustedCertFingerprint(key: String) =
        encryptedPrefs.edit().remove(key).apply()

    /**
     * Retourne la liste de tous les serveurs de confiance enregistrés.
     * Chaque entrée est une paire (clé de stockage → fingerprint).
     * Utilisé par l'écran de gestion des certificats dans SettingsFragment.
     */
    fun getAllTrustedCerts(): Map<String, String> {
        return encryptedPrefs.all
            .filter { it.key.startsWith("trusted_cert_") && it.value is String }
            .mapValues { it.value as String }
    }

    /**
     * Supprime tous les certificats de confiance enregistrés.
     * Toutes les connexions suivantes déclencheront à nouveau la dialog TOFU.
     */
    fun clearAllTrustedCerts() {
        val editor = encryptedPrefs.edit()
        encryptedPrefs.all.keys
            .filter { it.startsWith("trusted_cert_") }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    // ─────────────────────── Sessions Hermes ────────────────────────────────

    /**
     * Retourne l'ID de la session active, ou null si aucune session n'a été initialisée.
     * La session active est déterminée par SessionDao.getActive() — cette valeur est
     * mise en cache ici pour éviter des accès Room sur le main thread.
     */
    var activeSessionId: String?
        get() = prefs.getString("active_session_id", null)
        set(value) = prefs.edit().putString("active_session_id", value).apply()

    // ─────────────────────── Relay server (WebSocket) ───────────────────────

    var relayServerUrl: String
        get() = encryptedPrefs.getString("relay_server_url", "") ?: ""
        set(value) = encryptedPrefs.edit().putString("relay_server_url", value).apply()

    var relaySessionToken: String?
        get() = encryptedPrefs.getString("relay_session_token", null)
        set(value) = encryptedPrefs.edit().putString("relay_session_token", value).apply()

    /**
     * Refresh token (longue durée, usage unique — rotation à chaque usage
     * côté serveur) permettant de renouveler [relaySessionToken] sans
     * re-scanner de QR. Voir [com.hasan.v1.auth.SessionTokenStore.refresh].
     */
    var relayRefreshToken: String?
        get() = encryptedPrefs.getString("relay_refresh_token", null)
        set(value) = encryptedPrefs.edit().putString("relay_refresh_token", value).apply()

    /** Horodatage (epoch millis) du dernier succès authentifié confirmé avec le relay — voir [com.hasan.v1.auth.SessionTokenStore]. */
    var relayTokenLastRenewedAtMillis: Long
        get() = encryptedPrefs.getLong("relay_token_last_renewed_at", 0L)
        set(value) = encryptedPrefs.edit().putLong("relay_token_last_renewed_at", value).apply()

    /** Identifiant stable de ce device pour le pairing relay — SHA-256 de l'ANDROID_ID (salé), généré une seule fois. */
    var relayDeviceHash: String
        get() {
            val existing = encryptedPrefs.getString("relay_device_hash", null)
            if (existing != null) return existing
            val androidId = android.provider.Settings.Secure.getString(
                appContext.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
            val generated = java.security.MessageDigest.getInstance("SHA-256")
                .digest("relay:$androidId".toByteArray())
                .joinToString("") { "%02x".format(it) }
            encryptedPrefs.edit().putString("relay_device_hash", generated).apply()
            return generated
        }
        set(value) = encryptedPrefs.edit().putString("relay_device_hash", value).apply()

    // ─────────────────────── hermes-webui (chat REST/SSE) ───────────────────────

    /** URL de base hermes-webui (ex: "https://34.155.193.170"), distincte de [relayServerUrl] — voir com.hasan.v1.webui. */
    var webUiServerUrl: String
        get() = encryptedPrefs.getString("webui_server_url", "") ?: ""
        set(value) = encryptedPrefs.edit().putString("webui_server_url", value).apply()

    /**
     * Cookie de session hermes_session obtenu via POST /api/auth/login. Pas de
     * refresh token côté hermes-webui : le cookie expire côté serveur et un
     * 401 déclenche un nouveau login (mot de passe re-demandé), voir
     * com.hasan.v1.webui.WebUiAuthStore.
     */
    var webUiSessionCookie: String?
        get() = encryptedPrefs.getString("webui_session_cookie", null)
        set(value) = encryptedPrefs.edit().putString("webui_session_cookie", value).apply()

    /**
     * Cookie hermes_profile obtenu via POST /api/profile/switch — quel
     * HERMES_HOME est actif (config/skills/workspace). Absent tant qu'aucun
     * switch explicite n'a été fait (le serveur utilise alors son profil
     * par défaut). Voir com.hasan.v1.webui.WebUiAuthStore.
     */
    var webUiProfileCookie: String?
        get() = encryptedPrefs.getString("webui_profile_cookie", null)
        set(value) = encryptedPrefs.edit().putString("webui_profile_cookie", value).apply()

    /**
     * Modèle LLM choisi par l'utilisateur pour le prochain tour de chat
     * (picker dans la barre de composition) — vide = laisser le serveur
     * utiliser son modèle par défaut. Distinct de [model]/[customModel]
     * (vestiges de l'ancien flux relay, jamais branchés sur hermes-webui).
     */
    var webUiSelectedModel: String
        get() = prefs.getString("webui_selected_model", "") ?: ""
        set(value) = prefs.edit().putString("webui_selected_model", value).apply()

    /** Hash MD5 du JSON des capabilities — détecte les changements à synchroniser. */
    var capabilitiesVersion: String
        get() = prefs.getString("orchestrator_capabilities_version", "") ?: ""
        set(value) = prefs.edit().putString("orchestrator_capabilities_version", value).apply()

    /** Retourne l'état (activé/désactivé) de chaque capability connue. */
    fun getCapabilities(): Map<String, Boolean> {
        val json = encryptedPrefs.getString("orchestrator_capabilities", null) ?: return emptyMap()
        return try {
            val obj = org.json.JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getBoolean(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Sauvegarde l'état des capabilities et met à jour la version (hash MD5). */
    fun setCapabilities(caps: Map<String, Boolean>) {
        val obj = org.json.JSONObject()
        caps.forEach { (key, value) -> obj.put(key, value) }
        val json = obj.toString()
        encryptedPrefs.edit().putString("orchestrator_capabilities", json).apply()
        capabilitiesVersion = java.security.MessageDigest.getInstance("MD5")
            .digest(json.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun isCapabilityEnabled(name: String): Boolean = getCapabilities()[name] ?: false

    fun setCapabilityEnabled(name: String, enabled: Boolean) {
        val caps = getCapabilities().toMutableMap()
        caps[name] = enabled
        setCapabilities(caps)
    }

    /** Retourne, pour chaque capability connue, si une confirmation utilisateur est requise. */
    fun getCapabilitiesAuthRequired(): Map<String, Boolean> {
        val json = encryptedPrefs.getString("orchestrator_capabilities_auth", null) ?: return emptyMap()
        return try {
            val obj = org.json.JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getBoolean(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun isCapabilityAuthRequired(name: String, default: Boolean): Boolean =
        getCapabilitiesAuthRequired()[name] ?: default

    fun setCapabilityAuthRequired(name: String, authRequired: Boolean) {
        val map = getCapabilitiesAuthRequired().toMutableMap()
        map[name] = authRequired
        val obj = org.json.JSONObject()
        map.forEach { (key, value) -> obj.put(key, value) }
        encryptedPrefs.edit().putString("orchestrator_capabilities_auth", obj.toString()).apply()
        // Le changement de auth_required modifie le contrat envoyé au serveur →
        // recalcule la version pour déclencher une resynchronisation.
        capabilitiesVersion = java.security.MessageDigest.getInstance("MD5")
            .digest((org.json.JSONObject(getCapabilities() as Map<*, *>).toString() + obj.toString()).toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

}
