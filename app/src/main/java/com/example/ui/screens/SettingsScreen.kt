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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.AppSettings
import com.example.domain.model.DefaultConfig
import com.example.domain.model.FewShotExample
import com.example.domain.model.NamingMode
import com.example.domain.model.ParamMode
import com.example.domain.model.PromptMode
import com.example.domain.model.ThemeMode
import com.example.srt.SrtEngine
import com.example.ui.components.ContextAwareTranslationCard
import com.example.ui.components.ParamModeHeader
import com.example.ui.components.SectionTitleRow
import com.example.ui.components.StudioSectionCard
import com.example.ui.theme.BSubShape
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalThemeController
import com.example.ui.viewmodel.MainViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
  settings: AppSettings,
  viewModel: MainViewModel,
  modifier: Modifier = Modifier,
) {
  var editingExample by remember { mutableStateOf<FewShotExample?>(null) }
  var showAddExampleDialog by remember { mutableStateOf(false) }
  val themeController = LocalThemeController.current
  val activeModeLabel =
    when (themeController.themeMode) {
      ThemeMode.DARK -> "Active: Dark Mode"
      ThemeMode.LIGHT -> "Active: Light Mode"
      ThemeMode.SYSTEM ->
        if (themeController.isDarkTheme) "Active: Dark Mode (Following System)"
        else "Active: Light Mode (Following System)"
    }

  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // 1. Theme Selector
    StudioSectionCard {
      SectionTitleRow(
        title = "Appearance & Theme",
        subtitle = activeModeLabel,
        icon = Icons.Rounded.Palette,
      )

      Spacer(modifier = Modifier.height(12.dp))

      val themeSegmentedColors =
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
          selected = themeController.themeMode == ThemeMode.DARK,
          onClick = { themeController.onSetThemeMode(ThemeMode.DARK) },
          shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
          colors = themeSegmentedColors,
          icon = {
            Icon(
              imageVector = Icons.Rounded.DarkMode,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
          },
          modifier = Modifier.testTag("theme_dark_button"),
        ) {
          Text("Dark")
        }
        SegmentedButton(
          selected = themeController.themeMode == ThemeMode.LIGHT,
          onClick = { themeController.onSetThemeMode(ThemeMode.LIGHT) },
          shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
          colors = themeSegmentedColors,
          icon = {
            Icon(
              imageVector = Icons.Rounded.LightMode,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
          },
          modifier = Modifier.testTag("theme_light_button"),
        ) {
          Text("Light")
        }
        SegmentedButton(
          selected = themeController.themeMode == ThemeMode.SYSTEM,
          onClick = { themeController.onSetThemeMode(ThemeMode.SYSTEM) },
          shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
          colors = themeSegmentedColors,
          icon = {
            Icon(
              imageVector = Icons.Rounded.SettingsSuggest,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
          },
          modifier = Modifier.testTag("theme_system_button"),
        ) {
          Text("System")
        }
      }
    }

    // 2. Prompt Settings
    PromptSettingsCard(
      settings = settings,
      onPromptModeChange = viewModel::setPromptMode,
      onManualPromptChange = viewModel::updateManualPrompt,
    )

    // 3. Few-Shot Examples
    StudioSectionCard {
      SectionTitleRow(
        title = "Few-Shot Examples (${settings.fewShotExamples.size})",
        subtitle = "Guide regional, colloquial, or tone-specific Bengali style",
        icon = Icons.Rounded.Psychology,
        trailing = {
          FilledTonalButton(
            onClick = { showAddExampleDialog = true },
            colors =
              ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
              ),
            modifier = Modifier.testTag("add_few_shot_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Add,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add")
          }
        },
      )

      Spacer(modifier = Modifier.height(10.dp))

      if (settings.fewShotExamples.isEmpty()) {
        Text(
          text =
            "No few-shot examples configured. Leave blank to translate using only your system prompt, or tap 'Add' to define dialogue examples.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          settings.fewShotExamples.forEachIndexed { idx, ex ->
            FewShotExampleRow(
              index = idx + 1,
              example = ex,
              onEdit = { editingExample = ex },
              onDelete = { viewModel.deleteFewShotExample(ex.id) },
            )
          }
        }
      }
    }

    // 4. Context-Aware Translation
    ContextAwareTranslationCard(
      settings = settings,
      onSetContextLines = viewModel::setContextLines,
    )

    // 5. Output File Naming Convention
    NamingConventionCard(
      settings = settings,
      onSaveNaming = viewModel::setNamingConfig,
    )

    // 6. Advanced Settings (Default vs Manual for every parameter + Default Prompt Editor)
    AdvancedSettingsCard(
      settings = settings,
      viewModel = viewModel,
    )

    Spacer(modifier = Modifier.height(12.dp))
  }

  if (showAddExampleDialog) {
    FewShotEditorDialog(
      title = "Add Few-Shot Example",
      initialEnglish = "",
      initialBengali = "",
      initialNote = "",
      onDismiss = { showAddExampleDialog = false },
      onSave = { en, bn, note ->
        viewModel.addOrUpdateFewShotExample(null, en, bn, note)
        showAddExampleDialog = false
      },
    )
  }

  editingExample?.let { ex ->
    FewShotEditorDialog(
      title = "Edit Few-Shot Example",
      initialEnglish = ex.englishText,
      initialBengali = ex.bengaliTranslation,
      initialNote = ex.note,
      onDismiss = { editingExample = null },
      onSave = { en, bn, note ->
        viewModel.addOrUpdateFewShotExample(ex.id, en, bn, note)
        editingExample = null
      },
    )
  }
}

@Composable
private fun PromptSettingsCard(
  settings: AppSettings,
  onPromptModeChange: (PromptMode) -> Unit,
  onManualPromptChange: (String) -> Unit,
) {
  var manualDraft by rememberSaveable(settings.manualPrompt) { mutableStateOf(settings.manualPrompt) }

  StudioSectionCard {
    SectionTitleRow(
      title = "Prompt Settings",
      subtitle = "Choose Default or Manual translation instructions",
      icon = Icons.Rounded.AutoAwesome,
    )

    Spacer(modifier = Modifier.height(10.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      RadioButton(
        selected = settings.promptMode == PromptMode.DEFAULT,
        onClick = { onPromptModeChange(PromptMode.DEFAULT) },
        modifier = Modifier.testTag("prompt_mode_default_radio"),
      )
      Column(modifier = Modifier.weight(1f)) {
        Text("Default Prompt", style = MaterialTheme.typography.titleSmall)
        Text(
          text = "Uses your saved default translation prompt (editable in Advanced Settings).",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      RadioButton(
        selected = settings.promptMode == PromptMode.MANUAL,
        onClick = { onPromptModeChange(PromptMode.MANUAL) },
        modifier = Modifier.testTag("prompt_mode_manual_radio"),
      )
      Column(modifier = Modifier.weight(1f)) {
        Text("Manual Prompt", style = MaterialTheme.typography.titleSmall)
        Text(
          text = "Enter custom instructions for the active translation run.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    if (settings.promptMode == PromptMode.MANUAL) {
      Spacer(modifier = Modifier.height(10.dp))
      OutlinedTextField(
        value = manualDraft,
        onValueChange = {
          manualDraft = it
          onManualPromptChange(it)
        },
        label = { Text("Manual Translation Prompt") },
        placeholder = { Text("Enter custom English-to-Bengali translation rules...") },
        minLines = 4,
        maxLines = 10,
        modifier = Modifier.fillMaxWidth().testTag("manual_prompt_input"),
      )
    }
  }
}

@Composable
private fun NamingConventionCard(
  settings: AppSettings,
  onSaveNaming: (NamingMode, String, String) -> Unit,
) {
  var prefixDraft by rememberSaveable(settings.customPrefix) { mutableStateOf(settings.customPrefix) }
  var suffixDraft by rememberSaveable(settings.customSuffix) { mutableStateOf(settings.customSuffix) }

  val previewSettings =
    settings.copy(
      customPrefix = prefixDraft,
      customSuffix = suffixDraft,
    )
  val previewName = SrtEngine.buildOutputFileName("Andor_S02E01.srt", previewSettings)

  StudioSectionCard {
    SectionTitleRow(
      title = "Output File Naming",
      subtitle = "Preview: $previewName",
      icon = Icons.Rounded.DriveFileRenameOutline,
    )

    Spacer(modifier = Modifier.height(12.dp))

    val namingSegmentedColors =
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
        selected = settings.namingMode == NamingMode.DEFAULT,
        onClick = { onSaveNaming(NamingMode.DEFAULT, prefixDraft, suffixDraft) },
        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        colors = namingSegmentedColors,
        modifier = Modifier.testTag("naming_default_button"),
      ) {
        Text("Default (_BSub)")
      }
      SegmentedButton(
        selected = settings.namingMode == NamingMode.CUSTOM,
        onClick = { onSaveNaming(NamingMode.CUSTOM, prefixDraft, suffixDraft) },
        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        colors = namingSegmentedColors,
        modifier = Modifier.testTag("naming_custom_button"),
      ) {
        Text("Custom Prefix/Suffix")
      }
    }

    if (settings.namingMode == NamingMode.CUSTOM) {
      Spacer(modifier = Modifier.height(12.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedTextField(
          value = prefixDraft,
          onValueChange = {
            prefixDraft = it
            onSaveNaming(NamingMode.CUSTOM, it, suffixDraft)
          },
          label = { Text("Prefix") },
          placeholder = { Text("Bengali_") },
          singleLine = true,
          modifier = Modifier.weight(1f).testTag("naming_prefix_input"),
        )
        OutlinedTextField(
          value = suffixDraft,
          onValueChange = {
            suffixDraft = it
            onSaveNaming(NamingMode.CUSTOM, prefixDraft, it)
          },
          label = { Text("Suffix") },
          placeholder = { Text("- BSub") },
          singleLine = true,
          modifier = Modifier.weight(1f).testTag("naming_suffix_input"),
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedSettingsCard(
  settings: AppSettings,
  viewModel: MainViewModel,
) {
  var chunkInput by
    rememberSaveable(settings.manualChunkSize) {
      mutableStateOf(settings.manualChunkSize.toString())
    }
  var modelInput by
    rememberSaveable(settings.manualModel) { mutableStateOf(settings.manualModel) }
  var tempInput by
    rememberSaveable(settings.manualTemperature) {
      mutableStateOf(settings.manualTemperature.toString())
    }
  var thinkingInput by
    rememberSaveable(settings.manualThinkingBudget) {
      mutableStateOf(settings.manualThinkingBudget.toString())
    }
  var retryInput by
    rememberSaveable(settings.manualRetryIntervalSeconds) {
      mutableStateOf(settings.manualRetryIntervalSeconds.toString())
    }
  var attemptsInput by
    rememberSaveable(settings.manualMaxAttemptsPerKey) {
      mutableStateOf(settings.manualMaxAttemptsPerKey.toString())
    }
  var cooldownInput by
    rememberSaveable(settings.manualCooldownMinutes) {
      mutableStateOf(settings.manualCooldownMinutes.toString())
    }
  var defaultPromptDraft by
    rememberSaveable(settings.defaultPrompt) { mutableStateOf(settings.defaultPrompt) }

  StudioSectionCard {
    SectionTitleRow(
      title = "Advanced Settings",
      subtitle = "Switch any parameter between Default and Manual",
      icon = Icons.Rounded.Tune,
    )

    Spacer(modifier = Modifier.height(14.dp))

    // 1. Chunk Size
    ParamModeHeader(
      title = "Chunk Size (Blocks per Batch)",
      defaultSummary = "${DefaultConfig.CHUNK_SIZE} blocks",
      mode = settings.chunkSizeMode,
      onModeSelected = { mode ->
        viewModel.setChunkSize(mode, chunkInput.toIntOrNull() ?: DefaultConfig.CHUNK_SIZE)
      },
    )
    if (settings.chunkSizeMode == ParamMode.MANUAL) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        DefaultConfig.PRESET_CHUNK_SIZES.forEach { preset ->
          FilterChip(
            selected = settings.manualChunkSize == preset,
            onClick = {
              chunkInput = preset.toString()
              viewModel.setChunkSize(ParamMode.MANUAL, preset)
            },
            label = { Text("$preset blocks") },
          )
        }
      }
      Spacer(modifier = Modifier.height(6.dp))
      OutlinedTextField(
        value = chunkInput,
        onValueChange = { raw ->
          chunkInput = raw
          raw.toIntOrNull()?.let { viewModel.setChunkSize(ParamMode.MANUAL, it) }
        },
        label = { Text("Custom Chunk Size (10–2000)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("manual_chunk_size_input"),
      )
    }

    Spacer(modifier = Modifier.height(10.dp))
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
          Text(
            text = "Dynamic Latency-Based Batching",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text =
              "Auto-scales batch size up when recent API latency <= 3s and shrinks if slow (>10s) or rate-limited.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = settings.dynamicBatchSizingEnabled,
          onCheckedChange = viewModel::setDynamicBatchSizing,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("dynamic_batch_sizing_switch"),
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(12.dp))

    // 2. AI Model (for active engine selected above)
    val defaultModelForProvider = DefaultConfig.defaultModelFor(settings.apiProvider)
    val presetModelsForProvider = DefaultConfig.presetModelsFor(settings.apiProvider)

    ParamModeHeader(
      title = "AI Model (${settings.apiProvider.displayName})",
      defaultSummary = defaultModelForProvider,
      mode = settings.modelMode,
      onModeSelected = { mode -> viewModel.setModel(mode, modelInput) },
    )
    if (settings.modelMode == ParamMode.MANUAL) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        presetModelsForProvider.forEach { preset ->
          FilterChip(
            selected = settings.manualModel == preset,
            onClick = {
              modelInput = preset
              viewModel.setModel(ParamMode.MANUAL, preset)
            },
            label = { Text(preset) },
            modifier = Modifier.testTag("preset_model_chip_$preset"),
          )
        }
      }
      Spacer(modifier = Modifier.height(6.dp))
      OutlinedTextField(
        value = modelInput,
        onValueChange = {
          modelInput = it
          viewModel.setModel(ParamMode.MANUAL, it)
        },
        label = { Text("Model ID") },
        placeholder = { Text(defaultModelForProvider) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("manual_model_input"),
      )
    }

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(12.dp))

    // 3. Temperature
    ParamModeHeader(
      title = "Temperature",
      defaultSummary = DefaultConfig.TEMPERATURE.toString(),
      mode = settings.temperatureMode,
      onModeSelected = { mode ->
        viewModel.setTemperature(mode, tempInput.toFloatOrNull() ?: DefaultConfig.TEMPERATURE)
      },
    )
    if (settings.temperatureMode == ParamMode.MANUAL) {
      OutlinedTextField(
        value = tempInput,
        onValueChange = { raw ->
          tempInput = raw
          raw.toFloatOrNull()?.let { viewModel.setTemperature(ParamMode.MANUAL, it) }
        },
        label = { Text("Temperature (0.0 – 2.0)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag("manual_temperature_input"),
      )
    }

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(12.dp))

    // 4. Thinking Budget
    ParamModeHeader(
      title = "Thinking Budget",
      defaultSummary =
        if (DefaultConfig.THINKING_BUDGET == 0) {
          "0 (Disabled)"
        } else {
          DefaultConfig.THINKING_BUDGET.toString()
        },
      mode = settings.thinkingBudgetMode,
      onModeSelected = { mode ->
        viewModel.setThinkingBudget(
          mode,
          thinkingInput.toIntOrNull() ?: DefaultConfig.THINKING_BUDGET,
        )
      },
    )
    if (settings.thinkingBudgetMode == ParamMode.MANUAL) {
      OutlinedTextField(
        value = thinkingInput,
        onValueChange = { raw ->
          thinkingInput = raw
          raw.toIntOrNull()?.let { viewModel.setThinkingBudget(ParamMode.MANUAL, it) }
        },
        label = { Text("Thinking Budget (0 – 32768)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("manual_thinking_budget_input"),
      )
    }

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(12.dp))

    // 5. Retry Interval
    ParamModeHeader(
      title = "Retry Interval",
      defaultSummary = "${DefaultConfig.RETRY_INTERVAL_SECONDS} seconds",
      mode = settings.retryIntervalMode,
      onModeSelected = { mode ->
        viewModel.setRetryInterval(
          mode,
          retryInput.toIntOrNull() ?: DefaultConfig.RETRY_INTERVAL_SECONDS,
        )
      },
    )
    if (settings.retryIntervalMode == ParamMode.MANUAL) {
      OutlinedTextField(
        value = retryInput,
        onValueChange = { raw ->
          retryInput = raw
          raw.toIntOrNull()?.let { viewModel.setRetryInterval(ParamMode.MANUAL, it) }
        },
        label = { Text("Retry Interval (seconds)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("manual_retry_interval_input"),
      )
    }

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(12.dp))

    // 6. Maximum Attempts per API Key
    ParamModeHeader(
      title = "Max Attempts per API Key",
      defaultSummary = "${DefaultConfig.MAX_ATTEMPTS_PER_KEY} attempts",
      mode = settings.maxAttemptsMode,
      onModeSelected = { mode ->
        viewModel.setMaxAttemptsPerKey(
          mode,
          attemptsInput.toIntOrNull() ?: DefaultConfig.MAX_ATTEMPTS_PER_KEY,
        )
      },
    )
    if (settings.maxAttemptsMode == ParamMode.MANUAL) {
      OutlinedTextField(
        value = attemptsInput,
        onValueChange = { raw ->
          attemptsInput = raw
          raw.toIntOrNull()?.let { viewModel.setMaxAttemptsPerKey(ParamMode.MANUAL, it) }
        },
        label = { Text("Max Attempts per Key (1–20)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("manual_max_attempts_input"),
      )
    }

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(12.dp))

    // 7. All Keys Exhausted Cooldown
    ParamModeHeader(
      title = "All Keys Exhausted Cooldown",
      defaultSummary = "${DefaultConfig.COOLDOWN_MINUTES} minutes",
      mode = settings.cooldownMode,
      onModeSelected = { mode ->
        viewModel.setCooldownMinutes(
          mode,
          cooldownInput.toIntOrNull() ?: DefaultConfig.COOLDOWN_MINUTES,
        )
      },
    )
    if (settings.cooldownMode == ParamMode.MANUAL) {
      OutlinedTextField(
        value = cooldownInput,
        onValueChange = { raw ->
          cooldownInput = raw
          raw.toIntOrNull()?.let { viewModel.setCooldownMinutes(ParamMode.MANUAL, it) }
        },
        label = { Text("Cooldown Duration (minutes)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("manual_cooldown_input"),
      )
    }

    Spacer(modifier = Modifier.height(14.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(14.dp))

    // 8. Default Prompt Viewer & Editor
    Text(
      text = "Edit Default Translation Prompt",
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(6.dp))
    OutlinedTextField(
      value = defaultPromptDraft,
      onValueChange = { defaultPromptDraft = it },
      minLines = 6,
      maxLines = 12,
      textStyle = MaterialTheme.typography.bodySmall,
      modifier = Modifier.fillMaxWidth().testTag("default_prompt_editor_input"),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      OutlinedButton(
        onClick = {
          defaultPromptDraft = DefaultConfig.BUILT_IN_PROMPT
          viewModel.resetDefaultPrompt()
        },
        modifier = Modifier.testTag("restore_default_prompt_button"),
      ) {
        Icon(
          imageVector = Icons.Rounded.Restore,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text("Restore Built-in")
      }

      Button(
        onClick = { viewModel.updateDefaultPrompt(defaultPromptDraft) },
        shape = BSubShape.petalButton,
        modifier = Modifier.testTag("save_default_prompt_button"),
      ) {
        Text("Save Default Prompt")
      }
    }
  }
}

@Composable
private fun FewShotExampleRow(
  index: Int,
  example: FewShotExample,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text =
            if (example.note.isNotBlank()) "Example #$index • ${example.note}"
            else "Example #$index",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "EN: ${example.englishText}",
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "BN: ${example.bengaliTranslation}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton(
        onClick = onEdit,
        modifier = Modifier.minimumInteractiveComponentSize(),
      ) {
        Icon(
          imageVector = Icons.Rounded.Edit,
          contentDescription = "Edit few-shot example",
          modifier = Modifier.size(18.dp),
        )
      }
      IconButton(
        onClick = onDelete,
        modifier = Modifier.minimumInteractiveComponentSize(),
      ) {
        Icon(
          imageVector = Icons.Rounded.Delete,
          contentDescription = "Delete few-shot example",
          tint = MaterialTheme.colorScheme.error,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

@Composable
private fun FewShotEditorDialog(
  title: String,
  initialEnglish: String,
  initialBengali: String,
  initialNote: String,
  onDismiss: () -> Unit,
  onSave: (english: String, bengali: String, note: String) -> Unit,
) {
  var english by rememberSaveable { mutableStateOf(initialEnglish) }
  var bengali by rememberSaveable { mutableStateOf(initialBengali) }
  var note by rememberSaveable { mutableStateOf(initialNote) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedTextField(
          value = note,
          onValueChange = { note = it },
          label = { Text("Style / Tone Note (Optional)") },
          placeholder = { Text("e.g., Casual Dhaka slang / Formal respect") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = english,
          onValueChange = { english = it },
          label = { Text("English Subtitle Line / Block") },
          placeholder = { Text("1\n00:00:05,000 --> 00:00:07,500\nWhat are you doing here?") },
          minLines = 3,
          maxLines = 6,
          textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = bengali,
          onValueChange = { bengali = it },
          label = { Text("Target Bengali Translation") },
          placeholder = { Text("1\n00:00:05,000 --> 00:00:07,500\nতুমি এখানে কী করছ?") },
          minLines = 3,
          maxLines = 6,
          textStyle = MaterialTheme.typography.bodySmall,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onSave(english, bengali, note) },
        enabled = english.isNotBlank() && bengali.isNotBlank(),
      ) {
        Text("Save Example")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}
