package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.preferences.SecureKeyStorage
import com.example.data.storage.SubtitleFileStorage
import com.example.domain.model.ApiKeyEntry
import com.example.domain.model.ApiKeyStatus
import com.example.domain.model.ApiProvider
import com.example.domain.model.AppSettings
import com.example.ui.screens.ApiKeyEditorDialog
import com.example.ui.screens.ApiKeyRow
import com.example.ui.theme.BSubShape
import com.example.ui.theme.JetBrainsMonoFamily

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnifiedApiConfigCard(
  settings: AppSettings,
  apiKeys: List<ApiKeyEntry>,
  isTranslationRunning: Boolean,
  onSetActiveProvider: (ApiProvider) -> Unit,
  onSaveBulkKeys: (String, ApiProvider) -> Unit,
  onImportKeysFile: (Uri?, ApiProvider) -> Unit,
  onAddSingleKey: (String, String, ApiProvider) -> Unit,
  onEditKey: (String, String, String) -> Unit,
  onRemoveKey: (String) -> Unit,
  onClearAllKeys: (ApiProvider) -> Unit,
  onResetAllStatuses: (ApiProvider) -> Unit,
  modifier: Modifier = Modifier,
) {
  var selectedKeyTab by rememberSaveable { mutableStateOf(settings.apiProvider) }
  var bulkInput by rememberSaveable { mutableStateOf("") }
  var showAddSingleDialog by remember { mutableStateOf(false) }
  var editingKey by remember { mutableStateOf<ApiKeyEntry?>(null) }
  var showClearAllConfirm by remember { mutableStateOf(false) }

  LaunchedEffect(settings.apiProvider) {
    selectedKeyTab = settings.apiProvider
  }

  val keyFileLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      onImportKeysFile(uri, selectedKeyTab)
    }

  val activeProviderKeys =
    remember(apiKeys, settings.apiProvider) {
      apiKeys.count { it.provider == settings.apiProvider && it.status == ApiKeyStatus.ACTIVE }
    }
  val tabKeys =
    remember(apiKeys, selectedKeyTab) {
      apiKeys.filter { it.provider == selectedKeyTab }
    }
  val tabActiveCount = tabKeys.count { it.status == ApiKeyStatus.ACTIVE }
  val tabExhaustedCount = tabKeys.count { it.status == ApiKeyStatus.QUOTA_EXHAUSTED }
  val tabInvalidCount = tabKeys.count { it.status == ApiKeyStatus.INVALID }
  val detectedBulkCount = remember(bulkInput) { SecureKeyStorage.parseBulkKeys(bulkInput).size }

  val segmentedColors =
    SegmentedButtonDefaults.colors(
      activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
      activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      inactiveContainerColor = MaterialTheme.colorScheme.surface,
      inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
      activeBorderColor = MaterialTheme.colorScheme.outlineVariant,
      inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant,
    )

  StudioSectionCard(modifier = modifier.testTag("unified_api_config_card")) {
    SectionTitleRow(
      title = "API Engine & Keys",
      subtitle = "Toggle primary subtitle engine and manage provider keys",
      icon = Icons.Rounded.Key,
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Active Engine Status Summary
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.tertiaryContainer,
      modifier =
        Modifier.fillMaxWidth()
          .semantics(mergeDescendants = true) {}
          .testTag("settings_active_engine_badge"),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = Icons.Rounded.Bolt,
          contentDescription = "Active Engine",
          tint = MaterialTheme.colorScheme.onTertiaryContainer,
          modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Active Engine: ${settings.apiProvider.displayName}",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "${settings.effectiveModel} • $activeProviderKeys active key(s)",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Text(
      text = "Primary Translation Engine",
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(8.dp))

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
      ApiProvider.entries.forEachIndexed { index, provider ->
        SegmentedButton(
          selected = settings.apiProvider == provider,
          onClick = { onSetActiveProvider(provider) },
          enabled = !isTranslationRunning,
          shape = SegmentedButtonDefaults.itemShape(index = index, count = ApiProvider.entries.size),
          colors = segmentedColors,
          modifier = Modifier.testTag("settings_provider_${provider.name.lowercase()}"),
        ) {
          Text(provider.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }

    Spacer(modifier = Modifier.height(14.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(14.dp))

    // Independent Key Management Header & Provider Tabs
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
        Text(
          text = "Provider Key Pools",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = "Manage Gemini, Grok, and DeepSeek keys independently",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      FilledTonalButton(
        onClick = { showAddSingleDialog = true },
        enabled = !isTranslationRunning,
        colors =
          ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
        modifier = Modifier.minimumInteractiveComponentSize().testTag("settings_add_key_button"),
      ) {
        Icon(
          imageVector = Icons.Rounded.Add,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text("Add Key")
      }
    }

    Spacer(modifier = Modifier.height(10.dp))

    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      ApiProvider.entries.forEach { provider ->
        val count = apiKeys.count { it.provider == provider }
        FilterChip(
          selected = selectedKeyTab == provider,
          onClick = { selectedKeyTab = provider },
          label = { Text("${provider.displayName} ($count)") },
          modifier = Modifier.testTag("settings_key_tab_${provider.name.lowercase()}"),
        )
      }
    }

    Spacer(modifier = Modifier.height(10.dp))

    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      StatusPill(
        text = "Total: ${tabKeys.size}",
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
      )
      StatusPill(
        text = "Active: $tabActiveCount",
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
      )
      if (tabExhaustedCount > 0) {
        StatusPill(
          text = "Exhausted: $tabExhaustedCount",
          containerColor = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
      }
      if (tabInvalidCount > 0) {
        StatusPill(
          text = "Invalid: $tabInvalidCount",
          containerColor = MaterialTheme.colorScheme.errorContainer,
          contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
      }
    }

    if (selectedKeyTab != settings.apiProvider) {
      Spacer(modifier = Modifier.height(10.dp))
      FilledTonalButton(
        onClick = { onSetActiveProvider(selectedKeyTab) },
        enabled = !isTranslationRunning,
        modifier =
          Modifier.fillMaxWidth()
            .minimumInteractiveComponentSize()
            .testTag("settings_set_primary_from_tab_button"),
      ) {
        Text("Set ${selectedKeyTab.displayName} as Primary Engine")
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
      value = bulkInput,
      onValueChange = { bulkInput = it },
      label = { Text("Paste ${selectedKeyTab.displayName} Keys (One per line)") },
      placeholder = { Text(selectedKeyTab.keyPlaceholder) },
      minLines = 2,
      maxLines = 5,
      enabled = !isTranslationRunning,
      textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
      modifier = Modifier.fillMaxWidth().testTag("settings_bulk_keys_input"),
    )

    Spacer(modifier = Modifier.height(8.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      OutlinedButton(
        onClick = { keyFileLauncher.launch(SubtitleFileStorage.TEXT_KEY_FILE_MIME_TYPES) },
        enabled = !isTranslationRunning,
        shape = BSubShape.petalButton,
        modifier =
          Modifier.weight(1f)
            .minimumInteractiveComponentSize()
            .testTag("settings_import_keys_button"),
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
          onSaveBulkKeys(bulkInput, selectedKeyTab)
          bulkInput = ""
        },
        enabled = detectedBulkCount > 0 && !isTranslationRunning,
        shape = BSubShape.petalButton,
        modifier =
          Modifier.weight(1f)
            .minimumInteractiveComponentSize()
            .testTag("settings_save_bulk_keys_button"),
      ) {
        Text(
          text = if (detectedBulkCount > 0) "Save ($detectedBulkCount)" else "Save Keys",
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    if (tabKeys.isNotEmpty() && !isTranslationRunning) {
      Spacer(modifier = Modifier.height(10.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedButton(
          onClick = { onResetAllStatuses(selectedKeyTab) },
          modifier =
            Modifier.weight(1f)
              .minimumInteractiveComponentSize()
              .testTag("settings_reset_keys_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text("Reset Statuses", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        OutlinedButton(
          onClick = { showClearAllConfirm = true },
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
          modifier =
            Modifier.weight(1f)
              .minimumInteractiveComponentSize()
              .testTag("settings_clear_keys_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.DeleteForever,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Clear All",
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    if (tabKeys.isEmpty()) {
      Text(
        text =
          "No ${selectedKeyTab.displayName} API keys configured yet. Tap 'Add Key' or paste keys above.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tabKeys.forEachIndexed { idx, entry ->
          ApiKeyRow(
            position = idx + 1,
            entry = entry,
            enabled = !isTranslationRunning,
            onEdit = { editingKey = entry },
            onDelete = { onRemoveKey(entry.id) },
          )
        }
      }
    }
  }

  if (showAddSingleDialog) {
    ApiKeyEditorDialog(
      title = "Add ${selectedKeyTab.displayName} API Key",
      keyFieldLabel = "${selectedKeyTab.displayName} API Key",
      keyPlaceholder = selectedKeyTab.keyPlaceholder,
      initialLabel = "Key #${tabKeys.size + 1}",
      initialKey = "",
      onDismiss = { showAddSingleDialog = false },
      onConfirm = { key, label ->
        onAddSingleKey(key, label, selectedKeyTab)
        showAddSingleDialog = false
      },
    )
  }

  editingKey?.let { entry ->
    ApiKeyEditorDialog(
      title = "Edit ${entry.provider.displayName} API Key",
      keyFieldLabel = "${entry.provider.displayName} API Key",
      keyPlaceholder = entry.provider.keyPlaceholder,
      initialLabel = entry.label,
      initialKey = entry.key,
      onDismiss = { editingKey = null },
      onConfirm = { newKey, newLabel ->
        onEditKey(entry.id, newKey, newLabel)
        editingKey = null
      },
    )
  }

  if (showClearAllConfirm) {
    AlertDialog(
      onDismissRequest = { showClearAllConfirm = false },
      title = { Text("Remove ${selectedKeyTab.displayName} Keys?") },
      text = {
        Text(
          "This will permanently delete all ${tabKeys.size} saved ${selectedKeyTab.displayName} API keys from encrypted storage."
        )
      },
      confirmButton = {
        Button(
          onClick = {
            onClearAllKeys(selectedKeyTab)
            showClearAllConfirm = false
          }
        ) {
          Text("Remove All")
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearAllConfirm = false }) {
          Text("Cancel")
        }
      },
    )
  }
}
