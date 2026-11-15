package com.nbbang.platform

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryEfficiencyListener
import ai.pleos.playground.vehicle.listener.EvBatteryLevelListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelEconomyListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.nbbang.BuildConfig
import com.nbbang.domain.CarState
import com.nbbang.domain.Door
import com.nbbang.domain.Gear
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

interface CarSource {
    val state: StateFlow<CarState>
    fun start()
    fun stop()
}

/** 차가 아닌 기기(휴대폰, 단위 테스트)에서 쓰는 가짜 차. 시연 패널로 값을 바꾼다. */
class ManualCar(initial: CarState = CarState(gear = Gear.PARK, odometerKm = 12_480.0, batteryKwh = 52.0, socPercent = 67)) : CarSource {
    private val flow = MutableStateFlow(initial)
    override val state: StateFlow<CarState> = flow
    override fun start() = Unit
    override fun stop() = Unit
    fun update(transform: (CarState) -> CarState) = flow.update(transform)
}

/**
 * Pleos Vehicle SDK 2.3.2 신호를 [CarState] 하나로 모은다. 신호마다 따로 등록해서 하나가 실패해도 나머지는 받는다.
 *
 * - 자리별 탑승(Seat.registerSeatOccupancy)은 받지 않는다. 뒷줄 가운데 자리가 인식되지 않아(11/15 팀 결정)
 *   누가 타고 내렸는지는 사람이 C-04(탑승자 추가)·C-05(하차)로 알려 준다. [CarState.seated]는 늘 비어 있다.
 * - 문: 시연·화면 표시용으로만 받는다. 길은 문으로 끝내지 않는다(사람이 주행 종료를 누른다).
 * - 에너지: 전기차는 배터리 에너지(getEvBatteryLevel)로 구간마다 쓴 양을 잰다. SDK의 Wh를 kWh로 바꾼다.
 */
@SuppressLint("MissingPermission")
class PleosCar(context: Context) : CarSource {
    private val vehicle = Vehicle(context)
    private val flow = MutableStateFlow(CarState())
    override val state: StateFlow<CarState> = flow

    private val doorAreas = mapOf(
        Door.DRIVER to DoorValue.ROW_1_LEFT,
        Door.FRONT to DoorValue.ROW_1_RIGHT,
        Door.REAR_LEFT to DoorValue.ROW_2_LEFT,
        Door.REAR_RIGHT to DoorValue.ROW_2_RIGHT,
        Door.TRUNK to DoorValue.REAR,
    )
    private val doorListeners = doorAreas.mapValues { (door, _) ->
        object : DoorPositionListener {
            override fun onPositionUpdated(position: Int?) {
                val open = (position ?: 0) > 0
                received("door $door", position) { it.copy(openDoors = if (open) it.openDoors + door else it.openDoors - door) }
            }
            override fun onFailed(e: Exception) = failed("door $door", e)
        }
    }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = received("gear", gear) { it.copy(gear = gear.toDomain()) }
        override fun onFailed(e: Exception) = failed("gear", e)
    }
    private val odometerListener = object : DistanceDrivenListener {
        // 값이 잠깐 비어(null) 오면 마지막 주행거리를 그대로 둔다. null로 덮으면 구간 거리가 틀어진다.
        override fun onDistanceDrivenUpdated(km: Float?) = received("odometer", km) { if (km == null) it else it.copy(odometerKm = km.toDouble()) }
        override fun onFailed(e: Exception) = failed("odometer", e)
    }
    private val batteryListener = object : EvBatteryLevelListener {
        override fun onEvBatteryLevelUpdated(level: Float?) =
            received("batteryLevel", level) { it.copy(batteryKwh = batteryLevelKwh(level)) }
        override fun onFailed(e: Exception) = failed("batteryLevel", e)
    }
    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(percent: Int?) = received("soc", percent) { it.copy(socPercent = percent) }
        override fun onFailed(e: Exception) = failed("soc", e)
    }
    private val evEfficiencyListener = object : EvBatteryEfficiencyListener {
        override fun onEvBatteryEfficiencyUpdated(value: Float?) =
            received("evEfficiency", value) { it.copy(kmPerKwh = value?.toDouble()?.takeIf { v -> v in 1.0..15.0 }) }
        override fun onFailed(e: Exception) = failed("evEfficiency", e)
    }
    private val fuelListener = object : FuelPercentListener {
        override fun onFuelPercentUpdated(percent: Int?) = received("fuel", percent) { it.copy(fuelPercent = percent) }
        override fun onFailed(e: Exception) = failed("fuel", e)
    }
    private val fuelEconomyListener = object : FuelEconomyListener {
        override fun onFuelEconomyUpdated(value: Float?) =
            received("fuelEconomy", value) { it.copy(kmPerLiter = value?.toDouble()?.takeIf { v -> v in 3.0..40.0 }) }
        override fun onFailed(e: Exception) = failed("fuelEconomy", e)
    }

    override fun start() {
        attempt("init") { vehicle.initialize() }
        attempt("gear") { vehicle.getGear().registerGearSelection(gearListener) }
        attempt("odometer") { vehicle.getOdometer().registerDistanceDriven(odometerListener) }
        doorListeners.forEach { (door, listener) ->
            attempt("door $door") { vehicle.getDoor().registerDoorPosition(DoorArea(doorAreas.getValue(door)), listener) }
        }
        // 전기차와 내연기관차 중 한쪽만 온다. 오지 않는 쪽은 null로 남는다.
        attempt("batteryLevel") { vehicle.getEvBattery().registerEvBatteryLevel(batteryListener) }
        attempt("soc") { vehicle.getEvBattery().registerEvBatteryStateOfCharge(socListener) }
        attempt("evEfficiency") { vehicle.getEvBattery().registerCumulativeEvEfficiency(evEfficiencyListener) }
        // 배터리 용량(getEvBatteryCapacity)은 공개 권한으로 SecurityException이 났다(에뮬레이터 2.1.0, 11/8). 쓰지 않는다.
        attempt("fuel") { vehicle.getFuel().registerFuelPercent(fuelListener) }
        attempt("fuelEconomy") { vehicle.getFuel().registerCumulativeFuelEconomy(fuelEconomyListener) }
    }

    override fun stop() {
        attempt("gear") { vehicle.getGear().unregisterGearSelection(gearListener) }
        attempt("odometer") { vehicle.getOdometer().unregisterDistanceDriven(odometerListener) }
        doorListeners.forEach { (door, listener) ->
            attempt("door $door") { vehicle.getDoor().unregisterDoorPosition(DoorArea(doorAreas.getValue(door)), listener) }
        }
        attempt("batteryLevel") { vehicle.getEvBattery().unregisterEvBatteryLevel(batteryListener) }
        attempt("soc") { vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(socListener) }
        attempt("evEfficiency") { vehicle.getEvBattery().unregisterCumulativeEvEfficiency(evEfficiencyListener) }
        attempt("fuel") { vehicle.getFuel().unregisterFuelPercent(fuelListener) }
        attempt("fuelEconomy") { vehicle.getFuel().unregisterCumulativeFuelEconomy(fuelEconomyListener) }
        attempt("release") { vehicle.release() }
    }

    /** 시연 도구(디버그 빌드): 에뮬레이터가 주지 않는 신호(내연기관 평균 연비)를 넣는다. */
    internal fun debugUpdate(transform: (CarState) -> CarState) = flow.update(transform)

    private inline fun attempt(signal: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.w(TAG, "$signal 연결이나 해제에 실패했어요", it) }
    }

    private fun received(signal: String, value: Any?, transform: (CarState) -> CarState) {
        if (BuildConfig.DEBUG) Log.i(TAG, "$signal = $value")
        flow.update(transform)
    }

    private fun failed(signal: String, e: Throwable) {
        Log.w(TAG, "$signal 신호를 받지 못했어요", e)
    }

    private fun VehicleGear?.toDomain(): Gear = when (this) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_UNKNOWN, null -> Gear.UNKNOWN
        else -> Gear.DRIVE
    }

    private companion object {
        const val TAG = "RideCar"
    }
}
