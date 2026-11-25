package com.example.service

import android.net.Uri
import com.example.data.network.ApiKeyRotationManager
import com.example.data.network.BatchValidationOutcome
import com.example.data.network.GeminiApiClient
import com.example.data.network.GeminiGenerateContentRequest
import com.example.data.network.KeyRotationSession
import com.example.data.network.RotationExecutionResult
import com.example.data.preferences.SettingsRepository
import com.example.data.storage.SubtitleFileStorage
import com.example.domain.model.AppSettings
import com.example.domain.model.DefaultConfig
import com.example.domain.model.InputMode
import com.example.domain.model.LogLevel
import com.example.domain.model.SelectedSubtitleFile
import com.example.domain.model.SrtBlock
import com.example.srt.BatchReconciliationResult
import com.example.srt.SrtEngine
import com.example.srt.SrtParser
import com.example.srt.SrtValidator
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

data class SubtitleSegment(
  val segmentIndex: Int,
  val totalSegments: Int,
  val blocks: List<SrtBlock>,
  val formattedSrtPayload: String,
  val startBlockIndex: Int = 0,
  val endBlockIndexExclusive: Int = blocks.size,
  val surroundingContext: String = "",
)

data class SegmentedSubtitlePlan(
  val fileName: String,
  val parsedBlocks: List<SrtBlock>,
  val segments: List<SubtitleSegment>,
) {
  val totalBlocks: Int
    get() = parsedBlocks.size

  val isEmpty: Boolean
    get() = parsedBlocks.isEmpty()
}

data class PreparedSegmentRequest(
  val fileName: String,
  val segment: SubtitleSegment,
  val model: String,
  val requestBody: GeminiGenerateContentRequest,
)

sealed interface SubtitleTranslationServiceResult {
  data class Success(
    val fileName: String,
    val originalBlocks: List<SrtBlock>,
    val translatedBlocks: List<SrtBlock>,
    val reconstructedBengaliSrt: String,
    val segmentsProcessed: Int,
  ) : SubtitleTranslationServiceResult

  data class Failure(
    val reason: String,
    val partialTranslatedBlocks: List<SrtBlock> = emptyList(),
  ) : SubtitleTranslationServiceResult
}

/**
 * Service layer coordinating SRT parsing, chunk segmentation, Gemini API request preparation,
 * multi-key rotation, and final Bengali SRT reconstruction.
 */
class SubtitleTranslationService(
  private val settingsRepository: SettingsRepository,
  private val fileStorage: SubtitleFileStorage,
  private val geminiApiClient: GeminiApiClient,
  private val keyRotationManager: ApiKeyRotationManager,
) {

  companion object {
    private const val MIN_ADAPTIVE_SPLIT_BLOCKS = 4
    private const val MAX_ADAPTIVE_SPLIT_DEPTH = 4
    private const val PARTIAL_RECOVERY_MIN_BATCH_SIZE = 13
    private const val OVERLOAD_SPLIT_MIN_BLOCKS = 25
  }

  fun parseAndSegment(
    rawSrtContent: String,
    chunkSize: Int,
    fileName: String = "subtitle.srt",
    contextLines: Int = DefaultConfig.CONTEXT_LINES,
  ): SegmentedSubtitlePlan {
    val blocks = SrtParser.parseBlocks(rawSrtContent)
    return segmentBlocks(
      blocks = blocks,
      chunkSize = chunkSize,
      fileName = fileName,
      contextLines = contextLines,
    )
  }

  fun parseAndSegment(
    file: SelectedSubtitleFile,
    chunkSize: Int,
    contextLines: Int = DefaultConfig.CONTEXT_LINES,
  ): SegmentedSubtitlePlan {
    return segmentBlocks(
      blocks = file.blocks,
      chunkSize = chunkSize,
      fileName = file.fileName,
      contextLines = contextLines,
    )
  }

  fun parseAndSegment(
    file: File,
    chunkSize: Int,
    contextLines: Int = DefaultConfig.CONTEXT_LINES,
  ): SegmentedSubtitlePlan {
    val rawContent = if (file.exists() && file.isFile) SrtParser.readRaw(file) else ""
    return parseAndSegment(
      rawSrtContent = rawContent,
      chunkSize = chunkSize,
      fileName = file.name.ifBlank { "subtitle.srt" },
      contextLines = contextLines,
    )
  }

  suspend fun parseAndSegmentUri(
    uri: Uri,
    chunkSize: Int,
    contextLines: Int = DefaultConfig.CONTEXT_LINES,
  ): SegmentedSubtitlePlan? {
    val analyzed = fileStorage.readAndAnalyzeUri(uri) ?: return null
    return parseAndSegment(analyzed, chunkSize, contextLines)
  }

  fun segmentBlocks(
    blocks: List<SrtBlock>,
    chunkSize: Int,
    fileName: String = "subtitle.srt",
    contextLines: Int = DefaultConfig.CONTEXT_LINES,
  ): SegmentedSubtitlePlan {
    if (blocks.isEmpty()) {
      return SegmentedSubtitlePlan(
        fileName = fileName,
        parsedBlocks = emptyList(),
        segments = emptyList(),
      )
    }

    val safeChunkSize = chunkSize.coerceAtLeast(1)
    val totalSegments = (blocks.size + safeChunkSize - 1) / safeChunkSize
    val segments = ArrayList<SubtitleSegment>(totalSegments)
    var start = 0
    var segIdx = 1
    while (start < blocks.size) {
      val end = (start + safeChunkSize).coerceAtMost(blocks.size)
      val segmentBlocks = blocks.subList(start, end)
      val surroundingContext =
        SrtEngine.buildSurroundingContext(
          allBlocks = blocks,
          startIdx = start,
          endIdxExclusive = end,
          maxContextLines = contextLines,
        )
      segments.add(
        SubtitleSegment(
          segmentIndex = segIdx,
          totalSegments = totalSegments,
          blocks = segmentBlocks,
          formattedSrtPayload = SrtParser.formatBlocks(segmentBlocks),
          startBlockIndex = start,
          endBlockIndexExclusive = end,
          surroundingContext = surroundingContext,
        )
      )
      start = end
      segIdx++
    }

    return SegmentedSubtitlePlan(
      fileName = fileName,
      parsedBlocks = blocks,
      segments = segments,
    )
  }

  fun buildDynamicSlice(
    allBlocks: List<SrtBlock>,
    startIdx: Int,
    batchSize: Int,
    segmentIndex: Int,
    estimatedTotalSegments: Int,
    contextLines: Int = DefaultConfig.CONTEXT_LINES,
    translatedByIndex: Map<Int, SrtBlock> = emptyMap(),
    maxPayloadChars: Int = Int.MAX_VALUE,
    minFloorBlocks: Int = DefaultConfig.DYNAMIC_MIN_CHUNK_FLOOR,
  ): SubtitleSegment {
    val safeStart = startIdx.coerceIn(0, allBlocks.size)
    val maxEnd = (safeStart + batchSize.coerceAtLeast(1)).coerceAtMost(allBlocks.size)
    val safeEnd =
      if (maxPayloadChars < Int.MAX_VALUE && maxEnd - safeStart > minFloorBlocks) {
        var charBudget = 0
        var idx = safeStart
        val guaranteedMinEnd = (safeStart + minFloorBlocks.coerceAtLeast(1)).coerceAtMost(maxEnd)
        while (idx < maxEnd) {
          val blockChars =
            allBlocks[idx].lines.sumOf { it.length } + allBlocks[idx].timestamp.length + 12
          if (idx >= guaranteedMinEnd && charBudget + blockChars > maxPayloadChars) {
            break
          }
          charBudget += blockChars
          idx++
        }
        idx.coerceAtLeast((safeStart + 1).coerceAtMost(allBlocks.size))
      } else {
        maxEnd
      }
    val sliceBlocks = allBlocks.subList(safeStart, safeEnd)
    val surroundingContext =
      SrtEngine.buildSurroundingContext(
        allBlocks = allBlocks,
        startIdx = safeStart,
        endIdxExclusive = safeEnd,
        maxContextLines = contextLines,
        translatedByIndex = translatedByIndex,
      )
    return SubtitleSegment(
      segmentIndex = segmentIndex,
      totalSegments = estimatedTotalSegments.coerceAtLeast(segmentIndex),
      blocks = sliceBlocks,
      formattedSrtPayload = SrtParser.formatBlocks(sliceBlocks),
      startBlockIndex = safeStart,
      endBlockIndexExclusive = safeEnd,
      surroundingContext = surroundingContext,
    )
  }

  fun prepareTranslationRequest(
    segment: SubtitleSegment,
    settings: AppSettings,
    inputMode: InputMode,
    title: String,
    secondaryMeta: String,
    fileName: String,
  ): PreparedSegmentRequest {
    val requestBody =
      geminiApiClient.buildRequestBody(
        systemPrompt = settings.effectivePrompt,
        fewShotExamples = settings.fewShotExamples,
        inputMode = inputMode,
        title = title,
        secondaryMeta = secondaryMeta,
        batchSrtPayload = segment.formattedSrtPayload,
        batchIndex = segment.segmentIndex,
        totalBatches = segment.totalSegments,
        fileName = fileName,
        temperature = settings.effectiveTemperature,
        thinkingBudget = settings.effectiveThinkingBudget,
        surroundingContext = segment.surroundingContext,
        model = settings.effectiveModel,
      )
    return PreparedSegmentRequest(
      fileName = fileName,
      segment = segment,
      model = settings.effectiveModel,
      requestBody = requestBody,
    )
  }

  suspend fun translateSegmentWithRotation(
    session: KeyRotationSession,
    segment: SubtitleSegment,
    settings: AppSettings,
    inputMode: InputMode,
    title: String,
    secondaryMeta: String,
    fileName: String,
    workId: String? = null,
    allBlocks: List<SrtBlock> = emptyList(),
    translatedByIndex: Map<Int, SrtBlock> = emptyMap(),
    dynamicSizer: DynamicBatchSizer? = null,
    onSubBatchCompleted: suspend (List<SrtBlock>) -> Unit = {},
    onAttemptStarted: suspend (keyNumber: Int, keyLabel: String, attempt: Int, maxAttempts: Int) -> Unit = { _, _, _, _ -> },
    onStatusLog: suspend (message: String, level: LogLevel) -> Unit = { _, _ -> },
    onRetryDelay: suspend (seconds: Int, prefix: String) -> Unit = { seconds, _ ->
      for (remaining in seconds downTo 1) {
        currentCoroutineContext().ensureActive()
        delay(1000L)
      }
    },
    onCooldownWait: suspend (cooldownMinutes: Int) -> Unit = { minutes ->
      delay(minutes * 60_000L)
    },
  ): RotationExecutionResult<List<SrtBlock>> {
    return translateBlockSliceWithRotation(
      session = session,
      blocks = segment.blocks,
      sliceStartIndex = segment.startBlockIndex,
      allBlocks = allBlocks,
      translatedByIndex = translatedByIndex,
      fallbackContext = segment.surroundingContext,
      batchIndex = segment.segmentIndex,
      totalBatches = segment.totalSegments,
      settings = settings,
      inputMode = inputMode,
      title = title,
      secondaryMeta = secondaryMeta,
      fileName = fileName,
      workId = workId,
      splitDepth = 0,
      dynamicSizer = dynamicSizer,
      onSubBatchCompleted = onSubBatchCompleted,
      onAttemptStarted = onAttemptStarted,
      onStatusLog = onStatusLog,
      onRetryDelay = onRetryDelay,
      onCooldownWait = onCooldownWait,
    )
  }

  private suspend fun translateBlockSliceWithRotation(
    session: KeyRotationSession,
    blocks: List<SrtBlock>,
    sliceStartIndex: Int,
    allBlocks: List<SrtBlock>,
    translatedByIndex: Map<Int, SrtBlock>,
    fallbackContext: String,
    batchIndex: Int,
    totalBatches: Int,
    settings: AppSettings,
    inputMode: InputMode,
    title: String,
    secondaryMeta: String,
    fileName: String,
    workId: String?,
    splitDepth: Int,
    dynamicSizer: DynamicBatchSizer?,
    onSubBatchCompleted: suspend (List<SrtBlock>) -> Unit,
    onAttemptStarted: suspend (keyNumber: Int, keyLabel: String, attempt: Int, maxAttempts: Int) -> Unit,
    onStatusLog: suspend (message: String, level: LogLevel) -> Unit,
    onRetryDelay: suspend (seconds: Int, prefix: String) -> Unit,
    onCooldownWait: suspend (cooldownMinutes: Int) -> Unit,
  ): RotationExecutionResult<List<SrtBlock>> {
    val payload = SrtParser.formatBlocks(blocks)
    val contextLinesLimit = settings.effectiveContextLines
    val sliceContext =
      when {
        contextLinesLimit <= 0 -> ""
        allBlocks.isNotEmpty() ->
          SrtEngine.buildSurroundingContext(
            allBlocks = allBlocks,
            startIdx = sliceStartIndex,
            endIdxExclusive = sliceStartIndex + blocks.size,
            maxContextLines = contextLinesLimit,
            translatedByIndex = translatedByIndex,
          )
        splitDepth == 0 -> fallbackContext
        else -> ""
      }

    var bestPartialMatches: Map<Int, SrtBlock> = emptyMap()
    var bestMissingBlocks: List<SrtBlock> = emptyList()

    val canSplitForOverload =
      blocks.size >= OVERLOAD_SPLIT_MIN_BLOCKS && splitDepth < MAX_ADAPTIVE_SPLIT_DEPTH

    val outcome =
      keyRotationManager.executeBatchWithRotation(
        session = session,
        model = settings.effectiveModel,
        temperature = settings.effectiveTemperature,
        thinkingBudget = settings.effectiveThinkingBudget,
        systemPrompt = settings.effectivePrompt,
        fewShotExamples = settings.fewShotExamples,
        inputMode = inputMode,
        title = title,
        secondaryMeta = secondaryMeta,
        batchSrtPayload = payload,
        batchIndex = batchIndex,
        totalBatches = totalBatches,
        fileName = fileName,
        maxAttemptsPerKey = settings.effectiveMaxAttemptsPerKey,
        retryIntervalSeconds = settings.effectiveRetryIntervalSeconds,
        cooldownMinutes = settings.effectiveCooldownMinutes,
        workId = workId,
        surroundingContext = sliceContext,
        provider = settings.apiProvider,
        blockCount = blocks.size,
        maxCooldownCycles = if (canSplitForOverload) 0 else 2,
        validateOutput = { rawText ->
          val parsedOutput = SrtEngine.parseModelOutput(rawText, expectedBatch = blocks)
          when (val reconciled = SrtEngine.reconcileBatch(blocks, parsedOutput)) {
            is BatchReconciliationResult.Resolved ->
              BatchValidationOutcome.Valid(reconciled.blocks)
            is BatchReconciliationResult.TooIncomplete -> {
              if (reconciled.matchedBlocks.size > bestPartialMatches.size) {
                bestPartialMatches = reconciled.matchedBlocks
                bestMissingBlocks = reconciled.missingBlocks
              }
              val isTruncatedLargeBatch =
                reconciled.matchedCount > 0 && blocks.size >= PARTIAL_RECOVERY_MIN_BATCH_SIZE
              BatchValidationOutcome.Invalid(
                reason = reconciled.reason,
                failImmediately = isTruncatedLargeBatch,
              )
            }
          }
        },
        onAttemptStarted = onAttemptStarted,
        onStatusLog = onStatusLog,
        onRetryDelay = onRetryDelay,
        onCooldownWait = onCooldownWait,
      )

    if (outcome is RotationExecutionResult.Success) {
      if (splitDepth > 0) {
        onSubBatchCompleted(outcome.value)
      }
      return outcome
    }

    val shouldRecoverBySplitting =
      outcome is RotationExecutionResult.Failure &&
        splitDepth < MAX_ADAPTIVE_SPLIT_DEPTH &&
        ((outcome.isValidationFailure && blocks.size >= MIN_ADAPTIVE_SPLIT_BLOCKS) ||
          (outcome.isTransientOverloadOrRateLimit && blocks.size >= OVERLOAD_SPLIT_MIN_BLOCKS))

    if (shouldRecoverBySplitting) {
      val failure = outcome
      if (splitDepth == 0) {
        dynamicSizer?.recordTruncation()
      }
      if (failure.isTransientOverloadOrRateLimit) {
        if (failure.hadRateLimitedKeys) {
          onStatusLog(
            "Batch $batchIndex/$totalBatches overloaded/rate-limited across keys (${blocks.size} cues); cooling 1m before splitting",
            LogLevel.COOLDOWN,
          )
          onCooldownWait(1)
        } else {
          onStatusLog(
            "Batch $batchIndex/$totalBatches timed out/busy across keys (${blocks.size} cues); splitting into smaller halves",
            LogLevel.WARNING,
          )
        }
        keyRotationManager.resetExhaustedAfterCooldown(session)
      }

      // Smart partial-progress recovery: keep already-translated cues and only translate the missing remainder
      val minReusableCount = (blocks.size * 0.35).toInt().coerceAtLeast(1)
      if (
        blocks.size >= PARTIAL_RECOVERY_MIN_BATCH_SIZE &&
          bestPartialMatches.size >= minReusableCount &&
          bestMissingBlocks.isNotEmpty() &&
          bestMissingBlocks.size < blocks.size
      ) {
        val preservedList = blocks.mapNotNull { bestPartialMatches[it.index] }
        onStatusLog(
          "Batch $batchIndex/$totalBatches partial match (${preservedList.size}/${blocks.size} cues preserved); recovering ${bestMissingBlocks.size} missing cues",
          LogLevel.WARNING,
        )
        onSubBatchCompleted(preservedList)

        val updatedTranslated =
          HashMap(translatedByIndex).apply {
            preservedList.forEach { put(it.index, it) }
          }

        val gapChunks = bestMissingBlocks.chunked((blocks.size / 2).coerceIn(15, 60))
        val recoveredByIndex = HashMap<Int, SrtBlock>(bestMissingBlocks.size)
        var lastUsedKeyNum = 1
        var lastUsedKeyLabel = ""
        var totalLatency = 0L
        var totalRateLimits = 0

        for (gapChunk in gapChunks) {
          val gapResult =
            translateBlockSliceWithRotation(
              session = session,
              blocks = gapChunk,
              sliceStartIndex = sliceStartIndex,
              allBlocks = allBlocks,
              translatedByIndex = updatedTranslated,
              fallbackContext = "",
              batchIndex = batchIndex,
              totalBatches = totalBatches,
              settings = settings,
              inputMode = inputMode,
              title = title,
              secondaryMeta = secondaryMeta,
              fileName = fileName,
              workId = workId,
              splitDepth = splitDepth + 1,
              dynamicSizer = null,
              onSubBatchCompleted = onSubBatchCompleted,
              onAttemptStarted = onAttemptStarted,
              onStatusLog = onStatusLog,
              onRetryDelay = onRetryDelay,
              onCooldownWait = onCooldownWait,
            )
          if (gapResult !is RotationExecutionResult.Success) {
            return gapResult
          }
          gapResult.value.forEach { block ->
            recoveredByIndex[block.index] = block
            updatedTranslated[block.index] = block
          }
          lastUsedKeyNum = gapResult.usedKeyNumber
          lastUsedKeyLabel = gapResult.usedKeyLabel
          totalLatency += gapResult.latencyMs
          totalRateLimits += gapResult.rateLimitHitsInBatch
        }

        val mergedInOrder =
          blocks.map { orig ->
            recoveredByIndex[orig.index] ?: bestPartialMatches[orig.index] ?: orig
          }
        return RotationExecutionResult.Success(
          value = mergedInOrder,
          usedKeyNumber = lastUsedKeyNum,
          usedKeyLabel = lastUsedKeyLabel,
          latencyMs = (totalLatency / gapChunks.size.coerceAtLeast(1)).coerceAtLeast(1L),
          rateLimitHitsInBatch = totalRateLimits,
        )
      }

      val mid = blocks.size / 2
      val firstHalf = blocks.subList(0, mid)
      val secondHalf = blocks.subList(mid, blocks.size)
      if (!failure.isTransientOverloadOrRateLimit) {
        onStatusLog(
          "Batch $batchIndex/$totalBatches truncated (${blocks.size} cues); auto-splitting into ${firstHalf.size} + ${secondHalf.size} cues",
          LogLevel.WARNING,
        )
      }

      val firstResult =
        translateBlockSliceWithRotation(
          session = session,
          blocks = firstHalf,
          sliceStartIndex = sliceStartIndex,
          allBlocks = allBlocks,
          translatedByIndex = translatedByIndex,
          fallbackContext = "",
          batchIndex = batchIndex,
          totalBatches = totalBatches,
          settings = settings,
          inputMode = inputMode,
          title = title,
          secondaryMeta = secondaryMeta,
          fileName = fileName,
          workId = workId,
          splitDepth = splitDepth + 1,
          dynamicSizer = null,
          onSubBatchCompleted = onSubBatchCompleted,
          onAttemptStarted = onAttemptStarted,
          onStatusLog = onStatusLog,
          onRetryDelay = onRetryDelay,
          onCooldownWait = onCooldownWait,
        )
      if (firstResult !is RotationExecutionResult.Success) {
        return firstResult
      }

      val updatedTranslated =
        if (translatedByIndex.isEmpty() && firstResult.value.isEmpty()) {
          translatedByIndex
        } else {
          HashMap(translatedByIndex).apply {
            firstResult.value.forEach { put(it.index, it) }
          }
        }

      val secondResult =
        translateBlockSliceWithRotation(
          session = session,
          blocks = secondHalf,
          sliceStartIndex = sliceStartIndex + mid,
          allBlocks = allBlocks,
          translatedByIndex = updatedTranslated,
          fallbackContext = "",
          batchIndex = batchIndex,
          totalBatches = totalBatches,
          settings = settings,
          inputMode = inputMode,
          title = title,
          secondaryMeta = secondaryMeta,
          fileName = fileName,
          workId = workId,
          splitDepth = splitDepth + 1,
          dynamicSizer = null,
          onSubBatchCompleted = onSubBatchCompleted,
          onAttemptStarted = onAttemptStarted,
          onStatusLog = onStatusLog,
          onRetryDelay = onRetryDelay,
          onCooldownWait = onCooldownWait,
        )
      if (secondResult !is RotationExecutionResult.Success) {
        return secondResult
      }

      return RotationExecutionResult.Success(
        value = firstResult.value + secondResult.value,
        usedKeyNumber = secondResult.usedKeyNumber,
        usedKeyLabel = secondResult.usedKeyLabel,
        latencyMs = ((firstResult.latencyMs + secondResult.latencyMs) / 2L).coerceAtLeast(1L),
        rateLimitHitsInBatch = firstResult.rateLimitHitsInBatch + secondResult.rateLimitHitsInBatch,
      )
    }

    return outcome
  }

  suspend fun translateSrtContentWithRotation(
    rawSrtContent: String,
    fileName: String = "subtitle.srt",
    inputMode: InputMode = InputMode.MOVIE,
    title: String = "",
    secondaryMeta: String = "",
    chunkSizeOverride: Int? = null,
    workId: String? = null,
    dynamicBatchSizerOverride: DynamicBatchSizer? = null,
    onSegmentCompleted: suspend (segment: SubtitleSegment, translatedBlocks: List<SrtBlock>) -> Unit = { _, _ -> },
    onStatusLog: suspend (message: String, level: LogLevel) -> Unit = { _, _ -> },
    onRetryDelay: suspend (seconds: Int, prefix: String) -> Unit = { seconds, _ ->
      for (remaining in seconds downTo 1) {
        currentCoroutineContext().ensureActive()
        delay(1000L)
      }
    },
    onCooldownWait: suspend (cooldownMinutes: Int) -> Unit = { minutes ->
      delay(minutes * 60_000L)
    },
  ): SubtitleTranslationServiceResult {
    val settings = settingsRepository.getSettingsSnapshot()
    val parsedBlocks = SrtParser.parseBlocks(rawSrtContent)
    if (parsedBlocks.isEmpty()) {
      return SubtitleTranslationServiceResult.Failure(
        reason = "No valid SRT subtitle blocks found in $fileName"
      )
    }

    val session = keyRotationManager.prepareSession(settings.apiProvider)
    if (session.keys.isEmpty()) {
      return SubtitleTranslationServiceResult.Failure(
        reason = "No ${settings.apiProvider.displayName} API keys configured. Add keys in API Key Manager."
      )
    }

    val baseChunkSize = (chunkSizeOverride ?: settings.effectiveChunkSize).coerceAtLeast(1)
    val dynamicSizer =
      dynamicBatchSizerOverride
        ?: if (chunkSizeOverride == null && settings.dynamicBatchSizingEnabled) {
          DynamicBatchSizer(baseBatchSize = baseChunkSize, enabled = true)
        } else {
          null
        }

    val accumulated = ArrayList<SrtBlock>(parsedBlocks.size)
    val translatedContextMap = HashMap<Int, SrtBlock>(parsedBlocks.size)
    var nextStartIdx = 0
    var segmentIdx = 1
    var segmentsProcessed = 0

    while (nextStartIdx < parsedBlocks.size) {
      currentCoroutineContext().ensureActive()
      val batchSize = (dynamicSizer?.currentBatchSize ?: baseChunkSize).coerceAtLeast(1)
      val remainingBlocks = parsedBlocks.size - nextStartIdx
      val estimatedTotal =
        segmentIdx - 1 + ((remainingBlocks + batchSize - 1) / batchSize).coerceAtLeast(1)
      val segment =
        buildDynamicSlice(
          allBlocks = parsedBlocks,
          startIdx = nextStartIdx,
          batchSize = batchSize,
          segmentIndex = segmentIdx,
          estimatedTotalSegments = estimatedTotal,
          contextLines = settings.effectiveContextLines,
          translatedByIndex = translatedContextMap,
          maxPayloadChars =
            if (dynamicSizer != null && chunkSizeOverride == null) DefaultConfig.MAX_SLICE_PAYLOAD_CHARS
            else Int.MAX_VALUE,
        )

      val outcome =
        translateSegmentWithRotation(
          session = session,
          segment = segment,
          settings = settings,
          inputMode = inputMode,
          title = title,
          secondaryMeta = secondaryMeta,
          fileName = fileName,
          workId = workId,
          allBlocks = parsedBlocks,
          translatedByIndex = translatedContextMap,
          dynamicSizer = dynamicSizer,
          onStatusLog = onStatusLog,
          onRetryDelay = onRetryDelay,
          onCooldownWait = onCooldownWait,
        )

      when (outcome) {
        is RotationExecutionResult.Success -> {
          accumulated.addAll(outcome.value)
          outcome.value.forEach { translatedContextMap[it.index] = it }
          dynamicSizer?.recordSuccessLatency(
            latencyMs = outcome.latencyMs,
            rateLimitHitsInBatch = outcome.rateLimitHitsInBatch,
          )
          onSegmentCompleted(segment, outcome.value)
          nextStartIdx = segment.endBlockIndexExclusive
          segmentIdx++
          segmentsProcessed++
        }
        is RotationExecutionResult.Failure -> {
          return SubtitleTranslationServiceResult.Failure(
            reason = outcome.fatalError,
            partialTranslatedBlocks = accumulated.toList(),
          )
        }
      }
    }

    val reconstructed =
      SrtParser.reconstructFromBlocks(
        originalBlocks = parsedBlocks,
        translatedBlocks = accumulated,
        strictTimecodeCheck = true,
      )
    val validationReport =
      SrtValidator.validateAndRepairBeforeSave(
        rawSrtContent = reconstructed,
        originalBlocks = parsedBlocks,
      )
    if (!validationReport.isValid) {
      return SubtitleTranslationServiceResult.Failure(
        reason = "Final SRT validation failed: ${validationReport.summary}",
        partialTranslatedBlocks = accumulated.toList(),
      )
    }

    return SubtitleTranslationServiceResult.Success(
      fileName = fileName,
      originalBlocks = parsedBlocks,
      translatedBlocks = accumulated,
      reconstructedBengaliSrt = validationReport.normalizedSrt ?: reconstructed,
      segmentsProcessed = segmentsProcessed,
    )
  }
}
