package com.opentether.runtime

import com.opentether.Constants
import com.opentether.data.TunnelTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TunnelPhase {
    Idle,
    Starting,
    AwaitingTransport,
    Connecting,
    Connected,
    Stopping,
    Error,
}

data class TunnelRuntimeState(
    val phase: TunnelPhase = TunnelPhase.Idle,
    val transport: TunnelTransport = TunnelTransport.ADB,
    val isRunning: Boolean = false,
    val statusLabel: String = "Idle",
    val detail: String = "VPN offline",
    val relayEndpoint: String = "${Constants.RELAY_HOST}:${Constants.RELAY_PORT}",
    val sessionStartedAtMs: Long? = null,
    val lastError: String? = null,
)

object TunnelRuntimeHolder {
    private val _state = MutableStateFlow(TunnelRuntimeState())
    val state: StateFlow<TunnelRuntimeState> = _state.asStateFlow()

    fun onPermissionDenied() {
        _state.value = _state.value.copy(
            phase = TunnelPhase.Idle,
            isRunning = false,
            statusLabel = "Permission denied",
            detail = "VPN consent was not granted",
            sessionStartedAtMs = null,
        )
    }

    fun onServiceStarting(transport: TunnelTransport) {
        _state.value = TunnelRuntimeState(
            phase = TunnelPhase.Starting,
            transport = transport,
            isRunning = true,
            statusLabel = "Starting",
            detail = "Preparing ${transport.label}",
            relayEndpoint = relayEndpointFor(transport),
        )
    }

    fun onTunEstablished() {
        _state.value = _state.value.copy(
            phase = TunnelPhase.Connecting,
            isRunning = true,
            statusLabel = "Interface ready",
            detail = "TUN established, negotiating transport",
        )
    }

    fun onTransportWaiting(transport: TunnelTransport, detail: String) {
        _state.value = _state.value.copy(
            phase = TunnelPhase.AwaitingTransport,
            transport = transport,
            isRunning = true,
            statusLabel = if (transport == TunnelTransport.AOA) "Waiting for USB" else "Waiting",
            detail = detail,
            relayEndpoint = relayEndpointFor(transport),
        )
    }

    fun onTransportConnecting(transport: TunnelTransport, detail: String) {
        _state.value = _state.value.copy(
            phase = TunnelPhase.Connecting,
            transport = transport,
            isRunning = true,
            statusLabel = "Connecting",
            detail = detail,
            relayEndpoint = relayEndpointFor(transport),
            lastError = null,
        )
    }

    fun onTransportConnected(transport: TunnelTransport, detail: String) {
        val startedAt = _state.value.sessionStartedAtMs ?: System.currentTimeMillis()
        _state.value = _state.value.copy(
            phase = TunnelPhase.Connected,
            transport = transport,
            isRunning = true,
            statusLabel = "Connected",
            detail = detail,
            relayEndpoint = relayEndpointFor(transport),
            sessionStartedAtMs = startedAt,
            lastError = null,
        )
    }

    fun onTransportDisconnected(transport: TunnelTransport, detail: String) {
        _state.value = _state.value.copy(
            phase = TunnelPhase.AwaitingTransport,
            transport = transport,
            isRunning = true,
            statusLabel = "Reconnecting",
            detail = detail,
            relayEndpoint = relayEndpointFor(transport),
        )
    }

    fun onError(transport: TunnelTransport, detail: String) {
        _state.value = _state.value.copy(
            phase = TunnelPhase.Error,
            transport = transport,
            statusLabel = "Error",
            detail = detail,
            relayEndpoint = relayEndpointFor(transport),
            lastError = detail,
        )
    }

    fun onServiceStopping() {
        _state.value = _state.value.copy(
            phase = TunnelPhase.Stopping,
            statusLabel = "Stopping",
            detail = "Closing tunnel and foreground service",
        )
    }

    fun onStopped() {
        _state.value = _state.value.copy(
            phase = TunnelPhase.Idle,
            isRunning = false,
            statusLabel = "Idle",
            detail = "VPN offline",
            sessionStartedAtMs = null,
        )
    }

    private fun relayEndpointFor(transport: TunnelTransport): String = when (transport) {
        TunnelTransport.ADB -> "${Constants.RELAY_HOST}:${Constants.RELAY_PORT}"
        TunnelTransport.AOA -> "USB accessory"
    }
}
