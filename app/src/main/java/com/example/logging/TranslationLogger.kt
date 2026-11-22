package com.example.logging

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

enum class ErrorCategory {
  API_QUOTA_EXHAUSTED,
  API_INVALID_KEY,
  API_RETRYABLE_ERROR,
  SRT_PARSE_ERROR,
  SRT_VALIDATION_ERROR,
  COOLDOWN,
  WORKER_EXECUTION_ERROR,
  GENERAL,
}

data class TranslationErrorEvent(
  val timestampEpochMs: Long,
  val timestampLabel: String,
  val priority: Int,
  val category: ErrorCategory,
  val tag: String,
  val message: String,
  val workId: String? = null,
  val fileName: String? = null,
  val batchNumber: Int? = null,
  val keyLabel: String? = null,
  val httpCode: Int? = null,
  val throwableSummary: String? = null,
)

internal data class StructuredLogContext(
  val category: ErrorCategory,
  val workId: String? = null,
  val fileName: String? = null,
  val batchNumber: Int? = null,
  val keyLabel: String? = null,
  val httpCode: Int? = null,
)

class WorkManagerDiagnosticTree(
  private val logFile: File?,
  private val isDebugBuild: Boolean,
  private val onErrorEventRecorded: (TranslationErrorEvent) -> Unit,
) : Timber.Tree() {

  private val fileLock = ReentrantLock()
  private val timestampFormat =
    object : ThreadLocal<SimpleDateFormat>() {
      override fun initialValue(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

  override fun isLoggable(tag: String?, priority: Int): Boolean {
    return isDebugBuild || priority >= Log.INFO
  }

  override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
    val meta = TranslationLogger.consumeStructuredContext()
    val resolvedTag = formatTag(tag)
    val sanitizedMessage = TranslationLogger.redactSensitiveKeys(message)
    val throwableText =
      t?.let { err ->
        val rawSummary = "${err::class.java.simpleName}: ${err.message.orEmpty()}".trim()
        TranslationLogger.redactSensitiveKeys(rawSummary)
      }

    val logcatLine =
      if (throwableText.isNullOrBlank()) {
        sanitizedMessage
      } else {
        "$sanitizedMessage | $throwableText"
      }

    // Safe on Android and Robolectric (avoid crashing if Log stub is not mocked in plain JVM tests)
    runCatching { Log.println(priority, resolvedTag, logcatLine) }

    if (priority >= Log.WARN || meta != null) {
      val nowMs = System.currentTimeMillis()
      val timeLabel = timestampFormat.get()?.format(Date(nowMs)) ?: nowMs.toString()
      val event =
        TranslationErrorEvent(
          timestampEpochMs = nowMs,
          timestampLabel = timeLabel,
          priority = priority,
          category = meta?.category ?: ErrorCategory.GENERAL,
          tag = resolvedTag,
          message = sanitizedMessage,
          workId = meta?.workId,
          fileName = meta?.fileName,
          batchNumber = meta?.batchNumber,
          keyLabel = meta?.keyLabel,
          httpCode = meta?.httpCode,
          throwableSummary = throwableText,
        )
      onErrorEventRecorded(event)
      appendEventToRollingFile(event)
    }
  }

  private fun appendEventToRollingFile(event: TranslationErrorEvent) {
    val target = logFile ?: return
    fileLock.withLock {
      runCatching {
        target.parentFile?.mkdirs()
        if (target.exists() && target.length() > MAX_LOG_FILE_BYTES) {
          val lines = target.readLines(Charsets.UTF_8)
          val retained = lines.takeLast(lines.size / 2)
          target.writeText(retained.joinToString("\n") + "\n", Charsets.UTF_8)
        }
        val priorityChar =
          when (event.priority) {
            Log.ERROR, Log.ASSERT -> "E"
            Log.WARN -> "W"
            Log.INFO -> "I"
            else -> "D"
          }
        val metaParts =
          buildList {
            event.workId?.let { add("work=$it") }
            event.fileName?.let { add("file=$it") }
            event.batchNumber?.let { add("batch=$it") }
            event.keyLabel?.let { add("key=$it") }
            event.httpCode?.let { add("http=$it") }
          }.joinToString(", ")

        val line =
          buildString {
            append("${event.timestampLabel} $priorityChar/${event.tag} [${event.category.name}] ")
            if (metaParts.isNotEmpty()) {
              append("($metaParts) ")
            }
            append(event.message)
            if (!event.throwableSummary.isNullOrBlank()) {
              append(" -> ${event.throwableSummary}")
            }
          }
        target.appendText(line + "\n", Charsets.UTF_8)
      }
    }
  }

  private fun formatTag(tag: String?): String {
    val clean = tag?.takeIf { it.isNotBlank() } ?: "BSubWorker"
    return if (clean.startsWith("BSub")) clean else "BSub/$clean"
  }

  companion object {
    private const val MAX_LOG_FILE_BYTES = 128L * 1024L
  }
}

object TranslationLogger {

  private const val MAX_MEMORY_EVENTS = 250
  private const val TAG_WORKER = "WorkManager"
  private const val TAG_GEMINI_API = "GeminiAPI"
  private const val TAG_SRT_ENGINE = "SrtEngine"

  private val API_KEY_PATTERN = Regex("""AIza[0-9A-Za-z_-]{12,}""")
  private val QUERY_KEY_PATTERN = Regex("""([?&]key=)[^&\s]+""", RegexOption.IGNORE_CASE)

  private val structuredContextHolder = ThreadLocal<StructuredLogContext?>()

  @Volatile private var diagnosticLogFile: File? = null

  private val _errorEvents = MutableStateFlow<List<TranslationErrorEvent>>(emptyList())
  val errorEvents: StateFlow<List<TranslationErrorEvent>> = _errorEvents.asStateFlow()

  @Synchronized
  fun init(context: Context, isDebugBuild: Boolean = false) {
    val targetFile = File(context.applicationContext.filesDir, "logs/translation_errors.log")
    diagnosticLogFile = targetFile

    val alreadyPlanted = Timber.forest().any { it is WorkManagerDiagnosticTree }
    if (!alreadyPlanted) {
      Timber.plant(
        WorkManagerDiagnosticTree(
          logFile = targetFile,
          isDebugBuild = isDebugBuild,
          onErrorEventRecorded = { event ->
            _errorEvents.update { current -> (current + event).takeLast(MAX_MEMORY_EVENTS) }
          },
        )
      )
    }
  }

  internal fun consumeStructuredContext(): StructuredLogContext? {
    val current = structuredContextHolder.get()
    structuredContextHolder.remove()
    return current
  }

  fun redactSensitiveKeys(input: String): String {
    if (input.isEmpty()) return input
    val maskedQuery =
      QUERY_KEY_PATTERN.replace(input) { match -> "${match.groupValues[1]}[REDACTED]" }
    return API_KEY_PATTERN.replace(maskedQuery) { match ->
      val key = match.value
      if (key.length <= 10) "[REDACTED_KEY]"
      else "${key.take(6)}***${key.takeLast(4)}"
    }
  }

  fun logWorkerStarted(workId: String, fileCount: Int, totalBlocks: Int) {
    Timber.tag(TAG_WORKER)
      .i("Worker[%s] started batch job: %d file(s), %d blocks", workId, fileCount, totalBlocks)
  }

  fun logWorkerCompleted(workId: String, completedFiles: Int, completedBlocks: Int) {
    Timber.tag(TAG_WORKER)
      .i(
        "Worker[%s] finished successfully: %d file(s), %d blocks translated",
        workId,
        completedFiles,
        completedBlocks,
      )
  }

  fun logWorkerCancelled(workId: String, reason: String) {
    structuredContextHolder.set(
      StructuredLogContext(
        category = ErrorCategory.WORKER_EXECUTION_ERROR,
        workId = workId,
      )
    )
    Timber.tag(TAG_WORKER).w("Worker[%s] cancelled: %s", workId, reason)
  }

  fun logSrtParseError(
    workId: String?,
    fileName: String,
    reason: String,
    throwable: Throwable? = null,
  ) {
    structuredContextHolder.set(
      StructuredLogContext(
        category = ErrorCategory.SRT_PARSE_ERROR,
        workId = workId,
        fileName = fileName,
      )
    )
    if (throwable != null) {
      Timber.tag(TAG_SRT_ENGINE).e(throwable, "SRT parse error in %s: %s", fileName, reason)
    } else {
      Timber.tag(TAG_SRT_ENGINE).e("SRT parse error in %s: %s", fileName, reason)
    }
  }

  fun logApiFailure(
    category: ErrorCategory,
    keyLabel: String,
    httpCode: Int?,
    reason: String,
    fileName: String?,
    batchNumber: Int?,
    workId: String? = null,
  ) {
    structuredContextHolder.set(
      StructuredLogContext(
        category = category,
        workId = workId,
        fileName = fileName,
        batchNumber = batchNumber,
        keyLabel = keyLabel,
        httpCode = httpCode,
      )
    )
    val message =
      "API failure [$category] on $keyLabel" +
        (httpCode?.let { " (HTTP $it)" } ?: "") +
        (fileName?.let { " • file=$it" } ?: "") +
        (batchNumber?.let { " • batch=$it" } ?: "") +
        ": $reason"

    when (category) {
      ErrorCategory.API_RETRYABLE_ERROR -> Timber.tag(TAG_GEMINI_API).w("%s", message)
      else -> Timber.tag(TAG_GEMINI_API).e("%s", message)
    }
  }

  fun logValidationError(
    fileName: String,
    batchNumber: Int,
    totalBatches: Int,
    reason: String,
    keyLabel: String?,
    workId: String? = null,
  ) {
    structuredContextHolder.set(
      StructuredLogContext(
        category = ErrorCategory.SRT_VALIDATION_ERROR,
        workId = workId,
        fileName = fileName,
        batchNumber = batchNumber,
        keyLabel = keyLabel,
      )
    )
    Timber.tag(TAG_SRT_ENGINE)
      .w(
        "Batch validation failed (%s batch %d/%d, %s): %s",
        fileName,
        batchNumber,
        totalBatches,
        keyLabel ?: "unknown-key",
        reason,
      )
  }

  fun logCooldownEntered(cooldownMinutes: Int, reason: String, workId: String? = null) {
    structuredContextHolder.set(
      StructuredLogContext(
        category = ErrorCategory.COOLDOWN,
        workId = workId,
      )
    )
    Timber.tag(TAG_GEMINI_API)
      .w("Entered %d-minute key cooldown: %s", cooldownMinutes, reason)
  }

  fun logWorkerError(
    workId: String?,
    message: String,
    throwable: Throwable? = null,
  ) {
    structuredContextHolder.set(
      StructuredLogContext(
        category = ErrorCategory.WORKER_EXECUTION_ERROR,
        workId = workId,
      )
    )
    if (throwable != null) {
      Timber.tag(TAG_WORKER).e(throwable, "Worker error [%s]: %s", workId ?: "direct", message)
    } else {
      Timber.tag(TAG_WORKER).e("Worker error [%s]: %s", workId ?: "direct", message)
    }
  }

  fun readPersistedErrorLog(context: Context): String {
    val file =
      diagnosticLogFile
        ?: File(context.applicationContext.filesDir, "logs/translation_errors.log")
    return if (file.exists()) file.readText(Charsets.UTF_8) else ""
  }

  fun clearDiagnosticLogs(context: Context) {
    _errorEvents.value = emptyList()
    val file =
      diagnosticLogFile
        ?: File(context.applicationContext.filesDir, "logs/translation_errors.log")
    runCatching {
      if (file.exists()) {
        file.delete()
      }
    }
  }

  @VisibleForTesting
  internal fun resetForTesting(context: Context? = null) {
    structuredContextHolder.remove()
    _errorEvents.value = emptyList()
    context?.let {
      val file = File(it.applicationContext.filesDir, "logs/translation_errors.log")
      runCatching { if (file.exists()) file.delete() }
    }
    Timber.uprootAll()
  }
}
