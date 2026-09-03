/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import org.amnezia.awg.Application.Companion.get
import org.amnezia.awg.Application.Companion.getBackend
import org.amnezia.awg.Application.Companion.getTunnelManager
import org.amnezia.awg.BR
import org.amnezia.awg.R
import org.amnezia.awg.backend.Statistics
import org.amnezia.awg.backend.Backend
import org.amnezia.awg.backend.StatusCallback
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.configStore.ConfigStore
import org.amnezia.awg.databinding.ObservableSortedKeyedArrayList
import org.amnezia.awg.util.ErrorMessages
import org.amnezia.awg.util.UserKnobs
import org.amnezia.awg.util.applicationScope
import org.amnezia.awg.config.Config
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.amnezia.awg.warp.WarpAwgRecovery
import java.net.HttpURLConnection
import java.net.URL

/**
 * Maintains and mediates changes to the set of available AmneziaWG tunnels,
 */
class TunnelManager(private val configStore: ConfigStore) : BaseObservable() {
    private val tunnels = CompletableDeferred<ObservableSortedKeyedArrayList<String, ObservableTunnel>>()
    private val context: Context = get()
    private val tunnelMap: ObservableSortedKeyedArrayList<String, ObservableTunnel> = ObservableSortedKeyedArrayList(TunnelComparator)
    private var haveLoaded = false
    private val recoveryMutex = Mutex()
    @Volatile private var automaticRecoveryPaused = false
    private val awgRecovery = WarpAwgRecovery(context)
    private val healthMonitor = TunnelHealthMonitor(
        context,
        activeTunnel = {
            if (automaticRecoveryPaused) null
            else tunnelMap.firstOrNull { it.state == Tunnel.State.UP }
        },
        recover = ::recoverTunnel,
    )

    private fun addToList(name: String, config: Config?, state: Tunnel.State): ObservableTunnel {
        val tunnel = ObservableTunnel(this, name, config, state)
        tunnelMap.add(tunnel)
        return tunnel
    }

    suspend fun getTunnels(): ObservableSortedKeyedArrayList<String, ObservableTunnel> = tunnels.await()

    suspend fun create(name: String, config: Config?): ObservableTunnel = withContext(Dispatchers.Main.immediate) {
        if (Tunnel.isNameInvalid(name))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_invalid_name))
        if (tunnelMap.containsKey(name))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_already_exists, name))
        addToList(name, withContext(Dispatchers.IO) { configStore.create(name, config!!) }, Tunnel.State.DOWN)
    }

    suspend fun delete(tunnel: ObservableTunnel) = withContext(Dispatchers.Main.immediate) {
        val originalState = tunnel.state
        val wasLastUsed = tunnel == lastUsedTunnel
        // Make sure nothing touches the tunnel.
        if (wasLastUsed)
            lastUsedTunnel = null
        tunnelMap.remove(tunnel)
        try {
            if (originalState == Tunnel.State.UP)
                withContext(Dispatchers.IO) { getBackend().setState(tunnel, Tunnel.State.DOWN, null) }
            try {
                withContext(Dispatchers.IO) { configStore.delete(tunnel.name) }
            } catch (e: Throwable) {
                if (originalState == Tunnel.State.UP)
                    withContext(Dispatchers.IO) { getBackend().setState(tunnel, Tunnel.State.UP, tunnel.config) }
                throw e
            }
        } catch (e: Throwable) {
            // Failure, put the tunnel back.
            tunnelMap.add(tunnel)
            if (wasLastUsed)
                lastUsedTunnel = tunnel
            throw e
        }
    }

    @get:Bindable
    var lastUsedTunnel: ObservableTunnel? = null
        private set(value) {
            if (value == field) return
            field = value
            notifyPropertyChanged(BR.lastUsedTunnel)
            applicationScope.launch { UserKnobs.setLastUsedTunnel(value?.name) }
        }

    suspend fun getTunnelConfig(tunnel: ObservableTunnel): Config = withContext(Dispatchers.Main.immediate) {
        tunnel.onConfigChanged(withContext(Dispatchers.IO) { configStore.load(tunnel.name) })!!
    }

    fun onCreate() {
        healthMonitor.start(applicationScope)
        applicationScope.launch {
            try {
                onTunnelsLoaded(withContext(Dispatchers.IO) { configStore.enumerate() }, withContext(Dispatchers.IO) { getBackend().runningTunnelNames })
                setupStatusCallbacks()
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
            }
        }
    }

    private suspend fun recoverTunnel(tunnel: ObservableTunnel) {
        // Drop a probe that finished while an intentional connection test owns the backend;
        // otherwise it could queue on the mutex and restart the newly verified tunnel afterward.
        if (automaticRecoveryPaused) return
        recoveryMutex.withLock {
            if (automaticRecoveryPaused || tunnel.state != Tunnel.State.UP) return@withLock
            val currentConfig = tunnel.getConfigAsync()
            val recoveryConfig = withContext(Dispatchers.IO) {
                awgRecovery.nextConfig(currentConfig) ?: currentConfig
            }
            tunnel.onConnectionStatusChanged(ObservableTunnel.ConnectionStatus.CONNECTING)
            val activation = withContext(Dispatchers.IO) {
                val backend = getBackend()
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                delay(750L)
                try {
                    if (awgRecovery.isManagedConfig(recoveryConfig)) {
                        activateManagedWarp(tunnel, recoveryConfig)
                    } else {
                        WarpActivation(
                            backend.setState(tunnel, Tunnel.State.UP, recoveryConfig),
                            recoveryConfig,
                        )
                    }
                } catch (error: Throwable) {
                    runCatching { backend.setState(tunnel, Tunnel.State.UP, currentConfig) }
                    throw error
                }
            }
            if (activation.config != currentConfig) {
                withContext(Dispatchers.IO) { configStore.save(tunnel.name, activation.config) }
                tunnel.onConfigChanged(activation.config)
            }
            if (activation.state == Tunnel.State.UP)
                tunnel.onConnectionStatusChanged(ObservableTunnel.ConnectionStatus.CONNECTED)
        }
    }

    /** Prevents the health monitor from racing an intentional multi-endpoint connection test. */
    suspend fun <T> withAutomaticRecoveryPaused(block: suspend () -> T): T =
        recoveryMutex.withLock {
            automaticRecoveryPaused = true
            try {
                block()
            } finally {
                automaticRecoveryPaused = false
            }
        }

    /** Rebinds active tunnels after a debounced physical-network change. */
    suspend fun reconnectAfterNetworkChange() = recoveryMutex.withLock {
        val active = tunnelMap.filter { it.state == Tunnel.State.UP }
        if (active.isEmpty()) return@withLock
        for (tunnel in active) {
            val currentConfig = tunnel.getConfigAsync()
            val selectedConfig = withContext(Dispatchers.IO) {
                awgRecovery.bestConfig(currentConfig) ?: currentConfig
            }
            tunnel.onConnectionStatusChanged(ObservableTunnel.ConnectionStatus.CONNECTING)
            val activation = withContext(Dispatchers.IO) {
                val backend = getBackend()
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                delay(750L)
                if (awgRecovery.isManagedConfig(selectedConfig)) {
                    activateManagedWarp(tunnel, selectedConfig)
                } else {
                    WarpActivation(
                        backend.setState(tunnel, Tunnel.State.UP, selectedConfig),
                        selectedConfig,
                    )
                }
            }
            if (activation.config != currentConfig) {
                withContext(Dispatchers.IO) { configStore.save(tunnel.name, activation.config) }
                tunnel.onConfigChanged(activation.config)
            }
            if (activation.state == Tunnel.State.UP)
                tunnel.onConnectionStatusChanged(ObservableTunnel.ConnectionStatus.CONNECTED)
        }
    }

    private fun setupStatusCallbacks() {
        applicationScope.launch {
            try {
                val backend = getBackend()
                val statusCallback = object : StatusCallback {
                    override fun onStatusChanged(connected: Boolean) {
                        applicationScope.launch(Dispatchers.Main) {
                            // Find the currently active tunnel
                            val activeTunnel = tunnelMap.firstOrNull { it.state == Tunnel.State.UP }
                            if (activeTunnel != null) {
                                val newStatus = if (connected) {
                                    if (activeTunnel.connectionStatus != ObservableTunnel.ConnectionStatus.CONNECTED) {
                                        runCatching { awgRecovery.recordConnected(activeTunnel.getConfigAsync()) }
                                            .onFailure { error -> Log.w(TAG, "Could not save successful WARP endpoint", error) }
                                    }
                                    ObservableTunnel.ConnectionStatus.CONNECTED
                                } else {
                                    ObservableTunnel.ConnectionStatus.DISCONNECTED
                                }
                                activeTunnel.onConnectionStatusChanged(newStatus)
                            }
                        }
                    }
                }
                
                backend.setStatusCallback(statusCallback)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to setup status callbacks", e)
            }
        }
    }

    private fun onTunnelsLoaded(present: Iterable<String>, running: Collection<String>) {
        for (name in present)
            addToList(name, null, if (running.contains(name)) Tunnel.State.UP else Tunnel.State.DOWN)
        applicationScope.launch {
            val lastUsedName = UserKnobs.lastUsedTunnel.first()
            if (lastUsedName != null)
                lastUsedTunnel = tunnelMap[lastUsedName]
            haveLoaded = true
            restoreState(true)
            tunnels.complete(tunnelMap)
        }
    }

    private fun refreshTunnelStates() {
        applicationScope.launch {
            try {
                synchronizeTunnelStatesWithBackend()
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
            }
        }
    }

    /** GoBackend can implicitly replace its previous tunnel; mirror that native truth in the UI. */
    private suspend fun synchronizeTunnelStatesWithBackend() = withContext(Dispatchers.Main.immediate) {
        val running = withContext(Dispatchers.IO) { getBackend().runningTunnelNames }
        for (item in tunnelMap) {
            val actualState = if (item.name in running) Tunnel.State.UP else Tunnel.State.DOWN
            if (item.state != actualState) item.onStateChanged(actualState)
        }
    }

    suspend fun restoreState(force: Boolean) {
        if (!haveLoaded || (!force && !UserKnobs.restoreOnBoot.first()))
            return
        val previouslyRunning = UserKnobs.runningTunnels.first()
        if (previouslyRunning.isEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                tunnelMap.filter { previouslyRunning.contains(it.name) }.map { async(Dispatchers.IO + SupervisorJob()) { setTunnelState(it, Tunnel.State.UP) } }
                    .awaitAll()
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
            }
        }
    }

    suspend fun saveState() {
        UserKnobs.setRunningTunnels(tunnelMap.filter { it.state == Tunnel.State.UP }.map { it.name }.toSet())
    }

    suspend fun setTunnelConfig(tunnel: ObservableTunnel, config: Config): Config = withContext(Dispatchers.Main.immediate) {
        tunnel.onConfigChanged(withContext(Dispatchers.IO) {
            getBackend().setState(tunnel, tunnel.state, config)
            configStore.save(tunnel.name, config)
        })!!
    }

    suspend fun setTunnelName(tunnel: ObservableTunnel, name: String): String = withContext(Dispatchers.Main.immediate) {
        if (Tunnel.isNameInvalid(name))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_invalid_name))
        if (tunnelMap.containsKey(name)) {
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_already_exists, name))
        }
        val originalState = tunnel.state
        val wasLastUsed = tunnel == lastUsedTunnel
        // Make sure nothing touches the tunnel.
        if (wasLastUsed)
            lastUsedTunnel = null
        tunnelMap.remove(tunnel)
        var throwable: Throwable? = null
        var newName: String? = null
        try {
            if (originalState == Tunnel.State.UP)
                withContext(Dispatchers.IO) { getBackend().setState(tunnel, Tunnel.State.DOWN, null) }
            withContext(Dispatchers.IO) { configStore.rename(tunnel.name, name) }
            newName = tunnel.onNameChanged(name)
            if (originalState == Tunnel.State.UP)
                withContext(Dispatchers.IO) { getBackend().setState(tunnel, Tunnel.State.UP, tunnel.config) }
        } catch (e: Throwable) {
            throwable = e
            // On failure, we don't know what state the tunnel might be in. Fix that.
            getTunnelState(tunnel)
        }
        // Add the tunnel back to the manager, under whatever name it thinks it has.
        tunnelMap.add(tunnel)
        if (wasLastUsed)
            lastUsedTunnel = tunnel
        if (throwable != null)
            throw throwable
        newName!!
    }

    /**
     * A backend UP result only means that the local interface exists. For a managed WARP profile,
     * do not report success until both a fresh authenticated handshake and routed WARP traffic are
     * observed. A flaky first UDP/NAT mapping is retried automatically before rotating endpoint.
     */
    private suspend fun activateManagedWarp(
        tunnel: ObservableTunnel,
        initialConfig: Config,
    ): WarpActivation = withContext(Dispatchers.IO) {
        val backend = getBackend()
        var candidateConfig = awgRecovery.ensureNumericEndpoint(initialConfig)
            ?: throw IllegalStateException("No numeric WARP endpoint is available")
        var lastFailure: Throwable? = null

        repeat(WARP_CONNECT_ATTEMPTS) { attempt ->
            val attemptStartedAt = System.currentTimeMillis() / 1_000L - 1L
            val attemptStartedElapsed = SystemClock.elapsedRealtime()
            val state = runCatching { backend.setState(tunnel, Tunnel.State.UP, candidateConfig) }
                .onFailure { error -> lastFailure = error }
                .getOrNull()

            if (state == Tunnel.State.UP) {
                val handshaked = awaitFreshHandshake(backend, tunnel, attemptStartedAt)
                val handshakeMs = SystemClock.elapsedRealtime() - attemptStartedElapsed
                if (handshaked) {
                    delay(WARP_DATA_PATH_SETTLE_MS)
                    val validationStarted = SystemClock.elapsedRealtime()
                    if (verifyWarpDataPath()) {
                        val validationMs = SystemClock.elapsedRealtime() - validationStarted
                        awgRecovery.recordConnected(candidateConfig, handshakeMs, validationMs)
                        return@withContext WarpActivation(Tunnel.State.UP, candidateConfig)
                    }
                    lastFailure = IllegalStateException("WARP handshake completed but Internet routing was not ready")
                } else {
                    lastFailure = IllegalStateException("WARP handshake timed out")
                }
            }

            runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            if (attempt + 1 >= WARP_CONNECT_ATTEMPTS) return@repeat

            // The first retry deliberately keeps the verified endpoint. This repairs the common
            // first UDP/NAT mapping failure quickly; only subsequent attempts pay for a scan and
            // rotate to a different, supported WARP route.
            if (attempt + 1 >= SAME_ENDPOINT_ATTEMPTS) {
                candidateConfig = runCatching { awgRecovery.nextConfig(candidateConfig) }
                    .getOrNull() ?: candidateConfig
            }
            delay(WARP_RETRY_BASE_DELAY_MS * (attempt + 1L))
        }

        runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
        throw lastFailure ?: IllegalStateException("WARP connection could not be verified")
    }

    private suspend fun awaitFreshHandshake(
        backend: Backend,
        tunnel: ObservableTunnel,
        attemptStartedAt: Long,
    ): Boolean {
        repeat((WARP_HANDSHAKE_TIMEOUT_MS / WARP_HANDSHAKE_POLL_MS).toInt()) {
            delay(WARP_HANDSHAKE_POLL_MS)
            val handshake = runCatching { backend.getLastHandshake(tunnel) }.getOrDefault(0L)
            if (handshake >= attemptStartedAt) return true
        }
        return false
    }

    private suspend fun verifyWarpDataPath(): Boolean {
        repeat(WARP_DATA_PATH_ATTEMPTS) { attempt ->
            val verified = runCatching {
                val connection = URL(WARP_TRACE_URL).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = WARP_DATA_PATH_TIMEOUT_MS
                    connection.readTimeout = WARP_DATA_PATH_TIMEOUT_MS
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.setRequestProperty("Connection", "close")
                    connection.responseCode in 200..299 && connection.inputStream
                        .bufferedReader()
                        .useLines { lines ->
                            lines.any { line ->
                                line.equals("warp=on", ignoreCase = true) ||
                                    line.equals("warp=plus", ignoreCase = true)
                            }
                        }
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
            if (verified) return true
            if (attempt + 1 < WARP_DATA_PATH_ATTEMPTS) delay(WARP_DATA_PATH_RETRY_DELAY_MS)
        }
        return false
    }

    suspend fun setTunnelState(tunnel: ObservableTunnel, state: Tunnel.State): Tunnel.State = withContext(Dispatchers.Main.immediate) {
        var newState = tunnel.state
        var throwable: Throwable? = null
        try {
            val initialConfig = tunnel.getConfigAsync()
            val managedWarp = state == Tunnel.State.UP && !automaticRecoveryPaused &&
                withContext(Dispatchers.IO) { awgRecovery.isManagedConfig(initialConfig) }
            if (managedWarp) {
                tunnel.onConnectionStatusChanged(ObservableTunnel.ConnectionStatus.CONNECTING)
                val activation = recoveryMutex.withLock {
                    activateManagedWarp(tunnel, initialConfig)
                }
                newState = activation.state
                if (activation.config != initialConfig) {
                    withContext(Dispatchers.IO) { configStore.save(tunnel.name, activation.config) }
                    tunnel.onConfigChanged(activation.config)
                }
                tunnel.onConnectionStatusChanged(ObservableTunnel.ConnectionStatus.CONNECTED)
            } else {
                newState = withContext(Dispatchers.IO) {
                    getBackend().setState(tunnel, state, initialConfig)
                }
            }
            if (newState == Tunnel.State.UP) {
                lastUsedTunnel = tunnel
            }
        } catch (e: Throwable) {
            throwable = e
            // Query the actual state from the backend to ensure UI stays consistent with reality
            newState = runCatching { withContext(Dispatchers.IO) { getBackend().getState(tunnel) } }
                .getOrDefault(Tunnel.State.DOWN)
            tunnel.onConnectionStatusChanged(ObservableTunnel.ConnectionStatus.DISCONNECTED)
        }
        tunnel.onStateChanged(newState)
        runCatching { synchronizeTunnelStatesWithBackend() }
            .onFailure { error -> Log.w(TAG, "Could not synchronize tunnel states", error) }
        saveState()
        if (throwable != null)
            throw throwable
        newState
    }

    class IntentReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            applicationScope.launch {
                val manager = getTunnelManager()
                if (intent == null) return@launch
                val action = intent.action ?: return@launch
                if ("org.amnezia.awg.action.REFRESH_TUNNEL_STATES" == action) {
                    manager.refreshTunnelStates()
                    return@launch
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !UserKnobs.allowRemoteControlIntents.first())
                    return@launch
                val state: Tunnel.State
                state = when (action) {
                    "org.amnezia.awg.action.SET_TUNNEL_UP" -> Tunnel.State.UP
                    "org.amnezia.awg.action.SET_TUNNEL_DOWN" -> Tunnel.State.DOWN
                    else -> return@launch
                }
                val tunnelName = intent.getStringExtra("tunnel") ?: return@launch
                val tunnels = manager.getTunnels()
                val tunnel = tunnels[tunnelName] ?: return@launch
                try {
                    manager.setTunnelState(tunnel, state)
                } catch (e: Throwable) {
                    Toast.makeText(context, ErrorMessages[e], Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    suspend fun getTunnelState(tunnel: ObservableTunnel): Tunnel.State = withContext(Dispatchers.Main.immediate) {
        tunnel.onStateChanged(withContext(Dispatchers.IO) { getBackend().getState(tunnel) })
    }

    suspend fun getTunnelStatistics(tunnel: ObservableTunnel): Statistics = withContext(Dispatchers.Main.immediate) {
        tunnel.onStatisticsChanged(withContext(Dispatchers.IO) { getBackend().getStatistics(tunnel) })!!
    }

    companion object {
        private const val TAG = "AmneziaWG/TunnelManager"
        private const val WARP_CONNECT_ATTEMPTS = 3
        private const val SAME_ENDPOINT_ATTEMPTS = 2
        private const val WARP_HANDSHAKE_TIMEOUT_MS = 7_000L
        private const val WARP_HANDSHAKE_POLL_MS = 250L
        private const val WARP_DATA_PATH_SETTLE_MS = 500L
        private const val WARP_DATA_PATH_ATTEMPTS = 2
        private const val WARP_DATA_PATH_TIMEOUT_MS = 4_000
        private const val WARP_DATA_PATH_RETRY_DELAY_MS = 500L
        private const val WARP_RETRY_BASE_DELAY_MS = 500L
        private const val WARP_TRACE_URL = "https://connectivity.cloudflareclient.com/cdn-cgi/trace"
    }

    private data class WarpActivation(val state: Tunnel.State, val config: Config)
}
