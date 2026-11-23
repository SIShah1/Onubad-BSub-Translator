package com.example.data.network

import com.example.data.local.ApiRequestOutcome
import com.example.data.local.ApiUsageRepository
import com.example.data.preferences.SecureKeyStorage
import com.example.domain.model.ApiKeyEntry
import com.example.domain.model.ApiKeyStatus
import com.example.domain.model.ApiProvider
import com.example.domain.model.AppSettings
import com.example.domain.model.FewShotExample
import com.example.domain.model.InputMode
import com.example.domain.model.LogLevel
import com.example.logging.ErrorCategory
import com.example.logging.TranslationLogger
import com.example.srt.SrtParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class KeyRotationState(
  val totalKeys: Int = 0,
  val activeKeyIndex: Int = 0,
  val activeKeyLabel: String = "",
  val exhaustedCount: Int = 0,
  val invalidCount: Int = 0,
  val inCooldown: Boolean = false,
)

class KeyRotationSession internal constructor(
  val keys: List<ApiKeyEntry>,
  initialIndex: Int,
  val provider: ApiProvider = ApiProvider.GEMINI,
) {
  var currentIndex: Int =
    if (keys.isEmpty()) 0 else initialIndex.coerceIn(0, keys.lastIndex)
    internal set

  val exhaustedIndices: MutableSet<Int> = HashSet()
  val dailyQuotaExhaustedIndices: MutableSet<Int> = HashSet()
  val rateLimitedIndices: MutableSet<Int> = HashSet()
  val invalidIndices: MutableSet<Int> =
    keys.indices.filterTo(HashSet()) { keys[it].status == ApiKeyStatus.INVALID }

  fun findNextAvailableIndex(startFromIndex: Int = currentIndex): Int? {
    if (keys.isEmpty()) return null
    val start = startFromIndex.coerceIn(0, keys.lastIndex)
    for (offset in keys.indices) {
      val idx = (start + offset) % keys.size
      if (
        idx !in exhaustedIndices &&
          idx !in dailyQuotaExhaustedIndices &&
          idx !in invalidIndices
      ) {
        return idx
      }
    }
    return null
  }

  fun allKeysPermanentlyInvalid(): Boolean =
    keys.isNotEmpty() && invalidIndices.size >= keys.size

  fun allEligibleKeysDailyExhausted(): Boolean {
    if (keys.isEmpty()) return false
    val eligible = keys.indices.filter { it !in invalidIndices }
    return eligible.isNotEmpty() && eligible.all { it in dailyQuotaExhaustedIndices }
  }
}

sealed interface BatchValidationOutcome<out T> {
  data class Valid<T>(val value: T) : BatchValidationOutcome<T>
  data class Invalid(val reason: String) : BatchValidationOutcome<Nothing>
}

sealed interface RotationExecutionResult<out T> {
  data class Success<T>(
    val value: T,
    val usedKeyNumber: Int,
    val usedKeyLabel: String,
    val latencyMs: Long = 0L,
    val rateLimitHitsInBatch: Int = 0,
  ) : RotationExecutionResult<T>

  data class Failure(
    val fatalError: String,
    val isValidationFailure: Boolean = false,
  ) : RotationExecutionResult<Nothing>
}

class ApiKeyRotationManager(
  private val keyStorage: SecureKeyStorage,
  private val geminiApiClient: GeminiApiClient,
  private val apiUsageRepository: ApiUsageRepository? = null,
) {

  companion object {
    private const val MAX_COOLDOWN_CYCLES = 2
    private const val RATE_LIMIT_COOLDOWN_MAX_MINUTES = 1
    private const val MAX_VALIDATION_ATTEMPTS_BEFORE_SPLIT = 2
  }

  private val rotationMutex = Mutex()

  private val _rotationState = MutableStateFlow(KeyRotationState())
  val rotationState: StateFlow<KeyRotationState> = _rotationState.asStateFlow()

  suspend fun prepareSession(provider: ApiProvider = ApiProvider.GEMINI): KeyRotationSession =
    rotationMutex.withLock {
      keyStorage.resetExhaustedAndCooldownKeys(provider)
      var keys = keyStorage.getKeysSnapshot(provider)
      if (keys.isNotEmpty() && keys.all { it.status == ApiKeyStatus.INVALID }) {
        // Allow retry if user edited or re-enabled previously invalid keys
        keyStorage.resetAllKeyStatuses(provider)
        keys = keyStorage.getKeysSnapshot(provider)
      }

      val persistedIdx =
        if (keys.isEmpty()) 0 else keyStorage.getActiveKeyIndex(provider) % keys.size
      val session = KeyRotationSession(keys = keys, initialIndex = persistedIdx, provider = provider)

      val activeIdx = session.findNextAvailableIndex(persistedIdx) ?: 0
      session.currentIndex = activeIdx
      if (keys.isNotEmpty()) {
        keyStorage.setActiveKeyIndex(activeIdx, provider)
      }

      publishState(session, inCooldown = false)
      session
    }

  suspend fun getCurrentAvailableKey(session: KeyRotationSession): Pair<Int, ApiKeyEntry>? =
    rotationMutex.withLock {
      val idx = session.findNextAvailableIndex(session.currentIndex) ?: return@withLock null
      session.currentIndex = idx
      keyStorage.setActiveKeyIndex(idx, session.provider)
      publishState(session, inCooldown = false)
      idx to session.keys[idx]
    }

  suspend fun rotateToNextAvailableKey(
    session: KeyRotationSession,
    markCurrentExhausted: Boolean = false,
    markCurrentInvalid: Boolean = false,
    errorReason: String? = null,
  ): Pair<Int, ApiKeyEntry>? =
    rotationMutex.withLock {
      if (session.keys.isEmpty()) return@withLock null
      val failedIdx = session.currentIndex.coerceIn(0, session.keys.lastIndex)
      val failedKey = session.keys[failedIdx]

      when {
        markCurrentInvalid -> {
          session.invalidIndices.add(failedIdx)
          keyStorage.updateKeyStatus(failedKey.id, ApiKeyStatus.INVALID, errorReason)
        }
        markCurrentExhausted -> {
          session.exhaustedIndices.add(failedIdx)
          keyStorage.updateKeyStatus(failedKey.id, ApiKeyStatus.QUOTA_EXHAUSTED, errorReason)
        }
      }

      val nextStart = (failedIdx + 1) % session.keys.size
      val nextIdx = session.findNextAvailableIndex(nextStart)
      if (nextIdx != null) {
        session.currentIndex = nextIdx
        keyStorage.setActiveKeyIndex(nextIdx, session.provider)
        publishState(session, inCooldown = false)
        nextIdx to session.keys[nextIdx]
      } else {
        publishState(
          session,
          inCooldown =
            !session.allKeysPermanentlyInvalid() && !session.allEligibleKeysDailyExhausted(),
        )
        null
      }
    }

  suspend fun markExhaustedKeysCooldownInStorage(session: KeyRotationSession) {
    rotationMutex.withLock {
      publishState(session, inCooldown = true)
      for (idx in session.exhaustedIndices) {
        if (idx in session.invalidIndices || idx in session.dailyQuotaExhaustedIndices) continue
        val entry = session.keys.getOrNull(idx) ?: continue
        keyStorage.updateKeyStatus(
          entry.id,
          ApiKeyStatus.COOLDOWN,
          "Temporarily rate-limited or busy; waiting for cooldown",
        )
      }
    }
  }

  suspend fun resetExhaustedAfterCooldown(session: KeyRotationSession): Int =
    rotationMutex.withLock {
      session.exhaustedIndices.retainAll(session.dailyQuotaExhaustedIndices)
      session.rateLimitedIndices.clear()
      keyStorage.resetExhaustedAndCooldownKeys(session.provider)
      for (idx in session.dailyQuotaExhaustedIndices) {
        if (idx in session.invalidIndices) continue
        val entry = session.keys.getOrNull(idx) ?: continue
        keyStorage.updateKeyStatus(
          entry.id,
          ApiKeyStatus.QUOTA_EXHAUSTED,
          "Daily quota exhausted",
        )
      }
      val nextIdx = session.findNextAvailableIndex(0) ?: 0
      session.currentIndex = nextIdx
      if (session.keys.isNotEmpty()) {
        keyStorage.setActiveKeyIndex(nextIdx, session.provider)
      }
      publishState(session, inCooldown = false)
      nextIdx
    }

  suspend fun <T> executeBatchWithRotation(
    session: KeyRotationSession,
    model: String,
    temperature: Float,
    thinkingBudget: Int,
    systemPrompt: String,
    fewShotExamples: List<FewShotExample>,
    inputMode: InputMode,
    title: String,
    secondaryMeta: String,
    batchSrtPayload: String,
    batchIndex: Int,
    totalBatches: Int,
    fileName: String,
    maxAttemptsPerKey: Int,
    retryIntervalSeconds: Int,
    cooldownMinutes: Int,
    workId: String? = null,
    surroundingContext: String = "",
    provider: ApiProvider = session.provider,
    blockCount: Int = 0,
    validateOutput: (String) -> BatchValidationOutcome<T>,
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
  ): RotationExecutionResult<T> {
    if (session.keys.isEmpty()) {
      return RotationExecutionResult.Failure(
        "No ${provider.displayName} API keys configured. Add keys in API Key Manager."
      )
    }

    val resolvedBlockCount =
      if (blockCount > 0) blockCount else SrtParser.parseBlocks(batchSrtPayload).size
    val safeMaxAttempts = maxAttemptsPerKey.coerceAtLeast(1)
    val safeRetrySeconds = retryIntervalSeconds.coerceAtLeast(1)
    var cooldownCycles = 0
    var consecutiveValidationFailures = 0
    var rateLimitHitsInBatch = 0

    while (true) {
      currentCoroutineContext().ensureActive()

      if (session.allKeysPermanentlyInvalid()) {
        return RotationExecutionResult.Failure(
          "All configured API keys are invalid. Please update your keys."
        )
      }

      if (session.allEligibleKeysDailyExhausted()) {
        return RotationExecutionResult.Failure(
          "All active API keys exhausted their daily quota limit."
        )
      }

      val selected = getCurrentAvailableKey(session)
      if (selected == null) {
        if (cooldownCycles >= MAX_COOLDOWN_CYCLES) {
          return RotationExecutionResult.Failure(
            "Translation stopped after $MAX_COOLDOWN_CYCLES cooldown cycles: all keys remained rate-limited or busy."
          )
        }
        cooldownCycles++

        val eligibleNonDaily =
          session.keys.indices.filter {
            it !in session.invalidIndices && it !in session.dailyQuotaExhaustedIndices
          }
        val onlyRateLimited =
          eligibleNonDaily.isNotEmpty() && eligibleNonDaily.all { it in session.rateLimitedIndices }
        val effectiveCooldownMinutes =
          if (onlyRateLimited) {
            minOf(cooldownMinutes.coerceAtLeast(1), RATE_LIMIT_COOLDOWN_MAX_MINUTES)
          } else {
            cooldownMinutes.coerceAtLeast(1)
          }

        TranslationLogger.logCooldownEntered(
          cooldownMinutes = effectiveCooldownMinutes,
          reason =
            "All ${session.keys.size} keys temporarily rate-limited or busy during $fileName batch $batchIndex/$totalBatches (cycle $cooldownCycles/$MAX_COOLDOWN_CYCLES)",
          workId = workId,
        )
        markExhaustedKeysCooldownInStorage(session)
        onCooldownWait(effectiveCooldownMinutes)
        val resumedIdx = resetExhaustedAfterCooldown(session)
        onStatusLog("Cooldown ended – Resuming from Key #${resumedIdx + 1}", LogLevel.INFO)
        continue
      }

      val (keyIdx, activeKey) = selected
      val keyNum = keyIdx + 1
      var attempt = 1
      var switchToNextKey = false

      while (attempt <= safeMaxAttempts && !switchToNextKey) {
        currentCoroutineContext().ensureActive()
        onAttemptStarted(keyNum, activeKey.label, attempt, safeMaxAttempts)

        val requestStartNs = System.nanoTime()
        val apiResult =
          geminiApiClient.translateBatch(
            apiKey = activeKey.key,
            model = model,
            temperature = temperature,
            thinkingBudget = thinkingBudget,
            systemPrompt = systemPrompt,
            fewShotExamples = fewShotExamples,
            inputMode = inputMode,
            title = title,
            secondaryMeta = secondaryMeta,
            batchSrtPayload = batchSrtPayload,
            batchIndex = batchIndex,
            totalBatches = totalBatches,
            fileName = fileName,
            surroundingContext = surroundingContext,
            provider = provider,
          )
        val callLatencyMs = ((System.nanoTime() - requestStartNs) / 1_000_000L).coerceAtLeast(1L)

        when (apiResult) {
          is GeminiCallResult.Success -> {
            when (val validation = validateOutput(apiResult.translatedText)) {
              is BatchValidationOutcome.Valid -> {
                recordUsageEvent(
                  keyId = activeKey.id,
                  keyLabel = activeKey.label,
                  provider = provider,
                  model = model,
                  batchSize = resolvedBlockCount,
                  latencyMs = callLatencyMs,
                  outcome = ApiRequestOutcome.SUCCESS,
                  httpCode = 200,
                  blocksTranslated = resolvedBlockCount,
                )
                return RotationExecutionResult.Success(
                  value = validation.value,
                  usedKeyNumber = keyNum,
                  usedKeyLabel = activeKey.label,
                  latencyMs = callLatencyMs,
                  rateLimitHitsInBatch = rateLimitHitsInBatch,
                )
              }
              is BatchValidationOutcome.Invalid -> {
                consecutiveValidationFailures++
                recordUsageEvent(
                  keyId = activeKey.id,
                  keyLabel = activeKey.label,
                  provider = provider,
                  model = model,
                  batchSize = resolvedBlockCount,
                  latencyMs = callLatencyMs,
                  outcome = ApiRequestOutcome.RETRYABLE_ERROR,
                  httpCode = 200,
                  errorReason = validation.reason,
                )
                TranslationLogger.logValidationError(
                  fileName = fileName,
                  batchNumber = batchIndex,
                  totalBatches = totalBatches,
                  reason = validation.reason,
                  keyLabel = "Key #$keyNum (${activeKey.label})",
                  workId = workId,
                )
                onStatusLog(
                  "Batch $batchIndex/$totalBatches – Validation failed: ${validation.reason}",
                  LogLevel.WARNING,
                )

                val eligibleCount =
                  (session.keys.size -
                    session.invalidIndices.size -
                    session.dailyQuotaExhaustedIndices.size)
                    .coerceAtLeast(1)

                if (
                  consecutiveValidationFailures >= MAX_VALIDATION_ATTEMPTS_BEFORE_SPLIT ||
                    (eligibleCount == 1 && attempt >= safeMaxAttempts)
                ) {
                  return RotationExecutionResult.Failure(
                    fatalError =
                      "Batch $batchIndex/$totalBatches validation failed: ${validation.reason}",
                    isValidationFailure = true,
                  )
                }

                if (eligibleCount > 1) {
                  onStatusLog(
                    "Retrying batch on next available key without exhausting Key #$keyNum",
                    LogLevel.INFO,
                  )
                  rotateToNextAvailableKey(
                    session = session,
                    markCurrentExhausted = false,
                    markCurrentInvalid = false,
                    errorReason = null,
                  )
                  switchToNextKey = true
                } else {
                  val quickWait = minOf(safeRetrySeconds, 3)
                  onStatusLog(
                    "Retrying batch in ${quickWait}s (Attempt ${attempt + 1}/$safeMaxAttempts)",
                    LogLevel.INFO,
                  )
                  onRetryDelay(quickWait, "Retrying batch in")
                }
              }
            }
          }

          is GeminiCallResult.QuotaExhausted -> {
            consecutiveValidationFailures = 0
            session.dailyQuotaExhaustedIndices.add(keyIdx)
            recordUsageEvent(
              keyId = activeKey.id,
              keyLabel = activeKey.label,
              provider = provider,
              model = model,
              batchSize = resolvedBlockCount,
              latencyMs = callLatencyMs,
              outcome = ApiRequestOutcome.QUOTA_EXHAUSTED,
              httpCode = 429,
              errorReason = apiResult.reason,
            )
            TranslationLogger.logApiFailure(
              category = ErrorCategory.API_QUOTA_EXHAUSTED,
              keyLabel = "Key #$keyNum (${activeKey.label})",
              httpCode = 429,
              reason = apiResult.reason,
              fileName = fileName,
              batchNumber = batchIndex,
              workId = workId,
            )
            onStatusLog("Key #$keyNum – Daily Quota Exceeded", LogLevel.ERROR)
            rotateToNextAvailableKey(
              session = session,
              markCurrentExhausted = true,
              errorReason = apiResult.reason,
            )
            switchToNextKey = true
          }

          is GeminiCallResult.RateLimited -> {
            consecutiveValidationFailures = 0
            rateLimitHitsInBatch++
            session.rateLimitedIndices.add(keyIdx)
            recordUsageEvent(
              keyId = activeKey.id,
              keyLabel = activeKey.label,
              provider = provider,
              model = model,
              batchSize = resolvedBlockCount,
              latencyMs = callLatencyMs,
              outcome = ApiRequestOutcome.RATE_LIMITED,
              httpCode = 429,
              errorReason = apiResult.reason,
            )
            TranslationLogger.logApiFailure(
              category = ErrorCategory.API_QUOTA_EXHAUSTED,
              keyLabel = "Key #$keyNum (${activeKey.label})",
              httpCode = 429,
              reason = apiResult.reason,
              fileName = fileName,
              batchNumber = batchIndex,
              workId = workId,
            )
            onStatusLog("Key #$keyNum – Rate limit (RPM/TPM), switching key", LogLevel.WARNING)
            rotateToNextAvailableKey(
              session = session,
              markCurrentExhausted = true,
              errorReason = apiResult.reason,
            )
            switchToNextKey = true
          }

          is GeminiCallResult.InvalidKey -> {
            consecutiveValidationFailures = 0
            recordUsageEvent(
              keyId = activeKey.id,
              keyLabel = activeKey.label,
              provider = provider,
              model = model,
              batchSize = resolvedBlockCount,
              latencyMs = callLatencyMs,
              outcome = ApiRequestOutcome.INVALID_KEY,
              httpCode = 401,
              errorReason = apiResult.reason,
            )
            TranslationLogger.logApiFailure(
              category = ErrorCategory.API_INVALID_KEY,
              keyLabel = "Key #$keyNum (${activeKey.label})",
              httpCode = 400,
              reason = apiResult.reason,
              fileName = fileName,
              batchNumber = batchIndex,
              workId = workId,
            )
            onStatusLog("Key #$keyNum – Invalid API Key", LogLevel.ERROR)
            rotateToNextAvailableKey(
              session = session,
              markCurrentInvalid = true,
              errorReason = apiResult.reason,
            )
            switchToNextKey = true
          }

          is GeminiCallResult.FatalError -> {
            consecutiveValidationFailures = 0
            recordUsageEvent(
              keyId = activeKey.id,
              keyLabel = activeKey.label,
              provider = provider,
              model = model,
              batchSize = resolvedBlockCount,
              latencyMs = callLatencyMs,
              outcome = ApiRequestOutcome.FATAL_ERROR,
              httpCode = apiResult.httpCode,
              errorReason = apiResult.reason,
            )
            TranslationLogger.logApiFailure(
              category = ErrorCategory.API_RETRYABLE_ERROR,
              keyLabel = "Key #$keyNum (${activeKey.label})",
              httpCode = apiResult.httpCode,
              reason = apiResult.reason,
              fileName = fileName,
              batchNumber = batchIndex,
              workId = workId,
            )
            onStatusLog(
              "Fatal API error (HTTP ${apiResult.httpCode}): ${apiResult.reason}",
              LogLevel.ERROR,
            )
            return RotationExecutionResult.Failure(
              fatalError = "HTTP ${apiResult.httpCode}: ${apiResult.reason}"
            )
          }

          is GeminiCallResult.RetryableError -> {
            consecutiveValidationFailures = 0
            val isRateLimit = apiResult.httpCode == 429
            if (isRateLimit) {
              rateLimitHitsInBatch++
            }
            recordUsageEvent(
              keyId = activeKey.id,
              keyLabel = activeKey.label,
              provider = provider,
              model = model,
              batchSize = resolvedBlockCount,
              latencyMs = callLatencyMs,
              outcome =
                if (isRateLimit) ApiRequestOutcome.RATE_LIMITED
                else ApiRequestOutcome.RETRYABLE_ERROR,
              httpCode = apiResult.httpCode,
              errorReason = apiResult.reason,
            )
            TranslationLogger.logApiFailure(
              category = ErrorCategory.API_RETRYABLE_ERROR,
              keyLabel = "Key #$keyNum (${activeKey.label})",
              httpCode = apiResult.httpCode,
              reason = "${apiResult.reason} (attempt $attempt/$safeMaxAttempts)",
              fileName = fileName,
              batchNumber = batchIndex,
              workId = workId,
            )
            val shortReason =
              if (apiResult.reason.contains("Server busy", ignoreCase = true) ||
                apiResult.httpCode == 503 ||
                apiResult.httpCode == 500
              ) {
                "Server Busy"
              } else {
                apiResult.reason.take(64)
              }
            onStatusLog("Key #$keyNum – $shortReason", LogLevel.WARNING)

            if (!isRateLimit && attempt < safeMaxAttempts) {
              onStatusLog("Retrying in ${safeRetrySeconds}s", LogLevel.INFO)
              onRetryDelay(safeRetrySeconds, "Retrying in")
            } else {
              val reasonMsg =
                if (isRateLimit) {
                  "Rate limit hit (HTTP 429), switching to next key"
                } else {
                  "Max attempts ($safeMaxAttempts) reached, switching key"
                }
              onStatusLog("Key #$keyNum – $reasonMsg", LogLevel.WARNING)
              rotateToNextAvailableKey(
                session = session,
                markCurrentExhausted = true,
                errorReason = apiResult.reason,
              )
              switchToNextKey = true
            }
          }
        }
        attempt++
      }
    }
  }

  /**
   * Convenience entry point for direct callers wanting Retrofit + DataStore key rotation
   * using the current AppSettings snapshot.
   */
  suspend fun translateBatchWithAutoRotation(
    settings: AppSettings,
    inputMode: InputMode,
    title: String,
    secondaryMeta: String,
    batchSrtPayload: String,
    batchIndex: Int = 1,
    totalBatches: Int = 1,
    fileName: String = "subtitle.srt",
    workId: String? = null,
    surroundingContext: String = "",
    onRetryDelay: suspend (seconds: Int, prefix: String) -> Unit = { seconds, _ ->
      for (remaining in seconds downTo 1) {
        currentCoroutineContext().ensureActive()
        delay(1000L)
      }
    },
    onCooldownWait: suspend (cooldownMinutes: Int) -> Unit = { minutes ->
      delay(minutes * 60_000L)
    },
  ): RotationExecutionResult<String> {
    val session = prepareSession(settings.apiProvider)
    return executeBatchWithRotation(
      session = session,
      model = settings.effectiveModel,
      temperature = settings.effectiveTemperature,
      thinkingBudget = settings.effectiveThinkingBudget,
      systemPrompt = settings.effectivePrompt,
      fewShotExamples = settings.fewShotExamples,
      inputMode = inputMode,
      title = title,
      secondaryMeta = secondaryMeta,
      batchSrtPayload = batchSrtPayload,
      batchIndex = batchIndex,
      totalBatches = totalBatches,
      fileName = fileName,
      maxAttemptsPerKey = settings.effectiveMaxAttemptsPerKey,
      retryIntervalSeconds = settings.effectiveRetryIntervalSeconds,
      cooldownMinutes = settings.effectiveCooldownMinutes,
      workId = workId,
      surroundingContext = surroundingContext,
      provider = settings.apiProvider,
      validateOutput = { raw ->
        if (raw.isBlank()) {
          BatchValidationOutcome.Invalid("Empty response from ${settings.apiProvider.displayName}")
        } else {
          BatchValidationOutcome.Valid(raw)
        }
      },
      onRetryDelay = onRetryDelay,
      onCooldownWait = onCooldownWait,
    )
  }

  private suspend fun recordUsageEvent(
    keyId: String,
    keyLabel: String,
    provider: ApiProvider,
    model: String,
    batchSize: Int,
    latencyMs: Long,
    outcome: ApiRequestOutcome,
    httpCode: Int? = null,
    blocksTranslated: Int = 0,
    errorReason: String? = null,
  ) {
    val repo = apiUsageRepository ?: return
    runCatching {
      repo.recordRequestOutcome(
        keyId = keyId,
        keyLabel = keyLabel,
        provider = provider,
        model = model,
        batchSize = batchSize,
        dynamicBatchSizeAfter = batchSize,
        latencyMs = latencyMs,
        outcome = outcome,
        httpCode = httpCode,
        blocksTranslated = blocksTranslated,
        errorReason = errorReason,
      )
    }
  }

  private fun publishState(session: KeyRotationSession, inCooldown: Boolean) {
    val current = session.keys.getOrNull(session.currentIndex)
    _rotationState.value =
      KeyRotationState(
        totalKeys = session.keys.size,
        activeKeyIndex = session.currentIndex,
        activeKeyLabel = current?.label.orEmpty(),
        exhaustedCount = session.exhaustedIndices.size,
        invalidCount = session.invalidIndices.size,
        inCooldown = inCooldown,
      )
  }
}
