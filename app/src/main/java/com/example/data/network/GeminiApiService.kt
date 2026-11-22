package com.example.data.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface GeminiApiService {

  @POST("v1beta/models/{model}:generateContent")
  suspend fun generateContent(
    @Path("model") model: String,
    @Query("key") apiKey: String,
    @Body request: GeminiGenerateContentRequest,
  ): Response<GeminiGenerateContentResponse>

  @POST("v1beta/models/{model}:generateContent")
  suspend fun generateContentWithKeyRotation(
    @Path("model") model: String,
    @Body request: GeminiGenerateContentRequest,
    @Header(GeminiKeyRotationInterceptor.HEADER_AUTO_ROTATE) autoRotate: String = "true",
  ): Response<GeminiGenerateContentResponse>

  companion object {
    const val BASE_URL = "https://generativelanguage.googleapis.com/"
  }
}
