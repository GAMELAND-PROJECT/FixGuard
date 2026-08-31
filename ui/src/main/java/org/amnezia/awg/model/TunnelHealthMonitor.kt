package org.amnezia.awg.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.Application
import org.amnezia.awg.backend.Tunnel
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque

/** Detects black-holed tunnels and requests a bounded, evidence-based recovery. */
class TunnelHealthMonitor(
    context: Context,
    private val activeTunnel: () -> ObservableTunnel?,
    private val recover: suspend (ObservableTunnel) -> Unit,
) {
    private val connectivityManager = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.Default) { monitor() }
    }

    private suspend fun monitor() {
        var observedName: String? = null
        var observedSince = 0L
        var lastRx = 0L
        var lastTx = 0L
        var unansweredSince = 0L
        var lastProbeAt = 0L
        var failedProbes = 0
        var lastRecoveryAt = 0L
        val recoveryHistory = ArrayDeque<Long>()

        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL_MS)
            val tunnel = withContext(Dispatchers.Main.immediate) {
                activeTunnel()?.takeIf { it.state == Tunnel.State.UP }
            }
            if (tunnel == null) {
                observedName = null
                observedSince = 0L
                failedProbes = 0
                continue
            }
            if (observedName != tunnel.name) {
                observedName = tunnel.name
                observedSince = System.currentTimeMillis()
                lastRx = 0
                lastTx = 0
                unansweredSince = 0
                failedProbes = 0
            }

            val backend = runCatching { Application.getBackend() }.getOrNull() ?: continue
            val snapshot = runCatching {
                withContext(Dispatchers.IO) {
                    backend.getStatistics(tunnel) to backend.getLastHandshake(tunnel)
                }
            }.getOrNull() ?: continue
            val now = System.currentTimeMillis()
            val rx = snapshot.first.totalRx()
            val tx = snapshot.first.totalTx()
            val handshakeSeconds = snapshot.second

            if (rx > lastRx) {
                unansweredSince = 0
                failedProbes = 0
            } else if (tx - lastTx >= MEANINGFUL_TX_BYTES && unansweredSince == 0L) {
                unansweredSince = now
            }
            lastRx = rx
            lastTx = tx

            val handshakeIsOld = handshakeSeconds > 0 && now / 1000 - handshakeSeconds > STALE_HANDSHAKE_SECONDS
            val handshakeIsMissing = handshakeSeconds <= 0 &&
                observedSince > 0 && now - observedSince > INITIAL_HANDSHAKE_TIMEOUT_MS
            val outgoingIsUnanswered = unansweredSince > 0 && now - unansweredSince > UNANSWERED_TRAFFIC_MS
            if ((!handshakeIsOld && !handshakeIsMissing && !outgoingIsUnanswered) ||
                now - lastProbeAt < PROBE_INTERVAL_MS)
                continue
            if (!hasPhysicalInternet()) continue

            lastProbeAt = now
            if (probeTunnel()) {
                failedProbes = 0
                unansweredSince = 0
                continue
            }
            failedProbes++
            if (failedProbes < REQUIRED_FAILURES || now - lastRecoveryAt < RECOVERY_COOLDOWN_MS)
                continue

            while (recoveryHistory.isNotEmpty() && now - recoveryHistory.first() > RECOVERY_WINDOW_MS)
                recoveryHistory.removeFirst()
            if (recoveryHistory.size >= MAX_RECOVERIES_PER_WINDOW) {
                Log.w(TAG, "Recovery budget exhausted for ${tunnel.name}")
                continue
            }

            failedProbes = 0
            unansweredSince = 0
            lastRecoveryAt = now
            recoveryHistory.addLast(now)
            runCatching { recover(tunnel) }
                .onFailure { Log.e(TAG, "Automatic tunnel recovery failed", it) }
            // A restarted WireGuard device resets its byte counters even when the tunnel name stays.
            observedName = null
            lastRx = 0
            lastTx = 0
        }
    }

    private fun hasPhysicalInternet(): Boolean = connectivityManager.allNetworks.any { network ->
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@any false
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun probeTunnel(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(PROBE_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = PROBE_TIMEOUT_MS
                connection.readTimeout = PROBE_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Connection", "close")
                connection.inputStream.use { it.read() }
                connection.responseCode in 200..399
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "FixGuard/TunnelHealth"
        const val PROBE_URL = "https://connectivity.cloudflareclient.com/cdn-cgi/trace"
        const val POLL_INTERVAL_MS = 15_000L
        const val PROBE_INTERVAL_MS = 30_000L
        const val PROBE_TIMEOUT_MS = 6_000
        const val UNANSWERED_TRAFFIC_MS = 30_000L
        const val MEANINGFUL_TX_BYTES = 512L
        const val STALE_HANDSHAKE_SECONDS = 180L
        const val INITIAL_HANDSHAKE_TIMEOUT_MS = 30_000L
        const val REQUIRED_FAILURES = 2
        const val RECOVERY_COOLDOWN_MS = 60_000L
        const val RECOVERY_WINDOW_MS = 10 * 60_000L
        const val MAX_RECOVERIES_PER_WINDOW = 3
    }
}
