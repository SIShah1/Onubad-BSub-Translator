package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class HistoryTranslationStatus(val value: String, val label: String) {
  PROCESSING("PROCESSING", "Processing"),
  COMPLETED("COMPLETED", "Completed"),
  PARTIAL("PARTIAL", "Partial"),
  FAILED("FAILED", "Failed"),
  ABORTED("ABORTED", "Aborted");

  companion object {
    fun fromValue(raw: String?): HistoryTranslationStatus {
      val normalized = raw?.trim().orEmpty()
      if (normalized.isEmpty()) return COMPLETED
      return entries.firstOrNull {
        it.name.equals(normalized, ignoreCase = true) ||
          it.value.equals(normalized, ignoreCase = true)
      }
        ?: when {
          normalized.startsWith("partial", ignoreCase = true) -> PARTIAL
          normalized.startsWith("fail", ignoreCase = true) -> FAILED
          normalized.startsWith("abort", ignoreCase = true) -> ABORTED
          normalized.startsWith("process", ignoreCase = true) -> PROCESSING
          else -> COMPLETED
        }
    }
  }
}

class HistoryConverters {
  @TypeConverter
  fun fromHistoryStatus(status: HistoryTranslationStatus): String = status.value

  @TypeConverter
  fun toHistoryStatus(raw: String?): HistoryTranslationStatus =
    HistoryTranslationStatus.fromValue(raw)
}

@Entity(
  tableName = "translation_history",
  indices = [Index(value = ["translationStatus", "createdAtEpochMs"])],
)
data class TranslationHistoryEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val originalFileName: String,
  val outputFileName: String = "",
  val savedPath: String = "",
  val contentUri: String = "",
  val localBackupPath: String = "",
  val mode: String,
  val titleMetadata: String,
  val totalBlocks: Int,
  val translatedBlocks: Int = 0,
  val totalLines: Int = 0,
  val fileSizeBytes: Long = 0L,
  val modelUsed: String,
  val status: String,
  val translationStatus: HistoryTranslationStatus = HistoryTranslationStatus.fromValue(status),
  val createdAtEpochMs: Long = System.currentTimeMillis(),
  val updatedAtEpochMs: Long = createdAtEpochMs,
  val errorMessage: String? = null,
)
