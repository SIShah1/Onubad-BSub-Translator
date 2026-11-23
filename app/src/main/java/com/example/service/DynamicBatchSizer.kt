package com.example.service

import com.example.domain.model.DefaultConfig
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class BatchAdjustmentReason {
  INITIAL,
  SCALED_UP,
  STEADY,
  SCALED_DOWN_LATENCY,
  SCALED_DOWN_RATE_LIMIT,
  SCALED_DOWN_TRUNCATION,
}

data class BatchAdjustmentResult(
  val previousSize: Int,
  val newSize: Int,
  val averageLatencyMs: Long?,
  val reason: BatchAdjustmentReason,
) {
  val sizeChanged: Boolean
    get() = previousSize != newSize
}

/**
 * Dynamically adapts subtitle batch sizes using a sliding window of recent API response
 * latencies. When the API is fast/responsive (avg <= [fastThresholdMs]), batch size scales up
 * toward [maxBatchSize] to translate more cues simultaneously. When responses slow down
 * (avg > [slowThresholdMs]), rate limits trigger, or outputs truncate, batch size scales down
 * toward [minBatchSize] to prevent timeouts and retries.
 */
class DynamicBatchSizer(
  baseBatchSize: Int,
  val enabled: Boolean = DefaultConfig.DYNAMIC_BATCH_SIZING,
  val minBatchSize: Int =
    (baseBatchSize / 2)
      .coerceAtLeast(minOf(baseBatchSize, DefaultConfig.DYNAMIC_MIN_CHUNK_FLOOR))
      .coerceAtLeast(1),
  val maxBatchSize: Int =
    (baseBatchSize * 2)
      .coerceAtLeast(baseBatchSize)
      .coerceAtMost(maxOf(baseBatchSize, DefaultConfig.DYNAMIC_MAX_CHUNK_CEILING)),
  private val windowSize: Int = DefaultConfig.DYNAMIC_BATCH_WINDOW,
  private val fastThresholdMs: Long = DefaultConfig.DYNAMIC_FAST_LATENCY_MS,
  private val slowThresholdMs: Long = DefaultConfig.DYNAMIC_SLOW_LATENCY_MS,
) {
  private val latencyWindow = ArrayDeque<Long>(windowSize.coerceAtLeast(1))

  val baseSize: Int = baseBatchSize.coerceIn(1, maxBatchSize.coerceAtLeast(1))

  var currentBatchSize: Int =
    if (enabled) baseSize.coerceIn(minBatchSize, maxBatchSize) else baseSize
    private set

  fun averageLatencyMs(): Long? {
    if (latencyWindow.isEmpty()) return null
    return latencyWindow.average().roundToLong()
  }

  fun recentLatenciesSnapshot(): List<Long> = latencyWindow.toList()

  fun recordSuccessLatency(
    latencyMs: Long,
    rateLimitHitsInBatch: Int = 0,
  ): BatchAdjustmentResult {
    val safeLatency = latencyMs.coerceAtLeast(1L)
    val safeWindow = windowSize.coerceAtLeast(1)
    if (latencyWindow.size >= safeWindow) {
      latencyWindow.removeFirst()
    }
    latencyWindow.addLast(safeLatency)

    val avg = averageLatencyMs()
    val previous = currentBatchSize
    if (!enabled) {
      return BatchAdjustmentResult(
        previousSize = previous,
        newSize = previous,
        averageLatencyMs = avg,
        reason = BatchAdjustmentReason.STEADY,
      )
    }

    if (rateLimitHitsInBatch > 0) {
      val stepDown = maxOf(10, (previous * 0.20f).roundToInt())
      val next = (previous - stepDown).coerceIn(minBatchSize, maxBatchSize)
      currentBatchSize = next
      return BatchAdjustmentResult(
        previousSize = previous,
        newSize = next,
        averageLatencyMs = avg,
        reason =
          if (next < previous) BatchAdjustmentReason.SCALED_DOWN_RATE_LIMIT
          else BatchAdjustmentReason.STEADY,
      )
    }

    val resolvedAvg = avg ?: safeLatency
    return when {
      resolvedAvg <= fastThresholdMs -> {
        val stepUp = maxOf(10, (previous * 0.25f).roundToInt())
        val next = (previous + stepUp).coerceIn(minBatchSize, maxBatchSize)
        currentBatchSize = next
        BatchAdjustmentResult(
          previousSize = previous,
          newSize = next,
          averageLatencyMs = avg,
          reason =
            if (next > previous) BatchAdjustmentReason.SCALED_UP
            else BatchAdjustmentReason.STEADY,
        )
      }
      resolvedAvg > slowThresholdMs -> {
        val stepDown = maxOf(10, (previous * 0.25f).roundToInt())
        val next = (previous - stepDown).coerceIn(minBatchSize, maxBatchSize)
        currentBatchSize = next
        BatchAdjustmentResult(
          previousSize = previous,
          newSize = next,
          averageLatencyMs = avg,
          reason =
            if (next < previous) BatchAdjustmentReason.SCALED_DOWN_LATENCY
            else BatchAdjustmentReason.STEADY,
        )
      }
      else -> {
        BatchAdjustmentResult(
          previousSize = previous,
          newSize = previous,
          averageLatencyMs = avg,
          reason = BatchAdjustmentReason.STEADY,
        )
      }
    }
  }

  fun recordTruncation(): BatchAdjustmentResult {
    val previous = currentBatchSize
    val avg = averageLatencyMs()
    if (!enabled) {
      return BatchAdjustmentResult(previous, previous, avg, BatchAdjustmentReason.STEADY)
    }
    val stepDown = maxOf(10, (previous * 0.25f).roundToInt())
    val next = (previous - stepDown).coerceIn(minBatchSize, maxBatchSize)
    currentBatchSize = next
    return BatchAdjustmentResult(
      previousSize = previous,
      newSize = next,
      averageLatencyMs = avg,
      reason =
        if (next < previous) BatchAdjustmentReason.SCALED_DOWN_TRUNCATION
        else BatchAdjustmentReason.STEADY,
    )
  }
}
