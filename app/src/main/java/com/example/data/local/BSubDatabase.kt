package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [
    TranslationHistoryEntity::class,
    SubtitleTranslation::class,
    SubtitleHistory::class,
    ApiKeyUsageStat::class,
    ApiUsageEvent::class,
  ],
  version = 5,
  exportSchema = false,
)
@TypeConverters(SubtitleTranslationConverters::class, HistoryConverters::class)
abstract class BSubDatabase : RoomDatabase() {
  abstract fun translationHistoryDao(): TranslationHistoryDao

  abstract fun subtitleTranslationDao(): SubtitleTranslationDao

  abstract fun subtitleHistoryDao(): SubtitleHistoryDao

  abstract fun apiUsageDao(): ApiUsageDao

  companion object {
    @Volatile private var instance: BSubDatabase? = null

    val MIGRATION_1_2 =
      object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `subtitle_translations` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `jobId` TEXT NOT NULL,
              `fileName` TEXT NOT NULL,
              `blockIndex` INTEGER NOT NULL,
              `timestamp` TEXT NOT NULL,
              `originalEnglishText` TEXT NOT NULL,
              `translatedBengaliText` TEXT,
              `status` TEXT NOT NULL,
              `updatedAtEpochMs` INTEGER NOT NULL
            )
            """.trimIndent()
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_subtitle_translations_jobId_blockIndex` ON `subtitle_translations` (`jobId`, `blockIndex`)"
          )
        }
      }

    val MIGRATION_2_3 =
      object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            "ALTER TABLE `translation_history` ADD COLUMN `translationStatus` TEXT NOT NULL DEFAULT 'COMPLETED'"
          )
          db.execSQL(
            "ALTER TABLE `translation_history` ADD COLUMN `updatedAtEpochMs` INTEGER NOT NULL DEFAULT 0"
          )
          db.execSQL(
            "ALTER TABLE `translation_history` ADD COLUMN `errorMessage` TEXT"
          )
          db.execSQL(
            """
            UPDATE `translation_history`
            SET `translationStatus` = CASE
              WHEN LOWER(`status`) LIKE 'partial%' THEN 'PARTIAL'
              WHEN LOWER(`status`) LIKE 'fail%' THEN 'FAILED'
              WHEN LOWER(`status`) LIKE 'abort%' THEN 'ABORTED'
              WHEN LOWER(`status`) LIKE 'process%' THEN 'PROCESSING'
              ELSE 'COMPLETED'
            END,
            `updatedAtEpochMs` = CASE
              WHEN `updatedAtEpochMs` = 0 THEN `createdAtEpochMs`
              ELSE `updatedAtEpochMs`
            END
            """.trimIndent()
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_translation_history_translationStatus_createdAtEpochMs` ON `translation_history` (`translationStatus`, `createdAtEpochMs`)"
          )
        }
      }

    val MIGRATION_3_4 =
      object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `subtitle_history` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `originalFileName` TEXT NOT NULL,
              `outputFileName` TEXT NOT NULL,
              `savedPath` TEXT NOT NULL,
              `contentUri` TEXT NOT NULL,
              `localBackupPath` TEXT NOT NULL,
              `mode` TEXT NOT NULL,
              `titleMetadata` TEXT NOT NULL,
              `totalBlocks` INTEGER NOT NULL,
              `translatedBlocks` INTEGER NOT NULL,
              `totalLines` INTEGER NOT NULL,
              `fileSizeBytes` INTEGER NOT NULL,
              `modelUsed` TEXT NOT NULL,
              `status` TEXT NOT NULL,
              `statusLabel` TEXT NOT NULL,
              `processedAtEpochMs` INTEGER NOT NULL,
              `updatedAtEpochMs` INTEGER NOT NULL,
              `errorMessage` TEXT
            )
            """.trimIndent()
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_subtitle_history_status_processedAtEpochMs` ON `subtitle_history` (`status`, `processedAtEpochMs`)"
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_subtitle_history_originalFileName` ON `subtitle_history` (`originalFileName`)"
          )
          db.execSQL(
            """
            INSERT INTO `subtitle_history` (
              `originalFileName`, `outputFileName`, `savedPath`, `contentUri`, `localBackupPath`,
              `mode`, `titleMetadata`, `totalBlocks`, `translatedBlocks`, `totalLines`,
              `fileSizeBytes`, `modelUsed`, `status`, `statusLabel`, `processedAtEpochMs`,
              `updatedAtEpochMs`, `errorMessage`
            )
            SELECT
              `originalFileName`, `outputFileName`, `savedPath`, `contentUri`, `localBackupPath`,
              `mode`, `titleMetadata`, `totalBlocks`, `translatedBlocks`, `totalLines`,
              `fileSizeBytes`, `modelUsed`, `translationStatus`, `status`, `createdAtEpochMs`,
              `updatedAtEpochMs`, `errorMessage`
            FROM `translation_history`
            """.trimIndent()
          )
        }
      }

    val MIGRATION_4_5 =
      object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `api_key_usage_stats` (
              `keyId` TEXT NOT NULL,
              `keyLabel` TEXT NOT NULL,
              `provider` TEXT NOT NULL,
              `successfulRequests` INTEGER NOT NULL,
              `rateLimitHits` INTEGER NOT NULL,
              `quotaExhaustedHits` INTEGER NOT NULL,
              `retryableErrors` INTEGER NOT NULL,
              `invalidKeyHits` INTEGER NOT NULL,
              `totalLatencyMs` INTEGER NOT NULL,
              `latencySamples` INTEGER NOT NULL,
              `lastLatencyMs` INTEGER NOT NULL,
              `blocksTranslated` INTEGER NOT NULL,
              `lastOutcome` TEXT NOT NULL,
              `lastError` TEXT,
              `lastUsedAtEpochMs` INTEGER NOT NULL,
              PRIMARY KEY(`keyId`)
            )
            """.trimIndent()
          )
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `api_usage_events` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `keyId` TEXT NOT NULL,
              `keyLabel` TEXT NOT NULL,
              `provider` TEXT NOT NULL,
              `model` TEXT NOT NULL,
              `batchSize` INTEGER NOT NULL,
              `dynamicBatchSizeAfter` INTEGER NOT NULL,
              `latencyMs` INTEGER NOT NULL,
              `outcome` TEXT NOT NULL,
              `httpCode` INTEGER,
              `blocksTranslated` INTEGER NOT NULL,
              `recordedAtEpochMs` INTEGER NOT NULL
            )
            """.trimIndent()
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_api_usage_events_provider_recordedAtEpochMs` ON `api_usage_events` (`provider`, `recordedAtEpochMs`)"
          )
        }
      }

    fun getInstance(context: Context): BSubDatabase {
      return instance
        ?: synchronized(this) {
          instance
            ?: Room.databaseBuilder(
                context.applicationContext,
                BSubDatabase::class.java,
                "bsub_translator.db",
              )
              .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
              .build()
              .also { instance = it }
        }
    }
  }
}
