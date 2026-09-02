package org.amnezia.awg.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.Application
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.R
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.model.ConnectionHealthStore
import org.amnezia.awg.model.ObservableTunnel
import org.amnezia.awg.warp.WarpProvisioner

/** A deliberately redacted operational report: no private keys, tokens, IDs, or tunnel addresses. */
class WarpDiagnosticsActivity : BaseActivity() {
    private lateinit var reportView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.warp_diagnostics_activity)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        reportView = findViewById(R.id.warp_diagnostics_report)
        findViewById<MaterialButton>(R.id.warp_diagnostics_refresh).setOnClickListener { refresh() }
        findViewById<MaterialButton>(R.id.warp_diagnostics_copy).setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.warp_diagnostics_title), reportView.text))
            Toast.makeText(this, R.string.warp_diagnostics_copied, Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    private fun refresh() {
        reportView.setText(R.string.warp_diagnostics_loading)
        lifecycleScope.launch {
            reportView.text = runCatching { createRedactedReport() }
                .getOrElse { error -> getString(R.string.warp_diagnostics_error, error.javaClass.simpleName) }
        }
    }

    private suspend fun createRedactedReport(): String {
        val manager = Application.getTunnelManager()
        val tunnels = manager.getTunnels()
        val activeTunnel = tunnels.firstOrNull { it.state == Tunnel.State.UP }
        val inspectedTunnel = activeTunnel ?: manager.lastUsedTunnel
        val backend = Application.getBackend()
        val backendVersion = withContext(Dispatchers.IO) {
            runCatching { backend.version }.getOrDefault(getString(R.string.unknown_error))
        }
        val config = inspectedTunnel?.let { runCatching { it.getConfigAsync() }.getOrNull() }
        val peer = config?.peers?.firstOrNull()
        val statistics = activeTunnel?.let { tunnel ->
            withContext(Dispatchers.IO) { runCatching { backend.getStatistics(tunnel) }.getOrNull() }
        }
        val handshake = activeTunnel?.let { tunnel ->
            withContext(Dispatchers.IO) { runCatching { backend.getLastHandshake(tunnel) }.getOrDefault(0L) }
        } ?: 0L
        val account = withContext(Dispatchers.IO) { WarpProvisioner(this@WarpDiagnosticsActivity).diagnostics() }
        val health = ConnectionHealthStore(this@WarpDiagnosticsActivity).snapshot()
        val mtu = config?.getInterface()?.mtu?.orElse(null)
        val keepalive = peer?.persistentKeepalive?.orElse(null)
        val endpoint = peer?.endpoint?.orElse(null)?.toString() ?: "-"
        val handshakeText = if (handshake > 0) {
            DateUtils.getRelativeTimeSpanString(handshake * 1_000L).toString()
        } else "-"

        return buildString {
            appendLine(getString(R.string.warp_diagnostics_privacy))
            appendLine()
            appendLine("App: ${BuildConfig.VERSION_NAME}")
            appendLine("Backend: ${backend.javaClass.simpleName} / $backendVersion")
            appendLine("Network: ${Application.getNetworkState().getCurrentNetworkType()}")
            appendLine("Validated: ${Application.getNetworkState().isConnected()}")
            appendLine("Profiles: ${tunnels.size}")
            appendLine("Active: ${activeTunnel?.name ?: "-"}")
            appendLine("Connection state: ${activeTunnel?.connectionStatus ?: "DISCONNECTED"}")
            appendLine("Endpoint: $endpoint")
            appendLine("MTU / Keepalive: ${mtu ?: "auto"} / ${keepalive ?: "off"}")
            appendLine("Last handshake: $handshakeText")
            appendLine("Traffic RX / TX: ${Formatter.formatFileSize(this@WarpDiagnosticsActivity, statistics?.totalRx() ?: 0L)} / ${Formatter.formatFileSize(this@WarpDiagnosticsActivity, statistics?.totalTx() ?: 0L)}")
            appendLine("Last health probe: ${relativeTime(health.lastProbeAt)} / ${if (health.lastProbeSucceeded) "passed" else "failed"}")
            appendLine("Health failures: ${health.consecutiveFailures}")
            appendLine("Last recovery: ${relativeTime(health.lastRecoveryAt)} / ${health.lastReason}")
            appendLine()
            appendLine("WARP identity: ${if (account.registered) "stored securely" else "not created"}")
            appendLine("Account type: ${account.accountType}")
            appendLine("Device / WARP flags: ${account.deviceEnabled ?: "unknown"} / ${account.warpEnabled ?: "unknown"}")
            appendLine("Identity updated: ${account.updatedAt}")
            appendLine("API failures / retry: ${account.registrationFailures} / ${account.retryAfterSeconds}s")
        }.trimEnd()
    }

    private fun relativeTime(epochMillis: Long): String = if (epochMillis > 0L) {
        DateUtils.getRelativeTimeSpanString(epochMillis).toString()
    } else "-"

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSelectedTunnelChanged(
        oldTunnel: ObservableTunnel?,
        newTunnel: ObservableTunnel?,
    ) = true
}
