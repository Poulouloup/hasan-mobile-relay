package com.hasan.v1.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Base de données Room — singleton. Incrémenter la version à chaque changement de schéma. */
@Database(
    entities  = [Conversation::class, Message::class, HermesSession::class],
    version   = 4,
    exportSchema = false
)
abstract class HassanDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var INSTANCE: HassanDatabase? = null

        /**
         * Migration 1→2 : ajout colonne metadata (JSON Hermes) + index sur timestamp.
         * Les lignes existantes reçoivent metadata = NULL.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN metadata TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_timestamp ON messages (timestamp)")
            }
        }

        /**
         * Migration 2→3 : création table sessions pour la gestion multi-sessions Hermes.
         * Aucune donnée existante affectée.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sessions (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        /**
         * Migration 3→4 : ajout colonne sessionId sur conversations, remplace le
         * couplage fragile "title == sessionId" (voir MainViewModel.getOrCreateConversation
         * avant cette migration). Backfill : les conversations existantes ont leur title
         * qui contient déjà l'UUID de session (ancien comportement), donc sessionId
         * hérite directement de title pour ne perdre aucune conversation existante — le
         * title reprend ensuite sa vraie vocation d'affichage au prochain message envoyé
         * sur cette conversation (voir MainViewModel), mais reste temporairement égal à
         * l'UUID pour les conversations non retouchées après la migration, sans casse.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN sessionId TEXT")
                db.execSQL("UPDATE conversations SET sessionId = title")
            }
        }

        fun getInstance(context: Context): HassanDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HassanDatabase::class.java,
                    "hasan.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
