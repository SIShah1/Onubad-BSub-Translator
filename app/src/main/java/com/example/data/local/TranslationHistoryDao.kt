package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslationHistoryDao {
  @Query("SELECT * FROM translation_history ORDER BY updatedAtEpochMs DESC, createdAtEpochMs DESC, id DESC")
  fun getAllHistory(): Flow<List<TranslationHistoryEntity>>

  @Query(
    "SELECT * FROM translation_history WHERE translationStatus = :status ORDER BY updatedAtEpochMs DESC, createdAtEpochMs DESC, id DESC"
  )
  fun getHistoryByStatus(status: HistoryTranslationStatus): Flow<List<TranslationHistoryEntity>>

  @Query(
    """
    SELECT * FROM translation_history
    WHERE originalFileName LIKE '%' || :query || '%'
       OR outputFileName LIKE '%' || :query || '%'
       OR titleMetadata LIKE '%' || :query || '%'
    ORDER BY updatedAtEpochMs DESC, createdAtEpochMs DESC, id DESC
    """
  )
  fun searchHistory(query: String): Flow<List<TranslationHistoryEntity>>

  @Query("SELECT * FROM translation_history WHERE id = :id LIMIT 1")
  suspend fun getById(id: Long): TranslationHistoryEntity?

  @Query("SELECT COUNT(*) FROM translation_history")
  fun observeTotalCount(): Flow<Int>

  @Query("SELECT COUNT(*) FROM translation_history WHERE translationStatus = :status")
  fun observeCountByStatus(status: HistoryTranslationStatus): Flow<Int>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertHistory(item: TranslationHistoryEntity): Long

  @Update
  suspend fun updateHistory(item: TranslationHistoryEntity)

  @Query(
    """
    UPDATE translation_history
    SET translatedBlocks = :translatedBlocks,
        updatedAtEpochMs = :updatedAtEpochMs
    WHERE id = :id
    """
  )
  suspend fun updateProgress(
    id: Long,
    translatedBlocks: Int,
    updatedAtEpochMs: Long = System.currentTimeMillis(),
  )

  @Query(
    """
    UPDATE translation_history
    SET outputFileName = :outputFileName,
        savedPath = :savedPath,
        contentUri = :contentUri,
        localBackupPath = :localBackupPath,
        translatedBlocks = :translatedBlocks,
        fileSizeBytes = :fileSizeBytes,
        status = :status,
        translationStatus = :translationStatus,
        errorMessage = :errorMessage,
        updatedAtEpochMs = :updatedAtEpochMs
    WHERE id = :id
    """
  )
  suspend fun updateOutcome(
    id: Long,
    outputFileName: String,
    savedPath: String,
    contentUri: String,
    localBackupPath: String,
    translatedBlocks: Int,
    fileSizeBytes: Long,
    status: String,
    translationStatus: HistoryTranslationStatus,
    errorMessage: String? = null,
    updatedAtEpochMs: Long = System.currentTimeMillis(),
  )

  @Query("DELETE FROM translation_history WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("DELETE FROM translation_history")
  suspend fun deleteAll()
}
