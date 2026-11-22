package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BSubApplication
import com.example.data.local.FileTranslationProgress
import com.example.data.local.RoomTranslationProgress
import com.example.data.local.SubtitleTranslation
import com.example.data.local.SubtitleTranslationStatus
import com.example.data.local.TranslationHistoryEntity
import com.example.domain.model.ApiKeyEntry
import com.example.domain.model.AppSettings
import com.example.domain.model.FewShotExample
import com.example.domain.model.InputMode
import com.example.domain.model.NamingMode
import com.example.domain.model.ParamMode
import com.example.domain.model.PromptMode
import com.example.domain.model.SelectedSubtitleFile
import com.example.domain.model.ThemeMode
import com.example.domain.model.TranslationJobRequest
import com.example.domain.model.TranslationSessionState
import com.example.srt.SrtItem
import com.example.srt.SrtParser
import com.example.worker.TranslationWorkScheduler
import androidx.work.WorkInfo
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StudioFormState(
  val inputMode: InputMode = InputMode.MOVIE,
  val movieName: String = "",
  val releaseYear: String = "",
  val movieFiles: List<SelectedSubtitleFile> = emptyList(),
  val seriesName: String = "",
  val season: String = "",
  val seriesFiles: List<SelectedSubtitleFile> = emptyList(),
  val isAnalyzingFiles: Boolean = false,
) {
  val activeFiles: List<SelectedSubtitleFile>
    get() = if (inputMode == InputMode.MOVIE) movieFiles else seriesFiles

  val totalBlocks: Int
    get() = activeFiles.sumOf { it.totalBlocks }

  val totalLines: Int
    get() = activeFiles.sumOf { it.totalLines }

  val totalBytes: Long
    get() = activeFiles.sumOf { it.fileSizeBytes }
}

data class SubtitleCuePair(
  val index: Int,
  val timestamp: String,
  val englishText: String,
  val bengaliText: String,
  val isTranslated: Boolean,
)

data class SubtitlePreviewState(
  val fileName: String,
  val sourceFileName: String,
  val subtitleMeta: String,
  val cues: List<SubtitleCuePair>,
  val hasOriginalText: Boolean,
  val historyItem: TranslationHistoryEntity? = null,
  val isLoading: Boolean = false,
  val emptyMessage: String? = null,
) {
  val translatedCount: Int
    get() = cues.count { it.isTranslated }

  val totalCount: Int
    get() = cues.size
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

  private val app = application as BSubApplication
  private val settingsRepo = app.settingsRepository
  private val keyStorage = app.secureKeyStorage
  private val historyRepo = app.historyRepository
  private val subtitleTranslationRepo = app.subtitleTranslationRepository
  private val fileStorage = app.subtitleFileStorage
  private val coordinator = app.translationCoordinator

  val settings: StateFlow<AppSettings> =
    settingsRepo.settingsFlow.stateIn(
      scope = viewModelScope,
      started = SharingStarted.Eagerly,
      initialValue = AppSettings(),
    )

  val apiKeys: StateFlow<List<ApiKeyEntry>> =
    keyStorage.apiKeysFlow.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = emptyList(),
    )

  val historyItems: StateFlow<List<TranslationHistoryEntity>> =
    historyRepo.historyItems.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = emptyList(),
    )

  val subtitleTranslations: StateFlow<List<SubtitleTranslation>> =
    subtitleTranslationRepo.allTranslations.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = emptyList(),
    )

  val sessionState: StateFlow<TranslationSessionState> = coordinator.sessionState

  val roomTranslationProgress: StateFlow<RoomTranslationProgress> =
    subtitleTranslationRepo
      .observeOverallProgress()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RoomTranslationProgress(),
      )

  val fileTranslationProgressList: StateFlow<List<FileTranslationProgress>> =
    subtitleTranslationRepo
      .observeFileProgressList()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

  val backgroundWorkInfos: StateFlow<List<WorkInfo>> =
    TranslationWorkScheduler.observeWorkInfo(app)
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

  private val _studioForm = MutableStateFlow(StudioFormState())
  val studioForm: StateFlow<StudioFormState> = _studioForm.asStateFlow()

  private val _previewState = MutableStateFlow<SubtitlePreviewState?>(null)
  val previewState: StateFlow<SubtitlePreviewState?> = _previewState.asStateFlow()

  private val _historySearchQuery = MutableStateFlow("")
  val historySearchQuery: StateFlow<String> = _historySearchQuery.asStateFlow()

  private val _historyContentSnippets = MutableStateFlow<Map<Long, String>>(emptyMap())
  val historyContentSnippets: StateFlow<Map<Long, String>> = _historyContentSnippets.asStateFlow()

  private var historySearchJob: Job? = null

  private val _uiMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
  val uiMessages = _uiMessages.asSharedFlow()

  init {
    viewModelScope.launch {
      historyItems.collect { items ->
        val q = _historySearchQuery.value.trim()
        if (q.isNotEmpty()) {
          runHistoryContentSearch(q, items)
        }
      }
    }
  }

  // --- Studio Form Actions ---

  fun setInputMode(mode: InputMode) {
    _studioForm.update { it.copy(inputMode = mode) }
  }

  fun updateMovieName(value: String) {
    _studioForm.update { it.copy(movieName = value) }
  }

  fun updateReleaseYear(value: String) {
    _studioForm.update { it.copy(releaseYear = value) }
  }

  fun updateSeriesName(value: String) {
    _studioForm.update { it.copy(seriesName = value) }
  }

  fun updateSeason(value: String) {
    _studioForm.update { it.copy(season = value) }
  }

  fun onSingleSrtPicked(uri: Uri?) {
    if (uri == null) return
    viewModelScope.launch {
      _studioForm.update { it.copy(isAnalyzingFiles = true) }
      fileStorage.takePersistableReadPermission(uri)
      val analyzed = fileStorage.readAndAnalyzeUri(uri)
      if (analyzed == null) {
        _uiMessages.emit("Unable to read the selected subtitle file.")
        _studioForm.update { it.copy(isAnalyzingFiles = false) }
        return@launch
      }
      if (!analyzed.fileName.endsWith(".srt", ignoreCase = true) && analyzed.totalBlocks == 0) {
        _uiMessages.emit("Selected file is not a valid .srt subtitle file.")
        _studioForm.update { it.copy(isAnalyzingFiles = false) }
        return@launch
      }
      _studioForm.update {
        it.copy(
          movieFiles = listOf(analyzed),
          isAnalyzingFiles = false,
        )
      }
      if (analyzed.totalBlocks == 0) {
        _uiMessages.emit("Warning: No valid SRT blocks detected in ${analyzed.fileName}.")
      }
    }
  }

  fun onMultipleSrtPicked(uris: List<Uri>) {
    if (uris.isEmpty()) return
    viewModelScope.launch {
      _studioForm.update { it.copy(isAnalyzingFiles = true) }
      val analyzedList = ArrayList<SelectedSubtitleFile>(uris.size)
      for (uri in uris) {
        fileStorage.takePersistableReadPermission(uri)
        val analyzed = fileStorage.readAndAnalyzeUri(uri)
        if (analyzed != null && analyzed.totalBlocks > 0) {
          analyzedList.add(analyzed)
        }
      }
      if (analyzedList.isEmpty()) {
        _uiMessages.emit("No valid .srt subtitle blocks found in the selected files.")
      } else {
        _studioForm.update { state ->
          val existingUris = state.seriesFiles.map { it.uriString }.toHashSet()
          val merged =
            state.seriesFiles + analyzedList.filterNot { it.uriString in existingUris }
          state.copy(seriesFiles = merged)
        }
        _uiMessages.emit("Analyzed ${analyzedList.size} subtitle file(s).")
      }
      _studioForm.update { it.copy(isAnalyzingFiles = false) }
    }
  }

  fun removeSelectedFile(fileId: String) {
    _studioForm.update { state ->
      if (state.inputMode == InputMode.MOVIE) {
        state.copy(movieFiles = state.movieFiles.filterNot { it.id == fileId })
      } else {
        state.copy(seriesFiles = state.seriesFiles.filterNot { it.id == fileId })
      }
    }
  }

  fun moveSeriesFile(index: Int, direction: Int) {
    _studioForm.update { state ->
      val list = state.seriesFiles.toMutableList()
      val target = index + direction
      if (index !in list.indices || target !in list.indices) return@update state
      val item = list.removeAt(index)
      list.add(target, item)
      state.copy(seriesFiles = list)
    }
  }

  fun clearSelectedFiles() {
    _studioForm.update { state ->
      if (state.inputMode == InputMode.MOVIE) {
        state.copy(movieFiles = emptyList())
      } else {
        state.copy(seriesFiles = emptyList())
      }
    }
  }

  // --- Translation Execution & Abort ---

  fun startTranslation(): Boolean {
    val form = _studioForm.value
    val files = form.activeFiles.filter { it.totalBlocks > 0 }
    if (files.isEmpty()) {
      _uiMessages.tryEmit("Please select at least one valid .srt subtitle file.")
      return false
    }
    if (apiKeys.value.isEmpty()) {
      _uiMessages.tryEmit("Please add at least one Gemini API key in the Keys tab.")
      return false
    }
    if (sessionState.value.isRunningOrCooldown) {
      _uiMessages.tryEmit("A translation job is already running.")
      return false
    }

    val request =
      TranslationJobRequest(
        inputMode = form.inputMode,
        title = if (form.inputMode == InputMode.MOVIE) form.movieName else form.seriesName,
        secondaryMeta = if (form.inputMode == InputMode.MOVIE) form.releaseYear else form.season,
        files = files,
      )
    coordinator.markQueued(request)
    viewModelScope.launch {
      TranslationWorkScheduler.enqueueTranslation(app, request)
    }
    return true
  }

  fun abortTranslation() {
    TranslationWorkScheduler.cancelTranslation(app)
    coordinator.abortTranslation()
  }

  fun clearProcessingLogs() {
    coordinator.clearLogs()
    if (!sessionState.value.isRunningOrCooldown) {
      viewModelScope.launch { subtitleTranslationRepo.clearAll() }
    }
  }

  // --- API Key Manager ---

  fun saveBulkApiKeys(rawInput: String) {
    viewModelScope.launch {
      val added = keyStorage.addBulkKeys(rawInput)
      if (added > 0) {
        _uiMessages.emit("Saved $added new API key(s) securely.")
      } else {
        _uiMessages.emit("No new valid API keys found to add.")
      }
    }
  }

  fun importApiKeysFromUri(uri: Uri?) {
    if (uri == null) return
    viewModelScope.launch {
      fileStorage.takePersistableReadPermission(uri)
      val rawText = fileStorage.readPlainText(uri)
      if (rawText.isNullOrBlank()) {
        _uiMessages.emit("Unable to read API keys from the selected file.")
        return@launch
      }
      val added = keyStorage.addBulkKeys(rawText)
      if (added > 0) {
        _uiMessages.emit("Imported $added new Gemini API key(s) from file.")
      } else {
        _uiMessages.emit("No new valid Gemini API keys found in the selected file.")
      }
    }
  }

  fun addSingleApiKey(key: String, label: String) {
    viewModelScope.launch {
      if (key.trim().length < 16) {
        _uiMessages.emit("API key appears too short.")
        return@launch
      }
      keyStorage.addSingleKey(key, label)
      _uiMessages.emit("API key saved securely.")
    }
  }

  fun editApiKey(id: String, newKey: String, newLabel: String) {
    viewModelScope.launch {
      keyStorage.editKey(id, newKey, newLabel)
      _uiMessages.emit("API key updated.")
    }
  }

  fun removeApiKey(id: String) {
    viewModelScope.launch { keyStorage.removeKey(id) }
  }

  fun clearAllApiKeys() {
    viewModelScope.launch {
      keyStorage.clearAllKeys()
      _uiMessages.emit("All API keys removed.")
    }
  }

  fun resetAllKeyStatuses() {
    viewModelScope.launch {
      keyStorage.resetAllKeyStatuses()
      _uiMessages.emit("All key statuses reset to Active.")
    }
  }

  // --- Settings & Prompts ---

  fun setThemeMode(mode: ThemeMode) {
    viewModelScope.launch { settingsRepo.setThemeMode(mode) }
  }

  fun completeFirstLaunchSetup(defaultPrompt: String) {
    viewModelScope.launch {
      settingsRepo.completeFirstLaunchPromptSetup(defaultPrompt)
      _uiMessages.emit("Default prompt saved.")
    }
  }

  fun setPromptMode(mode: PromptMode) {
    viewModelScope.launch { settingsRepo.setPromptMode(mode) }
  }

  fun updateDefaultPrompt(prompt: String) {
    viewModelScope.launch {
      settingsRepo.updateDefaultPrompt(prompt)
      _uiMessages.emit("Default prompt updated.")
    }
  }

  fun resetDefaultPrompt() {
    viewModelScope.launch {
      settingsRepo.resetDefaultPromptToBuiltIn()
      _uiMessages.emit("Default prompt restored to built-in template.")
    }
  }

  fun updateManualPrompt(prompt: String) {
    viewModelScope.launch { settingsRepo.updateManualPrompt(prompt) }
  }

  fun addOrUpdateFewShotExample(
    existingId: String?,
    englishText: String,
    bengaliTranslation: String,
    note: String,
  ) {
    viewModelScope.launch {
      val current = settings.value.fewShotExamples.toMutableList()
      if (existingId == null) {
        current.add(
          FewShotExample(
            id = UUID.randomUUID().toString(),
            englishText = englishText.trim(),
            bengaliTranslation = bengaliTranslation.trim(),
            note = note.trim(),
          )
        )
      } else {
        val idx = current.indexOfFirst { it.id == existingId }
        if (idx >= 0) {
          current[idx] =
            current[idx].copy(
              englishText = englishText.trim(),
              bengaliTranslation = bengaliTranslation.trim(),
              note = note.trim(),
            )
        }
      }
      settingsRepo.saveFewShotExamples(current)
    }
  }

  fun deleteFewShotExample(id: String) {
    viewModelScope.launch {
      val updated = settings.value.fewShotExamples.filterNot { it.id == id }
      settingsRepo.saveFewShotExamples(updated)
    }
  }

  fun setNamingConfig(mode: NamingMode, prefix: String, suffix: String) {
    viewModelScope.launch { settingsRepo.setNamingConfig(mode, prefix, suffix) }
  }

  fun setChunkSize(mode: ParamMode, manualValue: Int) {
    viewModelScope.launch { settingsRepo.setChunkSize(mode, manualValue) }
  }

  fun setModel(mode: ParamMode, manualModel: String) {
    viewModelScope.launch { settingsRepo.setModel(mode, manualModel) }
  }

  fun setTemperature(mode: ParamMode, manualTemp: Float) {
    viewModelScope.launch { settingsRepo.setTemperature(mode, manualTemp) }
  }

  fun setThinkingBudget(mode: ParamMode, manualBudget: Int) {
    viewModelScope.launch { settingsRepo.setThinkingBudget(mode, manualBudget) }
  }

  fun setRetryInterval(mode: ParamMode, seconds: Int) {
    viewModelScope.launch { settingsRepo.setRetryInterval(mode, seconds) }
  }

  fun setMaxAttemptsPerKey(mode: ParamMode, attempts: Int) {
    viewModelScope.launch { settingsRepo.setMaxAttemptsPerKey(mode, attempts) }
  }

  fun setCooldownMinutes(mode: ParamMode, minutes: Int) {
    viewModelScope.launch { settingsRepo.setCooldownMinutes(mode, minutes) }
  }

  fun setContextLines(mode: ParamMode, manualValue: Int) {
    viewModelScope.launch { settingsRepo.setContextLines(mode, manualValue) }
  }

  // --- History & Export ---

  fun updateHistorySearchQuery(query: String) {
    _historySearchQuery.value = query
    val cleanQuery = query.trim()
    if (cleanQuery.isEmpty()) {
      historySearchJob?.cancel()
      _historyContentSnippets.value = emptyMap()
      return
    }
    runHistoryContentSearch(cleanQuery, historyItems.value)
  }

  private fun runHistoryContentSearch(query: String, items: List<TranslationHistoryEntity>) {
    historySearchJob?.cancel()
    historySearchJob =
      viewModelScope.launch {
        val matches = LinkedHashMap<Long, String>()
        for (item in items) {
          val snippet =
            fileStorage.findContentMatchSnippet(
              localBackupPath = item.localBackupPath,
              contentUri = item.contentUri,
              originalFileName = item.originalFileName,
              query = query,
            )
          if (snippet != null) {
            matches[item.id] = snippet
          }
        }
        if (_historySearchQuery.value.trim() == query) {
          _historyContentSnippets.value = matches
        }
      }
  }

  fun openHistoryPreview(item: TranslationHistoryEntity) {
    val displayName = item.outputFileName.ifBlank { item.originalFileName }
    _previewState.value =
      SubtitlePreviewState(
        fileName = displayName,
        sourceFileName = item.originalFileName,
        subtitleMeta = "${item.titleMetadata} • ${item.mode}",
        cues = emptyList(),
        hasOriginalText = true,
        historyItem = item,
        isLoading = true,
      )
    viewModelScope.launch {
      val translatedRaw = fileStorage.readPreviewText(item.localBackupPath, item.contentUri)
      val translatedItems =
        if (translatedRaw.isNotBlank()) SrtParser.parse(translatedRaw) else emptyList()

      val roomRows = subtitleTranslationRepo.getLatestForFile(item.originalFileName)
      val activeSelected =
        _studioForm.value.activeFiles.firstOrNull { it.fileName == item.originalFileName }
      val originalBackupRaw =
        fileStorage.readOriginalSourceBackup(item.localBackupPath, item.originalFileName)
      val originalBackupItems =
        if (originalBackupRaw.isNotBlank()) SrtParser.parse(originalBackupRaw) else emptyList()

      val originalItems: List<SrtItem> =
        when {
          originalBackupItems.isNotEmpty() -> originalBackupItems
          activeSelected != null && activeSelected.blocks.isNotEmpty() ->
            activeSelected.blocks.map {
              SrtItem(it.index, it.timestamp, it.lines.joinToString("\n"))
            }
          roomRows.isNotEmpty() ->
            roomRows.map { SrtItem(it.blockIndex, it.timestamp, it.originalEnglishText) }
          else -> emptyList()
        }

      val (cues, hasOriginal) =
        buildPairedSubtitleCues(
          originalItems = originalItems,
          translatedItems = translatedItems,
          roomRows = roomRows,
        )

      _previewState.value =
        SubtitlePreviewState(
          fileName = displayName,
          sourceFileName = item.originalFileName,
          subtitleMeta = "${item.titleMetadata} • ${item.mode}",
          cues = cues,
          hasOriginalText = hasOriginal,
          historyItem = item,
          isLoading = false,
          emptyMessage = if (cues.isEmpty()) "Subtitle preview is unavailable." else null,
        )
    }
  }

  fun openStudioFilePreview(fileName: String, selectedFile: SelectedSubtitleFile? = null) {
    viewModelScope.launch {
      val resolvedSelected =
        selectedFile ?: _studioForm.value.activeFiles.firstOrNull { it.fileName == fileName }
      val roomRows = subtitleTranslationRepo.getLatestForFile(fileName)
      val matchingHistory =
        historyItems.value.firstOrNull {
          (it.originalFileName == fileName || it.outputFileName == fileName) &&
            (it.localBackupPath.isNotBlank() || it.contentUri.isNotBlank())
        }

      if (roomRows.isEmpty() && matchingHistory != null) {
        openHistoryPreview(matchingHistory)
        return@launch
      }

      val translatedRaw =
        if (matchingHistory != null) {
          fileStorage.readPreviewText(matchingHistory.localBackupPath, matchingHistory.contentUri)
        } else {
          ""
        }
      val translatedItems =
        if (translatedRaw.isNotBlank()) SrtParser.parse(translatedRaw) else emptyList()

      val originalItems: List<SrtItem> =
        when {
          resolvedSelected != null && resolvedSelected.blocks.isNotEmpty() ->
            resolvedSelected.blocks.map {
              SrtItem(it.index, it.timestamp, it.lines.joinToString("\n"))
            }
          roomRows.isNotEmpty() ->
            roomRows.map { SrtItem(it.blockIndex, it.timestamp, it.originalEnglishText) }
          else -> emptyList()
        }

      val (cues, hasOriginal) =
        buildPairedSubtitleCues(
          originalItems = originalItems,
          translatedItems = translatedItems,
          roomRows = roomRows,
        )

      if (cues.isEmpty()) {
        _uiMessages.emit("No translated preview available yet for $fileName.")
        return@launch
      }

      _previewState.value =
        SubtitlePreviewState(
          fileName = matchingHistory?.outputFileName?.ifBlank { fileName } ?: fileName,
          sourceFileName = fileName,
          subtitleMeta = matchingHistory?.let { "${it.titleMetadata} • ${it.mode}" }
            ?: "Studio Queue Preview",
          cues = cues,
          hasOriginalText = hasOriginal,
          historyItem = matchingHistory,
          isLoading = false,
        )
    }
  }

  fun closeHistoryPreview() {
    _previewState.value = null
  }

  private fun buildPairedSubtitleCues(
    originalItems: List<SrtItem>,
    translatedItems: List<SrtItem>,
    roomRows: List<SubtitleTranslation>,
  ): Pair<List<SubtitleCuePair>, Boolean> {
    val translatedByIndex = translatedItems.associateBy { it.index }
    val roomByIndex = roomRows.associateBy { it.blockIndex }

    if (originalItems.isNotEmpty()) {
      val pairs =
        originalItems.map { orig ->
          val trans = translatedByIndex[orig.index]
          val room = roomByIndex[orig.index]
          val bengali =
            trans?.content?.takeIf { it.isNotBlank() }
              ?: room?.translatedBengaliText?.takeIf { it.isNotBlank() }.orEmpty()
          val translatedFlag =
            (trans != null && trans.content.isNotBlank()) ||
              (room?.status == SubtitleTranslationStatus.TRANSLATED &&
                !room.translatedBengaliText.isNullOrBlank())
          SubtitleCuePair(
            index = orig.index,
            timestamp = orig.timestamp,
            englishText = orig.content,
            bengaliText = bengali,
            isTranslated = translatedFlag,
          )
        }
      return pairs to true
    }

    if (roomRows.isNotEmpty()) {
      val pairs =
        roomRows.sortedBy { it.blockIndex }.map { row ->
          val trans = translatedByIndex[row.blockIndex]
          val bengali =
            row.translatedBengaliText?.takeIf { it.isNotBlank() }
              ?: trans?.content?.takeIf { it.isNotBlank() }.orEmpty()
          SubtitleCuePair(
            index = row.blockIndex,
            timestamp = row.timestamp,
            englishText = row.originalEnglishText,
            bengaliText = bengali,
            isTranslated = bengali.isNotBlank(),
          )
        }
      return pairs to true
    }

    val fallbackPairs =
      translatedItems.map { trans ->
        SubtitleCuePair(
          index = trans.index,
          timestamp = trans.timestamp,
          englishText = "",
          bengaliText = trans.content,
          isTranslated = trans.content.isNotBlank(),
        )
      }
    return fallbackPairs to false
  }

  fun reSaveToDownloads(item: TranslationHistoryEntity) {
    viewModelScope.launch {
      val saved =
        fileStorage.reSaveToDownloads(
          outputFileName = item.outputFileName,
          localBackupPath = item.localBackupPath,
          contentUri = item.contentUri,
        )
      if (saved != null) {
        _uiMessages.emit("Saved copy to ${saved.displayPath}")
      } else {
        _uiMessages.emit("Original translated content could not be read.")
      }
    }
  }

  fun exportHistoryItemToUri(item: TranslationHistoryEntity, targetUri: Uri?) {
    if (targetUri == null) return
    viewModelScope.launch {
      val ok = fileStorage.exportToUri(item.localBackupPath, item.contentUri, targetUri)
      if (ok) {
        _uiMessages.emit("Exported ${item.outputFileName} successfully.")
      } else {
        _uiMessages.emit("Failed to export ${item.outputFileName}.")
      }
    }
  }

  fun createShareIntent(item: TranslationHistoryEntity): Intent? {
    return fileStorage.createShareIntent(
      outputFileName = item.outputFileName,
      localBackupPath = item.localBackupPath,
      contentUri = item.contentUri,
    )
  }

  fun deleteHistoryItem(item: TranslationHistoryEntity) {
    viewModelScope.launch {
      fileStorage.deleteSavedFiles(item.localBackupPath)
      historyRepo.deleteRecord(item.id)
      _uiMessages.emit("Deleted ${item.outputFileName} from history.")
    }
  }

  // --- SubtitleTranslation Repository Abstractions ---

  fun observeJobTranslations(jobId: String): kotlinx.coroutines.flow.Flow<List<SubtitleTranslation>> =
    subtitleTranslationRepo.observeJobTranslations(jobId)

  fun observeTranslationsByStatus(
    status: SubtitleTranslationStatus
  ): kotlinx.coroutines.flow.Flow<List<SubtitleTranslation>> =
    subtitleTranslationRepo.observeByStatus(status)

  fun insertSubtitleTranslation(
    jobId: String,
    fileName: String,
    blockIndex: Int,
    timestamp: String,
    originalEnglishText: String,
    translatedBengaliText: String? = null,
  ) {
    viewModelScope.launch {
      subtitleTranslationRepo.insertTranslation(
        jobId = jobId,
        fileName = fileName,
        blockIndex = blockIndex,
        timestamp = timestamp,
        originalEnglishText = originalEnglishText,
        translatedBengaliText = translatedBengaliText,
      )
    }
  }

  fun markSubtitleTranslated(id: Long, translatedBengaliText: String) {
    viewModelScope.launch {
      subtitleTranslationRepo.markAsTranslated(id, translatedBengaliText)
    }
  }

  fun deleteSubtitleJob(jobId: String) {
    viewModelScope.launch { subtitleTranslationRepo.deleteJob(jobId) }
  }

  fun clearSubtitleTranslations() {
    viewModelScope.launch { subtitleTranslationRepo.clearAll() }
  }
}
