package com.hasan.v1.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /** Tous les messages d'une conversation, dans l'ordre chronologique. */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: Long): Flow<List<Message>>

    /** Version suspend pour récupérer l'historique une seule fois (envoi à Hermes). */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesForConversationOnce(conversationId: Long): List<Message>

    /** Nombre d'échanges dans une conversation (paires user+assistant). */
    @Query("SELECT COUNT(*) / 2 FROM messages WHERE conversationId = :conversationId")
    suspend fun getExchangeCount(conversationId: Long): Int

    /** Nombre total de messages dans une conversation. */
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countForConversation(conversationId: Long): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: Message): Long

    @Update
    suspend fun update(message: Message)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: Long)

    /** Supprime tous les placeholders streaming orphelins (app killée pendant un stream). */
    @Query("DELETE FROM messages WHERE isStreaming = 1")
    suspend fun deleteAllStreaming()
}
