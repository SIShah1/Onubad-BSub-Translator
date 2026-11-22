package com.example.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.BSubApplication
import com.example.BuildConfig
import com.example.MainActivity
import com.example.R
import com.example.data.local.HistoryTranslationStatus
import com.example.data.local.TranslationHistoryEntity
import com.example.domain.model.InputMode
import com.example.domain.model.SelectedSubtitleFile
import com.example.domain.model.TranslationJobRequest
import com.example.domain.model.TranslationSessionState
import com.example.domain.model.TranslationStatus
import com.example.logging.TranslationLogger
import com.example.service.TranslationForegroundService
import com.example.srt.SrtItem
import com.example.srt.SrtParser
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SubtitleTranslationWorker(
  appContext: Context,
  workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

  private val app: BSubApplication
    get() = applicationContext as BSubApplication

  override suspend fun getForegroundInfo(): ForegroundInfo {
    ensureNotificationChannel()
    return createForegroundInfo(app.translationCoordinator.sessionState.value)
  }

  override suspend fun doWork(): Result {
    TranslationLogger.init(applicationContext, isDebugBuild = BuildConfig.DEBUG)
    ensureNotificationChannel()

    val workIdString = id.toString()
    val coordinator = app.translationCoordinator
    val repository = app.subtitleTranslationRepository

    try {
      setForeground(createForegroundInfo(coordinator.sessionState.value))
    } catch (_: Exception) {
      // Allow background execution on platforms/tests where foreground promotion is restricted
    }

    val loaded = loadAndParseRequest(workIdString)
    if (loaded == null) {
      val failureReason = "No valid subtitle files found for translation."
      recordPreExecutionFailure(failureReason)
      TranslationLogger.logWorkerError(
        workId = workIdString,
        message = "Worker aborted before execution: no valid subtitle files found.",
      )
      return Result.failure(
        workDataOf(KEY_ERROR_MESSAGE to failureReason)
      )
    }

    val (manifestFile, cachedSrtFiles, jobRequest) = loaded
    val roomJobIdsArray = jobRequest.preSeededJobIds.values.toTypedArray()
    TranslationLogger.logWorkerStarted(
      workId = workIdString,
      fileCount = jobRequest.files.size,
      totalBlocks = jobRequest.files.sumOf { it.totalBlocks },
    )

    val wakeLock: PowerManager.WakeLock? =
      runCatching {
          val powerManager =
            applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
          powerManager
            ?.newWakeLock(
              PowerManager.PARTIAL_WAKE_LOCK,
              "BSubTranslator::TranslationWorkerWakeLock",
            )
            ?.apply {
              setReferenceCounted(false)
              acquire(60 * 60 * 1000L)
            }
        }
        .getOrNull()

    var shouldCleanupTempFiles = true

    return try {
      // Preserve Room progress when retrying or resuming an already-seeded job ID
      val hasSavedProgress =
        runAttemptCount > 0 ||
          roomJobIdsArray.any { jobId ->
            repository.getTranslatedBlocksMapForJob(jobId).isNotEmpty()
          }
      if (!hasSavedProgress) {
        repository.clearAll()
      }

      // Seed pending SrtItem rows directly into Room via SubtitleTranslationRepository
      for (file in jobRequest.files) {
        val roomJobId = jobRequest.preSeededJobIds[file.id] ?: continue
        val items =
          file.blocks.map { block ->
            SrtItem(
              index = block.index,
              timestamp = block.timestamp,
              content = block.lines.joinToString("\n"),
            )
          }
        repository.seedPendingItemsIfAbsent(
          jobId = roomJobId,
          fileName = file.fileName,
          items = items,
        )
      }

      var succeeded = false
      coroutineScope {
        val progressJob =
          launch {
            var lastPushMs = 0L
            coordinator.sessionState.collectLatest { state ->
              val now = System.currentTimeMillis()
              if (now - lastPushMs >= 350L || !state.isRunningOrCooldown) {
                lastPushMs = now
                setProgress(buildProgressData(state))
                if (state.isRunningOrCooldown) {
                  runCatching { setForeground(createForegroundInfo(state)) }
                }
              }
              delay(150L)
            }
          }

        try {
          succeeded = coordinator.executeJobFromWorker(jobRequest, workId = workIdString)
        } finally {
          progressJob.cancel()
        }
      }

      val finalState = coordinator.sessionState.value
      setProgress(buildProgressData(finalState))
      postResultNotification(finalState)

      if (succeeded) {
        TranslationLogger.logWorkerCompleted(
          workId = workIdString,
          completedFiles = finalState.completedFiles,
          completedBlocks = finalState.completedBlocksAllFiles,
        )
        Result.success(
          workDataOf(
            KEY_COMPLETED_FILES to finalState.completedFiles,
            KEY_COMPLETED_BLOCKS to finalState.completedBlocksAllFiles,
            KEY_ROOM_JOB_IDS to roomJobIdsArray,
          )
        )
      } else {
        TranslationLogger.logWorkerError(
          workId = workIdString,
          message = "Worker finished unsuccessfully: ${finalState.activePhaseMessage}",
        )
        Result.failure(
          workDataOf(
            KEY_ERROR_MESSAGE to finalState.activePhaseMessage,
            KEY_COMPLETED_BLOCKS to finalState.completedBlocksAllFiles,
            KEY_ROOM_JOB_IDS to roomJobIdsArray,
          )
        )
      }
    } catch (cancel: CancellationException) {
      shouldCleanupTempFiles = coordinator.isUserAborted()
      TranslationLogger.logWorkerCancelled(
        workId = workIdString,
        reason = cancel.message ?: "WorkManager task cancelled",
      )
      withContext(NonCancellable) {
        postResultNotification(coordinator.sessionState.value)
      }
      throw cancel
    } catch (e: Exception) {
      val errMsg = e.message ?: "Background translation failed"
      withContext(NonCancellable) {
        recordPreExecutionFailure(errMsg)
      }
      TranslationLogger.logWorkerError(
        workId = workIdString,
        message = "Unhandled exception inside SubtitleTranslationWorker",
        throwable = e,
      )
      Result.failure(
        workDataOf(KEY_ERROR_MESSAGE to errMsg)
      )
    } finally {
      runCatching {
        if (wakeLock?.isHeld == true) {
          wakeLock.release()
        }
      }
      if (shouldCleanupTempFiles) {
        withContext(NonCancellable) {
          manifestFile?.let { runCatching { it.delete() } }
          cachedSrtFiles.forEach { file -> runCatching { file.delete() } }
        }
      }
    }
  }

  private suspend fun recordPreExecutionFailure(reason: String) {
    runCatching {
      val fileName = inputData.getString(KEY_FILE_NAME)?.takeIf { it.isNotBlank() } ?: "subtitle.srt"
      val mode = inputData.getString(KEY_INPUT_MODE)?.takeIf { it.isNotBlank() } ?: InputMode.MOVIE.name
      val titleMeta = inputData.getString(KEY_TITLE)?.takeIf { it.isNotBlank() } ?: fileName
      val modelUsed = app.settingsRepository.getSettingsSnapshot().effectiveModel
      app.historyRepository.saveRecord(
        TranslationHistoryEntity(
          originalFileName = fileName,
          mode = mode,
          titleMetadata = titleMeta,
          totalBlocks = 0,
          translatedBlocks = 0,
          totalLines = 0,
          fileSizeBytes = 0L,
          modelUsed = modelUsed,
          status = HistoryTranslationStatus.FAILED.label,
          translationStatus = HistoryTranslationStatus.FAILED,
          errorMessage = reason,
        )
      )
    }
  }

  private data class LoadedJobPayload(
    val manifestFile: File?,
    val cachedSrtFiles: List<File>,
    val request: TranslationJobRequest,
  )

  private suspend fun loadAndParseRequest(workId: String): LoadedJobPayload? =
    withContext(Dispatchers.IO) {
      val manifestPath = inputData.getString(KEY_JOB_CONFIG_PATH)
      if (!manifestPath.isNullOrBlank()) {
        val manifestFile = File(manifestPath)
        if (!manifestFile.exists()) {
          TranslationLogger.logSrtParseError(
            workId = workId,
            fileName = manifestFile.name,
            reason = "Manifest config file does not exist at $manifestPath",
          )
          return@withContext null
        }
        val manifest =
          runCatching { WorkManifestCodec.fromJson(manifestFile.readText(Charsets.UTF_8)) }
            .onFailure { err ->
              TranslationLogger.logSrtParseError(
                workId = workId,
                fileName = manifestFile.name,
                reason = "Failed to decode WorkManager job manifest JSON",
                throwable = err,
              )
            }
            .getOrNull()
        if (manifest == null) {
          TranslationLogger.logSrtParseError(
            workId = workId,
            fileName = manifestFile.name,
            reason = "WorkManager job manifest decoded to null",
          )
          return@withContext null
        }

        val cachedFiles = ArrayList<File>(manifest.files.size)
        val parsedFiles = ArrayList<SelectedSubtitleFile>(manifest.files.size)
        val preSeededJobIds = LinkedHashMap<String, String>(manifest.files.size)

        for (entry in manifest.files) {
          val localFile = File(entry.localSrtPath)
          if (localFile.exists()) {
            cachedFiles.add(localFile)
          }
          val srtItems =
            parseSrtFromEntry(
              workId = workId,
              fileName = entry.fileName,
              localFile = localFile,
              uriString = entry.uriString,
            )
          if (srtItems.isEmpty()) {
            TranslationLogger.logSrtParseError(
              workId = workId,
              fileName = entry.fileName,
              reason = "Parsed 0 valid SRT subtitle blocks from file",
            )
            continue
          }

          val blocks = srtItems.map { it.toSrtBlock() }
          val totalLines = srtItems.sumOf { it.lines.size }
          parsedFiles.add(
            SelectedSubtitleFile(
              id = entry.id,
              uriString = entry.uriString,
              fileName = entry.fileName,
              fileSizeBytes = entry.fileSizeBytes,
              totalBlocks = blocks.size,
              totalLines = totalLines,
              blocks = blocks,
            )
          )
          preSeededJobIds[entry.id] = entry.roomJobId
        }

        if (parsedFiles.isEmpty()) {
          manifestFile.delete()
          cachedFiles.forEach { runCatching { it.delete() } }
          return@withContext null
        }

        val mode =
          runCatching { InputMode.valueOf(manifest.inputMode) }.getOrDefault(InputMode.MOVIE)
        return@withContext LoadedJobPayload(
          manifestFile = manifestFile,
          cachedSrtFiles = cachedFiles,
          request =
            TranslationJobRequest(
              inputMode = mode,
              title = manifest.title,
              secondaryMeta = manifest.secondaryMeta,
              files = parsedFiles,
              preSeededJobIds = preSeededJobIds,
            ),
        )
      }

      // Direct inputData fallback (raw content, file path, or content URI)
      val fileName = inputData.getString(KEY_FILE_NAME) ?: "subtitle.srt"
      val rawContent = inputData.getString(KEY_SRT_CONTENT)
      val srtFilePath = inputData.getString(KEY_SRT_FILE_PATH)
      val srtUriString = inputData.getString(KEY_SRT_URI)

      val srtItems: List<SrtItem> =
        runCatching {
            when {
              !rawContent.isNullOrBlank() -> SrtParser.parse(rawContent)
              !srtFilePath.isNullOrBlank() && File(srtFilePath).exists() ->
                File(srtFilePath).inputStream().use { SrtParser.parse(it) }
              !srtUriString.isNullOrBlank() ->
                applicationContext.contentResolver
                  .openInputStream(Uri.parse(srtUriString))
                  ?.use { SrtParser.parse(it) }
                  .orEmpty()
              else -> emptyList()
            }
          }
          .onFailure { err ->
            TranslationLogger.logSrtParseError(
              workId = workId,
              fileName = fileName,
              reason = "Exception parsing direct WorkManager SRT input",
              throwable = err,
            )
          }
          .getOrDefault(emptyList())

      if (srtItems.isEmpty()) {
        TranslationLogger.logSrtParseError(
          workId = workId,
          fileName = fileName,
          reason = "Direct input yielded 0 valid SRT subtitle blocks",
        )
        return@withContext null
      }

      val blocks = srtItems.map { it.toSrtBlock() }
      val totalLines = srtItems.sumOf { it.lines.size }
      val fileId = UUID.randomUUID().toString()
      val roomJobId = inputData.getString(KEY_ROOM_JOB_ID) ?: "${id}_0"
      val sizeBytes =
        when {
          rawContent != null -> rawContent.toByteArray(Charsets.UTF_8).size.toLong()
          !srtFilePath.isNullOrBlank() -> File(srtFilePath).length()
          else -> 0L
        }

      val modeName = inputData.getString(KEY_INPUT_MODE) ?: InputMode.MOVIE.name
      val mode = runCatching { InputMode.valueOf(modeName) }.getOrDefault(InputMode.MOVIE)

      LoadedJobPayload(
        manifestFile = null,
        cachedSrtFiles = emptyList(),
        request =
          TranslationJobRequest(
            inputMode = mode,
            title = inputData.getString(KEY_TITLE).orEmpty(),
            secondaryMeta = inputData.getString(KEY_SECONDARY_META).orEmpty(),
            files =
              listOf(
                SelectedSubtitleFile(
                  id = fileId,
                  uriString = srtUriString.orEmpty(),
                  fileName = fileName,
                  fileSizeBytes = sizeBytes,
                  totalBlocks = blocks.size,
                  totalLines = totalLines,
                  blocks = blocks,
                )
              ),
            preSeededJobIds = mapOf(fileId to roomJobId),
          ),
      )
    }

  private fun parseSrtFromEntry(
    workId: String,
    fileName: String,
    localFile: File,
    uriString: String,
  ): List<SrtItem> {
    if (localFile.exists()) {
      return runCatching { localFile.inputStream().use { SrtParser.parse(it) } }
        .onFailure { err ->
          TranslationLogger.logSrtParseError(
            workId = workId,
            fileName = fileName,
            reason = "Failed reading cached SRT file ${localFile.name}",
            throwable = err,
          )
        }
        .getOrDefault(emptyList())
    }
    if (uriString.isNotBlank()) {
      return runCatching {
          applicationContext.contentResolver
            .openInputStream(Uri.parse(uriString))
            ?.use { SrtParser.parse(it) }
            .orEmpty()
        }
        .onFailure { err ->
          TranslationLogger.logSrtParseError(
            workId = workId,
            fileName = fileName,
            reason = "Failed reading SRT content URI",
            throwable = err,
          )
        }
        .getOrDefault(emptyList())
    }
    return emptyList()
  }

  private fun buildProgressData(state: TranslationSessionState): Data {
    return workDataOf(
      KEY_PROGRESS_PERCENT to (state.overallProgressFraction * 100f).toInt().coerceIn(0, 100),
      KEY_COMPLETED_BLOCKS to state.completedBlocksAllFiles,
      KEY_TOTAL_BLOCKS to state.totalBlocksAllFiles,
      KEY_CURRENT_FILE to state.currentFileName,
      KEY_CURRENT_BATCH to state.currentBatchNumber,
      KEY_TOTAL_BATCHES to state.totalBatchesInCurrentFile,
      KEY_PHASE_MESSAGE to state.activePhaseMessage,
    )
  }

  private fun createForegroundInfo(state: TranslationSessionState): ForegroundInfo {
    val notification = buildProgressNotification(state)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(
        TranslationForegroundService.NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
      )
    } else {
      ForegroundInfo(
        TranslationForegroundService.NOTIFICATION_ID,
        notification,
      )
    }
  }

  private fun buildProgressNotification(state: TranslationSessionState): Notification {
    val openAppIntent =
      Intent(applicationContext, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val openAppPendingIntent =
      PendingIntent.getActivity(
        applicationContext,
        201,
        openAppIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val cancelPendingIntent =
      runCatching { WorkManager.getInstance(applicationContext).createCancelPendingIntent(id) }
        .getOrNull()

    val fileInfo =
      if (state.totalFiles > 0) "File ${state.currentFileIndex}/${state.totalFiles}" else "Preparing"
    val batchInfo =
      if (state.totalBatchesInCurrentFile > 0) {
        "Batch ${state.currentBatchNumber}/${state.totalBatchesInCurrentFile}"
      } else {
        "Initializing"
      }
    val keyInfo = if (state.currentKeyNumber > 0) "Key #${state.currentKeyNumber}" else "Key #1"
    val attemptInfo =
      "Attempt ${state.currentAttempt.coerceAtLeast(1)}/${state.maxAttempts.coerceAtLeast(1)}"

    val titleText = "BSub Translator • $fileInfo"
    val bodyText = "$batchInfo • $keyInfo • $attemptInfo • ${state.activePhaseMessage}"

    val maxProgress = state.totalBlocksAllFiles.coerceAtLeast(1)
    val currentProgress = state.completedBlocksAllFiles.coerceIn(0, maxProgress)
    val indeterminate = state.totalBlocksAllFiles <= 0

    val builder =
      NotificationCompat.Builder(applicationContext, TranslationForegroundService.CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_app_icon)
        .setColor(0xFF006D5B.toInt())
        .setContentTitle(titleText)
        .setContentText(bodyText)
        .setStyle(NotificationCompat.BigTextStyle().bigText("$bodyText\n${state.currentFileName}"))
        .setContentIntent(openAppPendingIntent)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setProgress(maxProgress, currentProgress, indeterminate)

    if (cancelPendingIntent != null) {
      builder.addAction(
        android.R.drawable.ic_menu_close_clear_cancel,
        applicationContext.getString(R.string.action_abort),
        cancelPendingIntent,
      )
    }

    return builder.build()
  }

  private fun postResultNotification(state: TranslationSessionState) {
    if (
      state.status != TranslationStatus.COMPLETED &&
      state.status != TranslationStatus.FAILED &&
      state.status != TranslationStatus.ABORTED
    ) {
      return
    }

    val openAppIntent =
      Intent(applicationContext, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val openAppPendingIntent =
      PendingIntent.getActivity(
        applicationContext,
        203,
        openAppIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val (title, body) =
      when (state.status) {
        TranslationStatus.COMPLETED ->
          "Translation Complete" to
            "Translated ${state.completedFiles}/${state.totalFiles} file(s) to Downloads/BSub Translator/."
        TranslationStatus.ABORTED ->
          "Translation Aborted" to
            "Stopped gracefully. Any validated batches have been preserved."
        else -> "Translation Stopped" to state.activePhaseMessage
      }

    val notification =
      NotificationCompat.Builder(applicationContext, TranslationForegroundService.CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_app_icon)
        .setColor(0xFF006D5B.toInt())
        .setContentTitle(title)
        .setContentText(body)
        .setContentIntent(openAppPendingIntent)
        .setAutoCancel(true)
        .build()

    runCatching {
      val manager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.notify(TranslationForegroundService.RESULT_NOTIFICATION_ID, notification)
    }
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
            TranslationForegroundService.CHANNEL_ID,
            applicationContext.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
          )
          .apply {
            description = applicationContext.getString(R.string.notification_channel_description)
            setShowBadge(false)
          }
      val manager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.createNotificationChannel(channel)
    }
  }

  companion object {
    const val KEY_JOB_CONFIG_PATH = "job_config_path"
    const val KEY_SRT_CONTENT = "srt_content"
    const val KEY_SRT_FILE_PATH = "srt_file_path"
    const val KEY_SRT_URI = "srt_uri"
    const val KEY_FILE_NAME = "file_name"
    const val KEY_INPUT_MODE = "input_mode"
    const val KEY_TITLE = "title"
    const val KEY_SECONDARY_META = "secondary_meta"
    const val KEY_ROOM_JOB_ID = "room_job_id"
    const val KEY_ROOM_JOB_IDS = "room_job_ids"

    const val KEY_PROGRESS_PERCENT = "progress_percent"
    const val KEY_COMPLETED_BLOCKS = "completed_blocks"
    const val KEY_TOTAL_BLOCKS = "total_blocks"
    const val KEY_COMPLETED_FILES = "completed_files"
    const val KEY_CURRENT_FILE = "current_file"
    const val KEY_CURRENT_BATCH = "current_batch"
    const val KEY_TOTAL_BATCHES = "total_batches"
    const val KEY_PHASE_MESSAGE = "phase_message"
    const val KEY_ERROR_MESSAGE = "error_message"
  }
}
