package com.opentether.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.opentether.logging.AppLogEntry
import com.opentether.logging.AppLogLevel
import com.opentether.ui.components.EmptyState
import com.opentether.ui.components.SectionCard
import com.opentether.ui.components.TerminalText
import com.opentether.ui.theme.OtBlue
import com.opentether.ui.theme.OtGreen
import com.opentether.ui.theme.OtRed
import com.opentether.ui.theme.OtSurfaceAlt
import com.opentether.ui.theme.OtYellow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(
    logs: List<AppLogEntry>,
    terminalEnabled: Boolean,
    showTechnicalDetails: Boolean,
    onClearLogs: () -> Unit,
    onSubmitCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var command by rememberSaveable { mutableStateOf("") }
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    // Build the timeline once per logs/settings change — no nested lazy needed
    val timeline = remember(logs, showTechnicalDetails) {
        logs.mapNotNull { it.toTimelineEntry(showTechnicalDetails) }
            .takeLast(200)
            .reversed()
    }

    // Single LazyColumn for the whole screen — no nested scroll surfaces.
    // spacedBy(6.dp) here replaces the Spacer() nodes that were interleaved
    // between items; those spacers add empty lazy nodes and extra recompositions.
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // ── Activity header ───────────────────────────────────────────────
        item(key = "activity_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "Activity",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Status updates and connection events",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClearLogs) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Clear logs",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── Empty state ───────────────────────────────────────────────────
        if (timeline.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = "No activity yet",
                    message = "Start the service or connect the cable to see what the app is doing.",
                )
            }
        } else {
            // ── Log entries — each entry is its own lazy item ─────────────
            items(timeline, key = { it.id }) { entry ->
                val color = when (entry.level) {
                    AppLogLevel.DEBUG -> OtBlue
                    AppLogLevel.INFO -> OtGreen
                    AppLogLevel.WARN -> OtYellow
                    AppLogLevel.ERROR -> OtRed
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(OtSurfaceAlt, MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    TerminalText(
                        text = "${formatter.format(Date(entry.timestampMs))}  ${entry.title}",
                        color = color,
                        bold = true,
                    )
                    Text(
                        text = entry.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (showTechnicalDetails && entry.technical != null) {
                        TerminalText(
                            text = entry.technical,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ── Terminal (optional) ───────────────────────────────────────────
        if (terminalEnabled) {
            item(key = "terminal") {
                SectionCard(
                    title = "Terminal",
                    subtitle = "Operator commands. Scope is intentionally limited.",
                ) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = {
                            Text(
                                text = "Command",
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                    )
                    Button(
                        onClick = {
                            onSubmitCommand(command)
                            command = ""
                        },
                        enabled = command.isNotBlank(),
                    ) { Text("Run") }
                }
            }
        }
    }
}

// ── Private timeline model ────────────────────────────────────────────────────

private data class TimelineEntry(
    val id: Long,
    val timestampMs: Long,
    val level: AppLogLevel,
    val title: String,
    val message: String,
    val technical: String? = null,
)

private fun AppLogEntry.toTimelineEntry(showTechnicalDetails: Boolean): TimelineEntry? {
    val (title, message) = when {
        tag == "OT/VpnService" && this.message.contains("START received") ->
            "Starting tunnel" to "Preparing the secure connection."
        tag == "OT/VpnService" && this.message.contains("TUN interface established") ->
            "Phone VPN ready" to "VPN interface active, waiting for USB transport."
        tag == "OT/VpnService" && this.message.contains("stopped") ->
            "Tunnel stopped" to "The connection has been closed."
        tag == "OT/AoaTunnelClient" && this.message.contains("started — waiting for USB accessory") ->
            "Waiting for USB accessory" to "Reconnect the cable after starting the relay in AOA mode."
        tag == "OT/AoaTunnelClient" && this.message.contains("accessory found") ->
            "USB accessory detected" to "The phone sees the workstation accessory."
        tag == "OT/AoaTunnelClient" && this.message.contains("openAccessory returned null") ->
            "Accessory permission needed" to "Accept the prompt on the phone or reconnect the cable."
        tag == "OT/AoaTunnelClient" && this.message.contains("USB accessory open") ->
            "USB link opening" to "Opening the direct USB channel."
        tag == "OT/UsbTunnelClient" && this.message.contains("connecting to relay") ->
            "Connecting over USB" to "Trying to reach the relay through the cable."
        tag == "OT/UsbTunnelClient" && this.message.contains("connected to relay") ->
            "USB connected" to "Traffic can now flow through the workstation."
        tag == "OT/UsbTunnelClient" && this.message.contains("disconnected") ->
            "Connection lost" to "Retrying automatically."
        tag == "OT/MainActivity" && this.message.contains("VPN consent denied") ->
            "Permission denied" to "Android did not allow the VPN to start."
        level == AppLogLevel.ERROR ->
            "Error" to this.message
        level == AppLogLevel.WARN ->
            "Warning" to this.message
        showTechnicalDetails && level == AppLogLevel.INFO ->
            "Info" to this.message
        else -> return null
    }

    return TimelineEntry(
        id = id,
        timestampMs = timestampMs,
        level = level,
        title = title,
        message = message,
        technical = if (showTechnicalDetails) "$tag  ${this.message}" else null,
    )
}
