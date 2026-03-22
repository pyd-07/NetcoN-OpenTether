package com.opentether.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opentether.OpenTetherUiState
import com.opentether.data.TunnelTransport
import com.opentether.formatBytes
import com.opentether.formatDuration
import com.opentether.ui.components.SectionCard
import com.opentether.ui.components.StatusPill
import com.opentether.ui.theme.OtYellow

@Composable
fun TunnelsScreen(
    uiState: OpenTetherUiState,
    onTransportSelected: (TunnelTransport) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uptimeMs = uiState.runtime.sessionStartedAtMs?.let { System.currentTimeMillis() - it }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Session state (read-only — use top bar or Dashboard to start/stop) ──
        item {
            SectionCard(
                title = "Session",
                subtitle = "Transport, endpoint, and session statistics",
            ) {
                StatusPill(
                    text = uiState.runtime.statusLabel,
                    phase = uiState.runtime.phase,
                )
                Text(
                    text = uiState.runtime.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Transport: ${uiState.runtime.transport.label}  ·  Relay: ${uiState.runtime.relayEndpoint}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (uptimeMs != null && uptimeMs > 0) {
                    Text(
                        text = "Up ${formatDuration(uptimeMs)}  ·  ↓ ${formatBytes(uiState.stats.totalDownloadBytes)}  ↑ ${formatBytes(uiState.stats.totalUploadBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── Transport selection ───────────────────────────────────────────
        item {
            SectionCard(
                title = "Transport",
                subtitle = "USB mode used the next time the tunnel starts",
            ) {
                TunnelTransport.entries.forEach { transport ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 64.dp)
                            .selectable(
                                selected = uiState.settings.preferredTransport == transport,
                                onClick = { onTransportSelected(transport) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // onClick = null — the selectable Row handles the click.
                        // Giving RadioButton its own onClick fires the callback twice.
                        RadioButton(
                            selected = uiState.settings.preferredTransport == transport,
                            onClick = null,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = transport.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = transport.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Inform when the active transport differs from the preference
                if (uiState.runtime.isRunning &&
                    uiState.settings.preferredTransport != uiState.runtime.transport
                ) {
                    Text(
                        text = "Running on ${uiState.runtime.transport.label}. New preference applies after restart.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OtYellow,
                    )
                }
            }
        }

        // ── AOA guidance — only emitted when AOA is the selected transport ──
        // NOTE: the conditional wraps the item{} call, not the content inside it.
        //       item { if (…) { … } } always emits an empty lazy node for the
        //       non-AOA case; if (…) { item { … } } skips it entirely.
        if (uiState.settings.preferredTransport == TunnelTransport.AOA) {
            item {
                SectionCard(
                    title = "USB Accessory setup",
                    subtitle = "Extra steps required for AOA mode",
                ) {
                    listOf(
                        "Start the relay on the workstation with AOA support enabled.",
                        "Reconnect the USB cable so Android can display the accessory permission prompt.",
                        "Accept the prompt, then tap Start from the Dashboard or the top bar.",
                    ).forEachIndexed { i, step ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "${i + 1}.",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = step,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}
