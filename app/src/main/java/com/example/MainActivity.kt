package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.ApiKeysScreen
import com.example.ui.screens.FirstLaunchPromptDialog
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.screens.SubtitlePreviewScreen
import com.example.ui.screens.UsageDashboardScreen
import com.example.ui.theme.ThemeProvider
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.flow.collectLatest

private enum class AppDestination(
  val route: String,
  val label: String,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
) {
  STUDIO(
    route = "studio",
    label = "Studio",
    selectedIcon = Icons.Filled.Subtitles,
    unselectedIcon = Icons.Outlined.Subtitles,
    testTag = "nav_studio",
  ),
  KEYS(
    route = "keys",
    label = "Keys",
    selectedIcon = Icons.Filled.Key,
    unselectedIcon = Icons.Outlined.Key,
    testTag = "nav_keys",
  ),
  DASHBOARD(
    route = "usage",
    label = "Usage",
    selectedIcon = Icons.Filled.Analytics,
    unselectedIcon = Icons.Outlined.Analytics,
    testTag = "nav_dashboard",
  ),
  HISTORY(
    route = "history",
    label = "History",
    selectedIcon = Icons.Filled.History,
    unselectedIcon = Icons.Outlined.History,
    testTag = "nav_history",
  ),
  SETTINGS(
    route = "settings",
    label = "Settings",
    selectedIcon = Icons.Filled.Settings,
    unselectedIcon = Icons.Outlined.Settings,
    testTag = "nav_settings",
  ),
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val mainViewModel: MainViewModel = viewModel()
      val settings by mainViewModel.settings.collectAsStateWithLifecycle()

      // Wait for saved settings so the first frame uses the right theme
      // and the first-launch dialog never flashes for returning users.
      if (settings.isLoaded) {
        ThemeProvider(
          themeMode = settings.themeMode,
          onThemeModeChange = mainViewModel::setThemeMode,
        ) {
          BSubAppRoot(viewModel = mainViewModel)
        }
      }
    }
  }
}

@Composable
private fun BSubAppRoot(viewModel: MainViewModel) {
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  val studioForm by viewModel.studioForm.collectAsStateWithLifecycle()
  val apiKeys by viewModel.apiKeys.collectAsStateWithLifecycle()
  val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
  val roomProgress by viewModel.roomTranslationProgress.collectAsStateWithLifecycle()
  val fileProgressList by viewModel.fileTranslationProgressList.collectAsStateWithLifecycle()
  val backgroundWorkInfos by viewModel.backgroundWorkInfos.collectAsStateWithLifecycle()
  val historyItems by viewModel.historyItems.collectAsStateWithLifecycle()
  val historySearchQuery by viewModel.historySearchQuery.collectAsStateWithLifecycle()
  val historyContentSnippets by viewModel.historyContentSnippets.collectAsStateWithLifecycle()
  val apiKeyUsageStats by viewModel.apiKeyUsageStats.collectAsStateWithLifecycle()
  val recentApiUsageEvents by viewModel.recentApiUsageEvents.collectAsStateWithLifecycle()
  val previewState by viewModel.previewState.collectAsStateWithLifecycle()

  val navController = rememberNavController()
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentDestination = navBackStackEntry?.destination
  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(viewModel) {
    viewModel.uiMessages.collectLatest { msg ->
      snackbarHostState.showSnackbar(message = msg)
    }
  }

  if (!settings.hasCompletedFirstLaunchSetup) {
    FirstLaunchPromptDialog(
      initialPrompt = settings.defaultPrompt,
      onSaveAndContinue = { prompt -> viewModel.completeFirstLaunchSetup(prompt) },
    )
  }

  val navigateToTab: (String) -> Unit = { route ->
    navController.navigate(route) {
      popUpTo(navController.graph.findStartDestination().id) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }

  val context = LocalContext.current

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useNavigationRail = maxWidth >= 600.dp

    Scaffold(
      modifier = Modifier.fillMaxSize(),
      containerColor = MaterialTheme.colorScheme.background,
      contentWindowInsets = WindowInsets.safeDrawing,
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
      bottomBar = {
        if (!useNavigationRail) {
          NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            AppDestination.entries.forEach { dest ->
              val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
              NavigationBarItem(
                selected = selected,
                onClick = { navigateToTab(dest.route) },
                icon = {
                  Icon(
                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                    contentDescription = dest.label,
                  )
                },
                label = { Text(dest.label) },
                colors =
                  NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                  ),
                modifier = Modifier.testTag(dest.testTag),
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Row(
        modifier = Modifier.fillMaxSize().padding(innerPadding),
      ) {
        if (useNavigationRail) {
          NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            AppDestination.entries.forEach { dest ->
              val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
              NavigationRailItem(
                selected = selected,
                onClick = { navigateToTab(dest.route) },
                icon = {
                  Icon(
                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                    contentDescription = dest.label,
                  )
                },
                label = { Text(dest.label) },
                colors =
                  NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                  ),
                modifier = Modifier.testTag(dest.testTag),
              )
            }
          }
        }

        Box(
          modifier = Modifier.weight(1f).fillMaxSize(),
          contentAlignment = Alignment.TopCenter,
        ) {
          NavHost(
            navController = navController,
            startDestination = AppDestination.STUDIO.route,
            modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
          ) {
            composable(AppDestination.STUDIO.route) {
              StudioScreen(
                formState = studioForm,
                settings = settings,
                apiKeys = apiKeys,
                sessionState = sessionState,
                roomProgress = roomProgress,
                fileProgressList = fileProgressList,
                workInfos = backgroundWorkInfos,
                onInputModeChange = viewModel::setInputMode,
                onMovieNameChange = viewModel::updateMovieName,
                onReleaseYearChange = viewModel::updateReleaseYear,
                onSeriesNameChange = viewModel::updateSeriesName,
                onSeasonChange = viewModel::updateSeason,
                onSingleSrtPicked = viewModel::onSingleSrtPicked,
                onMultipleSrtPicked = viewModel::onMultipleSrtPicked,
                onRemoveFile = viewModel::removeSelectedFile,
                onMoveSeriesFile = viewModel::moveSeriesFile,
                onClearFiles = viewModel::clearSelectedFiles,
                onStartTranslation = { viewModel.startTranslation() },
                onAbortTranslation = viewModel::abortTranslation,
                onClearLogs = viewModel::clearProcessingLogs,
                onLogsCopied = viewModel::notifyLogsCopied,
                onPreviewFile = viewModel::openStudioFilePreview,
                onNavigateToKeys = { navigateToTab(AppDestination.KEYS.route) },
                onNavigateToEngineSettings = { navigateToTab(AppDestination.KEYS.route) },
              )
            }

            composable(AppDestination.KEYS.route) {
              ApiKeysScreen(
                apiKeys = apiKeys,
                isTranslationRunning = sessionState.isRunningOrCooldown,
                activeProvider = settings.apiProvider,
                effectiveModel = settings.effectiveModel,
                onSetActiveProvider = viewModel::setApiProvider,
                onSaveBulkKeys = viewModel::saveBulkApiKeys,
                onImportKeysFile = viewModel::importApiKeysFromUri,
                onAddSingleKey = viewModel::addSingleApiKey,
                onEditKey = viewModel::editApiKey,
                onRemoveKey = viewModel::removeApiKey,
                onClearAllKeys = viewModel::clearAllApiKeys,
                onResetAllStatuses = viewModel::resetAllKeyStatuses,
              )
            }

            composable(AppDestination.DASHBOARD.route) {
              UsageDashboardScreen(
                keyStats = apiKeyUsageStats,
                recentEvents = recentApiUsageEvents,
                configuredKeys = apiKeys,
                settings = settings,
                sessionState = sessionState,
                onClearUsageStats = viewModel::clearApiUsageStats,
              )
            }

            composable(AppDestination.HISTORY.route) {
              HistoryScreen(
                historyItems = historyItems,
                previewState = null,
                onOpenPreview = viewModel::openHistoryPreview,
                onClosePreview = viewModel::closeHistoryPreview,
                onReSaveToDownloads = viewModel::reSaveToDownloads,
                onExportToUri = viewModel::exportHistoryItemToUri,
                onCreateShareIntent = viewModel::createShareIntent,
                onDeleteItem = viewModel::deleteHistoryItem,
                searchQuery = historySearchQuery,
                onSearchQueryChange = viewModel::updateHistorySearchQuery,
                matchedContentSnippets = historyContentSnippets,
              )
            }

            composable(AppDestination.SETTINGS.route) {
              SettingsScreen(
                settings = settings,
                viewModel = viewModel,
              )
            }
          }
        }
      }
    }

    AnimatedVisibility(
      visible = previewState != null,
      enter = fadeIn(),
      exit = fadeOut(),
    ) {
      previewState?.let { activePreview ->
        SubtitlePreviewScreen(
          state = activePreview,
          onClose = viewModel::closeHistoryPreview,
          onShare = { item ->
            viewModel.createShareIntent(item)?.let { shareIntent ->
              context.startActivity(Intent.createChooser(shareIntent, "Share Translated Subtitle"))
            }
          },
        )
      }
    }
  }
}
