package com.example.data.network

import com.example.data.preferences.SecureKeyStorage
import com.example.domain.model.FewShotExample
import com.example.domain.model.InputMode
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

sealed class GeminiCallResult {
  data class Success(val translatedText: String) : GeminiCallResult()

  data class RetryableError(val reason: String, val httpCode: Int? = null) : GeminiCallResult()

  data class RateLimited(val reason: String) : GeminiCallResult()

  data class QuotaExhausted(val reason: String) : GeminiCallResult()

  data class InvalidKey(val reason: String) : GeminiCallResult()

  data class FatalError(val reason: String, val httpCode: Int) : GeminiCallResult()
}

class GeminiApiClient(
  customService: GeminiApiService? = null,
  keyStorage: SecureKeyStorage? = null,
  keyRotationInterceptor: Interceptor? = keyStorage?.let { GeminiKeyRotationInterceptor(it) },
  extraInterceptors: List<Interceptor> = emptyList(),
) {

  private val okHttpClient: OkHttpClient =
    OkHttpClient.Builder()
      .apply {
        if (keyRotationInterceptor != null) {
          addInterceptor(keyRotationInterceptor)
        }
        extraInterceptors.forEach { addInterceptor(it) }
      }
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(240, TimeUnit.SECONDS)
      .writeTimeout(60, TimeUnit.SECONDS)
      .callTimeout(260, TimeUnit.SECONDS)
      .build()

  private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

  private val apiService: GeminiApiService =
    customService
      ?: Retrofit.Builder()
        .baseUrl(GeminiApiService.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(GeminiApiService::class.java)

  suspend fun translateBatch(
    apiKey: String,
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
  ): GeminiCallResult =
    withContext(Dispatchers.IO) {
      val cleanModel = model.trim().removePrefix("models/")
      val requestBody =
        buildRequestBody(
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
          thinkingBudget = thinkingBudget,
        )

      try {
        val response =
          apiService.generateContent(
            model = cleanModel,
            apiKey = apiKey,
            request = requestBody,
          )
        if (response.isSuccessful) {
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
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (e: SocketTimeoutException) {
        GeminiCallResult.RetryableError("Network timeout: ${e.message ?: "Request timed out"}")
      } catch (e: UnknownHostException) {
        GeminiCallResult.RetryableError("Network error: Unable to reach Gemini API")
      } catch (e: IOException) {
        GeminiCallResult.RetryableError("Network error: ${e.message ?: "Connection failed"}")
      } catch (e: Exception) {
        GeminiCallResult.RetryableError("Unexpected error: ${e.message ?: "Unknown failure"}")
      }
    }

  suspend fun translateBatchWithInterceptorRotation(
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
  ): GeminiCallResult =
    withContext(Dispatchers.IO) {
      val cleanModel = model.trim().removePrefix("models/")
      val requestBody =
        buildRequestBody(
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
          thinkingBudget = thinkingBudget,
        )
      executeGeminiCall {
        apiService.generateContentWithKeyRotation(
          model = cleanModel,
          request = requestBody,
        )
      }
    }

  private suspend inline fun executeGeminiCall(
    call: () -> Response<GeminiGenerateContentResponse>
  ): GeminiCallResult {
    return try {
      val response = call()
      if (response.isSuccessful) {
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
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (e: SocketTimeoutException) {
      GeminiCallResult.RetryableError("Network timeout: ${e.message ?: "Request timed out"}")
    } catch (e: UnknownHostException) {
      GeminiCallResult.RetryableError("Network error: Unable to reach Gemini API")
    } catch (e: IOException) {
      GeminiCallResult.RetryableError("Network error: ${e.message ?: "Connection failed"}")
    } catch (e: Exception) {
      GeminiCallResult.RetryableError("Unexpected error: ${e.message ?: "Unknown failure"}")
    }
  }

  internal fun buildRequestBody(
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

    // Add configured few-shot examples as alternating user/model demonstration turns
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

    // Final user turn with the actual SRT batch to translate
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

    val generationConfig =
      GeminiGenerationConfig(
        temperature = temperature.toDouble(),
        thinkingConfig = if (thinkingBudget > 0) GeminiThinkingConfig(thinkingBudget) else null,
      )

    return GeminiGenerateContentRequest(
      systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = contextHeader))),
      contents = contentsList,
      generationConfig = generationConfig,
    )
  }

  private fun extractCandidateText(response: GeminiGenerateContentResponse?): String? {
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
    return sb.toString().trim().ifBlank { null }
  }

  internal fun classifyHttpFailure(code: Int, rawBody: String): GeminiCallResult {
    val parsedMsg =
      try {
        JSONObject(rawBody).optJSONObject("error")?.optString("message").orEmpty()
      } catch (_: Exception) {
        ""
      }
    val parsedStatus =
      try {
        JSONObject(rawBody).optJSONObject("error")?.optString("status").orEmpty()
      } catch (_: Exception) {
        ""
      }

    val combinedLower = "$parsedStatus $parsedMsg $rawBody".lowercase()

    // Invalid / unauthorized API key
    if (
      code == 401 ||
        combinedLower.contains("api_key_invalid") ||
        combinedLower.contains("api key not valid") ||
        combinedLower.contains("invalid api key") ||
        combinedLower.contains("permission_denied") ||
        (code == 400 && combinedLower.contains("api key"))
    ) {
      return GeminiCallResult.InvalidKey(
        parsedMsg.ifBlank { "Invalid API key (HTTP $code)" }
      )
    }

    // Non-retryable client/model configuration errors (avoid burning retries or 30m cooldown loops)
    if (code == 404 || (code == 400 && !combinedLower.contains("api key"))) {
      val cleanReason =
        parsedMsg.ifBlank {
          if (code == 404) "Model not found (HTTP 404)" else "Invalid request payload (HTTP 400)"
        }
      return GeminiCallResult.FatalError(reason = cleanReason, httpCode = code)
    }

    // Distinguish temporary per-minute RPM/TPM 429s from daily quota exhaustion
    val isPerMinuteRateLimit =
      combinedLower.contains("perminute") ||
        combinedLower.contains("per_minute") ||
        combinedLower.contains("per minute") ||
        combinedLower.contains("tokenspermin") ||
        combinedLower.contains("requestspermin") ||
        (combinedLower.contains("rate limit") && !combinedLower.contains("perday") && !combinedLower.contains("daily"))

    val isDailyQuota =
      combinedLower.contains("perday") ||
        combinedLower.contains("per_day") ||
        combinedLower.contains("daily") ||
        combinedLower.contains("requestsperday") ||
        (!isPerMinuteRateLimit &&
          (combinedLower.contains("quota") ||
            combinedLower.contains("resource_exhausted") ||
            combinedLower.contains("limit")))

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

    // Server busy / transient 5xx / timeout
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
