package com.hasan.v1.webui

import com.hasan.v1.SettingsManager

/**
 * Encapsule le cookie de session hermes-webui (`hermes_session`, voir
 * api/auth.py côté serveur), stocké dans
 * [SettingsManager.webUiSessionCookie] (EncryptedSharedPreferences).
 *
 * Pas de refresh token côté hermes-webui (contrairement au relay) : le cookie
 * expire côté serveur et la seule reprise possible est un nouveau
 * POST /api/auth/login avec le mot de passe — voir [WebUiRestClient.login].
 */
class WebUiAuthStore(private val settings: SettingsManager) {

    /** Cookie `hermes_session` actuel, ou null si jamais authentifié. */
    val currentCookie: String?
        get() = settings.webUiSessionCookie

    val isLoggedIn: Boolean
        get() = !settings.webUiSessionCookie.isNullOrBlank()

    /** À appeler avec la valeur du header Set-Cookie renvoyé par POST /api/auth/login. */
    fun store(cookieHeaderValue: String) {
        settings.webUiSessionCookie = cookieHeaderValue
    }

    /** Invalide le cookie stocké — à appeler sur un 401 serveur confirmé (session expirée/révoquée). */
    fun clear() {
        settings.webUiSessionCookie = null
    }
}
