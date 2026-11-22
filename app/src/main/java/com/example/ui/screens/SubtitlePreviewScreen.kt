package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.TranslationHistoryEntity
import com.example.ui.components.StatusPill
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.viewmodel.SubtitleCuePair
import com.example.ui.viewmodel.SubtitlePreviewState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitlePreviewScreen(
  state: SubtitlePreviewState,
  onClose: () -> Unit,
  onShare: ((TranslationHistoryEntity) -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  BackHandler(onBack = onClose)

  var searchQuery by rememberSaveable(state.fileName) { mutableStateOf("") }
  val filteredCues =
    remember(state.cues, searchQuery) {
      val q = searchQuery.trim()
      if (q.isEmpty()) {
        state.cues
      } else {
        state.cues.filter { cue ->
          cue.englishText.contains(q, ignoreCase = true) ||
            cue.bengaliText.contains(q, ignoreCase = true) ||
            cue.timestamp.contains(q, ignoreCase = true) ||
            cue.index.toString() == q
        }
      }
    }

  Scaffold(
    modifier = modifier.fillMaxSize().testTag("subtitle_preview_screen"),
    containerColor = MaterialTheme.colorScheme.background,
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      TopAppBar(
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
          ),
        navigationIcon = {
          IconButton(
            onClick = onClose,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("preview_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = "Back from subtitle preview",
            )
          }
        },
        title = {
          Column(modifier = Modifier.fillMaxWidth()) {
            Text(
              text = state.fileName,
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.testTag("preview_title_text"),
            )
            Text(
              text =
                if (state.totalCount > 0) {
                  "${state.translatedCount}/${state.totalCount} cues • ${state.subtitleMeta}"
                } else {
                  state.subtitleMeta
                },
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        },
        actions = {
          val historyItem = state.historyItem
          val hasOutput =
            historyItem != null &&
              (historyItem.localBackupPath.isNotBlank() || historyItem.contentUri.isNotBlank())
          if (historyItem != null && hasOutput && onShare != null) {
            IconButton(
              onClick = { onShare(historyItem) },
              modifier =
                Modifier.minimumInteractiveComponentSize().testTag("preview_share_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Share,
                contentDescription = "Share translated subtitle",
              )
            }
          }
        },
      )
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 960.dp).fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
      ) {
        if (state.isLoading) {
          Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              CircularProgressIndicator(modifier = Modifier.testTag("preview_loading_indicator"))
              Spacer(modifier = Modifier.height(12.dp))
              Text(
                text = "Loading paired subtitles...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else if (state.cues.isEmpty()) {
          Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(
                imageVector = Icons.Rounded.Subtitles,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp),
              )
              Spacer(modifier = Modifier.height(10.dp))
              Text(
                text = state.emptyMessage ?: "Subtitle preview is unavailable.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        } else {
          OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search English, Bengali, or cue #") },
            leadingIcon = {
              Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
            },
            trailingIcon = {
              if (searchQuery.isNotEmpty()) {
                IconButton(
                  onClick = { searchQuery = "" },
                  modifier = Modifier.minimumInteractiveComponentSize(),
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
            modifier = Modifier.fillMaxWidth().testTag("preview_search_input"),
          )

          if (!state.hasOriginalText) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              modifier = Modifier.fillMaxWidth().testTag("preview_missing_original_banner"),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector = Icons.Rounded.Info,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSecondaryContainer,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text =
                    "Original English source was not archived for this older entry; displaying translated Bengali subtitles.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          // Pinned Side-by-Side Column Header Strip
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().testTag("preview_columns_header"),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              SideBySideColumnHeader(
                title = "English (Original)",
                badgeText = "ENG",
                badgeContainer = MaterialTheme.colorScheme.secondaryContainer,
                badgeContent = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f).testTag("preview_header_english"),
              )
              VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
              SideBySideColumnHeader(
                title = "Bengali (Translated)",
                badgeText = "বাংলা",
                badgeContainer = MaterialTheme.colorScheme.primaryContainer,
                badgeContent = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f).testTag("preview_header_bengali"),
              )
            }
          }

          Spacer(modifier = Modifier.height(8.dp))

          if (filteredCues.isEmpty()) {
            Box(
              modifier = Modifier.fillMaxSize().padding(24.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = "No subtitle cues match \"$searchQuery\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            LazyColumn(
              modifier = Modifier.fillMaxSize().testTag("preview_cues_list"),
              contentPadding = PaddingValues(bottom = 18.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              items(items = filteredCues, key = { it.index }) { cue ->
                SideBySideCueCard(
                  cue = cue,
                  hasOriginalArchive = state.hasOriginalText,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SideBySideColumnHeader(
  title: String,
  badgeText: String,
  badgeContainer: Color,
  badgeContent: Color,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.padding(horizontal = 10.dp, vertical = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Spacer(modifier = Modifier.width(6.dp))
    StatusPill(
      text = badgeText,
      containerColor = badgeContainer,
      contentColor = badgeContent,
    )
  }
}

@Composable
private fun SideBySideCueCard(
  cue: SubtitleCuePair,
  hasOriginalArchive: Boolean,
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth().testTag("preview_cue_row_${cue.index}"),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      // Cue Index + Timecode Header
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = MaterialTheme.colorScheme.primaryContainer,
        ) {
          Text(
            text = "#${cue.index}",
            style =
              MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
              ),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = cue.timestamp,
          style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
          imageVector =
            if (cue.isTranslated) Icons.Rounded.CheckCircle else Icons.Rounded.Schedule,
          contentDescription = if (cue.isTranslated) "Translated cue" else "Pending cue",
          tint =
            if (cue.isTranslated) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(15.dp),
        )
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      // Paired English (Left) | Bengali (Right) Columns
      Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
      ) {
        Box(
          modifier =
            Modifier.weight(1f)
              .fillMaxHeight()
              .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
          val englishMissing = cue.englishText.isBlank()
          Text(
            text =
              when {
                !englishMissing -> cue.englishText
                hasOriginalArchive -> "—"
                else -> "Original not archived"
              },
            style =
              MaterialTheme.typography.bodyMedium.copy(
                fontStyle = if (englishMissing) FontStyle.Italic else FontStyle.Normal,
              ),
            color =
              if (englishMissing) MaterialTheme.colorScheme.onSurfaceVariant
              else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().testTag("preview_english_text_${cue.index}"),
          )
        }

        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(
          modifier =
            Modifier.weight(1f)
              .fillMaxHeight()
              .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
          val bengaliMissing = cue.bengaliText.isBlank()
          Text(
            text = if (bengaliMissing) "Pending translation..." else cue.bengaliText,
            style =
              MaterialTheme.typography.bodyMedium.copy(
                fontStyle = if (bengaliMissing) FontStyle.Italic else FontStyle.Normal,
              ),
            color =
              if (bengaliMissing) MaterialTheme.colorScheme.onSurfaceVariant
              else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().testTag("preview_bengali_text_${cue.index}"),
          )
        }
      }
    }
  }
}
