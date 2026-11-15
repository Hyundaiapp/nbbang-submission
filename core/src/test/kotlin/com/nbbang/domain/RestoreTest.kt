package com.nbbang.domain

import com.nbbang.settle.settlement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-C01 앱이 중간에 꺼졌다 켜져도 끊기지 않은 것과 같은 정산이 나오는지. */
class RestoreTest {
    private val prices = Prices(kmPerLiter = 12.0, wonPerLiter = 1_700)

    private fun seated(vararg seats: Seat) = Seat.passengers.associateWith { it in seats }

    private fun car(km: Double, vararg seats: Seat, gear: Gear = Gear.DRIVE) =
        CarState(gear = gear, odometerKm = km, fuelPercent = 50, seated = seated(*seats))

    /** 출발(둘) → 20km 톨게이트 → [cut] → 60km 한 명 하차 → 120km 도착. [cut]에서 앱이 꺼졌다 켜질 수 있다. */
    private fun trip(cut: (RideTracker) -> RideTracker): Ride {
        var tracker = RideTracker(stableMs = 0)
        tracker.prepare(TripSetup(mode = TripMode.CARPOOL, groupId = "g1"))
        tracker.update(car(0.0, Seat.FRONT, Seat.REAR_LEFT), 1_000, prices = prices)
        tracker.update(car(20.0, Seat.FRONT, Seat.REAR_LEFT), 2_000, prices = prices)
        tracker.tollgatePassed(2_000, "서울")
        tracker.edit { r -> r.copy(tollPasses = r.tollPasses.map { it.copy(won = 3_200) }, costs = r.costs + Cost("음식", 6_000, setOf(0, 1, 2))) }
        tracker.boardNow(1, 2_500)
        tracker = cut(tracker)
        tracker.update(car(60.0, Seat.REAR_LEFT), 3_000, prices = prices)
        tracker.update(car(120.0, Seat.REAR_LEFT, gear = Gear.PARK), 4_000, prices = prices)
        return (tracker.finish(5_000) as RideEvent.Finished).ride
    }

    @Test
    fun `저장했다가 이어 가면 끊기지 않은 것과 같다`() {
        val straight = trip { it }
        val restored = trip { old ->
            val snapshot = old.snapshot(2_600)!!
            RideTracker(stableMs = 0).also { it.restore(snapshot) }
        }

        assertEquals(straight.segments, restored.segments)
        assertEquals(straight.riders, restored.riders)
        assertEquals(straight.tollPasses, restored.tollPasses)
        assertEquals(TripMode.CARPOOL, restored.mode)
        assertEquals("g1", restored.groupId)
        assertEquals(straight.settlement().shares.map { it.finalWon }, restored.settlement().shares.map { it.finalWon })
    }

    @Test
    fun `수동으로 태운 자리는 복원 뒤에도 내리지 않는다`() {
        val ride = trip { old -> RideTracker(stableMs = 0).also { it.restore(old.snapshot(2_600)!!) } }

        val manual = ride.riders.single { it.seat == Seat.REAR_RIGHT }
        assertEquals(120.0, manual.alightedKm!!, 1e-6)
    }

    @Test
    fun `저장 내용은 타고 내릴 때만 바뀐다`() {
        val tracker = RideTracker(stableMs = 0)
        assertNull(tracker.snapshot(0))
        tracker.update(car(0.0, Seat.FRONT), 1_000, prices = prices)
        val first = tracker.snapshot(1_000)!!
        tracker.update(car(5.0, Seat.FRONT), 2_000, prices = prices)

        assertTrue(first.sameContent(tracker.snapshot(2_000)))
        assertEquals(5.0, tracker.snapshot(2_000)!!.lastKm!!, 1e-6)
        tracker.update(car(6.0), 3_000, prices = prices)
        assertFalse(first.sameContent(tracker.snapshot(3_000)))
    }
}
