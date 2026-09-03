/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.fragment

import android.animation.ObjectAnimator
import android.content.Intent
import android.app.Activity
import android.content.res.Resources
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.amnezia.awg.Application
import org.amnezia.awg.R
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.activity.TunnelCreatorActivity
import org.amnezia.awg.databinding.ObservableKeyedRecyclerViewAdapter.RowConfigurationHandler
import org.amnezia.awg.databinding.TunnelListFragmentBinding
import org.amnezia.awg.databinding.TunnelListItemBinding
import org.amnezia.awg.databinding.ObservableSortedKeyedArrayList
import org.amnezia.awg.model.TunnelComparator
import org.amnezia.awg.model.ObservableTunnel
import org.amnezia.awg.util.ErrorMessages
import org.amnezia.awg.util.QrCodeFromFileScanner
import org.amnezia.awg.util.TunnelImporter
import org.amnezia.awg.widget.MultiselectableRelativeLayout
import org.amnezia.awg.warp.WarpProvisioner
import org.amnezia.awg.warp.WarpProfileCandidate
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fragment containing a list of known AmneziaWG tunnels. It allows creating and deleting tunnels.
 */
class TunnelListFragment : BaseFragment() {
    private val actionModeListener = ActionModeListener()
    private var actionMode: ActionMode? = null
    private var backPressedCallback: OnBackPressedCallback? = null
    private var binding: TunnelListFragmentBinding? = null
    private var warpStageHideJob: Job? = null
    private var smartConnectJob: Job? = null
    private var smartConnectAnimator: ObjectAnimator? = null
    private var pendingSmartConnectTunnel: ObservableTunnel? = null
    private val warpVpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pendingTunnel = pendingSmartConnectTunnel
        pendingSmartConnectTunnel = null
        if (result.resultCode == Activity.RESULT_OK) {
            if (pendingTunnel != null) {
                viewLifecycleOwner.lifecycleScope.launch { connectReusableWarpTunnel(pendingTunnel) }
            } else {
                createAndVerifyWarpProfile()
            }
        } else {
            setSmartConnectBusy(false)
            showSnackbar(getString(R.string.warp_profile_permission_required))
        }
    }
    private val tunnelFileImportResultLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { data ->
        if (data == null) return@registerForActivityResult
        val activity = activity ?: return@registerForActivityResult
        val contentResolver = activity.contentResolver ?: return@registerForActivityResult
        activity.lifecycleScope.launch {
            if (QrCodeFromFileScanner.validContentType(contentResolver, data)) {
                try {
                    val qrCodeFromFileScanner = QrCodeFromFileScanner(contentResolver, QRCodeReader())
                    val result = qrCodeFromFileScanner.scan(data)
                    TunnelImporter.importTunnel(parentFragmentManager, result.text) { showSnackbar(it) }
                } catch (e: Exception) {
                    val error = ErrorMessages[e]
                    val message = Application.get().resources.getString(R.string.import_error, error)
                    Log.e(TAG, message, e)
                    showSnackbar(message)
                }
            } else {
                TunnelImporter.importTunnel(contentResolver, data) { showSnackbar(it) }
            }
        }
    }

    private val qrImportResultLauncher = registerForActivityResult(ScanContract()) { result ->
        val qrCode = result.contents
        val activity = activity
        if (qrCode != null && activity != null) {
            activity.lifecycleScope.launch { TunnelImporter.importTunnel(parentFragmentManager, qrCode) { showSnackbar(it) } }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState != null) {
            val checkedItems = savedInstanceState.getIntegerArrayList(CHECKED_ITEMS)
            if (checkedItems != null) {
                for (i in checkedItems) actionModeListener.setItemChecked(i, true)
            }
        }
        warmUpWarpIdentities()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        binding = TunnelListFragmentBinding.inflate(inflater, container, false)
        val bottomSheet = AddTunnelsSheet()
        binding?.apply {
            smartConnectButton.setOnClickListener { onSmartConnectClicked() }
            optimizeWarpFab.setOnClickListener { prepareVerifiedWarpProfile() }
            createFab.setOnClickListener {
                if (childFragmentManager.findFragmentByTag("BOTTOM_SHEET") != null)
                    return@setOnClickListener
                childFragmentManager.setFragmentResultListener(AddTunnelsSheet.REQUEST_KEY_NEW_TUNNEL, viewLifecycleOwner) { _, bundle ->
                    when (bundle.getString(AddTunnelsSheet.REQUEST_METHOD)) {
                        AddTunnelsSheet.REQUEST_CREATE -> {
                            startActivity(Intent(requireActivity(), TunnelCreatorActivity::class.java))
                        }

                        AddTunnelsSheet.REQUEST_CREATE_WARP -> {
                            prepareVerifiedWarpProfile()
                        }

                        AddTunnelsSheet.REQUEST_IMPORT -> {
                            tunnelFileImportResultLauncher.launch("*/*")
                        }

                        AddTunnelsSheet.REQUEST_SCAN -> {
                            qrImportResultLauncher.launch(
                                ScanOptions()
                                    .setOrientationLocked(false)
                                    .setBeepEnabled(false)
                                    .setPrompt(getString(R.string.qr_code_hint))
                            )
                        }
                    }
                }
                bottomSheet.showNow(childFragmentManager, "BOTTOM_SHEET")
            }
            executePendingBindings()
        }
        backPressedCallback = requireActivity().onBackPressedDispatcher.addCallback(this) { actionMode?.finish() }
        backPressedCallback?.isEnabled = false

        return binding?.root
    }

    override fun onDestroyView() {
        smartConnectAnimator?.cancel()
        smartConnectAnimator = null
        binding = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntegerArrayList(CHECKED_ITEMS, actionModeListener.getCheckedItems())
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        binding ?: return
        lifecycleScope.launch {
            val tunnels = Application.getTunnelManager().getTunnels()
            if (newTunnel != null) viewForTunnel(newTunnel, tunnels)?.setSingleSelected(true)
            if (oldTunnel != null) viewForTunnel(oldTunnel, tunnels)?.setSingleSelected(false)
            refreshSmartConnectUi()
        }
    }

    private fun onTunnelDeletionFinished(count: Int, throwable: Throwable?) {
        val message: String
        val ctx = activity ?: Application.get()
        if (throwable == null) {
            message = ctx.resources.getQuantityString(R.plurals.delete_success, count, count)
        } else {
            val error = ErrorMessages[throwable]
            message = ctx.resources.getQuantityString(R.plurals.delete_error, count, count, error)
            Log.e(TAG, message, throwable)
        }
        showSnackbar(message)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        binding ?: return
        binding!!.fragment = this
        lifecycleScope.launch {
            val allTunnels = Application.getTunnelManager().getTunnels()
            // Hide WARP profiles from visual list — they are handled by the smart connect button
            val filtered = ObservableSortedKeyedArrayList<String, ObservableTunnel>(TunnelComparator)
            for (t in allTunnels) {
                if (!isWarpProfile(t)) filtered.add(t)
            }
            binding!!.tunnels = filtered
        }
        refreshSmartConnectUi()
        binding!!.rowConfigurationHandler = object : RowConfigurationHandler<TunnelListItemBinding, ObservableTunnel> {
            override fun onConfigureRow(binding: TunnelListItemBinding, item: ObservableTunnel, position: Int) {
                binding.fragment = this@TunnelListFragment
                binding.root.setOnClickListener {
                    if (actionMode == null) {
                        selectedTunnel = item
                    } else {
                        actionModeListener.toggleItemChecked(position)
                    }
                }
                binding.root.setOnLongClickListener {
                    actionModeListener.toggleItemChecked(position)
                    true
                }
                if (actionMode != null)
                    (binding.root as MultiselectableRelativeLayout).setMultiSelected(actionModeListener.checkedItems.contains(position))
                else
                    (binding.root as MultiselectableRelativeLayout).setSingleSelected(selectedTunnel == item)
            }
        }
    }

    private fun showSnackbar(message: CharSequence) {
        val binding = binding
        if (binding != null)
            Snackbar.make(binding.mainContainer, message, Snackbar.LENGTH_LONG)
                .setAnchorView(binding.createFab)
                .show()
        else
            Toast.makeText(activity ?: Application.get(), message, Toast.LENGTH_SHORT).show()
    }

    private fun warmUpWarpIdentities() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching { WarpProvisioner(requireContext()).ensureIdentityPool() }
                .onFailure { error -> Log.w(TAG, "WARP identity warm-up did not finish", error) }
        }
    }

    /** WARP profiles are managed by the central connect button and stay hidden from the list. */
    private fun isWarpProfile(tunnel: ObservableTunnel): Boolean =
        tunnel.name.startsWith(WARP_TUNNEL_PREFIX)

    private fun onSmartConnectClicked() {
        if (smartConnectJob?.isActive == true) {
            showSnackbar(getString(R.string.smart_connect_busy))
            return
        }
        smartConnectJob = viewLifecycleOwner.lifecycleScope.launch {
            val manager = Application.getTunnelManager()
            val tunnels = manager.getTunnels()
            val active = tunnels.firstOrNull { it.state == Tunnel.State.UP }
            if (active != null) {
                setSmartConnectBusy(true, getString(R.string.smart_connect_disconnecting))
                runCatching { active.setStateAsync(Tunnel.State.DOWN) }
                    .onFailure { error -> showSnackbar(getString(R.string.error_down, ErrorMessages[error])) }
                setSmartConnectBusy(false)
                refreshSmartConnectUi()
                return@launch
            }

            val reusable = tunnels.firstOrNull { isWarpProfile(it) }
            if (reusable == null) {
                setSmartConnectBusy(true, getString(R.string.smart_connect_preparing))
                prepareVerifiedWarpProfile()
                return@launch
            }

            try {
                if (Application.getBackend() is GoBackend) {
                    val intent = GoBackend.VpnService.prepare(requireActivity())
                    if (intent != null) {
                        pendingSmartConnectTunnel = reusable
                        warpVpnPermissionLauncher.launch(intent)
                        return@launch
                    }
                }
                connectReusableWarpTunnel(reusable)
            } finally {
                refreshSmartConnectUi()
            }
        }
    }

    private suspend fun connectReusableWarpTunnel(tunnel: ObservableTunnel) {
        setSmartConnectBusy(true, getString(R.string.smart_connect_connecting))
        updateWarpStage(getString(R.string.warp_stage_preparing))
        runCatching { tunnel.setStateAsync(Tunnel.State.UP) }
            .onSuccess {
                selectedTunnel = tunnel
                setSmartConnectBusy(false)
                updateWarpStage(getString(R.string.smart_connect_connected), autoHide = true)
            }
            .onFailure { error ->
                setSmartConnectBusy(false)
                updateWarpStage(getString(R.string.warp_stage_failed, ErrorMessages[error]), autoHide = true)
                showSnackbar(getString(R.string.error_up, ErrorMessages[error]))
            }
    }

    private fun refreshSmartConnectUi() {
        val currentBinding = binding ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val active = Application.getTunnelManager().getTunnels().firstOrNull { it.state == Tunnel.State.UP }
            if (smartConnectJob?.isActive == true) return@launch
            if (active != null) {
                currentBinding.smartConnectButton.setText(R.string.smart_disconnect)
                currentBinding.smartConnectCaption.setText(R.string.smart_connect_connected)
            } else {
                currentBinding.smartConnectButton.setText(R.string.smart_connect)
                currentBinding.smartConnectCaption.setText(R.string.smart_connect_ready)
            }
            currentBinding.smartConnectProgress.visibility = View.GONE
            // CRITICAL: Re-bind click listener to recover from cases where the button lost its handler
            currentBinding.smartConnectButton.setOnClickListener { onSmartConnectClicked() }
            currentBinding.smartConnectButton.isEnabled = true
            stopSmartConnectAnimation()
        }
    }

    private fun setSmartConnectBusy(busy: Boolean, caption: CharSequence? = null) {
        binding?.apply {
            smartConnectButton.isEnabled = !busy
            smartConnectButton.setText(if (busy) R.string.smart_connecting else R.string.smart_connect)
            caption?.let { smartConnectCaption.text = it }
            smartConnectProgress.visibility = if (busy) View.VISIBLE else View.GONE
            if (busy) startSmartConnectAnimation() else stopSmartConnectAnimation()
        }
    }

    private fun startSmartConnectAnimation() {
        val progress = binding?.smartConnectProgress ?: return
        if (smartConnectAnimator?.isStarted == true) return
        smartConnectAnimator = ObjectAnimator.ofFloat(progress, View.ROTATION, 0f, 360f).apply {
            duration = SMART_CONNECT_ROTATION_MS
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        binding?.smartConnectButton?.animate()
            ?.scaleX(0.94f)
            ?.scaleY(0.94f)
            ?.setDuration(220L)
            ?.withEndAction {
                binding?.smartConnectButton?.animate()
                    ?.scaleX(1f)
                    ?.scaleY(1f)
                    ?.setDuration(360L)
                    ?.start()
            }
            ?.start()
    }

    private fun stopSmartConnectAnimation() {
        smartConnectAnimator?.cancel()
        smartConnectAnimator = null
        binding?.smartConnectProgress?.rotation = 0f
        binding?.smartConnectButton?.animate()?.cancel()
        binding?.smartConnectButton?.scaleX = 1f
        binding?.smartConnectButton?.scaleY = 1f
    }

    private fun prepareVerifiedWarpProfile() {
        val activity = activity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (Application.getBackend() is GoBackend) {
                    val intent = GoBackend.VpnService.prepare(activity)
                    if (intent != null) {
                        warpVpnPermissionLauncher.launch(intent)
                        return@launch
                    }
                }
                createAndVerifyWarpProfile()
            } catch (error: Throwable) {
                Log.e(TAG, "Could not prepare Android VPN service", error)
                updateWarpStage(
                    getString(R.string.warp_stage_failed, ErrorMessages[error]),
                    autoHide = true,
                )
                showSnackbar(getString(R.string.warp_profile_error, ErrorMessages[error]))
            }
        }
    }

    private fun createAndVerifyWarpProfile() {
        val currentBinding = binding ?: return
        currentBinding.optimizeWarpFab.isEnabled = false
        updateWarpStage(getString(R.string.warp_stage_preparing))
        showSnackbar(getString(R.string.warp_verified_testing))
        viewLifecycleOwner.lifecycleScope.launch {
            var createdTunnel: ObservableTunnel? = null
            var previouslyActive: ObservableTunnel? = null
            val manager = Application.getTunnelManager()
            try {
                manager.withAutomaticRecoveryPaused {
                    runCatching {
                        val tunnels = manager.getTunnels()
                        previouslyActive = tunnels.firstOrNull { it.state == Tunnel.State.UP }
                        val provisioner = WarpProvisioner(requireContext())
                        val candidates = provisioner.createConnectionCandidates()
                        check(candidates.isNotEmpty()) { "No WARP connection candidates were produced" }

                        var name = "WARP"
                        var suffix = 2
                        while (tunnels.containsKey(name)) name = "WARP-${suffix++}"
                        val tunnel = manager.create(name, candidates.first().config)
                        createdTunnel = tunnel
                        // Force immediate refresh so new WARP profile appears in list
                        refreshSmartConnectUi()
                        var completedHandshake = false

                        val verifiedRoutes = mutableListOf<VerifiedWarpRoute>()
                        for ((index, candidate) in candidates.withIndex()) {
                            updateWarpStage(getString(
                                R.string.warp_stage_scanning,
                                index + 1,
                                candidates.size,
                                candidate.endpoint.authority,
                            ))
                            Log.i(TAG, "Testing WARP candidate ${index + 1}/${candidates.size}: ${candidate.endpoint.authority}")
                            if (index > 0) tunnel.setConfigAsync(candidate.config)
                            val attemptStartedAt = System.currentTimeMillis() / 1000L - 1L
                            val attemptStartedElapsed = SystemClock.elapsedRealtime()
                            tunnel.setStateAsync(Tunnel.State.UP)
                            updateWarpStage(getString(R.string.warp_stage_handshake, candidate.endpoint.authority))
                            val handshakeWaitSeconds = if (index < LONG_HANDSHAKE_ATTEMPTS)
                                HANDSHAKE_WAIT_SECONDS else FAST_HANDSHAKE_WAIT_SECONDS
                            val handshaked = awaitFreshHandshake(
                                tunnel,
                                attemptStartedAt,
                                handshakeWaitSeconds,
                            )
                            val handshakeMs = SystemClock.elapsedRealtime() - attemptStartedElapsed
                            completedHandshake = completedHandshake || handshaked
                            Log.i(TAG, "WARP handshake ${if (handshaked) "succeeded" else "timed out"}: ${candidate.endpoint.authority}")
                            if (handshaked) {
                                updateWarpStage(getString(R.string.warp_stage_verifying))
                                delay(DATA_PATH_SETTLE_MS)
                                val validationStartedElapsed = SystemClock.elapsedRealtime()
                                val routed = verifyWarpDataPath()
                                val validationMs = SystemClock.elapsedRealtime() - validationStartedElapsed
                                Log.i(TAG, "WARP data path ${if (routed) "verified" else "failed"}: ${candidate.endpoint.authority}")
                                if (routed) {
                                    provisioner.recordEndpointSuccess(
                                        candidate.endpoint,
                                        handshakeMs,
                                        validationMs,
                                    )
                                    verifiedRoutes += VerifiedWarpRoute(
                                        candidate,
                                        handshakeMs + validationMs,
                                    )
                                }
                            }
                            if (verifiedRoutes.none { it.candidate.endpoint == candidate.endpoint })
                                provisioner.recordEndpointFailure(candidate.endpoint)
                            tunnel.setStateAsync(Tunnel.State.DOWN)
                            // Android may need a short window to release the previous VPN network
                            // and UDP socket before the next endpoint is evaluated.
                            delay(CANDIDATE_SWITCH_DELAY_MS)
                            if (verifiedRoutes.size >= VERIFIED_ROUTES_TO_COMPARE ||
                                verifiedRoutes.isNotEmpty() && index + 1 >= MAX_DISCOVERY_ATTEMPTS)
                                break
                        }

                        // Reconnect the fastest fully verified route. If it changed underneath us,
                        // immediately fall through to the next verified route.
                        updateWarpStage(getString(R.string.warp_stage_selecting))
                        for (route in verifiedRoutes.sortedBy(VerifiedWarpRoute::qualityMs)) {
                            tunnel.setConfigAsync(route.candidate.config)
                            val attemptStartedAt = System.currentTimeMillis() / 1000L - 1L
                            tunnel.setStateAsync(Tunnel.State.UP)
                            if (awaitFreshHandshake(tunnel, attemptStartedAt, HANDSHAKE_WAIT_SECONDS)) {
                                delay(DATA_PATH_SETTLE_MS)
                                if (verifyWarpDataPath())
                                    return@runCatching tunnel to route.candidate.endpoint
                            }
                            provisioner.recordEndpointFailure(route.candidate.endpoint)
                            tunnel.setStateAsync(Tunnel.State.DOWN)
                            delay(CANDIDATE_SWITCH_DELAY_MS)
                        }
                        if (completedHandshake)
                            error("WARP handshake succeeded, but routed Internet verification failed")
                        error("WARP did not complete a handshake on any supported endpoint")
                    }.onSuccess { (tunnel, endpoint) ->
                        selectedTunnel = tunnel
                        updateWarpStage(
                            getString(R.string.warp_stage_connected, endpoint.authority),
                            autoHide = true,
                        )
                        showSnackbar(getString(R.string.warp_verified_connected, tunnel.name, endpoint.authority))
                    }.onFailure { error ->
                        Log.e(TAG, "Verified WARP profile creation failed", error)
                        createdTunnel?.let { tunnel ->
                            runCatching {
                                if (tunnel.state == Tunnel.State.UP) tunnel.setStateAsync(Tunnel.State.DOWN)
                                tunnel.deleteAsync()
                            }.onFailure { cleanupError ->
                                Log.e(TAG, "Could not remove failed WARP profile", cleanupError)
                            }
                        }
                        previouslyActive?.let { tunnel ->
                            runCatching { tunnel.setStateAsync(Tunnel.State.UP) }
                                .onFailure { restoreError -> Log.e(TAG, "Could not restore previous tunnel", restoreError) }
                        }
                        val reason = error.message?.takeIf { it.isNotBlank() } ?: ErrorMessages[error]
                        updateWarpStage(
                            getString(R.string.warp_stage_failed, reason),
                            autoHide = true,
                        )
                        showSnackbar(getString(R.string.warp_verified_error, reason))
                    }
                }
            } finally {
                binding?.optimizeWarpFab?.isEnabled = true
                setSmartConnectBusy(false)
                refreshSmartConnectUi()
            }
        }
    }

    private fun updateWarpStage(message: CharSequence, autoHide: Boolean = false) {
        warpStageHideJob?.cancel()
        binding?.apply {
            warpStatusCard.visibility = View.VISIBLE
            warpStatusText.text = message
            val stageInset = (76 * resources.displayMetrics.density).toInt()
            tunnelList.setPadding(
                tunnelList.paddingLeft,
                stageInset,
                tunnelList.paddingRight,
                tunnelList.paddingBottom,
            )
        }
        if (autoHide) {
            warpStageHideJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(STAGE_TERMINAL_VISIBILITY_MS)
                binding?.apply {
                    warpStatusCard.visibility = View.GONE
                    tunnelList.setPadding(
                        tunnelList.paddingLeft,
                        0,
                        tunnelList.paddingRight,
                        tunnelList.paddingBottom,
                    )
                }
            }
        }
    }

    private suspend fun awaitFreshHandshake(
        tunnel: ObservableTunnel,
        attemptStartedAt: Long,
        waitSeconds: Int,
    ): Boolean {
        repeat(waitSeconds) {
            delay(1_000L)
            val handshake = withContext(Dispatchers.IO) {
                runCatching { Application.getBackend().getLastHandshake(tunnel) }.getOrDefault(0L)
            }
            if (handshake >= attemptStartedAt) return true
        }
        return false
    }

    /** A handshake proves peer authentication; this additionally proves routed Internet access. */
    private suspend fun verifyWarpDataPath(): Boolean {
        var successes = 0
        repeat(DATA_PATH_ATTEMPTS) { attempt ->
            if (probeWarpDataPath()) successes++
            if (successes >= REQUIRED_DATA_PATH_SUCCESSES) return true
            val remainingAttempts = DATA_PATH_ATTEMPTS - attempt - 1
            if (successes + remainingAttempts < REQUIRED_DATA_PATH_SUCCESSES) return false
            if (attempt < DATA_PATH_ATTEMPTS - 1) delay(DATA_PATH_RETRY_DELAY_MS)
        }
        return false
    }

    private suspend fun probeWarpDataPath(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(WARP_TRACE_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = DATA_PATH_TIMEOUT_MS
                connection.readTimeout = DATA_PATH_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Connection", "close")
                if (connection.responseCode !in 200..299) return@runCatching false
                connection.inputStream.bufferedReader().use { reader ->
                    reader.lineSequence().any { line ->
                        line.equals("warp=on", ignoreCase = true) ||
                            line.equals("warp=plus", ignoreCase = true)
                    }
                }
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    private fun viewForTunnel(tunnel: ObservableTunnel, tunnels: List<*>): MultiselectableRelativeLayout? {
        return binding?.tunnelList?.findViewHolderForAdapterPosition(tunnels.indexOf(tunnel))?.itemView as? MultiselectableRelativeLayout
    }

    private data class VerifiedWarpRoute(
        val candidate: WarpProfileCandidate,
        val qualityMs: Long,
    )

    private inner class ActionModeListener : ActionMode.Callback {
        val checkedItems: MutableCollection<Int> = HashSet()
        private var resources: Resources? = null

        fun getCheckedItems(): ArrayList<Int> {
            return ArrayList(checkedItems)
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.menu_action_delete -> {
                    val activity = activity ?: return true
                    val copyCheckedItems = HashSet(checkedItems)
                    binding?.createFab?.apply {
                        visibility = View.VISIBLE
                        scaleX = 1f
                        scaleY = 1f
                    }
                    activity.lifecycleScope.launch {
                        try {
                            val tunnels = Application.getTunnelManager().getTunnels()
                            val tunnelsToDelete = ArrayList<ObservableTunnel>()
                            for (position in copyCheckedItems) tunnelsToDelete.add(tunnels[position])
                            val futures = tunnelsToDelete.map { async(SupervisorJob()) { it.deleteAsync() } }
                            onTunnelDeletionFinished(futures.awaitAll().size, null)
                        } catch (e: Throwable) {
                            onTunnelDeletionFinished(0, e)
                        }
                    }
                    checkedItems.clear()
                    mode.finish()
                    true
                }

                R.id.menu_action_select_all -> {
                    lifecycleScope.launch {
                        val tunnels = Application.getTunnelManager().getTunnels()
                        for (i in 0 until tunnels.size) {
                            setItemChecked(i, true)
                        }
                    }
                    true
                }

                else -> false
            }
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            actionMode = mode
            backPressedCallback?.isEnabled = true
            if (activity != null) {
                resources = activity!!.resources
            }
            animateFab(binding?.createFab, false)
            mode.menuInflater.inflate(R.menu.tunnel_list_action_mode, menu)
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            backPressedCallback?.isEnabled = false
            resources = null
            animateFab(binding?.createFab, true)
            checkedItems.clear()
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            updateTitle(mode)
            return false
        }

        fun setItemChecked(position: Int, checked: Boolean) {
            if (checked) {
                checkedItems.add(position)
            } else {
                checkedItems.remove(position)
            }
            val adapter = if (binding == null) null else binding!!.tunnelList.adapter
            if (actionMode == null && !checkedItems.isEmpty() && activity != null) {
                (activity as AppCompatActivity).startSupportActionMode(this)
            } else if (actionMode != null && checkedItems.isEmpty()) {
                actionMode!!.finish()
            }
            adapter?.notifyItemChanged(position)
            updateTitle(actionMode)
        }

        fun toggleItemChecked(position: Int) {
            setItemChecked(position, !checkedItems.contains(position))
        }

        private fun updateTitle(mode: ActionMode?) {
            if (mode == null) {
                return
            }
            val count = checkedItems.size
            if (count == 0) {
                mode.title = ""
            } else {
                mode.title = resources!!.getQuantityString(R.plurals.delete_title, count, count)
            }
        }

        private fun animateFab(view: View?, show: Boolean) {
            view ?: return
            val animation = AnimationUtils.loadAnimation(
                context, if (show) R.anim.scale_up else R.anim.scale_down
            )
            animation.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationRepeat(animation: Animation?) {
                }

                override fun onAnimationEnd(animation: Animation?) {
                    if (!show) view.visibility = View.GONE
                }

                override fun onAnimationStart(animation: Animation?) {
                    if (show) view.visibility = View.VISIBLE
                }
            })
            view.startAnimation(animation)
        }
    }

    companion object {
        private const val CHECKED_ITEMS = "CHECKED_ITEMS"
        private const val TAG = "AmneziaWG/TunnelListFragment"
        private const val WARP_TUNNEL_PREFIX = "WARP"
        private const val SMART_CONNECT_ROTATION_MS = 1_100L
        private const val HANDSHAKE_WAIT_SECONDS = 10
        private const val FAST_HANDSHAKE_WAIT_SECONDS = 6
        private const val LONG_HANDSHAKE_ATTEMPTS = 2
        private const val VERIFIED_ROUTES_TO_COMPARE = 2
        private const val MAX_DISCOVERY_ATTEMPTS = 4
        private const val WARP_TRACE_URL = "https://connectivity.cloudflareclient.com/cdn-cgi/trace"
        private const val DATA_PATH_TIMEOUT_MS = 6_000
        private const val DATA_PATH_SETTLE_MS = 500L
        private const val STAGE_TERMINAL_VISIBILITY_MS = 6_000L
        private const val DATA_PATH_ATTEMPTS = 3
        private const val REQUIRED_DATA_PATH_SUCCESSES = 2
        private const val DATA_PATH_RETRY_DELAY_MS = 750L
        private const val CANDIDATE_SWITCH_DELAY_MS = 500L
    }
}
