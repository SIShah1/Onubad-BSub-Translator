package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.domain.model.DefaultConfig
import com.example.ui.theme.BSubShape

@Composable
fun FirstLaunchPromptDialog(
  initialPrompt: String,
  onSaveAndContinue: (String) -> Unit,
) {
  var promptDraft by rememberSaveable(initialPrompt) { mutableStateOf(initialPrompt) }

  Dialog(
    onDismissRequest = {},
    properties =
      DialogProperties(
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
        usePlatformDefaultWidth = false,
      ),
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 8.dp,
    ) {
      Column(
        modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
          ) {
            Icon(
              imageVector = Icons.Rounded.AutoAwesome,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(10.dp).size(24.dp),
            )
          }
          Spacer(modifier = Modifier.width(12.dp))
          Column {
            Text(
              text = "Configure Default Prompt",
              style = MaterialTheme.typography.headlineSmall,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = "First-launch translation setup",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text =
            "Review or customize your default English-to-Bengali translation prompt. You can edit it anytime later in Advanced Settings.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(14.dp))
        OutlinedTextField(
          value = promptDraft,
          onValueChange = { promptDraft = it },
          label = { Text("Default Translation Prompt") },
          minLines = 8,
          maxLines = 14,
          textStyle = MaterialTheme.typography.bodySmall,
          modifier = Modifier.fillMaxWidth().testTag("first_launch_prompt_input"),
        )

        Spacer(modifier = Modifier.height(16.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedButton(
            onClick = { promptDraft = DefaultConfig.BUILT_IN_PROMPT },
            modifier = Modifier.testTag("first_launch_restore_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Restore,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Built-in Default")
          }

          Button(
            onClick = { onSaveAndContinue(promptDraft) },
            shape = BSubShape.petalButton,
            modifier = Modifier.testTag("first_launch_save_button"),
          ) {
            Text("Save & Continue")
          }
        }
      }
    }
  }
}
