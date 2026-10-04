package com.transvibe.app.presentation.navigation

import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.transvibe.app.R
import com.transvibe.app.TransVibeApplication
import com.transvibe.app.presentation.components.AppBackground
import com.transvibe.app.presentation.history.HistoryScreen
import com.transvibe.app.presentation.history.HistoryViewModel
import com.transvibe.app.presentation.home.HomeScreen
import com.transvibe.app.presentation.home.HomeViewModel
import com.transvibe.app.presentation.models.ModelManagerScreen
import com.transvibe.app.presentation.models.ModelsViewModel
import com.transvibe.app.presentation.player.PlayerScreen
import com.transvibe.app.presentation.player.PlayerViewModel
import com.transvibe.app.presentation.processing.ProcessingScreen
import com.transvibe.app.presentation.processing.ProcessingViewModel
import com.transvibe.app.presentation.settings.SettingsScreen
import com.transvibe.app.presentation.settings.SettingsViewModel
import com.transvibe.app.presentation.startup.StartupScreen
import com.transvibe.app.presentation.theme.TransVibeTheme

private object Routes {
    const val STARTUP = "startup"
    const val HOME = "home"
    const val MODELS = "models"
    const val PROCESSING = "processing"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransVibeApp() {
    val app = LocalContext.current.applicationContext as TransVibeApplication
    val factory = remember(app) { app.viewModelFactory }
    val homeVm: HomeViewModel = viewModel(factory = factory)
    val processingVm: ProcessingViewModel = viewModel(factory = factory)
    val modelsVm: ModelsViewModel = viewModel(factory = factory)
    val historyVm: HistoryViewModel = viewModel(factory = factory)
    val playerVm: PlayerViewModel = viewModel(factory = factory)
    val settingsVm: SettingsViewModel = viewModel(factory = factory)
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val process by processingVm.state.collectAsStateWithLifecycle()
    val homeMessage by homeVm.message.collectAsStateWithLifecycle()
    val processingMessage by processingVm.message.collectAsStateWithLifecycle()
    val modelsMessage by modelsVm.message.collectAsStateWithLifecycle()
    val historyMessage by historyVm.message.collectAsStateWithLifecycle()
    val settingsPrefs by settingsVm.preferences.collectAsStateWithLifecycle()

    fun goTop(target: String) {
        nav.navigate(target) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val message = when (route) {
        Routes.HOME -> homeMessage
        Routes.PROCESSING -> processingMessage
        Routes.MODELS, Routes.STARTUP -> modelsMessage
        Routes.HISTORY -> historyMessage
        else -> null
    }
    val dismissMessage: () -> Unit = when (route) {
        Routes.HOME -> homeVm::dismissMessage
        Routes.PROCESSING -> processingVm::dismissMessage
        Routes.MODELS, Routes.STARTUP -> modelsVm::dismissMessage
        Routes.HISTORY -> historyVm::dismissMessage
        else -> ({})
    }

    LocalizedUi(settingsPrefs.applicationLanguage) {
        TransVibeTheme(themeMode = settingsPrefs.themeMode) {
            AppBackground {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        if (route != Routes.STARTUP) {
                            androidx.compose.foundation.layout.Column {
                                TopAppBar(
                                    title = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Rounded.VideoLibrary,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(19.dp),
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                            Text(
                                                stringResource(R.string.app_name),
                                                modifier = Modifier.padding(start = 10.dp),
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    },
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                        titleContentColor = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                )
                            }
                        }
                    },
                    bottomBar = {
                        if (route in setOf(Routes.HOME, Routes.MODELS, Routes.HISTORY, Routes.SETTINGS)) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                tonalElevation = 3.dp
                            ) {
                                AppNavItem(
                                    selected = route == Routes.HOME,
                                    onClick = { goTop(Routes.HOME) },
                                    icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                                    label = stringResource(R.string.nav_home)
                                )
                                AppNavItem(
                                    selected = route == Routes.MODELS,
                                    onClick = { goTop(Routes.MODELS) },
                                    icon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                                    label = stringResource(R.string.nav_models)
                                )
                                AppNavItem(
                                    selected = route == Routes.HISTORY,
                                    onClick = { goTop(Routes.HISTORY) },
                                    icon = { Icon(Icons.Rounded.History, contentDescription = null) },
                                    label = stringResource(R.string.nav_history)
                                )
                                AppNavItem(
                                    selected = route == Routes.SETTINGS,
                                    onClick = { goTop(Routes.SETTINGS) },
                                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                                    label = stringResource(R.string.nav_settings)
                                )
                            }
                        }
                    }
                ) { pad ->
                    NavHost(navController = nav, startDestination = Routes.STARTUP, modifier = Modifier.padding(pad)) {
                        composable(Routes.STARTUP) { StartupScreen(modelsVm, onContinue = { goTop(Routes.HOME) }) }
                        composable(Routes.HOME) {
                            HomeScreen(
                                vm = homeVm,
                                process = process,
                                onCancelProcessing = processingVm::cancel,
                                onModels = { goTop(Routes.MODELS) },
                                onProcessing = { nav.navigate(Routes.PROCESSING) }
                            )
                        }
                        composable(Routes.MODELS) { ModelManagerScreen(modelsVm) }
                        composable(Routes.PROCESSING) {
                            ProcessingScreen(
                                processingVm,
                                process,
                                onPlayer = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } },
                                onHome = { processingVm.clearCurrent(); goTop(Routes.HOME) },
                                onModels = { goTop(Routes.MODELS) }
                            )
                        }
                        composable(Routes.PLAYER) {
                            PlayerScreen(playerVm, onAnother = { processingVm.clearCurrent(); goTop(Routes.HOME) })
                        }
                        composable(Routes.SETTINGS) { SettingsScreen(settingsVm, onModels = { goTop(Routes.MODELS) }) }
                        composable(Routes.HISTORY) {
                            HistoryScreen(historyVm) { summary ->
                                historyVm.openSession(summary.jobId) { nav.navigate(Routes.PLAYER) }
                            }
                        }
                    }
                }
            }
            message?.let {
                AlertDialog(
                    onDismissRequest = dismissMessage,
                    confirmButton = { TextButton(onClick = dismissMessage) { Text(stringResource(R.string.ok)) } },
                    text = { Text(it, color = MaterialTheme.colorScheme.onSurface) }
                )
            }
        }
    }
}

@Composable
private fun RowScope.AppNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: String
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer
                )
                .padding(horizontal = 18.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides contentColor
            ) {
                icon()
            }
        }
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.onSurface else contentColor,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}

@Composable
private fun LocalizedUi(language: String, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val baseConfiguration = LocalConfiguration.current
    if (language == "system") {
        content()
        return
    }
    val configuration = remember(baseConfiguration, language) {
        Configuration(baseConfiguration).apply { setLocales(LocaleList.forLanguageTags(language)) }
    }
    val localizedContext = remember(baseContext, configuration) {
        baseContext.createConfigurationContext(configuration)
    }
    val direction = if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides configuration,
        LocalLayoutDirection provides direction,
        content = content
    )
}
