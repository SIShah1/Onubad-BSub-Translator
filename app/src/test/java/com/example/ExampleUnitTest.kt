package com.example

import com.example.data.network.DeepSeekApiService
import com.example.data.network.DeepSeekTranslationService
import com.example.data.network.GeminiApiClient
import com.example.data.network.GeminiApiService
import com.example.data.network.GeminiCallResult
import com.example.data.network.GeminiTranslationService
import com.example.data.network.GrokApiService
import com.example.data.network.GrokTranslationService
import com.example.data.preferences.SecureKeyStorage
import com.example.domain.model.ApiProvider
import com.example.domain.model.AppSettings
import com.example.domain.model.DefaultConfig
import com.example.domain.model.InputMode
import com.example.domain.model.NamingMode
import com.example.domain.model.ParamMode
import com.example.domain.model.SrtBlock
import com.example.service.BatchAdjustmentReason
import com.example.service.DynamicBatchSizer
import com.example.srt.BatchReconciliationResult
import com.example.srt.SrtEngine
import com.example.srt.SrtParser
import com.example.srt.ValidationResult
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

  @Test
  fun `srt parser and validator verify exact serials and timestamps`() {
    val originalSrt =
      """
      1
      00:00:01,000 --> 00:00:03,500
      Hello world.
      How are you?

      2
      00:00:04,000 --> 00:00:06,200
      Let's go home.
      """.trimIndent()

    val translatedSrt =
      """
      ```srt
      1
      00:00:01,000 --> 00:00:03,500
      হ্যালো বিশ্ব।
      কেমন আছো?

      2
      00:00:04,000 --> 00:00:06,200
      চলো বাড়ি যাই।
      ```
      """.trimIndent()

    val originalBlocks = SrtEngine.parse(originalSrt)
    val parsedTranslated = SrtEngine.parseModelOutput(translatedSrt)
    val result = SrtEngine.validateBatch(originalBlocks, parsedTranslated)

    assertTrue(result is ValidationResult.Valid)
    val validBlocks = (result as ValidationResult.Valid).blocks
    assertEquals(2, validBlocks.size)
    assertEquals("হ্যালো বিশ্ব।", validBlocks[0].lines[0])
    assertEquals("00:00:04,000 --> 00:00:06,200", validBlocks[1].timestamp)
  }

  @Test
  fun `srt validator rejects missing blocks or mismatched timestamps`() {
    val original =
      SrtEngine.parse(
        """
        1
        00:00:01,000 --> 00:00:02,000
        Line one

        2
        00:00:03,000 --> 00:00:04,000
        Line two
        """.trimIndent()
      )

    val missingOne =
      SrtEngine.parse(
        """
        1
        00:00:01,000 --> 00:00:02,000
        লাইন এক
        """.trimIndent()
      )
    assertTrue(SrtEngine.validateBatch(original, missingOne) is ValidationResult.Invalid)

    val badTimestamp =
      SrtEngine.parse(
        """
        1
        00:00:01,000 --> 00:00:02,000
        লাইন এক

        2
        00:00:03,000 --> 00:00:05,000
        লাইন দুই
        """.trimIndent()
      )
    assertTrue(SrtEngine.validateBatch(original, badTimestamp) is ValidationResult.Invalid)
  }

  @Test
  fun `output naming convention builds default and custom filenames`() {
    val defaultSettings = AppSettings(namingMode = NamingMode.DEFAULT)
    assertEquals(
      "Andor_S02E01_BSub.srt",
      SrtEngine.buildOutputFileName("Andor_S02E01.srt", defaultSettings),
    )

    val customSettings =
      AppSettings(
        namingMode = NamingMode.CUSTOM,
        customPrefix = "Bengali_",
        customSuffix = "- BSub",
      )
    assertEquals(
      "Bengali_Andor_S02E01- BSub.srt",
      SrtEngine.buildOutputFileName("Andor_S02E01.srt", customSettings),
    )
  }

  @Test
  fun `bulk key parser parses 25 plus keys cleanly`() {
    val raw25Keys = (1..26).joinToString("\n") { "AIzaSyTestKeyNumber_${it.toString().padStart(2, '0')}_ABCDEF" }
    val parsed = SecureKeyStorage.parseBulkKeys(raw25Keys)
    assertEquals(26, parsed.size)
  }

  @Test
  fun `gemini client distinguishes daily quota exhaustion from server busy and invalid key`() {
    val client = GeminiApiClient()

    val quotaResult =
      client.classifyHttpFailure(
        429,
        """{"error":{"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded for GenerateRequestsPerDay"}}""",
      )
    assertTrue(quotaResult is GeminiCallResult.QuotaExhausted)

    val busyResult =
      client.classifyHttpFailure(
        503,
        """{"error":{"status":"UNAVAILABLE","message":"The model is overloaded."}}""",
      )
    assertTrue(busyResult is GeminiCallResult.RetryableError)

    val invalidResult =
      client.classifyHttpFailure(
        400,
        """{"error":{"status":"INVALID_ARGUMENT","message":"API key not valid. Please pass a valid API key."}}""",
      )
    assertTrue(invalidResult is GeminiCallResult.InvalidKey)
  }

  @Test
  fun `srt parser splits raw srt into items with index timestamp and content`() {
    val raw =
      "\uFEFF1\r\n00:00:01,200 --> 00:00:03,800\r\nFirst line\r\nSecond line\r\n\r\n2\r\n00:00:04,500 --> 00:00:06,000\r\nSingle line"
    val items = com.example.srt.SrtParser.parse(raw)

    assertEquals(2, items.size)
    assertEquals(1, items[0].index)
    assertEquals("00:00:01,200 --> 00:00:03,800", items[0].timestamp)
    assertEquals("First line\nSecond line", items[0].content)
    assertEquals(2, items[1].index)
    assertEquals("00:00:04,500 --> 00:00:06,000", items[1].timestamp)
    assertEquals("Single line", items[1].content)
  }

  @Test
  fun `gemini client delegates to retrofit GeminiApiService with dynamic key and model`() =
    kotlinx.coroutines.runBlocking {
      val capturedKeys = mutableListOf<String>()
      val capturedModels = mutableListOf<String>()

      val fakeService =
        object : com.example.data.network.GeminiApiService {
          override suspend fun generateContentWithKeyRotation(
            model: String,
            request: com.example.data.network.GeminiGenerateContentRequest,
            autoRotate: String,
          ): retrofit2.Response<com.example.data.network.GeminiGenerateContentResponse> =
            generateContent(model = model, apiKey = "", request = request)

          override suspend fun generateContent(
            model: String,
            apiKey: String,
            request: com.example.data.network.GeminiGenerateContentRequest,
          ): retrofit2.Response<com.example.data.network.GeminiGenerateContentResponse> {
            capturedModels.add(model)
            capturedKeys.add(apiKey)
            return if (apiKey == "key-exhausted") {
              retrofit2.Response.error(
                429,
                okhttp3.ResponseBody.create(
                  null,
                  """{"error":{"status":"RESOURCE_EXHAUSTED","message":"Daily quota exceeded"}}""",
                ),
              )
            } else {
              retrofit2.Response.success(
                com.example.data.network.GeminiGenerateContentResponse(
                  candidates =
                    listOf(
                      com.example.data.network.GeminiCandidate(
                        content =
                          com.example.data.network.GeminiContent(
                            role = "model",
                            parts =
                              listOf(
                                com.example.data.network.GeminiPart(text = "internal thought", thought = true),
                                com.example.data.network.GeminiPart(text = "1\n00:00:01,000 --> 00:00:02,000\nহ্যালো"),
                              ),
                          )
                      )
                    )
                )
              )
            }
          }
        }

      val client = GeminiApiClient(customService = fakeService)

      val r1 =
        client.translateBatch(
          apiKey = "key-exhausted",
          model = "gemini-3.8-flash",
          temperature = 0.3f,
          thinkingBudget = 8192,
          systemPrompt = "Translate",
          fewShotExamples = emptyList(),
          inputMode = com.example.domain.model.InputMode.MOVIE,
          title = "Test",
          secondaryMeta = "2026",
          batchSrtPayload = "1\n00:00:01,000 --> 00:00:02,000\nHello",
          batchIndex = 1,
          totalBatches = 1,
          fileName = "test.srt",
        )
      assertTrue(r1 is GeminiCallResult.QuotaExhausted)

      val r2 =
        client.translateBatch(
          apiKey = "key-active-2",
          model = "gemini-3.8-flash",
          temperature = 0.3f,
          thinkingBudget = 8192,
          systemPrompt = "Translate",
          fewShotExamples = emptyList(),
          inputMode = com.example.domain.model.InputMode.MOVIE,
          title = "Test",
          secondaryMeta = "2026",
          batchSrtPayload = "1\n00:00:01,000 --> 00:00:02,000\nHello",
          batchIndex = 1,
          totalBatches = 1,
          fileName = "test.srt",
        )
      assertTrue(r2 is GeminiCallResult.Success)
      assertEquals("1\n00:00:01,000 --> 00:00:02,000\nহ্যালো", (r2 as GeminiCallResult.Success).translatedText)
      assertEquals(listOf("key-exhausted", "key-active-2"), capturedKeys)
      assertEquals(listOf("gemini-3.8-flash", "gemini-3.8-flash"), capturedModels)
    }

  @Test
  fun `srt parser reads utf16 and utf8 bom files ignores dialogue arrows and reconstructs bengali srt`() {
    val rawSrt =
      """
      1
      00:00:01,000 --> 00:00:03,500 X1:100 X2:200
      Go left --> right
      Hurry up!

      2
      00:00:04.250 --> 00:00:06.750
      Welcome home.
      """.trimIndent()

    // Verify UTF-16LE BOM stream reading
    val utf16Bytes =
      byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
        rawSrt.toByteArray(Charset.forName("UTF-16LE"))
    val parsedFromStream = SrtParser.parse(ByteArrayInputStream(utf16Bytes))
    assertEquals(2, parsedFromStream.size)
    assertEquals("00:00:01,000 --> 00:00:03,500 X1:100 X2:200", parsedFromStream[0].timestamp)
    assertEquals("Go left --> right\nHurry up!", parsedFromStream[0].content)

    val range = SrtParser.parseTimecodeRange(parsedFromStream[0].timestamp)
    assertNotNull(range)
    assertEquals(1000L, range!!.startMillis)
    assertEquals(3500L, range.endMillis)
    assertEquals(2500L, range.durationMillis)
    assertTrue(range.isChronologicallyValid)
    assertFalse(SrtParser.isValidTimecodeLine("Go left --> right"))

    // Replace subtitle text with Bengali translations and reconstruct to temp file
    val bengaliMap =
      mapOf(
        1 to "বাম থেকে --> ডানে যাও\nতাড়াতাড়ি করো!",
        2 to "বাড়িতে স্বাগতম।",
      )
    val reconstructed = SrtParser.reconstruct(parsedFromStream, bengaliMap)

    val tempFile = File.createTempFile("bsub_parser_test", ".srt")
    try {
      val replacedItems = SrtParser.replaceTranslations(parsedFromStream, bengaliMap)
      SrtParser.write(tempFile, replacedItems)
      val reparsed = SrtParser.parse(tempFile)
      assertEquals(2, reparsed.size)
      assertEquals("00:00:01,000 --> 00:00:03,500 X1:100 X2:200", reparsed[0].timestamp)
      assertEquals("00:00:04.250 --> 00:00:06.750", reparsed[1].timestamp)
      assertEquals("বাম থেকে --> ডানে যাও\nতাড়াতাড়ি করো!", reparsed[0].content)
      assertEquals("বাড়িতে স্বাগতম।", reparsed[1].content)
      assertTrue(reconstructed.contains("00:00:01,000 --> 00:00:03,500 X1:100 X2:200"))
    } finally {
      tempFile.delete()
    }
  }

  @Test
  fun `srt parser enforces timecode integrity during block translation reconstruction`() {
    val originalBlocks =
      listOf(
        SrtBlock(index = 1, timestamp = "00:00:01,000 --> 00:00:03,000", lines = listOf("Hello")),
        SrtBlock(index = 2, timestamp = "00:00:04,000 --> 00:00:06,000", lines = listOf("World")),
      )
    val validTranslatedBlocks =
      listOf(
        SrtBlock(index = 1, timestamp = "00:00:01.000 --> 00:00:03.000", lines = listOf("হ্যালো")),
        SrtBlock(index = 2, timestamp = "00:00:04,000 --> 00:00:06,000", lines = listOf("বিশ্ব")),
      )

    val rebuilt = SrtParser.reconstructFromBlocks(originalBlocks, validTranslatedBlocks)
    assertEquals(
      """
      1
      00:00:01,000 --> 00:00:03,000
      হ্যালো

      2
      00:00:04,000 --> 00:00:06,000
      বিশ্ব
      """.trimIndent() + "\n",
      rebuilt,
    )

    val tamperedBlocks =
      listOf(
        SrtBlock(index = 1, timestamp = "00:00:01,000 --> 00:00:09,999", lines = listOf("হ্যালো")),
        SrtBlock(index = 2, timestamp = "00:00:04,000 --> 00:00:06,000", lines = listOf("বিশ্ব")),
      )
    assertThrows(IllegalArgumentException::class.java) {
      SrtParser.reconstructFromBlocks(originalBlocks, tamperedBlocks, strictTimecodeCheck = true)
    }
  }

  @Test
  fun `srt reconcileBatch aligns minor dropped or renumbered cues and rejects truncated output`() {
    val originalBatch =
      (1..5).map { idx ->
        SrtBlock(
          index = 100 + idx,
          timestamp = "00:00:0$idx,000 --> 00:00:0$idx,900",
          lines = listOf("English line $idx"),
        )
      }

    // 4/5 (80%) cues returned with slight timestamp change and missing cue #103 -> reconciled!
    val mostlyTranslated =
      listOf(
        SrtBlock(101, "00:00:01,000 --> 00:00:01,900", listOf("বাংলা ১")),
        SrtBlock(102, "00:00:02,000 --> 00:00:02,900", listOf("বাংলা ২")),
        SrtBlock(104, "00:00:04,000 --> 00:00:04,950", listOf("বাংলা ৪")),
        SrtBlock(999, "00:00:05,000 --> 00:00:05,900", listOf("বাংলা ৫")),
      )

    val resolvedResult = SrtEngine.reconcileBatch(originalBatch, mostlyTranslated)
    assertTrue(resolvedResult is BatchReconciliationResult.Resolved)
    val resolved = resolvedResult as BatchReconciliationResult.Resolved
    assertFalse(resolved.exactMatch)
    assertEquals(4, resolved.matchedCount)
    assertEquals(5, resolved.blocks.size)
    // Original indices & timestamps stay 100% verbatim
    assertEquals(104, resolved.blocks[3].index)
    assertEquals("00:00:04,000 --> 00:00:04,900", resolved.blocks[3].timestamp)
    assertEquals("বাংলা ৪", resolved.blocks[3].lines.first())
    // Dropped cue falls back safely to original text instead of aborting the batch
    assertEquals("English line 3", resolved.blocks[2].lines.first())
    // Cue #5 matched by normalized timestamp despite AI index renumbering (999)
    assertEquals("বাংলা ৫", resolved.blocks[4].lines.first())

    // Truncated 2/5 (40%) output -> TooIncomplete so service auto-splits
    val truncated = mostlyTranslated.take(2)
    val incomplete = SrtEngine.reconcileBatch(originalBatch, truncated)
    assertTrue(incomplete is BatchReconciliationResult.TooIncomplete)
    assertEquals(2, (incomplete as BatchReconciliationResult.TooIncomplete).matchedCount)
  }

  @Test
  fun `gemini client distinguishes per-minute 429 rate limits and 400 or 404 fatal errors`() {
    val client = GeminiApiClient()

    val rpmLimit =
      client.classifyHttpFailure(
        429,
        """{"error":{"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded for quota metric 'Generate content requests per minute'"}}""",
      )
    assertTrue("Expected RateLimited for RPM 429, got $rpmLimit", rpmLimit is GeminiCallResult.RateLimited)

    val modelNotFound =
      client.classifyHttpFailure(
        404,
        """{"error":{"status":"NOT_FOUND","message":"models/gemini-bad is not found"}}""",
      )
    assertTrue("Expected FatalError for 404, got $modelNotFound", modelNotFound is GeminiCallResult.FatalError)

    val badThinkingConfig =
      client.classifyHttpFailure(
        400,
        """{"error":{"status":"INVALID_ARGUMENT","message":"Thinking budget is not supported for this model"}}""",
      )
    assertTrue(
      "Expected FatalError for config 400, got $badThinkingConfig",
      badThinkingConfig is GeminiCallResult.FatalError,
    )
  }

  @Test
  fun `context lines default is 50 and SrtEngine buildSurroundingContext caps and includes prior translations`() {
    assertEquals(50, DefaultConfig.CONTEXT_LINES)
    assertEquals(50, AppSettings().effectiveContextLines)
    assertEquals(
      0,
      AppSettings(contextLinesMode = ParamMode.MANUAL, manualContextLines = -5).effectiveContextLines,
    )
    assertEquals(
      500,
      AppSettings(contextLinesMode = ParamMode.MANUAL, manualContextLines = 999).effectiveContextLines,
    )

    val allBlocks =
      (1..20).map { idx ->
        SrtBlock(
          index = idx,
          timestamp = "00:00:${idx.toString().padStart(2, '0')},000 --> 00:00:${idx.toString().padStart(2, '0')},900",
          lines = listOf("Dialogue line $idx"),
        )
      }

    // Disabled when 0
    assertEquals(
      "",
      SrtEngine.buildSurroundingContext(allBlocks, startIdx = 5, endIdxExclusive = 10, maxContextLines = 0),
    )

    // Middle batch [8..12) with maxContextLines = 6 -> 3 before (6,7,8 -> indices 5,6,7 => cues 6..8) + 3 after (cues 13..15)
    val translatedMap =
      mapOf(
        8 to SrtBlock(8, "00:00:08,000 --> 00:00:08,900", listOf("সংলাপ ৮"))
      )
    val midContext =
      SrtEngine.buildSurroundingContext(
        allBlocks = allBlocks,
        startIdx = 8,
        endIdxExclusive = 12,
        maxContextLines = 6,
        translatedByIndex = translatedMap,
      )
    assertTrue(midContext.contains("PRECEDING LINES"))
    assertTrue(midContext.contains("FOLLOWING LINES"))
    assertTrue(midContext.contains("[#6] \"Dialogue line 6\""))
    assertTrue(midContext.contains("[#8] \"Dialogue line 8\" (BN: \"সংলাপ ৮\")"))
    assertFalse(midContext.contains("Dialogue line 5\""))
    assertTrue(midContext.contains("[#13] \"Dialogue line 13\""))
    assertTrue(midContext.contains("[#15] \"Dialogue line 15\""))
    assertFalse(midContext.contains("Dialogue line 16\""))
    assertFalse(midContext.contains("-->"))

    // Start of file [0..5) -> all 6 context lines go to following cues (6..11)
    val startContext =
      SrtEngine.buildSurroundingContext(
        allBlocks = allBlocks,
        startIdx = 0,
        endIdxExclusive = 5,
        maxContextLines = 6,
      )
    assertFalse(startContext.contains("PRECEDING LINES"))
    assertTrue(startContext.contains("[#6] \"Dialogue line 6\""))
    assertTrue(startContext.contains("[#11] \"Dialogue line 11\""))
  }

  @Test
  fun `gemini client attaches surrounding context before final target batch turn without altering batch payload`() {
    val client = GeminiApiClient()

    val noContextReq =
      client.buildRequestBody(
        systemPrompt = "Translate",
        fewShotExamples = emptyList(),
        inputMode = InputMode.MOVIE,
        title = "Demo",
        secondaryMeta = "2026",
        batchSrtPayload = "2\n00:00:02,000 --> 00:00:03,000\nTarget line",
        batchIndex = 1,
        totalBatches = 1,
        fileName = "demo.srt",
        temperature = 0.3f,
        thinkingBudget = 0,
        surroundingContext = "",
      )
    assertEquals(1, noContextReq.contents.size)

    val withContextReq =
      client.buildRequestBody(
        systemPrompt = "Translate",
        fewShotExamples = emptyList(),
        inputMode = InputMode.MOVIE,
        title = "Demo",
        secondaryMeta = "2026",
        batchSrtPayload = "2\n00:00:02,000 --> 00:00:03,000\nTarget line",
        batchIndex = 1,
        totalBatches = 2,
        fileName = "demo.srt",
        temperature = 0.3f,
        thinkingBudget = 0,
        surroundingContext = "PRECEDING LINES:\n[#1] \"Who is there?\"",
      )
    assertEquals(3, withContextReq.contents.size)
    assertEquals("user", withContextReq.contents[0].role)
    assertTrue(withContextReq.contents[0].parts.first().text.orEmpty().contains("SURROUNDING DIALOGUE CONTEXT"))
    assertTrue(withContextReq.contents[0].parts.first().text.orEmpty().contains("Who is there?"))
    assertEquals("model", withContextReq.contents[1].role)
    assertEquals("user", withContextReq.contents.last().role)
    val targetTurnText = withContextReq.contents.last().parts.first().text.orEmpty()
    assertTrue(targetTurnText.contains("Target line"))
    assertFalse(targetTurnText.contains("Who is there?"))
  }

  @Test
  fun `provider default models chat completion request and 402 or 401 errors work for grok and deepseek`() {
    assertEquals(DefaultConfig.GEMINI_MODEL, AppSettings(apiProvider = ApiProvider.GEMINI).effectiveModel)
    assertEquals(DefaultConfig.GROK_MODEL, AppSettings(apiProvider = ApiProvider.GROK).effectiveModel)
    assertEquals(DefaultConfig.DEEPSEEK_MODEL, AppSettings(apiProvider = ApiProvider.DEEPSEEK).effectiveModel)

    val client = GeminiApiClient()
    val chatReq =
      client.buildChatCompletionRequest(
        model = "grok-3-mini",
        systemPrompt = "Translate SRT",
        fewShotExamples = emptyList(),
        inputMode = InputMode.SERIES,
        title = "Andor",
        secondaryMeta = "S02",
        batchSrtPayload = "1\n00:00:01,000 --> 00:00:02,000\nTarget line",
        batchIndex = 1,
        totalBatches = 1,
        fileName = "ep01.srt",
        temperature = 0.25f,
        surroundingContext = "FOLLOWING LINES:\n[#2] \"Next cue\"",
      )
    assertEquals("grok-3-mini", chatReq.model)
    assertEquals("system", chatReq.messages.first().role)
    assertTrue(chatReq.messages.first().content.contains("Series Name: Andor"))
    assertEquals("user", chatReq.messages[1].role)
    assertTrue(chatReq.messages[1].content.contains("SURROUNDING DIALOGUE CONTEXT"))
    assertEquals("assistant", chatReq.messages[2].role)
    assertEquals("user", chatReq.messages.last().role)
    assertTrue(chatReq.messages.last().content.contains("Target line"))

    val insufficientBalance =
      client.classifyHttpFailure(
        402,
        """{"error":{"message":"Insufficient Balance","type":"invalid_request_error"}}""",
      )
    assertTrue(
      "Expected QuotaExhausted for DeepSeek 402, got $insufficientBalance",
      insufficientBalance is GeminiCallResult.QuotaExhausted,
    )

    val badBearerKey =
      client.classifyHttpFailure(
        401,
        """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error","code":"invalid_api_key"}}""",
      )
    assertTrue(
      "Expected InvalidKey for OpenAI-style 401, got $badBearerKey",
      badBearerKey is GeminiCallResult.InvalidKey,
    )
  }

  @Test
  fun `dynamic batch sizer scales up when api is responsive and shrinks on slow latency or rate limits`() {
    val sizer =
      DynamicBatchSizer(
        baseBatchSize = 100,
        enabled = true,
        minBatchSize = 40,
        maxBatchSize = 250,
        windowSize = 3,
        fastThresholdMs = 3_000L,
        slowThresholdMs = 10_000L,
      )
    assertEquals(100, sizer.currentBatchSize)

    // Fast response -> scales up (+25 -> 125)
    val up1 = sizer.recordSuccessLatency(latencyMs = 1_200L)
    assertEquals(BatchAdjustmentReason.SCALED_UP, up1.reason)
    assertEquals(125, up1.newSize)
    assertEquals(125, sizer.currentBatchSize)

    // Balanced response (avg (1200 + 6000)/2 = 3600ms) -> stays steady
    val steady = sizer.recordSuccessLatency(latencyMs = 6_000L)
    assertEquals(BatchAdjustmentReason.STEADY, steady.reason)
    assertEquals(125, steady.newSize)

    // Slow latency pushes rolling avg above 10s -> scales down
    sizer.recordSuccessLatency(latencyMs = 18_000L)
    val down = sizer.recordSuccessLatency(latencyMs = 16_000L)
    assertEquals(BatchAdjustmentReason.SCALED_DOWN_LATENCY, down.reason)
    assertTrue("Expected reduced batch size, got ${down.newSize}", down.newSize < 125)

    // Rate limit hits scale down immediately toward minBatchSize
    val beforeRateLimit = sizer.currentBatchSize
    val rlDown = sizer.recordSuccessLatency(latencyMs = 1_500L, rateLimitHitsInBatch = 2)
    assertEquals(BatchAdjustmentReason.SCALED_DOWN_RATE_LIMIT, rlDown.reason)
    assertTrue(rlDown.newSize < beforeRateLimit)
    assertTrue(sizer.currentBatchSize >= 40)

    // Disabled sizer stays fixed at baseBatchSize
    val fixedSizer = DynamicBatchSizer(baseBatchSize = 150, enabled = false)
    val fixedRes = fixedSizer.recordSuccessLatency(latencyMs = 500L)
    assertEquals(150, fixedRes.newSize)
    assertEquals(150, fixedSizer.currentBatchSize)
  }

  @Test
  fun `polymorphic translation service provider resolves dedicated retrofit configurations per engine`() {
    val client = GeminiApiClient()

    val geminiService = client.serviceFor(ApiProvider.GEMINI)
    val grokService = client.serviceFor(ApiProvider.GROK)
    val deepSeekService = client.serviceFor(ApiProvider.DEEPSEEK)

    assertTrue(geminiService is GeminiTranslationService)
    assertTrue(grokService is GrokTranslationService)
    assertTrue(deepSeekService is DeepSeekTranslationService)

    assertEquals(ApiProvider.GEMINI, geminiService.provider)
    assertEquals(ApiProvider.GROK, grokService.provider)
    assertEquals(ApiProvider.DEEPSEEK, deepSeekService.provider)

    assertEquals(GeminiApiService.BASE_URL, geminiService.baseUrl)
    assertEquals(GrokApiService.BASE_URL, grokService.baseUrl)
    assertEquals(DeepSeekApiService.BASE_URL, deepSeekService.baseUrl)
  }

  @Test
  fun `lenient model parser and reconcileBatch recover decorated serials variant timestamps and relative numbering`() {
    val originalBatch =
      listOf(
        SrtBlock(151, "00:05:01,000 --> 00:05:03,500", listOf("Line one")),
        SrtBlock(152, "00:05:04,200 --> 00:05:06,000", listOf("Line two")),
        SrtBlock(153, "00:05:07,000 --> 00:05:09,500", listOf("Line three")),
      )

    // Real-world LLM quirks: <think> preamble, unclosed fence, decorated serials, single arrow, 2-digit ms, relative 1..3 numbering
    val noisyLlmOutput =
      """
      <think>Analyzing tone...</think>
      ```srt
      1.
      **00:05:01,000 -> 00:05:03,50**
      প্রথম সংলাপ

      **2**
      0:05:04:200 --> 0:05:06.000
      দ্বিতীয় সংলাপ

      [3]
      00:05:07,000 ==> 00:05:09,5
      তৃতীয় সংলাপ
      """.trimIndent()

    val parsed = SrtEngine.parseModelOutput(noisyLlmOutput, expectedBatch = originalBatch)
    assertEquals(3, parsed.size)

    val reconciled = SrtEngine.reconcileBatch(originalBatch, parsed)
    assertTrue("Expected Resolved, got $reconciled", reconciled is BatchReconciliationResult.Resolved)
    val resolved = reconciled as BatchReconciliationResult.Resolved
    assertEquals(3, resolved.blocks.size)
    assertEquals(151, resolved.blocks[0].index)
    assertEquals("00:05:01,000 --> 00:05:03,500", resolved.blocks[0].timestamp)
    assertEquals("প্রথম সংলাপ", resolved.blocks[0].lines.first())
    assertEquals(152, resolved.blocks[1].index)
    assertEquals("দ্বিতীয় সংলাপ", resolved.blocks[1].lines.first())
    assertEquals(153, resolved.blocks[2].index)
    assertEquals("তৃতীয় সংলাপ", resolved.blocks[2].lines.first())
  }

  @Test
  fun `generic 429 resource exhausted is treated as recoverable rate limit and deepseek request sets maxTokens`() {
    val client = GeminiApiClient()

    val generic429 =
      client.classifyHttpFailure(
        429,
        """{"error":{"status":"RESOURCE_EXHAUSTED","message":"Resource has been exhausted (e.g. check quota)."}}""",
      )
    assertTrue(
      "Expected recoverable RateLimited for generic 429, got $generic429",
      generic429 is GeminiCallResult.RateLimited,
    )

    val deepSeekReq =
      client.buildChatCompletionRequest(
        model = "deepseek-chat",
        systemPrompt = "Translate",
        fewShotExamples = emptyList(),
        inputMode = InputMode.MOVIE,
        title = "Demo",
        secondaryMeta = "2026",
        batchSrtPayload = "1\n00:00:01,000 --> 00:00:02,000\nHi",
        batchIndex = 1,
        totalBatches = 1,
        fileName = "demo.srt",
        temperature = 0.3f,
      )
    assertEquals(8192, deepSeekReq.maxTokens)
  }

  @Test
  fun `zero thinking budget explicitly sends thinkingBudget 0 for flash models and 429 limit 0 classifies as QuotaExhausted`() {
    val client = GeminiApiClient()

    val flashReq =
      client.buildRequestBody(
        systemPrompt = "Translate",
        fewShotExamples = emptyList(),
        inputMode = InputMode.MOVIE,
        title = "Demo",
        secondaryMeta = "2026",
        batchSrtPayload = "1\n00:00:01,000 --> 00:00:02,000\nHi",
        batchIndex = 1,
        totalBatches = 1,
        fileName = "demo.srt",
        temperature = 0.3f,
        thinkingBudget = 0,
        model = "gemini-3.8-flash",
      )
    assertEquals(0, flashReq.generationConfig?.thinkingConfig?.thinkingBudget)

    val proReq =
      client.buildRequestBody(
        systemPrompt = "Translate",
        fewShotExamples = emptyList(),
        inputMode = InputMode.MOVIE,
        title = "Demo",
        secondaryMeta = "2026",
        batchSrtPayload = "1\n00:00:01,000 --> 00:00:02,000\nHi",
        batchIndex = 1,
        totalBatches = 1,
        fileName = "demo.srt",
        temperature = 0.3f,
        thinkingBudget = 0,
        model = "gemini-2.5-pro",
      )
    assertEquals(null, proReq.generationConfig?.thinkingConfig)

    val zeroLimit429 =
      client.classifyHttpFailure(
        429,
        """{"error":{"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 0, model: gemini-3.8-flash"}}""",
      )
    assertTrue(
      "Expected QuotaExhausted for limit: 0, got $zeroLimit429",
      zeroLimit429 is GeminiCallResult.QuotaExhausted,
    )
  }
}
