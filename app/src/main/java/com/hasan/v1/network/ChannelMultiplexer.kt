package com.hasan.v1.network

import com.hasan.v1.network.models.Envelope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Route les enveloppes entrantes vers un [SharedFlow] par canal
 * (system, chat, proactive, bridge). [ConnectionManager] pousse chaque
 * enveloppe reçue ici ; les consommateurs (ViewModel, service) collectent
 * le flow du canal qui les concerne sans avoir à filtrer eux-mêmes.
 */
class ChannelMultiplexer {

    private val _system = MutableSharedFlow<Envelope>(extraBufferCapacity = 8)
    val system: SharedFlow<Envelope> = _system.asSharedFlow()

    private val _chat = MutableSharedFlow<Envelope>(extraBufferCapacity = 16)
    val chat: SharedFlow<Envelope> = _chat.asSharedFlow()

    private val _proactive = MutableSharedFlow<Envelope>(extraBufferCapacity = 8)
    val proactive: SharedFlow<Envelope> = _proactive.asSharedFlow()

    private val _bridge = MutableSharedFlow<Envelope>(extraBufferCapacity = 8)
    val bridge: SharedFlow<Envelope> = _bridge.asSharedFlow()

    /** Dispatch une enveloppe entrante vers le flow de son canal. Ignore un canal inconnu. */
    fun dispatch(envelope: Envelope) {
        when (envelope.channel) {
            "system" -> _system.tryEmit(envelope)
            "chat" -> _chat.tryEmit(envelope)
            "proactive" -> _proactive.tryEmit(envelope)
            "bridge" -> _bridge.tryEmit(envelope)
        }
    }
}
