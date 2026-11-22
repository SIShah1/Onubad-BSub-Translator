package com.example.data.local

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow

class HistoryRepository(
  private val dao: TranslationHistoryDao,
  private val subtitleHistoryDao: SubtitleHistoryDao? = null,
) {
  private val mirroredSubtitleIds = ConcurrentHashMap<Long, Long>()

  val historyItems: Flow<List<TranslationHistoryEntity>> = dao.getAllHistory()

  val totalCount: Flow<Int> = dao.observeTotalCount()

  fun observeByStatus(status: HistoryTranslationStatus): Flow<List<TranslationHistoryEntity>> =
    dao.getHistoryByStatus(status)

  fun searchHistory(query: String): Flow<List<TranslationHistoryEntity>> {
    val clean = query.trim()
    return if (clean.isEmpty()) dao.getAllHistory() else dao.searchHistory(clean)
  }

  fun observeCountByStatus(status: HistoryTranslationStatus): Flow<Int> =
    dao.observeCountByStatus(status)

  suspend fun getById(id: Long): TranslationHistoryEntity? = dao.getById(id)

  suspend fun saveRecord(record: TranslationHistoryEntity): Long {
    val insertedId = dao.insertHistory(record)
    subtitleHistoryDao?.let { subDao ->
      val existingSubId = mirroredSubtitleIds[insertedId] ?: 0L
      val subId = subDao.insert(record.toSubtitleHistory(idOverride = existingSubId))
      mirroredSubtitleIds[insertedId] = subId
    }
    return insertedId
  }

  suspend fun updateRecord(record: TranslationHistoryEntity) {
    dao.updateHistory(record)
    subtitleHistoryDao?.let { subDao ->
      val subId = mirroredSubtitleIds[record.id]
      if (subId != null) {
        subDao.update(record.toSubtitleHistory(idOverride = subId))
      }
    }
  }

  suspend fun createProcessingRecord(
    originalFileName: String,
    expectedOutputFileName: String,
    mode: String,
    titleMetadata: String,
    totalBlocks: Int,
    totalLines: Int,
    modelUsed: String,
    nowEpochMs: Long = System.currentTimeMillis(),
  ): Long {
    val entity =
      TranslationHistoryEntity(
        originalFileName = originalFileName,
        outputFileName = expectedOutputFileName,
        mode = mode,
        titleMetadata = titleMetadata,
        totalBlocks = totalBlocks,
        translatedBlocks = 0,
        totalLines = totalLines,
        fileSizeBytes = 0L,
        modelUsed = modelUsed,
        status = HistoryTranslationStatus.PROCESSING.label,
        translationStatus = HistoryTranslationStatus.PROCESSING,
        createdAtEpochMs = nowEpochMs,
        updatedAtEpochMs = nowEpochMs,
      )
    val insertedId = dao.insertHistory(entity)
    subtitleHistoryDao?.let { subDao ->
      val subId = subDao.insert(entity.toSubtitleHistory(idOverride = 0L))
      mirroredSubtitleIds[insertedId] = subId
    }
    return insertedId
  }

  suspend fun updateBatchProgress(
    historyId: Long,
    translatedBlocks: Int,
    updatedAtEpochMs: Long = System.currentTimeMillis(),
  ) {
    dao.updateProgress(
      id = historyId,
      translatedBlocks = translatedBlocks,
      updatedAtEpochMs = updatedAtEpochMs,
    )
    val subId = mirroredSubtitleIds[historyId] ?: return
    subtitleHistoryDao?.getById(subId)?.let { existing ->
      subtitleHistoryDao.update(
        existing.copy(
          translatedBlocks = translatedBlocks,
          updatedAtEpochMs = updatedAtEpochMs,
        )
      )
    }
  }

  suspend fun markCompleted(
    historyId: Long,
    outputFileName: String,
    savedPath: String,
    contentUri: String,
    localBackupPath: String,
    translatedBlocks: Int,
    fileSizeBytes: Long,
    updatedAtEpochMs: Long = System.currentTimeMillis(),
  ) {
    dao.updateOutcome(
      id = historyId,
      outputFileName = outputFileName,
      savedPath = savedPath,
      contentUri = contentUri,
      localBackupPath = localBackupPath,
      translatedBlocks = translatedBlocks,
      fileSizeBytes = fileSizeBytes,
      status = HistoryTranslationStatus.COMPLETED.label,
      translationStatus = HistoryTranslationStatus.COMPLETED,
      errorMessage = null,
      updatedAtEpochMs = updatedAtEpochMs,
    )
    val subId = mirroredSubtitleIds[historyId] ?: return
    subtitleHistoryDao?.updateProcessingResult(
      id = subId,
      status = HistoryTranslationStatus.COMPLETED,
      statusLabel = HistoryTranslationStatus.COMPLETED.label,
      translatedBlocks = translatedBlocks,
      outputFileName = outputFileName,
      savedPath = savedPath,
      contentUri = contentUri,
      localBackupPath = localBackupPath,
      fileSizeBytes = fileSizeBytes,
      errorMessage = null,
      updatedAtEpochMs = updatedAtEpochMs,
    )
  }

  suspend fun markPartial(
    historyId: Long,
    outputFileName: String,
    savedPath: String,
    contentUri: String,
    localBackupPath: String,
    translatedBlocks: Int,
    totalBlocks: Int,
    fileSizeBytes: Long,
    reason: String? = null,
    updatedAtEpochMs: Long = System.currentTimeMillis(),
  ) {
    val label = "Partial ($translatedBlocks/$totalBlocks)"
    dao.updateOutcome(
      id = historyId,
      outputFileName = outputFileName,
      savedPath = savedPath,
      contentUri = contentUri,
      localBackupPath = localBackupPath,
      translatedBlocks = translatedBlocks,
      fileSizeBytes = fileSizeBytes,
      status = label,
      translationStatus = HistoryTranslationStatus.PARTIAL,
      errorMessage = reason,
      updatedAtEpochMs = updatedAtEpochMs,
    )
    val subId = mirroredSubtitleIds[historyId] ?: return
    subtitleHistoryDao?.updateProcessingResult(
      id = subId,
      status = HistoryTranslationStatus.PARTIAL,
      statusLabel = label,
      translatedBlocks = translatedBlocks,
      outputFileName = outputFileName,
      savedPath = savedPath,
      contentUri = contentUri,
      localBackupPath = localBackupPath,
      fileSizeBytes = fileSizeBytes,
      errorMessage = reason,
      updatedAtEpochMs = updatedAtEpochMs,
    )
  }

  suspend fun markFailed(
    historyId: Long,
    translatedBlocks: Int = 0,
    errorMessage: String,
    status: HistoryTranslationStatus = HistoryTranslationStatus.FAILED,
    updatedAtEpochMs: Long = System.currentTimeMillis(),
  ) {
    val existing = dao.getById(historyId)
    val outputFileName = existing?.outputFileName.orEmpty()
    val savedPath = existing?.savedPath.orEmpty()
    val contentUri = existing?.contentUri.orEmpty()
    val localBackupPath = existing?.localBackupPath.orEmpty()
    val fileSizeBytes = existing?.fileSizeBytes ?: 0L

    dao.updateOutcome(
      id = historyId,
      outputFileName = outputFileName,
      savedPath = savedPath,
      contentUri = contentUri,
      localBackupPath = localBackupPath,
      translatedBlocks = translatedBlocks,
      fileSizeBytes = fileSizeBytes,
      status = status.label,
      translationStatus = status,
      errorMessage = errorMessage,
      updatedAtEpochMs = updatedAtEpochMs,
    )
    val subId = mirroredSubtitleIds[historyId] ?: return
    subtitleHistoryDao?.updateProcessingResult(
      id = subId,
      status = status,
      statusLabel = status.label,
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

  suspend fun deleteRecord(id: Long) {
    dao.deleteById(id)
    mirroredSubtitleIds.remove(id)?.let { subId ->
      subtitleHistoryDao?.deleteById(subId)
    }
  }

  suspend fun clearAll() {
    mirroredSubtitleIds.clear()
    dao.deleteAll()
    subtitleHistoryDao?.deleteAll()
  }

  private fun TranslationHistoryEntity.toSubtitleHistory(
    idOverride: Long = 0L,
  ): SubtitleHistory =
    SubtitleHistory(
      id = idOverride,
      originalFileName = originalFileName,
      outputFileName = outputFileName,
      savedPath = savedPath,
      contentUri = contentUri,
      localBackupPath = localBackupPath,
      mode = mode,
      titleMetadata = titleMetadata,
      totalBlocks = totalBlocks,
      translatedBlocks = translatedBlocks,
      totalLines = totalLines,
      fileSizeBytes = fileSizeBytes,
      modelUsed = modelUsed,
      status = translationStatus,
      statusLabel = status,
      processedAtEpochMs = createdAtEpochMs,
      updatedAtEpochMs = updatedAtEpochMs,
      errorMessage = errorMessage,
    )
}
