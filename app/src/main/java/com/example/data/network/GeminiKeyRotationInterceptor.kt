package com.example.data.network

import com.example.data.preferences.SecureKeyStorage
import com.example.domain.model.ApiKeyEntry
import com.example.domain.model.ApiKeyStatus
import com.example.logging.ErrorCategory
import com.example.logging.TranslationLogger
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject

/**
 * Provides thread-safe access to the multi-key pool for [GeminiKeyRotationInterceptor].
 */
interface GeminiKeyPoolProvider {
  fun getKeysSnapshot(): List<ApiKeyEntry>

  fun getActiveKeyIndex(): Int

  fun setActiveKeyIndex(index: Int)

  fun markKeyStatus(keyId: String, status: ApiKeyStatus, errorReason: String?)
}

class DataStoreKeyPoolProvider(
  private val keyStorage: SecureKeyStorage,
) : GeminiKeyPoolProvider {
  override fun getKeysSnapshot(): List<ApiKeyEntry> = runBlocking { keyStorage.getKeysSnapshot() }

  override fun getActiveKeyIndex(): Int = runBlocking { keyStorage.getActiveKeyIndex() }

  override fun setActiveKeyIndex(index: Int) {
    runBlocking { keyStorage.setActiveKeyIndex(index) }
  }

  override fun markKeyStatus(keyId: String, status: ApiKeyStatus, errorReason: String?) {
    runBlocking { keyStorage.updateKeyStatus(keyId, status, errorReason) }
  }
}

/**
 * Custom OkHttp [Interceptor] that injects an active Gemini API key and automatically rotates
 * through the remaining configured keys when rate limits (HTTP 429), quota limits (HTTP 403/429),
 * invalid-key responses (HTTP 400/401), or transient server busy errors (HTTP 500/502/503/504)
 * occur during high-volume subtitle translation.
 */
class GeminiKeyRotationInterceptor(
  private val keyPoolProvider: GeminiKeyPoolProvider,
  private val fileNameForLogs: () -> String = { "subtitle.srt" },
) : Interceptor {

  constructor(
    keyStorage: SecureKeyStorage,
    fileNameForLogs: () -> String = { "subtitle.srt" },
  ) : this(keyPoolProvider = DataStoreKeyPoolProvider(keyStorage), fileNameForLogs = fileNameForLogs)

  private val lock = Any()

  override fun intercept(chain: Interceptor.Chain): Response {
    val originalRequest = chain.request()
    val explicitKey = originalRequest.url.queryParameter(QUERY_KEY_PARAM)
    val autoRotateHeader = originalRequest.header(HEADER_AUTO_ROTATE)?.equals("true", ignoreCase = true) == true

    // Clean internal control header before hitting network
    val baseRequest =
      originalRequest.newBuilder().removeHeader(HEADER_AUTO_ROTATE).build()

    // If caller supplied an explicit key and did not request interceptor-level rotation, pass through.
    if (!explicitKey.isNullOrBlank() && !autoRotateHeader) {
      return chain.proceed(baseRequest)
    }

    val snapshot = synchronized(lock) { keyPoolProvider.getKeysSnapshot() }
    if (snapshot.isEmpty()) {
      return noKeysErrorResponse(baseRequest)
    }

    val eligibleIndices =
      snapshot.indices.filter { idx -> snapshot[idx].status != ApiKeyStatus.INVALID }
    if (eligibleIndices.isEmpty()) {
      return invalidKeysErrorResponse(baseRequest)
    }

    val startCursor = synchronized(lock) { keyPoolProvider.getActiveKeyIndex().coerceIn(0, snapshot.lastIndex) }
    val orderedIndices =
      ArrayList<Int>(eligibleIndices.size).apply {
        for (offset in snapshot.indices) {
          val candidateIdx = (startCursor + offset) % snapshot.size
          if (snapshot[candidateIdx].status != ApiKeyStatus.INVALID) {
            add(candidateIdx)
          }
        }
      }

    var lastResponse: Response? = null
    for ((attemptPos, keyIdx) in orderedIndices.withIndex()) {
      val entry = snapshot[keyIdx]
      val keyedUrl =
        baseRequest.url
          .newBuilder()
          .setQueryParameter(QUERY_KEY_PARAM, entry.key)
          .build()
      val requestWithKey = baseRequest.newBuilder().url(keyedUrl).build()

      synchronized(lock) { keyPoolProvider.setActiveKeyIndex(keyIdx) }

      val response = chain.proceed(requestWithKey)
      if (response.isSuccessful) {
        if (entry.status != ApiKeyStatus.ACTIVE) {
          synchronized(lock) {
            keyPoolProvider.markKeyStatus(entry.id, ApiKeyStatus.ACTIVE, null)
          }
        }
        return response
      }

      val errorBody = runCatching { response.peekBody(MAX_ERROR_PEEK_BYTES).string() }.getOrDefault("")
      val classification = classifyStatus(response.code, errorBody)

      if (classification == FailureKind.NON_ROTATABLE || attemptPos == orderedIndices.lastIndex) {
        updateKeyFailureInPool(entry, keyIdx, snapshot, classification, response.code, errorBody)
        return response
      }

      // Close failed response body before rotating and retrying with the next key
      lastResponse = response
      val failureCode = response.code
      response.close()

      updateKeyFailureInPool(entry, keyIdx, snapshot, classification, failureCode, errorBody)
      val nextIdx = orderedIndices[attemptPos + 1]
      synchronized(lock) { keyPoolProvider.setActiveKeyIndex(nextIdx) }
    }

    return lastResponse ?: noKeysErrorResponse(baseRequest)
  }

  private fun updateKeyFailureInPool(
    entry: ApiKeyEntry,
    keyIdx: Int,
    snapshot: List<ApiKeyEntry>,
    kind: FailureKind,
    httpCode: Int,
    errorBody: String,
  ) {
    val keyLabel = "Key #${keyIdx + 1} (${entry.label})"
    val reason = extractErrorMessage(errorBody).ifBlank { "HTTP $httpCode during Gemini request" }
    when (kind) {
      FailureKind.INVALID_KEY -> {
        TranslationLogger.logApiFailure(
          category = ErrorCategory.API_INVALID_KEY,
          keyLabel = keyLabel,
          httpCode = httpCode,
          reason = reason,
          fileName = fileNameForLogs(),
          batchNumber = null,
        )
        synchronized(lock) {
          keyPoolProvider.markKeyStatus(entry.id, ApiKeyStatus.INVALID, reason)
        }
      }
      FailureKind.QUOTA_OR_RATE_LIMIT -> {
        TranslationLogger.logApiFailure(
          category = ErrorCategory.API_QUOTA_EXHAUSTED,
          keyLabel = keyLabel,
          httpCode = httpCode,
          reason = reason,
          fileName = fileNameForLogs(),
          batchNumber = null,
        )
        synchronized(lock) {
          keyPoolProvider.markKeyStatus(entry.id, ApiKeyStatus.QUOTA_EXHAUSTED, reason)
        }
      }
      FailureKind.TRANSIENT_SERVER -> {
        TranslationLogger.logApiFailure(
          category = ErrorCategory.API_RETRYABLE_ERROR,
          keyLabel = keyLabel,
          httpCode = httpCode,
          reason = reason,
          fileName = fileNameForLogs(),
          batchNumber = null,
        )
        synchronized(lock) {
          keyPoolProvider.markKeyStatus(entry.id, ApiKeyStatus.COOLDOWN, reason)
        }
      }
      FailureKind.NON_ROTATABLE -> Unit
    }

    // Advance pointer past the failed key when keys remain
    if (snapshot.size > 1) {
      val nextCursor = (keyIdx + 1) % snapshot.size
      synchronized(lock) { keyPoolProvider.setActiveKeyIndex(nextCursor) }
    }
  }

  private enum class FailureKind {
    INVALID_KEY,
    QUOTA_OR_RATE_LIMIT,
    TRANSIENT_SERVER,
    NON_ROTATABLE,
  }

  private fun classifyStatus(code: Int, rawBody: String): FailureKind {
    val lower = rawBody.lowercase()
    if (
      code == 401 ||
        lower.contains("api_key_invalid") ||
        lower.contains("api key not valid") ||
        lower.contains("invalid api key") ||
        lower.contains("permission_denied") ||
        (code == 400 && lower.contains("api key"))
    ) {
      return FailureKind.INVALID_KEY
    }
    if (
      code == 429 ||
        (code == 403 &&
          (lower.contains("quota") || lower.contains("billing") || lower.contains("limit")))
    ) {
      return FailureKind.QUOTA_OR_RATE_LIMIT
    }
    if (code in setOf(408, 500, 502, 503, 504)) {
      return FailureKind.TRANSIENT_SERVER
    }
    return FailureKind.NON_ROTATABLE
  }

  private fun extractErrorMessage(rawBody: String): String {
    return try {
      JSONObject(rawBody).optJSONObject("error")?.optString("message").orEmpty()
    } catch (_: Exception) {
      rawBody.take(120)
    }
  }

  private fun noKeysErrorResponse(request: okhttp3.Request): Response {
    val bodyJson =
      """{"error":{"code":401,"status":"UNAUTHENTICATED","message":"No Gemini API keys configured in DataStore"}}"""
    return Response.Builder()
      .request(request)
      .protocol(Protocol.HTTP_1_1)
      .code(401)
      .message("Unauthorized")
      .body(bodyJson.toResponseBody("application/json".toMediaType()))
      .build()
  }

  private fun invalidKeysErrorResponse(request: okhttp3.Request): Response {
    val bodyJson =
      """{"error":{"code":401,"status":"API_KEY_INVALID","message":"All configured Gemini API keys are marked invalid"}}"""
    return Response.Builder()
      .request(request)
      .protocol(Protocol.HTTP_1_1)
      .code(401)
      .message("Unauthorized")
      .body(bodyJson.toResponseBody("application/json".toMediaType()))
      .build()
  }

  companion object {
    const val HEADER_AUTO_ROTATE = "X-BSub-Auto-Rotate"
    private const val QUERY_KEY_PARAM = "key"
    private const val MAX_ERROR_PEEK_BYTES = 4096L
  }
}
