package com.example.service

import com.example.data.local.HistoryRepository
import com.example.data.local.HistoryTranslationStatus
import com.example.data.local.SubtitleTranslationRepository
import com.example.data.local.TranslationHistoryEntity
import com.example.data.network.ApiKeyRotationManager
import com.example.data.network.GeminiApiClient
import com.example.data.network.RotationExecutionResult
import com.example.data.preferences.SecureKeyStorage
import com.example.data.preferences.SettingsRepository
import com.example.data.storage.SubtitleFileStorage
import com.example.domain.model.AppSettings
import com.example.domain.model.InputMode
import com.example.domain.model.LogLevel
import com.example.domain.model.ProcessingLogEntry
import com.example.domain.model.SelectedSubtitleFile
import com.example.domain.model.SrtBlock
import com.example.domain.model.TranslationJobRequest
import com.example.domain.model.TranslationSessionState
import com.example.domain.model.TranslationStatus
import com.example.logging.TranslationLogger
import com.example.srt.SrtEngine
import com.example.srt.SrtParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranslationCoordinator(
  private val settingsRepository: SettingsRepository,
  private val keyStorage: SecureKeyStorage,
  private val historyRepository: HistoryRepository,
  private val subtitleTranslationRepository: SubtitleTranslationRepository,
  private val fileStorage: SubtitleFileStorage,
  private val geminiApiClient: GeminiApiClient,
  private val keyRotationManager: ApiKeyRotationManager =
    ApiKeyRotationManager(keyStorage = keyStorage, geminiApiClient = geminiApiClient),
  private val subtitleTranslationService: SubtitleTranslationService =
    SubtitleTranslationService(
      settingsRepository = settingsRepository,
      fileStorage = fileStorage,
      geminiApiClient = geminiApiClient,
      keyRotationManager = keyRotationManager,
    ),
) {

  private val _sessionState = MutableStateFlow(TranslationSessionState())
  val sessionState: StateFlow<TranslationSessionState> = _sessionState.asStateFlow()

  private val logIdGen = AtomicLong(1L)
  private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

  @Volatile private var pendingJobRequest: TranslationJobRequest? = null
  @Volatile private var activeJob: Job? = null
  @Volatile private var userRequestedAbort: Boolean = false

  fun isUserAborted(): Boolean = userRequestedAbort

  fun prepareJobRequest(request: TranslationJobRequest) {
    userRequestedAbort = false
    pendingJobRequest = request
  }

  fun clearLogs() {
    _sessionState.update { state ->
      if (state.isRunningOrCooldown) {
        state.copy(logs = emptyList())
      } else {
        TranslationSessionState()
      }
    }
  }

  fun markQueued(request: TranslationJobRequest) {
    pendingJobRequest = request
    val totalFiles = request.files.size
    val totalBlocks = request.files.sumOf { it.totalBlocks }
    _sessionState.value =
      TranslationSessionState(
        status = TranslationStatus.RUNNING,
        totalFiles = totalFiles,
        completedFiles = 0,
        currentFileIndex = if (totalFiles > 0) 1 else 0,
        currentFileName = request.files.firstOrNull()?.fileName.orEmpty(),
        totalBlocksAllFiles = totalBlocks,
        completedBlocksAllFiles = 0,
        activePhaseMessage = "Queued in background...",
        logs =
          listOf(
            ProcessingLogEntry(
              id = logIdGen.getAndIncrement(),
              timestampLabel = timeFormatter.format(Date()),
              message = "Scheduled background WorkManager translation ($totalFiles file(s), $totalBlocks blocks)",
              level = LogLevel.INFO,
            )
          ),
      )
  }

  suspend fun executeJobFromWorker(
    request: TranslationJobRequest,
    workId: String? = null,
  ): Boolean {
    if (request.files.isEmpty()) {
      appendLog("No subtitle files queued for translation.", LogLevel.ERROR)
      TranslationLogger.logWorkerError(
        workId = workId,
        message = "No subtitle files queued for translation.",
      )
      _sessionState.update {
        it.copy(status = TranslationStatus.FAILED, activePhaseMessage = "No subtitle files selected")
      }
      return false
    }

    userRequestedAbort = false
    activeJob = currentCoroutineContext()[Job]
    return try {
      executeTranslationJob(request, workId = workId)
      _sessionState.value.status == TranslationStatus.COMPLETED
    } catch (cancel: CancellationException) {
      handleCancellationCleanup()
      if (workId != null) {
        TranslationLogger.logWorkerCancelled(
          workId = workId,
          reason = cancel.message ?: "WorkManager execution cancelled",
        )
      }
      if (!currentCoroutineContext().isActive) {
        throw cancel
      }
      false
    } catch (e: Exception) {
      val msg = e.message ?: "Unexpected error"
      appendLog("Translation failed: $msg", LogLevel.ERROR)
      TranslationLogger.logWorkerError(
        workId = workId,
        message = "Translation coordinator failed: $msg",
        throwable = e,
      )
      _sessionState.update {
        it.copy(
          status = TranslationStatus.FAILED,
          activePhaseMessage = "Failed: ${e.message ?: "Error"}",
        )
      }
      false
    } finally {
      activeJob = null
    }
  }

  fun startExecutionInScope(
    serviceScope: CoroutineScope,
    onFinished: () -> Unit,
  ) {
    val request = pendingJobRequest
    if (request == null || request.files.isEmpty()) {
      appendLog("No subtitle files queued for translation.", LogLevel.ERROR)
      _sessionState.update {
        it.copy(status = TranslationStatus.FAILED, activePhaseMessage = "No subtitle files selected")
      }
      onFinished()
      return
    }

    activeJob?.cancel()
    activeJob =
      serviceScope.launch {
        try {
          executeTranslationJob(request)
        } catch (_: CancellationException) {
          handleCancellationCleanup()
        } catch (e: Exception) {
          appendLog("Translation failed: ${e.message ?: "Unexpected error"}", LogLevel.ERROR)
          _sessionState.update {
            it.copy(
              status = TranslationStatus.FAILED,
              activePhaseMessage = "Failed: ${e.message ?: "Error"}",
            )
          }
        } finally {
          onFinished()
        }
      }
  }

  fun abortTranslation() {
    userRequestedAbort = true
    val wasRunning = _sessionState.value.isRunningOrCooldown
    activeJob?.cancel(CancellationException("Aborted by user"))
    activeJob = null
    if (wasRunning) {
      _sessionState.update {
        it.copy(
          status = TranslationStatus.ABORTED,
          cooldownRemainingSeconds = 0,
          activePhaseMessage = "Aborted by user",
        )
      }
    }
  }

  private fun handleCancellationCleanup() {
    appendLog("Translation aborted. Validated batches preserved.", LogLevel.WARNING)
    _sessionState.update {
      it.copy(
        status = TranslationStatus.ABORTED,
        cooldownRemainingSeconds = 0,
        activePhaseMessage = "Translation aborted",
      )
    }
  }

  private suspend fun executeTranslationJob(
    request: TranslationJobRequest,
    workId: String? = null,
  ) {
    val settings = settingsRepository.getSettingsSnapshot()
    val rotationSession = keyRotationManager.prepareSession()
    val allKeys = rotationSession.keys

    if (allKeys.isEmpty()) {
      appendLog("No Gemini API keys configured. Add keys in API Key Manager.", LogLevel.ERROR)
      TranslationLogger.logWorkerError(
        workId = workId,
        message = "No Gemini API keys configured. Add keys in API Key Manager.",
      )
      _sessionState.update {
        it.copy(
          status = TranslationStatus.FAILED,
          activePhaseMessage = "No Gemini API keys configured",
        )
      }
      return
    }

    val totalFiles = request.files.size
    val totalBlocksAllFiles = request.files.sumOf { it.totalBlocks }
    var completedBlocksAllFiles = 0
    val initialKeyIdx = rotationSession.currentIndex

    _sessionState.value =
      TranslationSessionState(
        status = TranslationStatus.RUNNING,
        totalFiles = totalFiles,
        completedFiles = 0,
        currentFileIndex = 1,
        currentFileName = request.files.first().fileName,
        totalBlocksAllFiles = totalBlocksAllFiles,
        completedBlocksAllFiles = 0,
        currentKeyNumber = initialKeyIdx + 1,
        currentKeyLabel = allKeys[initialKeyIdx].label,
        currentAttempt = 1,
        maxAttempts = settings.effectiveMaxAttemptsPerKey,
        activePhaseMessage = "Starting translation...",
        logs = emptyList(),
      )

    appendLog(
      "Started ${request.inputMode.name.lowercase()} job: $totalFiles file(s), $totalBlocksAllFiles blocks (${settings.effectiveModel}, context: ${settings.effectiveContextLines} lines)",
      LogLevel.INFO,
    )

    for ((fileIdx, file) in request.files.withIndex()) {
      currentCoroutineContext().ensureActive()
      val displayFileNum = fileIdx + 1
      val segmentedPlan =
        subtitleTranslationService.parseAndSegment(
          file = file,
          chunkSize = settings.effectiveChunkSize,
          contextLines = settings.effectiveContextLines,
        )
      val segments = segmentedPlan.segments
      val totalBatches = segments.size.coerceAtLeast(1)

      appendLog("File $displayFileNum/$totalFiles – Processing (${file.fileName})", LogLevel.INFO)
      _sessionState.update {
        it.copy(
          currentFileIndex = displayFileNum,
          currentFileName = file.fileName,
          totalBatchesInCurrentFile = totalBatches,
          currentBatchNumber = 1,
          activePhaseMessage = "File $displayFileNum/$totalFiles – Processing",
        )
      }

      val titleMeta = buildTitleMetadata(request)
      val expectedOutputName = SrtEngine.buildOutputFileName(file.fileName, settings)

      if (segmentedPlan.isEmpty) {
        historyRepository.saveRecord(
          TranslationHistoryEntity(
            originalFileName = file.fileName,
            outputFileName = expectedOutputName,
            mode = request.inputMode.name,
            titleMetadata = titleMeta,
            totalBlocks = file.totalBlocks,
            translatedBlocks = 0,
            totalLines = file.totalLines,
            fileSizeBytes = 0L,
            modelUsed = settings.effectiveModel,
            status = HistoryTranslationStatus.FAILED.label,
            translationStatus = HistoryTranslationStatus.FAILED,
            errorMessage = "Skipped: no valid SRT subtitle blocks detected",
          )
        )
        appendLog(
          "File $displayFileNum/$totalFiles – Skipped (no valid SRT blocks)",
          LogLevel.WARNING,
        )
        _sessionState.update { it.copy(completedFiles = displayFileNum) }
        continue
      }

      val historyId =
        historyRepository.createProcessingRecord(
          originalFileName = file.fileName,
          expectedOutputFileName = expectedOutputName,
          mode = request.inputMode.name,
          titleMetadata = titleMeta,
          totalBlocks = file.totalBlocks,
          totalLines = file.totalLines,
          modelUsed = settings.effectiveModel,
        )

      val preSeededId = request.preSeededJobIds[file.id]
      val fileJobId = preSeededId ?: "${System.currentTimeMillis()}_${file.id}"
      if (preSeededId == null) {
        if (fileIdx == 0 && !subtitleTranslationRepository.hasAnyRowsForJob(fileJobId)) {
          subtitleTranslationRepository.clearAll()
        }
        subtitleTranslationRepository.seedPendingBlocksIfAbsent(
          jobId = fileJobId,
          fileName = file.fileName,
          blocks = segmentedPlan.parsedBlocks,
        )
      }

      val savedTranslatedByIndex = subtitleTranslationRepository.getTranslatedBlocksMapForJob(fileJobId)
      val contextTranslatedByIndex = HashMap<Int, SrtBlock>(segmentedPlan.totalBlocks)
      savedTranslatedByIndex.forEach { (idx, savedRow) ->
        contextTranslatedByIndex[idx] =
          SrtBlock(
            index = idx,
            timestamp = savedRow.timestamp,
            lines = savedRow.lines,
          )
      }
      val translatedFileBlocks = ArrayList<SrtBlock>(segmentedPlan.totalBlocks)

      try {
        for (segment in segments) {
          currentCoroutineContext().ensureActive()
          val displayBatchNum = segment.segmentIndex

          val resumedBlocks =
            if (savedTranslatedByIndex.isNotEmpty() &&
              segment.blocks.all { savedTranslatedByIndex.containsKey(it.index) }
            ) {
              segment.blocks.mapNotNull { orig ->
                savedTranslatedByIndex[orig.index]?.let { saved ->
                  SrtBlock(
                    index = orig.index,
                    timestamp = orig.timestamp,
                    lines = saved.lines,
                  )
                }
              }
            } else {
              null
            }

          if (resumedBlocks != null && resumedBlocks.size == segment.blocks.size) {
            translatedFileBlocks.addAll(resumedBlocks)
            resumedBlocks.forEach { contextTranslatedByIndex[it.index] = it }
            completedBlocksAllFiles += resumedBlocks.size
            historyRepository.updateBatchProgress(
              historyId = historyId,
              translatedBlocks = translatedFileBlocks.size,
            )
            appendLog("Batch $displayBatchNum/$totalBatches – Resumed from Room cache", LogLevel.INFO)
            _sessionState.update {
              it.copy(
                currentBatchNumber = displayBatchNum,
                completedBlocksAllFiles = completedBlocksAllFiles,
                activePhaseMessage = "Batch $displayBatchNum/$totalBatches – Resumed",
              )
            }
            continue
          }

          _sessionState.update {
            it.copy(
              currentBatchNumber = displayBatchNum,
              activePhaseMessage = "Batch $displayBatchNum/$totalBatches – Processing",
            )
          }
          appendLog("Batch $displayBatchNum/$totalBatches – Processing", LogLevel.INFO)

          val rotationResult =
            subtitleTranslationService.translateSegmentWithRotation(
              session = rotationSession,
              segment = segment,
              settings = settings,
              inputMode = request.inputMode,
              title = request.title,
              secondaryMeta = request.secondaryMeta,
              fileName = file.fileName,
              workId = workId,
              allBlocks = segmentedPlan.parsedBlocks,
              translatedByIndex = contextTranslatedByIndex,
              onAttemptStarted = { keyNum, keyLabel, attempt, maxAttempts ->
                _sessionState.update {
                  it.copy(
                    status = TranslationStatus.RUNNING,
                    currentKeyNumber = keyNum,
                    currentKeyLabel = keyLabel,
                    currentAttempt = attempt,
                    maxAttempts = maxAttempts,
                    cooldownRemainingSeconds = 0,
                    activePhaseMessage =
                      "File $displayFileNum/$totalFiles • Batch $displayBatchNum/$totalBatches",
                  )
                }
              },
              onStatusLog = { message, level -> appendLog(message, level) },
              onRetryDelay = { seconds, prefix -> delayCancellableSeconds(seconds, prefix) },
              onCooldownWait = { minutes -> runCooldownTimer(minutes) },
            )

          val validatedBlocks =
            when (rotationResult) {
              is RotationExecutionResult.Success -> rotationResult.value
              is RotationExecutionResult.Failure -> {
                // Preserve any validated batches completed so far for this file
                finalizeIncompleteHistory(
                  historyId = historyId,
                  file = file,
                  translatedBlocks = translatedFileBlocks,
                  request = request,
                  settings = settings,
                  failureStatus = HistoryTranslationStatus.FAILED,
                  reason = rotationResult.fatalError,
                )
                appendLog(rotationResult.fatalError, LogLevel.ERROR)
                TranslationLogger.logWorkerError(
                  workId = workId,
                  message =
                    "${file.fileName} batch $displayBatchNum/$totalBatches aborted: ${rotationResult.fatalError}",
                )
                _sessionState.update {
                  it.copy(
                    status = TranslationStatus.FAILED,
                    activePhaseMessage = rotationResult.fatalError,
                  )
                }
                return
              }
            }
          translatedFileBlocks.addAll(validatedBlocks)
          validatedBlocks.forEach { contextTranslatedByIndex[it.index] = it }
          subtitleTranslationRepository.markBatchTranslated(
            jobId = fileJobId,
            translatedBlocks = validatedBlocks,
          )
          historyRepository.updateBatchProgress(
            historyId = historyId,
            translatedBlocks = translatedFileBlocks.size,
          )
          completedBlocksAllFiles += validatedBlocks.size

          appendLog("Batch $displayBatchNum/$totalBatches – Completed", LogLevel.SUCCESS)
          _sessionState.update {
            it.copy(
              completedBlocksAllFiles = completedBlocksAllFiles,
              activePhaseMessage = "Batch $displayBatchNum/$totalBatches – Completed",
            )
          }
        }
      } catch (cancel: CancellationException) {
        finalizeIncompleteHistory(
          historyId = historyId,
          file = file,
          translatedBlocks = translatedFileBlocks,
          request = request,
          settings = settings,
          failureStatus = HistoryTranslationStatus.ABORTED,
          reason = cancel.message ?: "Aborted by user",
        )
        throw cancel
      }

      // All segments for this file completed & validated -> Reconstruct output SRT preserving timecodes
      val formattedSrt =
        SrtParser.reconstructFromBlocks(
          originalBlocks = segmentedPlan.parsedBlocks,
          translatedBlocks = translatedFileBlocks,
          strictTimecodeCheck = true,
        )
      val savedOutput = fileStorage.saveTranslatedSrt(expectedOutputName, formattedSrt)
      runCatching {
        fileStorage.saveOriginalSourceBackup(
          translatedLocalBackupPath = savedOutput.localBackupPath,
          originalFileName = file.fileName,
          blocks = segmentedPlan.parsedBlocks,
        )
      }

      historyRepository.markCompleted(
        historyId = historyId,
        outputFileName = savedOutput.outputFileName,
        savedPath = savedOutput.displayPath,
        contentUri = savedOutput.contentUri,
        localBackupPath = savedOutput.localBackupPath,
        translatedBlocks = translatedFileBlocks.size,
        fileSizeBytes = savedOutput.fileSizeBytes,
      )

      appendLog(
        "File $displayFileNum/$totalFiles – Saved ${savedOutput.outputFileName}",
        LogLevel.SUCCESS,
      )
      _sessionState.update {
        it.copy(
          completedFiles = displayFileNum,
          activePhaseMessage = "File $displayFileNum/$totalFiles – Completed",
        )
      }
    }

    appendLog("All $totalFiles file(s) translated and saved.", LogLevel.SUCCESS)
    _sessionState.update {
      it.copy(
        status = TranslationStatus.COMPLETED,
        completedFiles = totalFiles,
        completedBlocksAllFiles = totalBlocksAllFiles,
        cooldownRemainingSeconds = 0,
        activePhaseMessage = "Translation completed",
      )
    }
  }

  private suspend fun finalizeIncompleteHistory(
    historyId: Long,
    file: SelectedSubtitleFile,
    translatedBlocks: List<SrtBlock>,
    request: TranslationJobRequest,
    settings: AppSettings,
    failureStatus: HistoryTranslationStatus,
    reason: String,
  ) {
    withContext(NonCancellable) {
      if (translatedBlocks.isEmpty()) {
        runCatching {
          historyRepository.markFailed(
            historyId = historyId,
            translatedBlocks = 0,
            errorMessage = reason,
            status = failureStatus,
          )
        }
        return@withContext
      }

      runCatching {
        val formattedSrt = SrtEngine.formatBlocks(translatedBlocks)
        val baseOutputName = SrtEngine.buildOutputFileName(file.fileName, settings)
        val partialName =
          if (baseOutputName.endsWith(".srt", ignoreCase = true)) {
            baseOutputName.dropLast(4) + "_partial.srt"
          } else {
            baseOutputName + "_partial.srt"
          }
        val savedOutput = fileStorage.saveTranslatedSrt(partialName, formattedSrt)
        runCatching {
          fileStorage.saveOriginalSourceBackup(
            translatedLocalBackupPath = savedOutput.localBackupPath,
            originalFileName = file.fileName,
            blocks = file.blocks,
          )
        }
        historyRepository.markPartial(
          historyId = historyId,
          outputFileName = savedOutput.outputFileName,
          savedPath = savedOutput.displayPath,
          contentUri = savedOutput.contentUri,
          localBackupPath = savedOutput.localBackupPath,
          translatedBlocks = translatedBlocks.size,
          totalBlocks = file.totalBlocks,
          fileSizeBytes = savedOutput.fileSizeBytes,
          reason = reason,
        )
        appendLog(
          "Preserved ${translatedBlocks.size} validated blocks in ${savedOutput.outputFileName}",
          LogLevel.INFO,
        )
      }.onFailure {
        runCatching {
          historyRepository.markFailed(
            historyId = historyId,
            translatedBlocks = translatedBlocks.size,
            errorMessage = reason,
            status = failureStatus,
          )
        }
      }
    }
  }

  private suspend fun delayCancellableSeconds(seconds: Int, prefix: String) {
    for (remaining in seconds downTo 1) {
      currentCoroutineContext().ensureActive()
      _sessionState.update { it.copy(activePhaseMessage = "$prefix ${remaining}s") }
      delay(1000L)
    }
  }

  private suspend fun runCooldownTimer(cooldownMinutes: Int) {
    val totalSeconds = cooldownMinutes * 60
    appendLog("All Keys Exhausted – Cooldown ${cooldownMinutes}m", LogLevel.COOLDOWN)
    _sessionState.update {
      it.copy(
        status = TranslationStatus.COOLDOWN,
        cooldownRemainingSeconds = totalSeconds,
        activePhaseMessage = "All Keys Exhausted – Cooldown ${cooldownMinutes}m",
      )
    }

    for (remaining in totalSeconds downTo 1) {
      currentCoroutineContext().ensureActive()
      val mins = remaining / 60
      val secs = remaining % 60
      val formatted = String.format(Locale.US, "%02dm %02ds", mins, secs)
      _sessionState.update {
        it.copy(
          status = TranslationStatus.COOLDOWN,
          cooldownRemainingSeconds = remaining,
          activePhaseMessage = "Cooldown $formatted",
        )
      }
      delay(1000L)
    }
  }

  private fun buildTitleMetadata(request: TranslationJobRequest): String {
    val t = request.title.trim()
    val s = request.secondaryMeta.trim()
    return when {
      t.isNotBlank() && s.isNotBlank() ->
        if (request.inputMode == InputMode.MOVIE) "$t ($s)" else "$t • $s"
      t.isNotBlank() -> t
      s.isNotBlank() -> s
      else -> if (request.inputMode == InputMode.MOVIE) "Movie Subtitle" else "Series Subtitle"
    }
  }

  private fun appendLog(message: String, level: LogLevel) {
    val entry =
      ProcessingLogEntry(
        id = logIdGen.getAndIncrement(),
        timestampLabel = timeFormatter.format(Date()),
        message = message,
        level = level,
      )
    _sessionState.update { state ->
      val updated = (state.logs + entry).takeLast(400)
      state.copy(logs = updated)
    }
  }
}
