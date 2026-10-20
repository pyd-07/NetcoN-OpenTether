package com.opentether

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opentether.data.AppPreferences
import com.opentether.data.AppSettings
import com.opentether.data.TunnelTransport
import com.opentether.diagnostics.AndroidDiagnostics
import com.opentether.diagnostics.AndroidDiagnosticsProvider
import com.opentether.logging.AppLogger
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

data class OpenTetherUiState(
    val runtime: TunnelRuntimeState = TunnelRuntimeState(),
    val settings: AppSettings = AppSettings(),
    val diagnostics: AndroidDiagnostics? = null,
    val appVersion: String = BuildConfig.VERSION_NAME,
)

class OpenTetherViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val diagnostics = MutableStateFlow<AndroidDiagnostics?>(null)

    val uiState: StateFlow<OpenTetherUiState> = combine(
        TunnelRuntimeHolder.state,
        AppPreferences.settings,
        diagnostics,
    ) { runtime, settings, androidDiagnostics ->
        OpenTetherUiState(
            runtime = runtime,
            settings = settings,
            diagnostics = androidDiagnostics,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = OpenTetherUiState(),
    )

    init {
        AppPreferences.initialize(app)
    }

    /**
     * Diagnostics are intentionally loaded on demand (Docs or connection
     * details) rather than during every app launch.
     */
    fun refreshDiagnostics() {
        val transport = AppPreferences.current(app).preferredTransport
        diagnostics.value = AndroidDiagnosticsProvider.collect(app, transport)
    }

    fun openBatteryOptimizationSettings() {
        app.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    fun openAppSettings() {
        app.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${app.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    fun onVpnPermissionRequested() {
        TunnelRuntimeHolder.onPermissionRequested(AppPreferences.current(app).preferredTransport)
        AppLogger.i("OT/MainActivity", "Waiting for VPN consent")
    }

    fun startVpnService() {
        val transport = AppPreferences.current(app).preferredTransport
        AppPreferences.setVpnRequested(app, true)
        TunnelRuntimeHolder.onServiceStarting(transport)
        ContextCompat.startForegroundService(
            app,
            Intent(app, OpenTetherVpnService::class.java).apply { action = ACTION_START },
        )
    }

    fun stopVpnService() {
        AppPreferences.setVpnRequested(app, false)
        TunnelRuntimeHolder.onServiceStopping()
        app.startService(
            Intent(app, OpenTetherVpnService::class.java).apply { action = ACTION_STOP },
        )
    }

    fun onVpnPermissionDenied() {
        AppPreferences.setVpnRequested(app, false)
        TunnelRuntimeHolder.onPermissionDenied()
        AppLogger.w("OT/MainActivity", "VPN consent denied")
    }

    fun updateTransport(transport: TunnelTransport) {
        if (TunnelRuntimeHolder.state.value.isRunning) return
        AppPreferences.updateTransport(app, transport)
        AppLogger.i("OT/Settings", "Preferred transport set to ${transport.label}")
        refreshDiagnostics()
    }

    fun updateAutoStartOnAccessory(enabled: Boolean) {
        AppPreferences.updateAutoStartOnAccessory(app, enabled)
        AppLogger.i("OT/Settings", "Auto-start on accessory ${if (enabled) "enabled" else "disabled"}")
    }

    fun updateDnsServer(dnsServer: String) {
        val value = dnsServer.trim().ifBlank { Constants.VPN_DNS_SERVER }
        AppPreferences.updateDnsServer(app, value)
        AppLogger.i("OT/Settings", "DNS server set to $value")
    }
}
