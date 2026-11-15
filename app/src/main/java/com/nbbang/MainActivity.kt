package com.nbbang

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.doOnLayout
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nbbang.ui.driving.DrivingOverlay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.nbbang.domain.Gear
import com.nbbang.platform.LedgerStore
import com.nbbang.platform.PleosLocator
import com.nbbang.platform.RideService
import com.nbbang.ui.RideApp
import com.nbbang.ui.RideTheme
import com.nbbang.ui.showIntroOnLaunch

class MainActivity : ComponentActivity() {
    private val viewModel: RideViewModel get() = (application as RideApplication).viewModel
    private var openedFromDrivingPopup by mutableStateOf(false)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) viewModel.onPermissionsGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleLaunch(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.ui.first { it.requiresDrivingPopup() }
                openDrivingPopupIfNeeded()
            }
        }
        setContent {
            val state by viewModel.ui.collectAsStateWithLifecycle()
            RideTheme {
                RideApp(state, viewModel, allowDrivingHome = openedFromDrivingPopup)
            }
        }
        // 시작 인트로(2초). 런처에서 새로 열 때만, 달리는 중에는 건너뛴다.
        showIntroOnLaunch(savedInstanceState, skip = viewModel.ui.value.let { it.restricted || it.car.moving || it.car.gear == Gear.DRIVE || it.car.gear == Gear.REVERSE })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
        openDrivingPopupIfNeeded()
    }

    private fun handleLaunch(intent: Intent) {
        openedFromDrivingPopup = intent.action == ACTION_OPEN_DRIVING_STATUS
        if (openedFromDrivingPopup) viewModel.openDrivingStatus()
    }

    private fun openDrivingPopupIfNeeded() {
        window.decorView.doOnLayout {
            if (!isFinishing && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                viewModel.ui.value.requiresDrivingPopup() &&
                DrivingOverlay.open(this, requestPermission = false)
            ) finish()
        }
    }

    // 정차 중에도 D/R단에서는 팝업을 연다. P단에서는 도착 직후의 정산 화면을 유지한다.
    private fun UiState.requiresDrivingPopup(): Boolean =
        !(openedFromDrivingPopup && (screen == Screen.DrivingStatus || screen == Screen.Home)) &&
            car.gear != Gear.PARK && (car.gear == Gear.DRIVE || car.gear == Gear.REVERSE || car.moving || restricted)

    override fun onStart() {
        super.onStart()
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        RideService.start(this)
        askLocationOnce()
    }

    /** 위치는 처음 한 번만 묻는다. 거절해도 기록과 정산은 그대로 된다(이름 짐작만 덜 정확해진다). */
    private fun askLocationOnce() {
        val store = LedgerStore(this)
        if (store.locationAsked) return
        store.locationAsked = true
        if (checkSelfPermission(PleosLocator.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            askPermissions.launch(arrayOf(PleosLocator.PERMISSION))
        }
    }

    companion object {
        internal const val ACTION_OPEN_DRIVING_STATUS = "com.nbbang.OPEN_DRIVING_STATUS"
    }
}
