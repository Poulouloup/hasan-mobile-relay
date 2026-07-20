package com.hasan.v1.webui

/**
 * Résultat d'un appel REST authentifié hermes-webui qui distingue "pas de
 * données" d'un vrai échec — les clients webui/ (WebUiSkillsClient,
 * WebUiMemoryClient, etc.) retournaient jusqu'ici emptyList()/null aussi
 * bien en cas de succès sans contenu qu'en cas d'échec réseau/HTTP, rendant
 * les erreurs invisibles côté ViewModel/UI (voir audit v2, findings
 * Bloquants B2/B7).
 */
sealed class WebUiCallResult<out T> {
    data class Ok<T>(val value: T) : WebUiCallResult<T>()
    /** HTTP 401 — [WebUiAuthStore.clear] a déjà été appelé au moment où ce résultat est produit. */
    object Unauthorized : WebUiCallResult<Nothing>()
    data class HttpError(val code: Int) : WebUiCallResult<Nothing>()
    data class NetworkError(val message: String) : WebUiCallResult<Nothing>()
}
