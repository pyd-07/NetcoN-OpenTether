package com.opentether.diagnostics

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.opentether.data.TunnelTransport

/** Snapshot of Android/device capabilities relevant to a long-lived VPN session. */
data class AndroidDiagnostics(
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidVersion: String,
    val apiLevel: Int,
    val securityPatch: String,
    val supportedAbis: String,
    val minSupportedApi: Int,
    val targetApi: Int,
    val batteryOptimizationIgnored: Boolean,
    val batteryOptimizationStatus: String,
    val transport: TunnelTransport,
    val foregroundServiceStatus: String,
    val compatibilityStatus: String,
    val compatibilityDetail: String,
    val troubleshooting: List<AndroidTroubleshootingItem>,
)

data class AndroidTroubleshootingItem(
    val title: String,
    val detail: String,
)

object AndroidDiagnosticsProvider {
    fun collect(context: Context, transport: TunnelTransport): AndroidDiagnostics {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val batteryOptimizationIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else {
            true
        }

        val apiLevel = Build.VERSION.SDK_INT
        val manufacturer = Build.MANUFACTURER.trim().ifBlank { "Unknown" }
        val model = Build.MODEL.trim().ifBlank { "Unknown" }
        val normalizedManufacturer = manufacturer.lowercase()
        val compatibility = compatibilityFor(apiLevel)

        return AndroidDiagnostics(
            manufacturer = manufacturer,
            model = model,
            device = Build.DEVICE.ifBlank { "Unknown" },
            androidVersion = Build.VERSION.RELEASE ?: "Unknown",
            apiLevel = apiLevel,
            securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Build.VERSION.SECURITY_PATCH
            } else {
                "Not available"
            },
            supportedAbis = Build.SUPPORTED_ABIS.joinToString(", ").ifBlank { "Unknown" },
            minSupportedApi = 26,
            targetApi = 34,
            batteryOptimizationIgnored = batteryOptimizationIgnored,
            batteryOptimizationStatus = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M -> "Not applicable on Android < 6"
                batteryOptimizationIgnored -> "Excluded from battery optimization"
                else -> "Battery optimization is enabled"
            },
            transport = transport,
            foregroundServiceStatus = if (apiLevel >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                "Android 14+ foreground-service type handling enabled"
            } else if (apiLevel >= Build.VERSION_CODES.Q) {
                "Foreground-service type API available"
            } else {
                "Legacy foreground-service API"
            },
            compatibilityStatus = compatibility.first,
            compatibilityDetail = compatibility.second,
            troubleshooting = troubleshootingFor(
                normalizedManufacturer,
                apiLevel,
                batteryOptimizationIgnored,
                transport,
            ),
        )
    }

    private fun compatibilityFor(apiLevel: Int): Pair<String, String> = when {
        apiLevel < 26 -> "Unsupported" to "OpenTether requires Android 8.0 (API 26) or newer."
        apiLevel < 29 -> "Supported" to "Android 8–9 uses the legacy foreground-service path."
        apiLevel < 31 -> "Supported" to "Android 10–11 supports typed foreground services and VPN lifecycle handling."
        apiLevel < 34 -> "Supported" to "Android 12–13 applies background-start restrictions; VPN startup must originate from a user-visible flow."
        else -> "Supported" to "Android 14+ requires explicit foreground-service type handling; OpenTether declares and starts its special-use VPN service accordingly."
    }

    private fun troubleshootingFor(
        manufacturer: String,
        apiLevel: Int,
        batteryOptimizationIgnored: Boolean,
        transport: TunnelTransport,
    ): List<AndroidTroubleshootingItem> = buildList {
        if (!batteryOptimizationIgnored && apiLevel >= Build.VERSION_CODES.M) {
            add(
                AndroidTroubleshootingItem(
                    "Battery optimization",
                    "If the VPN stops after the screen is locked, exclude OpenTether from battery optimization and check the OEM's background-app settings.",
                ),
            )
        }

        if (apiLevel >= Build.VERSION_CODES.S) {
            add(
                AndroidTroubleshootingItem(
                    "Foreground service",
                    "Start VPN from the visible OpenTether UI. Android 12+ restricts starting foreground services from the background.",
                ),
            )
        }

        if (apiLevel >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(
                AndroidTroubleshootingItem(
                    "Android 14+",
                    "Keep the persistent VPN notification enabled. The VPN service declares a foreground-service type and promotes itself immediately.",
                ),
            )
        }

        if (transport == TunnelTransport.ADB) {
            add(
                AndroidTroubleshootingItem(
                    "ADB transport",
                    "USB debugging must remain enabled and the workstation must have an authorized ADB connection.",
                ),
            )
        } else {
            add(
                AndroidTroubleshootingItem(
                    "AOA transport",
                    "Accept the USB accessory permission prompt when Android asks which application should handle the accessory.",
                ),
            )
        }

        when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> add(
                AndroidTroubleshootingItem(
                    "Xiaomi / Redmi / POCO",
                    "Check Settings → Apps → OpenTether → Battery and allow unrestricted/background activity if the service is being stopped.",
                ),
            )
            manufacturer.contains("oneplus") || manufacturer.contains("oppo") || manufacturer.contains("realme") -> add(
                AndroidTroubleshootingItem(
                    "OnePlus / OPPO / realme",
                    "Check battery, auto-launch, and background-activity controls for OpenTether; OEM task management can stop long-lived services.",
                ),
            )
            manufacturer.contains("samsung") -> add(
                AndroidTroubleshootingItem(
                    "Samsung",
                    "Check battery/background usage settings and make sure OpenTether is not placed into a sleeping/deep-sleeping apps list.",
                ),
            )
            manufacturer.contains("vivo") -> add(
                AndroidTroubleshootingItem(
                    "vivo",
                    "Check battery/background activity and autostart permissions for OpenTether if screen-lock stops the tunnel.",
                ),
            )
            manufacturer.contains("motorola") -> add(
                AndroidTroubleshootingItem(
                    "Motorola",
                    "Check battery optimization and background restrictions if the VPN is terminated while the display is off.",
                ),
            )
        }

        add(
            AndroidTroubleshootingItem(
                "Screen lock test",
                "Start the VPN, lock the screen for several minutes, unlock it, and verify the service and USB transport remain connected.",
            ),
        )
    }
}
