package com.opentether.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.ui.graphics.vector.ImageVector

sealed class OpenTetherDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    object Dashboard : OpenTetherDestination("dashboard", "Dashboard", Icons.Outlined.Dashboard)
    object Tunnels : OpenTetherDestination("tunnels", "Tunnels", Icons.Outlined.SyncAlt)
    object Logs : OpenTetherDestination("logs", "Logs", Icons.Outlined.Article)
    object Settings : OpenTetherDestination("settings", "Settings", Icons.Outlined.Settings)

    companion object {
        fun topLevel(): List<OpenTetherDestination> = listOf(
            Dashboard,
            Tunnels,
            Logs,
            Settings,
        )
    }
}
