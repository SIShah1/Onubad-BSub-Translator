package com.example.data.network

import com.example.data.preferences.SecureKeyStorage
import com.example.domain.model.ApiProvider
import com.example.domain.model.FewShotExample
import com.example.domain.model.InputMode
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
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

/**
 * Multi-provider network client implementing [TranslationServiceProvider] with independent
 * Retrofit configurations for Gemini, Grok, and DeepSeek.
 */
class GeminiApiClient(
  customService: GeminiApiService? = null,
  keyStorage: SecureKeyStorage? = null,
  keyRotationInterceptor: Interceptor? = keyStorage?.let { GeminiKeyRotationInterceptor(it) },
  extraInterceptors: List<Interceptor> = emptyList(),
  customChatService: OpenAiCompatibleApiService? = null,
  customGrokService: GrokApiService? = null,
  customDeepSeekService: DeepSeekApiService? = null,
) : TranslationServiceProvider {

  private val geminiOkHttpClient: OkHttpClient =
    OkHttpClient.Builder()
      .apply {
        if (keyRotationInterceptor != null) {
          addInterceptor(keyRotationInterceptor)
        }
        extraInterceptors.forEach { addInterceptor(it) }
      }
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(90, TimeUnit.SECONDS)
      .writeTimeout(30, TimeUnit.SECONDS)
      .callTimeout(105, TimeUnit.SECONDS)
      .build()

  private val providerOkHttpClient: OkHttpClient =
    OkHttpClient.Builder()
      .apply {
        extraInterceptors.forEach { addInterceptor(it) }
      }
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(90, TimeUnit.SECONDS)
      .writeTimeout(30, TimeUnit.SECONDS)
      .callTimeout(105, TimeUnit.SECONDS)
      .build()

  private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
  private val moshiConverter: MoshiConverterFactory = MoshiConverterFactory.create(moshi)

  private fun buildRetrofit(
    baseUrl: String,
    client: OkHttpClient,
  ): Retrofit =
    Retrofit.Builder()
      .baseUrl(baseUrl)
      .client(client)
      .addConverterFactory(moshiConverter)
      .build()

  private val apiService: GeminiApiService =
    customService
      ?: buildRetrofit(GeminiApiService.BASE_URL, geminiOkHttpClient)
        .create(GeminiApiService::class.java)

  private val grokApiService: GrokApiService =
    customGrokService
      ?: if (customChatService != null) {
        object : GrokApiService {
          override suspend fun createChatCompletion(
            authorization: String,
            request: ChatCompletionRequest,
          ) =
            customChatService.createChatCompletion(
              url = OpenAiCompatibleApiService.GROK_URL,
              authorization = authorization,
              request = request,
            )
        }
      } else {
        buildRetrofit(GrokApiService.BASE_URL, providerOkHttpClient)
          .create(GrokApiService::class.java)
      }

  private val deepSeekApiService: DeepSeekApiService =
    customDeepSeekService
      ?: if (customChatService != null) {
        object : DeepSeekApiService {
          override suspend fun createChatCompletion(
            authorization: String,
            request: ChatCompletionRequest,
          ) =
            customChatService.createChatCompletion(
              url = OpenAiCompatibleApiService.DEEPSEEK_URL,
              authorization = authorization,
              request = request,
            )
        }
      } else {
        buildRetrofit(DeepSeekApiService.BASE_URL, providerOkHttpClient)
          .create(DeepSeekApiService::class.java)
      }

  private val geminiTranslationService: TranslationService = GeminiTranslationService(apiService)
  private val grokTranslationService: TranslationService = GrokTranslationService(grokApiService)
  private val deepSeekTranslationService: TranslationService =
    DeepSeekTranslationService(deepSeekApiService)

  override fun serviceFor(provider: ApiProvider): TranslationService =
    when (provider) {
      ApiProvider.GEMINI -> geminiTranslationService
      ApiProvider.GROK -> grokTranslationService
      ApiProvider.DEEPSEEK -> deepSeekTranslationService
    }

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
    surroundingContext: String = "",
    provider: ApiProvider = ApiProvider.GEMINI,
  ): GeminiCallResult =
    serviceFor(provider)
      .translateBatch(
        apiKey = apiKey,
        request =
          TranslationRequest(
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
          ),
      )

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
    surroundingContext: String = "",
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
          surroundingContext = surroundingContext,
        )
      TranslationProtocolMapper.executeCall(providerName = ApiProvider.GEMINI.displayName) {
        val response =
          apiService.generateContentWithKeyRotation(
            model = cleanModel,
            request = requestBody,
          )
        TranslationProtocolMapper.handleGeminiResponse(response)
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
    surroundingContext: String = "",
    model: String = "",
  ): GeminiGenerateContentRequest =
    TranslationProtocolMapper.buildGeminiRequestBody(
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
      surroundingContext = surroundingContext,
      model = model,
    )

  internal fun buildChatCompletionRequest(
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
  ): ChatCompletionRequest =
    TranslationProtocolMapper.buildChatCompletionRequest(
      model = model,
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
      surroundingContext = surroundingContext,
    )

  internal fun classifyHttpFailure(code: Int, rawBody: String): GeminiCallResult =
    TranslationProtocolMapper.classifyHttpFailure(code, rawBody)
}
