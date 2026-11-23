package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Preview
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import com.example.data.local.FileTranslationProgress
import com.example.data.local.RoomTranslationProgress
import com.example.data.storage.SubtitleFileStorage
import com.example.domain.model.ApiKeyEntry
import com.example.domain.model.ApiProvider
import com.example.domain.model.AppSettings
import com.example.domain.model.InputMode
import com.example.domain.model.SelectedSubtitleFile
import com.example.domain.model.TranslationSessionState
import com.example.srt.SrtEngine
import com.example.ui.components.SectionTitleRow
import com.example.ui.components.StudioSectionCard
import com.example.ui.theme.BSubShape
import com.example.ui.theme.CloverEmerald
import com.example.ui.theme.CloverHeroDark
import com.example.ui.theme.CloverMintInk
import com.example.ui.theme.CloverMintSoft
import com.example.ui.theme.CloverYellow
import com.example.ui.theme.CloverYellowInk
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalThemeController
import com.example.ui.theme.PlusJakartaFamily
import com.example.ui.theme.SpaceGroteskFamily
import com.example.ui.viewmodel.StudioFormState
import kotlin.math.ceil

@Composable
fun StudioScreen(
  formState: StudioFormState,
  settings: AppSettings,
  apiKeys: List<ApiKeyEntry> = emptyList(),
  sessionState: TranslationSessionState,
  roomProgress: RoomTranslationProgress = RoomTranslationProgress(),
  fileProgressList: List<FileTranslationProgress> = emptyList(),
  workInfos: List<WorkInfo> = emptyList(),
  onInputModeChange: (InputMode) -> Unit,
  onMovieNameChange: (String) -> Unit,
  onReleaseYearChange: (String) -> Unit,
  onSeriesNameChange: (String) -> Unit,
  onSeasonChange: (String) -> Unit,
  onSingleSrtPicked: (Uri?) -> Unit,
  onMultipleSrtPicked: (List<Uri>) -> Unit,
  onRemoveFile: (String) -> Unit,
  onMoveSeriesFile: (Int, Int) -> Unit,
  onClearFiles: () -> Unit,
  onStartTranslation: () -> Unit,
  onAbortTranslation: () -> Unit,
  onClearLogs: () -> Unit,
  onImportBulkKeys: (String) -> Unit = {},
  onImportKeysFile: (Uri?) -> Unit = {},
  onPreviewFile: (String, SelectedSubtitleFile?) -> Unit = { _, _ -> },
  onNavigateToKeys: () -> Unit = {},
  onNavigateToEngineSettings: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current

  val singleSrtLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      onSingleSrtPicked(uri)
    }

  val multiSrtLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
      onMultipleSrtPicked(uris)
    }

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
      onStartTranslation()
    }

  val isRunning = sessionState.isRunningOrCooldown

  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // 1. Studio Atmospheric Banner Header
    StudioHeroBanner(
      activeProvider = settings.apiProvider,
      modelName = settings.effectiveModel,
      chunkSize =
        if (isRunning && sessionState.currentBatchSize > 0) sessionState.currentBatchSize
        else settings.effectiveChunkSize,
      onEngineBadgeClick = onNavigateToEngineSettings ?: onNavigateToKeys,
    )

    // 2. Mode Switcher (Movie / Series) + Metadata + File Picker
    StudioSectionCard {
      SectionTitleRow(
        title = "Subtitle Source",
        subtitle = "English .srt to Bengali studio",
        icon = Icons.Rounded.Subtitles,
      )

      Spacer(modifier = Modifier.height(14.dp))

      val segmentedColors =
        SegmentedButtonDefaults.colors(
          activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
          activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          inactiveContainerColor = MaterialTheme.colorScheme.surface,
          inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
          activeBorderColor = MaterialTheme.colorScheme.outlineVariant,
          inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant,
        )

      SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
          selected = formState.inputMode == InputMode.MOVIE,
          onClick = { if (!isRunning) onInputModeChange(InputMode.MOVIE) },
          enabled = !isRunning,
          shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
          colors = segmentedColors,
          icon = {
            Icon(
              imageVector = Icons.Rounded.Movie,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
          },
          modifier = Modifier.testTag("movie_mode_tab"),
        ) {
          Text("Movie")
        }

        SegmentedButton(
          selected = formState.inputMode == InputMode.SERIES,
          onClick = { if (!isRunning) onInputModeChange(InputMode.SERIES) },
          enabled = !isRunning,
          shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
          colors = segmentedColors,
          icon = {
            Icon(
              imageVector = Icons.Rounded.Tv,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
          },
          modifier = Modifier.testTag("series_mode_tab"),
        ) {
          Text("Series")
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      val tonalPickerColors =
        ButtonDefaults.filledTonalButtonColors(
          containerColor = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )

      if (formState.inputMode == InputMode.MOVIE) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OutlinedTextField(
            value = formState.movieName,
            onValueChange = onMovieNameChange,
            label = { Text("Movie Name") },
            placeholder = { Text("e.g., Interstellar") },
            singleLine = true,
            enabled = !isRunning,
            modifier = Modifier.weight(2f).testTag("movie_name_input"),
          )
          OutlinedTextField(
            value = formState.releaseYear,
            onValueChange = onReleaseYearChange,
            label = { Text("Year") },
            placeholder = { Text("2014") },
            singleLine = true,
            enabled = !isRunning,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f).testTag("release_year_input"),
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        FilledTonalButton(
          onClick = { singleSrtLauncher.launch(SubtitleFileStorage.SRT_PICKER_MIME_TYPES) },
          enabled = !isRunning && !formState.isAnalyzingFiles,
          shape = RoundedCornerShape(14.dp),
          colors = tonalPickerColors,
          modifier =
            Modifier.fillMaxWidth()
              .height(50.dp)
              .minimumInteractiveComponentSize()
              .testTag("pick_single_srt_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            if (formState.movieFiles.isEmpty()) "Select .srt Subtitle File"
            else "Change .srt Subtitle File"
          )
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OutlinedTextField(
            value = formState.seriesName,
            onValueChange = onSeriesNameChange,
            label = { Text("Series Name") },
            placeholder = { Text("e.g., Andor") },
            singleLine = true,
            enabled = !isRunning,
            modifier = Modifier.weight(2f).testTag("series_name_input"),
          )
          OutlinedTextField(
            value = formState.season,
            onValueChange = onSeasonChange,
            label = { Text("Season") },
            placeholder = { Text("S02") },
            singleLine = true,
            enabled = !isRunning,
            modifier = Modifier.weight(1f).testTag("series_season_input"),
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        FilledTonalButton(
          onClick = { multiSrtLauncher.launch(SubtitleFileStorage.SRT_PICKER_MIME_TYPES) },
          enabled = !isRunning && !formState.isAnalyzingFiles,
          shape = RoundedCornerShape(14.dp),
          colors = tonalPickerColors,
          modifier =
            Modifier.fillMaxWidth()
              .height(50.dp)
              .minimumInteractiveComponentSize()
              .testTag("pick_multi_srt_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            if (formState.seriesFiles.isEmpty()) "Select Multiple .srt Files"
            else "Add More .srt Files (${formState.seriesFiles.size} queued)"
          )
        }
      }

      if (formState.isAnalyzingFiles) {
        Spacer(modifier = Modifier.height(10.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Analyzing subtitle blocks and lines...",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    // 3. Analyzed Subtitle Counter & File Queue with Live Status Badges
    AnimatedVisibility(visible = formState.activeFiles.isNotEmpty()) {
      SubtitleAnalysisCard(
        formState = formState,
        chunkSize = settings.effectiveChunkSize,
        isRunning = isRunning,
        sessionState = sessionState,
        fileProgressList = fileProgressList,
        onRemoveFile = onRemoveFile,
        onMoveSeriesFile = onMoveSeriesFile,
        onClearFiles = onClearFiles,
        onPreviewFile = onPreviewFile,
      )
    }

    // 4. Primary Start / Abort Action Row
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!isRunning) {
        Button(
          onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              val granted =
                ContextCompat.checkSelfPermission(
                  context,
                  Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
              if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
              } else {
                onStartTranslation()
              }
            } else {
              onStartTranslation()
            }
          },
          enabled = formState.activeFiles.isNotEmpty() && !formState.isAnalyzingFiles,
          shape = BSubShape.petalButtonLarge,
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primary,
              contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
          modifier =
            Modifier.weight(1f)
              .height(54.dp)
              .testTag("start_translation_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text("Start Translation", style = MaterialTheme.typography.titleMedium)
        }
      } else {
        Button(
          onClick = onAbortTranslation,
          shape = BSubShape.petalButtonLarge,
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.error,
              contentColor = MaterialTheme.colorScheme.onError,
            ),
          modifier =
            Modifier.weight(1f)
              .height(54.dp)
              .testTag("abort_translation_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Stop,
            contentDescription = "Abort Translation",
            modifier = Modifier.size(22.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text("Abort Translation", style = MaterialTheme.typography.titleMedium)
        }
      }
    }

    // 6. Live Processing Status, Multi-File WorkManager Dashboard & Logs
    LiveProcessingSection(
      sessionState = sessionState,
      roomProgress = roomProgress,
      fileProgressList = fileProgressList,
      queuedFiles = formState.activeFiles,
      workInfos = workInfos,
      onClearLogs = onClearLogs,
      onPreviewFile = onPreviewFile,
    )

    Spacer(modifier = Modifier.height(12.dp))
  }
}

@Composable
private fun StudioHeroBanner(
  activeProvider: ApiProvider,
  modelName: String,
  chunkSize: Int,
  onEngineBadgeClick: () -> Unit,
) {
  val themeController = LocalThemeController.current
  val isDark = themeController.isDarkTheme
  val heroColor = if (isDark) CloverHeroDark else CloverEmerald

  Surface(
    modifier = Modifier.fillMaxWidth().height(106.dp).testTag("hero_banner"),
    shape = BSubShape.petal,
    color = heroColor,
    tonalElevation = 0.dp,
  ) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 11.dp)) {
      val density = LocalDensity.current
      val textMeasurer = rememberTextMeasurer()
      val isCompactWidth = maxWidth < 380.dp
      val rightStackWidth = if (isCompactWidth) 120.dp else 156.dp
      val columnGap = if (isCompactWidth) 10.dp else 12.dp
      val leftWidthPx =
        with(density) { (maxWidth - rightStackWidth - columnGap).coerceAtLeast(1.dp).roundToPx() }
      val innerHeightPx = with(density) { maxHeight.coerceAtLeast(1.dp).roundToPx() }
      val verticalGapPx = with(density) { 4.dp.roundToPx() }

      val titleText = "BSub Translator"
      val subtitleText = "English to Bengali Subtitle Studio"
      val baseTitleStyle =
        MaterialTheme.typography.headlineMedium.copy(
          fontFamily = SpaceGroteskFamily,
          fontWeight = FontWeight.Bold,
          letterSpacing = (-0.5).sp,
        )
      val baseSubtitleStyle =
        MaterialTheme.typography.bodyMedium.copy(
          fontFamily = PlusJakartaFamily,
          fontWeight = FontWeight.Medium,
          letterSpacing = 0.1.sp,
        )

      val (fittedTitleSize, fittedSubtitleSize, subtitleMaxLines) =
        remember(leftWidthPx, innerHeightPx, density.density, density.fontScale, isCompactWidth) {
          val preferredSubSp = if (isCompactWidth) 12f else 13f
          val minSingleLineSubSp = 9.5f
          var chosenSubSp = preferredSubSp
          var chosenSubMaxLines = 2
          var subResult =
            textMeasurer.measure(
              text = subtitleText,
              style =
                baseSubtitleStyle.copy(
                  fontSize = chosenSubSp.sp,
                  lineHeight = (chosenSubSp * 1.28f).sp,
                ),
              constraints = Constraints(maxWidth = leftWidthPx),
              maxLines = 2,
            )

          var probeSp = preferredSubSp
          while (probeSp >= minSingleLineSubSp) {
            val probeResult =
              textMeasurer.measure(
                text = subtitleText,
                style =
                  baseSubtitleStyle.copy(
                    fontSize = probeSp.sp,
                    lineHeight = (probeSp * 1.28f).sp,
                  ),
                constraints = Constraints(maxWidth = leftWidthPx),
                maxLines = 1,
                softWrap = false,
              )
            if (!probeResult.hasVisualOverflow && probeResult.size.width <= leftWidthPx) {
              chosenSubSp = probeSp
              chosenSubMaxLines = 1
              subResult = probeResult
              break
            }
            probeSp -= 0.5f
          }

          val targetWidthPx =
            if (chosenSubMaxLines == 1) {
              (subResult.getLineRight(0) - subResult.getLineLeft(0))
                .coerceAtLeast(1f)
                .coerceAtMost(leftWidthPx.toFloat())
            } else {
              leftWidthPx.toFloat()
            }

          val maxTitleHeightPx =
            (innerHeightPx - subResult.size.height - verticalGapPx).coerceAtLeast(1)

          val bestTitleSp =
            fitHeroTitleFontSizeSp(
              targetWidthPx = targetWidthPx,
              maxHeightPx = maxTitleHeightPx.toFloat(),
            ) { candidateSp ->
              val titleResult =
                textMeasurer.measure(
                  text = titleText,
                  style =
                    baseTitleStyle.copy(
                      fontSize = candidateSp.sp,
                      lineHeight = (candidateSp * 1.1f).sp,
                    ),
                  constraints = Constraints(maxWidth = leftWidthPx),
                  maxLines = 1,
                  softWrap = false,
                )
              val width =
                if (titleResult.hasVisualOverflow) Float.MAX_VALUE
                else titleResult.getLineRight(0) - titleResult.getLineLeft(0)
              width to titleResult.size.height.toFloat()
            }

          Triple(bestTitleSp.sp, chosenSubSp.sp, chosenSubMaxLines)
        }

      val pillShape = RoundedCornerShape(50)
      val pillTextStyle =
        MaterialTheme.typography.labelSmall.copy(
          fontFamily = JetBrainsMonoFamily,
          fontSize = if (isCompactWidth) 10.sp else 10.5.sp,
          lineHeight = 12.sp,
        )

      Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(columnGap),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(
          modifier = Modifier.weight(1f).fillMaxHeight(),
          verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
          Text(
            text = titleText,
            style =
              baseTitleStyle.copy(
                fontSize = fittedTitleSize,
                lineHeight = fittedTitleSize * 1.1f,
              ),
            color = Color.White,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = subtitleText,
            style =
              baseSubtitleStyle.copy(
                fontSize = fittedSubtitleSize,
                lineHeight = fittedSubtitleSize * 1.28f,
              ),
            color = Color(0xFFD2F2E8),
            maxLines = subtitleMaxLines,
            overflow = TextOverflow.Ellipsis,
          )
        }

        Column(
          modifier = Modifier.width(rightStackWidth).fillMaxHeight(),
          verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
          Surface(
            shape = pillShape,
            color = CloverMintSoft,
            modifier =
              Modifier.weight(1f)
                .fillMaxWidth()
                .clip(pillShape)
                .semantics(mergeDescendants = true) {}
                .clickable(
                  role = Role.Button,
                  onClickLabel = "Configure Active Engine",
                  onClick = onEngineBadgeClick,
                )
                .testTag("hero_engine_chip"),
          ) {
            Row(
              modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
              horizontalArrangement = Arrangement.Center,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = "Active Engine",
                tint = CloverMintInk,
                modifier = Modifier.size(12.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "Engine: ${activeProvider.displayName}",
                style = pillTextStyle.copy(fontWeight = FontWeight.SemiBold),
                color = CloverMintInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }

          Surface(
            shape = pillShape,
            color = Color.White.copy(alpha = 0.16f),
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("hero_model_chip"),
          ) {
            Box(
              modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = modelName,
                style = pillTextStyle.copy(fontWeight = FontWeight.Medium),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }

          Surface(
            shape = pillShape,
            color = CloverYellow,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("hero_chunk_chip"),
          ) {
            Box(
              modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = "$chunkSize blocks/batch",
                style = pillTextStyle.copy(fontWeight = FontWeight.SemiBold),
                color = CloverYellowInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }
  }
}

internal fun fitHeroTitleFontSizeSp(
  targetWidthPx: Float,
  maxHeightPx: Float,
  minSp: Float = 14f,
  maxSp: Float = 36f,
  measureTitle: (Float) -> Pair<Float, Float>,
): Float {
  var lowSp = minSp
  var highSp = maxSp
  var bestTitleSp = minSp
  repeat(14) {
    val midSp = (lowSp + highSp) * 0.5f
    val (measuredWidth, measuredHeight) = measureTitle(midSp)
    if (measuredWidth <= targetWidthPx && measuredHeight <= maxHeightPx) {
      bestTitleSp = midSp
      lowSp = midSp
    } else {
      highSp = midSp
    }
  }
  return bestTitleSp
}

@Composable
private fun SubtitleAnalysisCard(
  formState: StudioFormState,
  chunkSize: Int,
  isRunning: Boolean,
  sessionState: TranslationSessionState,
  fileProgressList: List<FileTranslationProgress>,
  onRemoveFile: (String) -> Unit,
  onMoveSeriesFile: (Int, Int) -> Unit,
  onClearFiles: () -> Unit,
  onPreviewFile: (String, SelectedSubtitleFile?) -> Unit = { _, _ -> },
) {
  val files = formState.activeFiles
  val totalBatches =
    files.sumOf { file ->
      if (file.totalBlocks == 0) 0 else ceil(file.totalBlocks.toDouble() / chunkSize).toInt()
    }

  StudioSectionCard {
    SectionTitleRow(
      title = "Subtitle Analysis",
      subtitle =
        if (files.size == 1) "Ready for batch translation"
        else "${files.size} files queued sequentially",
      icon = Icons.Rounded.Description,
      trailing = {
        if (!isRunning) {
          TextButton(onClick = onClearFiles, modifier = Modifier.testTag("clear_files_button")) {
            Text("Clear")
          }
        }
      },
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Aggregate Counters
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      MetricTile(
        label = "Blocks",
        value = formState.totalBlocks.toString(),
        modifier = Modifier.weight(1f),
      )
      MetricTile(
        label = "Text Lines",
        value = formState.totalLines.toString(),
        modifier = Modifier.weight(1f),
      )
      MetricTile(
        label = "Batches",
        value = totalBatches.toString(),
        modifier = Modifier.weight(1f),
      )
      MetricTile(
        label = "Size",
        value = SrtEngine.formatFileSize(formState.totalBytes),
        modifier = Modifier.weight(1f),
      )
    }

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(10.dp))

    files.forEachIndexed { idx, file ->
      val resolvedProgress =
        resolveFileQueueProgress(
          index = idx,
          file = file,
          fileProgressList = fileProgressList,
          sessionState = sessionState,
        )
      val canPreview =
        resolvedProgress.status == FileQueueStatus.COMPLETED || resolvedProgress.translatedBlocks > 0
      FileQueueRow(
        index = idx,
        totalCount = files.size,
        file = file,
        chunkSize = chunkSize,
        resolvedProgress = resolvedProgress,
        isSeriesMode = formState.inputMode == InputMode.SERIES,
        isRunning = isRunning,
        canPreview = canPreview,
        onPreview = { onPreviewFile(file.fileName, file) },
        onMoveUp = { onMoveSeriesFile(idx, -1) },
        onMoveDown = { onMoveSeriesFile(idx, 1) },
        onRemove = { onRemoveFile(file.id) },
      )
      if (idx < files.lastIndex) {
        Spacer(modifier = Modifier.height(8.dp))
      }
    }
  }
}

@Composable
internal fun MetricTile(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.semantics(mergeDescendants = true) {},
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = value,
        style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFamily),
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun FileQueueRow(
  index: Int,
  totalCount: Int,
  file: SelectedSubtitleFile,
  chunkSize: Int,
  resolvedProgress: ResolvedFileProgress,
  isSeriesMode: Boolean,
  isRunning: Boolean,
  canPreview: Boolean,
  onPreview: () -> Unit,
  onMoveUp: () -> Unit,
  onMoveDown: () -> Unit,
  onRemove: () -> Unit,
) {
  val batches = if (file.totalBlocks == 0) 0 else ceil(file.totalBlocks.toDouble() / chunkSize).toInt()
  Surface(
    modifier =
      Modifier.clip(RoundedCornerShape(12.dp))
        .let { base -> if (canPreview) base.clickable(onClick = onPreview) else base }
        .testTag("file_queue_item_$index"),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FileStatusIndicator(
        status = resolvedProgress.status,
        progressFraction = resolvedProgress.progressFraction,
        modifier = Modifier.testTag("file_status_indicator_$index"),
      )
      Spacer(modifier = Modifier.width(8.dp))

      if (isSeriesMode) {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
        ) {
          Text(
            text = "#${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
        Spacer(modifier = Modifier.width(10.dp))
      }

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = file.fileName,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text =
            "${file.totalBlocks} blocks • ${file.totalLines} lines • $batches batch(es) • ${SrtEngine.formatFileSize(file.fileSizeBytes)}",
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FileStatusBadge(
            status = resolvedProgress.status,
            modifier = Modifier.testTag("file_status_badge_$index"),
          )
          if (canPreview) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.testTag("file_preview_hint_$index"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Preview,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "Tap to preview",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
              )
            }
          }
        }
      }

      if (!isRunning) {
        if (isSeriesMode && totalCount > 1) {
          IconButton(
            onClick = onMoveUp,
            enabled = index > 0,
            modifier = Modifier.minimumInteractiveComponentSize(),
          ) {
            Icon(
              imageVector = Icons.Rounded.ArrowUpward,
              contentDescription = "Move file up",
              modifier = Modifier.size(18.dp),
            )
          }
          IconButton(
            onClick = onMoveDown,
            enabled = index < totalCount - 1,
            modifier = Modifier.minimumInteractiveComponentSize(),
          ) {
            Icon(
              imageVector = Icons.Rounded.ArrowDownward,
              contentDescription = "Move file down",
              modifier = Modifier.size(18.dp),
            )
          }
        }
        IconButton(
          onClick = onRemove,
          modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
          Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "Remove file",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
        }
      }
    }
  }
}
