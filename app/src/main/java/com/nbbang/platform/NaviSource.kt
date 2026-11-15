package com.nbbang.platform

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.nbbang.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 내비에 걸린 경로. 통행료는 내비가 계산한 예상 요금이다. */
data class NaviRoute(val name: String?, val tollWon: Int)

interface NaviSource {
    val routes: Flow<NaviRoute>
    /** 통과한 톨게이트의 내비 안내 문구. 내비가 이름을 제공하지 않으면 빈 문자열이다. */
    val tollgates: Flow<String>
    val arrivals: Flow<Unit>
    /** 지금 위치의 주소. 타고 내린 곳을 영수증에 적는 데 쓴다. */
    val address: StateFlow<String?>
    fun start()
    fun stop()
    fun refreshAddress()
}

/** 내비가 없는 기기와 시연용. 값은 시연 도구가 넣는다. */
class QuietNavi : NaviSource {
    val routeFlow = MutableSharedFlow<NaviRoute>(extraBufferCapacity = 4)
    val tollgateFlow = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val arrivalFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    private val here = MutableStateFlow<String?>(null)
    override val routes: Flow<NaviRoute> = routeFlow
    override val tollgates: Flow<String> = tollgateFlow
    override val arrivals: Flow<Unit> = arrivalFlow
    override val address: StateFlow<String?> = here
    override fun start() = Unit
    override fun stop() = Unit
    override fun refreshAddress() = Unit
    fun setAddress(value: String?) {
        here.value = value
    }
}

/**
 * Pleos NaviHelper 3.0.5.
 * - onRouteStarted: 목적지 이름과 예상 통행료(tollFare). 운전자가 내비에서 바로 정한 경로도 온다(First100 실측).
 * - onTBTInfo: 첫 항목이 톨게이트(TURN_TOLL)로 가까워졌다가 다음 항목으로 바뀌면 톨게이트를 지난 것으로 본다.
 * - onDestinationArrived: 도착하면 정산 카드를 띄운다.
 * - getCurrentLocationInfo: 지금 주소. 타고 내릴 때와 주행 중 가끔 묻는다.
 */
@SuppressLint("MissingPermission")
class PleosNavi(context: Context) : NaviSource {
    private val navi = NaviHelper(context)
    private val routeFlow = MutableSharedFlow<NaviRoute>(extraBufferCapacity = 4)
    private val tollgateFlow = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val arrivalFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    private val here = MutableStateFlow<String?>(null)
    @Volatile private var nearbyToll: String? = null

    override val routes: Flow<NaviRoute> = routeFlow
    override val tollgates: Flow<String> = tollgateFlow
    override val arrivals: Flow<Unit> = arrivalFlow
    override val address: StateFlow<String?> = here

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            debugLog { "경로 '${routeStartInfo.name}' ${routeStartInfo.distance}m 통행료 ${routeStartInfo.tollFare}원" }
            nearbyToll = null
            routeFlow.tryEmit(NaviRoute(routeStartInfo.name.ifBlank { null }, routeStartInfo.tollFare))
        }

        override fun onRouteCancelled() {
            debugLog { "경로 취소" }
            nearbyToll = null
            routeFlow.tryEmit(NaviRoute(null, 0))
        }

        override fun onReRouteEnded() {
            nearbyToll = null
        }

        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            val next = tbtInfoList.firstOrNull() ?: return
            if (next.type == TurnType.TURN_TOLL) {
                if (next.distance <= TOLL_NEAR_M) {
                    if (nearbyToll == null) debugLog { "톨게이트 ${next.distance.toInt()}m 앞" }
                    nearbyToll = next.description.trim().ifBlank { nearbyToll.orEmpty() }
                }
                return
            }

            val passed = nearbyToll ?: return
            nearbyToll = null
            debugLog { "톨게이트 지남: ${passed.ifBlank { "이름 없음" }}" }
            tollgateFlow.tryEmit(passed)
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            debugLog { "도착 ${destinationArrivedInfo.distance}m" }
            nearbyToll = null
            arrivalFlow.tryEmit(Unit)
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val address = currentLocationInfo.address.trim().ifBlank { null }
            if (address != here.value) debugLog { "주소 $address" }
            here.value = address
        }

        override fun onError(naviErrorCode: NaviErrorCode) {
            Log.w(TAG, "내비 오류: $naviErrorCode")
        }
    }

    override fun start() {
        runCatching {
            navi.initialize()
            navi.addListener(listener)
        }.onFailure { Log.w(TAG, "내비 연결에 실패했어요", it) }
    }

    override fun stop() {
        runCatching {
            navi.removeListener(listener)
            navi.release()
        }.onFailure { Log.w(TAG, "내비 해제에 실패했어요", it) }
    }

    override fun refreshAddress() {
        runCatching { navi.getCurrentLocationInfo() }.onFailure { Log.w(TAG, "지금 위치를 묻지 못했어요", it) }
    }

    internal fun debugTollgate(name: String) {
        if (!BuildConfig.DEBUG) return
        listener.onTBTInfo(listOf(TBTInfo(TurnType.TURN_TOLL, 100.0, name)))
        listener.onTBTInfo(listOf(TBTInfo(TurnType.TURN_STRAIGHT, 500.0, "")))
    }

    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message())
    }

    private companion object {
        const val TAG = "RideNavi"
        const val TOLL_NEAR_M = 300.0
    }
}
