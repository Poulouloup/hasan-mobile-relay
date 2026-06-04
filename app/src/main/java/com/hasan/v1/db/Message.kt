package com.hasan.v1.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Un message individuel dans une conversation. */
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity        = Conversation::class,
        parentColumns = ["id"],
        childColumns  = ["conversationId"],
        onDelete      = ForeignKey.CASCADE
    )],
    indices = [
        Index("conversationId"),
        Index("timestamp")          // requêtes triées par date
    ]
)
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val conversationId: Long,

    /** "user" ou "assistant" — compatible avec le format messages[] de l'API. */
    val role: String,

    val content: String,

    val timestamp: Long = System.currentTimeMillis(),

    /** Vrai pendant le streaming SSE — le contenu est encore en cours d'arrivée. */
    val isStreaming: Boolean = false,

    /**
     * Métadonnées JSON Hermes stockées après réception de response.completed.
     * Format : {"response_id":"resp_xxx","model":"hermes-agent","input_tokens":16366,"output_tokens":42}
     * Null pour les messages utilisateur et les anciens messages migrés.
     */
    val metadata: String? = null
)
