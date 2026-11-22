package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class SubtitleTranslationStatus(val value: String) {
  PENDING("pending"),
  TRANSLATED("translated");

  companion object {
    fun fromValue(raw: String?): SubtitleTranslationStatus {
      return entries.firstOrNull { it.value.equals(raw, ignoreCase = true) } ?: PENDING
    }
  }
}

class SubtitleTranslationConverters {
  @TypeConverter
  fun fromStatus(status: SubtitleTranslationStatus): String = status.value

  @TypeConverter
  fun toStatus(raw: String?): SubtitleTranslationStatus =
    SubtitleTranslationStatus.fromValue(raw)
}

@Entity(
  tableName = "subtitle_translations",
  indices = [Index(value = ["jobId", "blockIndex"])],
)
data class SubtitleTranslation(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val jobId: String,
  val fileName: String,
  val blockIndex: Int,
  val timestamp: String,
  val originalEnglishText: String,
  val translatedBengaliText: String? = null,
  val status: SubtitleTranslationStatus = SubtitleTranslationStatus.PENDING,
  val updatedAtEpochMs: Long = System.currentTimeMillis(),
)
