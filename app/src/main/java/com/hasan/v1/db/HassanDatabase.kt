package com.hasan.v1.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hasan.v1.SettingsManager
import com.hasan.v1.utils.LatencyLog
import java.io.File

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

        private const val DB_NAME = "hasan.db"
        private val SQLITE_HEADER = byteArrayOf(
            'S'.code.toByte(), 'Q'.code.toByte(), 'L'.code.toByte(), 'i'.code.toByte(),
            't'.code.toByte(), 'e'.code.toByte(), ' '.code.toByte(), 'f'.code.toByte(),
            'o'.code.toByte(), 'r'.code.toByte(), 'm'.code.toByte(), 'a'.code.toByte(),
            't'.code.toByte(), ' '.code.toByte(), '3'.code.toByte(), 0
        )

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

        /**
         * Convertit un fichier hasan.db SQLite standard (non chiffré) en base
         * SQLCipher chiffrée avec [passphrase], sans perte de données (finding #6
         * audit sécurité — historique de conversation en clair sur disque).
         *
         * No-op si le fichier n'existe pas (nouvelle installation, Room créera
         * directement une base chiffrée) ou s'il est déjà chiffré (signature SQLite
         * standard absente des 16 premiers bytes — un fichier SQLCipher n'a pas cette
         * signature en clair).
         *
         * Un backup hasan.db.bak est conservé indéfiniment après migration réussie —
         * filet de récupération manuel, jamais supprimé automatiquement. En cas
         * d'échec à n'importe quelle étape, hasan.db original n'est jamais supprimé :
         * on préfère un crash au prochain Room.databaseBuilder() (visible, diagnostic
         * possible via hasan.db.bak) à une perte silencieuse de l'historique.
         */
        private fun migratePlaintextDbIfNeeded(context: Context, passphrase: ByteArray) {
            net.sqlcipher.database.SQLiteDatabase.loadLibs(context)
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists() || dbFile.length() == 0L) return

            val header = ByteArray(16)
            val headerLen = dbFile.inputStream().use { it.read(header) }
            val isPlaintext = headerLen == 16 && header.contentEquals(SQLITE_HEADER)
            if (!isPlaintext) return

            LatencyLog.mark("ROOM_DB_MIGRATION", "startup", "start size=${dbFile.length()}")
            val backupFile = File(dbFile.parentFile, "$DB_NAME.bak")
            try {
                dbFile.copyTo(backupFile, overwrite = true)
                LatencyLog.mark("ROOM_DB_MIGRATION", "startup", "backup created path=${backupFile.name}")

                val encryptedTmp = File(dbFile.parentFile, "$DB_NAME.encrypting.tmp")
                encryptedTmp.delete()

                val plaintextDb = net.sqlcipher.database.SQLiteDatabase.openOrCreateDatabase(dbFile, "", null)
                // KEY attend un littéral hexadécimal SQLite (x'...', pas de guillemets simples
                // autour) pour une passphrase binaire brute — passphraseToSqlHex() produit déjà
                // ce littéral complet, l'envelopper dans des quotes supplémentaires (comme dans
                // la version précédente) cassait le parsing SQL ("unrecognized token").
                plaintextDb.rawExecSQL(
                    "ATTACH DATABASE '${encryptedTmp.absolutePath}' AS encrypted KEY ${passphraseToSqlHex(passphrase)}"
                )
                plaintextDb.rawExecSQL("SELECT sqlcipher_export('encrypted')")
                plaintextDb.rawExecSQL("DETACH DATABASE encrypted")
                plaintextDb.close()

                // Repartir sur un WAL frais côté fichier chiffré — évite tout état
                // -shm/-wal orphelin incohérent avec le nouveau fichier principal.
                File(dbFile.parentFile, "$DB_NAME-shm").delete()
                File(dbFile.parentFile, "$DB_NAME-wal").delete()

                if (!encryptedTmp.renameTo(dbFile)) {
                    throw java.io.IOException("échec renameTo encrypted -> $DB_NAME")
                }
                LatencyLog.mark("ROOM_DB_MIGRATION", "startup", "success newSize=${dbFile.length()}")
            } catch (e: Exception) {
                LatencyLog.mark("ROOM_DB_MIGRATION", "startup", "FAILED error=${e.message}")
                // hasan.db original n'a pas été touché tant que renameTo() n'a pas
                // réussi — le fichier plaintext reste ouvrable par Room standard au
                // pire des cas (chiffrement simplement pas appliqué cette fois-ci,
                // retentera au prochain lancement). hasan.db.bak reste disponible.
            }
        }

        /** SQLCipher attend une clé hexadécimale littérale ("x'...'") pour une passphrase binaire brute dans un ATTACH ... KEY. */
        private fun passphraseToSqlHex(passphrase: ByteArray): String =
            "x'" + passphrase.joinToString("") { "%02x".format(it) } + "'"

        fun getInstance(context: Context): HassanDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext
                    val passphrase = SettingsManager(appContext).getOrCreateRoomDbKey()
                    migratePlaintextDbIfNeeded(appContext, passphrase)
                    val factory = net.sqlcipher.database.SupportFactory(passphrase)
                    Room.databaseBuilder(appContext, HassanDatabase::class.java, DB_NAME)
                        .openHelperFactory(factory)
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
}
