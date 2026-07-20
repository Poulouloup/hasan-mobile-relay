package com.hasan.v1.webui

/**
 * Constantes de backoff exponentiel pour la reconnexion des flux SSE
 * longue durée (WebUiClarifyStream/WebUiApprovalStream) — mêmes valeurs
 * que [com.hasan.v1.network.ConnectionManager] (backoff WebSocket relay)
 * pour un comportement cohérent entre les deux canaux de reconnexion de
 * l'app (voir audit v2 B6).
 */
object SseBackoff {
    const val INITIAL_MS = 1_000L
    const val MAX_MS = 5 * 60_000L
    const val MAX_ATTEMPTS_BEFORE_CAP = 20

    /** Délai avant la tentative [attempt] (0-indexé), plafonné à [MAX_MS]. */
    fun delayForAttempt(attempt: Int): Long =
        if (attempt >= MAX_ATTEMPTS_BEFORE_CAP) MAX_MS
        else (INITIAL_MS shl attempt).coerceAtMost(MAX_MS)
}
