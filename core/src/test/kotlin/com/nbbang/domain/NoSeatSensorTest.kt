package com.nbbang.domain

import com.nbbang.settle.settlement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 좌석 센서를 쓰지 않는다(뒷줄 가운데 자리 미인식). 자리 신호가 없으면 사람이 알려 준 대로만 타고 내린다. */
class NoSeatSensorTest {
    private val parked = CarState(gear = Gear.PARK, odometerKm = 0.0, batteryKwh = 70.0)

    @Test
    fun `출발 명단, 탑승자 추가, 하차로만 구간을 나눈다`() {
        val tracker = RideTracker()
        val setup = TripSetup(mode = TripMode.TRAVEL, names = listOf("A", "B", "C"))
        tracker.startTravel(setup, parked, 0, Place(), Prices(), SplitRule.EVERYONE, Power.ELECTRIC)
        // 창가 먼저, 가운데는 뒤에
        assertEquals(listOf(Seat.FRONT, Seat.REAR_LEFT, Seat.REAR_RIGHT), tracker.ride!!.riders.map { it.seat })

        val drive = parked.copy(gear = Gear.DRIVE)
        tracker.update(drive, 1_000)
        assertTrue(tracker.update(drive.copy(odometerKm = 10.0), 2_000).isEmpty())
        val c = tracker.ride!!.riders.single { it.name == "C" }
        tracker.alightNow(c.id, 3_000)
        // 신호가 없으니 시간이 지나도 저절로 타거나 내리는 사람은 없다.
        assertTrue(tracker.update(drive.copy(odometerKm = 30.0), 60_000).isEmpty())
        val joined = tracker.boardNow(1, 61_000).single()
        assertEquals(Seat.REAR_RIGHT, joined.seat)
        tracker.update(drive.copy(odometerKm = 40.0), 120_000)

        val ride = (tracker.finish(130_000) as RideEvent.Finished).ride
        assertEquals(listOf(40.0, 40.0, 10.0, 10.0), ride.settlement().riders.map { it.km })
    }
}
