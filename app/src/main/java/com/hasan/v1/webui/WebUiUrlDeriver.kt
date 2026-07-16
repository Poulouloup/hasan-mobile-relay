package com.hasan.v1.webui

import java.net.URI

/**
 * Dérive l'URL HTTP racine hermes-webui à partir de l'URL de base configurée
 * (ex: "https://34.155.193.170" ou "34.155.193.170"). Pas de domaine — le
 * serveur est en TLS auto-signé (Caddy `tls internal`) derrière l'IP publique,
 * même modèle de confiance TOFU que [com.hasan.v1.network.RelayUrlDeriver],
 * mais un serveur distinct (le relay ne fait plus transiter le chat).
 */
object WebUiUrlDeriver {

    private const val DEFAULT_SCHEME = "https"

    /** URL HTTP racine, sans slash final. */
    fun httpBaseUrl(rawUrl: String): String {
        val uri = parse(rawUrl)
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "${uri.scheme}://${uri.host}$port"
    }

    private fun parse(rawUrl: String): URI {
        val trimmed = rawUrl.trim().trimEnd('/')
        val withScheme = if ("://" in trimmed) trimmed else "$DEFAULT_SCHEME://$trimmed"
        return URI(withScheme)
    }
}
