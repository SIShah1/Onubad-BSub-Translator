package com.example.data.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

@JsonClass(generateAdapter = true)
data class ChatCompletionRequest(
  @Json(name = "model") val model: String,
  @Json(name = "messages") val messages: List<ChatMessageDto>,
  @Json(name = "temperature") val temperature: Double? = null,
)

@JsonClass(generateAdapter = true)
data class ChatMessageDto(
  @Json(name = "role") val role: String,
  @Json(name = "content") val content: String,
)

@JsonClass(generateAdapter = true)
data class ChatCompletionResponse(
  @Json(name = "choices") val choices: List<ChatChoiceDto>? = null,
)

@JsonClass(generateAdapter = true)
data class ChatChoiceDto(
  @Json(name = "message") val message: ChatMessageDto? = null,
  @Json(name = "finish_reason") val finishReason: String? = null,
)

interface OpenAiCompatibleApiService {

  @POST
  suspend fun createChatCompletion(
    @Url url: String,
    @Header("Authorization") authorization: String,
    @Body request: ChatCompletionRequest,
  ): Response<ChatCompletionResponse>

  companion object {
    const val GROK_BASE_URL = "https://api.x.ai/"
    const val GROK_URL = "https://api.x.ai/v1/chat/completions"
    const val DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
  }
}
