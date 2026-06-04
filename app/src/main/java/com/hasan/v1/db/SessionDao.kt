package com.hasan.v1.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    /** Toutes les sessions triées par dernière activité, observables en temps réel. */
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<HermesSession>>

    /** Session actuellement active, ou null si aucune. */
    @Query("SELECT * FROM sessions WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): HermesSession?

    /** Session par ID. */
    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): HermesSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: HermesSession)

    @Update
    suspend fun update(session: HermesSession)

    @Delete
    suspend fun delete(session: HermesSession)

    /** Supprime toutes les sessions (utilisé lors du reset complet). */
    @Query("DELETE FROM sessions")
    suspend fun deleteAll()

    /**
     * Active une session et désactive toutes les autres.
     * Deux requêtes atomiques dans une transaction Room.
     */
    @Query("UPDATE sessions SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE sessions SET isActive = 1 WHERE id = :id")
    suspend fun activateById(id: String)

    /** Met à jour le timestamp d'activité de la session active. */
    @Query("UPDATE sessions SET updatedAt = :timestamp WHERE id = :id")
    suspend fun touchSession(id: String, timestamp: Long = System.currentTimeMillis())
}
