package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class RoomTranslationProgress(
  val totalBlocks: Int = 0,
  val translatedBlocks: Int = 0,
  val pendingBlocks: Int = 0,
  val activeFileName: String? = null,
) {
  val progressFraction: Float
    get() =
      if (totalBlocks <= 0) 0f
      else (translatedBlocks.toFloat() / totalBlocks.toFloat()).coerceIn(0f, 1f)

  val progressPercent: Int
    get() = (progressFraction * 100f).toInt().coerceIn(0, 100)
}

data class FileTranslationProgress(
  val jobId: String,
  val fileName: String,
  val totalBlocks: Int,
  val translatedBlocks: Int,
  val pendingBlocks: Int,
) {
  val progressFraction: Float
    get() =
      if (totalBlocks <= 0) 0f
      else (translatedBlocks.toFloat() / totalBlocks.toFloat()).coerceIn(0f, 1f)

  val progressPercent: Int
    get() = (progressFraction * 100f).toInt().coerceIn(0, 100)
}

@Dao
interface SubtitleTranslationDao {

  @Query("SELECT * FROM subtitle_translations ORDER BY id DESC")
  fun getAllTranslations(): Flow<List<SubtitleTranslation>>

  @Query("SELECT * FROM subtitle_translations WHERE status = :status ORDER BY id DESC")
  fun getAllByStatus(status: SubtitleTranslationStatus): Flow<List<SubtitleTranslation>>

  @Query("SELECT * FROM subtitle_translations WHERE jobId = :jobId ORDER BY blockIndex ASC")
  fun getTranslationsForJob(jobId: String): Flow<List<SubtitleTranslation>>

  @Query(
    "SELECT * FROM subtitle_translations WHERE jobId = :jobId AND status = :status ORDER BY blockIndex ASC"
  )
  fun getTranslationsByStatus(
    jobId: String,
    status: SubtitleTranslationStatus,
  ): Flow<List<SubtitleTranslation>>

  @Query("SELECT * FROM subtitle_translations WHERE id = :id LIMIT 1")
  suspend fun getById(id: Long): SubtitleTranslation?

  @Query("SELECT jobId FROM subtitle_translations WHERE fileName = :fileName ORDER BY id DESC LIMIT 1")
  suspend fun getLatestJobIdForFileName(fileName: String): String?

  @Query("SELECT * FROM subtitle_translations WHERE jobId = :jobId ORDER BY blockIndex ASC, id ASC")
  suspend fun getByJobIdOnce(jobId: String): List<SubtitleTranslation>

  @Query("SELECT * FROM subtitle_translations WHERE fileName = :fileName ORDER BY blockIndex ASC, id ASC")
  suspend fun getByFileNameOnce(fileName: String): List<SubtitleTranslation>

  @Query("SELECT COUNT(*) FROM subtitle_translations WHERE jobId = :jobId AND status = :status")
  fun observeCountByStatus(jobId: String, status: SubtitleTranslationStatus): Flow<Int>

  @Query(
    """
    SELECT
      COUNT(*) AS totalBlocks,
      COALESCE(SUM(CASE WHEN status = 'translated' THEN 1 ELSE 0 END), 0) AS translatedBlocks,
      COALESCE(SUM(CASE WHEN status = 'pending' THEN 1 ELSE 0 END), 0) AS pendingBlocks,
      (SELECT fileName FROM subtitle_translations ORDER BY updatedAtEpochMs DESC, id DESC LIMIT 1) AS activeFileName
    FROM subtitle_translations
    """
  )
  fun observeOverallProgress(): Flow<RoomTranslationProgress>

  @Query(
    """
    SELECT
      jobId,
      fileName,
      COUNT(*) AS totalBlocks,
      COALESCE(SUM(CASE WHEN status = 'translated' THEN 1 ELSE 0 END), 0) AS translatedBlocks,
      COALESCE(SUM(CASE WHEN status = 'pending' THEN 1 ELSE 0 END), 0) AS pendingBlocks
    FROM subtitle_translations
    GROUP BY jobId, fileName
    ORDER BY MIN(id) ASC
    """
  )
  fun observeFileProgressList(): Flow<List<FileTranslationProgress>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(items: List<SubtitleTranslation>): List<Long>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(item: SubtitleTranslation): Long

  @Update
  suspend fun update(item: SubtitleTranslation)

  @Query(
    """
    UPDATE subtitle_translations
    SET translatedBengaliText = :translatedBengaliText,
        status = :status,
        updatedAtEpochMs = :updatedAtEpochMs
    WHERE id = :id
    """
  )
  suspend fun updateTranslationById(
    id: Long,
    translatedBengaliText: String,
    status: SubtitleTranslationStatus = SubtitleTranslationStatus.TRANSLATED,
    updatedAtEpochMs: Long = System.currentTimeMillis(),
  )

  @Query(
    """
    UPDATE subtitle_translations
    SET translatedBengaliText = :translatedBengaliText,
        status = :status,
        updatedAtEpochMs = :updatedAtEpochMs
    WHERE jobId = :jobId AND blockIndex = :blockIndex
    """
  )
  suspend fun updateTranslationResult(
    jobId: String,
    blockIndex: Int,
    translatedBengaliText: String,
    status: SubtitleTranslationStatus = SubtitleTranslationStatus.TRANSLATED,
    updatedAtEpochMs: Long = System.currentTimeMillis(),
  )

  @Query("DELETE FROM subtitle_translations WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("DELETE FROM subtitle_translations WHERE jobId = :jobId")
  suspend fun deleteByJobId(jobId: String)

  @Query("DELETE FROM subtitle_translations")
  suspend fun deleteAll()
}
