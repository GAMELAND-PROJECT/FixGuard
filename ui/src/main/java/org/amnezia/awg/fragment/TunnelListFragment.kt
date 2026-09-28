/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.fragment

import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Resources
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

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
    private var connectionTimerJob: Job? = null
    private var connectionStartTime: Long = 0L
    private var smartConnectAnimator: ObjectAnimator? = null
    private var smartConnectHaloAnimator: AnimatorSet? = null
    private var smartConnectHaloPulseAnimator: AnimatorSet? = null
    private var smartConnectHaloPulse2Animator: AnimatorSet? = null
    private var outerBezelAnimator: ObjectAnimator? = null
    private var innerRingAnimator: ObjectAnimator? = null
    private var buttonColorAnimator: ValueAnimator? = null
    private var lastButtonColor: Int = 0xFF1F6FEB.toInt()
    private var isSmartConnecting = false
    private var pendingSmartConnectTunnel: ObservableTunnel? = null

    private inline fun safeViewScope(crossinline block: suspend CoroutineScope.() -> Unit): Job? {
        if (!isAdded || view == null) return null
        val scope = viewLifecycleOwnerLiveData.value?.lifecycleScope ?: return null
        return scope.launch { block() }
    }

    private val warpVpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pendingTunnel = pendingSmartConnectTunnel
        pendingSmartConnectTunnel = null
        if (result.resultCode == Activity.RESULT_OK) {
            if (pendingTunnel != null) {
                safeViewScope { connectReusableWarpTunnel(pendingTunnel) }
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
            setupVipCard()
            setupTelemetryCard()
            setupButtonSpringPhysics()
            smartConnectButton.icon = null
            smartConnectButton.setIconResource(0)
            stopCyberGyroscopicRotation()
            smartConnectButton.setOnClickListener { onSmartConnectClicked() }
            optimizeWarpFab.setOnClickListener { prepareVerifiedWarpProfile() }
            executePendingBindings()
        }
        backPressedCallback = requireActivity().onBackPressedDispatcher.addCallback(this) { actionMode?.finish() }
        backPressedCallback?.isEnabled = false

        return binding?.root
    }

    override fun onDestroyView() {
        connectionTimerJob?.cancel()
        connectionTimerJob = null
        smartConnectJob?.cancel()
        warpStageHideJob?.cancel()
        stopSmartConnectHaloPulse()
        stopCyberGyroscopicRotation()
        stopSmartConnectAnimation()
        buttonColorAnimator?.cancel()
        buttonColorAnimator = null
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

    override fun onResume() {
        super.onResume()
        refreshSmartConnectUi()
        safeViewScope {
            val allTunnels = Application.getTunnelManager().getTunnels()
            val filtered = ObservableSortedKeyedArrayList<String, ObservableTunnel>(TunnelComparator)
            for (t in allTunnels) {
                if (!isWarpProfile(t)) filtered.add(t)
            }
            binding?.tunnels = filtered
        }
    }

    private fun showSnackbar(message: CharSequence) {
        val binding = binding
        if (binding != null) {
            val snackbar = Snackbar.make(binding.mainContainer, message, Snackbar.LENGTH_LONG)
            runCatching {
                snackbar.setBackgroundTint(0xFF161B22.toInt())
                snackbar.setTextColor(0xFFFFFFFF.toInt())
                val sbView = snackbar.view
                sbView.setBackgroundResource(R.drawable.bg_cyber_snackbar)
                sbView.backgroundTintList = ColorStateList.valueOf(0xFF161B22.toInt())
                val textView = sbView.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text)
                textView?.setTextColor(0xFFFFFFFF.toInt())
                textView?.textSize = 13.5f
                textView?.typeface = android.graphics.Typeface.DEFAULT_BOLD
                textView?.textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            }
            snackbar.show()
        } else {
            Toast.makeText(activity ?: Application.get(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun warmUpWarpIdentities() {
        lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            runCatching { WarpProvisioner(ctx).ensureIdentityPool() }
                .onFailure { error -> Log.w(TAG, "WARP identity warm-up did not finish", error) }
        }
    }

    /** Dedicated ZUN & WARP profiles are managed by the central connect button and stay hidden from the list. */
    private fun isWarpProfile(tunnel: ObservableTunnel): Boolean =
        tunnel.name.startsWith(WARP_TUNNEL_PREFIX) || tunnel.name.startsWith("ZUN-")

    private fun cancelSmartConnectSearch() {
        smartConnectJob?.cancel()
        smartConnectJob = null
        isSmartConnecting = false
        setSmartConnectBusy(false)
        stopCyberGyroscopicRotation()
        safeViewScope {
            val manager = Application.getTunnelManager()
            val tunnels = manager.getTunnels()
            val active = tunnels.firstOrNull { it.state == Tunnel.State.UP }
            active?.setStateAsync(Tunnel.State.DOWN)
            refreshSmartConnectUi()
        }
        updateWarpStage("پویش سرورها توسط کاربر متوقف شد", autoHide = true)
        showSnackbar("عملیات جستجوی سرور متوقف گردید")
    }

    private fun onSmartConnectClicked() {
        binding?.smartConnectButton?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        if (isSmartConnecting || smartConnectJob?.isActive == true) {
            cancelSmartConnectSearch()
            return
        }
        smartConnectJob = safeViewScope {
            val manager = Application.getTunnelManager()
            val tunnels = manager.getTunnels()
            val active = tunnels.firstOrNull { it.state == Tunnel.State.UP }
            if (active != null) {
                setSmartConnectBusy(true, getString(R.string.smart_connect_disconnecting), showDotAsConnecting = true)
                runCatching { active.setStateAsync(Tunnel.State.DOWN) }
                    .onFailure { error -> showSnackbar(getString(R.string.error_down, ErrorMessages[error])) }
                setSmartConnectBusy(false)
                refreshSmartConnectUi()
                return@safeViewScope
            }

            val reusable = tunnels.firstOrNull { isWarpProfile(it) }
            if (reusable == null) {
                setSmartConnectBusy(true, getString(R.string.smart_connect_preparing), showDotAsConnecting = true)
                prepareVerifiedWarpProfile()
                return@safeViewScope
            }

            try {
                if (Application.getBackend() is GoBackend) {
                    val intent = GoBackend.VpnService.prepare(requireActivity())
                    if (intent != null) {
                        pendingSmartConnectTunnel = reusable
                        warpVpnPermissionLauncher.launch(intent)
                        return@safeViewScope
                    }
                }
                connectReusableWarpTunnel(reusable)
            } finally {
                setSmartConnectBusy(false)
                refreshSmartConnectUi()
            }
        }
    }

    private suspend fun connectReusableWarpTunnel(tunnel: ObservableTunnel) {
        setSmartConnectBusy(true, getString(R.string.smart_connect_connecting), showDotAsConnecting = true)
        updateWarpStage(getString(R.string.warp_stage_preparing))
        runCatching { tunnel.setStateAsync(Tunnel.State.UP) }
            .onSuccess {
                selectedTunnel = tunnel
                setSmartConnectBusy(false)
                updateWarpStage(getString(R.string.smart_connect_connected), autoHide = true)
                refreshSmartConnectUi()
            }
            .onFailure { error ->
                setSmartConnectBusy(false)
                updateWarpStage(getString(R.string.warp_stage_failed, ErrorMessages[error]), autoHide = true)
                showSnackbar(getString(R.string.error_up, ErrorMessages[error]))
                refreshSmartConnectUi()
            }
    }

    private fun animateButtonColor(targetColor: Int) {
        val button = binding?.smartConnectButton ?: return
        if (lastButtonColor == targetColor) return
        buttonColorAnimator?.cancel()
        val startColor = lastButtonColor
        buttonColorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), startColor, targetColor).apply {
            duration = 420L
            addUpdateListener { animator ->
                val c = animator.animatedValue as Int
                button.backgroundTintList = ColorStateList.valueOf(c)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    lastButtonColor = targetColor
                }
            })
            start()
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupButtonSpringPhysics() {
        val button = binding?.smartConnectButton ?: return
        val innerRing = binding?.smartConnectInnerRing
        val icon = binding?.smartConnectIcon
        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(0.92f)
                        .scaleY(0.92f)
                        .setDuration(120L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                    innerRing?.animate()
                        ?.scaleX(0.92f)
                        ?.scaleY(0.92f)
                        ?.setDuration(120L)
                        ?.setInterpolator(DecelerateInterpolator())
                        ?.start()
                    icon?.animate()
                        ?.scaleX(0.92f)
                        ?.scaleY(0.92f)
                        ?.setDuration(120L)
                        ?.setInterpolator(DecelerateInterpolator())
                        ?.start()
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(320L)
                        .setInterpolator(OvershootInterpolator(2.5f))
                        .start()
                    innerRing?.animate()
                        ?.scaleX(1.0f)
                        ?.scaleY(1.0f)
                        ?.setDuration(320L)
                        ?.setInterpolator(OvershootInterpolator(2.5f))
                        ?.start()
                    icon?.animate()
                        ?.scaleX(1.0f)
                        ?.scaleY(1.0f)
                        ?.setDuration(320L)
                        ?.setInterpolator(OvershootInterpolator(2.5f))
                        ?.start()
                }
            }
            false
        }
    }

    private fun setupVipCard() {
        val card = binding?.vipProfileCard ?: return
        card.alpha = 0f
        card.translationY = -60f
        card.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(550L)
            .setInterpolator(DecelerateInterpolator(1.8f))
            .start()

        card.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showSnackbar("اشتراک ویژه شما فعال و متصل به شبکه پرسرعت است")
        }
    }

    private fun setupTelemetryCard() {
        val card = binding?.connectionTelemetryCard ?: return
        card.alpha = 0f
        card.translationY = 50f
        card.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(550L)
            .setInterpolator(DecelerateInterpolator(1.8f))
            .start()

        card.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showSnackbar("سامانه امنیت کوانتومی و حفاظت نشت DNS فعال است")
        }
    }

    private fun refreshSmartConnectUi() {
        val currentBinding = binding ?: return
        safeViewScope {
            if (isSmartConnecting) return@safeViewScope
            val active = Application.getTunnelManager().getTunnels().firstOrNull { it.state == Tunnel.State.UP }
            if (active != null) {
                currentBinding.smartConnectButton.icon = null
                currentBinding.smartConnectButton.setIconResource(0)
                currentBinding.smartConnectButton.text = ""
                currentBinding.smartConnectButton.contentDescription = getString(R.string.smart_disconnect)
                currentBinding.telemetryCaption.text = "ارتباط پایدار و کاملاً امن برقرار است"
                currentBinding.telemetryLiveRow.visibility = View.VISIBLE
                animateButtonColor(0xFFDC2626.toInt())
                currentBinding.smartConnectIcon.setImageResource(R.drawable.ic_vpn_power)
                currentBinding.smartConnectIcon.imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                currentBinding.statusPill.setBackgroundResource(R.drawable.bg_status_pill_connected)
                currentBinding.statusDot.setBackgroundResource(R.drawable.bg_status_dot_connected)
                currentBinding.statusPillText.setText(R.string.smart_status_connected)
                currentBinding.statusPillText.setTextColor(0xFF3FB950.toInt())
                startSmartConnectHaloPulse()
                startCyberGyroscopicRotation(isFast = false)

                // Live Security & Connection Metrics
                currentBinding.metricDnsText.text = "۱۰۰٪ مسدود و امن"
                currentBinding.metricDnsText.setTextColor(0xFF3FB950.toInt())
                currentBinding.metricRouteText.text = "مسیر اختصاصی بهینه"
                currentBinding.metricShieldText.text = "هوشمند فعال"
                currentBinding.metricShieldText.setTextColor(0xFF3FB950.toInt())

                if (connectionStartTime == 0L) {
                    connectionStartTime = SystemClock.elapsedRealtime()
                }
                if (connectionTimerJob == null || connectionTimerJob?.isActive != true) {
                    connectionTimerJob = safeViewScope {
                        while (isActive) {
                            val elapsedMillis = SystemClock.elapsedRealtime() - connectionStartTime
                            val totalSeconds = (elapsedMillis / 1000).coerceAtLeast(0)
                            val hours = totalSeconds / 3600
                            val minutes = (totalSeconds % 3600) / 60
                            val seconds = totalSeconds % 60
                            val formattedTime = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
                            binding?.metricTimerText?.text = formattedTime
                            delay(1000L)
                        }
                    }
                }
            } else {
                currentBinding.smartConnectButton.icon = null
                currentBinding.smartConnectButton.setIconResource(0)
                currentBinding.smartConnectIcon.setImageResource(R.drawable.ic_vpn_power)
                currentBinding.smartConnectButton.contentDescription = getString(R.string.smart_connect)
                currentBinding.telemetryCaption.setText(R.string.smart_connect_ready)
                currentBinding.telemetryLiveRow.visibility = View.GONE
                animateButtonColor(0xFF1F6FEB.toInt())
                currentBinding.smartConnectIcon.imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                currentBinding.statusPill.setBackgroundResource(R.drawable.bg_status_pill)
                currentBinding.statusDot.setBackgroundResource(R.drawable.bg_status_dot_disconnected)
                currentBinding.statusPillText.setText(R.string.smart_status_disconnected)
                currentBinding.statusPillText.setTextColor(0xFFE6EDF3.toInt())
                stopSmartConnectHaloPulse()
                stopCyberGyroscopicRotation()

                // Reset Live Security & Connection Metrics
                connectionTimerJob?.cancel()
                connectionTimerJob = null
                connectionStartTime = 0L
                currentBinding.metricTimerText.text = "--:--:--"
                currentBinding.metricDnsText.text = "آماده‌باش"
                currentBinding.metricDnsText.setTextColor(0xFF8B949E.toInt())
                currentBinding.metricRouteText.text = "مسیر خودکار"
                currentBinding.metricShieldText.text = "آماده‌باش"
                currentBinding.metricShieldText.setTextColor(0xFF8B949E.toInt())
            }
            currentBinding.smartConnectProgress.visibility = View.GONE
            // CRITICAL: Re-bind click listener to recover from cases where the button lost its handler
            currentBinding.smartConnectButton.setOnClickListener { onSmartConnectClicked() }
            currentBinding.smartConnectButton.isEnabled = true
            stopSmartConnectAnimation()
        }
    }

    private fun setSmartConnectBusy(busy: Boolean, caption: CharSequence? = null, showDotAsConnecting: Boolean = true) {
        isSmartConnecting = busy
        binding?.apply {
            smartConnectButton.isEnabled = true // Always allow clicks so user can tap to stop
            smartConnectButton.icon = null
            smartConnectButton.setIconResource(0)
            smartConnectIcon.setImageResource(R.drawable.ic_vpn_power)
            smartConnectButton.contentDescription = getString(if (busy) R.string.smart_connecting else R.string.smart_connect)
            caption?.let { telemetryCaption.text = it } ?: run {
                telemetryCaption.setText(if (busy) R.string.smart_connecting else R.string.smart_connect_ready)
            }
            if (busy) {
                telemetryLiveRow.visibility = View.GONE
                if (showDotAsConnecting) {
                    statusPill.setBackgroundResource(R.drawable.bg_status_pill_connecting)
                    statusDot.setBackgroundResource(R.drawable.bg_status_dot_connecting)
                    statusPillText.setText(R.string.smart_connecting)
                    statusPillText.setTextColor(0xFFF59E0B.toInt())
                }
                animateButtonColor(0xFFD97706.toInt())
                smartConnectProgress.visibility = View.VISIBLE
                startSmartConnectAnimation()
                stopSmartConnectHaloPulse()
                startCyberGyroscopicRotation(isFast = true)
                smartConnectHalo.animate().alpha(0.28f).setDuration(200L).start()
            } else {
                smartConnectProgress.visibility = View.GONE
                stopSmartConnectAnimation()
                safeViewScope {
                    val active = Application.getTunnelManager().getTunnels().firstOrNull { it.state == Tunnel.State.UP }
                    if (active != null) {
                        startCyberGyroscopicRotation(isFast = false)
                    } else {
                        stopCyberGyroscopicRotation()
                    }
                } ?: stopCyberGyroscopicRotation()
            }
        }
    }

    private fun startSmartConnectHaloPulse() {
        val halo = binding?.smartConnectHalo ?: return
        val pulse1 = binding?.smartConnectHaloPulse ?: return
        val pulse2 = binding?.smartConnectHaloPulse2 ?: return
        if (smartConnectHaloAnimator != null) return

        // 1. Inner breathing halo (subtle glow expansion)
        val alphaAnim = ObjectAnimator.ofFloat(halo, View.ALPHA, 0.18f, 0.48f)
        val scaleXAnim = ObjectAnimator.ofFloat(halo, View.SCALE_X, 1.0f, 1.08f)
        val scaleYAnim = ObjectAnimator.ofFloat(halo, View.SCALE_Y, 1.0f, 1.08f)
        smartConnectHaloAnimator = AnimatorSet().apply {
            playTogether(alphaAnim, scaleXAnim, scaleYAnim)
            duration = 1_800L
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                var isReverse = false
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (binding?.smartConnectHalo != null && smartConnectHaloAnimator != null) {
                        isReverse = !isReverse
                        alphaAnim.setFloatValues(if (isReverse) 0.48f else 0.18f, if (isReverse) 0.18f else 0.48f)
                        scaleXAnim.setFloatValues(if (isReverse) 1.08f else 1.0f, if (isReverse) 1.0f else 1.08f)
                        scaleYAnim.setFloatValues(if (isReverse) 1.08f else 1.0f, if (isReverse) 1.0f else 1.08f)
                        start()
                    }
                }
            })
            start()
        }

        // 2. Primary outer pulse wave radiating outwards
        pulse1.visibility = View.VISIBLE
        val pulse1Alpha = ObjectAnimator.ofFloat(pulse1, View.ALPHA, 0.52f, 0.0f).apply {
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }
        val pulse1ScaleX = ObjectAnimator.ofFloat(pulse1, View.SCALE_X, 1.0f, 1.48f).apply {
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }
        val pulse1ScaleY = ObjectAnimator.ofFloat(pulse1, View.SCALE_Y, 1.0f, 1.48f).apply {
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }
        smartConnectHaloPulseAnimator = AnimatorSet().apply {
            playTogether(pulse1Alpha, pulse1ScaleX, pulse1ScaleY)
            duration = 2_200L
            interpolator = DecelerateInterpolator(1.2f)
            start()
        }

        // 3. Staggered secondary pulse wave radiating outwards
        pulse2.visibility = View.VISIBLE
        val pulse2Alpha = ObjectAnimator.ofFloat(pulse2, View.ALPHA, 0.52f, 0.0f).apply {
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }
        val pulse2ScaleX = ObjectAnimator.ofFloat(pulse2, View.SCALE_X, 1.0f, 1.48f).apply {
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }
        val pulse2ScaleY = ObjectAnimator.ofFloat(pulse2, View.SCALE_Y, 1.0f, 1.48f).apply {
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }
        smartConnectHaloPulse2Animator = AnimatorSet().apply {
            playTogether(pulse2Alpha, pulse2ScaleX, pulse2ScaleY)
            duration = 2_200L
            startDelay = 1_100L
            interpolator = DecelerateInterpolator(1.2f)
            start()
        }
    }

    private fun stopSmartConnectHaloPulse() {
        smartConnectHaloAnimator?.cancel()
        smartConnectHaloAnimator = null
        smartConnectHaloPulseAnimator?.cancel()
        smartConnectHaloPulseAnimator = null
        smartConnectHaloPulse2Animator?.cancel()
        smartConnectHaloPulse2Animator = null
        binding?.smartConnectHalo?.apply {
            scaleX = 1.0f
            scaleY = 1.0f
            alpha = 0.18f
        }
        binding?.smartConnectHaloPulse?.apply {
            scaleX = 1.0f
            scaleY = 1.0f
            alpha = 0.0f
            visibility = View.GONE
        }
        binding?.smartConnectHaloPulse2?.apply {
            scaleX = 1.0f
            scaleY = 1.0f
            alpha = 0.0f
            visibility = View.GONE
        }
    }

    private fun startCyberGyroscopicRotation(isFast: Boolean = false) {
        val bezel = binding?.smartConnectBezel ?: return
        val innerRing = binding?.smartConnectInnerRing ?: return
        val outerDuration = if (isFast) 4_500L else 22_000L
        val innerDuration = if (isFast) 3_500L else 16_000L

        if (outerBezelAnimator == null) {
            outerBezelAnimator = ObjectAnimator.ofFloat(bezel, View.ROTATION, 0f, 360f).apply {
                duration = outerDuration
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        } else {
            outerBezelAnimator?.duration = outerDuration
            if (outerBezelAnimator?.isStarted != true) outerBezelAnimator?.start()
        }

        if (innerRingAnimator == null) {
            innerRingAnimator = ObjectAnimator.ofFloat(innerRing, View.ROTATION, 360f, 0f).apply {
                duration = innerDuration
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        } else {
            innerRingAnimator?.duration = innerDuration
            if (innerRingAnimator?.isStarted != true) innerRingAnimator?.start()
        }
    }

    private fun stopCyberGyroscopicRotation() {
        outerBezelAnimator?.cancel()
        outerBezelAnimator = null
        innerRingAnimator?.cancel()
        innerRingAnimator = null
        binding?.smartConnectBezel?.animate()?.rotation(0f)?.setDuration(350L)?.start()
        binding?.smartConnectInnerRing?.animate()?.rotation(0f)?.setDuration(350L)?.start()
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
        safeViewScope {
            try {
                if (Application.getBackend() is GoBackend) {
                    val intent = GoBackend.VpnService.prepare(activity)
                    if (intent != null) {
                        warpVpnPermissionLauncher.launch(intent)
                        return@safeViewScope
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
        setSmartConnectBusy(true, getString(R.string.smart_connect_connecting))
        updateWarpStage("در حال بررسی و اعتبارسنجی حساب کاربری امن...")
        showSnackbar("آغاز پویش پیوسته سرورها تا برقراری اتصال...")
        smartConnectJob = safeViewScope {
            var createdTunnel: ObservableTunnel? = null
            var previouslyActive: ObservableTunnel? = null
            val manager = Application.getTunnelManager()
            try {
                manager.withAutomaticRecoveryPaused {
                    runCatching {
                        val provisioner = WarpProvisioner(requireContext())
                        val identity = provisioner.ensureVerifiedIdentity()
                        updateWarpStage("حساب کاربری امن تایید شد • آغاز پویش پیوسته سرورها")

                        val tunnels = manager.getTunnels()
                        previouslyActive = tunnels.firstOrNull { it.state == Tunnel.State.UP }

                        var name = "ZUN-VIP"
                        var suffix = 2
                        while (tunnels.containsKey(name)) name = "ZUN-VIP-${suffix++}"
                        
                        val initialCandidate = provisioner.getCandidateAt(identity, 0)
                        val tunnel = manager.create(name, initialCandidate.config)
                        createdTunnel = tunnel
                        refreshSmartConnectUi()
                        var completedHandshake = false

                        var candidateIndex = 0
                        while (isActive) {
                            val candidate = provisioner.getCandidateAt(identity, candidateIndex++)
                            val testNumber = candidateIndex
                            val scanningText = "پویش پیوسته: تست #$testNumber (${candidate.endpoint.authority}) • برای لغو لمس کنید"
                            updateWarpStage(scanningText)
                            currentBinding.telemetryCaption.text = scanningText
                            Log.i(TAG, "Testing candidate #$testNumber: ${candidate.endpoint.authority}")

                            tunnel.setConfigAsync(candidate.config)
                            val attemptStartedAt = System.currentTimeMillis() / 1000L - 1L
                            val attemptStartedElapsed = SystemClock.elapsedRealtime()
                            tunnel.setStateAsync(Tunnel.State.UP)

                            updateWarpStage("ارسال بسته‌های Handshake به ${candidate.endpoint.authority}...")
                            val handshaked = awaitFreshHandshake(
                                tunnel,
                                attemptStartedAt,
                                HANDSHAKE_WAIT_SECONDS,
                            )
                            val handshakeMs = SystemClock.elapsedRealtime() - attemptStartedElapsed
                            completedHandshake = completedHandshake || handshaked
                            Log.i(TAG, "Candidate #$testNumber handshake ${if (handshaked) "succeeded" else "timed out"}")

                            if (handshaked) {
                                updateWarpStage("دست‌تکانی موفق (#$testNumber) • در حال اعتبارسنجی ترافیک...")
                                delay(DATA_PATH_SETTLE_MS)
                                val validationStartedElapsed = SystemClock.elapsedRealtime()
                                val routed = verifyWarpDataPath()
                                val validationMs = SystemClock.elapsedRealtime() - validationStartedElapsed
                                Log.i(TAG, "Candidate #$testNumber data path ${if (routed) "verified" else "failed"}")
                                if (routed) {
                                    provisioner.recordEndpointSuccess(
                                        candidate.endpoint,
                                        handshakeMs,
                                        validationMs,
                                    )
                                    // INSTANT-LOCK: Successfully connected and verified!
                                    return@runCatching tunnel to candidate.endpoint
                                }
                            }

                            provisioner.recordEndpointFailure(candidate.endpoint)
                            tunnel.setStateAsync(Tunnel.State.DOWN)
                            delay(CANDIDATE_SWITCH_DELAY_MS)
                        }

                        if (!isActive) {
                            error("عملیات پویش توسط کاربر متوقف شد")
                        }
                        if (completedHandshake) {
                            error("دست‌تکانی برقرار شد اما اعتبارسنجی ترافیک اینترنت تایید نشد")
                        }
                        error("هیچ سرور سالمی پاسخ نداد")
                    }.onSuccess { (tunnel, endpoint) ->
                        // Keep WARP profiles hidden — don't navigate to TunnelDetailFragment
                        // Just update the smart connect button state and show success
                        setSmartConnectBusy(false)
                        refreshSmartConnectUi()
                        updateWarpStage(
                            getString(R.string.warp_stage_connected, endpoint.authority),
                            autoHide = true,
                        )
                        showSnackbar(getString(R.string.warp_verified_connected, tunnel.name, endpoint.authority))
                    }.onFailure { error ->
                        Log.e(TAG, "Verified WARP profile creation failed", error)
                        setSmartConnectBusy(false)
                        refreshSmartConnectUi()
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
                        if (error is kotlinx.coroutines.CancellationException || reason.contains("متوقف شد")) {
                            updateWarpStage("پویش سرورها توسط کاربر متوقف شد", autoHide = true)
                        } else {
                            updateWarpStage(
                                getString(R.string.warp_stage_failed, reason),
                                autoHide = true,
                            )
                            showSnackbar(getString(R.string.warp_verified_error, reason))
                        }
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
            telemetryCaption.animate().alpha(0.4f).setDuration(120L).withEndAction {
                telemetryCaption.text = message
                telemetryCaption.animate().alpha(1f).setDuration(180L).start()
            }.start()
        }
        if (autoHide) {
            warpStageHideJob = safeViewScope {
                delay(STAGE_TERMINAL_VISIBILITY_MS)
                binding?.apply {
                    val active = Application.getTunnelManager().getTunnels().firstOrNull { it.state == Tunnel.State.UP }
                    if (active != null) {
                        telemetryCaption.text = "ارتباط پایدار و کاملاً امن برقرار است"
                        telemetryLiveRow.visibility = View.VISIBLE
                        statusPill.setBackgroundResource(R.drawable.bg_status_pill_connected)
                        statusDot.setBackgroundResource(R.drawable.bg_status_dot_connected)
                        statusPillText.setText(R.string.smart_status_connected)
                        statusPillText.setTextColor(0xFF3FB950.toInt())
                    } else {
                        telemetryCaption.setText(R.string.smart_connect_ready)
                        telemetryLiveRow.visibility = View.GONE
                        statusPill.setBackgroundResource(R.drawable.bg_status_pill)
                        statusDot.setBackgroundResource(R.drawable.bg_status_dot_disconnected)
                        statusPillText.setText(R.string.smart_status_disconnected)
                        statusPillText.setTextColor(0xFFE6EDF3.toInt())
                    }
                }
            }
        }
    }

    private suspend fun awaitFreshHandshake(
        tunnel: ObservableTunnel,
        attemptStartedAt: Long,
        waitSeconds: Int,
    ): Boolean {
        val totalPolls = waitSeconds * 2
        repeat(totalPolls) {
            delay(500L)
            val handshake = withContext(Dispatchers.IO) {
                runCatching { Application.getBackend().getLastHandshake(tunnel) }.getOrDefault(0L)
            }
            if (handshake >= attemptStartedAt) return true
        }
        return false
    }

    /** A handshake proves peer authentication; this additionally proves routed Internet access. */
    private suspend fun verifyWarpDataPath(): Boolean {
        repeat(2) { attempt ->
            if (probeWarpDataPath()) return true
            if (attempt < 1) delay(250L)
        }
        return false
    }

    private suspend fun probeWarpDataPath(): Boolean = withContext(Dispatchers.IO) {
        probeTraceUrl(WARP_TRACE_URL) || probeTraceUrl(WARP_TRACE_FALLBACK_URL)
    }

    private fun probeTraceUrl(urlString: String): Boolean = runCatching {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = DATA_PATH_TIMEOUT_MS
            connection.readTimeout = DATA_PATH_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("User-Agent", "okhttp/3.12.1")
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
            mode.menuInflater.inflate(R.menu.tunnel_list_action_mode, menu)
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            backPressedCallback?.isEnabled = false
            resources = null
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
    }

    companion object {
        private const val CHECKED_ITEMS = "CHECKED_ITEMS"
        private const val TAG = "AmneziaWG/TunnelListFragment"
        private const val WARP_TUNNEL_PREFIX = "WARP"
        private const val SMART_CONNECT_ROTATION_MS = 1_100L
        private const val HANDSHAKE_WAIT_SECONDS = 3
        private const val FAST_HANDSHAKE_WAIT_SECONDS = 2
        private const val LONG_HANDSHAKE_ATTEMPTS = 2
        private const val MAX_DISCOVERY_ATTEMPTS = 6
        private const val WARP_TRACE_URL = "https://connectivity.cloudflareclient.com/cdn-cgi/trace"
        private const val WARP_TRACE_FALLBACK_URL = "https://1.1.1.1/cdn-cgi/trace"
        private const val DATA_PATH_TIMEOUT_MS = 2_800
        private const val DATA_PATH_SETTLE_MS = 250L
        private const val STAGE_TERMINAL_VISIBILITY_MS = 6_000L
        private const val CANDIDATE_SWITCH_DELAY_MS = 250L
    }
}
