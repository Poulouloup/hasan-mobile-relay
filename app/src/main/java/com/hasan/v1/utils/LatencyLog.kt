package com.hasan.v1.utils

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Instrumentation temporaire de latence pour le pipeline chat texte
 * (envoi -> reception WS -> parsing -> flush DB -> affichage).
 *
 * Filtrer isolement avec : adb logcat -s HasanLatency:D
 *
 * A retirer une fois la cause de la latence/jitter identifiee et corrigee —
 * ce n'est pas un mecanisme de logging permanent du projet.
 */
object LatencyLog {

    private const val TAG = "HasanLatency"

    // Timestamp du dernier point logue par tour (cle = turn id) — permet de
    // calculer un delta inter-etapes sans faire circuler le timestamp
    // precedent manuellement a travers 4 fichiers differents.
    private val lastStepAt = ConcurrentHashMap<String, Long>()

    fun mark(point: String, turn: String, detail: String = "") {
        val now = System.currentTimeMillis()
        val previous = lastStepAt.put(turn, now)
        val delta = if (previous != null) "${now - previous}ms" else "-"
        val suffix = if (detail.isNotEmpty()) " $detail" else ""
        Log.d(TAG, "[$point] turn=$turn delta=$delta$suffix")
    }

    /** A appeler quand le tour se termine (Done/Error) pour eviter une fuite mémoire du registre. */
    fun clear(turn: String) {
        lastStepAt.remove(turn)
    }
}
