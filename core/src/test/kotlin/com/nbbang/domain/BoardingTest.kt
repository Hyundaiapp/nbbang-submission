package com.nbbang.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C-04 수동 추가와 C-05 하차 처리. 센서 안정 시간은 실제처럼 3초로 둔다. */
class BoardingTest {
    private fun seated(vararg seats: Seat) = Seat.passengers.associateWith { it in seats }

    private fun driving(tracker: RideTracker, vararg seats: Seat): CarState {
        val car = CarState(gear = Gear.DRIVE, odometerKm = 100.0, fuelPercent = 50, seated = seated(*seats))
        tracker.update(car, 0)
        return car
    }

    @Test
    fun `수동 추가는 바로 태우고 센서가 빈자리여도 내리지 않는다`() {
        val tracker = RideTracker()
        var car = driving(tracker, Seat.FRONT)
        car = car.copy(odometerKm = 110.0)
        tracker.update(car, 1_000)

        val added = tracker.boardNow(2, 1_000)

        assertEquals(listOf(Seat.REAR_LEFT, Seat.REAR_RIGHT), added.map { it.seat }) // 창가 먼저
        assertEquals(110.0, added.first().boardedKm, 1e-6)
        assertTrue(tracker.update(car, 10_000).isEmpty())
        assertEquals(3, tracker.aboardCount)
    }

    @Test
    fun `자리는 3열까지 여섯, 모자라면 있는 만큼만`() {
        val tracker = RideTracker()
        driving(tracker, Seat.FRONT, Seat.REAR_LEFT)

        assertEquals(4, tracker.ride!!.freeSeats())
        assertEquals(4, tracker.boardNow(6, 1_000).size)
        assertEquals(0, tracker.ride!!.freeSeats())
        assertTrue(tracker.boardNow(1, 2_000).isEmpty())
    }

    @Test
    fun `하차 처리는 바로 내리고, 센서가 비었다가 다시 앉아야 새로 태운다`() {
        val tracker = RideTracker()
        var car = driving(tracker, Seat.FRONT)
        val rider = tracker.ride!!.riders.single()
        car = car.copy(odometerKm = 130.0)
        tracker.update(car, 1_000)

        assertEquals(130.0, tracker.alightNow(rider.id, 1_000)!!.alightedKm!!, 1e-6)
        assertNull(tracker.alightNow(rider.id, 1_500))
        // 센서는 아직 앉아 있다고 한다: 다시 태우지 않는다
        assertTrue(tracker.update(car, 10_000).isEmpty())
        // 빈자리가 됐다가 다시 앉으면 새로 탄 것이다
        car = car.copy(seated = seated())
        tracker.update(car, 11_000)
        car = car.copy(seated = seated(Seat.FRONT))
        tracker.update(car, 12_000)
        val boarded = tracker.update(car, 15_000)
        assertTrue(boarded.single() is RideEvent.Boarded)
    }

    @Test
    fun `센서보다 적게 확인하면 남는 사람은 처음부터 없던 것으로 지우고 짐 자리로 둔다`() {
        val tracker = RideTracker()
        var car = driving(tracker, Seat.FRONT, Seat.REAR_LEFT)
        val bag = tracker.ride!!.riders.single { it.seat == Seat.REAR_LEFT }
        car = car.copy(odometerKm = 105.0)
        tracker.update(car, 1_000)
        tracker.boardNow(1, 1_000) // 구간을 하나 닫아 둔다(가방이 들어간 구간)

        tracker.discard(listOf(bag.id))

        val ride = tracker.ride!!
        assertEquals(listOf(Seat.FRONT, Seat.REAR_RIGHT), ride.riders.map { it.seat })
        assertTrue(ride.segments.none { bag.id in it.riderIds })
        // 센서는 아직 앉아 있다고 해도 다시 태우지 않는다
        assertTrue(tracker.update(car, 10_000).isEmpty())
        assertEquals(2, tracker.aboardCount)
        // 가방을 내리고(빈자리) 사람이 앉으면 새로 탄 것이다
        car = car.copy(seated = seated(Seat.FRONT))
        tracker.update(car, 11_000)
        car = car.copy(seated = seated(Seat.FRONT, Seat.REAR_LEFT))
        tracker.update(car, 12_000)
        assertTrue(tracker.update(car, 15_000).single() is RideEvent.Boarded)
    }

    @Test
    fun `재탑승 후보는 내려서 지금 없는 사람, 재탑승하면 후보에서 빠진다`() {
        val tracker = RideTracker()
        driving(tracker, Seat.FRONT, Seat.REAR_LEFT)
        tracker.rename(1, "김민수")
        tracker.alightNow(1, 1_000)

        assertEquals(listOf(1), tracker.ride!!.reboardCandidates().map { it.id })

        val back = tracker.boardNow(1, 2_000).single()
        tracker.edit { r -> r.copy(riders = r.riders.map { if (it.id == back.id) it.copy(sameAs = 1) else it }) }
        val ride = tracker.ride!!
        assertEquals(1, ride.rootId(back.id))
        assertTrue(ride.reboardCandidates().isEmpty())
        assertEquals(setOf(1, 2), ride.aboardPeople())
    }

    @Test
    fun `이름을 못 받은 새 사람은 인원 N`() {
        val tracker = RideTracker()
        driving(tracker, Seat.FRONT, Seat.REAR_LEFT)

        assertEquals("인원 2", tracker.ride!!.placeholderName(2))
    }

    @Test
    fun `센서가 앉았다고 했지만 아직 안 태운 자리부터 채운다`() {
        val tracker = RideTracker()
        var car = driving(tracker, Seat.FRONT)
        car = car.copy(seated = seated(Seat.FRONT, Seat.REAR_RIGHT))
        tracker.update(car, 1_000)

        val added = tracker.boardNow(1, 1_500).single()

        assertEquals(Seat.REAR_RIGHT, added.seat)
        // 그 자리는 센서를 따른다: 안정 시간이 지나도 두 번 태우지 않고, 센서가 비면 내린다
        assertTrue(tracker.update(car, 5_000).isEmpty())
        car = car.copy(seated = seated(Seat.FRONT))
        tracker.update(car, 6_000)
        assertTrue(tracker.update(car, 9_000).single() is RideEvent.Alighted)
    }

    @Test
    fun `C-04 확인 전에 거르는 것`() {
        val tracker = RideTracker()
        driving(tracker, Seat.FRONT, Seat.REAR_LEFT)
        tracker.alightNow(1, 1_000)
        val ride = tracker.ride!!

        assertNull(ride.boardingProblem(listOf(Boarder.Returning(1), Boarder.Newcomer("새 사람"))))
        assertEquals("몇 명이 탔는지 골라 주세요.", ride.boardingProblem(emptyList()))
        assertEquals("같은 사람을 두 번 고를 수 없어요.", ride.boardingProblem(listOf(Boarder.Returning(1), Boarder.Returning(1))))
        assertEquals("지금 다시 탈 수 있는 사람이 아니에요.", ride.boardingProblem(listOf(Boarder.Returning(2))))
        assertEquals("빈 자리가 모자라요. 최대 5명이에요.", ride.boardingProblem(List(6) { Boarder.Newcomer(null) }))
        assertNull(ride.boardingProblem(List(6) { Boarder.Newcomer(null) }, sensed = 1))
    }

    @Test
    fun `C-04는 이미 붙은 이름을 살리고 고른 이름은 이름 없는 자리에 붙인다`() {
        // 3번은 장부로 "민지"라고 짐작한 자리, 4번은 이름이 없다. 민지 칩은 이미 타 있어서 숨는다.
        val ride = Ride(
            id = "r", startedAt = 0,
            riders = listOf(Rider(3, Seat.FRONT, 0, 0.0, name = "민지"), Rider(4, Seat.REAR_LEFT, 0, 0.0)),
        )
        val picked = listOf(Boarder.Newcomer("지훈"), Boarder.Newcomer(null))
        assertEquals(listOf<Int?>(4, 3) to emptyList<Int>(), ride.seatBoarders(picked, listOf(3, 4)))
        // 한 명(지훈)만 탔다고 하면 지훈은 이름 없는 자리, 짐작한 자리는 짐
        assertEquals(listOf<Int?>(4) to listOf(3), ride.seatBoarders(listOf(Boarder.Newcomer("지훈")), listOf(3, 4)))
        // 이름 없이 한 명이면 이름이 붙은 자리를 살린다
        assertEquals(listOf<Int?>(3) to listOf(4), ride.seatBoarders(listOf(Boarder.Newcomer(null)), listOf(3, 4)))
        // 고른 이름이 이미 그 자리에 붙어 있으면 그 자리
        assertEquals(listOf<Int?>(3) to listOf(4), ride.seatBoarders(listOf(Boarder.Newcomer("민지")), listOf(4, 3)))
        // 센서보다 많이 고르면 남는 사람은 새 자리(null), 0명이면 센서 자리는 모두 짐
        assertEquals(listOf(4, 3, null) to emptyList<Int>(), ride.seatBoarders(picked + Boarder.Newcomer("수아"), listOf(3, 4, 4)))
        assertEquals(emptyList<Int?>() to listOf(3, 4), ride.seatBoarders(emptyList(), listOf(3, 4)))
    }

    @Test
    fun `센서가 본 자리가 짐이면 0명으로 확인할 수 있다`() {
        val ride = Ride(id = "r", startedAt = 0, riders = listOf(Rider(1, Seat.FRONT, 0, 0.0)))
        assertNull(ride.boardingProblem(emptyList(), sensed = 1))
        assertEquals("몇 명이 탔는지 골라 주세요.", ride.boardingProblem(emptyList(), sensed = 0))
    }

    @Test
    fun `탑승 팝업은 같은 사람을 두 번 세지 않는다`() {
        val queue = PopupQueue().push(Popup.Boarding(listOf(1, 2))).push(Popup.Boarding(listOf(2, 3)))
        assertEquals(listOf(Popup.Boarding(listOf(1, 2, 3))), queue.items)
    }

    @Test
    fun `혼자 지난 톨게이트와 운전자 혼자 낸 비용은 함께 나눈 게 아니다`() {
        val solo = Ride(
            id = "r", startedAt = 0,
            tollPasses = listOf(TollPass(at = 0, km = 3.0, aboard = setOf(DRIVER_ID))),
            costs = listOf(Cost("주차비", 3_000, setOf(DRIVER_ID))),
        )
        assertTrue(solo.sharedNothing)
        assertTrue(!solo.copy(tollPasses = listOf(TollPass(at = 0, km = 3.0, aboard = setOf(DRIVER_ID, 1)))).sharedNothing)
        assertTrue(!solo.copy(costs = listOf(Cost("주차비", 3_000, setOf(DRIVER_ID, 1)))).sharedNothing)
    }
}
