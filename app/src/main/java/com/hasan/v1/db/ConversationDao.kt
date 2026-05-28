package com.hasan.v1.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    /** Toutes les conversations, triées par activité récente. */
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Conversation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: Conversation): Long

    @Update
    suspend fun update(conversation: Conversation)

    @Delete
    suspend fun delete(conversation: Conversation)

    /** Supprime toutes les conversations (et les messages par CASCADE). */
    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    /** Version one-shot pour export. */
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun getAllOnce(): List<Conversation>

    /** La conversation la plus récente (pour restauration au démarrage). */
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getMostRecent(): Conversation?
}
