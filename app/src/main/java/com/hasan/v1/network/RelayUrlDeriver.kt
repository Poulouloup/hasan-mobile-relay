package com.hasan.v1.network

import java.net.URI

/**
 * Dérive les URLs https:// et wss:// à partir de l'URL de base du relay server
 * configurée par l'utilisateur (ex: "https://relay.example.com" ou
 * "relay.example.com").
 *
 * Toujours chiffré : une adresse saisie en http:// (ou ws://) est ramenée à
 * https:// — l'app n'a plus aucun chemin en clair (voir aussi
 * res/xml/network_security_config.xml).
 */
object RelayUrlDeriver {

    private const val SCHEME = "https"

    /** URL HTTPS racine, sans slash final. "relay.example.com/" -> "https://relay.example.com" */
    fun httpBaseUrl(rawUrl: String): String {
        val uri = parse(rawUrl)
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "$SCHEME://${uri.host}$port"
    }

    /**
     * URL WebSocket pour /ws — SANS le token de session. Le token ne doit
     * jamais transiter en query param (finit dans les logs d'accès du
     * reverse proxy) : il est envoyé dans le premier message applicatif
     * après l'upgrade, voir [com.hasan.v1.network.ConnectionManager].
     */
    fun webSocketUrl(rawUrl: String): String {
        val uri = parse(rawUrl)
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "wss://${uri.host}$port/ws"
    }

    private fun parse(rawUrl: String): URI {
        val trimmed = rawUrl.trim().trimEnd('/')
        // relayServerUrl est vide tant que l'appairage n'a pas eu lieu — un
        // host placeholder évite l'URISyntaxException si un appelant dérive
        // une URL avant d'avoir vérifié isBlank().
        if (trimmed.isEmpty()) return URI("$SCHEME://unconfigured.invalid")
        val withScheme = if ("://" in trimmed) trimmed else "$SCHEME://$trimmed"
        return URI(withScheme)
    }
}
