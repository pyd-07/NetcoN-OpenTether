package com.opentether

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.opentether.vpn.ACTION_START
import com.opentether.vpn.ACTION_STOP
import com.opentether.vpn.OpenTetherVpnService

private const val TAG = "OT/MainActivity"

class MainActivity : AppCompatActivity() {

    /**
     * VPN consent flow:
     *
     *  1. VpnService.prepare() returns null  → already consented, start immediately.
     *  2. VpnService.prepare() returns Intent → launch it; system shows the
     *     "OpenTether wants to create a VPN" dialog.
     *  3. User taps OK  → result RESULT_OK  → start the service.
     *  4. User taps Cancel → result RESULT_CANCELED → show message, do nothing.
     */
    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.i(TAG, "consent granted")
            startVpnService()
        } else {
            Log.w(TAG, "consent denied")
            setStatus("Permission denied — tap Start to try again")
            btnStart.isEnabled = true
            btnStop.isEnabled  = false
        }
    }

    private lateinit var btnStart: Button
    private lateinit var btnStop:  Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnStop  = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)

        btnStart.setOnClickListener { requestVpnPermission() }
        btnStop.setOnClickListener  { stopVpnService() }

        setStatus("Idle")
    }

    private fun requestVpnPermission() {
        btnStart.isEnabled = false
        setStatus("Requesting VPN permission...")

        val consentIntent = VpnService.prepare(this)
        if (consentIntent == null) {
            // Permission already granted in a previous session
            Log.i(TAG, "already have consent, starting directly")
            startVpnService()
        } else {
            // Show system dialog — result handled in vpnConsentLauncher above
            vpnConsentLauncher.launch(consentIntent)
        }
    }

    private fun startVpnService() {
        setStatus("Connecting...")
        btnStart.isEnabled = false
        btnStop.isEnabled  = true
        startService(
            Intent(this, OpenTetherVpnService::class.java).apply { action = ACTION_START }
        )
    }

    private fun stopVpnService() {
        setStatus("Stopping...")
        btnStart.isEnabled = true
        btnStop.isEnabled  = false
        startService(
            Intent(this, OpenTetherVpnService::class.java).apply { action = ACTION_STOP }
        )
    }

    private fun setStatus(msg: String) {
        tvStatus.text = msg
        Log.i(TAG, msg)
    }
}
