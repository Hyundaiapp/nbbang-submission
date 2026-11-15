package com.nbbang.platform

import com.nbbang.domain.CarState
import com.nbbang.domain.Gear
import com.nbbang.domain.RideTracker
import com.nbbang.settle.actualEfficiency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryLevelTest {
    @Test
    fun convertsWhEvenAtOrBelowOneKwh() {
        mapOf(77_400f to 77.4, 1_001f to 1.001, 1_000f to 1.0, 900f to 0.9, 800f to 0.8, 0f to 0.0)
            .forEach { (wh, kwh) -> assertEquals(kwh, batteryLevelKwh(wh)!!, 0.000001) }
    }

    @Test
    fun unavailableOrInvalidReadingsStayUnknown() {
        listOf(null, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
            .forEach { assertNull(batteryLevelKwh(it)) }
    }

    @Test
    fun lowBatteryRideMeasuresEnergyAndEfficiencyInKwh() {
        val tracker = RideTracker()
        val departure = CarState(gear = Gear.DRIVE, odometerKm = 1_000.0, batteryKwh = batteryLevelKwh(900f))
        tracker.update(departure, 0)
        tracker.update(departure.copy(odometerKm = 1_001.0, batteryKwh = batteryLevelKwh(800f)), 1_000)

        val ride = tracker.preview()!!
        assertEquals(0.1, ride.segments.single().energyUsed!!, 0.000001)
        assertEquals(10.0, ride.actualEfficiency()!!, 0.000001)
    }

    @Test
    fun crossingOneKwhKeepsEnergyContinuous() {
        val tracker = RideTracker()
        val departure = CarState(gear = Gear.DRIVE, odometerKm = 1_000.0, batteryKwh = batteryLevelKwh(1_001f))
        tracker.update(departure, 0)
        tracker.update(departure.copy(odometerKm = 1_000.01, batteryKwh = batteryLevelKwh(999f)), 1_000)

        assertEquals(0.002, tracker.preview()!!.segments.single().energyUsed!!, 0.000001)
    }
}
