// C-03 내비 위 미니 팝업. 기록 서비스와 창의 수명을 분리한다.
package com.nbbang.ui.driving

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.layout.Box
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nbbang.MainActivity
import com.nbbang.RideViewModel
import com.nbbang.domain.Gear
import com.nbbang.platform.RideService
import com.nbbang.ui.RideTheme

class DrivingOverlay(
    private val context: Context,
    private val vm: RideViewModel,
    showStatusInitially: Boolean = true,
) : SavedStateRegistryOwner {
    override val lifecycle = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = savedState.savedStateRegistry
    private val windows = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { close() }
    private var view: ComposeView? = null
    private var awaitingInput = false
    private var showStatus by mutableStateOf(showStatusInitially)
    val isShowing: Boolean get() = view != null

    fun requestStatus() {
        showStatus = true
        resetTimeout()
    }

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        lifecycle.currentState = Lifecycle.State.CREATED
    }

    fun show(x: Int, y: Int) {
        if (view != null || !Settings.canDrawOverlays(context)) return
        val content = ComposeView(context)
        content.setViewTreeLifecycleOwner(this)
        content.setViewTreeSavedStateRegistryOwner(this)
        content.setContent {
            val state by vm.ui.collectAsState()
            var showBoarding by remember { mutableStateOf(false) }
            val liveRide = state.live
            val rideId = liveRide?.id
            var hadLiveRide by remember { mutableStateOf(liveRide != null) }
            var alightingRiderId by remember(rideId) { mutableStateOf<Int?>(null) }
            var showCost by remember(rideId) { mutableStateOf(false) }
            var showTollNotice by remember(rideId) { mutableStateOf(false) }
            val toll = state.nextTollPopup
            val keepOpen = showBoarding || alightingRiderId != null || showCost || toll != null || showTollNotice
            LaunchedEffect(keepOpen) {
                awaitingInput = keepOpen
                resetTimeout()
            }
            val canType = showBoarding && state.car.gear == Gear.PARK && !state.restricted
            LaunchedEffect(canType) { setKeyboardEnabled(canType) }
            LaunchedEffect(rideId, showStatus) {
                if (rideId != null) hadLiveRide = true
                else if (hadLiveRide || !showStatus) close()
            }
            RideTheme {
                Box(Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            handler.removeCallbacks(timeout)
                            if (event.changes.none { it.pressed }) resetTimeout()
                        }
                    }
                }) {
                    val selectedRider = alightingRiderId
                    when {
                        showBoarding -> BoardingPopup(
                            state,
                            onCancel = { showBoarding = false },
                            onConfirm = { choices ->
                                vm.confirmBoarding(choices).also { problem ->
                                    if (problem == null) showBoarding = false
                                }
                            },
                        )
                        selectedRider != null -> AlightingPopup(
                            state,
                            initialRiderId = selectedRider,
                            onClose = { alightingRiderId = null },
                            onConfirm = { riderId ->
                                if (vm.ui.value.live?.id == rideId) vm.alight(riderId)
                                alightingRiderId = null
                            },
                        )
                        showCost && liveRide != null -> CostPopup(
                            ride = liveRide,
                            voiceInput = vm.costVoiceInput,
                            onRequestVoice = { vm.requestCostVoice(liveRide.id) },
                            onCancelVoice = { vm.cancelCostVoice(liveRide.id) },
                            onClose = { showCost = false },
                            onAdd = { name, won ->
                                if (vm.ui.value.live?.id == rideId) vm.addCost(null, name, won)
                                showCost = false
                            },
                        )
                        showTollNotice -> TollDeferredPopup(
                            pendingCount = state.live?.tollPasses?.count { it.won == null } ?: 0,
                            onDone = {
                                showTollNotice = false
                                closeIfIdle()
                            },
                        )
                        toll != null -> key(rideId, toll.index) {
                            val initialWon = remember {
                                state.live?.tollPasses?.lastOrNull { (it.won ?: 0) > 0 }?.won
                                    ?: state.rides.asReversed().firstNotNullOfOrNull { ride ->
                                        ride.tollPasses.lastOrNull { (it.won ?: 0) > 0 }?.won
                                    } ?: 0
                            }
                            TollPopup(toll.name, initialWon) { won, timedOut ->
                                val current = vm.ui.value
                                if (current.live?.id == rideId && current.nextTollPopup == toll) {
                                    showTollNotice = timedOut
                                    vm.enterToll(null, toll.index, won)
                                    if (!timedOut) closeIfIdle()
                                }
                            }
                        }
                        showStatus -> DrivingPopup(
                            state,
                            onOpenFullScreen = {
                                context.startActivity(Intent(context, MainActivity::class.java)
                                    .setAction(MainActivity.ACTION_OPEN_DRIVING_STATUS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                                close()
                            },
                            onClose = ::close,
                            onAddPassenger = { showBoarding = true },
                            onSelectPassenger = { alightingRiderId = it },
                            onAddCost = { showCost = true },
                            onFinish = {
                                val current = vm.ui.value
                                if (current.car.gear == Gear.PARK && !current.restricted && current.live != null) {
                                    // 여행은 T-03, 카풀은 K-04 주행 요약에서 종료를 확인한다.
                                    vm.endDrive()
                                    close()
                                    context.startActivity(Intent(context, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                                }
                            },
                        )
                    }
                }
            }
        }
        val density = context.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            (STATUS_WIDTH_DP * density).toInt(), (STATUS_HEIGHT_DP * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        view = content
        try {
            windows.addView(content, params)
            lifecycle.currentState = Lifecycle.State.RESUMED
            resetTimeout()
        } catch (_: WindowManager.BadTokenException) {
            close()
        } catch (_: SecurityException) {
            close()
        }
    }

    private fun resetTimeout() {
        handler.removeCallbacks(timeout)
        if (!awaitingInput) handler.postDelayed(timeout, 10_000L)
    }

    private fun closeIfIdle() {
        if (!showStatus && vm.ui.value.nextTollPopup == null) close()
    }

    private fun setKeyboardEnabled(enabled: Boolean) {
        val content = view ?: return
        val params = content.layoutParams as? WindowManager.LayoutParams ?: return
        if (!enabled) {
            context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(content.windowToken, 0)
            content.clearFocus()
        }
        params.flags = if (enabled) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        if (content.isAttachedToWindow) windows.updateViewLayout(content, params)
    }

    fun close() {
        handler.removeCallbacks(timeout)
        setKeyboardEnabled(false)
        view?.let {
            if (it.isAttachedToWindow) windows.removeView(it)
            it.disposeComposition()
        }
        view = null
        lifecycle.currentState = Lifecycle.State.DESTROYED
    }

    companion object {
        const val ACTION_SHOW = "com.nbbang.SHOW_DRIVING_POPUP"
        private const val STATUS_WIDTH_DP = 850
        private const val STATUS_HEIGHT_DP = 400

        fun open(context: Context, requestPermission: Boolean = true): Boolean {
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "주행 팝업을 보려면 주차 후 시스템 설정에서 다른 앱 위에 표시를 허용해 주세요.", Toast.LENGTH_LONG).show()
                if (requestPermission) context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                )
                return false
            }
            val (x, y) = position(context)
            context.startForegroundService(Intent(context, RideService::class.java).setAction(ACTION_SHOW)
                .putExtra("x", x).putExtra("y", y))
            return true
        }

        internal fun position(context: Context): Pair<Int, Int> {
            val decor = (context as? Activity)?.window?.decorView
            val position = IntArray(2)
            decor?.getLocationOnScreen(position)
            val density = context.resources.displayMetrics.density
            val manager = context.getSystemService(WindowManager::class.java)
            val displayWidth = if (Build.VERSION.SDK_INT >= 30) manager.maximumWindowMetrics.bounds.width()
                else context.resources.displayMetrics.widthPixels
            val contentWidth = decor?.width?.takeIf { it > 0 } ?: minOf(displayWidth, (1700 * density).toInt())
            // Pleos 런처가 우측 앱 영역에 태스크를 붙이기 전에는 화면 원점이 잠시 0으로 보고된다.
            val originX = if (position[0] == 0) (displayWidth - contentWidth).coerceAtLeast(0) else position[0]
            val x = originX + (contentWidth - STATUS_WIDTH_DP * density) / 2
            val y = position[1] + ((decor?.height ?: context.resources.displayMetrics.heightPixels) - STATUS_HEIGHT_DP * density) / 2
            return x.toInt().coerceAtLeast(0) to y.toInt().coerceAtLeast(0)
        }
    }
}
