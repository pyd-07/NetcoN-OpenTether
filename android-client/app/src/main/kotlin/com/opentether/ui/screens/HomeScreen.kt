package com.opentether.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opentether.runtime.TunnelPhase
import com.opentether.runtime.TunnelRuntimeState
import com.opentether.ui.ConnectionTone
import com.opentether.ui.PrimaryAction
import com.opentether.ui.connectionPresentation
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    runtime: TunnelRuntimeState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = remember(runtime) { connectionPresentation(runtime) }
    val accent = presentation.tone.color()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .fillMaxHeight(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                Text(
                    text = "OpenTether",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "USB tethering",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(44.dp))

                StatusGlyph(tone = presentation.tone, color = accent)

                Spacer(Modifier.height(24.dp))

                Text(
                    text = presentation.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = presentation.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (presentation.showDuration && runtime.phase == TunnelPhase.Connected) {
                    runtime.sessionStartedAtMs?.let { startedAt ->
                        Spacer(Modifier.height(12.dp))
                        ConnectionDuration(startedAtMs = startedAt)
                    }
                }

                Spacer(Modifier.height(32.dp))

                Button(
                    onClick = {
                        when (presentation.action) {
                            PrimaryAction.Connect -> onConnect()
                            PrimaryAction.Disconnect -> onDisconnect()
                            PrimaryAction.None -> Unit
                        }
                    },
                    enabled = presentation.action != PrimaryAction.None,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(
                        text = presentation.actionLabel,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                if (presentation.action == PrimaryAction.Connect) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Android will ask for VPN permission before the first connection. " +
                            "OpenTether uses it to route traffic through your computer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (presentation.showDetails) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onOpenDetails,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text("View details")
                    }
                }

                Spacer(Modifier.height(24.dp))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Connection settings")
                }
            }
        }
    }
}

@Composable
private fun StatusGlyph(tone: ConnectionTone, color: Color) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        if (tone == ConnectionTone.Progress) {
            CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                color = color,
                strokeWidth = 2.5.dp,
            )
        } else {
            Icon(
                imageVector = when (tone) {
                    ConnectionTone.Connected -> Icons.Default.CheckCircle
                    ConnectionTone.Warning, ConnectionTone.Error -> Icons.Default.Warning
                    else -> Icons.Default.Info
                },
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

@Composable
private fun ConnectionTone.color(): Color {
    return when (this) {
        ConnectionTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionTone.Progress -> MaterialTheme.colorScheme.primary
        ConnectionTone.Connected -> MaterialTheme.colorScheme.primary
        ConnectionTone.Warning -> MaterialTheme.colorScheme.tertiary
        ConnectionTone.Error -> MaterialTheme.colorScheme.error
    }
}

@Composable
private fun ConnectionDuration(startedAtMs: Long) {
    var nowMs by remember(startedAtMs) { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(startedAtMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    Text(
        text = "Connected for ${formatElapsed(nowMs - startedAtMs)}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatElapsed(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "%dh %02dm".format(hours, minutes)
        minutes > 0L -> "%dm %02ds".format(minutes, seconds)
        else -> "${seconds}s"
    }
}
