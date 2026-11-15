package com.nbbang.platform

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 플랫폼의 주행 규제 상태. 차속이나 기어가 아니라 이 값으로 복잡한 화면을 막는다. */
class DrivingRestriction(private val context: Context) {
    private val state = MutableStateFlow(false)
    val restricted: StateFlow<Boolean> = state

    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    fun start() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        runCatching {
            val connected = Car.createCar(context)
            val restrictions = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            state.value = restrictions.currentCarUxRestrictions.isRequiresDistractionOptimization
            restrictions.registerListener { state.value = it.isRequiresDistractionOptimization }
            car = connected
            manager = restrictions
        }.onFailure { Log.w(TAG, "주행 규제 상태를 받지 못했어요", it) }
    }

    fun stop() {
        runCatching { manager?.unregisterListener() }
        car?.disconnect()
        car = null
        manager = null
    }

    private companion object {
        const val TAG = "RideRestriction"
    }
}
