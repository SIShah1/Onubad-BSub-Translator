package com.example.data.local

import com.example.domain.model.SrtBlock
import com.example.srt.SrtItem
import kotlinx.coroutines.flow.Flow

class SubtitleTranslationRepository(private val dao: SubtitleTranslationDao) {

  val allTranslations: Flow<List<SubtitleTranslation>> = dao.getAllTranslations()

  fun observeByStatus(status: SubtitleTranslationStatus): Flow<List<SubtitleTranslation>> =
    dao.getAllByStatus(status)

  fun observeJobTranslations(jobId: String): Flow<List<SubtitleTranslation>> =
    dao.getTranslationsForJob(jobId)

  fun observePendingForJob(jobId: String): Flow<List<SubtitleTranslation>> =
    dao.getTranslationsByStatus(jobId, SubtitleTranslationStatus.PENDING)

  fun observeTranslatedForJob(jobId: String): Flow<List<SubtitleTranslation>> =
    dao.getTranslationsByStatus(jobId, SubtitleTranslationStatus.TRANSLATED)

  fun observeCountByStatus(jobId: String, status: SubtitleTranslationStatus): Flow<Int> =
    dao.observeCountByStatus(jobId, status)

  fun observeOverallProgress(): Flow<RoomTranslationProgress> = dao.observeOverallProgress()

  fun observeFileProgressList(): Flow<List<FileTranslationProgress>> = dao.observeFileProgressList()

  suspend fun getById(id: Long): SubtitleTranslation? = dao.getById(id)

  suspend fun getLatestForFile(fileName: String): List<SubtitleTranslation> {
    if (fileName.isBlank()) return emptyList()
    val latestJobId = dao.getLatestJobIdForFileName(fileName)
    return if (latestJobId != null) {
      dao.getByJobIdOnce(latestJobId).filter { it.fileName == fileName }
    } else {
      dao.getByFileNameOnce(fileName)
    }
  }

  suspend fun insertTranslation(
    jobId: String,
    fileName: String,
    blockIndex: Int,
    timestamp: String,
    originalEnglishText: String,
    translatedBengaliText: String? = null,
    status: SubtitleTranslationStatus =
      if (translatedBengaliText.isNullOrBlank()) {
        SubtitleTranslationStatus.PENDING
      } else {
        SubtitleTranslationStatus.TRANSLATED
      },
  ): Long {
    return dao.insert(
      SubtitleTranslation(
        jobId = jobId,
        fileName = fileName,
        blockIndex = blockIndex,
        timestamp = timestamp,
        originalEnglishText = originalEnglishText,
        translatedBengaliText = translatedBengaliText,
        status = status,
      )
    )
  }

  suspend fun insert(item: SubtitleTranslation): Long = dao.insert(item)

  suspend fun insertAll(items: List<SubtitleTranslation>): List<Long> = dao.insertAll(items)

  suspend fun seedPendingItems(
    jobId: String,
    fileName: String,
    items: List<SrtItem>,
  ): List<Long> {
    return seedPendingBlocks(
      jobId = jobId,
      fileName = fileName,
      blocks = items.map { it.toSrtBlock() },
    )
  }

  suspend fun seedPendingBlocks(
    jobId: String,
    fileName: String,
    blocks: List<SrtBlock>,
  ): List<Long> {
    if (blocks.isEmpty()) return emptyList()
    val entities =
      blocks.map { block ->
        SubtitleTranslation(
          jobId = jobId,
          fileName = fileName,
          blockIndex = block.index,
          timestamp = block.timestamp,
          originalEnglishText = block.lines.joinToString("\n"),
          translatedBengaliText = null,
          status = SubtitleTranslationStatus.PENDING,
        )
      }
    return dao.insertAll(entities)
  }

  suspend fun seedPendingItemsIfAbsent(
    jobId: String,
    fileName: String,
    items: List<SrtItem>,
  ): List<Long> {
    return seedPendingBlocksIfAbsent(
      jobId = jobId,
      fileName = fileName,
      blocks = items.map { it.toSrtBlock() },
    )
  }

  suspend fun seedPendingBlocksIfAbsent(
    jobId: String,
    fileName: String,
    blocks: List<SrtBlock>,
  ): List<Long> {
    if (blocks.isEmpty()) return emptyList()
    if (dao.getByJobIdOnce(jobId).isNotEmpty()) return emptyList()
    return seedPendingBlocks(jobId = jobId, fileName = fileName, blocks = blocks)
  }

  suspend fun getTranslatedBlocksMapForJob(jobId: String): Map<Int, SrtBlock> {
    val rows = dao.getByJobIdOnce(jobId)
    if (rows.isEmpty()) return emptyMap()
    val result = LinkedHashMap<Int, SrtBlock>(rows.size)
    for (row in rows) {
      val text = row.translatedBengaliText
      if (row.status == SubtitleTranslationStatus.TRANSLATED && !text.isNullOrBlank()) {
        result[row.blockIndex] =
          SrtBlock(
            index = row.blockIndex,
            timestamp = row.timestamp,
            lines = text.split("\n"),
          )
      }
    }
    return result
  }

  suspend fun hasAnyRowsForJob(jobId: String): Boolean = dao.getByJobIdOnce(jobId).isNotEmpty()

  suspend fun markAsTranslated(id: Long, translatedBengaliText: String) {
    dao.updateTranslationById(
      id = id,
      translatedBengaliText = translatedBengaliText,
      status = SubtitleTranslationStatus.TRANSLATED,
    )
  }

  suspend fun markBatchTranslated(jobId: String, translatedBlocks: List<SrtBlock>) {
    val now = System.currentTimeMillis()
    for (block in translatedBlocks) {
      dao.updateTranslationResult(
        jobId = jobId,
        blockIndex = block.index,
        translatedBengaliText = block.lines.joinToString("\n"),
        status = SubtitleTranslationStatus.TRANSLATED,
        updatedAtEpochMs = now,
      )
    }
  }

  suspend fun update(item: SubtitleTranslation) = dao.update(item)

  suspend fun deleteById(id: Long) = dao.deleteById(id)

  suspend fun deleteJob(jobId: String) = dao.deleteByJobId(jobId)

  suspend fun clearAll() = dao.deleteAll()
}
