package com.hasan.v1.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Une conversation = une session de questions/réponses. */
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Premier message de la conversation — utilisé comme titre dans l'historique. */
    val title: String = "",

    /**
     * ID de la session Hermes (HermesSession.id) à laquelle cette conversation est liée.
     * Remplace l'ancien couplage fragile "title == sessionId" (migration 3→4) — ne pas
     * réutiliser [title] pour retrouver une conversation par session.
     */
    val sessionId: String? = null,

    /** Timestamp de création (millisecondes). */
    val createdAt: Long = System.currentTimeMillis(),

    /** Timestamp de dernière activité (millisecondes). */
    val updatedAt: Long = System.currentTimeMillis(),

    /** "voice" ou "text" — pour l'icône dans l'historique. */
    val type: String = "voice"
)
