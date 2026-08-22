package com.opentether.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opentether.diagnostics.AndroidDiagnostics
import com.opentether.ui.components.SectionCard

@Composable
fun DiagnosticsScreen(
    diagnostics: AndroidDiagnostics?,
    onRefresh: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard(
                title = "Android compatibility",
                subtitle = "Runtime information used to troubleshoot VPN lifecycle and OEM restrictions.",
            ) {
                if (diagnostics == null) {
                    Text("Diagnostics are loading…")
                } else {
                    DiagnosticRow("Status", diagnostics.compatibilityStatus)
                    DiagnosticRow("Detail", diagnostics.compatibilityDetail)
                    DiagnosticRow("Android", "${diagnostics.androidVersion} (API ${diagnostics.apiLevel})")
                    DiagnosticRow("Target API", diagnostics.targetApi.toString())
                    DiagnosticRow("Security patch", diagnostics.securityPatch)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onRefresh) {
                        Text("Refresh")
                    }
                    Button(onClick = onOpenAppSettings) {
                        Text("App settings")
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Device",
                subtitle = "Detected device information",
            ) {
                diagnostics?.let {
                    DiagnosticRow("Manufacturer", it.manufacturer)
                    DiagnosticRow("Model", it.model)
                    DiagnosticRow("Device", it.device)
                    DiagnosticRow("ABIs", it.supportedAbis)
                }
            }
        }

        item {
            SectionCard(
                title = "VPN service",
                subtitle = "Current Android service compatibility checks",
            ) {
                diagnostics?.let {
                    DiagnosticRow("Transport", it.transport.label)
                    DiagnosticRow("Foreground service", it.foregroundServiceStatus)
                    DiagnosticRow("Supported API range", "${it.minSupportedApi}–latest")
                }
            }
        }

        item {
            SectionCard(
                title = "Battery optimization",
                subtitle = "Battery restrictions can stop long-lived VPN services on some devices.",
            ) {
                diagnostics?.let {
                    DiagnosticRow("Status", it.batteryOptimizationStatus)
                    if (!it.batteryOptimizationIgnored) {
                        Text(
                            text = "If the VPN stops after screen lock, review battery/background restrictions for OpenTether.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(onClick = onOpenBatterySettings) {
                    Text("Open battery settings")
                }
            }
        }

        item {
            SectionCard(
                title = "Android troubleshooting",
                subtitle = "Device-specific checks for common Android failure modes.",
            ) {
                diagnostics?.troubleshooting?.forEach { item ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = item.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.38f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.62f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
