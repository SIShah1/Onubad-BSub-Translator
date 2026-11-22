package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Preview
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WorkHistory
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.example.data.local.FileTranslationProgress
import com.example.data.local.RoomTranslationProgress
import com.example.data.preferences.SecureKeyStorage
import com.example.domain.model.ApiKeyEntry
import com.example.domain.model.ApiKeyStatus
import com.example.domain.model.LogLevel
import com.example.domain.model.ProcessingLogEntry
import com.example.domain.model.SelectedSubtitleFile
import com.example.domain.model.TranslationSessionState
import com.example.domain.model.TranslationStatus
import com.example.ui.components.SectionTitleRow
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioSectionCard
import com.example.ui.theme.BSubShape
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalThemeController
import java.util.Locale

internal enum class FileQueueStatus(val label: String) {
  PENDING("Pending"),
  IN_PROGRESS("In Progress"),
  COMPLETED("Completed"),
  FAILED("Failed"),
}

internal data class ResolvedFileProgress(
  val fileName: String,
  val totalBlocks: Int,
  val translatedBlocks: Int,
  val progressFraction: Float,
  val progressPercent: Int,
  val status: FileQueueStatus,
)

internal fun resolveFileQueueProgress(
  index: Int,
  file: SelectedSubtitleFile,
  fileProgressList: List<FileTranslationProgress>,
  sessionState: TranslationSessionState,
): ResolvedFileProgress {
  val matchedProgress =
    fileProgressList.filter { it.fileName == file.fileName }.let { matches ->
      if (matches.size == 1) matches.first()
      else matches.getOrNull(0)
    }

  if (matchedProgress != null && matchedProgress.totalBlocks > 0) {
    val status =
      when {
        matchedProgress.translatedBlocks >= matchedProgress.totalBlocks -> FileQueueStatus.COMPLETED
        sessionState.isRunningOrCooldown &&
          (sessionState.currentFileName == file.fileName || matchedProgress.translatedBlocks > 0) ->
          FileQueueStatus.IN_PROGRESS
        sessionState.status == TranslationStatus.FAILED &&
          matchedProgress.translatedBlocks < matchedProgress.totalBlocks -> FileQueueStatus.FAILED
        else -> FileQueueStatus.PENDING
      }
    return ResolvedFileProgress(
      fileName = file.fileName,
      totalBlocks = matchedProgress.totalBlocks,
      translatedBlocks = matchedProgress.translatedBlocks,
      progressFraction = matchedProgress.progressFraction,
      progressPercent = matchedProgress.progressPercent,
      status = status,
    )
  }

  val status =
    when {
      sessionState.status == TranslationStatus.COMPLETED && index < sessionState.completedFiles ->
        FileQueueStatus.COMPLETED
      sessionState.isRunningOrCooldown && index < sessionState.completedFiles ->
        FileQueueStatus.COMPLETED
      sessionState.isRunningOrCooldown &&
        (sessionState.currentFileName == file.fileName ||
          (sessionState.currentFileIndex > 0 && index == sessionState.currentFileIndex - 1)) ->
        FileQueueStatus.IN_PROGRESS
      sessionState.status == TranslationStatus.FAILED &&
        sessionState.currentFileName == file.fileName -> FileQueueStatus.FAILED
      else -> FileQueueStatus.PENDING
    }

  val translatedBlocks = if (status == FileQueueStatus.COMPLETED) file.totalBlocks else 0
  val fraction =
    if (status == FileQueueStatus.COMPLETED && file.totalBlocks > 0) 1f else 0f
  return ResolvedFileProgress(
    fileName = file.fileName,
    totalBlocks = file.totalBlocks,
    translatedBlocks = translatedBlocks,
    progressFraction = fraction,
    progressPercent = (fraction * 100).toInt(),
    status = status,
  )
}

@Composable
internal fun FileStatusIndicator(
  status: FileQueueStatus,
  progressFraction: Float,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier.size(22.dp).semantics(mergeDescendants = true) {},
    contentAlignment = Alignment.Center,
  ) {
    when (status) {
      FileQueueStatus.IN_PROGRESS -> {
        if (progressFraction > 0f) {
          CircularProgressIndicator(
            progress = { progressFraction },
            strokeWidth = 2.4.dp,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(18.dp),
          )
        } else {
          CircularProgressIndicator(
            strokeWidth = 2.4.dp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
        }
      }
      FileQueueStatus.COMPLETED -> {
        Icon(
          imageVector = Icons.Rounded.CheckCircle,
          contentDescription = "Completed",
          tint = MaterialTheme.colorScheme.tertiary,
          modifier = Modifier.size(19.dp),
        )
      }
      FileQueueStatus.FAILED -> {
        Icon(
          imageVector = Icons.Rounded.WarningAmber,
          contentDescription = "Failed",
          tint = MaterialTheme.colorScheme.error,
          modifier = Modifier.size(19.dp),
        )
      }
      FileQueueStatus.PENDING -> {
        Icon(
          imageVector = Icons.Rounded.Schedule,
          contentDescription = "Pending",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

@Composable
internal fun FileStatusBadge(
  status: FileQueueStatus,
  modifier: Modifier = Modifier,
) {
  val (containerColor, contentColor) =
    when (status) {
      FileQueueStatus.PENDING ->
        MaterialTheme.colorScheme.surfaceContainer to MaterialTheme.colorScheme.onSurfaceVariant
      FileQueueStatus.IN_PROGRESS ->
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
      FileQueueStatus.COMPLETED ->
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
      FileQueueStatus.FAILED ->
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
  StatusPill(
    text = status.label,
    containerColor = containerColor,
    contentColor = contentColor,
    modifier = modifier.semantics(mergeDescendants = true) {},
  )
}

@Composable
internal fun StudioBulkKeyImportCard(
  apiKeys: List<ApiKeyEntry>,
  isRunning: Boolean,
  onImportBulkKeys: (String) -> Unit,
  onLaunchKeyFilePicker: () -> Unit,
  onNavigateToKeys: () -> Unit,
) {
  var bulkInput by rememberSaveable { mutableStateOf("") }
  var isExpanded by rememberSaveable { mutableStateOf(false) }
  val showEditor = isExpanded || apiKeys.isEmpty()
  val detectedCount = remember(bulkInput) { SecureKeyStorage.parseBulkKeys(bulkInput).size }
  val activeKeysCount = apiKeys.count { it.status == ApiKeyStatus.ACTIVE }

  StudioSectionCard(
    modifier = Modifier.testTag("studio_bulk_keys_card"),
  ) {
    SectionTitleRow(
      title = "Gemini API Key Pool",
      subtitle =
        if (apiKeys.isEmpty()) "No keys saved — paste keys or import a .txt file"
        else "$activeKeysCount active / ${apiKeys.size} total keys for rotation",
      icon = Icons.Rounded.VpnKey,
      trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          TextButton(
            onClick = onNavigateToKeys,
            modifier = Modifier.testTag("studio_manage_keys_button"),
          ) {
            Text("Manage")
          }
          if (apiKeys.isNotEmpty()) {
            IconButton(
              onClick = { isExpanded = !isExpanded },
              modifier =
                Modifier.minimumInteractiveComponentSize()
                  .testTag("studio_toggle_bulk_import_button"),
            ) {
              Icon(
                imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (isExpanded) "Collapse bulk import" else "Expand bulk import",
              )
            }
          }
        }
      },
    )

    if (apiKeys.isEmpty()) {
      Spacer(modifier = Modifier.height(10.dp))
      Surface(
        modifier =
          Modifier.fillMaxWidth()
            .clickable(onClick = onNavigateToKeys)
            .testTag("missing_keys_banner"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
      ) {
        Row(
          modifier = Modifier.padding(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
          )
          Spacer(modifier = Modifier.width(10.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "No Gemini API Keys Saved",
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
              text = "Import keys below or tap to open full Key Manager.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
            )
          }
        }
      }
    }

    AnimatedVisibility(visible = showEditor) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
          value = bulkInput,
          onValueChange = { bulkInput = it },
          label = { Text("Paste Multiple Gemini API Keys") },
          placeholder = { Text("AIzaSy...\nAIzaSy...\n(Newlines, commas, or key=value)") },
          minLines = 3,
          maxLines = 6,
          enabled = !isRunning,
          textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
          modifier = Modifier.fillMaxWidth().testTag("studio_bulk_keys_input"),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text =
            if (detectedCount > 0) "$detectedCount key(s) detected for multi-key rotation"
            else "Paste keys manually or import a .txt key file",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("studio_bulk_keys_detected_count"),
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OutlinedButton(
            onClick = onLaunchKeyFilePicker,
            enabled = !isRunning,
            shape = BSubShape.petalButton,
            modifier =
              Modifier.weight(1f)
                .minimumInteractiveComponentSize()
                .testTag("studio_import_keys_file_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.UploadFile,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Import .txt", maxLines = 1, overflow = TextOverflow.Ellipsis)
          }

          Button(
            onClick = {
              onImportBulkKeys(bulkInput)
              bulkInput = ""
            },
            enabled = detectedCount > 0 && !isRunning,
            shape = BSubShape.petalButton,
            modifier =
              Modifier.weight(1f)
                .minimumInteractiveComponentSize()
                .testTag("studio_save_bulk_keys_button"),
          ) {
            Text("Save Keys", maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
      }
    }
  }
}

@Composable
internal fun LiveProcessingSection(
  sessionState: TranslationSessionState,
  roomProgress: RoomTranslationProgress,
  fileProgressList: List<FileTranslationProgress>,
  queuedFiles: List<SelectedSubtitleFile>,
  workInfos: List<WorkInfo>,
  onClearLogs: () -> Unit,
  onPreviewFile: (String, SelectedSubtitleFile?) -> Unit = { _, _ -> },
) {
  val hasRoomData = roomProgress.totalBlocks > 0
  val targetFraction =
    if (hasRoomData) roomProgress.progressFraction else sessionState.overallProgressFraction
  val animatedProgress by
    animateFloatAsState(
      targetValue = targetFraction,
      label = "translation_progress",
    )
  val percentInt = (animatedProgress * 100f).toInt().coerceIn(0, 100)
  val logListState = rememberLazyListState()
  val latestWorkInfo = workInfos.firstOrNull()

  val dashboardRows =
    remember(queuedFiles, fileProgressList, sessionState) {
      if (queuedFiles.isNotEmpty()) {
        queuedFiles.mapIndexed { index, file ->
          resolveFileQueueProgress(
            index = index,
            file = file,
            fileProgressList = fileProgressList,
            sessionState = sessionState,
          )
        }
      } else {
        fileProgressList.map { fileProgress ->
          val status =
            when {
              fileProgress.translatedBlocks >= fileProgress.totalBlocks && fileProgress.totalBlocks > 0 ->
                FileQueueStatus.COMPLETED
              sessionState.isRunningOrCooldown &&
                (sessionState.currentFileName == fileProgress.fileName || fileProgress.translatedBlocks > 0) ->
                FileQueueStatus.IN_PROGRESS
              sessionState.status == TranslationStatus.FAILED &&
                fileProgress.translatedBlocks < fileProgress.totalBlocks -> FileQueueStatus.FAILED
              else -> FileQueueStatus.PENDING
            }
          ResolvedFileProgress(
            fileName = fileProgress.fileName,
            totalBlocks = fileProgress.totalBlocks,
            translatedBlocks = fileProgress.translatedBlocks,
            progressFraction = fileProgress.progressFraction,
            progressPercent = fileProgress.progressPercent,
            status = status,
          )
        }
      }
    }

  LaunchedEffect(sessionState.logs.size) {
    if (sessionState.logs.isNotEmpty()) {
      logListState.animateScrollToItem(sessionState.logs.lastIndex)
    }
  }

  StudioSectionCard(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    borderColor =
      when (sessionState.status) {
        TranslationStatus.RUNNING -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        TranslationStatus.COOLDOWN -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
        TranslationStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
        TranslationStatus.FAILED,
        TranslationStatus.ABORTED -> MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
        TranslationStatus.IDLE -> MaterialTheme.colorScheme.outlineVariant
      },
  ) {
    SectionTitleRow(
      title = "Processing Status & Logs",
      subtitle =
        if (sessionState.status == TranslationStatus.IDLE && hasRoomData) {
          "Room DB synced: ${roomProgress.translatedBlocks}/${roomProgress.totalBlocks} blocks translated"
        } else {
          sessionState.activePhaseMessage
        },
      icon = Icons.Rounded.Terminal,
      trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          StatusPill(
            text = sessionState.status.name,
            containerColor =
              when (sessionState.status) {
                TranslationStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer
                TranslationStatus.COOLDOWN -> MaterialTheme.colorScheme.secondaryContainer
                TranslationStatus.COMPLETED -> MaterialTheme.colorScheme.tertiaryContainer
                TranslationStatus.ABORTED,
                TranslationStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                TranslationStatus.IDLE -> MaterialTheme.colorScheme.surfaceContainer
              },
            contentColor =
              when (sessionState.status) {
                TranslationStatus.RUNNING -> MaterialTheme.colorScheme.onPrimaryContainer
                TranslationStatus.COOLDOWN -> MaterialTheme.colorScheme.onSecondaryContainer
                TranslationStatus.COMPLETED -> MaterialTheme.colorScheme.onTertiaryContainer
                TranslationStatus.ABORTED,
                TranslationStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
                TranslationStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
              },
          )
          if (sessionState.logs.isNotEmpty() || (hasRoomData && !sessionState.isRunningOrCooldown)) {
            IconButton(
              onClick = onClearLogs,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("clear_logs_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.DeleteSweep,
                contentDescription = "Clear processing logs",
                modifier = Modifier.size(20.dp),
              )
            }
          }
        }
      },
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Room & WorkManager Multi-File Progress Dashboard
    Surface(
      modifier = Modifier.fillMaxWidth().testTag("room_progress_card"),
      shape = RoundedCornerShape(14.dp),
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Rounded.Storage,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text =
                if (hasRoomData) {
                  "${roomProgress.translatedBlocks}/${roomProgress.totalBlocks} blocks processed"
                } else if (sessionState.totalFiles > 0) {
                  "Files: ${sessionState.completedFiles}/${sessionState.totalFiles} queued"
                } else {
                  "No active SRT translation job"
                },
              style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.testTag("room_progress_counts"),
            )
          }
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "$percentInt%",
            style =
              MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = JetBrainsMonoFamily,
              ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("room_progress_percent"),
          )
        }

        if (latestWorkInfo != null) {
          Spacer(modifier = Modifier.height(6.dp))
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.WorkHistory,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(14.dp),
            )
            Text(
              text = "WorkManager: ${latestWorkInfo.state.name}",
              style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.testTag("workmanager_state_pill"),
            )
          }
        }

        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
          progress = { animatedProgress },
          color = MaterialTheme.colorScheme.primary,
          trackColor = MaterialTheme.colorScheme.primaryContainer,
          modifier =
            Modifier.fillMaxWidth()
              .height(9.dp)
              .clip(RoundedCornerShape(50))
              .testTag("room_translation_progress_bar"),
        )

        if (hasRoomData) {
          Spacer(modifier = Modifier.height(10.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Surface(
              modifier = Modifier.testTag("room_translated_count_pill"),
              shape = RoundedCornerShape(50),
              color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector = Icons.Rounded.CheckCircle,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onTertiaryContainer,
                  modifier = Modifier.size(13.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = "Translated: ${roomProgress.translatedBlocks}",
                  style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                  color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
              }
            }

            Surface(
              modifier = Modifier.testTag("room_pending_count_pill"),
              shape = RoundedCornerShape(50),
              color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector = Icons.Rounded.Schedule,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSecondaryContainer,
                  modifier = Modifier.size(13.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = "Pending: ${roomProgress.pendingBlocks}",
                  style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
              }
            }
          }
        }

        if (dashboardRows.isNotEmpty()) {
          Spacer(modifier = Modifier.height(10.dp))
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          Spacer(modifier = Modifier.height(8.dp))
          Column(
            modifier = Modifier.fillMaxWidth().testTag("workmanager_file_dashboard"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            dashboardRows.forEachIndexed { idx, row ->
              val animatedFileProgress by
                animateFloatAsState(
                  targetValue = row.progressFraction,
                  label = "dashboard_file_progress_${idx}_${row.fileName}",
                )
              val canPreview = row.status == FileQueueStatus.COMPLETED || row.translatedBlocks > 0
              val matchedFile = queuedFiles.firstOrNull { it.fileName == row.fileName }
              Column(
                modifier =
                  Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .let { base ->
                      if (canPreview) {
                        base.clickable { onPreviewFile(row.fileName, matchedFile) }
                      } else {
                        base
                      }
                    }
                    .padding(vertical = 4.dp)
                    .testTag("dashboard_file_row_$idx"),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  FileStatusIndicator(
                    status = row.status,
                    progressFraction = animatedFileProgress,
                    modifier = Modifier.testTag("dashboard_file_icon_$idx"),
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                    text = row.fileName,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                  )
                  if (canPreview) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                      imageVector = Icons.Rounded.Preview,
                      contentDescription = "Preview side-by-side",
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(15.dp),
                    )
                  }
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                    text = "${row.translatedBlocks}/${row.totalBlocks} (${row.progressPercent}%)",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.primary,
                  )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                  progress = { animatedFileProgress },
                  color =
                    when (row.status) {
                      FileQueueStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
                      FileQueueStatus.FAILED -> MaterialTheme.colorScheme.error
                      else -> MaterialTheme.colorScheme.primary
                    },
                  trackColor = MaterialTheme.colorScheme.primaryContainer,
                  modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)),
                )
              }
            }
          }
        }
      }
    }

    if (sessionState.totalFiles > 0) {
      Spacer(modifier = Modifier.height(10.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        MetricTile(
          label = "File",
          value = "${sessionState.currentFileIndex}/${sessionState.totalFiles}",
          modifier = Modifier.weight(1f),
        )
        MetricTile(
          label = "Batch",
          value =
            "${sessionState.currentBatchNumber}/${sessionState.totalBatchesInCurrentFile.coerceAtLeast(1)}",
          modifier = Modifier.weight(1f),
        )
        MetricTile(
          label = "API Key",
          value = if (sessionState.currentKeyNumber > 0) "#${sessionState.currentKeyNumber}" else "-",
          modifier = Modifier.weight(1f),
        )
        MetricTile(
          label = if (sessionState.status == TranslationStatus.COOLDOWN) "Cooldown" else "Attempt",
          value =
            if (sessionState.status == TranslationStatus.COOLDOWN) {
              val m = sessionState.cooldownRemainingSeconds / 60
              val s = sessionState.cooldownRemainingSeconds % 60
              String.format(Locale.US, "%02d:%02d", m, s)
            } else {
              "${sessionState.currentAttempt}/${sessionState.maxAttempts}"
            },
          modifier = Modifier.weight(1f),
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    val isDarkTheme = LocalThemeController.current.isDarkTheme
    val logContainerColor =
      if (isDarkTheme) MaterialTheme.colorScheme.surfaceContainerHighest
      else MaterialTheme.colorScheme.surfaceContainerLow

    // Persistent Console Log Window
    Surface(
      modifier =
        Modifier.fillMaxWidth()
          .heightIn(min = 120.dp, max = 240.dp)
          .testTag("processing_log_container"),
      shape = RoundedCornerShape(14.dp),
      color = logContainerColor,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
      if (sessionState.logs.isEmpty()) {
        Box(
          modifier = Modifier.fillMaxWidth().padding(20.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = "Processing logs will appear here during translation.",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        LazyColumn(
          state = logListState,
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          items(items = sessionState.logs, key = { it.id }) { log ->
            LogLineRow(log = log)
          }
        }
      }
    }
  }
}

@Composable
private fun LogLineRow(log: ProcessingLogEntry) {
  val accentColor =
    when (log.level) {
      LogLevel.SUCCESS -> MaterialTheme.colorScheme.tertiary
      LogLevel.WARNING -> MaterialTheme.colorScheme.secondary
      LogLevel.ERROR -> MaterialTheme.colorScheme.error
      LogLevel.COOLDOWN -> MaterialTheme.colorScheme.secondary
      LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
    }

  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      text = "[${log.timestampLabel}]",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.width(6.dp))
    Text(
      text = log.message,
      style = MaterialTheme.typography.labelSmall,
      color = accentColor,
      modifier = Modifier.weight(1f),
    )
  }
}
