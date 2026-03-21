package com.opentether.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.opentether.OpenTetherViewModel
import com.opentether.ui.components.StatusPill
import com.opentether.ui.navigation.OpenTetherDestination
import com.opentether.ui.screens.DashboardScreen
import com.opentether.ui.screens.LogsScreen
import com.opentether.ui.screens.SettingsScreen
import com.opentether.ui.screens.TunnelsScreen
import com.opentether.ui.theme.OtBackground

@Composable
fun OpenTetherApp(
    viewModel: OpenTetherViewModel,
    onRequestStartVpnPermission: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: OpenTetherDestination.Dashboard.route
    val screenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val useRail = screenWidthDp >= 840
    val compactTopBar = screenWidthDp < 420
    val topLevelDestinations = OpenTetherDestination.topLevel()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = OtBackground,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "OpenTether",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusPill(
                        text = uiState.runtime.statusLabel,
                        phase = uiState.runtime.phase,
                        compact = compactTopBar,
                    )
                    if (uiState.runtime.isRunning) {
                        IconButton(onClick = { viewModel.stopVpnService() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop VPN")
                        }
                    } else {
                        IconButton(onClick = onRequestStartVpnPermission) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Start VPN")
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (!useRail) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    topLevelDestinations.forEach { destination ->
                        val selected = currentRoute == destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    launchSingleTop = true
                                    popUpTo(OpenTetherDestination.Dashboard.route) {
                                        saveState = true
                                    }
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
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
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    topLevelDestinations.forEach { destination ->
                        val selected = currentRoute == destination.route
                        NavigationRailItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    launchSingleTop = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                NavHost(
                    navController = navController,
                    startDestination = OpenTetherDestination.Dashboard.route,
                ) {
                    composable(OpenTetherDestination.Dashboard.route) {
                        DashboardScreen(
                            uiState = uiState,
                            onStartRequested = onRequestStartVpnPermission,
                            onStopRequested = { viewModel.stopVpnService() },
                            onOpenLogs = { navController.navigate(OpenTetherDestination.Logs.route) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    composable(OpenTetherDestination.Tunnels.route) {
                        TunnelsScreen(
                            uiState = uiState,
                            onTransportSelected = viewModel::updateTransport,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    composable(OpenTetherDestination.Logs.route) {
                        LogsScreen(
                            logs = uiState.logs,
                            terminalEnabled = uiState.settings.terminalEnabled,
                            showTechnicalDetails = uiState.settings.showDebugLogs,
                            onClearLogs = viewModel::clearLogs,
                            onSubmitCommand = viewModel::submitCommand,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    composable(OpenTetherDestination.Settings.route) {
                        SettingsScreen(
                            settings = uiState.settings,
                            onAutoStartChanged = viewModel::updateAutoStartOnAccessory,
                            onShowDebugLogsChanged = viewModel::updateShowDebugLogs,
                            onTerminalEnabledChanged = viewModel::updateTerminalEnabled,
                            onDnsServerChanged = viewModel::updateDnsServer,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
