package com.example.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.ApiProvider
import com.example.domain.model.AppSettings
import com.example.domain.model.DefaultConfig
import com.example.domain.model.FewShotExample
import com.example.domain.model.NamingMode
import com.example.domain.model.ParamMode
import com.example.domain.model.PromptMode
import com.example.domain.model.ThemeMode
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.settingsDataStore by preferencesDataStore(name = "bsub_app_settings")

class SettingsRepository(private val context: Context) {

  private object Keys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val FIRST_LAUNCH_DONE = booleanPreferencesKey("first_launch_setup_done")
    val PROMPT_MODE = stringPreferencesKey("prompt_mode")
    val DEFAULT_PROMPT = stringPreferencesKey("default_prompt")
    val MANUAL_PROMPT = stringPreferencesKey("manual_prompt")
    val FEW_SHOT_EXAMPLES = stringPreferencesKey("few_shot_examples_json")
    val NAMING_MODE = stringPreferencesKey("naming_mode")
    val CUSTOM_PREFIX = stringPreferencesKey("custom_prefix")
    val CUSTOM_SUFFIX = stringPreferencesKey("custom_suffix")

    val CHUNK_SIZE_MODE = stringPreferencesKey("chunk_size_mode")
    val MANUAL_CHUNK_SIZE = intPreferencesKey("manual_chunk_size")
    val MODEL_MODE = stringPreferencesKey("model_mode")
    val MANUAL_MODEL = stringPreferencesKey("manual_model")
    val TEMPERATURE_MODE = stringPreferencesKey("temperature_mode")
    val MANUAL_TEMPERATURE = floatPreferencesKey("manual_temperature")
    val THINKING_BUDGET_MODE = stringPreferencesKey("thinking_budget_mode")
    val MANUAL_THINKING_BUDGET = intPreferencesKey("manual_thinking_budget")
    val RETRY_INTERVAL_MODE = stringPreferencesKey("retry_interval_mode")
    val MANUAL_RETRY_INTERVAL = intPreferencesKey("manual_retry_interval")
    val MAX_ATTEMPTS_MODE = stringPreferencesKey("max_attempts_mode")
    val MANUAL_MAX_ATTEMPTS = intPreferencesKey("manual_max_attempts")
    val COOLDOWN_MODE = stringPreferencesKey("cooldown_mode")
    val MANUAL_COOLDOWN_MINUTES = intPreferencesKey("manual_cooldown_minutes")
    val CONTEXT_LINES_MODE = stringPreferencesKey("context_lines_mode")
    val MANUAL_CONTEXT_LINES = intPreferencesKey("manual_context_lines")
    val API_PROVIDER = stringPreferencesKey("api_provider")
  }

  val settingsFlow: Flow<AppSettings> =
    context.settingsDataStore.data.map { prefs ->
      AppSettings(
        isLoaded = true,
        themeMode = enumOrDefault(prefs[Keys.THEME_MODE], ThemeMode.DARK),
        hasCompletedFirstLaunchSetup = prefs[Keys.FIRST_LAUNCH_DONE] ?: false,
        promptMode = enumOrDefault(prefs[Keys.PROMPT_MODE], PromptMode.DEFAULT),
        defaultPrompt = prefs[Keys.DEFAULT_PROMPT] ?: DefaultConfig.BUILT_IN_PROMPT,
        manualPrompt = prefs[Keys.MANUAL_PROMPT] ?: "",
        fewShotExamples = decodeFewShotExamples(prefs[Keys.FEW_SHOT_EXAMPLES]),
        namingMode = enumOrDefault(prefs[Keys.NAMING_MODE], NamingMode.DEFAULT),
        customPrefix = prefs[Keys.CUSTOM_PREFIX] ?: "",
        customSuffix = prefs[Keys.CUSTOM_SUFFIX] ?: DefaultConfig.DEFAULT_SUFFIX,
        chunkSizeMode = enumOrDefault(prefs[Keys.CHUNK_SIZE_MODE], ParamMode.DEFAULT),
        manualChunkSize = prefs[Keys.MANUAL_CHUNK_SIZE] ?: DefaultConfig.CHUNK_SIZE,
        modelMode = enumOrDefault(prefs[Keys.MODEL_MODE], ParamMode.DEFAULT),
        manualModel = prefs[Keys.MANUAL_MODEL] ?: DefaultConfig.GEMINI_MODEL,
        temperatureMode = enumOrDefault(prefs[Keys.TEMPERATURE_MODE], ParamMode.DEFAULT),
        manualTemperature = prefs[Keys.MANUAL_TEMPERATURE] ?: DefaultConfig.TEMPERATURE,
        thinkingBudgetMode = enumOrDefault(prefs[Keys.THINKING_BUDGET_MODE], ParamMode.DEFAULT),
        manualThinkingBudget =
          prefs[Keys.MANUAL_THINKING_BUDGET] ?: DefaultConfig.THINKING_BUDGET,
        retryIntervalMode = enumOrDefault(prefs[Keys.RETRY_INTERVAL_MODE], ParamMode.DEFAULT),
        manualRetryIntervalSeconds =
          prefs[Keys.MANUAL_RETRY_INTERVAL] ?: DefaultConfig.RETRY_INTERVAL_SECONDS,
        maxAttemptsMode = enumOrDefault(prefs[Keys.MAX_ATTEMPTS_MODE], ParamMode.DEFAULT),
        manualMaxAttemptsPerKey =
          prefs[Keys.MANUAL_MAX_ATTEMPTS] ?: DefaultConfig.MAX_ATTEMPTS_PER_KEY,
        cooldownMode = enumOrDefault(prefs[Keys.COOLDOWN_MODE], ParamMode.DEFAULT),
        manualCooldownMinutes =
          prefs[Keys.MANUAL_COOLDOWN_MINUTES] ?: DefaultConfig.COOLDOWN_MINUTES,
        contextLinesMode = enumOrDefault(prefs[Keys.CONTEXT_LINES_MODE], ParamMode.DEFAULT),
        manualContextLines =
          prefs[Keys.MANUAL_CONTEXT_LINES] ?: DefaultConfig.CONTEXT_LINES,
        apiProvider = enumOrDefault(prefs[Keys.API_PROVIDER], ApiProvider.GEMINI),
      )
    }

  suspend fun getSettingsSnapshot(): AppSettings = settingsFlow.first()

  suspend fun setThemeMode(mode: ThemeMode) {
    context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
  }

  suspend fun completeFirstLaunchPromptSetup(defaultPrompt: String) {
    val clean = defaultPrompt.trim().ifBlank { DefaultConfig.BUILT_IN_PROMPT }
    context.settingsDataStore.edit {
      it[Keys.DEFAULT_PROMPT] = clean
      it[Keys.FIRST_LAUNCH_DONE] = true
    }
  }

  suspend fun setPromptMode(mode: PromptMode) {
    context.settingsDataStore.edit { it[Keys.PROMPT_MODE] = mode.name }
  }

  suspend fun updateDefaultPrompt(prompt: String) {
    context.settingsDataStore.edit {
      it[Keys.DEFAULT_PROMPT] = prompt.ifBlank { DefaultConfig.BUILT_IN_PROMPT }
    }
  }

  suspend fun resetDefaultPromptToBuiltIn() {
    context.settingsDataStore.edit { it[Keys.DEFAULT_PROMPT] = DefaultConfig.BUILT_IN_PROMPT }
  }

  suspend fun updateManualPrompt(prompt: String) {
    context.settingsDataStore.edit { it[Keys.MANUAL_PROMPT] = prompt }
  }

  suspend fun saveFewShotExamples(examples: List<FewShotExample>) {
    context.settingsDataStore.edit {
      it[Keys.FEW_SHOT_EXAMPLES] = encodeFewShotExamples(examples)
    }
  }

  suspend fun setNamingConfig(mode: NamingMode, prefix: String, suffix: String) {
    context.settingsDataStore.edit {
      it[Keys.NAMING_MODE] = mode.name
      it[Keys.CUSTOM_PREFIX] = prefix
      it[Keys.CUSTOM_SUFFIX] = suffix
    }
  }

  suspend fun setChunkSize(mode: ParamMode, manualValue: Int) {
    context.settingsDataStore.edit {
      it[Keys.CHUNK_SIZE_MODE] = mode.name
      it[Keys.MANUAL_CHUNK_SIZE] = manualValue.coerceIn(10, 2000)
    }
  }

  suspend fun setModel(mode: ParamMode, manualModel: String) {
    context.settingsDataStore.edit {
      it[Keys.MODEL_MODE] = mode.name
      it[Keys.MANUAL_MODEL] = manualModel.trim().ifBlank { DefaultConfig.GEMINI_MODEL }
    }
  }

  suspend fun setTemperature(mode: ParamMode, manualTemp: Float) {
    context.settingsDataStore.edit {
      it[Keys.TEMPERATURE_MODE] = mode.name
      it[Keys.MANUAL_TEMPERATURE] = manualTemp.coerceIn(0.0f, 2.0f)
    }
  }

  suspend fun setThinkingBudget(mode: ParamMode, manualBudget: Int) {
    context.settingsDataStore.edit {
      it[Keys.THINKING_BUDGET_MODE] = mode.name
      it[Keys.MANUAL_THINKING_BUDGET] = manualBudget.coerceIn(0, 32768)
    }
  }

  suspend fun setRetryInterval(mode: ParamMode, seconds: Int) {
    context.settingsDataStore.edit {
      it[Keys.RETRY_INTERVAL_MODE] = mode.name
      it[Keys.MANUAL_RETRY_INTERVAL] = seconds.coerceIn(1, 300)
    }
  }

  suspend fun setMaxAttemptsPerKey(mode: ParamMode, attempts: Int) {
    context.settingsDataStore.edit {
      it[Keys.MAX_ATTEMPTS_MODE] = mode.name
      it[Keys.MANUAL_MAX_ATTEMPTS] = attempts.coerceIn(1, 20)
    }
  }

  suspend fun setCooldownMinutes(mode: ParamMode, minutes: Int) {
    context.settingsDataStore.edit {
      it[Keys.COOLDOWN_MODE] = mode.name
      it[Keys.MANUAL_COOLDOWN_MINUTES] = minutes.coerceIn(1, 180)
    }
  }

  suspend fun setContextLines(mode: ParamMode, manualValue: Int) {
    context.settingsDataStore.edit {
      it[Keys.CONTEXT_LINES_MODE] = mode.name
      it[Keys.MANUAL_CONTEXT_LINES] =
        manualValue.coerceIn(DefaultConfig.MIN_CONTEXT_LINES, DefaultConfig.MAX_CONTEXT_LINES)
    }
  }

  suspend fun setApiProvider(provider: ApiProvider) {
    context.settingsDataStore.edit {
      it[Keys.API_PROVIDER] = provider.name
    }
  }

  private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T {
    if (raw.isNullOrBlank()) return default
    return runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
  }

  private fun encodeFewShotExamples(examples: List<FewShotExample>): String {
    val array = JSONArray()
    examples.forEach { ex ->
      val obj =
        JSONObject().apply {
          put("id", ex.id)
          put("englishText", ex.englishText)
          put("bengaliTranslation", ex.bengaliTranslation)
          put("note", ex.note)
        }
      array.put(obj)
    }
    return array.toString()
  }

  private fun decodeFewShotExamples(rawJson: String?): List<FewShotExample> {
    if (rawJson.isNullOrBlank()) return emptyList()
    return try {
      val array = JSONArray(rawJson)
      val list = ArrayList<FewShotExample>(array.length())
      for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        list.add(
          FewShotExample(
            id = obj.optString("id", UUID.randomUUID().toString()),
            englishText = obj.optString("englishText", ""),
            bengaliTranslation = obj.optString("bengaliTranslation", ""),
            note = obj.optString("note", ""),
          )
        )
      }
      list
    } catch (_: Exception) {
      emptyList()
    }
  }
}
