package com.opentether

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opentether.data.AppPreferences
import com.opentether.data.AppSettings
import com.opentether.data.TunnelTransport
import com.opentether.logging.AppLogEntry
import com.opentether.logging.AppLogLevel
import com.opentether.logging.AppLogger
import com.opentether.runtime.TunnelPhase
import com.opentether.runtime.TunnelRuntimeHolder
import com.opentether.runtime.TunnelRuntimeState
import com.opentether.vpn.ACTION_START
import com.opentether.vpn.ACTION_STOP
import com.opentether.vpn.OpenTetherVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ThroughputSample(
    val downloadBytesPerSec: Long,
    val uploadBytesPerSec: Long,
    val timestampMs: Long,
)

enum class NodeStatus {
    Active,
    Warning,
    Error,
    Idle,
}

data class NetworkNode(
    val id: String,
    val title: String,
    val address: String,
    val subtitle: String,
    val detail: String,
    val status: NodeStatus,
)

data class OpenTetherUiState(
    val stats: VpnStats = VpnStats(),
    val runtime: TunnelRuntimeState = TunnelRuntimeState(),
    val settings: AppSettings = AppSettings(),
    val throughputHistory: List<ThroughputSample> = emptyList(),
    val logs: List<AppLogEntry> = emptyList(),
    val nodes: List<NetworkNode> = emptyList(),
)

class OpenTetherViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext

    private val throughputHistory = MutableStateFlow<List<ThroughputSample>>(emptyList())

    val uiState: StateFlow<OpenTetherUiState> = combine(
        StatsHolder.stats,
        TunnelRuntimeHolder.state,
        AppPreferences.settings,
        AppLogger.logs,
        throughputHistory,
    ) { stats, runtime, settings, logs, history ->
        OpenTetherUiState(
            stats = stats,
            runtime = runtime,
            settings = settings,
            throughputHistory = history,
            logs = logs.filter { settings.showDebugLogs || it.level != AppLogLevel.DEBUG },
            nodes = buildNodes(stats, runtime, settings),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = OpenTetherUiState(),
    )

    init {
        AppPreferences.initialize(app)
        viewModelScope.launch {
            StatsHolder.stats.collect { stats ->
                throughputHistory.update { current ->
                    (current + ThroughputSample(
                        downloadBytesPerSec = stats.downloadBytesPerSec,
                        uploadBytesPerSec = stats.uploadBytesPerSec,
                        timestampMs = System.currentTimeMillis(),
                    )).takeLast(30)
                }
            }
        }
    }

    fun startVpnService() {
        val transport = AppPreferences.current(app).preferredTransport
        TunnelRuntimeHolder.onServiceStarting(transport)
        app.startService(Intent(app, OpenTetherVpnService::class.java).apply { action = ACTION_START })
    }

    fun stopVpnService() {
        TunnelRuntimeHolder.onServiceStopping()
        app.startService(Intent(app, OpenTetherVpnService::class.java).apply { action = ACTION_STOP })
    }

    fun onVpnPermissionDenied() {
        TunnelRuntimeHolder.onPermissionDenied()
        AppLogger.w("OT/MainActivity", "VPN consent denied")
    }

    fun updateTransport(transport: TunnelTransport) {
        AppPreferences.updateTransport(app, transport)
        AppLogger.i("OT/Settings", "Preferred transport set to ${transport.label}")
    }

    fun updateAutoStartOnAccessory(enabled: Boolean) {
        AppPreferences.updateAutoStartOnAccessory(app, enabled)
        AppLogger.i("OT/Settings", "Auto-start on accessory ${if (enabled) "enabled" else "disabled"}")
    }

    fun updateShowDebugLogs(enabled: Boolean) {
        AppPreferences.updateShowDebugLogs(app, enabled)
        AppLogger.i("OT/Settings", "Debug log visibility ${if (enabled) "enabled" else "disabled"}")
    }

    fun updateTerminalEnabled(enabled: Boolean) {
        AppPreferences.updateTerminalEnabled(app, enabled)
        AppLogger.i("OT/Settings", "Terminal ${if (enabled) "enabled" else "disabled"}")
    }

    fun updateDnsServer(dnsServer: String) {
        AppPreferences.updateDnsServer(app, dnsServer)
        AppLogger.i("OT/Settings", "DNS server set to ${dnsServer.trim().ifBlank { Constants.VPN_DNS_SERVER }}")
    }

    fun clearLogs() {
        AppLogger.clear()
        AppLogger.i("OT/Logs", "In-app logs cleared")
    }

    fun submitCommand(command: String) {
        val normalized = command.trim()
        if (normalized.isBlank()) return

        when (normalized.lowercase()) {
            "status" -> {
                val runtime = uiState.value.runtime
                AppLogger.i(
                    "OT/Terminal",
                    "status=${runtime.statusLabel.lowercase()} transport=${runtime.transport.name} detail=${runtime.detail}",
                )
            }
            "clear" -> clearLogs()
            "start" -> {
                if (VpnService.prepare(app) == null) {
                    startVpnService()
                } else {
                    AppLogger.w("OT/Terminal", "VPN consent is still required; use the main action button first")
                }
            }
            "stop" -> stopVpnService()
            "help" -> {
                AppLogger.i("OT/Terminal", "commands: status, start, stop, clear, help")
            }
            else -> {
                AppLogger.w("OT/Terminal", "unknown command: $normalized")
            }
        }
    }

    fun findNode(nodeId: String): NetworkNode? = uiState.value.nodes.firstOrNull { it.id == nodeId }

    private fun buildNodes(
        stats: VpnStats,
        runtime: TunnelRuntimeState,
        settings: AppSettings,
    ): List<NetworkNode> {
        val dnsServer = settings.dnsServer.ifBlank { Constants.VPN_DNS_SERVER }
        val relayAddress = when (runtime.transport) {
            TunnelTransport.ADB -> "${Constants.RELAY_HOST}:${Constants.RELAY_PORT}"
            TunnelTransport.AOA -> "USB accessory"
        }

        val runtimeStatus = when (runtime.phase) {
            TunnelPhase.Connected -> NodeStatus.Active
            TunnelPhase.Error -> NodeStatus.Error
            TunnelPhase.Starting, TunnelPhase.Connecting, TunnelPhase.AwaitingTransport, TunnelPhase.Stopping -> NodeStatus.Warning
            TunnelPhase.Idle -> NodeStatus.Idle
        }

        return buildList {
            add(
                NetworkNode(
                    id = "device",
                    title = "Android Device",
                    address = Constants.VPN_CLIENT_IP,
                    subtitle = if (runtime.isRunning) "VPN interface active" else "VPN offline",
                    detail = "Client TUN address ${Constants.VPN_CLIENT_IP}/${Constants.VPN_PREFIX}",
                    status = if (runtime.isRunning) NodeStatus.Active else NodeStatus.Idle,
                ),
            )
            add(
                NetworkNode(
                    id = "relay",
                    title = runtime.transport.label,
                    address = relayAddress,
                    subtitle = runtime.statusLabel,
                    detail = "Preferred ${settings.preferredTransport.label} • ${runtime.detail}",
                    status = runtimeStatus,
                ),
            )
            add(
                NetworkNode(
                    id = "dns",
                    title = "DNS Resolver",
                    address = dnsServer,
                    subtitle = "Configured by VPN",
                    detail = "All device DNS traffic is routed through the tunnel when active",
                    status = if (runtime.isRunning) NodeStatus.Active else NodeStatus.Idle,
                ),
            )

            stats.connections.take(12).forEach { connection ->
                add(
                    NetworkNode(
                        id = "peer-${connection.host.sanitizeNodeId()}",
                        title = connection.host,
                        address = connection.host,
                        subtitle = if (connection.active) "Observed peer" else "Recent peer",
                        detail = "↓ ${formatRate(connection.downloadBytesPerSec)}  ↑ ${formatRate(connection.uploadBytesPerSec)}",
                        status = if (connection.active) NodeStatus.Active else NodeStatus.Idle,
                    ),
                )
            }
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000f)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000f)
    bytes >= 1_000L -> "%.1f KB".format(bytes / 1_000f)
    else -> "$bytes B"
}

fun formatRate(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"

fun formatDuration(durationMs: Long?): String {
    val safeDuration = durationMs ?: return "00:00:00"
    val totalSeconds = (safeDuration / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

private fun String.sanitizeNodeId(): String = buildString(length) {
    for (char in this@sanitizeNodeId) {
        append(if (char.isLetterOrDigit()) char else '_')
    }
}
