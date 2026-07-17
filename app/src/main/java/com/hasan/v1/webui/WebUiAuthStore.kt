package com.hasan.v1.webui

import com.hasan.v1.SettingsManager

/**
 * Encapsule les cookies hermes-webui, stockés dans [SettingsManager]
 * (EncryptedSharedPreferences) :
 *  - `hermes_session` (voir api/auth.py côté serveur) — session d'auth,
 *    émis par POST /api/auth/login.
 *  - `hermes_profile` (voir api/helpers.py `get_profile_cookie_name`,
 *    signé côté serveur si l'auth est activée) — profil Hermes actif
 *    (quel HERMES_HOME), émis par POST /api/profile/switch. Sans ce
 *    cookie renvoyé sur chaque requête, le serveur retombe sur son profil
 *    par défaut à chaque appel plutôt que de garder le profil choisi.
 *
 * Pas de refresh token côté hermes-webui (contrairement au relay) : le cookie
 * de session expire côté serveur et la seule reprise possible est un nouveau
 * POST /api/auth/login avec le mot de passe — voir [WebUiRestClient.login].
 */
class WebUiAuthStore(private val settings: SettingsManager) {

    /** Cookie `hermes_session` actuel, ou null si jamais authentifié. */
    val currentCookie: String?
        get() = settings.webUiSessionCookie

    /** Cookie `hermes_profile` actuel, ou null si jamais de switch de profil explicite (le serveur utilise alors son profil par défaut). */
    val currentProfileCookie: String?
        get() = settings.webUiProfileCookie

    val isLoggedIn: Boolean
        get() = !settings.webUiSessionCookie.isNullOrBlank()

    /** À appeler avec la valeur du header Set-Cookie renvoyé par POST /api/auth/login. */
    fun store(cookieHeaderValue: String) {
        settings.webUiSessionCookie = cookieHeaderValue
    }

    /** À appeler avec la valeur du header Set-Cookie renvoyé par POST /api/profile/switch. */
    fun storeProfileCookie(cookieHeaderValue: String) {
        settings.webUiProfileCookie = cookieHeaderValue
    }

    /** Invalide les cookies stockés — à appeler sur un 401 serveur confirmé (session expirée/révoquée). */
    fun clear() {
        settings.webUiSessionCookie = null
        settings.webUiProfileCookie = null
    }
}
