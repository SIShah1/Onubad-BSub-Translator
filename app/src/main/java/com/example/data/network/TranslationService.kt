package com.example.data.network

import com.example.domain.model.ApiProvider
import com.example.domain.model.FewShotExample
import com.example.domain.model.InputMode
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.Response

data class TranslationRequest(
  val model: String,
  val temperature: Float,
  val thinkingBudget: Int,
  val systemPrompt: String,
  val fewShotExamples: List<FewShotExample>,
  val inputMode: InputMode,
  val title: String,
  val secondaryMeta: String,
  val batchSrtPayload: String,
  val batchIndex: Int,
  val totalBatches: Int,
  val fileName: String,
  val surroundingContext: String = "",
)

/**
 * Polymorphic subtitle translation engine contract implemented per provider
 * (Gemini, Grok, DeepSeek) with provider-specific Retrofit configurations.
 */
interface TranslationService {
  val provider: ApiProvider
  val baseUrl: String

  suspend fun translateBatch(
    apiKey: String,
    request: TranslationRequest,
  ): GeminiCallResult
}

fun interface TranslationServiceProvider {
  fun serviceFor(provider: ApiProvider): TranslationService
}

class GeminiTranslationService(
  private val apiService: GeminiApiService,
  override val baseUrl: String = GeminiApiService.BASE_URL,
) : TranslationService {

  override val provider: ApiProvider = ApiProvider.GEMINI

  override suspend fun translateBatch(
    apiKey: String,
    request: TranslationRequest,
  ): GeminiCallResult =
    withContext(Dispatchers.IO) {
      val cleanModel = request.model.trim().removePrefix("models/")
      val requestBody =
        TranslationProtocolMapper.buildGeminiRequestBody(
          systemPrompt = request.systemPrompt,
          fewShotExamples = request.fewShotExamples,
          inputMode = request.inputMode,
          title = request.title,
          secondaryMeta = request.secondaryMeta,
          batchSrtPayload = request.batchSrtPayload,
          batchIndex = request.batchIndex,
          totalBatches = request.totalBatches,
          fileName = request.fileName,
          temperature = request.temperature,
          thinkingBudget = request.thinkingBudget,
          surroundingContext = request.surroundingContext,
          model = cleanModel,
        )
      TranslationProtocolMapper.executeCall(providerName = provider.displayName) {
        val response =
          apiService.generateContent(
            model = cleanModel,
            apiKey = apiKey,
            request = requestBody,
          )
        val firstResult = TranslationProtocolMapper.handleGeminiResponse(response)
        if (
          firstResult is GeminiCallResult.FatalError &&
            firstResult.httpCode == 400 &&
            requestBody.generationConfig?.thinkingConfig != null &&
            request.thinkingBudget <= 0 &&
            firstResult.reason.contains("thinking", ignoreCase = true)
        ) {
          val fallbackBody =
            requestBody.copy(
              generationConfig = requestBody.generationConfig.copy(thinkingConfig = null)
            )
          val retryResponse =
            apiService.generateContent(
              model = cleanModel,
              apiKey = apiKey,
              request = fallbackBody,
            )
          TranslationProtocolMapper.handleGeminiResponse(retryResponse)
        } else {
          firstResult
        }
      }
    }
}

class GrokTranslationService(
  private val apiService: GrokApiService,
  override val baseUrl: String = GrokApiService.BASE_URL,
) : TranslationService {

  override val provider: ApiProvider = ApiProvider.GROK

  override suspend fun translateBatch(
    apiKey: String,
    request: TranslationRequest,
  ): GeminiCallResult =
    withContext(Dispatchers.IO) {
      val chatRequest =
        TranslationProtocolMapper.buildChatCompletionRequest(
          model = request.model,
          systemPrompt = request.systemPrompt,
          fewShotExamples = request.fewShotExamples,
          inputMode = request.inputMode,
          title = request.title,
          secondaryMeta = request.secondaryMeta,
          batchSrtPayload = request.batchSrtPayload,
          batchIndex = request.batchIndex,
          totalBatches = request.totalBatches,
          fileName = request.fileName,
          temperature = request.temperature,
          surroundingContext = request.surroundingContext,
        )
      TranslationProtocolMapper.executeCall(providerName = provider.displayName) {
        val response =
          apiService.createChatCompletion(
            authorization = "Bearer ${apiKey.trim()}",
            request = chatRequest,
          )
        TranslationProtocolMapper.handleChatResponse(provider, response)
      }
    }
}

class DeepSeekTranslationService(
  private val apiService: DeepSeekApiService,
  override val baseUrl: String = DeepSeekApiService.BASE_URL,
) : TranslationService {

  override val provider: ApiProvider = ApiProvider.DEEPSEEK

  override suspend fun translateBatch(
    apiKey: String,
    request: TranslationRequest,
  ): GeminiCallResult =
    withContext(Dispatchers.IO) {
      val chatRequest =
        TranslationProtocolMapper.buildChatCompletionRequest(
          model = request.model,
          systemPrompt = request.systemPrompt,
          fewShotExamples = request.fewShotExamples,
          inputMode = request.inputMode,
          title = request.title,
          secondaryMeta = request.secondaryMeta,
          batchSrtPayload = request.batchSrtPayload,
          batchIndex = request.batchIndex,
          totalBatches = request.totalBatches,
          fileName = request.fileName,
          temperature = request.temperature,
          surroundingContext = request.surroundingContext,
        )
      TranslationProtocolMapper.executeCall(providerName = provider.displayName) {
        val response =
          apiService.createChatCompletion(
            authorization = "Bearer ${apiKey.trim()}",
            request = chatRequest,
          )
        TranslationProtocolMapper.handleChatResponse(provider, response)
      }
    }
}

internal object TranslationProtocolMapper {

  suspend inline fun executeCall(
    providerName: String,
    block: () -> GeminiCallResult,
  ): GeminiCallResult {
    return try {
      block()
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (e: SocketTimeoutException) {
      GeminiCallResult.RetryableError("Network timeout: ${e.message ?: "Request timed out"}")
    } catch (e: UnknownHostException) {
      GeminiCallResult.RetryableError("Network error: Unable to reach $providerName API")
    } catch (e: IOException) {
      GeminiCallResult.RetryableError("Network error: ${e.message ?: "Connection failed"}")
    } catch (e: Exception) {
      GeminiCallResult.RetryableError("Unexpected error: ${e.message ?: "Unknown failure"}")
    }
  }

  fun handleGeminiResponse(
    response: Response<GeminiGenerateContentResponse>,
  ): GeminiCallResult {
    return if (response.isSuccessful) {
      val extracted = extractCandidateText(response.body())
      if (extracted.isNullOrBlank()) {
        GeminiCallResult.RetryableError(
          reason = "Empty response returned by Gemini API",
          httpCode = response.code(),
        )
      } else {
        GeminiCallResult.Success(extracted)
      }
    } else {
      val errorPayload = response.errorBody()?.string().orEmpty()
      classifyHttpFailure(response.code(), errorPayload)
    }
  }

  fun handleChatResponse(
    provider: ApiProvider,
    response: Response<ChatCompletionResponse>,
  ): GeminiCallResult {
    return if (response.isSuccessful) {
      val extracted = extractChatCompletionText(response.body())
      if (extracted.isNullOrBlank()) {
        GeminiCallResult.RetryableError(
          reason = "Empty response returned by ${provider.displayName} API",
          httpCode = response.code(),
        )
      } else {
        GeminiCallResult.Success(extracted)
      }
    } else {
      val errorPayload = response.errorBody()?.string().orEmpty()
      classifyHttpFailure(response.code(), errorPayload)
    }
  }

  fun buildGeminiRequestBody(
    systemPrompt: String,
    fewShotExamples: List<FewShotExample>,
    inputMode: InputMode,
    title: String,
    secondaryMeta: String,
    batchSrtPayload: String,
    batchIndex: Int,
    totalBatches: Int,
    fileName: String,
    temperature: Float,
    thinkingBudget: Int,
    surroundingContext: String = "",
    model: String = "",
  ): GeminiGenerateContentRequest {
    val contextHeader =
      buildString {
        appendLine(systemPrompt.trim())
        appendLine()
        appendLine("MEDIA CONTEXT:")
        when (inputMode) {
          InputMode.MOVIE -> {
            appendLine("- Type: Movie")
            if (title.isNotBlank()) appendLine("- Movie Name: ${title.trim()}")
            if (secondaryMeta.isNotBlank()) appendLine("- Release Year: ${secondaryMeta.trim()}")
          }
          InputMode.SERIES -> {
            appendLine("- Type: TV Series")
            if (title.isNotBlank()) appendLine("- Series Name: ${title.trim()}")
            if (secondaryMeta.isNotBlank()) appendLine("- Season: ${secondaryMeta.trim()}")
          }
        }
        appendLine("- Source File: $fileName (Batch $batchIndex of $totalBatches)")
      }.trim()

    val contentsList = ArrayList<GeminiContent>()

    fewShotExamples
      .filter { it.englishText.isNotBlank() && it.bengaliTranslation.isNotBlank() }
      .forEachIndexed { idx, ex ->
        val notePrefix = if (ex.note.isNotBlank()) "[Style note: ${ex.note.trim()}]\n" else ""
        val userTurnText =
          "Example ${idx + 1} - Translate into Bengali following the exact style and SRT structure:\n$notePrefix${ex.englishText.trim()}"
        contentsList.add(
          GeminiContent(
            role = "user",
            parts = listOf(GeminiPart(text = userTurnText)),
          )
        )
        contentsList.add(
          GeminiContent(
            role = "model",
            parts = listOf(GeminiPart(text = ex.bengaliTranslation.trim())),
          )
        )
      }

    val cleanSurroundingContext = surroundingContext.trim()
    if (cleanSurroundingContext.isNotEmpty()) {
      val contextTurnText =
        """
SURROUNDING DIALOGUE CONTEXT (REFERENCE ONLY — DO NOT TRANSLATE OR OUTPUT THESE LINES):
Use these surrounding lines strictly to preserve dialogue coherence, pronoun formality (আপনি / তুমি / তুই), and consistent character voices.

$cleanSurroundingContext
        """.trimIndent()
      contentsList.add(
        GeminiContent(
          role = "user",
          parts = listOf(GeminiPart(text = contextTurnText)),
        )
      )
      contentsList.add(
        GeminiContent(
          role = "model",
          parts =
            listOf(
              GeminiPart(
                text =
                  "Understood. I will use the surrounding dialogue only for context and character-voice consistency, and I will translate and output ONLY the exact SRT blocks in the next message."
              )
            ),
        )
      )
    }

    val batchInstruction =
      """
Translate every subtitle block below from English to Bengali.
Return ONLY the translated .srt blocks with identical serial numbers and identical timestamps.

$batchSrtPayload
      """.trimIndent()

    contentsList.add(
      GeminiContent(
        role = "user",
        parts = listOf(GeminiPart(text = batchInstruction)),
      )
    )

    val resolvedThinkingConfig =
      when {
        thinkingBudget > 0 -> GeminiThinkingConfig(thinkingBudget)
        thinkingBudget == 0 && !model.contains("pro", ignoreCase = true) ->
          GeminiThinkingConfig(0)
        else -> null
      }

    val generationConfig =
      GeminiGenerationConfig(
        temperature = temperature.toDouble(),
        thinkingConfig = resolvedThinkingConfig,
      )

    return GeminiGenerateContentRequest(
      systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = contextHeader))),
      contents = contentsList,
      generationConfig = generationConfig,
    )
  }

  fun buildChatCompletionRequest(
    model: String,
    systemPrompt: String,
    fewShotExamples: List<FewShotExample>,
    inputMode: InputMode,
    title: String,
    secondaryMeta: String,
    batchSrtPayload: String,
    batchIndex: Int,
    totalBatches: Int,
    fileName: String,
    temperature: Float,
    surroundingContext: String = "",
  ): ChatCompletionRequest {
    val geminiReq =
      buildGeminiRequestBody(
        systemPrompt = systemPrompt,
        fewShotExamples = fewShotExamples,
        inputMode = inputMode,
        title = title,
        secondaryMeta = secondaryMeta,
        batchSrtPayload = batchSrtPayload,
        batchIndex = batchIndex,
        totalBatches = totalBatches,
        fileName = fileName,
        temperature = temperature,
        thinkingBudget = 0,
        surroundingContext = surroundingContext,
      )

    val messages = ArrayList<ChatMessageDto>()
    val systemText =
      geminiReq.systemInstruction?.parts?.joinToString("\n") { it.text.orEmpty() }?.trim().orEmpty()
    if (systemText.isNotEmpty()) {
      messages.add(ChatMessageDto(role = "system", content = systemText))
    }

    geminiReq.contents.forEach { content ->
      val role = if (content.role == "model") "assistant" else (content.role ?: "user")
      val text = content.parts.joinToString("\n") { it.text.orEmpty() }.trim()
      if (text.isNotEmpty()) {
        messages.add(ChatMessageDto(role = role, content = text))
      }
    }

    val cleanModel = model.trim()
    val resolvedMaxTokens =
      when {
        cleanModel.contains("deepseek-chat", ignoreCase = true) -> 8192
        cleanModel.contains("deepseek", ignoreCase = true) -> 16384
        cleanModel.contains("grok", ignoreCase = true) -> 16384
        else -> 8192
      }

    return ChatCompletionRequest(
      model = cleanModel,
      messages = messages,
      temperature = temperature.toDouble(),
      maxTokens = resolvedMaxTokens,
    )
  }

  private val THINK_BLOCK_REGEX = Regex("""(?is)<think>.*?</think>""")
  private val UNCLOSED_THINK_REGEX = Regex("""(?is)<think>.*$""")
  private val ZERO_QUOTA_LIMIT_REGEX = Regex("""\blimit\s*:\s*0\b""")

  private fun stripReasoningBlocks(raw: String): String =
    raw
      .replace(THINK_BLOCK_REGEX, "")
      .replace(UNCLOSED_THINK_REGEX, "")
      .trim()

  fun extractChatCompletionText(response: ChatCompletionResponse?): String? {
    val raw = response?.choices?.firstOrNull()?.message?.content?.trim().orEmpty()
    if (raw.isBlank()) return null
    return stripReasoningBlocks(raw).ifBlank { null }
  }

  fun extractCandidateText(response: GeminiGenerateContentResponse?): String? {
    val parts = response?.candidates?.firstOrNull()?.content?.parts ?: return null
    val sb = StringBuilder()
    for (part in parts) {
      if (part.thought == true) continue
      val text = part.text.orEmpty()
      if (text.isNotEmpty()) {
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(text)
      }
    }
    return stripReasoningBlocks(sb.toString()).ifBlank { null }
  }

  fun classifyHttpFailure(code: Int, rawBody: String): GeminiCallResult {
    val parsedMsg =
      try {
        JSONObject(rawBody).optJSONObject("error")?.optString("message").orEmpty()
      } catch (_: Exception) {
        ""
      }
    val parsedStatus =
      try {
        val err = JSONObject(rawBody).optJSONObject("error")
        err?.optString("status").orEmpty().ifBlank { err?.optString("type").orEmpty() }
      } catch (_: Exception) {
        ""
      }

    val combinedLower = "$parsedStatus $parsedMsg $rawBody".lowercase()

    if (
      code == 401 ||
        combinedLower.contains("api_key_invalid") ||
        combinedLower.contains("invalid_api_key") ||
        combinedLower.contains("api key not valid") ||
        combinedLower.contains("invalid api key") ||
        combinedLower.contains("incorrect api key") ||
        combinedLower.contains("authentication_error") ||
        combinedLower.contains("permission_denied") ||
        (code == 400 && combinedLower.contains("api key"))
    ) {
      return GeminiCallResult.InvalidKey(
        parsedMsg.ifBlank { "Invalid API key (HTTP $code)" }
      )
    }

    if (
      code == 402 ||
        combinedLower.contains("insufficient balance") ||
        combinedLower.contains("insufficient_quota")
    ) {
      return GeminiCallResult.QuotaExhausted(
        parsedMsg.ifBlank { "API account balance or quota exhausted (HTTP $code)" }
      )
    }

    if (code == 404 || (code == 400 && !combinedLower.contains("api key"))) {
      val cleanReason =
        parsedMsg.ifBlank {
          if (code == 404) "Model not found (HTTP 404)" else "Invalid request payload (HTTP 400)"
        }
      return GeminiCallResult.FatalError(reason = cleanReason, httpCode = code)
    }

    val isPerMinuteRateLimit =
      combinedLower.contains("perminute") ||
        combinedLower.contains("per_minute") ||
        combinedLower.contains("per minute") ||
        combinedLower.contains("tokenspermin") ||
        combinedLower.contains("requestspermin") ||
        combinedLower.contains("too many requests") ||
        (combinedLower.contains("rate limit") && !combinedLower.contains("perday") && !combinedLower.contains("daily"))

    val isZeroQuotaLimit =
      ZERO_QUOTA_LIMIT_REGEX.containsMatchIn(combinedLower) ||
        combinedLower.contains("\"limit\":\"0\"")

    val isDailyQuota =
      isZeroQuotaLimit ||
        combinedLower.contains("perday") ||
        combinedLower.contains("per_day") ||
        combinedLower.contains("per day") ||
        combinedLower.contains("daily") ||
        combinedLower.contains("requestsperday") ||
        combinedLower.contains("insufficient_quota") ||
        combinedLower.contains("insufficient balance") ||
        combinedLower.contains("exceeded your current quota") ||
        (!isPerMinuteRateLimit &&
          (combinedLower.contains("billing") || combinedLower.contains("credit")))

    if (code == 429) {
      return if (isDailyQuota) {
        GeminiCallResult.QuotaExhausted(
          parsedMsg.ifBlank { "Daily API quota exhausted (HTTP 429)" }
        )
      } else {
        GeminiCallResult.RateLimited(
          parsedMsg.ifBlank { "Rate limit exceeded (HTTP 429)" }
        )
      }
    }

    if (code == 403) {
      return if (
        combinedLower.contains("quota") ||
          combinedLower.contains("billing") ||
          combinedLower.contains("credit") ||
          combinedLower.contains("balance") ||
          combinedLower.contains("limit")
      ) {
        GeminiCallResult.QuotaExhausted(
          parsedMsg.ifBlank { "API quota or billing limit exceeded (HTTP 403)" }
        )
      } else {
        GeminiCallResult.FatalError(
          reason = parsedMsg.ifBlank { "Access forbidden (HTTP 403)" },
          httpCode = code,
        )
      }
    }

    return when (code) {
      500, 502, 503, 504, 408 ->
        GeminiCallResult.RetryableError(
          "Server busy (HTTP $code): ${parsedMsg.take(120)}".trim(),
          code,
        )
      else ->
        GeminiCallResult.RetryableError(
          "API error (HTTP $code): ${parsedMsg.ifBlank { rawBody.take(120) }}",
          code,
        )
    }
  }
}
