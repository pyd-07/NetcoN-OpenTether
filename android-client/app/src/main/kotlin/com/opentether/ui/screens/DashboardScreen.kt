package com.opentether.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentether.OpenTetherUiState
import com.opentether.formatBytes
import com.opentether.formatDuration
import com.opentether.formatRate
import com.opentether.ui.components.EmptyState
import com.opentether.ui.components.LegendRow
import com.opentether.ui.components.MetricTile
import com.opentether.ui.components.SectionCard
import com.opentether.ui.components.StatusPill
import com.opentether.ui.components.TrafficChart
import com.opentether.ui.theme.OtBlue
import com.opentether.ui.theme.OtGreen
import com.opentether.ui.theme.OtYellow

@Composable
fun DashboardScreen(
    uiState: OpenTetherUiState,
    onStartRequested: () -> Unit,
    onStopRequested: () -> Unit,
    onOpenLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uptimeMs = uiState.runtime.sessionStartedAtMs?.let { System.currentTimeMillis() - it }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Session card ──────────────────────────────────────────────────
        item {
            SectionCard(title = "Session") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusPill(
                            text = uiState.runtime.statusLabel,
                            phase = uiState.runtime.phase,
                        )
                        Text(
                            text = uiState.runtime.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (uptimeMs != null && uptimeMs > 0) {
                            Text(
                                text = "Up ${formatDuration(uptimeMs)}  ·  ${uiState.runtime.transport.label}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onStartRequested,
                        enabled = !uiState.runtime.isRunning,
                    ) { Text("Start") }
                    OutlinedButton(
                        onClick = onStopRequested,
                        enabled = uiState.runtime.isRunning,
                    ) { Text("Stop") }
                    OutlinedButton(onClick = onOpenLogs) { Text("Logs") }
                }
            }
        }

        // ── Metrics 2×2 grid ─────────────────────────────────────────────
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MetricTile(
                        label = "Download",
                        value = formatRate(uiState.stats.downloadBytesPerSec),
                        modifier = Modifier.weight(1f),
                        accent = OtGreen,
                    )
                    MetricTile(
                        label = "Upload",
                        value = formatRate(uiState.stats.uploadBytesPerSec),
                        modifier = Modifier.weight(1f),
                        accent = OtBlue,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MetricTile(
                        label = "RTT",
                        value = if (uiState.stats.rttMs > 0) "${uiState.stats.rttMs} ms" else "—",
                        modifier = Modifier.weight(1f),
                        accent = OtYellow,
                    )
                    MetricTile(
                        label = "Peers",
                        value = uiState.stats.activeConnections.toString(),
                        modifier = Modifier.weight(1f),
                        accent = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // ── Traffic chart ─────────────────────────────────────────────────
        item {
            SectionCard(
                title = "Traffic",
                subtitle = "Per-second throughput · last 30 samples",
            ) {
                TrafficChart(
                    points = uiState.throughputHistory,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LegendRow(label = "Download", color = OtGreen)
                    LegendRow(label = "Upload", color = OtBlue)
                }
                Text(
                    text = "Total  ↓ ${formatBytes(uiState.stats.totalDownloadBytes)}  ↑ ${formatBytes(uiState.stats.totalUploadBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Active connections ────────────────────────────────────────────
        item {
            SectionCard(
                title = "Connections",
                subtitle = "Live peer endpoints from TUN traffic",
            ) {
                if (uiState.stats.connections.isEmpty()) {
                    EmptyState(
                        title = "No active peers",
                        message = "Start the tunnel and generate traffic to see live connection data.",
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        uiState.stats.connections.take(8).forEach { connection ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = connection.host,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        text = "↓ ${formatRate(connection.downloadBytesPerSec)}  ↑ ${formatRate(connection.uploadBytesPerSec)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = formatRate(connection.downloadBytesPerSec + connection.uploadBytesPerSec),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (connection.active) OtGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
