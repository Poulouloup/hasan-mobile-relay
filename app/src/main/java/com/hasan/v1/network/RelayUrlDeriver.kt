package com.hasan.v1.network

import java.net.URI

/**
 * Dérive les URLs ws:// et http:// à partir de l'URL de base du relay server
 * configurée par l'utilisateur (ex: "https://relay.example.com" ou
 * "relay.example.com:8767").
 *
 * ws:// en dev uniquement (scheme http), wss:// en prod avec certificat
 * (scheme https) — même convention que HermesApiClient.buildRootUrl.
 */
object RelayUrlDeriver {

    private const val DEFAULT_SCHEME = "https"

    /** URL HTTP racine, sans slash final. "relay.example.com:8767/" -> "https://relay.example.com:8767" */
    fun httpBaseUrl(rawUrl: String): String {
        val uri = parse(rawUrl)
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "${uri.scheme}://${uri.host}$port"
    }

    /** URL WebSocket pour /ws, avec le token de session en query param. */
    fun webSocketUrl(rawUrl: String, sessionToken: String): String {
        val uri = parse(rawUrl)
        val wsScheme = if (uri.scheme == "https") "wss" else "ws"
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "$wsScheme://${uri.host}$port/ws?token=$sessionToken"
    }

    private fun parse(rawUrl: String): URI {
        val trimmed = rawUrl.trim().trimEnd('/')
        val withScheme = if ("://" in trimmed) trimmed else "$DEFAULT_SCHEME://$trimmed"
        return URI(withScheme)
    }
}
