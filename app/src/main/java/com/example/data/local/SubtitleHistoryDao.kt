package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SubtitleHistoryDao {

  @Query("SELECT * FROM subtitle_history ORDER BY updatedAtEpochMs DESC, processedAtEpochMs DESC, id DESC")
  fun getAllHistory(): Flow<List<SubtitleHistory>>

  @Query(
    "SELECT * FROM subtitle_history WHERE status = :status ORDER BY updatedAtEpochMs DESC, processedAtEpochMs DESC, id DESC"
  )
  fun getHistoryByStatus(status: HistoryTranslationStatus): Flow<List<SubtitleHistory>>

  @Query(
    """
    SELECT * FROM subtitle_history
    WHERE originalFileName LIKE '%' || :query || '%'
       OR outputFileName LIKE '%' || :query || '%'
       OR titleMetadata LIKE '%' || :query || '%'
    ORDER BY updatedAtEpochMs DESC, processedAtEpochMs DESC, id DESC
    """
  )
  fun searchByFileName(query: String): Flow<List<SubtitleHistory>>

  @Query(
    "SELECT * FROM subtitle_history WHERE originalFileName = :originalFileName ORDER BY updatedAtEpochMs DESC, id DESC"
  )
  fun getByOriginalFileName(originalFileName: String): Flow<List<SubtitleHistory>>

  @Query("SELECT * FROM subtitle_history WHERE id = :id LIMIT 1")
  suspend fun getById(id: Long): SubtitleHistory?

  @Query("SELECT COUNT(*) FROM subtitle_history")
  fun observeCount(): Flow<Int>

  @Query("SELECT COUNT(*) FROM subtitle_history WHERE status = :status")
  fun observeCountByStatus(status: HistoryTranslationStatus): Flow<Int>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(record: SubtitleHistory): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(records: List<SubtitleHistory>): List<Long>

  @Update
  suspend fun update(record: SubtitleHistory)

  @Query(
    """
    UPDATE subtitle_history
    SET status = :status,
        statusLabel = :statusLabel,
        translatedBlocks = :translatedBlocks,
        outputFileName = :outputFileName,
        savedPath = :savedPath,
        contentUri = :contentUri,
        localBackupPath = :localBackupPath,
        fileSizeBytes = :fileSizeBytes,
        errorMessage = :errorMessage,
        updatedAtEpochMs = :updatedAtEpochMs
    WHERE id = :id
    """
  )
  suspend fun updateProcessingResult(
    id: Long,
    status: HistoryTranslationStatus,
    statusLabel: String,
    translatedBlocks: Int,
    outputFileName: String,
    savedPath: String,
    contentUri: String,
    localBackupPath: String,
    fileSizeBytes: Long,
    errorMessage: String? = null,
    updatedAtEpochMs: Long = System.currentTimeMillis(),
  )

  @Delete
  suspend fun delete(record: SubtitleHistory)

  @Query("DELETE FROM subtitle_history WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("DELETE FROM subtitle_history")
  suspend fun deleteAll()
}
