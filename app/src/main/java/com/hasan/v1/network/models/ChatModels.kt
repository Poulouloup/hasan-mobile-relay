package com.hasan.v1.network.models

/**
 * Types partagés du flux chat, indépendants du transport (WS via
 * [com.hasan.v1.network.ChatStreamHandler]) — extraits de l'ancien
 * HermesApiClient.kt HTTP/SSE, supprimé lors du passage à 100% WSS.
 */

/** Résultat d'un health check applicatif Hermes (via chat/health, voir ChatStreamHandler). */
sealed class HealthResult {
    /** Hermes accessible et répond en 2xx. */
    object Ok : HealthResult()
    /** Hermes accessible mais retourne une erreur HTTP (rapportée par le relay). */
    data class ServerError(val code: Int?) : HealthResult()
    /** Relay injoignable, ou relay incapable de joindre Hermes. */
    data class NetworkError(val message: String) : HealthResult()
}

/** Types d'erreurs réseau distingués pour un affichage UI adapté. */
enum class ErrorType {
    NO_NETWORK,
    HERMES_UNREACHABLE,
    AUTH_FAILED,
    SERVER_ERROR,
    STREAM_INTERRUPTED,
    TIMEOUT,
    INVALID_CONTEXT
}

/** Evenements du flux chat, produits par [com.hasan.v1.network.ChatStreamHandler]. */
sealed class StreamEvent {
    object Connecting : StreamEvent()
    object Connected : StreamEvent()
    data class Token(val text: String) : StreamEvent()
    /** Hermes execute un outil — message court a afficher dans une bulle thinking. */
    data class Thinking(val message: String) : StreamEvent()
    /** Hermes demande une clarification — le tour reste ouvert en attendant la reponse. */
    data class Clarify(
        val clarifyId: String,
        val question: String,
        val choices: List<String>?  // null = champ texte libre
    ) : StreamEvent()
    /**
     * Fin du stream.
     * @param responseId  ID de la reponse Hermes (ex: "resp_xxx"), null si non disponible.
     *                    Stocke par le ViewModel pour "previous_response_id" au prochain message.
     */
    data class Done(
        val responseId: String? = null,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0
    ) : StreamEvent()
    data class Error(
        val message: String,
        val type: ErrorType = ErrorType.HERMES_UNREACHABLE
    ) : StreamEvent()
    /**
     * Certificat non reconnu ou modifie — filet defensif herite du chemin
     * HTTP historique. ChatStreamHandler (WS) ne produit jamais ce type : le
     * relay parle a Hermes en HTTP local depuis le VPS, il n'y a plus de TLS
     * app->Hermes a verifier depuis le telephone (seul le TOFU du relay lui-
     * meme reste actif, gere separement par ConnectionManager). Garde pour
     * eviter un `when` non-exhaustif, cout nul.
     */
    data class CertificateCheck(
        val fingerprint: String,
        val isChanged: Boolean,
        val storedFingerprint: String?
    ) : StreamEvent()
}

/** Convertit un nom d'outil Hermes en message lisible pour la bulle thinking. */
fun toolDisplayMessage(toolName: String): String = when {
    toolName.contains("web_search", ignoreCase = true) -> "Recherche web en cours..."
    toolName.contains("spotify", ignoreCase = true) -> "Spotify en cours..."
    toolName.contains("terminal", ignoreCase = true) -> "Execution en cours..."
    toolName.contains("memory", ignoreCase = true) -> "Memorisation..."
    toolName.contains("files", ignoreCase = true) -> "Fichiers en cours..."
    toolName.contains("todo", ignoreCase = true) -> "Tache en cours..."
    toolName.contains("navigate", ignoreCase = true) ||
        toolName.contains("click", ignoreCase = true) -> "Navigation web..."
    else -> "$toolName en cours..."
}

/**
 * Extrait scheme + host + port d'une URL (supprime tout chemin).
 * "http://10.200.0.2:8642/v1" -> "http://10.200.0.2:8642"
 */
fun buildRootUrl(baseUrl: String): String {
    val trimmed = baseUrl.trimEnd('/')
    return try {
        val uri = java.net.URI(trimmed)
        val port = if (uri.port != -1) ":${uri.port}" else ""
        "${uri.scheme}://${uri.host}$port"
    } catch (_: Exception) {
        trimmed
    }
}

/**
 * Clé de stockage TOFU historique pour le certificat HTTP Hermes ("trusted_cert_<md5>").
 * Filet défensif hérité du chemin HTTP — voir StreamEvent.CertificateCheck,
 * ce chemin est inatteignable en pratique depuis le passage à 100% WSS mais
 * conservé pour ne pas casser un `when` exhaustif ni invalider les
 * fingerprints déjà approuvés par les utilisateurs existants.
 */
fun certStorageKey(baseUrl: String): String {
    val root = buildRootUrl(baseUrl)
    val hash = java.security.MessageDigest.getInstance("MD5")
        .digest(root.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return "trusted_cert_$hash"
}
