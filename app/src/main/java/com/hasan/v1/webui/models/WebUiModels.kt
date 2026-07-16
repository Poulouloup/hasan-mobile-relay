package com.hasan.v1.webui.models

/**
 * Types du flux chat hermes-webui, indépendants du transport (SSE via
 * [com.hasan.v1.webui.WebUiChatStream]). Vérifiés contre le code réel de
 * nesquena/hermes-webui (api/routes.py `_handle_chat_start`/`_sse`,
 * api/clarify.py) — pas d'invention de schéma.
 */

/** Un item de GET /api/sessions (Session.compact() côté serveur, api/models.py) — sous-ensemble utile à Hasan. */
data class WebUiSessionSummary(
    val sessionId: String,
    val title: String?,
    val model: String?,
    val messageCount: Int,
    val updatedAt: Double,
    val pinned: Boolean,
    val archived: Boolean
)

/** Evenements SSE de GET /api/chat/stream?stream_id=X (voir api/routes.py section 4.3). */
sealed class WebUiStreamEvent {
    /** event: token — delta LLM. */
    data class Token(val text: String) : WebUiStreamEvent()
    /** event: tool — invocation d'outil démarrée. */
    data class Tool(val name: String, val preview: String) : WebUiStreamEvent()
    /**
     * event: approval — le serveur attend une confirmation avant d'exécuter
     * une commande sensible (tools/approval.py). Distinct de [ClarifyPrompt] :
     * ceci est une permission d'exécution d'outil (once/session/always/deny
     * via POST /api/approval/respond), pas une clarification conversationnelle.
     */
    data class Approval(
        val command: String,
        val description: String,
        val patternKeys: List<String>
    ) : WebUiStreamEvent()
    /** event: done — fin de run réussie. [sessionRaw] est le JSON session complet, laissé brut pour que l'appelant extraie ce dont il a besoin. */
    data class Done(val sessionRaw: org.json.JSONObject?) : WebUiStreamEvent()
    /** event: error — l'agent a levé une exception côté serveur. */
    data class Error(val message: String, val trace: String?) : WebUiStreamEvent()
}

/**
 * Prompt de clarification en attente (GET /api/clarify/pending,
 * event `clarify` de GET /api/clarify/stream — voir api/clarify.py et le
 * payload construit par api/streaming.py `_clarify_callback_impl`).
 * Équivalent fonctionnel de l'ancien chat/clarify du relay WSS, sous un
 * mécanisme distinct côté hermes-webui (endpoints /api/clarify/..., pas
 * d'enveloppe multiplexée).
 *
 * [choicesOffered] vide (liste vide, pas null — le serveur envoie toujours
 * une liste, potentiellement vide pour une question ouverte) : l'UI doit
 * alors proposer une réponse libre plutôt que des boutons.
 */
data class WebUiClarifyPrompt(
    val clarifyId: String,
    val question: String,
    val choicesOffered: List<String>,
    val timeoutSeconds: Int
)

/** Résultat de POST /api/auth/login. */
sealed class WebUiLoginResult {
    object Ok : WebUiLoginResult()
    object InvalidPassword : WebUiLoginResult()
    object RateLimited : WebUiLoginResult()
    data class NetworkError(val message: String) : WebUiLoginResult()
}

/** Résultat de GET /health — endpoint public, sans cookie (vérifié en étape 1 du déploiement). */
sealed class WebUiHealthResult {
    object Ok : WebUiHealthResult()
    data class ServerError(val code: Int) : WebUiHealthResult()
    data class NetworkError(val message: String) : WebUiHealthResult()
}
