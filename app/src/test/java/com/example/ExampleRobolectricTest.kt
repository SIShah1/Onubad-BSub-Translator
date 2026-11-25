package com.example

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.example.data.preferences.SecureKeyStorage
import com.example.data.storage.SubtitleFileStorage
import com.example.srt.SrtFileParser
import com.example.domain.model.TranslationStatus
import com.example.data.local.ApiKeyUsageStat
import com.example.data.local.ApiRequestOutcome
import com.example.data.local.ApiUsageEvent
import com.example.data.local.BSubDatabase
import com.example.data.local.FileTranslationProgress
import com.example.data.local.HistoryRepository
import com.example.data.local.HistoryTranslationStatus
import com.example.data.local.RoomTranslationProgress
import com.example.data.local.SubtitleHistory
import com.example.data.local.SubtitleHistoryRepository
import com.example.data.local.SubtitleTranslation
import com.example.data.local.SubtitleTranslationRepository
import com.example.data.local.SubtitleTranslationStatus
import com.example.data.local.TranslationHistoryEntity
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.UsageDashboardScreen
import com.example.data.network.ApiKeyRotationManager
import com.example.data.network.ChatChoiceDto
import com.example.data.network.ChatCompletionRequest
import com.example.data.network.ChatCompletionResponse
import com.example.data.network.ChatMessageDto
import com.example.data.network.DeepSeekApiService
import com.example.data.network.GeminiApiClient
import com.example.data.network.GeminiApiService
import com.example.data.network.GeminiCallResult
import com.example.data.network.GeminiCandidate
import com.example.data.network.GeminiContent
import com.example.data.network.GeminiGenerateContentRequest
import com.example.data.network.GeminiGenerateContentResponse
import com.example.data.network.GeminiKeyRotationInterceptor
import com.example.data.network.GeminiPart
import com.example.data.network.GrokApiService
import com.example.data.network.OpenAiCompatibleApiService
import com.example.data.network.RotationExecutionResult
import com.example.domain.model.ApiKeyStatus
import com.example.domain.model.ApiProvider
import com.example.domain.model.AppSettings
import com.example.domain.model.DefaultConfig
import com.example.domain.model.InputMode
import com.example.domain.model.ParamMode
import com.example.domain.model.SelectedSubtitleFile
import com.example.domain.model.SrtBlock
import com.example.domain.model.ThemeMode
import com.example.domain.model.TranslationJobRequest
import com.example.domain.model.TranslationSessionState
import com.example.logging.ErrorCategory
import com.example.logging.TranslationLogger
import com.example.logging.WorkManagerDiagnosticTree
import com.example.service.DynamicBatchSizer
import com.example.service.SubtitleTranslationService
import com.example.service.SubtitleTranslationServiceResult
import com.example.ui.components.ContextAwareTranslationCard
import com.example.ui.screens.ApiKeysScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.screens.SubtitlePreviewScreen
import com.example.ui.screens.fitHeroTitleFontSizeSp
import com.example.ui.theme.BSubTranslatorTheme
import com.example.ui.theme.CloverCoralTint
import com.example.ui.theme.CloverEmerald
import com.example.ui.theme.CloverMintAqua
import com.example.ui.theme.CloverYellow
import com.example.ui.theme.CloverYellowTint
import com.example.ui.theme.LightCanvas
import com.example.ui.theme.LocalThemeController
import com.example.ui.theme.StudioMidnight
import com.example.ui.theme.ThemeProvider
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.StudioFormState
import com.example.ui.viewmodel.SubtitleCuePair
import com.example.ui.viewmodel.SubtitlePreviewState
import com.example.worker.SubtitleTranslationWorker
import com.example.worker.TranslationWorkScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response as OkHttpResponse
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var db: BSubDatabase
  private lateinit var app: BSubApplication

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    app = context as BSubApplication
    db = Room.inMemoryDatabaseBuilder(context, BSubDatabase::class.java).allowMainThreadQueries().build()
    WorkManagerTestInitHelper.initializeTestWorkManager(context)
    runBlocking {
      app.settingsRepository.setApiProvider(ApiProvider.GEMINI)
      app.settingsRepository.setModel(ParamMode.DEFAULT, DefaultConfig.GEMINI_MODEL)
    }
  }

  @After
  fun tearDown() {
    app.overrideGeminiServiceForTesting(null)
    runBlocking {
      app.settingsRepository.setApiProvider(ApiProvider.GEMINI)
      app.settingsRepository.setModel(ParamMode.DEFAULT, DefaultConfig.GEMINI_MODEL)
    }
    TranslationLogger.resetForTesting(app)
    db.close()
  }

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("BSub Translator", appName)
  }

  @Test
  fun `subtitle translation entity persists pending and transitions to translated`() = runBlocking {
    val repo = SubtitleTranslationRepository(db.subtitleTranslationDao())
    val block = SrtBlock(index = 1, timestamp = "00:00:01,000 --> 00:00:03,000", lines = listOf("Hello!"))

    repo.seedPendingBlocks(jobId = "job_1", fileName = "movie.srt", blocks = listOf(block))

    val initialRows: List<SubtitleTranslation> = repo.observeJobTranslations("job_1").first()
    assertEquals(1, initialRows.size)
    assertEquals("Hello!", initialRows[0].originalEnglishText)
    assertEquals(null, initialRows[0].translatedBengaliText)
    assertEquals(SubtitleTranslationStatus.PENDING, initialRows[0].status)

    val translatedBlock = block.copy(lines = listOf("হ্যালো!"))
    repo.markBatchTranslated(jobId = "job_1", translatedBlocks = listOf(translatedBlock))

    val updatedRows = repo.observeJobTranslations("job_1").first()
    assertEquals(1, updatedRows.size)
    assertEquals("হ্যালো!", updatedRows[0].translatedBengaliText)
    assertEquals(SubtitleTranslationStatus.TRANSLATED, updatedRows[0].status)
  }

  @Test
  fun `subtitle translation repository inserts queries by status and updates by id`() = runBlocking {
    val repo = SubtitleTranslationRepository(db.subtitleTranslationDao())
    val id =
      repo.insertTranslation(
        jobId = "job_2",
        fileName = "episode1.srt",
        blockIndex = 1,
        timestamp = "00:00:05,000 --> 00:00:07,000",
        originalEnglishText = "Where are we going?",
      )

    val pending = repo.observeByStatus(SubtitleTranslationStatus.PENDING).first()
    assertEquals(1, pending.size)
    assertEquals(id, pending[0].id)

    repo.markAsTranslated(id, "আমরা কোথায় যাচ্ছি?")

    val translated = repo.observeByStatus(SubtitleTranslationStatus.TRANSLATED).first()
    assertEquals(1, translated.size)
    assertEquals("আমরা কোথায় যাচ্ছি?", translated[0].translatedBengaliText)
  }

  @Test
  fun `workmanager subtitle translation worker parses srt seeds room and translates batch`() = runBlocking {
    app.historyRepository.clearAll()
    app.subtitleTranslationRepository.clearAll()
    app.secureKeyStorage.clearAllKeys()
    app.secureKeyStorage.addSingleKey("AIzaSyDummyTestKeyForWorkManager12345", "Test Key #1")

    val fakeGemini =
      object : GeminiApiService {
        override suspend fun generateContentWithKeyRotation(
          model: String,
          request: GeminiGenerateContentRequest,
          autoRotate: String,
        ): Response<GeminiGenerateContentResponse> =
          generateContent(model = model, apiKey = "", request = request)

        override suspend fun generateContent(
          model: String,
          apiKey: String,
          request: GeminiGenerateContentRequest,
        ): Response<GeminiGenerateContentResponse> {
          val translatedSrt =
            """
            1
            00:00:01,000 --> 00:00:03,000
            কেমন আছেন?

            2
            00:00:04,000 --> 00:00:06,500
            সবকিছু ঠিক আছে।
            """.trimIndent()
          return Response.success(
            GeminiGenerateContentResponse(
              candidates =
                listOf(
                  GeminiCandidate(
                    content = GeminiContent(parts = listOf(GeminiPart(text = translatedSrt))),
                    finishReason = "STOP",
                  )
                )
            )
          )
        }
      }

    app.overrideGeminiServiceForTesting(fakeGemini)

    val sampleSrt =
      """
      1
      00:00:01,000 --> 00:00:03,000
      How are you?

      2
      00:00:04,000 --> 00:00:06,500
      Everything is fine.
      """.trimIndent()

    val workerJobId = "wm_test_job_1"
    val worker =
      TestListenableWorkerBuilder<SubtitleTranslationWorker>(app)
        .setInputData(
          workDataOf(
            SubtitleTranslationWorker.KEY_SRT_CONTENT to sampleSrt,
            SubtitleTranslationWorker.KEY_FILE_NAME to "test_movie.srt",
            SubtitleTranslationWorker.KEY_INPUT_MODE to InputMode.MOVIE.name,
            SubtitleTranslationWorker.KEY_TITLE to "Test Film",
            SubtitleTranslationWorker.KEY_SECONDARY_META to "2026",
            SubtitleTranslationWorker.KEY_ROOM_JOB_ID to workerJobId,
          )
        )
        .build()

    val result = worker.doWork()
    assertTrue("Expected WorkManager Result.Success, got $result", result is ListenableWorker.Result.Success)

    val rows = app.subtitleTranslationRepository.observeJobTranslations(workerJobId).first()
    assertEquals(2, rows.size)
    assertEquals("How are you?", rows[0].originalEnglishText)
    assertEquals("কেমন আছেন?", rows[0].translatedBengaliText)
    assertEquals(SubtitleTranslationStatus.TRANSLATED, rows[0].status)
    assertEquals("সবকিছু ঠিক আছে।", rows[1].translatedBengaliText)
    assertEquals(SubtitleTranslationStatus.TRANSLATED, rows[1].status)

    val subtitleHistoryRows =
      app.subtitleHistoryRepository.observeByOriginalFileName("test_movie.srt").first()
    assertEquals(1, subtitleHistoryRows.size)
    assertEquals(HistoryTranslationStatus.COMPLETED, subtitleHistoryRows[0].status)
    assertEquals(2, subtitleHistoryRows[0].translatedBlocks)

    val uiHistoryRows = app.historyRepository.historyItems.first()
    assertTrue(
      uiHistoryRows.any {
        it.originalFileName == "test_movie.srt" &&
          it.translationStatus == HistoryTranslationStatus.COMPLETED &&
          it.translatedBlocks == 2
      }
    )
  }

  @Test
  fun `translation work scheduler enqueues unique work with network constraint`() = runBlocking {
    val sampleBlocks =
      listOf(
        SrtBlock(index = 1, timestamp = "00:00:01,000 --> 00:00:02,000", lines = listOf("Hello"))
      )
    val request =
      TranslationJobRequest(
        inputMode = InputMode.MOVIE,
        title = "Inception",
        secondaryMeta = "2010",
        files =
          listOf(
            SelectedSubtitleFile(
              uriString = "",
              fileName = "inception.srt",
              fileSizeBytes = 64L,
              totalBlocks = 1,
              totalLines = 1,
              blocks = sampleBlocks,
            )
          ),
      )

    val workId = TranslationWorkScheduler.enqueueTranslation(app, request)
    val info = WorkManager.getInstance(app).getWorkInfoById(workId).get()
    assertEquals(WorkInfo.State.ENQUEUED, info?.state)
    assertTrue(info?.tags?.contains(TranslationWorkScheduler.TAG_SUBTITLE_TRANSLATION) == true)
  }

  @Test
  fun `room progress queries and studio progress indicator update from translation status counts`() =
    runBlocking {
      val repo = SubtitleTranslationRepository(db.subtitleTranslationDao())
      val blocks =
        listOf(
          SrtBlock(index = 1, timestamp = "00:00:01,000 --> 00:00:02,000", lines = listOf("One")),
          SrtBlock(index = 2, timestamp = "00:00:03,000 --> 00:00:04,000", lines = listOf("Two")),
        )
      repo.seedPendingBlocks(jobId = "progress_job", fileName = "sample.srt", blocks = blocks)

      val initial = repo.observeOverallProgress().first()
      assertEquals(2, initial.totalBlocks)
      assertEquals(0, initial.translatedBlocks)
      assertEquals(2, initial.pendingBlocks)
      assertEquals(0f, initial.progressFraction)

      composeTestRule.setContent {
        val roomProgress by repo.observeOverallProgress().collectAsState(initial = RoomTranslationProgress())
        val fileProgress by
          repo.observeFileProgressList().collectAsState(initial = emptyList<FileTranslationProgress>())
        BSubTranslatorTheme {
          StudioScreen(
            formState = StudioFormState(),
            settings = AppSettings(),
            apiKeys = emptyList(),
            sessionState = TranslationSessionState(),
            roomProgress = roomProgress,
            fileProgressList = fileProgress,
            onInputModeChange = {},
            onMovieNameChange = {},
            onReleaseYearChange = {},
            onSeriesNameChange = {},
            onSeasonChange = {},
            onSingleSrtPicked = {},
            onMultipleSrtPicked = {},
            onRemoveFile = {},
            onMoveSeriesFile = { _, _ -> },
            onClearFiles = {},
            onStartTranslation = {},
            onAbortTranslation = {},
            onClearLogs = {},
            onNavigateToKeys = {},
          )
        }
      }

      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("room_progress_counts").assertTextEquals("0/2 blocks processed")
      composeTestRule.onNodeWithTag("room_progress_percent").assertTextEquals("0%")

      repo.markBatchTranslated(
        jobId = "progress_job",
        translatedBlocks = listOf(blocks[0].copy(lines = listOf("এক"))),
      )

      val halfOverall = repo.observeOverallProgress().first { it.translatedBlocks == 1 }
      assertEquals(1, halfOverall.pendingBlocks)

      composeTestRule.waitUntil(timeoutMillis = 5000L) {
        try {
          composeTestRule.onNodeWithTag("room_progress_counts").assertTextEquals("1/2 blocks processed")
          true
        } catch (_: AssertionError) {
          false
        }
      }
      composeTestRule.onNodeWithTag("room_progress_percent").assertTextEquals("50%")

      val halfFileProgress = repo.observeFileProgressList().first()
      assertEquals(1, halfFileProgress.size)
      assertEquals(1, halfFileProgress[0].translatedBlocks)
      assertEquals(1, halfFileProgress[0].pendingBlocks)
      assertEquals(50, halfFileProgress[0].progressPercent)
    }

  @Test
  fun `theme provider dynamically toggles light and dark color schemes across ui segments`() {
    var observedBgColor = Color.Unspecified
    var observedPrimaryColor = Color.Unspecified
    var observedSecondaryColor = Color.Unspecified
    var observedSecondaryContainer = Color.Unspecified
    var observedErrorContainer = Color.Unspecified
    var observedIsDark = true

    composeTestRule.setContent {
      var mode by remember { mutableStateOf(ThemeMode.DARK) }
      ThemeProvider(
        themeMode = mode,
        onThemeModeChange = { mode = it },
        animateTransitions = false,
      ) {
        val controller = LocalThemeController.current
        observedBgColor = MaterialTheme.colorScheme.background
        observedPrimaryColor = MaterialTheme.colorScheme.primary
        observedSecondaryColor = MaterialTheme.colorScheme.secondary
        observedSecondaryContainer = MaterialTheme.colorScheme.secondaryContainer
        observedErrorContainer = MaterialTheme.colorScheme.errorContainer
        observedIsDark = controller.isDarkTheme
        Column {
          Button(
            onClick = controller.onToggleLightDark,
            modifier = Modifier.testTag("test_theme_toggle_trigger"),
          ) {
            Text(if (controller.isDarkTheme) "Dark" else "Light")
          }
          StudioScreen(
            formState = StudioFormState(),
            settings = AppSettings(themeMode = mode),
            apiKeys = emptyList(),
            sessionState = TranslationSessionState(),
            onInputModeChange = {},
            onMovieNameChange = {},
            onReleaseYearChange = {},
            onSeriesNameChange = {},
            onSeasonChange = {},
            onSingleSrtPicked = {},
            onMultipleSrtPicked = {},
            onRemoveFile = {},
            onMoveSeriesFile = { _, _ -> },
            onClearFiles = {},
            onStartTranslation = {},
            onAbortTranslation = {},
            onClearLogs = {},
            onNavigateToKeys = {},
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    assertTrue(observedIsDark)
    assertEquals(StudioMidnight, observedBgColor)
    assertEquals(CloverMintAqua, observedPrimaryColor)
    assertEquals(CloverYellow, observedSecondaryColor)
    composeTestRule.onNodeWithTag("hero_theme_toggle_chip").assertDoesNotExist()
    composeTestRule.onNodeWithTag("test_theme_toggle_trigger").assertTextContains("Dark")

    composeTestRule.onNodeWithTag("test_theme_toggle_trigger").performClick()
    composeTestRule.waitForIdle()

    assertEquals(false, observedIsDark)
    assertEquals(LightCanvas, observedBgColor)
    assertEquals(CloverEmerald, observedPrimaryColor)
    assertEquals(CloverYellowTint, observedSecondaryContainer)
    assertEquals(CloverCoralTint, observedErrorContainer)
    composeTestRule.onNodeWithTag("test_theme_toggle_trigger").assertTextContains("Light")
  }

  @Test
  fun `studio hero banner displays redesigned branding with active engine badge instead of key pool`() {
    var engineSettingsClicked = false
    composeTestRule.setContent {
      BSubTranslatorTheme(themeMode = ThemeMode.LIGHT) {
        StudioScreen(
          formState = StudioFormState(),
          settings = AppSettings(themeMode = ThemeMode.LIGHT, apiProvider = ApiProvider.GROK),
          apiKeys = emptyList(),
          sessionState = TranslationSessionState(),
          onInputModeChange = {},
          onMovieNameChange = {},
          onReleaseYearChange = {},
          onSeriesNameChange = {},
          onSeasonChange = {},
          onSingleSrtPicked = {},
          onMultipleSrtPicked = {},
          onRemoveFile = {},
          onMoveSeriesFile = { _, _ -> },
          onClearFiles = {},
          onStartTranslation = {},
          onAbortTranslation = {},
          onClearLogs = {},
          onNavigateToEngineSettings = { engineSettingsClicked = true },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("hero_theme_toggle_chip").assertDoesNotExist()
    composeTestRule.onNodeWithTag("hero_keys_chip").assertDoesNotExist()
    composeTestRule.onNodeWithText("BSub Translator").assertIsDisplayed()
    composeTestRule.onNodeWithText("English to Bengali Subtitle Studio").assertIsDisplayed()

    val bannerBounds = composeTestRule.onNodeWithTag("hero_banner").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val engineNode = composeTestRule.onNodeWithTag("hero_engine_chip").assertIsDisplayed()
    engineNode.assertTextContains("Engine: Grok", substring = true)
    val modelNode = composeTestRule.onNodeWithTag("hero_model_chip").assertIsDisplayed()
    val chunkNode = composeTestRule.onNodeWithTag("hero_chunk_chip").assertIsDisplayed()

    val engineBounds = engineNode.fetchSemanticsNode().boundsInRoot
    val modelBounds = modelNode.fetchSemanticsNode().boundsInRoot
    val chunkBounds = chunkNode.fetchSemanticsNode().boundsInRoot
    val density = composeTestRule.density.density

    assertTrue("Expected compact hero banner <= 112dp", bannerBounds.height <= 112f * density)
    assertTrue("Expected short status pills <= 26dp, was ${engineBounds.height / density}dp", engineBounds.height <= 26f * density)
    assertTrue("Expected top pill inside banner", engineBounds.top >= bannerBounds.top)
    assertTrue("Expected bottom pill inside banner", chunkBounds.bottom <= bannerBounds.bottom)
    assertEquals(engineBounds.width, modelBounds.width, 1f)
    assertEquals(modelBounds.width, chunkBounds.width, 1f)
    assertEquals(engineBounds.height, modelBounds.height, 1f)
    assertEquals(modelBounds.height, chunkBounds.height, 1f)
    assertTrue("Expected right-side pills stacked vertically", engineBounds.top < modelBounds.top)
    assertTrue("Expected model pill above chunk pill", modelBounds.top < chunkBounds.top)

    engineNode.performClick()
    assertTrue(engineSettingsClicked)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w440dp-h900dp")
  fun `studio hero banner scales title font so BSub Translator matches subtitle text width`() {
    composeTestRule.setContent {
      BSubTranslatorTheme(themeMode = ThemeMode.LIGHT) {
        StudioScreen(
          formState = StudioFormState(),
          settings = AppSettings(themeMode = ThemeMode.LIGHT),
          apiKeys = emptyList(),
          sessionState = TranslationSessionState(),
          onInputModeChange = {},
          onMovieNameChange = {},
          onReleaseYearChange = {},
          onSeriesNameChange = {},
          onSeasonChange = {},
          onSingleSrtPicked = {},
          onMultipleSrtPicked = {},
          onRemoveFile = {},
          onMoveSeriesFile = { _, _ -> },
          onClearFiles = {},
          onStartTranslation = {},
          onAbortTranslation = {},
          onClearLogs = {},
          onNavigateToKeys = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    val density = composeTestRule.density.density
    val bannerBounds = composeTestRule.onNodeWithTag("hero_banner").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val titleBounds = composeTestRule.onNodeWithText("BSub Translator").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val subtitleBounds =
      composeTestRule.onNodeWithText("English to Bengali Subtitle Studio").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val engineBounds = composeTestRule.onNodeWithTag("hero_engine_chip").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

    assertTrue("Expected BSub Translator to sit cleanly inside banner", titleBounds.top >= bannerBounds.top)
    assertTrue("Expected subtitle to sit cleanly inside banner", subtitleBounds.bottom <= bannerBounds.bottom)
    assertTrue("Expected title stacked above subtitle", titleBounds.top < subtitleBounds.top)
    assertTrue("Expected title to stay left of right pill stack", titleBounds.right <= engineBounds.left)
    assertTrue("Expected subtitle to stay left of right pill stack", subtitleBounds.right <= engineBounds.left)
    assertTrue("Expected compact hero banner <= 112dp", bannerBounds.height <= 112f * density)

    // Verify proportional width-matching solver matches subtitle width within 1px
    val targetSubtitleWidthPx = 204f * density
    val titleChars = "BSub Translator".length
    val fittedSp =
      fitHeroTitleFontSizeSp(
        targetWidthPx = targetSubtitleWidthPx,
        maxHeightPx = 64f * density,
      ) { candidateSp ->
        val w = titleChars * 0.56f * candidateSp * density
        val h = 1.1f * candidateSp * density
        w to h
      }
    val resultingWidthPx = titleChars * 0.56f * fittedSp * density
    assertEquals(targetSubtitleWidthPx, resultingWidthPx, 1f * density)
    assertTrue("Expected scaled title font size >= 23sp, was $fittedSp", fittedSp >= 23f)
  }

  @Test
  fun `timber diagnostic tree tracks workmanager api failures redacts keys and persists log file`() =
    runBlocking {
      assertTrue(
        "Expected BSubApplication.onCreate() to plant WorkManagerDiagnosticTree",
        Timber.forest().any { it is WorkManagerDiagnosticTree },
      )

      TranslationLogger.clearDiagnosticLogs(app)
      app.subtitleTranslationRepository.clearAll()
      app.secureKeyStorage.clearAllKeys()
      app.settingsRepository.setMaxAttemptsPerKey(ParamMode.MANUAL, 1)
      app.settingsRepository.setRetryInterval(ParamMode.MANUAL, 1)

      val keyAlpha = "AIzaSySecretAlphaKeyValue1234567890"
      val keyBravo = "AIzaSySecretBravoKeyValue1234567890"
      val keyCharlie = "AIzaSySecretCharlieKeyValue12345678"
      val keyDelta = "AIzaSySecretDeltaKeyValue1234567890"

      app.secureKeyStorage.addSingleKey(keyAlpha, "Alpha")
      app.secureKeyStorage.addSingleKey(keyBravo, "Bravo")
      app.secureKeyStorage.addSingleKey(keyCharlie, "Charlie")
      app.secureKeyStorage.addSingleKey(keyDelta, "Delta")

      val jsonMedia = "application/json".toMediaType()
      val fakeGemini =
        object : GeminiApiService {
          override suspend fun generateContentWithKeyRotation(
            model: String,
            request: GeminiGenerateContentRequest,
            autoRotate: String,
          ): Response<GeminiGenerateContentResponse> =
            generateContent(model = model, apiKey = "", request = request)

          override suspend fun generateContent(
            model: String,
            apiKey: String,
            request: GeminiGenerateContentRequest,
          ): Response<GeminiGenerateContentResponse> {
            return when (apiKey) {
              keyAlpha ->
                Response.error(
                  429,
                  """{"error":{"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded for ?key=$keyAlpha"}}"""
                    .toResponseBody(jsonMedia),
                )
              keyBravo ->
                Response.error(
                  503,
                  """{"error":{"status":"UNAVAILABLE","message":"Server busy for $keyBravo"}}"""
                    .toResponseBody(jsonMedia),
                )
              keyCharlie ->
                Response.error(
                  400,
                  """{"error":{"status":"INVALID_ARGUMENT","message":"API key not valid: $keyCharlie"}}"""
                    .toResponseBody(jsonMedia),
                )
              else ->
                Response.success(
                  GeminiGenerateContentResponse(
                    candidates =
                      listOf(
                        GeminiCandidate(
                          content =
                            GeminiContent(
                              parts =
                                listOf(
                                  GeminiPart(
                                    text =
                                      """
                                      1
                                      00:00:01,000 --> 00:00:03,000
                                      হ্যালো পৃথিবী।
                                      """.trimIndent()
                                  )
                                )
                            ),
                          finishReason = "STOP",
                        )
                      )
                  )
                )
            }
          }
        }

      app.overrideGeminiServiceForTesting(fakeGemini)

      val sampleSrt =
        """
        1
        00:00:01,000 --> 00:00:03,000
        Hello world.
        """.trimIndent()

      val worker =
        TestListenableWorkerBuilder<SubtitleTranslationWorker>(app)
          .setInputData(
            workDataOf(
              SubtitleTranslationWorker.KEY_SRT_CONTENT to sampleSrt,
              SubtitleTranslationWorker.KEY_FILE_NAME to "diagnostic_test.srt",
              SubtitleTranslationWorker.KEY_ROOM_JOB_ID to "timber_test_job",
            )
          )
          .build()

      val result = worker.doWork()
      assertTrue("Expected worker success after key rotation, got $result", result is ListenableWorker.Result.Success)

      val events = TranslationLogger.errorEvents.value
      assertTrue(events.any { it.category == ErrorCategory.API_QUOTA_EXHAUSTED && it.httpCode == 429 })
      assertTrue(events.any { it.category == ErrorCategory.API_RETRYABLE_ERROR && it.httpCode == 503 })
      assertTrue(events.any { it.category == ErrorCategory.API_INVALID_KEY && it.httpCode == 400 })
      assertTrue(events.all { it.fileName == "diagnostic_test.srt" && !it.workId.isNullOrBlank() })

      val persistedLog = TranslationLogger.readPersistedErrorLog(app)
      assertTrue(persistedLog.contains("API_QUOTA_EXHAUSTED"))
      assertTrue(persistedLog.contains("API_RETRYABLE_ERROR"))
      assertTrue(persistedLog.contains("API_INVALID_KEY"))
      assertFalse("Raw API key Alpha leaked into persisted log!", persistedLog.contains(keyAlpha))
      assertFalse("Raw API key Bravo leaked into persisted log!", persistedLog.contains(keyBravo))
      assertFalse("Raw API key Charlie leaked into persisted log!", persistedLog.contains(keyCharlie))
    }

  @Test
  fun `timber diagnostic tree captures workmanager srt parse and execution failures`() =
    runBlocking {
      TranslationLogger.clearDiagnosticLogs(app)

      val worker =
        TestListenableWorkerBuilder<SubtitleTranslationWorker>(app)
          .setInputData(
            workDataOf(
              SubtitleTranslationWorker.KEY_SRT_CONTENT to "not a valid subtitle file",
              SubtitleTranslationWorker.KEY_FILE_NAME to "broken_input.srt",
            )
          )
          .build()

      val result = worker.doWork()
      assertTrue("Expected Result.Failure for invalid SRT", result is ListenableWorker.Result.Failure)

      val events = TranslationLogger.errorEvents.value
      assertTrue(
        "Expected SRT_PARSE_ERROR event, got $events",
        events.any {
          it.category == ErrorCategory.SRT_PARSE_ERROR && it.fileName == "broken_input.srt"
        },
      )
      assertTrue(
        "Expected WORKER_EXECUTION_ERROR event, got $events",
        events.any { it.category == ErrorCategory.WORKER_EXECUTION_ERROR },
      )
    }

  @Test
  fun `api key rotation manager rotates datastore keys on retrofit rate limit and persists active cursor`() =
    runBlocking {
      app.secureKeyStorage.clearAllKeys()

      val key1 = "AIzaSyRateLimitedKeyOne1234567890"
      val key2 = "AIzaSyInvalidKeyTwo12345678901234"
      val key3 = "AIzaSyHealthyKeyThree123456789012"

      app.secureKeyStorage.addSingleKey(key1, "Primary")
      app.secureKeyStorage.addSingleKey(key2, "Backup")
      app.secureKeyStorage.addSingleKey(key3, "Reserve")

      val attemptedKeys = mutableListOf<String>()
      val jsonMedia = "application/json".toMediaType()
      val retrofitService =
        object : GeminiApiService {
          override suspend fun generateContentWithKeyRotation(
            model: String,
            request: GeminiGenerateContentRequest,
            autoRotate: String,
          ): Response<GeminiGenerateContentResponse> =
            generateContent(model = model, apiKey = "", request = request)

          override suspend fun generateContent(
            model: String,
            apiKey: String,
            request: GeminiGenerateContentRequest,
          ): Response<GeminiGenerateContentResponse> {
            attemptedKeys.add(apiKey)
            return when (apiKey) {
              key1 ->
                Response.error(
                  429,
                  """{"error":{"status":"RESOURCE_EXHAUSTED","message":"Rate limit exceeded"}}"""
                    .toResponseBody(jsonMedia),
                )
              key2 ->
                Response.error(
                  401,
                  """{"error":{"status":"PERMISSION_DENIED","message":"Invalid API key"}}"""
                    .toResponseBody(jsonMedia),
                )
              else ->
                Response.success(
                  GeminiGenerateContentResponse(
                    candidates =
                      listOf(
                        GeminiCandidate(
                          content =
                            GeminiContent(
                              parts =
                                listOf(
                                  GeminiPart(
                                    text = "1\n00:00:01,000 --> 00:00:02,000\nসব ঠিক আছে।"
                                  )
                                )
                            ),
                          finishReason = "STOP",
                        )
                      )
                  )
                )
            }
          }
        }

      val rotationManager =
        ApiKeyRotationManager(
          keyStorage = app.secureKeyStorage,
          geminiApiClient = GeminiApiClient(customService = retrofitService),
        )

      val result =
        rotationManager.translateBatchWithAutoRotation(
          settings = AppSettings(),
          inputMode = InputMode.MOVIE,
          title = "Arrival",
          secondaryMeta = "2016",
          batchSrtPayload = "1\n00:00:01,000 --> 00:00:02,000\nEverything is fine.",
          onRetryDelay = { _, _ -> },
        )

      assertTrue("Expected Success after rotating past key1 and key2, got $result", result is RotationExecutionResult.Success)
      val success = result as RotationExecutionResult.Success
      assertEquals(3, success.usedKeyNumber)
      assertEquals("Reserve", success.usedKeyLabel)
      assertEquals(listOf(key1, key2, key3), attemptedKeys)

      // Verify DataStore persisted updated key statuses and active cursor index = 2 (Reserve)
      assertEquals(2, app.secureKeyStorage.getActiveKeyIndex())
      val storedKeys = app.secureKeyStorage.getKeysSnapshot()
      assertEquals(ApiKeyStatus.QUOTA_EXHAUSTED, storedKeys[0].status)
      assertEquals(ApiKeyStatus.INVALID, storedKeys[1].status)
      assertEquals(ApiKeyStatus.ACTIVE, storedKeys[2].status)

      // Subsequent session starts from persisted active cursor (index 2 -> Reserve) without retrying key2 (INVALID)
      val nextSession = rotationManager.prepareSession()
      assertEquals(2, nextSession.currentIndex)
      assertTrue(1 in nextSession.invalidIndices)
    }

  @Test
  fun `notification foreground info uses monochrome clover app icon drawable`() = runBlocking {
    val drawable = ContextCompat.getDrawable(app, R.drawable.ic_notification_app_icon)
    assertTrue("Expected ic_notification_app_icon drawable to load", drawable != null)

    val worker = TestListenableWorkerBuilder<SubtitleTranslationWorker>(app).build()
    val foregroundInfo = worker.getForegroundInfo()
    assertEquals(
      R.drawable.ic_notification_app_icon,
      foregroundInfo.notification.smallIcon.resId,
    )
    assertEquals(0xFF006D5B.toInt(), foregroundInfo.notification.color)
  }

  @Test
  fun `subtitle translation service parses segments prepares gemini requests and rotates keys`() =
    runBlocking {
      app.secureKeyStorage.clearAllKeys()
      app.settingsRepository.setChunkSize(ParamMode.MANUAL, 2)
      app.settingsRepository.setMaxAttemptsPerKey(ParamMode.MANUAL, 1)
      app.settingsRepository.setRetryInterval(ParamMode.MANUAL, 1)

      val keyA = "AIzaSyServiceKeyA_1234567890"
      val keyB = "AIzaSyServiceKeyB_1234567890"
      app.secureKeyStorage.addSingleKey(keyA, "Primary")
      app.secureKeyStorage.addSingleKey(keyB, "Backup")
      app.secureKeyStorage.setActiveKeyIndex(0)

      val rawSrt =
        """
        1
        00:00:01,000 --> 00:00:02,500
        Good morning.

        2
        00:00:03,000 --> 00:00:04,500
        How is everything?

        3
        00:00:05,000 --> 00:00:06,500
        All is ready.
        """.trimIndent()

      val seenRequests = mutableListOf<Pair<String, String>>()
      val mockGeminiService =
        object : GeminiApiService {
          override suspend fun generateContentWithKeyRotation(
            model: String,
            request: GeminiGenerateContentRequest,
            autoRotate: String,
          ): Response<GeminiGenerateContentResponse> =
            generateContent(model = model, apiKey = "", request = request)

          override suspend fun generateContent(
            model: String,
            apiKey: String,
            request: GeminiGenerateContentRequest,
          ): Response<GeminiGenerateContentResponse> {
            val promptText = request.contents.last().parts.first().text.orEmpty()
            seenRequests.add(apiKey to promptText)

            if (apiKey == keyA) {
              return Response.error(
                429,
                """{"error":{"status":"RESOURCE_EXHAUSTED","message":"Rate limit hit"}}"""
                  .toResponseBody("application/json".toMediaType()),
              )
            }

            val responseSrt =
              if (promptText.contains("Good morning.")) {
                """
                1
                00:00:01,000 --> 00:00:02,500
                শুভ সকাল।

                2
                00:00:03,000 --> 00:00:04,500
                সবকিছু কেমন চলছে?
                """.trimIndent()
              } else {
                """
                3
                00:00:05,000 --> 00:00:06,500
                সবকিছু প্রস্তুত।
                """.trimIndent()
              }

            return Response.success(
              GeminiGenerateContentResponse(
                candidates =
                  listOf(
                    GeminiCandidate(
                      content = GeminiContent(parts = listOf(GeminiPart(text = responseSrt))),
                      finishReason = "STOP",
                    )
                  )
              )
            )
          }
        }

      val apiClient = GeminiApiClient(customService = mockGeminiService)
      val rotationManager =
        ApiKeyRotationManager(
          keyStorage = app.secureKeyStorage,
          geminiApiClient = apiClient,
        )
      val service =
        SubtitleTranslationService(
          settingsRepository = app.settingsRepository,
          fileStorage = app.subtitleFileStorage,
          geminiApiClient = apiClient,
          keyRotationManager = rotationManager,
        )

      val settingsSnapshot = app.settingsRepository.getSettingsSnapshot()
      val plan = service.parseAndSegment(rawSrt, chunkSize = 2, fileName = "demo.srt")
      assertEquals(3, plan.totalBlocks)
      assertEquals(2, plan.segments.size)
      assertEquals(2, plan.segments[0].blocks.size)
      assertEquals(1, plan.segments[1].blocks.size)

      val prepared =
        service.prepareTranslationRequest(
          segment = plan.segments[0],
          settings = settingsSnapshot,
          inputMode = InputMode.MOVIE,
          title = "Demo Movie",
          secondaryMeta = "2026",
          fileName = "demo.srt",
        )
      assertTrue(
        prepared.requestBody.contents.last().parts.first().text.orEmpty().contains("Good morning.")
      )

      val serviceResult =
        service.translateSrtContentWithRotation(
          rawSrtContent = rawSrt,
          fileName = "demo.srt",
          inputMode = InputMode.MOVIE,
          title = "Demo Movie",
          secondaryMeta = "2026",
          chunkSizeOverride = 2,
          onRetryDelay = { _, _ -> },
          onCooldownWait = { minutes ->
            throw AssertionError("Unexpected cooldown ($minutes min). Seen requests: $seenRequests")
          },
        )

      assertTrue(serviceResult is SubtitleTranslationServiceResult.Success)
      val success = serviceResult as SubtitleTranslationServiceResult.Success
      assertEquals(2, success.segmentsProcessed)
      assertEquals(3, success.translatedBlocks.size)
      assertTrue(success.reconstructedBengaliSrt.contains("00:00:01,000 --> 00:00:02,500\nশুভ সকাল।"))
      assertTrue(success.reconstructedBengaliSrt.contains("00:00:05,000 --> 00:00:06,500\nসবকিছু প্রস্তুত।"))
      assertEquals(keyA, seenRequests[0].first)
      assertEquals(keyB, seenRequests[1].first)
      assertEquals(keyB, seenRequests[2].first)
    }

  @Test
  fun `room history repository tracks processing completed failed records with filenames and timestamps`() =
    runBlocking {
      val repo = HistoryRepository(db.translationHistoryDao())

      val processingId =
        repo.createProcessingRecord(
          originalFileName = "Inception.2010.English.srt",
          expectedOutputFileName = "Inception.2010.English_BSub.srt",
          mode = InputMode.MOVIE.name,
          titleMetadata = "Inception (2010)",
          totalBlocks = 1200,
          totalLines = 3100,
          modelUsed = "gemini-2.5-flash",
          nowEpochMs = 1_700_000_000_000L,
        )

      val initial = repo.getById(processingId)!!
      assertEquals("Inception.2010.English.srt", initial.originalFileName)
      assertEquals(HistoryTranslationStatus.PROCESSING, initial.translationStatus)
      assertEquals(1_700_000_000_000L, initial.createdAtEpochMs)
      assertEquals(1_700_000_000_000L, initial.updatedAtEpochMs)

      repo.updateBatchProgress(
        historyId = processingId,
        translatedBlocks = 300,
        updatedAtEpochMs = 1_700_000_030_000L,
      )
      assertEquals(300, repo.getById(processingId)!!.translatedBlocks)

      repo.markCompleted(
        historyId = processingId,
        outputFileName = "Inception.2010.English_BSub.srt",
        savedPath = "Downloads/BSub Translator/Inception.2010.English_BSub.srt",
        contentUri = "content://downloads/1",
        localBackupPath = "/tmp/inception_bsub.srt",
        translatedBlocks = 1200,
        fileSizeBytes = 42_000L,
        updatedAtEpochMs = 1_700_000_060_000L,
      )

      val failedId =
        repo.createProcessingRecord(
          originalFileName = "Andor_S02E01.srt",
          expectedOutputFileName = "Andor_S02E01_BSub.srt",
          mode = InputMode.SERIES.name,
          titleMetadata = "Andor • S02",
          totalBlocks = 500,
          totalLines = 1200,
          modelUsed = "gemini-2.5-flash",
          nowEpochMs = 1_700_000_100_000L,
        )
      repo.markFailed(
        historyId = failedId,
        translatedBlocks = 0,
        errorMessage = "Quota exhausted on all keys",
        updatedAtEpochMs = 1_700_000_120_000L,
      )

      assertEquals(2, repo.totalCount.first())
      assertEquals(1, repo.observeCountByStatus(HistoryTranslationStatus.COMPLETED).first())
      assertEquals(1, repo.observeCountByStatus(HistoryTranslationStatus.FAILED).first())

      val completedList = repo.observeByStatus(HistoryTranslationStatus.COMPLETED).first()
      assertEquals(1, completedList.size)
      assertEquals("Inception.2010.English.srt", completedList[0].originalFileName)
      assertEquals(1_700_000_060_000L, completedList[0].updatedAtEpochMs)

      val searchMatches = repo.searchHistory("Andor").first()
      assertEquals(1, searchMatches.size)
      assertEquals(HistoryTranslationStatus.FAILED, searchMatches[0].translationStatus)
      assertEquals("Quota exhausted on all keys", searchMatches[0].errorMessage)

      repo.deleteRecord(failedId)
      assertEquals(1, repo.totalCount.first())
    }

  @Test
  fun `bsub database migration 2 to 3 preserves existing history and populates status and timestamp`() =
    runBlocking {
      val dbName = "migration_2_3_test.db"
      app.deleteDatabase(dbName)
      val sqlite = app.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
      sqlite.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `translation_history` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `originalFileName` TEXT NOT NULL,
          `outputFileName` TEXT NOT NULL,
          `savedPath` TEXT NOT NULL,
          `contentUri` TEXT NOT NULL,
          `localBackupPath` TEXT NOT NULL,
          `mode` TEXT NOT NULL,
          `titleMetadata` TEXT NOT NULL,
          `totalBlocks` INTEGER NOT NULL,
          `translatedBlocks` INTEGER NOT NULL,
          `totalLines` INTEGER NOT NULL,
          `fileSizeBytes` INTEGER NOT NULL,
          `modelUsed` TEXT NOT NULL,
          `status` TEXT NOT NULL,
          `createdAtEpochMs` INTEGER NOT NULL
        )
        """.trimIndent()
      )
      sqlite.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `subtitle_translations` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `jobId` TEXT NOT NULL,
          `fileName` TEXT NOT NULL,
          `blockIndex` INTEGER NOT NULL,
          `timestamp` TEXT NOT NULL,
          `originalEnglishText` TEXT NOT NULL,
          `translatedBengaliText` TEXT,
          `status` TEXT NOT NULL,
          `updatedAtEpochMs` INTEGER NOT NULL
        )
        """.trimIndent()
      )
      sqlite.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_subtitle_translations_jobId_blockIndex` ON `subtitle_translations` (`jobId`, `blockIndex`)"
      )
      sqlite.execSQL(
        """
        INSERT INTO `translation_history` VALUES
        (1, 'movie.srt', 'movie_BSub.srt', 'Downloads/movie_BSub.srt', '', '/tmp/a.srt', 'MOVIE', 'Movie', 100, 100, 250, 4096, 'gemini-2.5-flash', 'Completed', 1700000000000),
        (2, 'ep1.srt', 'ep1_partial.srt', 'Downloads/ep1_partial.srt', '', '/tmp/b.srt', 'SERIES', 'Series', 200, 80, 500, 2048, 'gemini-2.5-flash', 'Partial (80/200)', 1700000050000)
        """.trimIndent()
      )
      sqlite.version = 2
      sqlite.close()

      val migratedDb =
        Room.databaseBuilder(app, BSubDatabase::class.java, dbName)
          .addMigrations(
            BSubDatabase.MIGRATION_1_2,
            BSubDatabase.MIGRATION_2_3,
            BSubDatabase.MIGRATION_3_4,
            BSubDatabase.MIGRATION_4_5,
          )
          .allowMainThreadQueries()
          .build()

      try {
        val items = migratedDb.translationHistoryDao().getAllHistory().first()
        assertEquals(2, items.size)
        val partial = items.first { it.id == 2L }
        val completed = items.first { it.id == 1L }
        assertEquals(HistoryTranslationStatus.PARTIAL, partial.translationStatus)
        assertEquals(1_700_000_050_000L, partial.updatedAtEpochMs)
        assertEquals(HistoryTranslationStatus.COMPLETED, completed.translationStatus)
        assertEquals(1_700_000_000_000L, completed.updatedAtEpochMs)

        val subtitleHistoryItems = migratedDb.subtitleHistoryDao().getAllHistory().first()
        assertEquals(2, subtitleHistoryItems.size)
        assertTrue(subtitleHistoryItems.any { it.originalFileName == "movie.srt" && it.status == HistoryTranslationStatus.COMPLETED })
        assertTrue(subtitleHistoryItems.any { it.originalFileName == "ep1.srt" && it.status == HistoryTranslationStatus.PARTIAL })

        // Verify v5 API usage tables are created and writable after migration
        migratedDb.apiUsageDao().upsertKeyStat(
          ApiKeyUsageStat(
            keyId = "migrated-key-1",
            keyLabel = "Key 1",
            provider = ApiProvider.GEMINI.name,
            successfulRequests = 3,
            rateLimitHits = 1,
          )
        )
        val stats = migratedDb.apiUsageDao().observeAllKeyStats().first()
        assertEquals(1, stats.size)
        assertEquals(3, stats.first().successfulRequests)
        assertEquals(1, stats.first().rateLimitHits)
      } finally {
        migratedDb.close()
        app.deleteDatabase(dbName)
      }
    }

  @Test
  fun `history screen displays original filenames timestamps and filters by status`() {
    val sampleItems =
      listOf(
        TranslationHistoryEntity(
          id = 1L,
          originalFileName = "Dune.Part.One.srt",
          outputFileName = "Dune.Part.One_BSub.srt",
          savedPath = "Downloads/BSub Translator/Dune.Part.One_BSub.srt",
          localBackupPath = "/tmp/dune.srt",
          mode = "MOVIE",
          titleMetadata = "Dune (2021)",
          totalBlocks = 100,
          translatedBlocks = 100,
          totalLines = 220,
          fileSizeBytes = 5000L,
          modelUsed = "gemini-2.5-flash",
          status = "Completed",
          translationStatus = HistoryTranslationStatus.COMPLETED,
          createdAtEpochMs = 1_700_000_000_000L,
          updatedAtEpochMs = 1_700_000_050_000L,
        ),
        TranslationHistoryEntity(
          id = 2L,
          originalFileName = "Broken_File.srt",
          outputFileName = "",
          mode = "MOVIE",
          titleMetadata = "Broken Movie",
          totalBlocks = 50,
          translatedBlocks = 0,
          totalLines = 100,
          fileSizeBytes = 0L,
          modelUsed = "gemini-2.5-flash",
          status = "Failed",
          translationStatus = HistoryTranslationStatus.FAILED,
          errorMessage = "Network timeout",
          createdAtEpochMs = 1_700_000_100_000L,
          updatedAtEpochMs = 1_700_000_110_000L,
        ),
      )

    composeTestRule.setContent {
      BSubTranslatorTheme {
        HistoryScreen(
          historyItems = sampleItems,
          previewState = null,
          onOpenPreview = {},
          onClosePreview = {},
          onReSaveToDownloads = {},
          onExportToUri = { _, _ -> },
          onCreateShareIntent = { null },
          onDeleteItem = {},
        )
      }
    }

    composeTestRule.onNodeWithText("Source: Dune.Part.One.srt").assertIsDisplayed()

    composeTestRule.onNodeWithTag("history_filter_failed").performClick()
    composeTestRule.onNodeWithText("Source: Broken_File.srt").assertIsDisplayed()
    composeTestRule.onNodeWithText("Network timeout").assertIsDisplayed()
    composeTestRule.onNodeWithText("Source: Dune.Part.One.srt").assertDoesNotExist()

    composeTestRule.onNodeWithTag("history_filter_completed").performClick()
    composeTestRule.onNodeWithText("Source: Dune.Part.One.srt").assertIsDisplayed()
    composeTestRule.onNodeWithText("Source: Broken_File.srt").assertDoesNotExist()
  }

  @Test
  fun `history screen empty state renders custom empty archive illustration and headline`() {
    val emptyDrawable = ContextCompat.getDrawable(app, R.drawable.img_history_empty_state)
    assertTrue("Expected img_history_empty_state drawable to exist", emptyDrawable != null)

    composeTestRule.setContent {
      BSubTranslatorTheme {
        HistoryScreen(
          historyItems = emptyList(),
          onOpenPreview = {},
          onReSaveToDownloads = {},
          onExportToUri = { _, _ -> },
          onCreateShareIntent = { null },
          onDeleteItem = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("history_empty_image").assertIsDisplayed()
    composeTestRule.onNodeWithText("No Translated Files Yet").assertIsDisplayed()
  }

  @Test
  fun `gemini key rotation interceptor rotates through keys on 429 and 401 via retrofit`() =
    runBlocking {
      app.secureKeyStorage.clearAllKeys()
      val key1 = "AIzaSyInterceptorKeyOne_12345"
      val key2 = "AIzaSyInterceptorKeyTwo_12345"
      val key3 = "AIzaSyInterceptorKeyThree_123"
      app.secureKeyStorage.addSingleKey(key1, "QuotaKey")
      app.secureKeyStorage.addSingleKey(key2, "InvalidKey")
      app.secureKeyStorage.addSingleKey(key3, "HealthyKey")
      app.secureKeyStorage.setActiveKeyIndex(0)

      val attemptedKeys = mutableListOf<String>()
      val sawInternalHeader = mutableListOf<Boolean>()
      val mockTransportInterceptor = Interceptor { chain ->
        val req = chain.request()
        val keyParam = req.url.queryParameter("key").orEmpty()
        attemptedKeys.add(keyParam)
        sawInternalHeader.add(req.header(GeminiKeyRotationInterceptor.HEADER_AUTO_ROTATE) != null)

        when (keyParam) {
          key1 ->
            OkHttpResponse.Builder()
              .request(req)
              .protocol(Protocol.HTTP_1_1)
              .code(429)
              .message("Too Many Requests")
              .body(
                """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Quota limit exceeded"}}"""
                  .toResponseBody("application/json".toMediaType())
              )
              .build()
          key2 ->
            OkHttpResponse.Builder()
              .request(req)
              .protocol(Protocol.HTTP_1_1)
              .code(401)
              .message("Unauthorized")
              .body(
                """{"error":{"code":401,"status":"API_KEY_INVALID","message":"API key not valid"}}"""
                  .toResponseBody("application/json".toMediaType())
              )
              .build()
          key3 ->
            OkHttpResponse.Builder()
              .request(req)
              .protocol(Protocol.HTTP_1_1)
              .code(200)
              .message("OK")
              .body(
                """{"candidates":[{"content":{"parts":[{"text":"1\n00:00:01,000 --> 00:00:02,000\nহ্যালো বিশ্ব"}]},"finishReason":"STOP"}]}"""
                  .toResponseBody("application/json".toMediaType())
              )
              .build()
          else -> error("Unexpected API key: $keyParam")
        }
      }

      val client =
        GeminiApiClient(
          keyStorage = app.secureKeyStorage,
          extraInterceptors = listOf(mockTransportInterceptor),
        )

      val result =
        client.translateBatchWithInterceptorRotation(
          model = "gemini-2.5-flash",
          temperature = 0.2f,
          thinkingBudget = 0,
          systemPrompt = "Translate to Bengali",
          fewShotExamples = emptyList(),
          inputMode = InputMode.MOVIE,
          title = "Demo",
          secondaryMeta = "2026",
          batchSrtPayload = "1\n00:00:01,000 --> 00:00:02,000\nHello world",
          batchIndex = 1,
          totalBatches = 1,
          fileName = "demo.srt",
        )

      assertTrue(result is GeminiCallResult.Success)
      assertTrue((result as GeminiCallResult.Success).translatedText.contains("হ্যালো বিশ্ব"))
      assertEquals(listOf(key1, key2, key3), attemptedKeys)
      assertTrue(sawInternalHeader.none { it })

      val keysAfter = app.secureKeyStorage.getKeysSnapshot()
      assertEquals(ApiKeyStatus.QUOTA_EXHAUSTED, keysAfter[0].status)
      assertEquals(ApiKeyStatus.INVALID, keysAfter[1].status)
      assertEquals(ApiKeyStatus.ACTIVE, keysAfter[2].status)
      assertEquals(2, app.secureKeyStorage.getActiveKeyIndex())
    }

  @Test
  fun `subtitle history entity and dao insert update search and query by status`() = runBlocking {
    val dao = db.subtitleHistoryDao()
    val repo = SubtitleHistoryRepository(dao)

    val rowId =
      repo.insert(
        SubtitleHistory(
          originalFileName = "Interstellar.2014.srt",
          outputFileName = "Interstellar.2014_BSub.srt",
          mode = InputMode.MOVIE.name,
          titleMetadata = "Interstellar (2014)",
          totalBlocks = 850,
          translatedBlocks = 0,
          totalLines = 1900,
          modelUsed = "gemini-2.5-flash",
          status = HistoryTranslationStatus.PROCESSING,
          processedAtEpochMs = 1_700_000_000_000L,
        )
      )

    val inserted = repo.getById(rowId)!!
    assertEquals("Interstellar.2014.srt", inserted.originalFileName)
    assertEquals(HistoryTranslationStatus.PROCESSING, inserted.status)

    repo.updateProcessingResult(
      id = rowId,
      status = HistoryTranslationStatus.COMPLETED,
      statusLabel = "Completed",
      translatedBlocks = 850,
      outputFileName = "Interstellar.2014_BSub.srt",
      savedPath = "Downloads/BSub Translator/Interstellar.2014_BSub.srt",
      fileSizeBytes = 38_400L,
      updatedAtEpochMs = 1_700_000_050_000L,
    )

    val completedRows = repo.observeByStatus(HistoryTranslationStatus.COMPLETED).first()
    assertEquals(1, completedRows.size)
    assertEquals(850, completedRows[0].translatedBlocks)
    assertEquals(1_700_000_050_000L, completedRows[0].updatedAtEpochMs)

    val matchedByName = repo.searchByFileName("Interstellar").first()
    assertEquals(1, matchedByName.size)
    assertEquals(1, repo.totalCount.first())

    repo.deleteById(rowId)
    assertEquals(0, repo.totalCount.first())
  }

  @Test
  fun `workmanager worker records failed subtitle history in room and single file scheduler enqueues work`() =
    runBlocking {
      app.historyRepository.clearAll()

      val badWorker =
        TestListenableWorkerBuilder<SubtitleTranslationWorker>(app)
          .setInputData(
            workDataOf(
              SubtitleTranslationWorker.KEY_SRT_CONTENT to "not valid srt content",
              SubtitleTranslationWorker.KEY_FILE_NAME to "corrupt_file.srt",
              SubtitleTranslationWorker.KEY_INPUT_MODE to InputMode.MOVIE.name,
              SubtitleTranslationWorker.KEY_TITLE to "Corrupt Film",
            )
          )
          .build()

      val failureResult = badWorker.doWork()
      assertTrue(failureResult is ListenableWorker.Result.Failure)

      val failedHistory =
        app.subtitleHistoryRepository.observeByOriginalFileName("corrupt_file.srt").first()
      assertEquals(1, failedHistory.size)
      assertEquals(HistoryTranslationStatus.FAILED, failedHistory[0].status)
      assertTrue(failedHistory[0].errorMessage?.isNotBlank() == true)

      val workId =
        TranslationWorkScheduler.enqueueSingleFileTranslation(
          context = app,
          rawSrtContent = "1\n00:00:01,000 --> 00:00:02,000\nHello",
          fileName = "single_job.srt",
          inputMode = InputMode.MOVIE,
          title = "Single Job",
        )
      val info = WorkManager.getInstance(app).getWorkInfoById(workId).get()
      assertEquals(WorkInfo.State.ENQUEUED, info?.state)
      assertTrue(info?.tags?.contains(TranslationWorkScheduler.TAG_SUBTITLE_TRANSLATION) == true)
    }

  @Test
  fun `studio screen displays file queue badges workmanager multi-file dashboard and inline bulk key import`() {
    val file1 =
      SelectedSubtitleFile(
        id = "f1",
        uriString = "content://subtitles/ep01.srt",
        fileName = "ep01.srt",
        fileSizeBytes = 1024L,
        totalBlocks = 10,
        totalLines = 15,
        blocks = listOf(SrtBlock(1, "00:00:01,000 --> 00:00:02,000", listOf("Hello"))),
      )
    val file2 =
      SelectedSubtitleFile(
        id = "f2",
        uriString = "content://subtitles/ep02.srt",
        fileName = "ep02.srt",
        fileSizeBytes = 2048L,
        totalBlocks = 10,
        totalLines = 18,
        blocks = listOf(SrtBlock(1, "00:00:01,000 --> 00:00:02,000", listOf("World"))),
      )
    val file3 =
      SelectedSubtitleFile(
        id = "f3",
        uriString = "content://subtitles/ep03.srt",
        fileName = "ep03.srt",
        fileSizeBytes = 1536L,
        totalBlocks = 10,
        totalLines = 14,
        blocks = listOf(SrtBlock(1, "00:00:01,000 --> 00:00:02,000", listOf("End"))),
      )

    var importedBulkPayload = ""

    composeTestRule.setContent {
      BSubTranslatorTheme {
        StudioScreen(
          formState =
            StudioFormState(
              inputMode = InputMode.SERIES,
              seriesName = "Andor",
              season = "S02",
              seriesFiles = listOf(file1, file2, file3),
            ),
          settings = AppSettings(),
          apiKeys = emptyList(),
          sessionState =
            TranslationSessionState(
              status = TranslationStatus.RUNNING,
              totalFiles = 3,
              completedFiles = 1,
              currentFileIndex = 2,
              currentFileName = "ep02.srt",
              totalBlocksAllFiles = 30,
              completedBlocksAllFiles = 15,
            ),
          roomProgress =
            RoomTranslationProgress(
              totalBlocks = 30,
              translatedBlocks = 15,
              pendingBlocks = 15,
              activeFileName = "ep02.srt",
            ),
          fileProgressList =
            listOf(
              FileTranslationProgress("job_1", "ep01.srt", 10, 10, 0),
              FileTranslationProgress("job_2", "ep02.srt", 10, 5, 5),
              FileTranslationProgress("job_3", "ep03.srt", 10, 0, 10),
            ),
          onInputModeChange = {},
          onMovieNameChange = {},
          onReleaseYearChange = {},
          onSeriesNameChange = {},
          onSeasonChange = {},
          onSingleSrtPicked = {},
          onMultipleSrtPicked = {},
          onRemoveFile = {},
          onMoveSeriesFile = { _, _ -> },
          onClearFiles = {},
          onStartTranslation = {},
          onAbortTranslation = {},
          onClearLogs = {},
          onImportBulkKeys = { importedBulkPayload = it },
          onImportKeysFile = {},
          onNavigateToKeys = {},
        )
      }
    }

    composeTestRule.waitForIdle()

    // 1. Visual status indicators and badges on file list items
    composeTestRule.onNodeWithTag("file_status_indicator_0").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("file_status_badge_0").assertTextEquals("Completed")
    composeTestRule.onNodeWithTag("file_status_indicator_1").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("file_status_badge_1").assertTextEquals("In Progress")
    composeTestRule.onNodeWithTag("file_status_indicator_2").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("file_status_badge_2").assertTextEquals("Pending")

    // 3. Gemini API Key Pool card is removed from StudioScreen (lives in Keys tab)
    composeTestRule.onNodeWithTag("studio_bulk_keys_card").assertDoesNotExist()
    composeTestRule.onNodeWithTag("studio_import_keys_file_button").assertDoesNotExist()

    // 4. Multi-file WorkManager progress dashboard
    composeTestRule.onNodeWithTag("workmanager_file_dashboard").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("dashboard_file_row_0").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("dashboard_file_row_1").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("dashboard_file_row_2").performScrollTo().assertIsDisplayed()
    assertEquals("", importedBulkPayload)
  }

  @Test
  fun `api keys screen bulk key input and file storage read helpers work for multi-key rotation`() =
    runBlocking {
      var capturedBulk = ""
      var capturedProvider: ApiProvider? = null
      composeTestRule.setContent {
        BSubTranslatorTheme {
          ApiKeysScreen(
            apiKeys = emptyList(),
            isTranslationRunning = false,
            onSaveBulkKeys = { raw, provider ->
              capturedBulk = raw
              capturedProvider = provider
            },
            onAddSingleKey = { _, _, _ -> },
            onEditKey = { _, _, _ -> },
            onRemoveKey = {},
            onClearAllKeys = {},
            onResetAllStatuses = {},
          )
        }
      }

      val keyInput = "GEMINI_1=AIzaSyBulkAlphaKey123456\nAIzaSyBulkBravoKey123456"
      composeTestRule.onNodeWithTag("import_keys_file_button").performScrollTo().assertIsDisplayed()
      composeTestRule.onNodeWithTag("bulk_keys_input").performScrollTo().performTextInput(keyInput)
      composeTestRule.onNodeWithTag("bulk_keys_detected_count").assertTextContains("2 key(s) detected", substring = true)
      composeTestRule.onNodeWithTag("save_bulk_keys_button").performScrollTo().performClick()
      assertEquals(keyInput, capturedBulk)
      assertEquals(ApiProvider.GEMINI, capturedProvider)

      // 2 & 5. File storage URI read + SrtFileParser class structured parsing from text file
      val tempSrt = java.io.File(app.cacheDir, "picker_sample.srt")
      tempSrt.writeText(
        "\uFEFF1\r\n00:00:01,000 --> 00:00:03,500\r\nFirst line\r\nSecond line\r\n\r\n2\r\n00:00:04,000 --> 00:00:06,000\r\nThird line\r\n",
        Charsets.UTF_8,
      )
      val fileParser = SrtFileParser()
      val document = fileParser.parseDocument(tempSrt)
      assertTrue(document.isValid)
      assertEquals(2, document.totalBlocks)
      assertEquals(3, document.totalLines)
      assertEquals("First line\nSecond line", document.items[0].content)
      assertEquals("00:00:04,000 --> 00:00:06,000", document.blocks[1].timestamp)

      val analyzedFromUri = app.subtitleFileStorage.readAndAnalyzeUri(android.net.Uri.fromFile(tempSrt))
      assertEquals("picker_sample.srt", analyzedFromUri?.fileName)
      assertEquals(2, analyzedFromUri?.totalBlocks)
      assertTrue(SubtitleFileStorage.SRT_PICKER_MIME_TYPES.contains("application/x-subrip"))
      assertEquals(2, SecureKeyStorage.parseBulkKeys(keyInput).size)
      assertTrue(tempSrt.delete())
    }

  @Test
  fun `tapping processed file in studio or history opens side-by-side english and bengali preview screen`() {
    val fileDone =
      SelectedSubtitleFile(
        id = "done1",
        uriString = "content://subtitles/dune_ep01.srt",
        fileName = "dune_ep01.srt",
        fileSizeBytes = 1024L,
        totalBlocks = 2,
        totalLines = 2,
        blocks =
          listOf(
            SrtBlock(1, "00:00:01,000 --> 00:00:03,000", listOf("Dreams are messages from the deep.")),
            SrtBlock(2, "00:00:04,000 --> 00:00:06,500", listOf("The mystery of life isn't a problem to solve.")),
          ),
      )
    val filePending =
      SelectedSubtitleFile(
        id = "pend1",
        uriString = "content://subtitles/dune_ep02.srt",
        fileName = "dune_ep02.srt",
        fileSizeBytes = 512L,
        totalBlocks = 2,
        totalLines = 2,
        blocks = listOf(SrtBlock(1, "00:00:01,000 --> 00:00:02,000", listOf("Pending line"))),
      )

    var previewedStudioFile: String? = null
    var openedHistoryItem: TranslationHistoryEntity? = null
    var closedPreview = false

    val sampleHistory =
      TranslationHistoryEntity(
        id = 10L,
        originalFileName = "dune_ep01.srt",
        outputFileName = "dune_ep01_BSub.srt",
        savedPath = "Downloads/BSub Translator/dune_ep01_BSub.srt",
        localBackupPath = "/tmp/dune_ep01_bsub.srt",
        mode = "MOVIE",
        titleMetadata = "Dune (2021)",
        totalBlocks = 2,
        translatedBlocks = 2,
        totalLines = 2,
        fileSizeBytes = 2048L,
        modelUsed = "gemini-2.5-flash",
        status = "Completed",
        translationStatus = HistoryTranslationStatus.COMPLETED,
      )

    val previewSampleState =
      SubtitlePreviewState(
        fileName = "dune_ep01_BSub.srt",
        sourceFileName = "dune_ep01.srt",
        subtitleMeta = "Dune (2021) • MOVIE",
        hasOriginalText = true,
        historyItem = sampleHistory,
        cues =
          listOf(
            SubtitleCuePair(
              index = 1,
              timestamp = "00:00:01,000 --> 00:00:03,000",
              englishText = "Dreams are messages from the deep.",
              bengaliText = "স্বপ্ন হলো গভীরের বার্তা।",
              isTranslated = true,
            ),
            SubtitleCuePair(
              index = 2,
              timestamp = "00:00:04,000 --> 00:00:06,500",
              englishText = "The mystery of life isn't a problem to solve.",
              bengaliText = "জীবনের রহস্য সমাধানের কোনো সমস্যা নয়।",
              isTranslated = true,
            ),
          ),
      )

    var activeMode by mutableStateOf("studio")

    composeTestRule.setContent {
      BSubTranslatorTheme {
        when (activeMode) {
          "studio" ->
            StudioScreen(
              formState =
                StudioFormState(
                  inputMode = InputMode.SERIES,
                  seriesName = "Dune",
                  season = "S01",
                  seriesFiles = listOf(fileDone, filePending),
                ),
              settings = AppSettings(),
              apiKeys = emptyList(),
              sessionState =
                TranslationSessionState(
                  status = TranslationStatus.RUNNING,
                  totalFiles = 2,
                  completedFiles = 1,
                  currentFileIndex = 2,
                  currentFileName = "dune_ep02.srt",
                ),
              fileProgressList =
                listOf(
                  FileTranslationProgress("j1", "dune_ep01.srt", 2, 2, 0),
                  FileTranslationProgress("j2", "dune_ep02.srt", 2, 0, 2),
                ),
              onInputModeChange = {},
              onMovieNameChange = {},
              onReleaseYearChange = {},
              onSeriesNameChange = {},
              onSeasonChange = {},
              onSingleSrtPicked = {},
              onMultipleSrtPicked = {},
              onRemoveFile = {},
              onMoveSeriesFile = { _, _ -> },
              onClearFiles = {},
              onStartTranslation = {},
              onAbortTranslation = {},
              onClearLogs = {},
              onPreviewFile = { name, _ ->
                previewedStudioFile = name
                activeMode = "preview"
              },
              onNavigateToKeys = {},
            )
          "history" ->
            HistoryScreen(
              historyItems = listOf(sampleHistory),
              onOpenPreview = { item ->
                openedHistoryItem = item
                activeMode = "preview"
              },
              onReSaveToDownloads = {},
              onExportToUri = { _, _ -> },
              onCreateShareIntent = { null },
              onDeleteItem = {},
            )
          else ->
            SubtitlePreviewScreen(
              state = previewSampleState,
              onClose = { closedPreview = true },
            )
        }
      }
    }

    composeTestRule.waitForIdle()
    // Pending row should not trigger preview on tap
    composeTestRule.onNodeWithTag("file_queue_item_1").performScrollTo().performClick()
    assertEquals(null, previewedStudioFile)

    // Processed row triggers preview
    composeTestRule.onNodeWithTag("file_queue_item_0").performScrollTo().performClick()
    assertEquals("dune_ep01.srt", previewedStudioFile)
    composeTestRule.waitForIdle()

    // Verify side-by-side preview headers & cue columns are left (English) vs right (Bengali)
    composeTestRule.onNodeWithTag("subtitle_preview_screen").assertIsDisplayed()
    val engHeaderBounds =
      composeTestRule.onNodeWithTag("preview_header_english").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val benHeaderBounds =
      composeTestRule.onNodeWithTag("preview_header_bengali").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    assertTrue(
      "Expected English column left of Bengali column",
      engHeaderBounds.left < benHeaderBounds.left && engHeaderBounds.right <= benHeaderBounds.left + 4f,
    )

    val engCue1Bounds =
      composeTestRule.onNodeWithTag("preview_english_text_1").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val benCue1Bounds =
      composeTestRule.onNodeWithTag("preview_bengali_text_1").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    assertTrue("Expected cue #1 English text left of Bengali text", engCue1Bounds.left < benCue1Bounds.left)
    composeTestRule.onNodeWithTag("preview_english_text_1").assertTextContains("Dreams are messages from the deep.", substring = true)
    composeTestRule.onNodeWithTag("preview_bengali_text_1").assertTextContains("স্বপ্ন হলো গভীরের বার্তা।", substring = true)
    composeTestRule.onNodeWithTag("preview_cue_row_2").assertIsDisplayed()

    // Search filters cue list
    composeTestRule.onNodeWithTag("preview_search_input").performTextInput("mystery")
    composeTestRule.onNodeWithTag("preview_cue_row_2").assertIsDisplayed()
    composeTestRule.onNodeWithTag("preview_cue_row_1").assertDoesNotExist()

    // Back button closes preview
    composeTestRule.onNodeWithTag("preview_back_button").performClick()
    assertTrue(closedPreview)

    // Switch to HistoryScreen and verify tapping processed history card triggers onOpenPreview
    activeMode = "history"
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("history_item_card_10").performClick()
    assertEquals(10L, openedHistoryItem?.id)
  }

  @Test
  fun `viewmodel pairs archived original english srt and translated bengali srt side-by-side after room cleared`() =
    runBlocking {
      app.subtitleTranslationRepository.clearAll()
      val originalBlocks =
        listOf(
          SrtBlock(1, "00:00:01,000 --> 00:00:02,500", listOf("Hello friend")),
          SrtBlock(2, "00:00:03,000 --> 00:00:05,000", listOf("See you tomorrow")),
        )
      val translatedSrt =
        """
        1
        00:00:01,000 --> 00:00:02,500
        হ্যালো বন্ধু

        2
        00:00:03,000 --> 00:00:05,000
        আগামীকাল দেখা হবে
        """.trimIndent()

      val saved = app.subtitleFileStorage.saveTranslatedSrt("sample_pair_BSub.srt", translatedSrt)
      app.subtitleFileStorage.saveOriginalSourceBackup(
        translatedLocalBackupPath = saved.localBackupPath,
        originalFileName = "sample_pair.srt",
        blocks = originalBlocks,
      )

      val historyItem =
        TranslationHistoryEntity(
          id = 77L,
          originalFileName = "sample_pair.srt",
          outputFileName = saved.outputFileName,
          savedPath = saved.displayPath,
          contentUri = saved.contentUri,
          localBackupPath = saved.localBackupPath,
          mode = InputMode.MOVIE.name,
          titleMetadata = "Sample Movie",
          totalBlocks = 2,
          translatedBlocks = 2,
          totalLines = 2,
          fileSizeBytes = saved.fileSizeBytes,
          modelUsed = "gemini-2.5-flash",
          status = HistoryTranslationStatus.COMPLETED.label,
          translationStatus = HistoryTranslationStatus.COMPLETED,
        )

      val viewModel = MainViewModel(app)
      viewModel.openHistoryPreview(historyItem)

      val deadline = System.currentTimeMillis() + 5000L
      while (System.currentTimeMillis() < deadline) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val current = viewModel.previewState.value
        if (current != null && !current.isLoading && current.cues.isNotEmpty()) break
        Thread.sleep(25L)
      }
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

      val loaded = viewModel.previewState.value!!
      assertFalse(loaded.isLoading)
      assertTrue(loaded.hasOriginalText)
      assertEquals(2, loaded.cues.size)
      assertEquals("Hello friend", loaded.cues[0].englishText)
      assertEquals("হ্যালো বন্ধু", loaded.cues[0].bengaliText)
      assertEquals("See you tomorrow", loaded.cues[1].englishText)
      assertEquals("আগামীকাল দেখা হবে", loaded.cues[1].bengaliText)

      viewModel.closeHistoryPreview()
      assertEquals(null, viewModel.previewState.value)
      app.subtitleFileStorage.deleteSavedFiles(saved.localBackupPath)
    }

  @Test
  @Config(sdk = [36], qualifiers = "w440dp-h900dp")
  fun `history search bar filters translated srt records by filename and subtitle content`() {
    val (savedInterstellar, savedAndor) =
      runBlocking {
        val s1 =
          app.subtitleFileStorage.saveTranslatedSrt(
            "Interstellar_BSub.srt",
            "1\n00:00:01,000 --> 00:00:03,000\nমহাকাশে ভালোবাসা সময় অতিক্রম করে।\n",
          )
        app.subtitleFileStorage.saveOriginalSourceBackup(
          translatedLocalBackupPath = s1.localBackupPath,
          originalFileName = "Interstellar.2014.srt",
          blocks = listOf(SrtBlock(1, "00:00:01,000 --> 00:00:03,000", listOf("Love transcends time and space."))),
        )

        val s2 =
          app.subtitleFileStorage.saveTranslatedSrt(
            "Andor_S01E01_BSub.srt",
            "1\n00:00:02,000 --> 00:00:04,000\nবিদ্রোহ আজ শুরু হচ্ছে।\n",
          )
        app.subtitleFileStorage.saveOriginalSourceBackup(
          translatedLocalBackupPath = s2.localBackupPath,
          originalFileName = "Andor.S01E01.srt",
          blocks = listOf(SrtBlock(1, "00:00:02,000 --> 00:00:04,000", listOf("The rebellion begins today."))),
        )
        s1 to s2
      }

    val item1 =
      TranslationHistoryEntity(
        id = 101L,
        originalFileName = "Interstellar.2014.srt",
        outputFileName = savedInterstellar.outputFileName,
        savedPath = savedInterstellar.displayPath,
        contentUri = savedInterstellar.contentUri,
        localBackupPath = savedInterstellar.localBackupPath,
        mode = InputMode.MOVIE.name,
        titleMetadata = "Interstellar (2014)",
        totalBlocks = 1,
        translatedBlocks = 1,
        totalLines = 1,
        fileSizeBytes = savedInterstellar.fileSizeBytes,
        modelUsed = "gemini-2.5-flash",
        status = HistoryTranslationStatus.COMPLETED.label,
        translationStatus = HistoryTranslationStatus.COMPLETED,
      )
    val item2 =
      TranslationHistoryEntity(
        id = 102L,
        originalFileName = "Andor.S01E01.srt",
        outputFileName = savedAndor.outputFileName,
        savedPath = savedAndor.displayPath,
        contentUri = savedAndor.contentUri,
        localBackupPath = savedAndor.localBackupPath,
        mode = InputMode.SERIES.name,
        titleMetadata = "Andor • S01",
        totalBlocks = 1,
        translatedBlocks = 1,
        totalLines = 1,
        fileSizeBytes = savedAndor.fileSizeBytes,
        modelUsed = "gemini-2.5-flash",
        status = HistoryTranslationStatus.COMPLETED.label,
        translationStatus = HistoryTranslationStatus.COMPLETED,
      )

    try {
      composeTestRule.setContent {
        BSubTranslatorTheme {
          HistoryScreen(
            historyItems = listOf(item1, item2),
            onOpenPreview = {},
            onReSaveToDownloads = {},
            onExportToUri = { _, _ -> },
            onCreateShareIntent = { null },
            onDeleteItem = {},
          )
        }
      }

      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("history_search_input").assertIsDisplayed()
      composeTestRule.onNodeWithText("Source: Interstellar.2014.srt").assertIsDisplayed()
      composeTestRule.onNodeWithText("Source: Andor.S01E01.srt").assertIsDisplayed()

      // 1. Filter by filename
      composeTestRule.onNodeWithTag("history_search_input").performTextReplacement("Andor")
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Source: Andor.S01E01.srt").assertIsDisplayed()
      composeTestRule.onNodeWithText("Source: Interstellar.2014.srt").assertDoesNotExist()

      // Clear search restores both items
      composeTestRule.onNodeWithTag("history_search_clear").performClick()
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Source: Interstellar.2014.srt").assertIsDisplayed()
      composeTestRule.onNodeWithText("Source: Andor.S01E01.srt").assertIsDisplayed()

      runBlocking {
        assertEquals(
          "মহাকাশে ভালোবাসা সময় অতিক্রম করে।",
          app.subtitleFileStorage.findContentMatchSnippet(
            savedInterstellar.localBackupPath,
            savedInterstellar.contentUri,
            "Interstellar.2014.srt",
            "ভালোবাসা",
          ),
        )
        assertEquals(
          "The rebellion begins today.",
          app.subtitleFileStorage.findContentMatchSnippet(
            savedAndor.localBackupPath,
            savedAndor.contentUri,
            "Andor.S01E01.srt",
            "rebellion",
          ),
        )
      }

      // 2. Filter by translated Bengali subtitle content
      composeTestRule.onNodeWithTag("history_search_input").performTextReplacement("ভালোবাসা")
      composeTestRule.waitUntil(timeoutMillis = 5000L) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        composeTestRule.mainClock.advanceTimeByFrame()
        try {
          composeTestRule
            .onNodeWithTag("history_content_match_101", useUnmergedTree = true)
            .assertIsDisplayed()
          true
        } catch (_: AssertionError) {
          false
        }
      }
      composeTestRule.onNodeWithText("Match: \"মহাকাশে ভালোবাসা সময় অতিক্রম করে।\"", substring = true).assertIsDisplayed()
      composeTestRule.onNodeWithText("Source: Interstellar.2014.srt").assertIsDisplayed()
      composeTestRule.onNodeWithText("Source: Andor.S01E01.srt").assertDoesNotExist()

      // 3. Filter by archived original English subtitle content
      composeTestRule.onNodeWithTag("history_search_clear").performClick()
      composeTestRule.onNodeWithTag("history_search_input").performTextReplacement("rebellion")
      composeTestRule.waitUntil(timeoutMillis = 5000L) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        composeTestRule.mainClock.advanceTimeByFrame()
        try {
          composeTestRule
            .onNodeWithTag("history_content_match_102", useUnmergedTree = true)
            .assertIsDisplayed()
          true
        } catch (_: AssertionError) {
          false
        }
      }
      composeTestRule.onNodeWithText("Match: \"The rebellion begins today.\"", substring = true).assertIsDisplayed()
      composeTestRule.onNodeWithText("Source: Andor.S01E01.srt").assertIsDisplayed()
      composeTestRule.onNodeWithText("Source: Interstellar.2014.srt").assertDoesNotExist()

      // 4. Unmatched query shows empty state + reset button
      composeTestRule.onNodeWithTag("history_search_clear").performClick()
      composeTestRule.onNodeWithTag("history_search_input").performTextReplacement("nonexistent_phrase_xyz")
      composeTestRule.onNodeWithText("No Matching Translated Files").assertIsDisplayed()
      composeTestRule.onNodeWithTag("history_reset_filters_button").performClick()
      composeTestRule.onNodeWithText("Source: Interstellar.2014.srt").assertIsDisplayed()
      composeTestRule.onNodeWithText("Source: Andor.S01E01.srt").assertIsDisplayed()
    } finally {
      runBlocking {
        app.subtitleFileStorage.deleteSavedFiles(savedInterstellar.localBackupPath)
        app.subtitleFileStorage.deleteSavedFiles(savedAndor.localBackupPath)
      }
    }
  }

  @Test
  fun `key rotation fails fast on daily quota or 404 caps rpm cooldowns and never exhausts on validation`() =
    runBlocking {
      app.secureKeyStorage.clearAllKeys()
      val key1 = "AIzaSyFastFailKey1_1234567890"
      val key2 = "AIzaSyFastFailKey2_1234567890"
      app.secureKeyStorage.addSingleKey(key1, "One")
      app.secureKeyStorage.addSingleKey(key2, "Two")
      app.secureKeyStorage.setActiveKeyIndex(0)

      val jsonMedia = "application/json".toMediaType()
      var mode = "DAILY"
      val cooldownMinutesObserved = mutableListOf<Int>()

      val fakeService =
        object : GeminiApiService {
          override suspend fun generateContentWithKeyRotation(
            model: String,
            request: GeminiGenerateContentRequest,
            autoRotate: String,
          ): Response<GeminiGenerateContentResponse> = generateContent(model, "", request)

          override suspend fun generateContent(
            model: String,
            apiKey: String,
            request: GeminiGenerateContentRequest,
          ): Response<GeminiGenerateContentResponse> {
            return when (mode) {
              "DAILY" ->
                Response.error(
                  429,
                  """{"error":{"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded for GenerateRequestsPerDay"}}"""
                    .toResponseBody(jsonMedia),
                )
              "FATAL_404" ->
                Response.error(
                  404,
                  """{"error":{"status":"NOT_FOUND","message":"Model not found"}}"""
                    .toResponseBody(jsonMedia),
                )
              "RPM_429" ->
                Response.error(
                  429,
                  """{"error":{"status":"RESOURCE_EXHAUSTED","message":"Requests per minute rate limit exceeded"}}"""
                    .toResponseBody(jsonMedia),
                )
              else ->
                Response.success(
                  GeminiGenerateContentResponse(
                    candidates =
                      listOf(
                        GeminiCandidate(
                          content =
                            GeminiContent(parts = listOf(GeminiPart(text = "invalid non-srt text"))),
                          finishReason = "STOP",
                        )
                      )
                  )
                )
            }
          }
        }

      val manager =
        ApiKeyRotationManager(
          keyStorage = app.secureKeyStorage,
          geminiApiClient = GeminiApiClient(customService = fakeService),
        )

      // 1. All keys hitting daily quota must fail immediately with 0 cooldown waits
      val dailyResult =
        manager.translateBatchWithAutoRotation(
          settings = AppSettings(),
          inputMode = InputMode.MOVIE,
          title = "Test",
          secondaryMeta = "2026",
          batchSrtPayload = "1\n00:00:01,000 --> 00:00:02,000\nHi",
          onRetryDelay = { _, _ -> },
          onCooldownWait = { cooldownMinutesObserved.add(it) },
        )
      assertTrue(dailyResult is RotationExecutionResult.Failure)
      assertTrue(
        "Expected 0 cooldown waits when all keys hit daily quota, got $cooldownMinutesObserved",
        cooldownMinutesObserved.isEmpty(),
      )

      // 2. HTTP 404 must fail immediately without cycling keys or entering cooldown
      mode = "FATAL_404"
      val fatalResult =
        manager.translateBatchWithAutoRotation(
          settings = AppSettings(),
          inputMode = InputMode.MOVIE,
          title = "Test",
          secondaryMeta = "2026",
          batchSrtPayload = "1\n00:00:01,000 --> 00:00:02,000\nHi",
          onRetryDelay = { _, _ -> },
          onCooldownWait = { cooldownMinutesObserved.add(it) },
        )
      assertTrue(fatalResult is RotationExecutionResult.Failure)
      assertTrue(cooldownMinutesObserved.isEmpty())

      // 3. Repeated per-minute 429 caps at 2 cooldown cycles with 1-minute max wait (not 30m infinite)
      mode = "RPM_429"
      val rpmResult =
        manager.translateBatchWithAutoRotation(
          settings = AppSettings(),
          inputMode = InputMode.MOVIE,
          title = "Test",
          secondaryMeta = "2026",
          batchSrtPayload = "1\n00:00:01,000 --> 00:00:02,000\nHi",
          onRetryDelay = { _, _ -> },
          onCooldownWait = { cooldownMinutesObserved.add(it) },
        )
      assertTrue(rpmResult is RotationExecutionResult.Failure)
      assertEquals(listOf(1, 1), cooldownMinutesObserved)

      // 4. Validation failure flags isValidationFailure = true and never marks healthy keys QUOTA_EXHAUSTED
      mode = "BAD_OUTPUT"
      cooldownMinutesObserved.clear()
      val batchBlock = listOf(SrtBlock(1, "00:00:01,000 --> 00:00:02,000", listOf("Hi")))
      val validationSession = manager.prepareSession()
      val valResult =
        manager.executeBatchWithRotation(
          session = validationSession,
          model = "gemini-3.8-flash",
          temperature = 0.3f,
          thinkingBudget = 0,
          systemPrompt = "Translate",
          fewShotExamples = emptyList(),
          inputMode = InputMode.MOVIE,
          title = "Test",
          secondaryMeta = "2026",
          batchSrtPayload = "1\n00:00:01,000 --> 00:00:02,000\nHi",
          batchIndex = 1,
          totalBatches = 1,
          fileName = "test.srt",
          maxAttemptsPerKey = 3,
          retryIntervalSeconds = 1,
          cooldownMinutes = 30,
          validateOutput = { raw ->
            val parsed = com.example.srt.SrtEngine.parseModelOutput(raw)
            when (val r = com.example.srt.SrtEngine.reconcileBatch(batchBlock, parsed)) {
              is com.example.srt.BatchReconciliationResult.Resolved ->
                com.example.data.network.BatchValidationOutcome.Valid(r.blocks)
              is com.example.srt.BatchReconciliationResult.TooIncomplete ->
                com.example.data.network.BatchValidationOutcome.Invalid(r.reason)
            }
          },
          onRetryDelay = { _, _ -> },
          onCooldownWait = { cooldownMinutesObserved.add(it) },
        )
      assertTrue(valResult is RotationExecutionResult.Failure && valResult.isValidationFailure)
      assertTrue(cooldownMinutesObserved.isEmpty())
      val storedStatuses = app.secureKeyStorage.getKeysSnapshot().map { it.status }
      assertTrue(
        "Expected validation failure not to mark keys QUOTA_EXHAUSTED, got $storedStatuses",
        storedStatuses.all { it == ApiKeyStatus.ACTIVE },
      )
    }

  @Test
  fun `subtitle translation service auto-splits truncated large batch into halves`() =
    runBlocking {
      app.secureKeyStorage.clearAllKeys()
      app.secureKeyStorage.addSingleKey("AIzaSySplitTestKey_1234567890", "Primary")
      app.secureKeyStorage.setActiveKeyIndex(0)
      app.settingsRepository.setChunkSize(ParamMode.MANUAL, 6)
      app.settingsRepository.setMaxAttemptsPerKey(ParamMode.MANUAL, 1)

      val rawSrt =
        (1..6).joinToString("\n\n") { idx ->
          "$idx\n00:00:0$idx,000 --> 00:00:0$idx,800\nCue text $idx"
        }

      val requestedCueCounts = mutableListOf<Int>()
      val mockService =
        object : GeminiApiService {
          override suspend fun generateContentWithKeyRotation(
            model: String,
            request: GeminiGenerateContentRequest,
            autoRotate: String,
          ): Response<GeminiGenerateContentResponse> = generateContent(model, "", request)

          override suspend fun generateContent(
            model: String,
            apiKey: String,
            request: GeminiGenerateContentRequest,
          ): Response<GeminiGenerateContentResponse> {
            val prompt = request.contents.last().parts.first().text.orEmpty()
            val blocksInPrompt = com.example.srt.SrtParser.parseBlocks(prompt)
            requestedCueCounts.add(blocksInPrompt.size)

            // Truncate 6-cue batch (only 2/6 cues) so service must split into 3 + 3
            val responseText =
              if (blocksInPrompt.size > 3) {
                blocksInPrompt.take(2).joinToString("\n\n") { b ->
                  "${b.index}\n${b.timestamp}\nখণ্ডিত ${b.index}"
                }
              } else {
                blocksInPrompt.joinToString("\n\n") { b ->
                  "${b.index}\n${b.timestamp}\nঅনুবাদ ${b.index}"
                }
              }

            return Response.success(
              GeminiGenerateContentResponse(
                candidates =
                  listOf(
                    GeminiCandidate(
                      content = GeminiContent(parts = listOf(GeminiPart(text = responseText))),
                      finishReason = "STOP",
                    )
                  )
              )
            )
          }
        }

      val apiClient = GeminiApiClient(customService = mockService)
      val rotationManager = ApiKeyRotationManager(app.secureKeyStorage, apiClient)
      val service =
        SubtitleTranslationService(
          settingsRepository = app.settingsRepository,
          fileStorage = app.subtitleFileStorage,
          geminiApiClient = apiClient,
          keyRotationManager = rotationManager,
        )

      val result =
        service.translateSrtContentWithRotation(
          rawSrtContent = rawSrt,
          fileName = "autosplit.srt",
          chunkSizeOverride = 6,
          onRetryDelay = { _, _ -> },
          onCooldownWait = { minutes ->
            throw AssertionError("Unexpected cooldown ($minutes m) during auto-split")
          },
        )

      assertTrue("Expected Success after auto-split, got $result", result is SubtitleTranslationServiceResult.Success)
      val success = result as SubtitleTranslationServiceResult.Success
      assertEquals(6, success.translatedBlocks.size)
      assertEquals("অনুবাদ 6", success.translatedBlocks[5].lines.first())
      assertEquals(listOf(6, 3, 3), requestedCueCounts)
    }

  @Test
  fun `worker resumes from saved room translated batches without re-requesting completed segments`() =
    runBlocking {
      app.secureKeyStorage.clearAllKeys()
      app.secureKeyStorage.addSingleKey("AIzaSyResumeKey_1234567890", "Primary")
      app.secureKeyStorage.setActiveKeyIndex(0)
      app.settingsRepository.setChunkSize(ParamMode.MANUAL, 10)
      app.settingsRepository.setMaxAttemptsPerKey(ParamMode.MANUAL, 1)

      val jobId = "resume_job_42"
      val rawSrt =
        (1..20).joinToString("\n\n") { idx ->
          val startSec = idx.toString().padStart(2, '0')
          val endSec = (idx + 1).toString().padStart(2, '0')
          "$idx\n00:00:$startSec,000 --> 00:00:$endSec,000\nEnglish line $idx"
        }

      // Seed Room with Batch 1 (cues 1..10) already translated from an earlier interrupted run
      app.subtitleTranslationRepository.clearAll()
      val originalBlocks = com.example.srt.SrtParser.parseBlocks(rawSrt)
      app.subtitleTranslationRepository.seedPendingBlocks(jobId, "resume_test.srt", originalBlocks)
      app.subtitleTranslationRepository.markBatchTranslated(
        jobId = jobId,
        translatedBlocks =
          originalBlocks.take(10).map { b ->
            b.copy(lines = listOf("বাংলা লাইন ${b.index}"))
          },
      )

      val promptsSentToApi = mutableListOf<String>()
      val mockService =
        object : GeminiApiService {
          override suspend fun generateContentWithKeyRotation(
            model: String,
            request: GeminiGenerateContentRequest,
            autoRotate: String,
          ): Response<GeminiGenerateContentResponse> = generateContent(model, "", request)

          override suspend fun generateContent(
            model: String,
            apiKey: String,
            request: GeminiGenerateContentRequest,
          ): Response<GeminiGenerateContentResponse> {
            val prompt = request.contents.last().parts.first().text.orEmpty()
            promptsSentToApi.add(prompt)
            val parsedPromptBlocks = com.example.srt.SrtParser.parseBlocks(prompt)
            val reply =
              parsedPromptBlocks.joinToString("\n\n") { b ->
                "${b.index}\n${b.timestamp}\nবাংলা লাইন ${b.index}"
              }
            return Response.success(
              GeminiGenerateContentResponse(
                candidates =
                  listOf(
                    GeminiCandidate(
                      content = GeminiContent(parts = listOf(GeminiPart(text = reply))),
                      finishReason = "STOP",
                    )
                  )
              )
            )
          }
        }
      app.overrideGeminiServiceForTesting(mockService)

      val worker =
        TestListenableWorkerBuilder<SubtitleTranslationWorker>(app)
          .setInputData(
            workDataOf(
              SubtitleTranslationWorker.KEY_SRT_CONTENT to rawSrt,
              SubtitleTranslationWorker.KEY_FILE_NAME to "resume_test.srt",
              SubtitleTranslationWorker.KEY_ROOM_JOB_ID to jobId,
            )
          )
          .build()

      val result = worker.doWork()
      assertTrue("Expected resumed worker success, got $result", result is ListenableWorker.Result.Success)
      // Only Batch 2 (cues 11..20) should hit the API; Batch 1 (1..10) was skipped via Room cache
      assertEquals(1, promptsSentToApi.size)
      assertFalse(promptsSentToApi[0].contains("English line 1\n"))
      assertTrue(promptsSentToApi[0].contains("English line 11"))
      assertTrue(promptsSentToApi[0].contains("English line 20"))
      assertEquals(20, result.outputData.getInt(SubtitleTranslationWorker.KEY_COMPLETED_BLOCKS, 0))
    }

  @Test
  fun `context lines setting defaults to 50 persists updates and adjusts via settings stepper UI`() =
    runBlocking {
      app.settingsRepository.setContextLines(ParamMode.DEFAULT, 50)
      val initial = app.settingsRepository.getSettingsSnapshot()
      assertEquals(ParamMode.DEFAULT, initial.contextLinesMode)
      assertEquals(50, initial.effectiveContextLines)

      composeTestRule.setContent {
        val currentSettings by
          app.settingsRepository.settingsFlow.collectAsState(initial = AppSettings())
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        BSubTranslatorTheme {
          ContextAwareTranslationCard(
            settings = currentSettings,
            onSetContextLines = { mode, value ->
              scope.launch {
                app.settingsRepository.setContextLines(mode, value)
              }
            },
          )
        }
      }

      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("context_lines_value_text").assertTextEquals("50 lines")

      // Increase from default 50 -> 60 (switches to MANUAL)
      composeTestRule.onNodeWithTag("context_lines_increase_button").performClick()
      composeTestRule.waitUntil(timeoutMillis = 5000L) {
        try {
          composeTestRule.onNodeWithTag("context_lines_value_text").assertTextEquals("60 lines")
          true
        } catch (_: AssertionError) {
          false
        }
      }

      // Decrease 60 -> 50 -> 40
      composeTestRule.onNodeWithTag("context_lines_decrease_button").performClick()
      composeTestRule.onNodeWithTag("context_lines_decrease_button").performClick()
      composeTestRule.waitUntil(timeoutMillis = 5000L) {
        try {
          composeTestRule.onNodeWithTag("context_lines_value_text").assertTextEquals("40 lines")
          true
        } catch (_: AssertionError) {
          false
        }
      }

      // Custom input updates limit
      composeTestRule.onNodeWithTag("manual_context_lines_input").performTextReplacement("120")
      composeTestRule.waitUntil(timeoutMillis = 5000L) {
        try {
          composeTestRule.onNodeWithTag("context_lines_value_text").assertTextEquals("120 lines")
          true
        } catch (_: AssertionError) {
          false
        }
      }

      val persisted = app.settingsRepository.getSettingsSnapshot()
      assertEquals(ParamMode.MANUAL, persisted.contextLinesMode)
      assertEquals(120, persisted.effectiveContextLines)
    }

  @Test
  fun `context-aware translation sends surrounding lines per batch and skips when context lines is zero`() =
    runBlocking {
      app.secureKeyStorage.clearAllKeys()
      app.secureKeyStorage.addSingleKey("AIzaSyContextTestKey_1234567890", "Primary")
      app.secureKeyStorage.setActiveKeyIndex(0)
      app.settingsRepository.setChunkSize(ParamMode.MANUAL, 2)
      app.settingsRepository.setContextLines(ParamMode.MANUAL, 4)

      val rawSrt =
        (1..4).joinToString("\n\n") { idx ->
          "$idx\n00:00:0$idx,000 --> 00:00:0$idx,800\nLine number $idx"
        }

      val capturedRequests = mutableListOf<GeminiGenerateContentRequest>()
      val mockService =
        object : GeminiApiService {
          override suspend fun generateContentWithKeyRotation(
            model: String,
            request: GeminiGenerateContentRequest,
            autoRotate: String,
          ): Response<GeminiGenerateContentResponse> = generateContent(model, "", request)

          override suspend fun generateContent(
            model: String,
            apiKey: String,
            request: GeminiGenerateContentRequest,
          ): Response<GeminiGenerateContentResponse> {
            capturedRequests.add(request)
            val batchPrompt = request.contents.last().parts.first().text.orEmpty()
            val blocks = com.example.srt.SrtParser.parseBlocks(batchPrompt)
            val reply =
              blocks.joinToString("\n\n") { b ->
                "${b.index}\n${b.timestamp}\nবাংলা ${b.index}"
              }
            return Response.success(
              GeminiGenerateContentResponse(
                candidates =
                  listOf(
                    GeminiCandidate(
                      content = GeminiContent(parts = listOf(GeminiPart(text = reply))),
                      finishReason = "STOP",
                    )
                  )
              )
            )
          }
        }

      val apiClient = GeminiApiClient(customService = mockService)
      val rotationManager = ApiKeyRotationManager(app.secureKeyStorage, apiClient)
      val service =
        SubtitleTranslationService(
          settingsRepository = app.settingsRepository,
          fileStorage = app.subtitleFileStorage,
          geminiApiClient = apiClient,
          keyRotationManager = rotationManager,
        )

      val result =
        service.translateSrtContentWithRotation(
          rawSrtContent = rawSrt,
          fileName = "context_demo.srt",
          chunkSizeOverride = 2,
          onRetryDelay = { _, _ -> },
        )
      assertTrue(result is SubtitleTranslationServiceResult.Success)
      assertEquals(2, capturedRequests.size)

      // Batch 1 receives cues 3..4 as following context
      val batch1Context = capturedRequests[0].contents.first().parts.first().text.orEmpty()
      assertTrue(batch1Context.contains("SURROUNDING DIALOGUE CONTEXT"))
      assertTrue(batch1Context.contains("Line number 3"))
      assertTrue(batch1Context.contains("Line number 4"))

      // Batch 2 receives cues 1..2 as preceding context including their Bengali translations
      val batch2Context = capturedRequests[1].contents.first().parts.first().text.orEmpty()
      assertTrue(batch2Context.contains("SURROUNDING DIALOGUE CONTEXT"))
      assertTrue(batch2Context.contains("Line number 1"))
      assertTrue(batch2Context.contains("বাংলা 1"))
      assertTrue(batch2Context.contains("Line number 2"))

      // When Context Lines = 0, no context turn is sent
      capturedRequests.clear()
      app.settingsRepository.setContextLines(ParamMode.MANUAL, 0)
      val disabledResult =
        service.translateSrtContentWithRotation(
          rawSrtContent = rawSrt,
          fileName = "context_off.srt",
          chunkSizeOverride = 2,
          onRetryDelay = { _, _ -> },
        )
      assertTrue(disabledResult is SubtitleTranslationServiceResult.Success)
      assertEquals(2, capturedRequests.size)
      assertEquals(1, capturedRequests[0].contents.size)
      assertEquals(1, capturedRequests[1].contents.size)
    }

  @Test
  fun `api keys screen separates gemini grok and deepseek segments and switches active provider`() {
    runBlocking {
      app.secureKeyStorage.clearAllKeys()
      app.settingsRepository.setApiProvider(ApiProvider.GEMINI)
    }

    composeTestRule.setContent {
      val keys by app.secureKeyStorage.apiKeysFlow.collectAsState(initial = emptyList())
      val currentSettings by
        app.settingsRepository.settingsFlow.collectAsState(initial = AppSettings())
      val scope = androidx.compose.runtime.rememberCoroutineScope()
      BSubTranslatorTheme {
        ApiKeysScreen(
          apiKeys = keys,
          isTranslationRunning = false,
          activeProvider = currentSettings.apiProvider,
          onSetActiveProvider = { provider ->
            scope.launch { app.settingsRepository.setApiProvider(provider) }
          },
          onSaveBulkKeys = { raw, provider ->
            scope.launch { app.secureKeyStorage.addBulkKeys(raw, provider) }
          },
          onAddSingleKey = { k, l, provider ->
            scope.launch { app.secureKeyStorage.addSingleKey(k, l, provider) }
          },
          onEditKey = { id, k, l ->
            scope.launch { app.secureKeyStorage.editKey(id, k, l) }
          },
          onRemoveKey = { id ->
            scope.launch { app.secureKeyStorage.removeKey(id) }
          },
          onClearAllKeys = { provider ->
            scope.launch { app.secureKeyStorage.clearAllKeys(provider) }
          },
          onResetAllStatuses = { provider ->
            scope.launch { app.secureKeyStorage.resetAllKeyStatuses(provider) }
          },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("provider_segment_gemini").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("provider_segment_grok").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("provider_segment_deepseek").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("active_provider_badge").performScrollTo().assertIsDisplayed()

    // Switch to Grok segment, save a Grok key, and activate Grok
    composeTestRule.onNodeWithTag("provider_segment_grok").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("activate_provider_button").performScrollTo().assertIsDisplayed().performClick()
    composeTestRule.onNodeWithTag("bulk_keys_input").performScrollTo().performTextInput("xai-test-grok-key-1234567890")
    composeTestRule.onNodeWithTag("save_bulk_keys_button").performScrollTo().performClick()

    composeTestRule.waitUntil(timeoutMillis = 5000L) {
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
      try {
        composeTestRule.onNodeWithTag("active_provider_badge").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("provider_segment_grok").performScrollTo().assertTextContains("Grok (1)", substring = true)
        true
      } catch (_: AssertionError) {
        false
      }
    }

    // Switch to DeepSeek segment and save a DeepSeek key
    composeTestRule.onNodeWithTag("provider_segment_deepseek").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("bulk_keys_input").performScrollTo().performTextReplacement("sk-deepseek-key-1234567890")
    composeTestRule.onNodeWithTag("save_bulk_keys_button").performScrollTo().performClick()

    composeTestRule.waitUntil(timeoutMillis = 5000L) {
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
      try {
        composeTestRule.onNodeWithTag("provider_segment_deepseek").assertTextContains("DeepSeek (1)", substring = true)
        true
      } catch (_: AssertionError) {
        false
      }
    }

    runBlocking {
      assertEquals(0, app.secureKeyStorage.getKeysSnapshot(ApiProvider.GEMINI).size)
      assertEquals(1, app.secureKeyStorage.getKeysSnapshot(ApiProvider.GROK).size)
      assertEquals(1, app.secureKeyStorage.getKeysSnapshot(ApiProvider.DEEPSEEK).size)
    }
    assertEquals(DefaultConfig.DEEPSEEK_MODEL, DefaultConfig.defaultModelFor(ApiProvider.DEEPSEEK))
  }

  @Test
  fun `translation service executes grok and deepseek endpoints with per-provider key rotation`() =
    runBlocking {
      app.secureKeyStorage.clearAllKeys()
      app.secureKeyStorage.addSingleKey("xai-grok-alpha-key-123456", "Grok 1", ApiProvider.GROK)
      app.secureKeyStorage.addSingleKey("sk-ds-exhausted-key-123456", "DS 1", ApiProvider.DEEPSEEK)
      app.secureKeyStorage.addSingleKey("sk-ds-healthy-key-1234567", "DS 2", ApiProvider.DEEPSEEK)
      app.settingsRepository.setChunkSize(ParamMode.MANUAL, 5)
      app.settingsRepository.setModel(ParamMode.DEFAULT, "")
      app.settingsRepository.setContextLines(ParamMode.MANUAL, 0)

      val capturedUrls = mutableListOf<String>()
      val capturedAuths = mutableListOf<String>()
      val capturedModels = mutableListOf<String>()

      val fakeChatService =
        object : OpenAiCompatibleApiService {
          override suspend fun createChatCompletion(
            url: String,
            authorization: String,
            request: ChatCompletionRequest,
          ): Response<ChatCompletionResponse> {
            capturedUrls.add(url)
            capturedAuths.add(authorization)
            capturedModels.add(request.model)
            if (authorization == "Bearer sk-ds-exhausted-key-123456") {
              return Response.error(
                402,
                """{"error":{"message":"Insufficient Balance","type":"invalid_request_error"}}"""
                  .toResponseBody("application/json".toMediaType()),
              )
            }
            val userMsg = request.messages.last().content
            val blocks = com.example.srt.SrtParser.parseBlocks(userMsg)
            val reply =
              blocks.joinToString("\n\n") { b ->
                "${b.index}\n${b.timestamp}\nবাংলা ${b.index}"
              }
            return Response.success(
              ChatCompletionResponse(
                choices =
                  listOf(
                    ChatChoiceDto(
                      message = ChatMessageDto(role = "assistant", content = reply),
                      finishReason = "stop",
                    )
                  )
              )
            )
          }
        }

      val apiClient = GeminiApiClient(customChatService = fakeChatService)
      val rotationManager = ApiKeyRotationManager(app.secureKeyStorage, apiClient)
      val service =
        SubtitleTranslationService(
          settingsRepository = app.settingsRepository,
          fileStorage = app.subtitleFileStorage,
          geminiApiClient = apiClient,
          keyRotationManager = rotationManager,
        )

      val sampleSrt = "1\n00:00:01,000 --> 00:00:03,000\nHello world."

      // 1. Run with Grok
      app.settingsRepository.setApiProvider(ApiProvider.GROK)
      val grokResult =
        service.translateSrtContentWithRotation(
          rawSrtContent = sampleSrt,
          fileName = "grok.srt",
          onRetryDelay = { _, _ -> },
        )
      assertTrue(grokResult is SubtitleTranslationServiceResult.Success)
      assertEquals(listOf(OpenAiCompatibleApiService.GROK_URL), capturedUrls)
      assertEquals(listOf("Bearer xai-grok-alpha-key-123456"), capturedAuths)
      assertEquals(listOf(DefaultConfig.GROK_MODEL), capturedModels)

      // 2. Run with DeepSeek (rotates past 402 Insufficient Balance on DS 1 -> succeeds on DS 2)
      capturedUrls.clear()
      capturedAuths.clear()
      capturedModels.clear()
      app.settingsRepository.setApiProvider(ApiProvider.DEEPSEEK)
      val dsResult =
        service.translateSrtContentWithRotation(
          rawSrtContent = sampleSrt,
          fileName = "deepseek.srt",
          onRetryDelay = { _, _ -> },
        )
      assertTrue(dsResult is SubtitleTranslationServiceResult.Success)
      assertEquals(
        listOf(OpenAiCompatibleApiService.DEEPSEEK_URL, OpenAiCompatibleApiService.DEEPSEEK_URL),
        capturedUrls,
      )
      assertEquals(
        listOf("Bearer sk-ds-exhausted-key-123456", "Bearer sk-ds-healthy-key-1234567"),
        capturedAuths,
      )
      assertEquals(
        listOf(DefaultConfig.DEEPSEEK_MODEL, DefaultConfig.DEEPSEEK_MODEL),
        capturedModels,
      )
    }

  @Test
  fun `usage dashboard displays recorded gemini rate limits success counts and clears stats`() =
    runBlocking {
      val app = ApplicationProvider.getApplicationContext<BSubApplication>()
      app.apiUsageRepository.clearStats()

      app.apiUsageRepository.recordRequestOutcome(
        keyId = "gem-key-1",
        keyLabel = "Key 1",
        provider = ApiProvider.GEMINI,
        model = DefaultConfig.GEMINI_MODEL,
        batchSize = 100,
        dynamicBatchSizeAfter = 125,
        latencyMs = 1_450L,
        outcome = ApiRequestOutcome.SUCCESS,
        httpCode = 200,
        blocksTranslated = 100,
      )
      app.apiUsageRepository.recordRequestOutcome(
        keyId = "gem-key-1",
        keyLabel = "Key 1",
        provider = ApiProvider.GEMINI,
        model = DefaultConfig.GEMINI_MODEL,
        batchSize = 125,
        dynamicBatchSizeAfter = 100,
        latencyMs = 920L,
        outcome = ApiRequestOutcome.RATE_LIMITED,
        httpCode = 429,
        blocksTranslated = 0,
        errorReason = "HTTP 429 Too Many Requests",
      )
      app.apiUsageRepository.recordRequestOutcome(
        keyId = "gem-key-2",
        keyLabel = "Key 2",
        provider = ApiProvider.GEMINI,
        model = DefaultConfig.GEMINI_MODEL,
        batchSize = 100,
        dynamicBatchSizeAfter = 100,
        latencyMs = 2_100L,
        outcome = ApiRequestOutcome.SUCCESS,
        httpCode = 200,
        blocksTranslated = 100,
      )

      var clearedProvider: ApiProvider? = ApiProvider.DEEPSEEK
      var clearedCalled = false
      val stats = app.apiUsageRepository.allKeyStats.first()
      val events = app.apiUsageRepository.recentEvents.first()

      composeTestRule.setContent {
        BSubTranslatorTheme {
          UsageDashboardScreen(
            keyStats = stats,
            recentEvents = events,
            configuredKeys = emptyList(),
            settings = AppSettings(dynamicBatchSizingEnabled = true),
            sessionState = TranslationSessionState(),
            onClearUsageStats = { provider ->
              clearedCalled = true
              clearedProvider = provider
            },
          )
        }
      }

      composeTestRule.onNodeWithTag("usage_total_success").assertTextContains("2")
      composeTestRule.onNodeWithTag("usage_total_rate_limits").assertTextContains("1")
      composeTestRule.onNodeWithTag("usage_bottleneck_banner").assertTextContains("Rate-limit bottleneck on Key 1", substring = true)
      composeTestRule.onNodeWithTag("usage_key_comparison_chart").assertIsDisplayed()
      composeTestRule.onNodeWithTag("usage_latency_chart").performScrollTo().assertIsDisplayed()
      composeTestRule.onNodeWithTag("usage_key_stat_row_0").performScrollTo().assertTextContains("Key 1", substring = true)

      composeTestRule.onNodeWithTag("clear_usage_stats_button").performScrollTo().performClick()
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("confirm_clear_usage_stats_button").performClick()
      assertTrue(clearedCalled)
      assertEquals(ApiProvider.GEMINI, clearedProvider)
    }

  @Test
  fun `dynamic batch sizing expands slices on fast responses and logs room api usage telemetry`() =
    runBlocking {
      val app = ApplicationProvider.getApplicationContext<BSubApplication>()
      app.secureKeyStorage.clearAllKeys()
      app.apiUsageRepository.clearStats()
      app.settingsRepository.setApiProvider(ApiProvider.GEMINI)

      app.secureKeyStorage.addSingleKey("AIzaSyDynamicTestKeyAlpha123456", "Key 1", ApiProvider.GEMINI)
      app.secureKeyStorage.addSingleKey("AIzaSyDynamicTestKeyBeta1234567", "Key 2", ApiProvider.GEMINI)

      val observedSliceSizes = mutableListOf<Int>()
      val fakeGemini =
        object : GeminiApiService {
          var callNumber = 0

          override suspend fun generateContentWithKeyRotation(
            model: String,
            request: GeminiGenerateContentRequest,
            autoRotate: String,
          ): Response<GeminiGenerateContentResponse> =
            generateContent(model = model, apiKey = "", request = request)

          override suspend fun generateContent(
            model: String,
            apiKey: String,
            request: GeminiGenerateContentRequest,
          ): Response<GeminiGenerateContentResponse> {
            callNumber += 1
            val batchPrompt = request.contents.lastOrNull()?.parts?.firstOrNull()?.text.orEmpty()
            val blocks = com.example.srt.SrtParser.parseBlocks(batchPrompt)
            observedSliceSizes.add(blocks.size)

            // First attempt on Key 1 hits HTTP 429 rate limit; rotated Key 2 succeeds fast
            if (callNumber == 1) {
              return Response.error(
                429,
                """{"error":{"message":"Rate limit exceeded"}}"""
                  .toResponseBody("application/json".toMediaType()),
              )
            }

            val reply =
              blocks.joinToString("\n\n") { b ->
                "${b.index}\n${b.timestamp}\nঅনুবাদ ${b.index}"
              }
            return Response.success(
              GeminiGenerateContentResponse(
                candidates =
                  listOf(
                    GeminiCandidate(
                      content = GeminiContent(parts = listOf(GeminiPart(text = reply))),
                      finishReason = "STOP",
                    )
                  )
              )
            )
          }
        }

      val apiClient = GeminiApiClient(customService = fakeGemini)
      val rotationManager =
        ApiKeyRotationManager(
          keyStorage = app.secureKeyStorage,
          geminiApiClient = apiClient,
          apiUsageRepository = app.apiUsageRepository,
        )
      val service =
        SubtitleTranslationService(
          settingsRepository = app.settingsRepository,
          fileStorage = app.subtitleFileStorage,
          geminiApiClient = apiClient,
          keyRotationManager = rotationManager,
        )

      val rawSrt =
        (1..9).joinToString("\n\n") { idx ->
          "$idx\n00:00:0$idx,000 --> 00:00:0$idx,800\nEnglish line $idx"
        }

      val dynamicSizer =
        DynamicBatchSizer(
          baseBatchSize = 2,
          enabled = true,
          minBatchSize = 2,
          maxBatchSize = 5,
          windowSize = 3,
          fastThresholdMs = 3_000L,
          slowThresholdMs = 10_000L,
        )

      val result =
        service.translateSrtContentWithRotation(
          rawSrtContent = rawSrt,
          fileName = "dynamic_test.srt",
          dynamicBatchSizerOverride = dynamicSizer,
          onRetryDelay = { _, _ -> },
        )

      assertTrue("Expected Success, got $result", result is SubtitleTranslationServiceResult.Success)
      // Call 1 (2 blocks -> 429), Call 2 (2 blocks -> ok), Call 3 (2 blocks -> ok -> scale up to 5), Call 4 (5 blocks!)
      assertTrue(
        "Expected dynamic sizer to expand slice size > 2, observed=$observedSliceSizes",
        observedSliceSizes.any { it > 2 },
      )

      val keyStats = app.apiUsageRepository.allKeyStats.first()
      assertEquals(1, keyStats.sumOf { it.rateLimitHits })
      assertTrue(
        "Expected multiple successful Gemini requests recorded, got $keyStats",
        keyStats.sumOf { it.successfulRequests } >= 3,
      )
    }

  @Test
  fun `workmanager dynamically swaps gemini grok and deepseek services via provider input data`() =
    runBlocking {
      app.secureKeyStorage.clearAllKeys()
      app.settingsRepository.setChunkSize(ParamMode.MANUAL, 5)
      app.settingsRepository.setModel(ParamMode.DEFAULT, "")
      app.settingsRepository.setContextLines(ParamMode.MANUAL, 0)
      app.settingsRepository.setApiProvider(ApiProvider.GEMINI)

      app.secureKeyStorage.addSingleKey("AIzaSyWorkerGemKey1234567890", "Gem", ApiProvider.GEMINI)
      app.secureKeyStorage.addSingleKey("xai-worker-grok-key-1234567", "Grok", ApiProvider.GROK)
      app.secureKeyStorage.addSingleKey("sk-worker-ds-key-1234567890", "DeepSeek", ApiProvider.DEEPSEEK)

      val calledServices = mutableListOf<String>()
      fun replyForChat(request: ChatCompletionRequest): Response<ChatCompletionResponse> {
        val blocks = com.example.srt.SrtParser.parseBlocks(request.messages.last().content)
        val reply = blocks.joinToString("\n\n") { b -> "${b.index}\n${b.timestamp}\nবাংলা ${b.index}" }
        return Response.success(
          ChatCompletionResponse(
            choices =
              listOf(
                ChatChoiceDto(
                  message = ChatMessageDto(role = "assistant", content = reply),
                  finishReason = "stop",
                )
              )
          )
        )
      }

      val fakeGemini =
        object : GeminiApiService {
          override suspend fun generateContentWithKeyRotation(
            model: String,
            request: GeminiGenerateContentRequest,
            autoRotate: String,
          ) = generateContent(model, "", request)

          override suspend fun generateContent(
            model: String,
            apiKey: String,
            request: GeminiGenerateContentRequest,
          ): Response<GeminiGenerateContentResponse> {
            calledServices.add("GEMINI:$apiKey:$model")
            val blocks =
              com.example.srt.SrtParser.parseBlocks(request.contents.last().parts.first().text.orEmpty())
            val reply = blocks.joinToString("\n\n") { b -> "${b.index}\n${b.timestamp}\nবাংলা ${b.index}" }
            return Response.success(
              GeminiGenerateContentResponse(
                candidates =
                  listOf(
                    GeminiCandidate(
                      content = GeminiContent(parts = listOf(GeminiPart(text = reply))),
                      finishReason = "STOP",
                    )
                  )
              )
            )
          }
        }

      val fakeGrok =
        object : GrokApiService {
          override suspend fun createChatCompletion(
            authorization: String,
            request: ChatCompletionRequest,
          ): Response<ChatCompletionResponse> {
            calledServices.add("GROK:$authorization:${request.model}")
            return replyForChat(request)
          }
        }

      val fakeDeepSeek =
        object : DeepSeekApiService {
          override suspend fun createChatCompletion(
            authorization: String,
            request: ChatCompletionRequest,
          ): Response<ChatCompletionResponse> {
            calledServices.add("DEEPSEEK:$authorization:${request.model}")
            return replyForChat(request)
          }
        }

      app.overrideServicesForTesting(
        customService = fakeGemini,
        customGrokService = fakeGrok,
        customDeepSeekService = fakeDeepSeek,
      )

      val sampleSrt = "1\n00:00:01,000 --> 00:00:02,000\nHello world."

      for (provider in listOf(ApiProvider.GROK, ApiProvider.DEEPSEEK, ApiProvider.GEMINI)) {
        val worker =
          TestListenableWorkerBuilder<SubtitleTranslationWorker>(app)
            .setInputData(
              workDataOf(
                SubtitleTranslationWorker.KEY_SRT_CONTENT to sampleSrt,
                SubtitleTranslationWorker.KEY_FILE_NAME to "${provider.name.lowercase()}.srt",
                SubtitleTranslationWorker.KEY_API_PROVIDER to provider.name,
              )
            )
            .build()
        val result = worker.doWork()
        assertTrue("Expected success for $provider, got $result", result is ListenableWorker.Result.Success)
      }

      assertEquals(
        listOf(
          "GROK:Bearer xai-worker-grok-key-1234567:${DefaultConfig.GROK_MODEL}",
          "DEEPSEEK:Bearer sk-worker-ds-key-1234567890:${DefaultConfig.DEEPSEEK_MODEL}",
          "GEMINI:AIzaSyWorkerGemKey1234567890:${DefaultConfig.GEMINI_MODEL}",
        ),
        calledServices,
      )
      app.overrideServicesForTesting()
    }

  @Test
  fun `keys screen unified api configuration manages independent provider keys and primary engine`() {
    runBlocking {
      app.secureKeyStorage.clearAllKeys()
      app.settingsRepository.setApiProvider(ApiProvider.GEMINI)
    }

    val viewModel = MainViewModel(app)
    var showSettingsOnly by mutableStateOf(false)
    composeTestRule.setContent {
      val settings by viewModel.settings.collectAsState()
      val apiKeys by viewModel.apiKeys.collectAsState()
      val sessionState by viewModel.sessionState.collectAsState()
      BSubTranslatorTheme {
        if (!showSettingsOnly) {
          ApiKeysScreen(
            apiKeys = apiKeys,
            isTranslationRunning = sessionState.isRunningOrCooldown,
            activeProvider = settings.apiProvider,
            effectiveModel = settings.effectiveModel,
            onSetActiveProvider = viewModel::setApiProvider,
            onSaveBulkKeys = viewModel::saveBulkApiKeys,
            onImportKeysFile = viewModel::importApiKeysFromUri,
            onAddSingleKey = viewModel::addSingleApiKey,
            onEditKey = viewModel::editApiKey,
            onRemoveKey = viewModel::removeApiKey,
            onClearAllKeys = viewModel::clearAllApiKeys,
            onResetAllStatuses = viewModel::resetAllKeyStatuses,
          )
        } else {
          SettingsScreen(
            settings = settings,
            viewModel = viewModel,
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("unified_api_config_card").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("keys_active_engine_badge").assertTextContains("Active Engine: Gemini", substring = true)

    // Toggle primary engine to Grok
    composeTestRule.onNodeWithTag("keys_primary_provider_grok").performScrollTo().performClick()
    composeTestRule.waitUntil(timeoutMillis = 5000L) {
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
      try {
        composeTestRule.onNodeWithTag("keys_active_engine_badge").assertTextContains("Active Engine: Grok", substring = true)
        true
      } catch (_: AssertionError) {
        false
      }
    }

    // Add a Grok key while primary is Grok
    composeTestRule.onNodeWithTag("bulk_keys_input").performScrollTo().performTextInput("xai-settings-grok-key-123456")
    composeTestRule.onNodeWithTag("save_bulk_keys_button").performScrollTo().performClick()

    // Switch independently to DeepSeek key tab and save a DeepSeek key (primary remains Grok)
    composeTestRule.onNodeWithTag("provider_segment_deepseek").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("bulk_keys_input").performScrollTo().performTextReplacement("sk-settings-ds-key-123456789")
    composeTestRule.onNodeWithTag("save_bulk_keys_button").performScrollTo().performClick()

    composeTestRule.waitUntil(timeoutMillis = 5000L) {
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
      try {
        composeTestRule.onNodeWithTag("provider_segment_grok").assertTextContains("Grok (1)", substring = true)
        composeTestRule.onNodeWithTag("provider_segment_deepseek").assertTextContains("DeepSeek (1)", substring = true)
        true
      } catch (_: AssertionError) {
        false
      }
    }

    // Promote DeepSeek to primary engine using tab CTA
    composeTestRule.onNodeWithTag("activate_provider_button").performScrollTo().performClick()
    composeTestRule.waitUntil(timeoutMillis = 5000L) {
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
      try {
        composeTestRule.onNodeWithTag("keys_active_engine_badge").assertTextContains("Active Engine: DeepSeek", substring = true)
        true
      } catch (_: AssertionError) {
        false
      }
    }

    // Verify Settings screen no longer hosts API Engine & Keys card
    showSettingsOnly = true
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("unified_api_config_card").assertDoesNotExist()
    composeTestRule.onNodeWithText("API Engine & Keys").assertDoesNotExist()

    runBlocking {
      assertEquals(ApiProvider.DEEPSEEK, app.settingsRepository.getSettingsSnapshot().apiProvider)
      assertEquals(1, app.secureKeyStorage.getKeysSnapshot(ApiProvider.GROK).size)
      assertEquals(1, app.secureKeyStorage.getKeysSnapshot(ApiProvider.DEEPSEEK).size)
      assertEquals(0, app.secureKeyStorage.getKeysSnapshot(ApiProvider.GEMINI).size)
    }
  }

  @Test
  fun `grok and deepseek succeed with zero gemini keys configured when keyStorage interceptor is active`() =
    runBlocking {
      app.secureKeyStorage.clearAllKeys()
      app.settingsRepository.setChunkSize(ParamMode.MANUAL, 5)
      app.settingsRepository.setContextLines(ParamMode.MANUAL, 0)
      app.settingsRepository.setApiProvider(ApiProvider.GROK)

      // Zero Gemini keys; Grok key initially marked INVALID by prior Gemini interceptor bug
      app.secureKeyStorage.addSingleKey("xai-real-grok-key-1234567890", "Grok Primary", ApiProvider.GROK)
      app.secureKeyStorage.addSingleKey("sk-real-deepseek-key-12345678", "DeepSeek Primary", ApiProvider.DEEPSEEK)
      val grokEntry = app.secureKeyStorage.getKeysSnapshot(ApiProvider.GROK).single()
      app.secureKeyStorage.updateKeyStatus(
        grokEntry.id,
        ApiKeyStatus.INVALID,
        "No Gemini API keys configured in DataStore",
      )

      val observedRequests = mutableListOf<Pair<String, String?>>()
      val transportStub = Interceptor { chain ->
        val req = chain.request()
        observedRequests.add(req.url.toString() to req.header("Authorization"))
        val jsonBody =
          """
          {
            "choices": [
              {
                "message": {
                  "role": "assistant",
                  "content": "1\n00:00:01,000 --> 00:00:02,000\nসব ঠিক আছে।"
                },
                "finish_reason": "stop"
              }
            ]
          }
          """.trimIndent()
        OkHttpResponse.Builder()
          .request(req)
          .protocol(Protocol.HTTP_1_1)
          .code(200)
          .message("OK")
          .body(jsonBody.toResponseBody("application/json".toMediaType()))
          .build()
      }

      // Real Retrofit instances with GeminiKeyRotationInterceptor wired via keyStorage (production path)
      val prodStyleClient =
        GeminiApiClient(
          keyStorage = app.secureKeyStorage,
          extraInterceptors = listOf(transportStub),
        )
      val rotationManager = ApiKeyRotationManager(app.secureKeyStorage, prodStyleClient)
      val service =
        SubtitleTranslationService(
          settingsRepository = app.settingsRepository,
          fileStorage = app.subtitleFileStorage,
          geminiApiClient = prodStyleClient,
          keyRotationManager = rotationManager,
        )

      val sampleSrt = "1\n00:00:01,000 --> 00:00:02,000\nEverything is fine."

      val grokResult =
        service.translateSrtContentWithRotation(
          rawSrtContent = sampleSrt,
          fileName = "grok_live_path.srt",
          onRetryDelay = { _, _ -> },
        )
      assertTrue("Expected Grok Success even with 0 Gemini keys, got $grokResult", grokResult is SubtitleTranslationServiceResult.Success)
      val recoveredGrok = app.secureKeyStorage.getKeysSnapshot(ApiProvider.GROK).single()
      assertEquals(ApiKeyStatus.ACTIVE, recoveredGrok.status)
      assertEquals(null, recoveredGrok.lastError)

      app.settingsRepository.setApiProvider(ApiProvider.DEEPSEEK)
      val deepSeekResult =
        service.translateSrtContentWithRotation(
          rawSrtContent = sampleSrt,
          fileName = "deepseek_live_path.srt",
          onRetryDelay = { _, _ -> },
        )
      assertTrue("Expected DeepSeek Success even with 0 Gemini keys, got $deepSeekResult", deepSeekResult is SubtitleTranslationServiceResult.Success)

      assertEquals(
        listOf(
          "https://api.x.ai/v1/chat/completions" to "Bearer xai-real-grok-key-1234567890",
          "https://api.deepseek.com/chat/completions" to "Bearer sk-real-deepseek-key-12345678",
        ),
        observedRequests,
      )
    }

  @Test
  fun `persisted false gemini error on grok key auto-heals immediately in keys ui and storage`() {
    runBlocking {
      app.secureKeyStorage.clearAllKeys()
      app.settingsRepository.setApiProvider(ApiProvider.GROK)
      app.settingsRepository.setModel(ParamMode.DEFAULT, DefaultConfig.GEMINI_MODEL)

      app.secureKeyStorage.addSingleKey("xai-persisted-grok-key-12345678", "Key #1", ApiProvider.GROK)
      val saved = app.secureKeyStorage.getKeysSnapshot(ApiProvider.GROK).single()
      app.secureKeyStorage.updateKeyStatus(
        saved.id,
        ApiKeyStatus.INVALID,
        "No Gemini API keys configured in DataStore",
      )
    }

    val viewModel = MainViewModel(app)
    composeTestRule.setContent {
      val settings by viewModel.settings.collectAsState()
      val apiKeys by viewModel.apiKeys.collectAsState()
      val sessionState by viewModel.sessionState.collectAsState()
      BSubTranslatorTheme {
        ApiKeysScreen(
          apiKeys = apiKeys,
          isTranslationRunning = sessionState.isRunningOrCooldown,
          activeProvider = settings.apiProvider,
          effectiveModel = settings.effectiveModel,
          onSetActiveProvider = viewModel::setApiProvider,
          onSaveBulkKeys = viewModel::saveBulkApiKeys,
          onImportKeysFile = viewModel::importApiKeysFromUri,
          onAddSingleKey = viewModel::addSingleApiKey,
          onEditKey = viewModel::editApiKey,
          onRemoveKey = viewModel::removeApiKey,
          onClearAllKeys = viewModel::clearAllApiKeys,
          onResetAllStatuses = viewModel::resetAllKeyStatuses,
        )
      }
    }

    composeTestRule.waitUntil(timeoutMillis = 5000L) {
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
      try {
        composeTestRule.onNodeWithTag("keys_active_engine_badge")
          .assertTextContains("1 active key(s)", substring = true)
        true
      } catch (_: AssertionError) {
        false
      }
    }

    composeTestRule.onNodeWithTag("keys_active_engine_badge")
      .assertTextContains("Active Engine: Grok", substring = true)
    composeTestRule.onNodeWithText("No Gemini API keys configured in DataStore").assertDoesNotExist()
    composeTestRule.onNodeWithText("Active: 1").performScrollTo().assertIsDisplayed()

    runBlocking {
      val healed = app.secureKeyStorage.getKeysSnapshot(ApiProvider.GROK).single()
      assertEquals(ApiKeyStatus.ACTIVE, healed.status)
      assertEquals(null, healed.lastError)
    }
  }

  @Test
  fun `srt validator verifies strict HH MM SS mmm timing syntax and format integrity`() {
    val validSrt =
      """
      1
      00:00:01,000 --> 00:00:03,500
      হ্যালো, তুমি কেমন আছো?

      2
      00:01:12,250 --> 00:01:15,000
      সবকিছু ঠিক আছে।
      """.trimIndent()

    val validReport = com.example.srt.SrtValidator.validateSrtContent(validSrt)
    assertTrue("Expected valid SRT report, got ${validReport.issues}", validReport.isValid)
    assertEquals(2, validReport.totalBlocks)
    assertEquals(2, validReport.validTimingBlocks)
    assertTrue(validReport.issues.isEmpty())

    val invalidTimingSrt =
      """
      1
      00:65:01,000 --> 00:00:03,500
      ভুল মিনিট

      2
      00:00:10,000 --> 00:00:05,000
      উল্টো সময়
      """.trimIndent()

    val invalidReport = com.example.srt.SrtValidator.validateSrtContent(invalidTimingSrt)
    assertFalse(invalidReport.isValid)
    assertTrue(
      invalidReport.issues.any {
        it.type == com.example.srt.SrtValidationIssueType.INVALID_TIMING_SYNTAX
      }
    )
    assertTrue(
      invalidReport.issues.any {
        it.type == com.example.srt.SrtValidationIssueType.REVERSED_TIMING_RANGE
      }
    )

    val duplicateAndEmptyTextSrt =
      """
      1
      00:00:01,000 --> 00:00:02,000
      প্রথম লাইন

      1
      00:00:03,000 --> 00:00:04,000

      """.trimIndent()

    val integrityReport = com.example.srt.SrtValidator.validateSrtContent(duplicateAndEmptyTextSrt)
    assertFalse(integrityReport.isValid)
    assertTrue(
      integrityReport.issues.any {
        it.type == com.example.srt.SrtValidationIssueType.DUPLICATE_SERIAL
      }
    )
    assertTrue(
      integrityReport.issues.any {
        it.type == com.example.srt.SrtValidationIssueType.MISSING_TEXT
      }
    )
  }

  @Test
  fun `file storage saveValidatedTranslatedSrt normalizes minor timing formats and rejects corrupted srt`() =
    runBlocking {
      val originalBlocks =
        listOf(
          SrtBlock(1, "00:00:01,000 --> 00:00:03,000", listOf("Hello world.")),
          SrtBlock(2, "00:00:04,000 --> 00:00:06,500", listOf("See you soon.")),
        )

      // 1. Minor dot/single-digit timing formatting is repaired to strict HH:MM:SS,mmm before save
      val repairableSrt =
        """
        1
        0:00:01.000 --> 0:00:03.000
        হ্যালো পৃথিবী।

        2
        00:00:04.000 --> 00:00:06.500
        শীঘ্রই দেখা হবে।
        """.trimIndent()

      val savedOutcome =
        app.subtitleFileStorage.saveValidatedTranslatedSrt(
          outputFileName = "validated_repair_BSub.srt",
          srtContent = repairableSrt,
          originalBlocks = originalBlocks,
        )
      assertTrue(
        "Expected Saved outcome, got $savedOutcome",
        savedOutcome is com.example.data.storage.SaveValidatedSrtResult.Saved,
      )
      val saved = savedOutcome as com.example.data.storage.SaveValidatedSrtResult.Saved
      val writtenText =
        app.subtitleFileStorage.readPreviewText(
          saved.output.localBackupPath,
          saved.output.contentUri,
        )
      assertTrue(writtenText.contains("00:00:01,000 --> 00:00:03,000"))
      assertTrue(writtenText.contains("00:00:04,000 --> 00:00:06,500"))

      // 2. Unparseable / corrupted SRT is rejected before saving
      val corruptedSrt =
        """
        1
        invalid timestamp syntax
        হ্যালো
        """.trimIndent()
      val failedOutcome =
        app.subtitleFileStorage.saveValidatedTranslatedSrt(
          outputFileName = "corrupted_should_not_save_BSub.srt",
          srtContent = corruptedSrt,
          originalBlocks = originalBlocks,
        )
      assertTrue(
        "Expected ValidationFailed outcome, got $failedOutcome",
        failedOutcome is com.example.data.storage.SaveValidatedSrtResult.ValidationFailed,
      )
    }

  @Test
  fun `subtitle translation service preserves partial batch translations and recovers only missing cues`() =
    runBlocking {
      app.secureKeyStorage.clearAllKeys()
      app.secureKeyStorage.addSingleKey("AIzaSyPartialRecKey1_123456789", "Key 1")
      app.secureKeyStorage.addSingleKey("AIzaSyPartialRecKey2_123456789", "Key 2")
      app.secureKeyStorage.setActiveKeyIndex(0)
      app.settingsRepository.setApiProvider(ApiProvider.GEMINI)

      val rawSrt =
        (1..20).joinToString("\n\n") { idx ->
          val sec = idx.toString().padStart(2, '0')
          "$idx\n00:00:$sec,000 --> 00:00:$sec,800\nLine $idx"
        }

      val requestedCounts = mutableListOf<Int>()
      val mockService =
        object : GeminiApiService {
          override suspend fun generateContentWithKeyRotation(
            model: String,
            request: GeminiGenerateContentRequest,
            autoRotate: String,
          ): Response<GeminiGenerateContentResponse> = generateContent(model, "", request)

          override suspend fun generateContent(
            model: String,
            apiKey: String,
            request: GeminiGenerateContentRequest,
          ): Response<GeminiGenerateContentResponse> {
            val prompt = request.contents.last().parts.first().text.orEmpty()
            val blocksInPrompt = com.example.srt.SrtParser.parseBlocks(prompt)
            requestedCounts.add(blocksInPrompt.size)

            // First call (20 cues) returns only 14 cues (70% < 80%); recovery should only request remaining 6 cues!
            val replyBlocks =
              if (blocksInPrompt.size == 20) {
                blocksInPrompt.take(14).joinToString("\n\n") { b ->
                  "${b.index}\n${b.timestamp}\nআংশিক ${b.index}"
                }
              } else {
                blocksInPrompt.joinToString("\n\n") { b ->
                  "${b.index}\n${b.timestamp}\nবাকি ${b.index}"
                }
              }

            return Response.success(
              GeminiGenerateContentResponse(
                candidates =
                  listOf(
                    GeminiCandidate(
                      content = GeminiContent(parts = listOf(GeminiPart(text = replyBlocks))),
                      finishReason = "STOP",
                    )
                  )
              )
            )
          }
        }

      val apiClient = GeminiApiClient(customService = mockService)
      val rotationManager = ApiKeyRotationManager(app.secureKeyStorage, apiClient)
      val service =
        SubtitleTranslationService(
          settingsRepository = app.settingsRepository,
          fileStorage = app.subtitleFileStorage,
          geminiApiClient = apiClient,
          keyRotationManager = rotationManager,
        )

      val dynamicSizer = DynamicBatchSizer(baseBatchSize = 20, enabled = true, minBatchSize = 10)
      val result =
        service.translateSrtContentWithRotation(
          rawSrtContent = rawSrt,
          fileName = "partial_recovery.srt",
          dynamicBatchSizerOverride = dynamicSizer,
          onRetryDelay = { _, _ -> },
          onCooldownWait = { minutes ->
            throw AssertionError("Unexpected cooldown ($minutes m) during partial recovery")
          },
        )

      assertTrue("Expected Success, got $result", result is SubtitleTranslationServiceResult.Success)
      val success = result as SubtitleTranslationServiceResult.Success
      assertEquals(20, success.translatedBlocks.size)
      // First 14 cues preserved from first call without re-translating; remaining 6 cues recovered in 2nd call!
      assertEquals("আংশিক 1", success.translatedBlocks[0].lines.first())
      assertEquals("আংশিক 14", success.translatedBlocks[13].lines.first())
      assertEquals("বাকি 15", success.translatedBlocks[14].lines.first())
      assertEquals("বাকি 20", success.translatedBlocks[19].lines.first())
      assertEquals(listOf(20, 6), requestedCounts)
    }
}
