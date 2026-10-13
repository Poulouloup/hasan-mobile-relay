package com.hasan.v1.webui

import java.net.URI

/**
 * Dérive l'URL HTTP racine hermes-webui à partir de l'URL de base configurée
 * (ex: "https://hermes.example.net" ou "hermes.example.net"). Serveur distinct
 * du relay (qui ne fait plus transiter le chat), même règle TLS standard —
 * voir [com.hasan.v1.network.SecureHttp]. Toujours en https://, même si
 * l'adresse a été saisie en http://.
 */
object WebUiUrlDeriver {

    private const val SCHEME = "https"

    /** URL HTTPS racine, sans slash final. */
    fun httpBaseUrl(rawUrl: String): String {
        val uri = parse(rawUrl)
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "$SCHEME://${uri.host}$port"
    }

    private fun parse(rawUrl: String): URI {
        val trimmed = rawUrl.trim().trimEnd('/')
        // webUiServerUrl est vide tant que l'utilisateur n'a jamais configuré
        // hermes-webui — host placeholder pour éviter l'URISyntaxException
        // sur une install fraîche (voir RelayUrlDeriver.parse).
        if (trimmed.isEmpty()) return URI("$SCHEME://unconfigured.invalid")
        val withScheme = if ("://" in trimmed) trimmed else "$SCHEME://$trimmed"
        return URI(withScheme)
    }
}
