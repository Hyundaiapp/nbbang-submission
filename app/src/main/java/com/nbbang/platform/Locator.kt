package com.nbbang.platform

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nbbang.domain.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 지금 위치. 타고 내린 곳의 좌표를 남겨, 같은 곳에서 매번 타는 사람의 이름을 짐작하는 데 쓴다. */
interface Locator {
    val here: StateFlow<GeoPoint?>
    fun start()
    fun stop()
}

class NoLocator : Locator {
    override val here: StateFlow<GeoPoint?> = MutableStateFlow(null)
    override fun start() = Unit
    override fun stop() = Unit
}

/**
 * Pleos FusedLocation 1.1.3. 권한(pleos.car.permission.FUSED_LOCATION, dangerous)이 없으면 아무것도 하지 않는다.
 * 좌표는 차 안에만, 약 10m 단위로 줄여 남긴다.
 */
@SuppressLint("MissingPermission")
class PleosLocator(private val context: Context) : Locator {
    private val fused = FusedLocation(context)
    private val point = MutableStateFlow<GeoPoint?>(null)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var started = false

    override val here: StateFlow<GeoPoint?> = point

    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            point.value = GeoPoint(round4(location.latitude), round4(location.longitude))
        }

        override fun onError(e: Exception) {
            Log.w(TAG, "위치를 받지 못했어요", e)
        }
    }

    override fun start() {
        if (started) return
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return
        runCatching { fused.initialize() }
            .onSuccess {
                started = true
                // 에뮬레이터 2.1.0에서 initialize 직후에 등록한 콜백은 위치를 받지 못했다(First100 실측). 잠깐 기다린다.
                handler.postDelayed({
                    runCatching {
                        fused.registerFusedLocationCallback(listener)
                        fused.getLastKnownLocation(listener)
                    }.onFailure { Log.w(TAG, "위치 업데이트를 받지 못했어요", it) }
                }, SUBSCRIBE_DELAY_MS)
            }
            .onFailure { Log.w(TAG, "위치 연결에 실패했어요", it) }
    }

    override fun stop() {
        handler.removeCallbacksAndMessages(null)
        runCatching {
            fused.unregisterFusedLocationCallback(listener)
            fused.release()
        }
        started = false
    }

    private fun round4(value: Double): Double = Math.round(value * 10_000) / 10_000.0

    companion object {
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
        private const val SUBSCRIBE_DELAY_MS = 1_500L
        private const val TAG = "RideLocation"
    }
}
