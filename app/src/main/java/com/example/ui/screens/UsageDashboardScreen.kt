package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.ApiKeyUsageStat
import com.example.data.local.ApiRequestOutcome
import com.example.data.local.ApiUsageEvent
import com.example.domain.model.ApiKeyEntry
import com.example.domain.model.ApiKeyStatus
import com.example.domain.model.ApiProvider
import com.example.domain.model.AppSettings
import com.example.domain.model.DefaultConfig
import com.example.domain.model.TranslationSessionState
import com.example.ui.components.SectionTitleRow
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioSectionCard
import com.example.ui.theme.JetBrainsMonoFamily
import java.util.Locale
import kotlin.math.roundToLong

private enum class UsageFilter(val label: String, val provider: ApiProvider?) {
  GEMINI("Gemini", ApiProvider.GEMINI),
  ALL("All", null),
  GROK("Grok", ApiProvider.GROK),
  DEEPSEEK("DeepSeek", ApiProvider.DEEPSEEK),
}

internal data class BottleneckInsight(
  val title: String,
  val detail: String,
  val isWarning: Boolean,
)

internal fun analyzeBottlenecks(
  stats: List<ApiKeyUsageStat>,
  events: List<ApiUsageEvent>,
  averageLatencyMs: Long,
  dynamicBatchingEnabled: Boolean,
  currentBatchSize: Int,
): BottleneckInsight {
  if (stats.isEmpty() && events.isEmpty()) {
    return BottleneckInsight(
      title = "No API telemetry recorded yet",
      detail = "Run a subtitle translation to inspect per-key throughput, HTTP 429 rate limits, and dynamic batch scaling.",
      isWarning = false,
    )
  }

  val topRateLimited = stats.maxByOrNull { it.rateLimitHits }
  if (topRateLimited != null && topRateLimited.rateLimitHits > 0) {
    val totalRateLimits = stats.sumOf { it.rateLimitHits }
    return BottleneckInsight(
      title = "Rate-limit bottleneck on ${topRateLimited.keyLabel}",
      detail =
        "${topRateLimited.keyLabel} absorbed ${topRateLimited.rateLimitHits} of $totalRateLimits HTTP 429 rate-limit hits. Add more keys or enable dynamic batching to reduce RPM pressure.",
      isWarning = true,
    )
  }

  val quotaOrInvalid = stats.firstOrNull { it.quotaExhaustedHits > 0 || it.invalidKeyHits > 0 }
  if (quotaOrInvalid != null) {
    return BottleneckInsight(
      title = "Key availability issue on ${quotaOrInvalid.keyLabel}",
      detail =
        "${quotaOrInvalid.keyLabel} recorded ${quotaOrInvalid.quotaExhaustedHits} quota exhaustion and ${quotaOrInvalid.invalidKeyHits} invalid-key failures.",
      isWarning = true,
    )
  }

  if (averageLatencyMs > DefaultConfig.DYNAMIC_SLOW_LATENCY_MS) {
    val sec = String.format(Locale.US, "%.1fs", averageLatencyMs / 1000f)
    return BottleneckInsight(
      title = "High API latency detected ($sec avg)",
      detail =
        if (dynamicBatchingEnabled) {
          "Dynamic batching scales batch size down toward smaller slices ($currentBatchSize blocks) to prevent timeouts."
        } else {
          "Enable Dynamic Latency-Based Batching in Settings to automatically shrink batches when latency spikes."
        },
      isWarning = true,
    )
  }

  if (averageLatencyMs in 1L..DefaultConfig.DYNAMIC_FAST_LATENCY_MS) {
    val sec = String.format(Locale.US, "%.2fs", averageLatencyMs / 1000f)
    return BottleneckInsight(
      title = "API responsive ($sec avg latency)",
      detail =
        if (dynamicBatchingEnabled) {
          "Dynamic batching scales batch size upward (currently $currentBatchSize blocks) to process more subtitles simultaneously."
        } else {
          "Latency is well within fast thresholds with zero rate-limit bottlenecks."
        },
      isWarning = false,
    )
  }

  val totalSuccess = stats.sumOf { it.successfulRequests }
  return BottleneckInsight(
    title = "Healthy throughput ($totalSuccess successful calls)",
    detail = "All tracked keys are responding cleanly with no active rate-limit bottlenecks.",
    isWarning = false,
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsageDashboardScreen(
  keyStats: List<ApiKeyUsageStat>,
  recentEvents: List<ApiUsageEvent>,
  configuredKeys: List<ApiKeyEntry>,
  settings: AppSettings,
  sessionState: TranslationSessionState,
  onClearUsageStats: (ApiProvider?) -> Unit,
  modifier: Modifier = Modifier,
) {
  var selectedFilter by rememberSaveable { mutableStateOf(UsageFilter.GEMINI) }
  var showClearConfirm by remember { mutableStateOf(false) }

  val filteredStats =
    remember(keyStats, selectedFilter) {
      val provider = selectedFilter.provider
      if (provider == null) keyStats else keyStats.filter { it.provider == provider.name }
    }
  val filteredEvents =
    remember(recentEvents, selectedFilter) {
      val provider = selectedFilter.provider
      if (provider == null) recentEvents else recentEvents.filter { it.provider == provider.name }
    }
  val configuredById = remember(configuredKeys) { configuredKeys.associateBy { it.id } }

  val totalSuccess = filteredStats.sumOf { it.successfulRequests }
  val totalRateLimits = filteredStats.sumOf { it.rateLimitHits }
  val totalQuotaHits = filteredStats.sumOf { it.quotaExhaustedHits }
  val totalBlocks = filteredStats.sumOf { it.blocksTranslated }
  val totalSamples = filteredStats.sumOf { it.latencySamples }
  val totalLatency = filteredStats.sumOf { it.totalLatencyMs }
  val avgLatencyMs =
    sessionState.recentAvgLatencyMs
      ?: if (totalSamples > 0) (totalLatency.toDouble() / totalSamples).roundToLong() else 0L
  val activeBatchSize =
    if (sessionState.isRunningOrCooldown) sessionState.currentBatchSize
    else settings.effectiveChunkSize

  val bottleneck =
    remember(filteredStats, filteredEvents, avgLatencyMs, settings.dynamicBatchSizingEnabled, activeBatchSize) {
      analyzeBottlenecks(
        stats = filteredStats,
        events = filteredEvents,
        averageLatencyMs = avgLatencyMs,
        dynamicBatchingEnabled = settings.dynamicBatchSizingEnabled,
        currentBatchSize = activeBatchSize,
      )
    }

  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // 1. Header & Provider Filter
    StudioSectionCard {
      SectionTitleRow(
        title = "API Usage & Bottlenecks",
        subtitle = "Per-key rate limits, success counts & latency scaling",
        icon = Icons.Rounded.Analytics,
        trailing = {
          if (filteredStats.isNotEmpty() || filteredEvents.isNotEmpty()) {
            IconButton(
              onClick = { showClearConfirm = true },
              modifier = Modifier.minimumInteractiveComponentSize().testTag("clear_usage_stats_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.DeleteSweep,
                contentDescription = "Clear usage statistics",
                modifier = Modifier.size(20.dp),
              )
            }
          }
        },
      )

      Spacer(modifier = Modifier.height(12.dp))

      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        UsageFilter.entries.forEach { filter ->
          FilterChip(
            selected = selectedFilter == filter,
            onClick = { selectedFilter = filter },
            label = { Text(filter.label) },
            modifier = Modifier.testTag("usage_filter_${filter.name.lowercase()}"),
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      // KPI Summary Tiles
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        MetricTile(
          label = "Succeeded",
          value = totalSuccess.toString(),
          modifier = Modifier.weight(1f).testTag("usage_total_success"),
        )
        MetricTile(
          label = "429 Limits",
          value = totalRateLimits.toString(),
          modifier = Modifier.weight(1f).testTag("usage_total_rate_limits"),
        )
        MetricTile(
          label = "Avg Latency",
          value = formatLatencyLabel(avgLatencyMs),
          modifier = Modifier.weight(1f).testTag("usage_avg_latency"),
        )
        MetricTile(
          label = if (settings.dynamicBatchSizingEnabled) "Dyn Batch" else "Batch Size",
          value = "$activeBatchSize",
          modifier = Modifier.weight(1f).testTag("usage_dynamic_batch_size"),
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        StatusPill(
          text = "Blocks: $totalBlocks",
          containerColor = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        StatusPill(
          text = if (settings.dynamicBatchSizingEnabled) "Dynamic Batching: ON" else "Dynamic Batching: OFF",
          containerColor =
            if (settings.dynamicBatchSizingEnabled) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
          contentColor =
            if (settings.dynamicBatchSizingEnabled) MaterialTheme.colorScheme.onTertiaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (totalQuotaHits > 0) {
          StatusPill(
            text = "Quota Exhausted: $totalQuotaHits",
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
          )
        }
      }
    }

    // 2. Bottleneck Diagnostics Card
    Surface(
      modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("usage_bottleneck_banner"),
      shape = MaterialTheme.shapes.medium,
      color =
        if (bottleneck.isWarning) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
      border =
        BorderStroke(
          1.dp,
          if (bottleneck.isWarning) MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
          else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f),
        ),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        verticalAlignment = Alignment.Top,
      ) {
        Icon(
          imageVector =
            if (bottleneck.isWarning) Icons.Rounded.WarningAmber else Icons.Rounded.Bolt,
          contentDescription = null,
          tint =
            if (bottleneck.isWarning) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.tertiary,
          modifier = Modifier.padding(top = 2.dp).size(20.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
          Text(
            text = bottleneck.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = bottleneck.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    if (filteredStats.isEmpty() && filteredEvents.isEmpty()) {
      StudioSectionCard(modifier = Modifier.testTag("usage_empty_state")) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 12.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Icon(
            imageVector = Icons.Rounded.Speed,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
          )
          Spacer(modifier = Modifier.height(10.dp))
          Text(
            text = "No ${selectedFilter.label} API usage data yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text =
              "Start translating a subtitle file in the Studio tab to visualize per-key request success counts, rate limits (429), and dynamic batch sizing response times.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }
    } else {
      // 3. Per-Key Success vs Rate Limit Chart
      if (filteredStats.isNotEmpty()) {
        StudioSectionCard(modifier = Modifier.testTag("usage_key_comparison_chart")) {
          SectionTitleRow(
            title = "Per-Key Success vs. Rate Limits",
            subtitle = "Compare successful requests against 429 rate-limit bottlenecks",
            icon = Icons.Rounded.CheckCircle,
          )
          Spacer(modifier = Modifier.height(10.dp))
          Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            LegendDot(
              label = "Success",
              color = MaterialTheme.colorScheme.tertiary,
            )
            LegendDot(
              label = "429 Rate Limit",
              color = MaterialTheme.colorScheme.secondary,
            )
            LegendDot(
              label = "Quota / Errors",
              color = MaterialTheme.colorScheme.error,
            )
          }
          Spacer(modifier = Modifier.height(12.dp))
          PerKeyStackedBarChart(stats = filteredStats)
        }
      }

      // 4. Recent API Response Latency Chart
      if (filteredEvents.isNotEmpty()) {
        val chronologicalEvents = remember(filteredEvents) { filteredEvents.take(24).reversed() }
        StudioSectionCard(modifier = Modifier.testTag("usage_latency_chart")) {
          SectionTitleRow(
            title = "Recent Response Latencies",
            subtitle = "Guides dynamic batch sizing (${chronologicalEvents.size} recent requests)",
            icon = Icons.Rounded.Timeline,
          )
          Spacer(modifier = Modifier.height(12.dp))
          LatencyBarChart(events = chronologicalEvents)
          Spacer(modifier = Modifier.height(8.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = "Fast <= 3.0s (Scale Up)",
              style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
              color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
              text = "Slow > 10.0s (Scale Down)",
              style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
      }

      // 5. Detailed Per-Key Usage Cards
      if (filteredStats.isNotEmpty()) {
        StudioSectionCard {
          SectionTitleRow(
            title = "Per-Key Bottleneck Breakdown (${filteredStats.size})",
            subtitle = "Sorted by rate-limit hits and request activity",
            icon = Icons.Rounded.Speed,
          )
          Spacer(modifier = Modifier.height(12.dp))
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            filteredStats.forEachIndexed { idx, stat ->
              val currentKey = configuredById[stat.keyId]
              KeyUsageDetailRow(
                stat = stat,
                keyStatus = currentKey?.status,
                modifier = Modifier.testTag("usage_key_stat_row_$idx"),
              )
            }
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(12.dp))
  }

  if (showClearConfirm) {
    AlertDialog(
      onDismissRequest = { showClearConfirm = false },
      title = { Text("Clear Usage Statistics?") },
      text = {
        Text(
          "Reset recorded request counts, rate-limit metrics, and latency history for ${selectedFilter.label}?"
        )
      },
      confirmButton = {
        Button(
          onClick = {
            onClearUsageStats(selectedFilter.provider)
            showClearConfirm = false
          },
          modifier = Modifier.testTag("confirm_clear_usage_stats_button"),
        ) {
          Text("Clear")
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearConfirm = false }) {
          Text("Cancel")
        }
      },
    )
  }
}

@Composable
private fun LegendDot(
  label: String,
  color: androidx.compose.ui.graphics.Color,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    Canvas(modifier = Modifier.size(9.dp)) {
      drawCircle(color = color)
    }
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun PerKeyStackedBarChart(stats: List<ApiKeyUsageStat>) {
  val successColor = MaterialTheme.colorScheme.tertiary
  val rateLimitColor = MaterialTheme.colorScheme.secondary
  val errorColor = MaterialTheme.colorScheme.error
  val trackColor = MaterialTheme.colorScheme.surfaceContainer

  val maxAttempts = remember(stats) { (stats.maxOfOrNull { it.totalAttempts } ?: 1).coerceAtLeast(1) }

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    stats.forEach { stat ->
      val otherErrors = stat.quotaExhaustedHits + stat.retryableErrors + stat.invalidKeyHits
      Column(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = stat.keyLabel,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "${stat.successfulRequests} ok • ${stat.rateLimitHits} 429 • $otherErrors err",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(12.dp)) {
          val w = size.width
          val h = size.height
          val r = CornerRadius(h / 2f, h / 2f)
          drawRoundRect(color = trackColor, size = size, cornerRadius = r)

          var cursorX = 0f
          val segments =
            listOf(
              stat.successfulRequests to successColor,
              stat.rateLimitHits to rateLimitColor,
              otherErrors to errorColor,
            )
          for ((count, segColor) in segments) {
            if (count <= 0) continue
            val segWidth = (count.toFloat() / maxAttempts.toFloat()) * w
            drawRoundRect(
              color = segColor,
              topLeft = Offset(cursorX, 0f),
              size = Size(segWidth.coerceAtLeast(4f).coerceAtMost(w - cursorX), h),
              cornerRadius = r,
            )
            cursorX += segWidth
          }
        }
      }
    }
  }
}

@Composable
private fun LatencyBarChart(events: List<ApiUsageEvent>) {
  val successColor = MaterialTheme.colorScheme.primary
  val fastColor = MaterialTheme.colorScheme.tertiary
  val rateLimitColor = MaterialTheme.colorScheme.secondary
  val errorColor = MaterialTheme.colorScheme.error
  val guideColor = MaterialTheme.colorScheme.outlineVariant

  val maxLatency =
    remember(events) {
      maxOf(DefaultConfig.DYNAMIC_SLOW_LATENCY_MS, events.maxOfOrNull { it.latencyMs } ?: 3_000L)
        .coerceAtLeast(1_000L)
    }

  Canvas(
    modifier = Modifier.fillMaxWidth().height(120.dp),
  ) {
    val w = size.width
    val h = size.height
    val count = events.size.coerceAtLeast(1)
    val slotWidth = w / count
    val barWidth = (slotWidth * 0.65f).coerceIn(4f, 24f)

    // Fast threshold guide line (3s)
    val fastY = h - ((DefaultConfig.DYNAMIC_FAST_LATENCY_MS.toFloat() / maxLatency) * h).coerceIn(0f, h)
    drawLine(
      color = fastColor.copy(alpha = 0.5f),
      start = Offset(0f, fastY),
      end = Offset(w, fastY),
      strokeWidth = 2f,
      pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
    )

    // Slow threshold guide line (10s)
    val slowY = h - ((DefaultConfig.DYNAMIC_SLOW_LATENCY_MS.toFloat() / maxLatency) * h).coerceIn(0f, h)
    drawLine(
      color = guideColor,
      start = Offset(0f, slowY),
      end = Offset(w, slowY),
      strokeWidth = 2f,
      pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
    )

    events.forEachIndexed { idx, event ->
      val ratio = (event.latencyMs.toFloat() / maxLatency.toFloat()).coerceIn(0.05f, 1f)
      val barHeight = (ratio * h).coerceAtLeast(6f)
      val x = idx * slotWidth + (slotWidth - barWidth) / 2f
      val y = h - barHeight
      val barColor =
        when (event.outcome) {
          ApiRequestOutcome.RATE_LIMITED.name -> rateLimitColor
          ApiRequestOutcome.QUOTA_EXHAUSTED.name,
          ApiRequestOutcome.INVALID_KEY.name,
          ApiRequestOutcome.FATAL_ERROR.name -> errorColor
          ApiRequestOutcome.SUCCESS.name ->
            if (event.latencyMs <= DefaultConfig.DYNAMIC_FAST_LATENCY_MS) fastColor else successColor
          else -> rateLimitColor
        }
      drawRoundRect(
        color = barColor,
        topLeft = Offset(x, y),
        size = Size(barWidth, barHeight),
        cornerRadius = CornerRadius(4f, 4f),
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyUsageDetailRow(
  stat: ApiKeyUsageStat,
  keyStatus: ApiKeyStatus?,
  modifier: Modifier = Modifier,
) {
  val providerName =
    runCatching { ApiProvider.valueOf(stat.provider).displayName }.getOrDefault(stat.provider)

  Surface(
    modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          modifier = Modifier.weight(1f),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = stat.keyLabel,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          StatusPill(
            text = providerName,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          )
          if (keyStatus != null && keyStatus != ApiKeyStatus.ACTIVE) {
            StatusPill(
              text = keyStatus.name,
              containerColor = MaterialTheme.colorScheme.errorContainer,
              contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
        }
        Text(
          text = "${stat.successRatePercent}% ok",
          style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
          color =
            if (stat.rateLimitHits > 0) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.tertiary,
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        StatusPill(
          text = "Success: ${stat.successfulRequests}",
          containerColor = MaterialTheme.colorScheme.tertiaryContainer,
          contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        StatusPill(
          text = "429 Limits: ${stat.rateLimitHits}",
          containerColor =
            if (stat.rateLimitHits > 0) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
          contentColor =
            if (stat.rateLimitHits > 0) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatusPill(
          text = "Avg: ${formatLatencyLabel(stat.averageLatencyMs)}",
          containerColor = MaterialTheme.colorScheme.surfaceContainer,
          contentColor = MaterialTheme.colorScheme.onSurface,
        )
        StatusPill(
          text = "Blocks: ${stat.blocksTranslated}",
          containerColor = MaterialTheme.colorScheme.surfaceContainer,
          contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Spacer(modifier = Modifier.height(8.dp))
      LinearProgressIndicator(
        progress = { (stat.successRatePercent / 100f).coerceIn(0f, 1f) },
        color =
          if (stat.rateLimitHits > 0 && stat.successRatePercent < 70) {
            MaterialTheme.colorScheme.secondary
          } else {
            MaterialTheme.colorScheme.tertiary
          },
        trackColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
      )

      if (!stat.lastError.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Last bottleneck: ${stat.lastError}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.error,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

private fun formatLatencyLabel(latencyMs: Long): String {
  if (latencyMs <= 0L) return "-"
  return if (latencyMs < 1_000L) "${latencyMs}ms"
  else String.format(Locale.US, "%.1fs", latencyMs / 1000f)
}
