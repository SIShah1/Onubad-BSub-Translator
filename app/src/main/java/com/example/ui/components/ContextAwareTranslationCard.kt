package com.example.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.domain.model.AppSettings
import com.example.domain.model.DefaultConfig
import com.example.domain.model.ParamMode

private const val CONTEXT_STEP = 10

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContextAwareTranslationCard(
  settings: AppSettings,
  onSetContextLines: (ParamMode, Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  var contextInput by
    rememberSaveable(settings.manualContextLines) {
      mutableStateOf(settings.manualContextLines.toString())
    }

  val activeLines = settings.effectiveContextLines

  StudioSectionCard(modifier = modifier.testTag("context_aware_settings_card")) {
    SectionTitleRow(
      title = "Context-Aware Translation",
      subtitle = "Send surrounding lines per batch for dialogue coherence & character voice",
      icon = Icons.Rounded.Forum,
    )

    Spacer(modifier = Modifier.height(12.dp))

    ParamModeHeader(
      title = "Context Lines",
      defaultSummary = "${DefaultConfig.CONTEXT_LINES} lines",
      mode = settings.contextLinesMode,
      onModeSelected = { mode ->
        val manualVal =
          if (mode == ParamMode.MANUAL) {
            contextInput.toIntOrNull() ?: settings.manualContextLines
          } else {
            settings.manualContextLines
          }
        onSetContextLines(mode, manualVal)
      },
    )

    Surface(
      shape = RoundedCornerShape(14.dp),
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilledTonalIconButton(
          onClick = {
            val next =
              (activeLines - CONTEXT_STEP).coerceIn(
                DefaultConfig.MIN_CONTEXT_LINES,
                DefaultConfig.MAX_CONTEXT_LINES,
              )
            contextInput = next.toString()
            onSetContextLines(ParamMode.MANUAL, next)
          },
          enabled = activeLines > DefaultConfig.MIN_CONTEXT_LINES,
          colors =
            IconButtonDefaults.filledTonalIconButtonColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer,
              contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
          modifier =
            Modifier.minimumInteractiveComponentSize().testTag("context_lines_decrease_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Remove,
            contentDescription = "Decrease context lines",
            modifier = Modifier.size(20.dp),
          )
        }

        Column(
          modifier = Modifier.weight(1f),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Text(
            text = if (activeLines == 0) "0 lines (Off)" else "$activeLines lines",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("context_lines_value_text"),
          )
          Text(
            text = "Higher = better coherence (Default: ${DefaultConfig.CONTEXT_LINES})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }

        FilledTonalIconButton(
          onClick = {
            val next =
              (activeLines + CONTEXT_STEP).coerceIn(
                DefaultConfig.MIN_CONTEXT_LINES,
                DefaultConfig.MAX_CONTEXT_LINES,
              )
            contextInput = next.toString()
            onSetContextLines(ParamMode.MANUAL, next)
          },
          enabled = activeLines < DefaultConfig.MAX_CONTEXT_LINES,
          colors =
            IconButtonDefaults.filledTonalIconButtonColors(
              containerColor = MaterialTheme.colorScheme.primaryContainer,
              contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
          modifier =
            Modifier.minimumInteractiveComponentSize().testTag("context_lines_increase_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = "Increase context lines",
            modifier = Modifier.size(20.dp),
          )
        }
      }
    }

    if (settings.contextLinesMode == ParamMode.MANUAL) {
      Spacer(modifier = Modifier.height(8.dp))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        DefaultConfig.PRESET_CONTEXT_LINES.forEach { preset ->
          FilterChip(
            selected = settings.manualContextLines == preset,
            onClick = {
              contextInput = preset.toString()
              onSetContextLines(ParamMode.MANUAL, preset)
            },
            label = { Text(if (preset == 0) "0 (Off)" else "$preset lines") },
            modifier = Modifier.testTag("context_lines_preset_$preset"),
          )
        }
      }
      Spacer(modifier = Modifier.height(6.dp))
      OutlinedTextField(
        value = contextInput,
        onValueChange = { raw ->
          contextInput = raw
          raw.toIntOrNull()?.let { parsed ->
            onSetContextLines(ParamMode.MANUAL, parsed)
          }
        },
        label = {
          Text(
            "Custom Context Lines (${DefaultConfig.MIN_CONTEXT_LINES}–${DefaultConfig.MAX_CONTEXT_LINES})"
          )
        },
        supportingText = {
          Text("Lines included per batch as context (default ${DefaultConfig.CONTEXT_LINES}).")
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("manual_context_lines_input"),
      )
    }
  }
}
