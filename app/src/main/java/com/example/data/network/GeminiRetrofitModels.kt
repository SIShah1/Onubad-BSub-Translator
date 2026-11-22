package com.example.data.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GeminiGenerateContentRequest(
  @Json(name = "systemInstruction") val systemInstruction: GeminiContent? = null,
  @Json(name = "contents") val contents: List<GeminiContent>,
  @Json(name = "generationConfig") val generationConfig: GeminiGenerationConfig? = null,
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
  @Json(name = "role") val role: String? = null,
  @Json(name = "parts") val parts: List<GeminiPart>,
)

@JsonClass(generateAdapter = true)
data class GeminiPart(
  @Json(name = "text") val text: String? = null,
  @Json(name = "thought") val thought: Boolean? = null,
)

@JsonClass(generateAdapter = true)
data class GeminiGenerationConfig(
  @Json(name = "temperature") val temperature: Double? = null,
  @Json(name = "thinkingConfig") val thinkingConfig: GeminiThinkingConfig? = null,
)

@JsonClass(generateAdapter = true)
data class GeminiThinkingConfig(
  @Json(name = "thinkingBudget") val thinkingBudget: Int,
)

@JsonClass(generateAdapter = true)
data class GeminiGenerateContentResponse(
  @Json(name = "candidates") val candidates: List<GeminiCandidate>? = null,
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(
  @Json(name = "content") val content: GeminiContent? = null,
  @Json(name = "finishReason") val finishReason: String? = null,
)
