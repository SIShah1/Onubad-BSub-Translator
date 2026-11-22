package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.preferences.SecureKeyStorage
import com.example.domain.model.ApiKeyEntry
import com.example.domain.model.ApiKeyStatus
import com.example.ui.components.SectionTitleRow
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioSectionCard
import com.example.ui.theme.BSubShape
import com.example.ui.theme.JetBrainsMonoFamily

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApiKeysScreen(
  apiKeys: List<ApiKeyEntry>,
  isTranslationRunning: Boolean,
  onSaveBulkKeys: (String) -> Unit,
  onAddSingleKey: (String, String) -> Unit,
  onEditKey: (String, String, String) -> Unit,
  onRemoveKey: (String) -> Unit,
  onClearAllKeys: () -> Unit,
  onResetAllStatuses: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var bulkInput by rememberSaveable { mutableStateOf("") }
  var editingKey by remember { mutableStateOf<ApiKeyEntry?>(null) }
  var showAddSingleDialog by remember { mutableStateOf(false) }
  var showClearAllConfirm by remember { mutableStateOf(false) }

  val detectedBulkCount = remember(bulkInput) { SecureKeyStorage.parseBulkKeys(bulkInput).size }
  val activeCount = apiKeys.count { it.status == ApiKeyStatus.ACTIVE }
  val exhaustedCount = apiKeys.count { it.status == ApiKeyStatus.QUOTA_EXHAUSTED }
  val invalidCount = apiKeys.count { it.status == ApiKeyStatus.INVALID }

  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // 1. Key Pool Overview
    StudioSectionCard {
      SectionTitleRow(
        title = "API Key Manager",
        subtitle = "Encrypted with Android Keystore AES-256",
        icon = Icons.Rounded.Lock,
        trailing = {
          FilledTonalButton(
            onClick = { showAddSingleDialog = true },
            enabled = !isTranslationRunning,
            colors =
              ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
              ),
            modifier = Modifier.testTag("add_single_key_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Add,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add One")
          }
        },
      )

      Spacer(modifier = Modifier.height(12.dp))

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        StatusPill(
          text = "Total: ${apiKeys.size}",
          containerColor = MaterialTheme.colorScheme.surfaceContainer,
          contentColor = MaterialTheme.colorScheme.onSurface,
        )
        StatusPill(
          text = "Active: $activeCount",
          containerColor = MaterialTheme.colorScheme.tertiaryContainer,
          contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        if (exhaustedCount > 0) {
          StatusPill(
            text = "Exhausted: $exhaustedCount",
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          )
        }
        if (invalidCount > 0) {
          StatusPill(
            text = "Invalid: $invalidCount",
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
          )
        }
      }

      if (apiKeys.isNotEmpty() && !isTranslationRunning) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OutlinedButton(
            onClick = onResetAllStatuses,
            modifier = Modifier.weight(1f).testTag("reset_key_statuses_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Refresh,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Reset Statuses")
          }

          OutlinedButton(
            onClick = { showClearAllConfirm = true },
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
            modifier = Modifier.weight(1f).testTag("clear_all_keys_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.DeleteForever,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Clear All", color = MaterialTheme.colorScheme.error)
          }
        }
      }
    }

    // 2. Bulk Paste Card (Supports 25+ keys simultaneously)
    StudioSectionCard {
      SectionTitleRow(
        title = "Bulk Paste Gemini Keys",
        subtitle = "Paste 25+ keys separated by newlines, commas, or spaces",
        icon = Icons.Rounded.VpnKey,
      )

      Spacer(modifier = Modifier.height(12.dp))

      OutlinedTextField(
        value = bulkInput,
        onValueChange = { bulkInput = it },
        label = { Text("Paste API Keys Here") },
        placeholder = { Text("AIzaSy...\nAIzaSy...\nAIzaSy...") },
        minLines = 5,
        maxLines = 10,
        enabled = !isTranslationRunning,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
        modifier = Modifier.fillMaxWidth().testTag("bulk_keys_input"),
      )

      Spacer(modifier = Modifier.height(10.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text =
            if (detectedBulkCount > 0) "$detectedBulkCount key(s) detected"
            else "Supports unlimited simultaneous keys",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (bulkInput.isNotBlank()) {
            TextButton(onClick = { bulkInput = "" }) {
              Text("Clear")
            }
          }
          Button(
            onClick = {
              onSaveBulkKeys(bulkInput)
              bulkInput = ""
            },
            enabled = detectedBulkCount > 0 && !isTranslationRunning,
            shape = BSubShape.petalButton,
            modifier = Modifier.testTag("save_bulk_keys_button"),
          ) {
            Text("Save Keys")
          }
        }
      }
    }

    // 3. Saved Keys List
    StudioSectionCard {
      SectionTitleRow(
        title = "Saved Key Rotation Pool (${apiKeys.size})",
        subtitle = "Keys rotate sequentially on failures or quota limits",
        icon = Icons.Rounded.Key,
      )

      Spacer(modifier = Modifier.height(12.dp))

      if (apiKeys.isEmpty()) {
        Text(
          text =
            "No Gemini API keys saved yet. Paste your keys in the bulk box above or tap 'Add One'.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 8.dp),
        )
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          apiKeys.forEachIndexed { idx, entry ->
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

    Spacer(modifier = Modifier.height(12.dp))
  }

  if (showAddSingleDialog) {
    ApiKeyEditorDialog(
      title = "Add Gemini API Key",
      initialLabel = "Key #${apiKeys.size + 1}",
      initialKey = "",
      onDismiss = { showAddSingleDialog = false },
      onConfirm = { key, label ->
        onAddSingleKey(key, label)
        showAddSingleDialog = false
      },
    )
  }

  editingKey?.let { entry ->
    ApiKeyEditorDialog(
      title = "Edit API Key",
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
      title = { Text("Remove All API Keys?") },
      text = {
        Text(
          "This will permanently delete all ${apiKeys.size} saved Gemini API keys from encrypted storage."
        )
      },
      confirmButton = {
        Button(
          onClick = {
            onClearAllKeys()
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

@Composable
private fun ApiKeyRow(
  position: Int,
  entry: ApiKeyEntry,
  enabled: Boolean,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  var revealed by remember { mutableStateOf(false) }

  val (statusBg, statusFg, statusLabel) =
    when (entry.status) {
      ApiKeyStatus.ACTIVE ->
        Triple(
          MaterialTheme.colorScheme.tertiaryContainer,
          MaterialTheme.colorScheme.onTertiaryContainer,
          "ACTIVE",
        )
      ApiKeyStatus.QUOTA_EXHAUSTED ->
        Triple(
          MaterialTheme.colorScheme.secondaryContainer,
          MaterialTheme.colorScheme.onSecondaryContainer,
          "QUOTA",
        )
      ApiKeyStatus.INVALID ->
        Triple(
          MaterialTheme.colorScheme.errorContainer,
          MaterialTheme.colorScheme.onErrorContainer,
          "INVALID",
        )
      ApiKeyStatus.COOLDOWN ->
        Triple(
          MaterialTheme.colorScheme.errorContainer,
          MaterialTheme.colorScheme.onErrorContainer,
          "COOLDOWN",
        )
    }

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = "#$position • ${entry.label}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          StatusPill(text = statusLabel, containerColor = statusBg, contentColor = statusFg)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = if (revealed) entry.key else SecureKeyStorage.maskKey(entry.key),
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (!entry.lastError.isNullOrBlank()) {
          Text(
            text = entry.lastError,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      IconButton(
        onClick = { revealed = !revealed },
        modifier = Modifier.minimumInteractiveComponentSize(),
      ) {
        Icon(
          imageVector = if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
          contentDescription = if (revealed) "Mask API key" else "Reveal API key",
          modifier = Modifier.size(19.dp),
        )
      }

      IconButton(
        onClick = onEdit,
        enabled = enabled,
        modifier = Modifier.minimumInteractiveComponentSize(),
      ) {
        Icon(
          imageVector = Icons.Rounded.Edit,
          contentDescription = "Edit API key",
          modifier = Modifier.size(19.dp),
        )
      }

      IconButton(
        onClick = onDelete,
        enabled = enabled,
        modifier = Modifier.minimumInteractiveComponentSize(),
      ) {
        Icon(
          imageVector = Icons.Rounded.Delete,
          contentDescription = "Delete API key",
          tint = MaterialTheme.colorScheme.error,
          modifier = Modifier.size(19.dp),
        )
      }
    }
  }
}

@Composable
private fun ApiKeyEditorDialog(
  title: String,
  initialLabel: String,
  initialKey: String,
  onDismiss: () -> Unit,
  onConfirm: (key: String, label: String) -> Unit,
) {
  var label by rememberSaveable { mutableStateOf(initialLabel) }
  var key by rememberSaveable { mutableStateOf(initialKey) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = label,
          onValueChange = { label = it },
          label = { Text("Label (Optional)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = key,
          onValueChange = { key = it },
          label = { Text("Gemini API Key") },
          placeholder = { Text("AIzaSy...") },
          singleLine = true,
          textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(key, label) },
        enabled = key.trim().length >= 16,
      ) {
        Text("Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}
