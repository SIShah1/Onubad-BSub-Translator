package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Preview
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.HistoryTranslationStatus
import com.example.data.local.TranslationHistoryEntity
import com.example.data.storage.SubtitleFileStorage
import com.example.srt.SrtEngine
import com.example.ui.components.SectionTitleRow
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioSectionCard
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.viewmodel.SubtitlePreviewState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class HistoryFilterOption(val label: String) {
  ALL("All"),
  COMPLETED("Completed"),
  PARTIAL("Partial"),
  FAILED("Failed"),
}

@Composable
fun HistoryScreen(
  historyItems: List<TranslationHistoryEntity>,
  previewState: SubtitlePreviewState? = null,
  onOpenPreview: (TranslationHistoryEntity) -> Unit,
  onClosePreview: () -> Unit = {},
  onReSaveToDownloads: (TranslationHistoryEntity) -> Unit,
  onExportToUri: (TranslationHistoryEntity, android.net.Uri?) -> Unit,
  onCreateShareIntent: (TranslationHistoryEntity) -> Intent?,
  onDeleteItem: (TranslationHistoryEntity) -> Unit,
  onClearAllHistory: () -> Unit = {},
  searchQuery: String? = null,
  onSearchQueryChange: ((String) -> Unit)? = null,
  matchedContentSnippets: Map<Long, String>? = null,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val standaloneStorage = remember(context) { SubtitleFileStorage(context) }
  var pendingExportItem by remember { mutableStateOf<TranslationHistoryEntity?>(null) }
  var pendingDeleteItem by remember { mutableStateOf<TranslationHistoryEntity?>(null) }
  var showClearAllDialog by remember { mutableStateOf(false) }
  var selectedFilter by remember { mutableStateOf(HistoryFilterOption.ALL) }
  var internalSearchQuery by rememberSaveable { mutableStateOf("") }
  var internalSnippets by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

  val activeQuery = searchQuery ?: internalSearchQuery
  val updateQuery: (String) -> Unit = { next ->
    if (onSearchQueryChange != null) {
      onSearchQueryChange(next)
    } else {
      internalSearchQuery = next
    }
  }

  LaunchedEffect(historyItems, activeQuery, matchedContentSnippets) {
    if (matchedContentSnippets != null) return@LaunchedEffect
    val cleanQuery = activeQuery.trim()
    if (cleanQuery.isEmpty() || historyItems.isEmpty()) {
      internalSnippets = emptyMap()
      return@LaunchedEffect
    }
    val resolved = LinkedHashMap<Long, String>()
    for (item in historyItems) {
      val snippet =
        standaloneStorage.findContentMatchSnippet(
          localBackupPath = item.localBackupPath,
          contentUri = item.contentUri,
          originalFileName = item.originalFileName,
          query = cleanQuery,
        )
      if (snippet != null) {
        resolved[item.id] = snippet
      }
    }
    internalSnippets = resolved
  }

  val activeSnippets = matchedContentSnippets ?: internalSnippets

  val filteredItems =
    remember(historyItems, selectedFilter, activeQuery, activeSnippets) {
      val statusFiltered =
        when (selectedFilter) {
          HistoryFilterOption.ALL -> historyItems
          HistoryFilterOption.COMPLETED ->
            historyItems.filter { it.translationStatus == HistoryTranslationStatus.COMPLETED }
          HistoryFilterOption.PARTIAL ->
            historyItems.filter { it.translationStatus == HistoryTranslationStatus.PARTIAL }
          HistoryFilterOption.FAILED ->
            historyItems.filter {
              it.translationStatus == HistoryTranslationStatus.FAILED ||
                it.translationStatus == HistoryTranslationStatus.ABORTED
            }
        }
      val cleanQuery = activeQuery.trim()
      if (cleanQuery.isEmpty()) {
        statusFiltered
      } else {
        statusFiltered.filter { item ->
          item.originalFileName.contains(cleanQuery, ignoreCase = true) ||
            item.outputFileName.contains(cleanQuery, ignoreCase = true) ||
            item.titleMetadata.contains(cleanQuery, ignoreCase = true) ||
            item.savedPath.contains(cleanQuery, ignoreCase = true) ||
            activeSnippets.containsKey(item.id)
        }
      }
    }

  val exportDocumentLauncher =
    rememberLauncherForActivityResult(
      ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri ->
      val item = pendingExportItem
      pendingExportItem = null
      if (item != null) {
        onExportToUri(item, uri)
      }
    }

  Column(
    modifier = modifier.fillMaxSize().padding(16.dp),
  ) {
    StudioSectionCard {
      SectionTitleRow(
        title = "Translated Files History",
        subtitle = "Local SQLite archive • Downloads/BSub Translator/",
        icon = Icons.Rounded.History,
        trailing = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            StatusPill(
              text =
                if (filteredItems.size != historyItems.size) {
                  "${filteredItems.size}/${historyItems.size} file(s)"
                } else {
                  "${historyItems.size} file(s)"
                },
              containerColor = MaterialTheme.colorScheme.surfaceContainer,
              contentColor = MaterialTheme.colorScheme.onSurface,
            )
            if (historyItems.isNotEmpty()) {
              IconButton(
                onClick = { showClearAllDialog = true },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("history_clear_all"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.DeleteSweep,
                  contentDescription = "Clear all translation history",
                  tint = MaterialTheme.colorScheme.error,
                )
              }
            }
          }
        },
      )

      if (historyItems.isNotEmpty()) {
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
          value = activeQuery,
          onValueChange = updateQuery,
          placeholder = { Text("Search by filename or subtitle content...") },
          leadingIcon = {
            Icon(
              imageVector = Icons.Rounded.Search,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
          },
          trailingIcon = {
            if (activeQuery.isNotEmpty()) {
              IconButton(
                onClick = { updateQuery("") },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("history_search_clear"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Close,
                  contentDescription = "Clear search query",
                  modifier = Modifier.size(18.dp),
                )
              }
            }
          },
          singleLine = true,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth().testTag("history_search_input"),
        )

        Spacer(modifier = Modifier.height(10.dp))
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          HistoryFilterOption.entries.forEach { option ->
            FilterChip(
              selected = selectedFilter == option,
              onClick = { selectedFilter = option },
              label = { Text(option.label) },
              modifier = Modifier.testTag("history_filter_${option.name.lowercase()}"),
            )
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(14.dp))

    if (historyItems.isEmpty()) {
      EmptyHistoryState(modifier = Modifier.fillMaxSize())
    } else if (filteredItems.isEmpty()) {
      Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        Text(
          text =
            if (activeQuery.isNotBlank()) {
              "No Matching Translated Files"
            } else {
              "No ${selectedFilter.label} Records"
            },
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        TextButton(
          onClick = {
            updateQuery("")
            selectedFilter = HistoryFilterOption.ALL
          },
          modifier = Modifier.testTag("history_reset_filters_button"),
        ) {
          Text(if (activeQuery.isNotBlank()) "Clear search & filters" else "Show all history")
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("history_items_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(items = filteredItems, key = { it.id }) { item ->
          HistoryItemCard(
            item = item,
            matchedContentSnippet = activeSnippets[item.id],
            onPreview = { onOpenPreview(item) },
            onReDownload = { onReSaveToDownloads(item) },
            onExportAs = {
              pendingExportItem = item
              exportDocumentLauncher.launch(
                item.outputFileName.ifBlank { item.originalFileName }
              )
            },
            onShare = {
              val intent = onCreateShareIntent(item)
              if (intent != null) {
                context.startActivity(Intent.createChooser(intent, "Share Translated Subtitle"))
              }
            },
            onDelete = { pendingDeleteItem = item },
          )
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
      }
    }
  }

  pendingDeleteItem?.let { item ->
    AlertDialog(
      onDismissRequest = { pendingDeleteItem = null },
      title = { Text("Delete History Entry?") },
      text = {
        Text(
          "Remove ${item.outputFileName.ifBlank { item.originalFileName }} from translation history?"
        )
      },
      confirmButton = {
        Button(
          onClick = {
            onDeleteItem(item)
            pendingDeleteItem = null
          }
        ) {
          Text("Delete")
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingDeleteItem = null }) {
          Text("Cancel")
        }
      },
    )
  }

  if (showClearAllDialog) {
    AlertDialog(
      onDismissRequest = { showClearAllDialog = false },
      title = { Text("Clear All History?") },
      text = { Text("Remove all ${historyItems.size} processed subtitle records from local history?") },
      confirmButton = {
        Button(
          onClick = {
            onClearAllHistory()
            showClearAllDialog = false
          }
        ) {
          Text("Clear All")
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearAllDialog = false }) {
          Text("Cancel")
        }
      },
    )
  }
}

@Composable
private fun EmptyHistoryState(modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Image(
      painter = painterResource(id = R.drawable.img_history_empty_state),
      contentDescription = "No translated subtitles yet",
      contentScale = ContentScale.Fit,
      modifier =
        Modifier.size(width = 240.dp, height = 180.dp)
          .clip(MaterialTheme.shapes.large)
          .testTag("history_empty_image"),
    )
    Spacer(modifier = Modifier.height(18.dp))
    Text(
      text = "No Translated Files Yet",
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text =
        "Processed movie and series subtitle files are logged locally with timestamps and translation status for preview, export, and sharing.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun HistoryItemCard(
  item: TranslationHistoryEntity,
  matchedContentSnippet: String?,
  onPreview: () -> Unit,
  onReDownload: () -> Unit,
  onExportAs: () -> Unit,
  onShare: () -> Unit,
  onDelete: () -> Unit,
) {
  val dateText =
    remember(item.updatedAtEpochMs, item.createdAtEpochMs) {
      val epoch = if (item.updatedAtEpochMs > 0L) item.updatedAtEpochMs else item.createdAtEpochMs
      SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.US).format(Date(epoch))
    }
  val hasOutputFile = item.localBackupPath.isNotBlank() || item.contentUri.isNotBlank()
  val displayTitle = item.outputFileName.ifBlank { item.originalFileName }

  val (pillContainer, pillContent) =
    when (item.translationStatus) {
      HistoryTranslationStatus.COMPLETED ->
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
      HistoryTranslationStatus.PARTIAL ->
        MaterialTheme.colorScheme.secondaryContainer to
          MaterialTheme.colorScheme.onSecondaryContainer
      HistoryTranslationStatus.PROCESSING ->
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
      HistoryTranslationStatus.FAILED,
      HistoryTranslationStatus.ABORTED ->
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }

  StudioSectionCard(
    modifier =
      Modifier.testTag("history_item_card_${item.id}").let { base ->
        if (hasOutputFile) base.clickable(onClick = onPreview) else base
      },
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.Top,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = displayTitle,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "${item.titleMetadata} • ${item.mode}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Spacer(modifier = Modifier.width(8.dp))
      StatusPill(
        text = item.status,
        containerColor = pillContainer,
        contentColor = pillContent,
        modifier = Modifier.testTag("history_status_${item.id}"),
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

    Text(
      text = "Source: ${item.originalFileName}",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text =
        "${item.translatedBlocks}/${item.totalBlocks} blocks • ${item.totalLines} lines • ${SrtEngine.formatFileSize(item.fileSizeBytes)}",
      style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (item.savedPath.isNotBlank()) {
      Text(
        text = "Path: ${item.savedPath}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (!item.errorMessage.isNullOrBlank()) {
      Text(
        text = item.errorMessage,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (!matchedContentSnippet.isNullOrBlank()) {
      Spacer(modifier = Modifier.height(6.dp))
      Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth().testTag("history_content_match_${item.id}"),
      ) {
        Text(
          text = "Match: \"$matchedContentSnippet\"",
          style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
          color = MaterialTheme.colorScheme.onPrimaryContainer,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        )
      }
    }
    Text(
      text = "${item.modelUsed} • $dateText",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(10.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(10.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (hasOutputFile) {
        FilledTonalButton(
          onClick = onPreview,
          colors =
            ButtonDefaults.filledTonalButtonColors(
              containerColor = MaterialTheme.colorScheme.primaryContainer,
              contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
          modifier = Modifier.weight(1f).testTag("history_preview_${item.id}"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Preview,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text("View", maxLines = 1)
        }

        OutlinedButton(
          onClick = onReDownload,
          modifier = Modifier.weight(1f).testTag("history_redownload_${item.id}"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Download,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text("Save", maxLines = 1)
        }

        IconButton(
          onClick = onExportAs,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("history_export_${item.id}"),
        ) {
          Icon(
            imageVector = Icons.Rounded.FileDownload,
            contentDescription = "Export file to custom folder",
          )
        }

        IconButton(
          onClick = onShare,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("history_share_${item.id}"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Share,
            contentDescription = "Share translated subtitle",
          )
        }
      } else {
        Text(
          text =
            if (item.translationStatus == HistoryTranslationStatus.PROCESSING) {
              "Translation in progress..."
            } else {
              "No output file saved"
            },
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
      }

      IconButton(
        onClick = onDelete,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("history_delete_${item.id}"),
      ) {
        Icon(
          imageVector = Icons.Rounded.Delete,
          contentDescription = "Delete history entry",
          tint = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}
