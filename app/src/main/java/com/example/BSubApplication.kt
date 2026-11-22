package com.example

import android.app.Application
import androidx.annotation.VisibleForTesting
import com.example.data.local.BSubDatabase
import com.example.data.local.HistoryRepository
import com.example.data.local.SubtitleHistoryRepository
import com.example.data.local.SubtitleTranslationRepository
import com.example.data.network.ApiKeyRotationManager
import com.example.data.network.GeminiApiClient
import com.example.data.network.GeminiApiService
import com.example.data.preferences.SecureKeyStorage
import com.example.data.preferences.SettingsRepository
import com.example.data.storage.SubtitleFileStorage
import com.example.logging.TranslationLogger
import com.example.service.SubtitleTranslationService
import com.example.service.TranslationCoordinator

class BSubApplication : Application() {

  override fun onCreate() {
    super.onCreate()
    TranslationLogger.init(this, isDebugBuild = BuildConfig.DEBUG)
  }

  val database: BSubDatabase by lazy { BSubDatabase.getInstance(this) }
  val historyRepository: HistoryRepository by lazy {
    HistoryRepository(
      dao = database.translationHistoryDao(),
      subtitleHistoryDao = database.subtitleHistoryDao(),
    )
  }
  val subtitleHistoryRepository: SubtitleHistoryRepository by lazy {
    SubtitleHistoryRepository(database.subtitleHistoryDao())
  }
  val subtitleTranslationRepository: SubtitleTranslationRepository by lazy {
    SubtitleTranslationRepository(database.subtitleTranslationDao())
  }
  val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
  val secureKeyStorage: SecureKeyStorage by lazy { SecureKeyStorage(this) }
  val subtitleFileStorage: SubtitleFileStorage by lazy { SubtitleFileStorage(this) }
  val geminiApiClient: GeminiApiClient by lazy { GeminiApiClient(keyStorage = secureKeyStorage) }
  val apiKeyRotationManager: ApiKeyRotationManager by lazy {
    ApiKeyRotationManager(
      keyStorage = secureKeyStorage,
      geminiApiClient = geminiApiClient,
    )
  }
  val subtitleTranslationService: SubtitleTranslationService by lazy {
    SubtitleTranslationService(
      settingsRepository = settingsRepository,
      fileStorage = subtitleFileStorage,
      geminiApiClient = geminiApiClient,
      keyRotationManager = apiKeyRotationManager,
    )
  }

  @Volatile private var coordinatorOverride: TranslationCoordinator? = null

  private val defaultCoordinator: TranslationCoordinator by lazy {
    TranslationCoordinator(
      settingsRepository = settingsRepository,
      keyStorage = secureKeyStorage,
      historyRepository = historyRepository,
      subtitleTranslationRepository = subtitleTranslationRepository,
      fileStorage = subtitleFileStorage,
      geminiApiClient = geminiApiClient,
      keyRotationManager = apiKeyRotationManager,
      subtitleTranslationService = subtitleTranslationService,
    )
  }

  val translationCoordinator: TranslationCoordinator
    get() = coordinatorOverride ?: defaultCoordinator

  @VisibleForTesting
  internal fun overrideGeminiServiceForTesting(customService: GeminiApiService?) {
    coordinatorOverride =
      customService?.let { service ->
        val customClient = GeminiApiClient(customService = service)
        val customRotationManager =
          ApiKeyRotationManager(
            keyStorage = secureKeyStorage,
            geminiApiClient = customClient,
          )
        TranslationCoordinator(
          settingsRepository = settingsRepository,
          keyStorage = secureKeyStorage,
          historyRepository = historyRepository,
          subtitleTranslationRepository = subtitleTranslationRepository,
          fileStorage = subtitleFileStorage,
          geminiApiClient = customClient,
          keyRotationManager = customRotationManager,
          subtitleTranslationService =
            SubtitleTranslationService(
              settingsRepository = settingsRepository,
              fileStorage = subtitleFileStorage,
              geminiApiClient = customClient,
              keyRotationManager = customRotationManager,
            ),
        )
      }
  }
}
