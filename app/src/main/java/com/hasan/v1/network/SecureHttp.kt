package com.hasan.v1.network

import okhttp3.OkHttpClient
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Point d'entrée unique pour tout client HTTP/WebSocket de l'app (relay,
 * hermes-webui, réveil FCM) — vérification TLS standard d'Android : chaîne
 * validée contre les autorités du système ET nom d'hôte contrôlé. Aucun
 * `sslSocketFactory`, `X509TrustManager` ni `hostnameVerifier` maison : c'est
 * précisément leur absence qui fait la sécurité ici, ne pas en rajouter
 * (voir SecureHttpSourceGuardTest, qui échoue si un client en redéfinit un).
 *
 * Remplace l'ancien mode « certificat auto-signé + confiance au premier usage
 * + épinglage » (CertPinStore, retiré) : le serveur est désormais servi sous
 * un nom de domaine avec un certificat Let's Encrypt, dont le renouvellement
 * périodique doit rester invisible — donc rien n'est enregistré ni comparé
 * d'une connexion à l'autre.
 */
object SecureHttp {

    /** Builder avec les timeouts communs — l'appelant n'ajoute que ce qui lui est propre (ex: pingInterval). */
    fun newClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)

    /**
     * Rappel affiché quand le serveur ne répond pas. Sans condition sur le
     * réseau : même sur le Wi-Fi de la maison, le nom du serveur peut se
     * résoudre vers son adresse Tailscale (constaté le 2026-10-12 : la box
     * répondait avant le DNS local), et la connexion expire alors exactement
     * comme à l'extérieur.
     */
    const val TAILSCALE_HINT = "Vérifiez que Tailscale est actif sur le téléphone."

    /**
     * Message utilisateur pour un échec de connexion. Un certificat refusé est
     * une erreur définitive, jamais une question posée à l'utilisateur ; un
     * serveur injoignable renvoie vers Tailscale ([TAILSCALE_HINT]).
     */
    fun describeFailure(t: Throwable): String = when {
        // Avant SSLHandshakeException : les deux héritent de SSLException, mais
        // seul celui-ci signale un certificat valide présenté pour un autre nom.
        t is SSLPeerUnverifiedException ->
            "Le certificat du serveur ne correspond pas à son nom d'hôte — connexion refusée."
        t is SSLHandshakeException && t.hasCause<CertificateException>() ->
            "Certificat du serveur non reconnu (auto-signé, expiré ou autorité inconnue) — connexion refusée."
        t is SSLException ->
            "Échec de la connexion sécurisée (TLS) : ${t.message ?: t.javaClass.simpleName}"
        t is UnknownHostException -> "Serveur injoignable (nom d'hôte introuvable). $TAILSCALE_HINT"
        t is SocketTimeoutException -> "Serveur injoignable (délai dépassé). $TAILSCALE_HINT"
        t is ConnectException || t is NoRouteToHostException ->
            "Serveur injoignable (connexion refusée). $TAILSCALE_HINT"
        else -> t.message ?: t.javaClass.simpleName
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
        generateSequence(cause) { it.cause }.take(10).any { it is T }
}
