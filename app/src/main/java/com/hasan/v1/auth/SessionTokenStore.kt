package com.hasan.v1.auth

import com.hasan.v1.SettingsManager

/**
 * Encapsule le cycle de vie du session_token relay (stocké dans
 * [SettingsManager.relaySessionToken], EncryptedSharedPreferences).
 *
 * Le renouvellement effectif du token se fait côté serveur : chaque
 * requête HTTP authentifiée ou connexion WebSocket réussie glisse
 * l'expiration de 30 jours (voir server/relay/pairing.py `Session.touch()`).
 * Ce store ne renouvelle rien lui-même — il expose seulement une heuristique
 * locale (basée sur le dernier succès observé) pour distinguer "jamais
 * pairé", "probablement encore valide", et "probablement expiré côté
 * serveur" (device resté longtemps sans connexion réseau), afin que l'UI
 * puisse proposer un re-pairing avant qu'une requête échoue en 401.
 *
 * Pas de scheduler d'arrière-plan (WorkManager) : le renouvellement se
 * produit naturellement via les connexions WS normales de
 * [com.hasan.v1.network.ConnectionManager] tant que l'app est utilisée.
 */
class SessionTokenStore(private val settings: SettingsManager) {

    /** Token actuel, ou null si aucun pairing n'a jamais réussi. */
    val currentToken: String?
        get() = settings.relaySessionToken

    val isPaired: Boolean
        get() = !settings.relaySessionToken.isNullOrBlank()

    /** Marque le token comme confirmé valide — à appeler après toute réponse serveur authentifiée réussie (2xx). */
    fun markRenewed() {
        settings.relayTokenLastRenewedAtMillis = System.currentTimeMillis()
    }

    /**
     * true si le token n'a plus été confirmé valide depuis plus que la
     * marge de sécurité — le device a probablement dépassé la fenêtre de
     * grâce serveur (30 jours) sans jamais se reconnecter. L'UI doit alors
     * proposer un re-pairing avant de tenter une requête qui échouerait en 401.
     */
    fun isLikelyExpired(): Boolean {
        if (!isPaired) return false
        val lastRenewed = settings.relayTokenLastRenewedAtMillis
        if (lastRenewed == 0L) return false // jamais confirmé mais jamais testé non plus — laisser essayer
        val elapsed = System.currentTimeMillis() - lastRenewed
        return elapsed > STALE_THRESHOLD_MILLIS
    }

    /** Invalide le token stocké — à appeler sur un 401 serveur confirmé (session révoquée/expirée). */
    fun clear() {
        settings.relaySessionToken = null
        settings.relayTokenLastRenewedAtMillis = 0L
    }

    companion object {
        // Marge de sécurité sous la fenêtre serveur de 30 jours (voir
        // SESSION_TOKEN_TTL_SECONDS côté server/relay/pairing.py) — déclenche
        // un re-pairing proactif avant l'expiration réelle plutôt qu'après.
        private const val STALE_THRESHOLD_MILLIS = 25L * 24 * 60 * 60 * 1000
    }
}
