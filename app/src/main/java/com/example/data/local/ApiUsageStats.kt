package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.ApiProvider

enum class ApiRequestOutcome {
  SUCCESS,
  RATE_LIMITED,
  QUOTA_EXHAUSTED,
  RETRYABLE_ERROR,
  INVALID_KEY,
  FATAL_ERROR,
}

@Entity(tableName = "api_key_usage_stats")
data class ApiKeyUsageStat(
  @PrimaryKey val keyId: String,
  val keyLabel: String,
  val provider: String = ApiProvider.GEMINI.name,
  val successfulRequests: Int = 0,
  val rateLimitHits: Int = 0,
  val quotaExhaustedHits: Int = 0,
  val retryableErrors: Int = 0,
  val invalidKeyHits: Int = 0,
  val totalLatencyMs: Long = 0L,
  val latencySamples: Int = 0,
  val lastLatencyMs: Long = 0L,
  val blocksTranslated: Int = 0,
  val lastOutcome: String = ApiRequestOutcome.SUCCESS.name,
  val lastError: String? = null,
  val lastUsedAtEpochMs: Long = 0L,
) {
  val totalAttempts: Int
    get() =
      successfulRequests + rateLimitHits + quotaExhaustedHits + retryableErrors + invalidKeyHits

  val averageLatencyMs: Long
    get() = if (latencySamples > 0) totalLatencyMs / latencySamples else 0L

  val successRatePercent: Int
    get() {
      val total = totalAttempts
      return if (total <= 0) 0 else ((successfulRequests * 100f) / total).toInt().coerceIn(0, 100)
    }

  val failureAndLimitCount: Int
    get() = rateLimitHits + quotaExhaustedHits + retryableErrors + invalidKeyHits
}

@Entity(
  tableName = "api_usage_events",
  indices = [Index(value = ["provider", "recordedAtEpochMs"])],
)
data class ApiUsageEvent(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val keyId: String,
  val keyLabel: String,
  val provider: String,
  val model: String,
  val batchSize: Int,
  val dynamicBatchSizeAfter: Int,
  val latencyMs: Long,
  val outcome: String,
  val httpCode: Int? = null,
  val blocksTranslated: Int = 0,
  val recordedAtEpochMs: Long = System.currentTimeMillis(),
)
