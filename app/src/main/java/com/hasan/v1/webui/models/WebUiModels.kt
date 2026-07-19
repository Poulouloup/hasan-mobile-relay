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

/**
 * Evenements SSE de GET /api/chat/stream?stream_id=X — schéma vérifié
 * exhaustivement contre le code réel du serveur (api/streaming.py, tous les
 * `put(event, data)` de `_run_agent_streaming`), pas deviné. Le serveur émet
 * en réalité 19 types d'événements distincts ; seuls ceux pertinents pour
 * l'affichage du chat/steer/stop/tool-calls sont modélisés ici. Les autres
 * (reasoning, interim_assistant, metering, context_status, compressing,
 * compressed, warning, goal, goal_continue) sont délibérément ignorés —
 * hors périmètre de l'étape 4.4, voir WebUiChatStream.parseEvent (retourne
 * null pour ces events plutôt que d'inventer une variante non consommée).
 */
sealed class WebUiStreamEvent {
    /** event: token — delta LLM. */
    data class Token(val text: String) : WebUiStreamEvent()
    /** event: tool — invocation d'outil démarrée. */
    data class Tool(val name: String, val preview: String) : WebUiStreamEvent()
    /** event: tool_complete — outil terminé (succès ou échec, avec durée). */
    data class ToolComplete(
        val name: String,
        val preview: String,
        val isError: Boolean,
        val durationMs: Double?
    ) : WebUiStreamEvent()
    /** event: done — fin de run réussie. [sessionRaw] est le JSON session complet, laissé brut pour que l'appelant extraie ce dont il a besoin. */
    data class Done(val sessionRaw: org.json.JSONObject?) : WebUiStreamEvent()
    /**
     * event: apperror — l'agent a levé une exception applicative côté
     * serveur. C'est le VRAI nom d'event émis par le serveur (vérifié dans
     * api/streaming.py) — un event nommé "error" n'existe pas dans le
     * schéma réel, contrairement à ce que suggérait le nom de cette classe
     * avant l'audit de l'étape 4.4.
     */
    data class AppError(val message: String, val trace: String?) : WebUiStreamEvent()
    /** event: cancel — le run a été annulé (via GET /api/chat/cancel ou une erreur pré-run). Distinct de [AppError] : pas un échec, une interruption volontaire. */
    data class Cancel(val message: String) : WebUiStreamEvent()
    /** event: stream_end — fin de la connexion SSE elle-même (peut suivre done/cancel/apperror, ou survenir sans eux dans certains chemins serveur). */
    object StreamEnd : WebUiStreamEvent()
    /**
     * event: pending_steer_leftover — un POST /api/chat/steer a été accepté
     * mais le tour s'est terminé avant qu'il ne soit consommé (pas de
     * boundary de résultat d'outil atteinte). Le serveur renvoie le texte
     * pour que le client le mette en attente du prochain tour plutôt que de
     * le perdre silencieusement.
     */
    data class PendingSteerLeftover(val text: String) : WebUiStreamEvent()
    /**
     * event: title — titre de session généré par LLM en tâche de fond après
     * `done` (voir api/streaming.py `_run_background_title_update`), émis
     * juste avant `stream_end` sur le même flux (pas un flux séparé) quand
     * le titrage automatique s'applique. Remplace le titre local tronqué
     * (80 premiers caractères du message) par le vrai titre serveur.
     */
    data class Title(val title: String) : WebUiStreamEvent()
}

/** Résultat de POST /api/chat/steer — voir api/streaming.py `_handle_chat_steer`. */
sealed class WebUiSteerResult {
    /** Le texte a été injecté dans le run actif (appliqué au prochain résultat d'outil, pas immédiat). */
    data class Accepted(val streamId: String) : WebUiSteerResult()
    /**
     * Refusé par le serveur — [fallback] porte la raison exacte
     * (no_cached_agent, not_running, stream_dead, agent_lacks_steer,
     * session_not_found, steer_error, gateway_steer_queued). Le serveur est
     * explicite : un steer refusé n'autorise PAS un fallback implicite vers
     * cancel+renvoi — l'appelant doit juste informer l'utilisateur.
     */
    data class Rejected(val fallback: String) : WebUiSteerResult()
    data class NetworkError(val message: String) : WebUiSteerResult()
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
