package com.example.domain.model

import java.util.UUID

enum class InputMode {
  MOVIE,
  SERIES,
}

enum class ThemeMode {
  DARK,
  LIGHT,
  SYSTEM,
}

enum class PromptMode {
  DEFAULT,
  MANUAL,
}

enum class NamingMode {
  DEFAULT,
  CUSTOM,
}

enum class ParamMode {
  DEFAULT,
  MANUAL,
}

enum class ApiKeyStatus {
  ACTIVE,
  QUOTA_EXHAUSTED,
  INVALID,
  COOLDOWN,
}

enum class LogLevel {
  INFO,
  SUCCESS,
  WARNING,
  ERROR,
  COOLDOWN,
}

enum class TranslationStatus {
  IDLE,
  RUNNING,
  COOLDOWN,
  COMPLETED,
  ABORTED,
  FAILED,
}

data class SrtBlock(
  val index: Int,
  val timestamp: String,
  val lines: List<String>,
)

data class SelectedSubtitleFile(
  val id: String = UUID.randomUUID().toString(),
  val uriString: String,
  val fileName: String,
  val fileSizeBytes: Long,
  val totalBlocks: Int,
  val totalLines: Int,
  val blocks: List<SrtBlock>,
)

data class ApiKeyEntry(
  val id: String = UUID.randomUUID().toString(),
  val key: String,
  val label: String,
  val status: ApiKeyStatus = ApiKeyStatus.ACTIVE,
  val lastError: String? = null,
)

data class FewShotExample(
  val id: String = UUID.randomUUID().toString(),
  val englishText: String,
  val bengaliTranslation: String,
  val note: String = "",
)

object DefaultConfig {
  const val CHUNK_SIZE = 150
  const val GEMINI_MODEL = "gemini-3.8-flash"
  const val TEMPERATURE = 0.3f
  const val THINKING_BUDGET = 0
  const val RETRY_INTERVAL_SECONDS = 5
  const val MAX_ATTEMPTS_PER_KEY = 3
  const val COOLDOWN_MINUTES = 30
  const val DEFAULT_SUFFIX = "_BSub"
  const val CONTEXT_LINES = 50
  const val MIN_CONTEXT_LINES = 0
  const val MAX_CONTEXT_LINES = 500

  val PRESET_CHUNK_SIZES = listOf(100, 150, 200, 300)
  val PRESET_CONTEXT_LINES = listOf(0, 25, 50, 100, 150)

  val BUILT_IN_PROMPT =
    """
You are an expert English-to-Bengali movie and TV series subtitle translator.
Translate the provided English .srt subtitle blocks into natural, expressive, conversational Bengali.

STRICT TRANSLATION RULES:
1. Context & Tone: Match the character's tone, emotion, relationship formality (আপনি / তুমি / তুই), and colloquial nuance rather than translating word-for-word.
2. Readability: Keep Bengali lines concise, natural, and easy to read on screen while preserving the full meaning and humor/drama.
3. Names & Terms: Keep proper nouns, character names, and well-known titles naturally transliterated in Bengali script where appropriate.
4. Formatting Tags: Preserve any inline HTML formatting tags (e.g., <i>...</i>, <b>...</b>) or speaker dashes (- ) if present.

CRITICAL SRT INTEGRITY RULES (MUST NEVER VIOLATE):
- Keep the EXACT same number of subtitle blocks. Do NOT merge, split, skip, add, or duplicate any block.
- Keep EVERY block's exact serial number unchanged.
- Keep EVERY block's exact timestamp line (HH:MM:SS,mmm --> HH:MM:SS,mmm) completely unchanged.
- Output ONLY valid .srt subtitle content. Do NOT include markdown code fences (```), explanations, notes, or extra text.
    """.trimIndent()
}

data class AppSettings(
  val isLoaded: Boolean = false,
  val themeMode: ThemeMode = ThemeMode.DARK,
  val hasCompletedFirstLaunchSetup: Boolean = false,
  val promptMode: PromptMode = PromptMode.DEFAULT,
  val defaultPrompt: String = DefaultConfig.BUILT_IN_PROMPT,
  val manualPrompt: String = "",
  val fewShotExamples: List<FewShotExample> = emptyList(),
  val namingMode: NamingMode = NamingMode.DEFAULT,
  val customPrefix: String = "",
  val customSuffix: String = DefaultConfig.DEFAULT_SUFFIX,
  // Advanced settings (Default vs Manual)
  val chunkSizeMode: ParamMode = ParamMode.DEFAULT,
  val manualChunkSize: Int = DefaultConfig.CHUNK_SIZE,
  val modelMode: ParamMode = ParamMode.DEFAULT,
  val manualModel: String = DefaultConfig.GEMINI_MODEL,
  val temperatureMode: ParamMode = ParamMode.DEFAULT,
  val manualTemperature: Float = DefaultConfig.TEMPERATURE,
  val thinkingBudgetMode: ParamMode = ParamMode.DEFAULT,
  val manualThinkingBudget: Int = DefaultConfig.THINKING_BUDGET,
  val retryIntervalMode: ParamMode = ParamMode.DEFAULT,
  val manualRetryIntervalSeconds: Int = DefaultConfig.RETRY_INTERVAL_SECONDS,
  val maxAttemptsMode: ParamMode = ParamMode.DEFAULT,
  val manualMaxAttemptsPerKey: Int = DefaultConfig.MAX_ATTEMPTS_PER_KEY,
  val cooldownMode: ParamMode = ParamMode.DEFAULT,
  val manualCooldownMinutes: Int = DefaultConfig.COOLDOWN_MINUTES,
  val contextLinesMode: ParamMode = ParamMode.DEFAULT,
  val manualContextLines: Int = DefaultConfig.CONTEXT_LINES,
) {
  val effectivePrompt: String
    get() =
      if (promptMode == PromptMode.MANUAL && manualPrompt.isNotBlank()) {
        manualPrompt.trim()
      } else {
        defaultPrompt.ifBlank { DefaultConfig.BUILT_IN_PROMPT }.trim()
      }

  val effectiveChunkSize: Int
    get() =
      if (chunkSizeMode == ParamMode.MANUAL) {
        manualChunkSize.coerceIn(10, 2000)
      } else {
        DefaultConfig.CHUNK_SIZE
      }

  val effectiveModel: String
    get() =
      if (modelMode == ParamMode.MANUAL && manualModel.isNotBlank()) {
        manualModel.trim()
      } else {
        DefaultConfig.GEMINI_MODEL
      }

  val effectiveTemperature: Float
    get() =
      if (temperatureMode == ParamMode.MANUAL) {
        manualTemperature.coerceIn(0.0f, 2.0f)
      } else {
        DefaultConfig.TEMPERATURE
      }

  val effectiveThinkingBudget: Int
    get() =
      if (thinkingBudgetMode == ParamMode.MANUAL) {
        manualThinkingBudget.coerceIn(0, 32768)
      } else {
        DefaultConfig.THINKING_BUDGET
      }

  val effectiveRetryIntervalSeconds: Int
    get() =
      if (retryIntervalMode == ParamMode.MANUAL) {
        manualRetryIntervalSeconds.coerceIn(1, 300)
      } else {
        DefaultConfig.RETRY_INTERVAL_SECONDS
      }

  val effectiveMaxAttemptsPerKey: Int
    get() =
      if (maxAttemptsMode == ParamMode.MANUAL) {
        manualMaxAttemptsPerKey.coerceIn(1, 20)
      } else {
        DefaultConfig.MAX_ATTEMPTS_PER_KEY
      }

  val effectiveCooldownMinutes: Int
    get() =
      if (cooldownMode == ParamMode.MANUAL) {
        manualCooldownMinutes.coerceIn(1, 180)
      } else {
        DefaultConfig.COOLDOWN_MINUTES
      }

  val effectiveContextLines: Int
    get() =
      if (contextLinesMode == ParamMode.MANUAL) {
        manualContextLines.coerceIn(DefaultConfig.MIN_CONTEXT_LINES, DefaultConfig.MAX_CONTEXT_LINES)
      } else {
        DefaultConfig.CONTEXT_LINES
      }
}

data class ProcessingLogEntry(
  val id: Long,
  val timestampLabel: String,
  val message: String,
  val level: LogLevel,
)

data class TranslationSessionState(
  val status: TranslationStatus = TranslationStatus.IDLE,
  val totalFiles: Int = 0,
  val completedFiles: Int = 0,
  val currentFileIndex: Int = 0, // 1-based for display when running
  val currentFileName: String = "",
  val totalBatchesInCurrentFile: Int = 0,
  val currentBatchNumber: Int = 0, // 1-based
  val totalBlocksAllFiles: Int = 0,
  val completedBlocksAllFiles: Int = 0,
  val currentKeyNumber: Int = 0, // 1-based
  val currentKeyLabel: String = "",
  val currentAttempt: Int = 0,
  val maxAttempts: Int = DefaultConfig.MAX_ATTEMPTS_PER_KEY,
  val cooldownRemainingSeconds: Int = 0,
  val activePhaseMessage: String = "Ready to translate",
  val logs: List<ProcessingLogEntry> = emptyList(),
) {
  val isRunningOrCooldown: Boolean
    get() = status == TranslationStatus.RUNNING || status == TranslationStatus.COOLDOWN

  val overallProgressFraction: Float
    get() =
      if (totalBlocksAllFiles <= 0) {
        0f
      } else {
        (completedBlocksAllFiles.toFloat() / totalBlocksAllFiles.toFloat()).coerceIn(0f, 1f)
      }
}

data class TranslationJobRequest(
  val inputMode: InputMode,
  val title: String,
  val secondaryMeta: String, // Release Year for Movie, Season for Series
  val files: List<SelectedSubtitleFile>,
  val preSeededJobIds: Map<String, String> = emptyMap(),
)
