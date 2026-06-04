package com.hasan.v1.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Base de données Room — singleton. Incrémenter la version à chaque changement de schéma. */
@Database(
    entities  = [Conversation::class, Message::class],
    version   = 2,
    exportSchema = false
)
abstract class HassanDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var INSTANCE: HassanDatabase? = null

        /**
         * Migration 1→2 : ajout colonne metadata (JSON Hermes) + index sur timestamp.
         * Les lignes existantes reçoivent metadata = NULL (valeur par défaut).
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN metadata TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_timestamp ON messages (timestamp)")
            }
        }

        fun getInstance(context: Context): HassanDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HassanDatabase::class.java,
                    "hasan.db"
                )
                .addMigrations(MIGRATION_1_2)
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
