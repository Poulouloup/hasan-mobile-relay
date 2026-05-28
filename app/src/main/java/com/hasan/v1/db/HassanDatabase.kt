package com.hasan.v1.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** Base de données Room — singleton. Incrémenter la version à chaque changement de schéma. */
@Database(
    entities  = [Conversation::class, Message::class],
    version   = 1,
    exportSchema = false
)
abstract class HassanDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var INSTANCE: HassanDatabase? = null

        fun getInstance(context: Context): HassanDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HassanDatabase::class.java,
                    "hasan.db"
                )
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                })
                .build().also { INSTANCE = it }
            }
    }
}
