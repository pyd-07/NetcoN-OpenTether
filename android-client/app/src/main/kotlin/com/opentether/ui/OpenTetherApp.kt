package com.opentether.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opentether.OpenTetherViewModel
import com.opentether.ui.components.ConnectionDetailsDialog
import com.opentether.ui.components.ConnectionSettingsDialog
import com.opentether.ui.screens.DocsScreen
import com.opentether.ui.screens.HomeScreen

enum class OpenTetherSection(val label: String) {
    Home("Home"),
    Docs("Docs"),
}

@Composable
fun OpenTetherApp(
    viewModel: OpenTetherViewModel,
    onRequestStartVpnPermission: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(OpenTetherSection.Home) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = section == OpenTetherSection.Docs) {
        section = OpenTetherSection.Home
    }

    LaunchedEffect(section) {
        if (section == OpenTetherSection.Docs) {
            viewModel.refreshDiagnostics()
        }
    }
    LaunchedEffect(showDetails) {
        if (showDetails) {
            viewModel.refreshDiagnostics()
        }
    }

    BoxWithConstraints {
        val useRail = maxWidth >= 720.dp
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (!useRail) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        OpenTetherSection.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = section == tab,
                                onClick = { section = tab },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon(),
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (useRail) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        OpenTetherSection.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = section == tab,
                                onClick = { section = tab },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon(),
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (section) {
                        OpenTetherSection.Home -> HomeScreen(
                            runtime = uiState.runtime,
                            onConnect = onRequestStartVpnPermission,
                            onDisconnect = viewModel::stopVpnService,
                            onOpenDetails = { showDetails = true },
                            onOpenSettings = { showSettings = true },
                        )

                        OpenTetherSection.Docs -> DocsScreen(
                            diagnostics = uiState.diagnostics,
                            onRefreshDiagnostics = viewModel::refreshDiagnostics,
                            onOpenAppSettings = viewModel::openAppSettings,
                            onOpenBatterySettings = viewModel::openBatteryOptimizationSettings,
                        )
                    }
                }
            }
        }
    }

    if (showDetails) {
        ConnectionDetailsDialog(
            runtime = uiState.runtime,
            diagnostics = uiState.diagnostics,
            appVersion = uiState.appVersion,
            onRefresh = viewModel::refreshDiagnostics,
            onDismiss = { showDetails = false },
        )
    }

    if (showSettings) {
        ConnectionSettingsDialog(
            settings = uiState.settings,
            isRunning = uiState.runtime.isRunning,
            onTransportChanged = viewModel::updateTransport,
            onAutoStartChanged = viewModel::updateAutoStartOnAccessory,
            onDnsServerChanged = viewModel::updateDnsServer,
            onDismiss = { showSettings = false },
        )
    }
}

private fun OpenTetherSection.icon() = when (this) {
    OpenTetherSection.Home -> Icons.Default.Home
    OpenTetherSection.Docs -> Icons.Default.Info
}
