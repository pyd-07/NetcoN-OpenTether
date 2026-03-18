package com.opentether

import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.opentether.view.ThroughputChartView
import com.opentether.vpn.ACTION_START
import com.opentether.vpn.ACTION_STOP
import com.opentether.vpn.OpenTetherVpnService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "OT/MainActivity"

class MainActivity : AppCompatActivity() {

    // ── VPN consent ───────────────────────────────────────────────────────
    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.i(TAG, "consent granted")
            startVpnService()
        } else {
            Log.w(TAG, "consent denied")
            setIdleState("Permission denied — tap Start to try again")
        }
    }

    // ── View references ───────────────────────────────────────────────────
    private lateinit var dotStatus:          View
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvSessionTimer:     TextView
    private lateinit var statsContainer:     LinearLayout
    private lateinit var tvDownloadSpeed:    TextView
    private lateinit var tvUploadSpeed:      TextView
    private lateinit var chartView:          ThroughputChartView
    private lateinit var tvTotalDown:        TextView
    private lateinit var tvTotalUp:          TextView
    private lateinit var tvRtt:              TextView
    private lateinit var tvActiveConnCount:  TextView
    private lateinit var llConnections:      LinearLayout
    private lateinit var hintCard:           View
    private lateinit var btnStart:           MaterialButton
    private lateinit var btnStop:            MaterialButton

    // ── Session timer ─────────────────────────────────────────────────────
    private var sessionStartMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - sessionStartMs) / 1000L
            tvSessionTimer.text = "%02d:%02d:%02d".format(
                elapsed / 3600, elapsed % 3600 / 60, elapsed % 60
            )
            handler.postDelayed(this, 1_000L)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dotStatus          = findViewById(R.id.dotStatus)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvSessionTimer     = findViewById(R.id.tvSessionTimer)
        statsContainer     = findViewById(R.id.statsContainer)
        tvDownloadSpeed    = findViewById(R.id.tvDownloadSpeed)
        tvUploadSpeed      = findViewById(R.id.tvUploadSpeed)
        chartView          = findViewById(R.id.chartView)
        tvTotalDown        = findViewById(R.id.tvTotalDown)
        tvTotalUp          = findViewById(R.id.tvTotalUp)
        tvRtt              = findViewById(R.id.tvRtt)
        tvActiveConnCount  = findViewById(R.id.tvActiveConnCount)
        llConnections      = findViewById(R.id.llConnections)
        hintCard           = findViewById(R.id.hintCard)
        btnStart           = findViewById(R.id.btnStart)
        btnStop            = findViewById(R.id.btnStop)

        btnStart.setOnClickListener { requestVpnPermission() }
        btnStop.setOnClickListener  { stopVpnService() }

        // Observe live stats (emits VpnStats() when disconnected → UI stays blank)
        lifecycleScope.launch {
            StatsHolder.stats.collectLatest { stats -> applyStats(stats) }
        }

        if (StatsHolder.isRunning) setConnectedState() else setIdleState()
    }

    override fun onResume() {
        super.onResume()
        if (StatsHolder.isRunning && sessionStartMs == 0L) {
            setConnectedState()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(timerTick)
    }

    // ── VPN control ───────────────────────────────────────────────────────

    private fun requestVpnPermission() {
        val consentIntent = VpnService.prepare(this)
        if (consentIntent == null) startVpnService()
        else vpnConsentLauncher.launch(consentIntent)
    }

    private fun startVpnService() {
        startService(
            Intent(this, OpenTetherVpnService::class.java).apply { action = ACTION_START }
        )
        setConnectedState()
    }

    private fun stopVpnService() {
        startService(
            Intent(this, OpenTetherVpnService::class.java).apply { action = ACTION_STOP }
        )
        setIdleState("Disconnected")
        handler.removeCallbacks(timerTick)
        sessionStartMs = 0L
    }

    // ── UI state transitions ──────────────────────────────────────────────

    private fun setConnectedState() {
        if (sessionStartMs == 0L) {
            sessionStartMs = System.currentTimeMillis()
            handler.post(timerTick)
        }

        dotStatus.setBackgroundResource(R.drawable.bg_dot_status_connected)
        tvConnectionStatus.apply {
            text      = getString(R.string.status_connected)
            setTextColor(Color.parseColor("#1D9E75"))
        }
        tvSessionTimer.visibility = View.VISIBLE

        statsContainer.visibility = View.VISIBLE
        hintCard.visibility       = View.GONE
        btnStart.visibility       = View.GONE
        btnStop.visibility        = View.VISIBLE
    }

    private fun setIdleState(message: String = getString(R.string.status_idle)) {
        dotStatus.setBackgroundResource(R.drawable.bg_dot_status_idle)
        tvConnectionStatus.apply {
            text = message
            setTextColor(Color.parseColor("#888888"))
        }
        tvSessionTimer.visibility = View.GONE

        statsContainer.visibility = View.GONE
        hintCard.visibility       = View.VISIBLE
        btnStart.visibility       = View.VISIBLE
        btnStop.visibility        = View.GONE
    }

    // ── Stats rendering ───────────────────────────────────────────────────

    private fun applyStats(stats: VpnStats) {
        val dlMbps = stats.downloadBytesPerSec / 1_000_000f
        val ulMbps = stats.uploadBytesPerSec   / 1_000_000f

        tvDownloadSpeed.text = "%.1f".format(dlMbps)
        tvUploadSpeed.text   = "%.1f".format(ulMbps)

        chartView.addDataPoint(dlMbps, ulMbps)

        tvTotalDown.text = formatBytes(stats.totalDownloadBytes)
        tvTotalUp.text   = formatBytes(stats.totalUploadBytes)
        tvRtt.text       = if (stats.rttMs > 0) "${stats.rttMs} ms" else "— ms"

        tvActiveConnCount.text = stats.activeConnections.toString()
        rebuildConnectionRows(stats.connections)
    }

    private fun rebuildConnectionRows(connections: List<ConnectionEntry>) {
        llConnections.removeAllViews()
        val density = resources.displayMetrics.density

        connections.take(6).forEach { conn ->
            val row = LinearLayout(this).apply {
                orientation  = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.bottomMargin = (6 * density).toInt() }
            }

            row.addView(TextView(this).apply {
                text      = conn.host
                textSize  = 12f
                setTextColor(Color.parseColor("#888888"))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })

            row.addView(TextView(this).apply {
                text     = if (conn.active) "%.1f MB/s".format(conn.mbps) else "idle"
                textSize = 11f
                setTextColor(
                    if (conn.active) Color.parseColor("#1D9E75")
                    else             Color.parseColor("#555555")
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            })

            llConnections.addView(row)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000f)
        bytes >= 1_000_000L     -> "%.0f MB".format(bytes / 1_000_000f)
        bytes >= 1_000L         -> "%.0f KB".format(bytes / 1_000f)
        else                    -> "$bytes B"
    }
}