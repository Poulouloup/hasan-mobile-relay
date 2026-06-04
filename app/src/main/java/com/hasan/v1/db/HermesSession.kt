package com.hasan.v1.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Une session Hermes = une conversation continue identifiée par un UUID stable.
 * L'UUID est envoyé dans chaque requête POST /v1/responses sous la clé "conversation_id",
 * ce qui permet à Hermes de maintenir le contexte entre les messages.
 *
 * Une seule session peut être active à la fois (isActive = true).
 */
@Entity(tableName = "sessions")
data class HermesSession(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    /** Nom affiché dans la liste — "Session du JJ/MM" par défaut. */
    val name: String,

    /** Timestamp de création en millisecondes. */
    val createdAt: Long = System.currentTimeMillis(),

    /** Timestamp de dernière activité (mis à jour à chaque message). */
    val updatedAt: Long = System.currentTimeMillis(),

    /** Vrai pour la session courante — une seule session active à la fois. */
    val isActive: Boolean = false
)
