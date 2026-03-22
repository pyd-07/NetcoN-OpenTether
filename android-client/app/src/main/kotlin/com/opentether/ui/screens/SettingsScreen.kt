package com.opentether.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opentether.data.AppSettings
import com.opentether.ui.components.SectionCard

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onAutoStartChanged: (Boolean) -> Unit,
    onShowDebugLogsChanged: (Boolean) -> Unit,
    onTerminalEnabledChanged: (Boolean) -> Unit,
    onDnsServerChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dnsValue by rememberSaveable(settings.dnsServer) { mutableStateOf(settings.dnsServer) }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── DNS ───────────────────────────────────────────────────────────
        item {
            SectionCard(
                title = "DNS",
                subtitle = "Override the resolver used by the VPN interface. Enter an IP address.",
            ) {
                OutlinedTextField(
                    value = dnsValue,
                    onValueChange = {
                        dnsValue = it
                        onDnsServerChanged(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("DNS server") },
                    placeholder = { Text("8.8.8.8") },
                )
                Text(
                    text = "Applied the next time the VPN interface is created.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Behavior ─────────────────────────────────────────────────────
        item {
            SectionCard(
                title = "Behavior",
                subtitle = "Startup and debug preferences",
            ) {
                SettingRow(
                    title = "Auto-start on USB accessory",
                    subtitle = "Request VPN start automatically when the OS launches the app on cable attach.",
                    checked = settings.autoStartOnAccessory,
                    onCheckedChange = onAutoStartChanged,
                )
                SettingRow(
                    title = "Show debug logs",
                    subtitle = "Include raw transport logs alongside the activity timeline in Logs.",
                    checked = settings.showDebugLogs,
                    onCheckedChange = onShowDebugLogsChanged,
                )
                SettingRow(
                    title = "Enable terminal",
                    subtitle = "Show the in-app command runner on the Logs screen.",
                    checked = settings.terminalEnabled,
                    onCheckedChange = onTerminalEnabledChanged,
                )
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
