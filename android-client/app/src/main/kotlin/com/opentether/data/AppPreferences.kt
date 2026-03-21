package com.opentether.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TunnelTransport(
    val label: String,
    val description: String,
) {
    ADB(
        label = "ADB Reverse",
        description = "TCP tunnel over adb reverse to the relay on your workstation",
    ),
    AOA(
        label = "USB Accessory",
        description = "Direct Android Open Accessory link with no USB debugging required",
    ),
    ;

    companion object {
        fun fromStored(value: String?): TunnelTransport = entries.firstOrNull { it.name == value } ?: ADB
    }
}

data class AppSettings(
    val preferredTransport: TunnelTransport = TunnelTransport.ADB,
    val autoStartOnAccessory: Boolean = false,
    val showDebugLogs: Boolean = false,
    val terminalEnabled: Boolean = true,
    val dnsServer: String = "8.8.8.8",
)

object AppPreferences {
    private const val PREFS_NAME = "opentether_settings"
    private const val KEY_TRANSPORT = "transport"
    private const val KEY_AUTO_START = "auto_start_on_accessory"
    private const val KEY_DEBUG_LOGS = "show_debug_logs"
    private const val KEY_TERMINAL = "terminal_enabled"
    private const val KEY_DNS_SERVER = "dns_server"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        val sharedPrefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sharedPrefs
        _settings.value = readSettings(sharedPrefs)
    }

    fun current(context: Context): AppSettings {
        initialize(context)
        return _settings.value
    }

    fun updateTransport(context: Context, transport: TunnelTransport) {
        val sharedPrefs = requirePrefs(context)
        sharedPrefs.edit().putString(KEY_TRANSPORT, transport.name).apply()
        publish(sharedPrefs)
    }

    fun updateAutoStartOnAccessory(context: Context, enabled: Boolean) {
        val sharedPrefs = requirePrefs(context)
        sharedPrefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
        publish(sharedPrefs)
    }

    fun updateShowDebugLogs(context: Context, enabled: Boolean) {
        val sharedPrefs = requirePrefs(context)
        sharedPrefs.edit().putBoolean(KEY_DEBUG_LOGS, enabled).apply()
        publish(sharedPrefs)
    }

    fun updateTerminalEnabled(context: Context, enabled: Boolean) {
        val sharedPrefs = requirePrefs(context)
        sharedPrefs.edit().putBoolean(KEY_TERMINAL, enabled).apply()
        publish(sharedPrefs)
    }

    fun updateDnsServer(context: Context, dnsServer: String) {
        val sharedPrefs = requirePrefs(context)
        sharedPrefs.edit().putString(KEY_DNS_SERVER, dnsServer.trim()).apply()
        publish(sharedPrefs)
    }

    private fun requirePrefs(context: Context): SharedPreferences {
        initialize(context)
        return checkNotNull(prefs)
    }

    private fun publish(sharedPrefs: SharedPreferences) {
        _settings.value = readSettings(sharedPrefs)
    }

    private fun readSettings(sharedPrefs: SharedPreferences): AppSettings {
        return AppSettings(
            preferredTransport = TunnelTransport.fromStored(sharedPrefs.getString(KEY_TRANSPORT, TunnelTransport.ADB.name)),
            autoStartOnAccessory = sharedPrefs.getBoolean(KEY_AUTO_START, false),
            showDebugLogs = sharedPrefs.getBoolean(KEY_DEBUG_LOGS, false),
            terminalEnabled = sharedPrefs.getBoolean(KEY_TERMINAL, true),
            dnsServer = sharedPrefs.getString(KEY_DNS_SERVER, "8.8.8.8").orEmpty().ifBlank { "8.8.8.8" },
        )
    }
}
