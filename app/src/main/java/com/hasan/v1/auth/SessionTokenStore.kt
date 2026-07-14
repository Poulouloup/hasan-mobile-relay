package com.hasan.v1.auth

import com.hasan.v1.SettingsManager

/**
 * Encapsule le cycle de vie du session_token relay (stocké dans
 * [SettingsManager.relaySessionToken], EncryptedSharedPreferences) et de son
 * [SettingsManager.relayRefreshToken] associé.
 *
 * Le renouvellement "gratuit" du token se fait côté serveur : chaque requête
 * HTTP authentifiée ou connexion WebSocket réussie glisse l'expiration de 30
 * jours (voir server/relay/pairing.py `Session.touch()`). Ce store expose en
 * plus une heuristique locale (basée sur le dernier succès observé) pour
 * détecter une expiration probable AVANT qu'une requête échoue en 401, et
 * déclenche alors un renouvellement explicite via [tryRefresh] (échange du
 * refresh_token — pas de re-scan de QR) plutôt que d'attendre l'échec.
 *
 * Pas de scheduler d'arrière-plan (WorkManager) : le check se produit
 * naturellement via les connexions WS normales de
 * [com.hasan.v1.network.ConnectionManager] tant que l'app est utilisée.
 */
class SessionTokenStore(private val settings: SettingsManager) {

    private val pairingManager = PairingManager(settings)

    /** Token actuel, ou null si aucun pairing n'a jamais réussi. */
    val currentToken: String?
        get() = settings.relaySessionToken

    val isPaired: Boolean
        get() = !settings.relaySessionToken.isNullOrBlank()

    /** true si un refresh_token est disponible pour un renouvellement silencieux (sans re-scan de QR). */
    val canRefresh: Boolean
        get() = !settings.relayRefreshToken.isNullOrBlank()

    /** Marque le token comme confirmé valide — à appeler après toute réponse serveur authentifiée réussie (2xx). */
    fun markRenewed() {
        settings.relayTokenLastRenewedAtMillis = System.currentTimeMillis()
    }

    /**
     * true si le token n'a plus été confirmé valide depuis plus que la
     * marge de sécurité — le device a probablement dépassé la fenêtre de
     * grâce serveur (30 jours) sans jamais se reconnecter. L'appelant doit
     * alors tenter [tryRefresh] avant de faire une requête qui échouerait en 401.
     */
    fun isLikelyExpired(): Boolean {
        if (!isPaired) return false
        val lastRenewed = settings.relayTokenLastRenewedAtMillis
        if (lastRenewed == 0L) return false // jamais confirmé mais jamais testé non plus — laisser essayer
        val elapsed = System.currentTimeMillis() - lastRenewed
        return elapsed > STALE_THRESHOLD_MILLIS
    }

    /**
     * Tente un renouvellement silencieux via le refresh_token stocké — pas
     * de re-scan de QR. Retourne false si aucun refresh_token n'est
     * disponible ou si le serveur le rejette (expiré, déjà utilisé) ; dans
     * ce cas l'appelant doit proposer un re-pairing complet (nouveau QR).
     */
    suspend fun tryRefresh(): Boolean {
        val renewed = pairingManager.refresh()
        if (renewed) markRenewed()
        return renewed
    }

    /** Invalide le token stocké — à appeler sur un 401 serveur confirmé (session révoquée/expirée) ou après un [tryRefresh] échoué. */
    fun clear() {
        settings.relaySessionToken = null
        settings.relayRefreshToken = null
        settings.relayTokenLastRenewedAtMillis = 0L
    }

    companion object {
        // Marge de sécurité sous la fenêtre serveur de 30 jours (voir
        // SESSION_TOKEN_TTL_SECONDS côté server/relay/pairing.py) — déclenche
        // un renouvellement proactif avant l'expiration réelle plutôt qu'après.
        private const val STALE_THRESHOLD_MILLIS = 25L * 24 * 60 * 60 * 1000
    }
}
