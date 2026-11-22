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
import com.example.domain.model.InputMode
import com.example.domain.model.LogLevel
import com.example.domain.model.SelectedSubtitleFile
import com.example.domain.model.SrtBlock
import com.example.srt.BatchReconciliationResult
import com.example.srt.SrtEngine
import com.example.srt.SrtParser
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

data class SubtitleSegment(
  val segmentIndex: Int,
  val totalSegments: Int,
  val blocks: List<SrtBlock>,
  val formattedSrtPayload: String,
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
    private const val MAX_ADAPTIVE_SPLIT_DEPTH = 3
  }

  fun parseAndSegment(
    rawSrtContent: String,
    chunkSize: Int,
    fileName: String = "subtitle.srt",
  ): SegmentedSubtitlePlan {
    val blocks = SrtParser.parseBlocks(rawSrtContent)
    return segmentBlocks(
      blocks = blocks,
      chunkSize = chunkSize,
      fileName = fileName,
    )
  }

  fun parseAndSegment(
    file: SelectedSubtitleFile,
    chunkSize: Int,
  ): SegmentedSubtitlePlan {
    return segmentBlocks(
      blocks = file.blocks,
      chunkSize = chunkSize,
      fileName = file.fileName,
    )
  }

  fun parseAndSegment(
    file: File,
    chunkSize: Int,
  ): SegmentedSubtitlePlan {
    val rawContent = if (file.exists() && file.isFile) SrtParser.readRaw(file) else ""
    return parseAndSegment(
      rawSrtContent = rawContent,
      chunkSize = chunkSize,
      fileName = file.name.ifBlank { "subtitle.srt" },
    )
  }

  suspend fun parseAndSegmentUri(
    uri: Uri,
    chunkSize: Int,
  ): SegmentedSubtitlePlan? {
    val analyzed = fileStorage.readAndAnalyzeUri(uri) ?: return null
    return parseAndSegment(analyzed, chunkSize)
  }

  fun segmentBlocks(
    blocks: List<SrtBlock>,
    chunkSize: Int,
    fileName: String = "subtitle.srt",
  ): SegmentedSubtitlePlan {
    if (blocks.isEmpty()) {
      return SegmentedSubtitlePlan(
        fileName = fileName,
        parsedBlocks = emptyList(),
        segments = emptyList(),
      )
    }

    val safeChunkSize = chunkSize.coerceAtLeast(1)
    val chunked = blocks.chunked(safeChunkSize)
    val totalSegments = chunked.size
    val segments =
      chunked.mapIndexed { idx, segmentBlocks ->
        SubtitleSegment(
          segmentIndex = idx + 1,
          totalSegments = totalSegments,
          blocks = segmentBlocks,
          formattedSrtPayload = SrtParser.formatBlocks(segmentBlocks),
        )
      }

    return SegmentedSubtitlePlan(
      fileName = fileName,
      parsedBlocks = blocks,
      segments = segments,
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
      batchIndex = segment.segmentIndex,
      totalBatches = segment.totalSegments,
      settings = settings,
      inputMode = inputMode,
      title = title,
      secondaryMeta = secondaryMeta,
      fileName = fileName,
      workId = workId,
      splitDepth = 0,
      onAttemptStarted = onAttemptStarted,
      onStatusLog = onStatusLog,
      onRetryDelay = onRetryDelay,
      onCooldownWait = onCooldownWait,
    )
  }

  private suspend fun translateBlockSliceWithRotation(
    session: KeyRotationSession,
    blocks: List<SrtBlock>,
    batchIndex: Int,
    totalBatches: Int,
    settings: AppSettings,
    inputMode: InputMode,
    title: String,
    secondaryMeta: String,
    fileName: String,
    workId: String?,
    splitDepth: Int,
    onAttemptStarted: suspend (keyNumber: Int, keyLabel: String, attempt: Int, maxAttempts: Int) -> Unit,
    onStatusLog: suspend (message: String, level: LogLevel) -> Unit,
    onRetryDelay: suspend (seconds: Int, prefix: String) -> Unit,
    onCooldownWait: suspend (cooldownMinutes: Int) -> Unit,
  ): RotationExecutionResult<List<SrtBlock>> {
    val payload = SrtParser.formatBlocks(blocks)
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
        validateOutput = { rawText ->
          val parsedOutput = SrtEngine.parseModelOutput(rawText)
          when (val reconciled = SrtEngine.reconcileBatch(blocks, parsedOutput)) {
            is BatchReconciliationResult.Resolved ->
              BatchValidationOutcome.Valid(reconciled.blocks)
            is BatchReconciliationResult.TooIncomplete ->
              BatchValidationOutcome.Invalid(reconciled.reason)
          }
        },
        onAttemptStarted = onAttemptStarted,
        onStatusLog = onStatusLog,
        onRetryDelay = onRetryDelay,
        onCooldownWait = onCooldownWait,
      )

    if (
      outcome is RotationExecutionResult.Failure &&
        outcome.isValidationFailure &&
        blocks.size >= MIN_ADAPTIVE_SPLIT_BLOCKS &&
        splitDepth < MAX_ADAPTIVE_SPLIT_DEPTH
    ) {
      val mid = blocks.size / 2
      val firstHalf = blocks.subList(0, mid)
      val secondHalf = blocks.subList(mid, blocks.size)
      onStatusLog(
        "Batch $batchIndex/$totalBatches truncated (${blocks.size} cues); auto-splitting into ${firstHalf.size} + ${secondHalf.size} cues",
        LogLevel.WARNING,
      )

      val firstResult =
        translateBlockSliceWithRotation(
          session = session,
          blocks = firstHalf,
          batchIndex = batchIndex,
          totalBatches = totalBatches,
          settings = settings,
          inputMode = inputMode,
          title = title,
          secondaryMeta = secondaryMeta,
          fileName = fileName,
          workId = workId,
          splitDepth = splitDepth + 1,
          onAttemptStarted = onAttemptStarted,
          onStatusLog = onStatusLog,
          onRetryDelay = onRetryDelay,
          onCooldownWait = onCooldownWait,
        )
      if (firstResult !is RotationExecutionResult.Success) {
        return firstResult
      }

      val secondResult =
        translateBlockSliceWithRotation(
          session = session,
          blocks = secondHalf,
          batchIndex = batchIndex,
          totalBatches = totalBatches,
          settings = settings,
          inputMode = inputMode,
          title = title,
          secondaryMeta = secondaryMeta,
          fileName = fileName,
          workId = workId,
          splitDepth = splitDepth + 1,
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
    val effectiveChunkSize = (chunkSizeOverride ?: settings.effectiveChunkSize).coerceAtLeast(1)
    val plan =
      parseAndSegment(
        rawSrtContent = rawSrtContent,
        chunkSize = effectiveChunkSize,
        fileName = fileName,
      )
    if (plan.isEmpty) {
      return SubtitleTranslationServiceResult.Failure(
        reason = "No valid SRT subtitle blocks found in $fileName"
      )
    }

    val session = keyRotationManager.prepareSession()
    if (session.keys.isEmpty()) {
      return SubtitleTranslationServiceResult.Failure(
        reason = "No Gemini API keys configured. Add keys in API Key Manager."
      )
    }

    val accumulated = ArrayList<SrtBlock>(plan.totalBlocks)
    for (segment in plan.segments) {
      currentCoroutineContext().ensureActive()
      val outcome =
        translateSegmentWithRotation(
          session = session,
          segment = segment,
          settings = settings,
          inputMode = inputMode,
          title = title,
          secondaryMeta = secondaryMeta,
          fileName = plan.fileName,
          workId = workId,
          onStatusLog = onStatusLog,
          onRetryDelay = onRetryDelay,
          onCooldownWait = onCooldownWait,
        )

      when (outcome) {
        is RotationExecutionResult.Success -> {
          accumulated.addAll(outcome.value)
          onSegmentCompleted(segment, outcome.value)
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
        originalBlocks = plan.parsedBlocks,
        translatedBlocks = accumulated,
        strictTimecodeCheck = true,
      )

    return SubtitleTranslationServiceResult.Success(
      fileName = plan.fileName,
      originalBlocks = plan.parsedBlocks,
      translatedBlocks = accumulated,
      reconstructedBengaliSrt = reconstructed,
      segmentsProcessed = plan.segments.size,
    )
  }
}
