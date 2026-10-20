package com.opentether.ui

import com.opentether.data.TunnelTransport
import com.opentether.diagnostics.AndroidDiagnostics
import com.opentether.runtime.TunnelPhase
import com.opentether.runtime.TunnelRuntimeState

enum class ConnectionTone {
    Neutral,
    Progress,
    Connected,
    Warning,
    Error,
}

enum class PrimaryAction {
    Connect,
    Disconnect,
    None,
}

data class ConnectionPresentation(
    val phase: TunnelPhase,
    val title: String,
    val description: String,
    val tone: ConnectionTone,
    val action: PrimaryAction,
    val actionLabel: String,
    val isBusy: Boolean,
    val showDetails: Boolean,
    val showDuration: Boolean,
)

/**
 * Turns the real tunnel lifecycle into plain-language UI copy. Raw status
 * labels and exception text stay out of the main screen and are only shown in
 * the technical details dialog.
 */
fun connectionPresentation(state: TunnelRuntimeState): ConnectionPresentation {
    return when (state.phase) {
        TunnelPhase.Idle -> ConnectionPresentation(
            phase = state.phase,
            title = "Not connected",
            description = "Connect to send this phone's internet through your computer over USB.",
            tone = ConnectionTone.Neutral,
            action = PrimaryAction.Connect,
            actionLabel = "Connect",
            isBusy = false,
            showDetails = false,
            showDuration = false,
        )

        TunnelPhase.AwaitingPermission -> ConnectionPresentation(
            phase = state.phase,
            title = "Waiting for permission",
            description = "Android is asking whether OpenTether can create a VPN connection. Allow it to continue.",
            tone = ConnectionTone.Progress,
            action = PrimaryAction.None,
            actionLabel = "Waiting for permission",
            isBusy = true,
            showDetails = false,
            showDuration = false,
        )

        TunnelPhase.PermissionDenied -> ConnectionPresentation(
            phase = state.phase,
            title = "VPN permission needed",
            description = "OpenTether needs permission to create the VPN connection. Tap Try again and allow Android's prompt.",
            tone = ConnectionTone.Warning,
            action = PrimaryAction.Connect,
            actionLabel = "Try again",
            isBusy = false,
            showDetails = false,
            showDuration = false,
        )

        TunnelPhase.Starting -> ConnectionPresentation(
            phase = state.phase,
            title = "Connecting…",
            description = "Preparing the VPN connection.",
            tone = ConnectionTone.Progress,
            action = PrimaryAction.Disconnect,
            actionLabel = "Cancel",
            isBusy = true,
            showDetails = false,
            showDuration = false,
        )

        TunnelPhase.AwaitingTransport -> ConnectionPresentation(
            phase = state.phase,
            title = "Waiting for your computer",
            description = when (state.transport) {
                TunnelTransport.ADB -> "Make sure the relay is running and USB debugging is enabled."
                TunnelTransport.AOA -> "Connect the USB cable and accept the USB accessory prompt."
            },
            tone = ConnectionTone.Progress,
            action = PrimaryAction.Disconnect,
            actionLabel = "Cancel",
            isBusy = true,
            showDetails = false,
            showDuration = false,
        )

        TunnelPhase.Connecting -> ConnectionPresentation(
            phase = state.phase,
            title = "Connecting…",
            description = "Reaching the relay over USB.",
            tone = ConnectionTone.Progress,
            action = PrimaryAction.Disconnect,
            actionLabel = "Cancel",
            isBusy = true,
            showDetails = false,
            showDuration = false,
        )

        TunnelPhase.Connected -> ConnectionPresentation(
            phase = state.phase,
            title = "Connected",
            description = "Your phone's traffic is going through your computer.",
            tone = ConnectionTone.Connected,
            action = PrimaryAction.Disconnect,
            actionLabel = "Disconnect",
            isBusy = false,
            showDetails = false,
            showDuration = true,
        )

        TunnelPhase.Stopping -> ConnectionPresentation(
            phase = state.phase,
            title = "Disconnecting…",
            description = "Closing the VPN connection.",
            tone = ConnectionTone.Progress,
            action = PrimaryAction.None,
            actionLabel = "Disconnecting…",
            isBusy = true,
            showDetails = false,
            showDuration = false,
        )

        TunnelPhase.Error -> {
            if (state.isRunning) {
                ConnectionPresentation(
                    phase = state.phase,
                    title = "Connection needs attention",
                    description = "The relay reported a problem. OpenTether is still running and may retry.",
                    tone = ConnectionTone.Warning,
                    action = PrimaryAction.Disconnect,
                    actionLabel = "Disconnect",
                    isBusy = false,
                    showDetails = true,
                    showDuration = false,
                )
            } else {
                ConnectionPresentation(
                    phase = state.phase,
                    title = "Couldn't connect",
                    description = when (state.transport) {
                        TunnelTransport.ADB ->
                            "Check that the phone is connected to the computer and the relay is running, then try again."
                        TunnelTransport.AOA ->
                            "Check the USB cable and accept the accessory prompt, then try again."
                    },
                    tone = ConnectionTone.Error,
                    action = PrimaryAction.Connect,
                    actionLabel = "Try again",
                    isBusy = false,
                    showDetails = true,
                    showDuration = false,
                )
            }
        }
    }
}

/**
 * Sanitized technical summary for the details dialog. It intentionally excludes
 * packet payloads, credentials, logs, and device identifiers.
 */
fun buildDiagnosticSummary(
    runtime: TunnelRuntimeState,
    diagnostics: AndroidDiagnostics?,
    appVersion: String,
): String = buildString {
    appendLine("OpenTether $appVersion")
    appendLine("State: ${runtime.phase.name}")
    appendLine("Transport: ${runtime.transport.label}")
    appendLine("Relay: ${runtime.relayEndpoint}")
    appendLine("VPN running: ${runtime.isRunning}")
    runtime.lastError?.let { appendLine("Last error: ${sanitizeDetail(it)}") }
    diagnostics?.let {
        appendLine("Android: ${it.androidVersion} (API ${it.apiLevel})")
        appendLine("Device: ${it.manufacturer} ${it.model}")
        appendLine("Battery optimization: ${it.batteryOptimizationStatus}")
        appendLine("Compatibility: ${it.compatibilityStatus}")
    }
}.trim()

private fun sanitizeDetail(value: String): String {
    return value
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(300)
        .ifBlank { "Unknown" }
}
