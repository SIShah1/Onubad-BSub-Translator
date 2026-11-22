package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "subtitle_history",
  indices =
    [
      Index(value = ["status", "processedAtEpochMs"]),
      Index(value = ["originalFileName"]),
    ],
)
data class SubtitleHistory(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val originalFileName: String,
  val outputFileName: String = "",
  val savedPath: String = "",
  val contentUri: String = "",
  val localBackupPath: String = "",
  val mode: String = "MOVIE",
  val titleMetadata: String = "",
  val totalBlocks: Int = 0,
  val translatedBlocks: Int = 0,
  val totalLines: Int = 0,
  val fileSizeBytes: Long = 0L,
  val modelUsed: String = "",
  val status: HistoryTranslationStatus = HistoryTranslationStatus.PROCESSING,
  val statusLabel: String = status.label,
  val processedAtEpochMs: Long = System.currentTimeMillis(),
  val updatedAtEpochMs: Long = processedAtEpochMs,
  val errorMessage: String? = null,
)
