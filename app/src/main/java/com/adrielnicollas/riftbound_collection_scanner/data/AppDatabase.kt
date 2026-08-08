package com.adrielnicollas.riftbound_collection_scanner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CardEntity::class, ScanDraftEntity::class],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao

    companion object {
        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN cardNumber TEXT NOT NULL DEFAULT ''")
            }
        }

        private val migration2To5 = object : Migration(2, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN scannedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cards ADD COLUMN scanDate TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cards ADD COLUMN cardKey TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scan_drafts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId TEXT NOT NULL,
                        imagePath TEXT NOT NULL,
                        ocrText TEXT NOT NULL,
                        name TEXT NOT NULL,
                        cardNumber TEXT NOT NULL,
                        cost INTEGER,
                        cardType TEXT NOT NULL,
                        domain TEXT NOT NULL,
                        scannedAt INTEGER NOT NULL,
                        scanDate TEXT NOT NULL,
                        captureOrder INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scan_drafts_sessionId_captureOrder ON scan_drafts(sessionId, captureOrder)")
                recreateCardsTable(db)
            }
        }

        private val migration3To5 = object : Migration(3, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                recreateCardsTable(db)
                db.execSQL("DROP TABLE IF EXISTS card_price_history")
            }
        }

        private val migration4To5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                recreateCardsTable(db)
                db.execSQL("DROP TABLE IF EXISTS card_price_history")
            }
        }

        private val migration5To6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN might INTEGER")
                db.execSQL("ALTER TABLE scan_drafts ADD COLUMN might INTEGER")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "riftbound_cards.db",
                )
                    .addMigrations(migration1To2, migration2To5, migration3To5, migration4To5, migration5To6)
                    .build()
                    .also { instance = it }
            }
        }

        private fun recreateCardsTable(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS cards_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    cardNumber TEXT NOT NULL,
                    cost INTEGER,
                    cardType TEXT NOT NULL,
                    domain TEXT NOT NULL,
                    imagePath TEXT NOT NULL,
                    ocrText TEXT NOT NULL,
                    scannedAt INTEGER NOT NULL,
                    scanDate TEXT NOT NULL,
                    cardKey TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO cards_new (
                    id,
                    name,
                    cardNumber,
                    cost,
                    cardType,
                    domain,
                    imagePath,
                    ocrText,
                    scannedAt,
                    scanDate,
                    cardKey,
                    createdAt
                )
                SELECT
                    id,
                    name,
                    cardNumber,
                    cost,
                    cardType,
                    domain,
                    imagePath,
                    ocrText,
                    scannedAt,
                    scanDate,
                    cardKey,
                    createdAt
                FROM cards
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE cards")
            db.execSQL("ALTER TABLE cards_new RENAME TO cards")
        }
    }
}
