package com.example.data.local

import com.example.domain.model.ApiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ApiUsageRepository(private val dao: ApiUsageDao) {

  private val writeMutex = Mutex()

  val allKeyStats: Flow<List<ApiKeyUsageStat>> = dao.observeAllKeyStats()

  val recentEvents: Flow<List<ApiUsageEvent>> = dao.observeRecentEvents(limit = 60)

  fun observeKeyStatsForProvider(provider: ApiProvider): Flow<List<ApiKeyUsageStat>> =
    dao.observeKeyStatsByProvider(provider.name)

  suspend fun recordRequestOutcome(
    keyId: String,
    keyLabel: String,
    provider: ApiProvider,
    model: String,
    batchSize: Int,
    dynamicBatchSizeAfter: Int = batchSize,
    latencyMs: Long,
    outcome: ApiRequestOutcome,
    httpCode: Int? = null,
    blocksTranslated: Int = 0,
    errorReason: String? = null,
    nowEpochMs: Long = System.currentTimeMillis(),
  ) {
    val safeLatency = latencyMs.coerceAtLeast(0L)
    writeMutex.withLock {
      val existing = dao.getKeyStatById(keyId)
      val updatedStat =
        ApiKeyUsageStat(
          keyId = keyId,
          keyLabel = keyLabel.ifBlank { existing?.keyLabel ?: "Key" },
          provider = provider.name,
          successfulRequests =
            (existing?.successfulRequests ?: 0) +
              if (outcome == ApiRequestOutcome.SUCCESS) 1 else 0,
          rateLimitHits =
            (existing?.rateLimitHits ?: 0) +
              if (outcome == ApiRequestOutcome.RATE_LIMITED) 1 else 0,
          quotaExhaustedHits =
            (existing?.quotaExhaustedHits ?: 0) +
              if (outcome == ApiRequestOutcome.QUOTA_EXHAUSTED) 1 else 0,
          retryableErrors =
            (existing?.retryableErrors ?: 0) +
              if (
                outcome == ApiRequestOutcome.RETRYABLE_ERROR ||
                  outcome == ApiRequestOutcome.FATAL_ERROR
              ) {
                1
              } else {
                0
              },
          invalidKeyHits =
            (existing?.invalidKeyHits ?: 0) +
              if (outcome == ApiRequestOutcome.INVALID_KEY) 1 else 0,
          totalLatencyMs = (existing?.totalLatencyMs ?: 0L) + safeLatency,
          latencySamples = (existing?.latencySamples ?: 0) + 1,
          lastLatencyMs = safeLatency,
          blocksTranslated =
            (existing?.blocksTranslated ?: 0) + blocksTranslated.coerceAtLeast(0),
          lastOutcome = outcome.name,
          lastError =
            if (outcome == ApiRequestOutcome.SUCCESS) null
            else errorReason?.take(180) ?: existing?.lastError,
          lastUsedAtEpochMs = nowEpochMs,
        )
      dao.upsertKeyStat(updatedStat)

      dao.insertEvent(
        ApiUsageEvent(
          keyId = keyId,
          keyLabel = updatedStat.keyLabel,
          provider = provider.name,
          model = model,
          batchSize = batchSize.coerceAtLeast(0),
          dynamicBatchSizeAfter = dynamicBatchSizeAfter.coerceAtLeast(0),
          latencyMs = safeLatency,
          outcome = outcome.name,
          httpCode = httpCode,
          blocksTranslated = blocksTranslated.coerceAtLeast(0),
          recordedAtEpochMs = nowEpochMs,
        )
      )
      dao.trimOldEvents(keepLimit = 120)
    }
  }

  suspend fun clearStats(provider: ApiProvider? = null) {
    writeMutex.withLock {
      if (provider == null) {
        dao.deleteAllKeyStats()
        dao.deleteAllEvents()
      } else {
        dao.deleteKeyStatsByProvider(provider.name)
        dao.deleteEventsByProvider(provider.name)
      }
    }
  }
}
