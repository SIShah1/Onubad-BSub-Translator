package com.example.data.local

import kotlinx.coroutines.flow.Flow

class SubtitleHistoryRepository(
  private val dao: SubtitleHistoryDao,
) {
  val allHistory: Flow<List<SubtitleHistory>> = dao.getAllHistory()

  val totalCount: Flow<Int> = dao.observeCount()

  fun observeByStatus(status: HistoryTranslationStatus): Flow<List<SubtitleHistory>> =
    dao.getHistoryByStatus(status)

  fun searchByFileName(query: String): Flow<List<SubtitleHistory>> {
    val clean = query.trim()
    return if (clean.isEmpty()) dao.getAllHistory() else dao.searchByFileName(clean)
  }

  fun observeByOriginalFileName(originalFileName: String): Flow<List<SubtitleHistory>> =
    dao.getByOriginalFileName(originalFileName)

  fun observeCountByStatus(status: HistoryTranslationStatus): Flow<Int> =
    dao.observeCountByStatus(status)

  suspend fun getById(id: Long): SubtitleHistory? = dao.getById(id)

  suspend fun insert(record: SubtitleHistory): Long = dao.insert(record)

  suspend fun insertAll(records: List<SubtitleHistory>): List<Long> = dao.insertAll(records)

  suspend fun update(record: SubtitleHistory) = dao.update(record)

  suspend fun updateProcessingResult(
    id: Long,
    status: HistoryTranslationStatus,
    statusLabel: String = status.label,
    translatedBlocks: Int,
    outputFileName: String = "",
    savedPath: String = "",
    contentUri: String = "",
    localBackupPath: String = "",
    fileSizeBytes: Long = 0L,
    errorMessage: String? = null,
    updatedAtEpochMs: Long = System.currentTimeMillis(),
  ) {
    dao.updateProcessingResult(
      id = id,
      status = status,
      statusLabel = statusLabel,
      translatedBlocks = translatedBlocks,
      outputFileName = outputFileName,
      savedPath = savedPath,
      contentUri = contentUri,
      localBackupPath = localBackupPath,
      fileSizeBytes = fileSizeBytes,
      errorMessage = errorMessage,
      updatedAtEpochMs = updatedAtEpochMs,
    )
  }

  suspend fun delete(record: SubtitleHistory) = dao.delete(record)

  suspend fun deleteById(id: Long) = dao.deleteById(id)

  suspend fun clearAll() = dao.deleteAll()
}
