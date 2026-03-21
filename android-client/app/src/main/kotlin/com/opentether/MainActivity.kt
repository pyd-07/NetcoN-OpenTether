package com.opentether

import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.opentether.data.AppPreferences
import com.opentether.logging.AppLogger
import com.opentether.runtime.TunnelRuntimeHolder
import com.opentether.ui.OpenTetherApp
import com.opentether.ui.theme.OpenTetherTheme

private const val TAG = "OT/MainActivity"

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<OpenTetherViewModel>()

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            AppLogger.i(TAG, "VPN consent granted")
            viewModel.startVpnService()
        } else {
            viewModel.onVpnPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPreferences.initialize(applicationContext)
        handleLaunchIntent(intent)

        setContent {
            OpenTetherTheme {
                OpenTetherApp(
                    viewModel = viewModel,
                    onRequestStartVpnPermission = ::requestVpnPermission,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun requestVpnPermission() {
        val consentIntent = VpnService.prepare(this)
        if (consentIntent == null) {
            AppLogger.i(TAG, "VPN consent already available")
            viewModel.startVpnService()
        } else {
            vpnConsentLauncher.launch(consentIntent)
        }
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return

        AppLogger.i(TAG, "USB accessory attach intent received")
        val shouldAutoStart = AppPreferences.current(applicationContext).autoStartOnAccessory
        val alreadyRunning = TunnelRuntimeHolder.state.value.isRunning
        if (shouldAutoStart && !alreadyRunning) {
            requestVpnPermission()
        }
    }
}
