package com.hasan.v1.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** Une ligne de l'onglet Activité — événement relay/connexion/enveloppe, en mémoire uniquement. */
data class ActivityEvent(
    val id: Long,
    val timestampMillis: Long,
    val title: String,
    val tag: String
)

/**
 * Journal d'événements en mémoire pour l'onglet Activité (voir docs/design/hasan-mockup-v2.html
 * #tab-activity). Volontairement non persisté (pas de Room) : l'historique repart à zéro à
 * chaque redémarrage de l'app, décision validée — ce sont des événements de diagnostic
 * transitoires, pas des données métier. Borné à [MAX_EVENTS] pour éviter une fuite mémoire
 * sur une session longue.
 */
class ActivityLog {

    companion object {
        private const val MAX_EVENTS = 100
    }

    private val nextId = AtomicLong(0)

    private val _events = MutableStateFlow<List<ActivityEvent>>(emptyList())
    val events: StateFlow<List<ActivityEvent>> = _events.asStateFlow()

    fun log(title: String, tag: String) {
        val event = ActivityEvent(
            id = nextId.getAndIncrement(),
            timestampMillis = System.currentTimeMillis(),
            title = title,
            tag = tag
        )
        _events.update { current -> (current + event).takeLast(MAX_EVENTS) }
    }
}
