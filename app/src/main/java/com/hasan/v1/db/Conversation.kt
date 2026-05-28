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

    /** Timestamp de création (millisecondes). */
    val createdAt: Long = System.currentTimeMillis(),

    /** Timestamp de dernière activité (millisecondes). */
    val updatedAt: Long = System.currentTimeMillis(),

    /** "voice" ou "text" — pour l'icône dans l'historique. */
    val type: String = "voice"
)
