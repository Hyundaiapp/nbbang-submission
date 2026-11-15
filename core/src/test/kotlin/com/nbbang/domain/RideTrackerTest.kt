package com.nbbang.domain

import com.nbbang.settle.settlement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideTrackerTest {
    private val ev = CarState(gear = Gear.PARK, odometerKm = 1_000.0, batteryKwh = 70.0)
    private val prices = Prices(wonPerKwh = 300)

    private fun seated(vararg seats: Seat) = Seat.passengers.associateWith { it in seats }

    @Test
    fun travelStartsWithNamedPassengersEvenWithoutSeatSignals() {
        val tracker = RideTracker()
        val setup = TripSetup(mode = TripMode.TRAVEL, names = listOf("A", "B"))
        val parked = ev.copy(seated = seated())

        val events = tracker.startTravel(setup, parked, 1_000, Place(), prices, SplitRule.EVERYONE, Power.ELECTRIC)
        assertTrue(RideEvent.Started in events)
        assertEquals(listOf("A", "B"), events.filterIsInstance<RideEvent.Boarded>().map { it.rider.name })
        val initial = tracker.preview()!!
        assertEquals(TripMode.TRAVEL, initial.mode)
        assertEquals(listOf("A", "B"), initial.settlement().riders.map { it.person.name })
        assertTrue(tracker.awaitingFirstDrive)
        assertEquals(0.0, tracker.preview(parked.copy(odometerKm = 1_003.0))!!.settlement().km, 0.001)

        val departure = parked.copy(gear = Gear.DRIVE, odometerKm = 1_004.0, seated = seated(Seat.FRONT))
        assertTrue(tracker.update(departure, 2_000).none { it is RideEvent.Boarded })
        val first = tracker.ride!!.riders.first()
        assertEquals(Seat.FRONT, first.seat)
        assertEquals(1_004.0, first.boardedKm, 0.001)
        val moving = departure.copy(odometerKm = 1_014.0, seated = seated(Seat.FRONT, Seat.REAR_LEFT))
        tracker.update(moving, 3_000)
        assertTrue(tracker.update(moving, 6_000).none { it is RideEvent.Boarded })
        val settlement = tracker.preview()!!.settlement()
        assertEquals(listOf(10.0, 10.0), settlement.riders.map { it.km })
        assertEquals(Seat.REAR_LEFT, tracker.ride!!.riders.last().seat)
    }

    @Test
    fun carpoolOpenedInParkMeasuresFromFirstDriveEvenAfterRestore() {
        val setup = TripSetup(mode = TripMode.CARPOOL, groupId = "group", names = listOf("A", "B"))
        val tracker = RideTracker()
        assertTrue(tracker.startCarpool(setup, ev, 1_000, Place(), prices, SplitRule.EVERYONE, Power.ELECTRIC).isNotEmpty())
        val initial = tracker.preview()!!.settlement()
        assertEquals(listOf("A", "B"), initial.riders.map { it.person.name })
        assertEquals(0.0, initial.km, 0.001)
        assertEquals(0, initial.fuelWon)
        assertTrue(tracker.startCarpool(setup, ev, 1_001, Place(), prices, SplitRule.EVERYONE, Power.ELECTRIC).isEmpty())

        val restored = RideTracker().apply { restore(tracker.snapshot()!!) }
        val reversing = ev.copy(gear = Gear.REVERSE, odometerKm = 1_004.0, batteryKwh = 69.0)
        restored.update(reversing, 2_000)
        assertEquals(0.0, restored.preview()!!.settlement().km, 0.001)
        val departure = reversing.copy(gear = Gear.DRIVE, odometerKm = 1_005.0, batteryKwh = 68.0)
        restored.update(departure, 3_000)
        val moving = departure.copy(odometerKm = 1_015.0, batteryKwh = 66.0)
        restored.update(moving, 4_000)

        val ride = restored.preview()!!
        assertEquals("group", ride.groupId)
        assertEquals(10.0, ride.segments.single().km, 0.001)
        assertEquals(2.0, ride.segments.single().energyUsed!!, 0.001)
        assertEquals(listOf(10.0, 10.0), ride.settlement().riders.map { it.km })
    }

    @Test
    fun carpoolNamesMoveToSensedSeatsAtFirstDriveSoAlightingIsDetected() {
        // K-03에서 [주행 시작]을 누를 때는 아무도 앉지 않았고, 그 뒤 조수석과 뒷좌석 왼쪽에 탄다.
        val setup = TripSetup(mode = TripMode.CARPOOL, groupId = "group", names = listOf("지훈", "민지"))
        val tracker = RideTracker(stableMs = 0)
        val empty = ev.copy(seated = seated())
        tracker.startCarpool(setup, empty, 1_000, Place(), prices, SplitRule.EVERYONE, Power.ELECTRIC)

        val departure = empty.copy(gear = Gear.DRIVE, seated = seated(Seat.FRONT, Seat.REAR_LEFT))
        val events = tracker.update(departure, 2_000)
        assertTrue(events.none { it is RideEvent.Boarded })
        assertEquals(listOf("지훈" to Seat.FRONT, "민지" to Seat.REAR_LEFT), tracker.ride!!.riders.map { it.name to it.seat })

        // 5km 가서 뒷좌석 왼쪽(민지)이 내리면 센서로 하차를 안다.
        tracker.update(departure.copy(odometerKm = 1_005.0), 3_000)
        val alighted = tracker.update(departure.copy(odometerKm = 1_005.0, seated = seated(Seat.FRONT)), 4_000)
        assertEquals(listOf("민지"), alighted.filterIsInstance<RideEvent.Alighted>().map { it.rider.name })
    }

    @Test
    fun carpoolNamesFollowK03OrderEvenWhenSomeSeatsHaveNoSensor() {
        // 에뮬레이터처럼 뒷좌석 가운데는 센서 값이 없다. 출발 전에는 그 자리에 먼저 들어가도, 첫 D에서 K-03 순서대로 센서 자리에 붙는다.
        val setup = TripSetup(mode = TripMode.CARPOOL, groupId = "group", names = listOf("지훈", "민지"))
        val tracker = RideTracker(stableMs = 0)
        val noCenter = mapOf(Seat.FRONT to false, Seat.REAR_LEFT to false, Seat.REAR_RIGHT to false)
        val empty = ev.copy(seated = noCenter)
        tracker.startCarpool(setup, empty, 1_000, Place(), prices, SplitRule.EVERYONE, Power.ELECTRIC)
        assertEquals(Seat.REAR_CENTER, tracker.ride!!.riders.first().seat)

        val departure = empty.copy(gear = Gear.DRIVE, seated = noCenter + mapOf(Seat.FRONT to true, Seat.REAR_LEFT to true))
        assertTrue(tracker.update(departure, 2_000).none { it is RideEvent.Boarded })
        assertEquals(listOf("지훈" to Seat.FRONT, "민지" to Seat.REAR_LEFT), tracker.ride!!.riders.map { it.name to it.seat })
        tracker.update(departure.copy(odometerKm = 1_004.0), 3_000)
        assertEquals(2, tracker.ride!!.riders.count { it.aboard })
    }

    @Test
    fun carpoolSeatsChosenInK03AreUsedFromStartAndSensorTakesOver() {
        // K-03에서 민지 뒤 오른쪽, 지훈 조수석을 골랐다. 아무도 안 앉은 채 [주행 시작]을 눌러도 C-03에 고른 자리로 보인다.
        val setup = TripSetup(
            mode = TripMode.CARPOOL, groupId = "group", names = listOf("민지", "지훈"),
            seats = mapOf("민지" to Seat.REAR_RIGHT, "지훈" to Seat.FRONT),
        )
        val tracker = RideTracker(stableMs = 0)
        val empty = ev.copy(seated = seated())
        tracker.startCarpool(setup, empty, 1_000, Place(), prices, SplitRule.EVERYONE, Power.ELECTRIC)
        assertEquals(listOf("민지" to Seat.REAR_RIGHT, "지훈" to Seat.FRONT), tracker.ride!!.riders.map { it.name to it.seat })

        // 고른 자리에 그대로 앉았다. 순서(민지 먼저)와 상관없이 자리를 지킨다.
        val departure = empty.copy(gear = Gear.DRIVE, seated = seated(Seat.FRONT, Seat.REAR_RIGHT))
        assertTrue(tracker.update(departure, 2_000).none { it is RideEvent.Boarded })
        assertEquals(listOf("민지" to Seat.REAR_RIGHT, "지훈" to Seat.FRONT), tracker.ride!!.riders.map { it.name to it.seat })

        // 이제 센서를 따른다. 뒤 오른쪽이 비면 민지가 내린 것이다.
        tracker.update(departure.copy(odometerKm = 1_006.0), 3_000)
        val alighted = tracker.update(departure.copy(odometerKm = 1_006.0, seated = seated(Seat.FRONT)), 4_000)
        assertEquals(listOf("민지"), alighted.filterIsInstance<RideEvent.Alighted>().map { it.rider.name })
    }

    @Test
    fun carpoolSeatChosenInK03MovesWhenPassengerSitsElsewhere() {
        // 지훈을 뒤 왼쪽으로 골랐는데 뒤 오른쪽에 앉았다. 출발 때 이미 앉아 있으면 바로, 아니면 첫 D에서 옮긴다.
        val setup = TripSetup(
            mode = TripMode.CARPOOL, groupId = "group", names = listOf("민지", "지훈"),
            seats = mapOf("민지" to Seat.FRONT, "지훈" to Seat.REAR_LEFT),
        )
        val seatedAtStart = RideTracker(stableMs = 0)
        val parked = ev.copy(seated = seated(Seat.FRONT, Seat.REAR_RIGHT))
        seatedAtStart.startCarpool(setup, parked, 1_000, Place(), prices, SplitRule.EVERYONE, Power.ELECTRIC)
        assertEquals(listOf("민지" to Seat.FRONT, "지훈" to Seat.REAR_RIGHT), seatedAtStart.ride!!.riders.map { it.name to it.seat })

        val seatedLater = RideTracker(stableMs = 0)
        seatedLater.startCarpool(setup, ev.copy(seated = seated()), 1_000, Place(), prices, SplitRule.EVERYONE, Power.ELECTRIC)
        val departure = parked.copy(gear = Gear.DRIVE)
        assertTrue(seatedLater.update(departure, 2_000).none { it is RideEvent.Boarded })
        assertEquals(listOf("민지" to Seat.FRONT, "지훈" to Seat.REAR_RIGHT), seatedLater.ride!!.riders.map { it.name to it.seat })
        seatedLater.update(departure.copy(odometerKm = 1_004.0), 3_000)
        val alighted = seatedLater.update(departure.copy(odometerKm = 1_004.0, seated = seated(Seat.FRONT)), 4_000)
        assertEquals(listOf("지훈"), alighted.filterIsInstance<RideEvent.Alighted>().map { it.rider.name })
        assertEquals(2, seatedLater.ride!!.riders.size)
    }

    @Test
    fun carpoolSeatChosenInK03WaitsForPickupOnTheWay() {
        // 지훈은 가는 길에 태운다. 첫 D 때 자리가 비어 있어도 내린 것으로 보지 않고, 10km 뒤 그 자리에 앉으면 센서를 따른다.
        val setup = TripSetup(
            mode = TripMode.CARPOOL, groupId = "group", names = listOf("민지", "지훈"),
            seats = mapOf("민지" to Seat.FRONT, "지훈" to Seat.REAR_LEFT),
        )
        val tracker = RideTracker(stableMs = 0)
        tracker.startCarpool(setup, ev.copy(seated = seated()), 1_000, Place(), prices, SplitRule.EVERYONE, Power.ELECTRIC)
        val departure = ev.copy(gear = Gear.DRIVE, seated = seated(Seat.FRONT))
        tracker.update(departure, 2_000)
        tracker.update(departure.copy(odometerKm = 1_010.0), 3_000)
        assertEquals(2, tracker.ride!!.riders.count { it.aboard })

        val pickup = departure.copy(odometerKm = 1_010.0, seated = seated(Seat.FRONT, Seat.REAR_LEFT))
        assertTrue(tracker.update(pickup, 4_000).none { it is RideEvent.Boarded })
        tracker.update(pickup.copy(odometerKm = 1_020.0), 5_000)
        val alighted = tracker.update(pickup.copy(odometerKm = 1_020.0, seated = seated(Seat.FRONT)), 6_000)
        assertEquals(listOf("지훈"), alighted.filterIsInstance<RideEvent.Alighted>().map { it.rider.name })
        assertEquals(listOf(1_000.0, 1_000.0), tracker.ride!!.riders.map { it.boardedKm })
    }

    /** 실제 차처럼 조수석과 뒷줄 세 자리만 센서가 있다(3열은 값이 없다). */
    private fun noThirdRow(vararg seats: Seat) =
        listOf(Seat.FRONT, Seat.REAR_LEFT, Seat.REAR_CENTER, Seat.REAR_RIGHT).associateWith { it in seats }

    @Test
    fun carpoolRiderChosenForSensorlessSeatIsNotMovedWhenSomeoneElseBoardsLater() {
        // 민지 조수석, 수아 3열 왼쪽(센서 없음, 이미 탐), 지훈 뒤 왼쪽(가는 길에 태움). 10km에서 지훈이 뒤 오른쪽에 앉는다.
        val setup = TripSetup(
            mode = TripMode.CARPOOL, groupId = "group", names = listOf("민지", "수아", "지훈"),
            seats = mapOf("민지" to Seat.FRONT, "수아" to Seat.THIRD_LEFT, "지훈" to Seat.REAR_LEFT),
        )
        val tracker = RideTracker(stableMs = 0)
        tracker.startCarpool(setup, ev.copy(seated = noThirdRow(Seat.FRONT)), 1_000, Place(), prices, SplitRule.EVERYONE, Power.ELECTRIC)
        val departure = ev.copy(gear = Gear.DRIVE, seated = noThirdRow(Seat.FRONT))
        tracker.update(departure, 2_000)
        tracker.update(departure.copy(odometerKm = 1_010.0), 3_000)
        val pickup = departure.copy(odometerKm = 1_010.0, seated = noThirdRow(Seat.FRONT, Seat.REAR_RIGHT))
        assertTrue(tracker.update(pickup, 4_000).none { it is RideEvent.Boarded })
        assertEquals(
            listOf("민지" to Seat.FRONT, "수아" to Seat.THIRD_LEFT, "지훈" to Seat.REAR_RIGHT),
            tracker.ride!!.riders.map { it.name to it.seat },
        )
        // 30km에서 뒤 오른쪽이 비면 내린 사람은 지훈이다. 수아는 그대로 탄다.
        tracker.update(pickup.copy(odometerKm = 1_030.0), 5_000)
        val alighted = tracker.update(pickup.copy(odometerKm = 1_030.0, seated = noThirdRow(Seat.FRONT)), 6_000)
        assertEquals(listOf("지훈"), alighted.filterIsInstance<RideEvent.Alighted>().map { it.rider.name })
        assertTrue(tracker.ride!!.riders.first { it.name == "수아" }.aboard)
    }

    @Test
    fun carpoolRiderChosenForSensorlessSeatButSittingInSensedSeatIsCountedOnce() {
        // 수아를 3열로 골랐는데 출발 때 실제로는 뒤 왼쪽에 앉아 있다. 이름 없는 사람을 따로 세지 않는다.
        val setup = TripSetup(mode = TripMode.CARPOOL, groupId = "group", names = listOf("수아"), seats = mapOf("수아" to Seat.THIRD_LEFT))
        val atStart = RideTracker(stableMs = 0)
        atStart.startCarpool(setup, ev.copy(seated = noThirdRow(Seat.REAR_LEFT)), 1_000, Place(), prices, SplitRule.EVERYONE, Power.ELECTRIC)
        assertEquals(listOf("수아" to Seat.REAR_LEFT), atStart.ride!!.riders.map { it.name to it.seat })

        // 출발 뒤 첫 D 전에 앉아도 첫 D에서 그 자리로 옮긴다.
        val beforeDrive = RideTracker(stableMs = 0)
        beforeDrive.startCarpool(setup, ev.copy(seated = noThirdRow()), 1_000, Place(), prices, SplitRule.EVERYONE, Power.ELECTRIC)
        val events = beforeDrive.update(ev.copy(gear = Gear.DRIVE, seated = noThirdRow(Seat.REAR_LEFT)), 2_000)
        assertTrue(events.none { it is RideEvent.Boarded })
        assertEquals(listOf("수아" to Seat.REAR_LEFT), beforeDrive.ride!!.riders.map { it.name to it.seat })
    }

    @Test
    fun carpoolRiderWhoGetsUpBeforeFirstDriveIsKeptNotAlightedAtZeroKm() {
        // 민지가 출발 때 조수석에 앉아 있다가 첫 D 전에 일어났다(센서 없는 자리로 옮김). K-03에서 고른 사람이라 내린 것으로 보지 않는다.
        val setup = TripSetup(mode = TripMode.CARPOOL, groupId = "group", names = listOf("민지"), seats = mapOf("민지" to Seat.FRONT))
        val tracker = RideTracker(stableMs = 0)
        tracker.startCarpool(setup, ev.copy(seated = noThirdRow(Seat.FRONT)), 1_000, Place(), prices, SplitRule.EVERYONE, Power.ELECTRIC)
        val departure = ev.copy(gear = Gear.DRIVE, seated = noThirdRow())
        val events = tracker.update(departure, 2_000) + tracker.update(departure.copy(odometerKm = 1_005.0), 3_000)
        assertTrue(events.none { it is RideEvent.Alighted })
        assertEquals(1, tracker.ride!!.riders.count { it.aboard })
        // 나중에 조수석에 다시 앉으면 그 자리에서 센서를 따른다.
        val back = departure.copy(odometerKm = 1_005.0, seated = noThirdRow(Seat.FRONT))
        assertTrue(tracker.update(back, 4_000).none { it is RideEvent.Boarded })
        tracker.update(back.copy(odometerKm = 1_015.0), 5_000)
        val alighted = tracker.update(back.copy(odometerKm = 1_015.0, seated = noThirdRow()), 6_000)
        assertEquals(listOf("민지"), alighted.filterIsInstance<RideEvent.Alighted>().map { it.rider.name })
    }

    @Test
    fun carpoolSeatsThatDoNotCoverEveryoneFallBackToK03Order() {
        // 자리가 겹치거나 빠지면(예전 저장본 등) 쓰지 않고 예전처럼 명단 순서대로 센서 자리에 붙인다.
        val broken = TripSetup(
            mode = TripMode.CARPOOL, groupId = "group", names = listOf("지훈", "민지"),
            seats = mapOf("지훈" to Seat.REAR_LEFT, "민지" to Seat.REAR_LEFT),
        )
        val tracker = RideTracker(stableMs = 0)
        tracker.startCarpool(broken, ev.copy(seated = seated()), 1_000, Place(), prices, SplitRule.EVERYONE, Power.ELECTRIC)
        tracker.update(ev.copy(gear = Gear.DRIVE, seated = seated(Seat.REAR_LEFT, Seat.REAR_RIGHT)), 2_000)
        assertEquals(listOf("지훈" to Seat.REAR_LEFT, "민지" to Seat.REAR_RIGHT), tracker.ride!!.riders.map { it.name to it.seat })
        assertNull(broken.seatPlan)
        assertEquals(listOf(Seat.FRONT, Seat.REAR_LEFT), broken.copy(seats = mapOf("지훈" to Seat.FRONT, "민지" to Seat.REAR_LEFT)).seatPlan)
    }

    @Test
    fun carpoolNameWithoutSensedSeatStaysManual() {
        val setup = TripSetup(mode = TripMode.CARPOOL, groupId = "group", names = listOf("지훈", "민지"))
        val tracker = RideTracker(stableMs = 0)
        val empty = ev.copy(seated = seated())
        tracker.startCarpool(setup, empty, 1_000, Place(), prices, SplitRule.EVERYONE, Power.ELECTRIC)

        // 조수석만 센서가 잡힌다. 민지는 수동으로 남고 센서가 비었다고 내린 것으로 보지 않는다.
        val departure = empty.copy(gear = Gear.DRIVE, seated = seated(Seat.FRONT))
        tracker.update(departure, 2_000)
        tracker.update(departure.copy(odometerKm = 1_003.0), 3_000)
        assertEquals(2, tracker.ride!!.riders.count { it.aboard })
        assertEquals(Seat.FRONT, tracker.ride!!.riders.first { it.name == "지훈" }.seat)
    }

    @Test
    fun carpoolClosedBeforeFirstDriveDoesNotCreateEmptyLedgerEntry() {
        val setup = TripSetup(mode = TripMode.CARPOOL, groupId = "group", names = listOf("A"))
        val tracker = RideTracker()
        tracker.startCarpool(setup, ev, 1_000, Place(), prices, SplitRule.EVERYONE, Power.ELECTRIC)

        assertEquals(RideEvent.FinishedAlone, tracker.finish(2_000))
        assertNull(tracker.ride)
    }

    @Test
    fun carpoolIncludesConfirmedPassengersFromDeparture() {
        val tracker = RideTracker()
        tracker.prepare(TripSetup(mode = TripMode.CARPOOL, groupId = "group", names = listOf("A", "B")))
        val car = ev.copy(seated = mapOf(Seat.FRONT to true))

        assertTrue(tracker.update(car.copy(gear = Gear.REVERSE), 1_000).isEmpty())
        assertNull(tracker.ride)

        val start = car.copy(gear = Gear.DRIVE)
        val events = tracker.update(start, 2_000)
        val riders = events.filterIsInstance<RideEvent.Boarded>().map { it.rider }
        assertEquals(listOf("A", "B"), riders.map { it.name })
        assertEquals(Seat.FRONT, riders.first().seat)
        assertEquals(listOf(1_000.0, 1_000.0), riders.map { it.boardedKm })

        val end = start.copy(odometerKm = 1_012.0)
        val ride = (tracker.finish(3_000, end) as RideEvent.Finished).ride
        assertEquals("group", ride.groupId)
        assertEquals(setOf(1, 2), ride.segments.single().riderIds)
        assertEquals(listOf(12.0, 12.0), ride.settlement().riders.map { it.km })
    }

    @Test
    fun carpoolKeepsAdditionalDetectedPassengerUnconfirmed() {
        val tracker = RideTracker()
        tracker.prepare(TripSetup(mode = TripMode.CARPOOL, groupId = "group", names = listOf("A")))

        val start = ev.copy(gear = Gear.DRIVE, seated = seated(Seat.FRONT, Seat.REAR_LEFT))
        tracker.update(start, 1_000)

        assertEquals(listOf("A", null), tracker.ride?.riders?.map { it.name })
    }

    @Test
    fun `확인한 동승자는 그 이후 주행 구간부터 정산한다`() {
        val tracker = RideTracker()
        val start = ev.copy(gear = Gear.DRIVE, batteryKwh = null)
        tracker.update(start, 1_000, prices = prices)
        val boarding = start.copy(gear = Gear.PARK, odometerKm = 1_010.0)
        tracker.update(boarding, 2_000)
        tracker.tollgatePassed(2_000)
        val boarded = tracker.boardNow(1, 2_100).single()
        tracker.rename(boarded.id, "동승자")
        val rider = tracker.preview()!!.riders.single()
        assertEquals("동승자", rider.name)
        assertEquals(1_010.0, rider.boardedKm, 0.001)

        val end = start.copy(odometerKm = 1_020.0)
        tracker.update(end, 3_000)
        val ride = (tracker.finish(4_000, end) as RideEvent.Finished).ride
        assertEquals(listOf(emptySet<Int>(), setOf(rider.id)), ride.segments.map { it.riderIds })
        assertEquals(setOf(DRIVER_ID), ride.tollPasses.single().aboard)
        assertEquals(10.0, ride.bill().shares.single().km, 0.001)
        assertEquals(300, ride.bill().shares.single().won)
        assertEquals(300, ride.settlement().riders.single().finalWon)
    }

    @Test
    fun `재탑승으로 추가하면 이전과 이후 구간이 한 명의 몫으로 합쳐진다`() {
        val tracker = RideTracker(stableMs = 0)
        val start = ev.copy(gear = Gear.DRIVE, batteryKwh = null, seated = seated(Seat.FRONT))
        tracker.update(start, 1_000, prices = prices)
        val id = tracker.preview()!!.riders.single().id
        tracker.rename(id, "동승자")
        tracker.update(start.copy(odometerKm = 1_010.0, seated = seated()), 2_000)
        val boarding = start.copy(odometerKm = 1_020.0, seated = seated())
        tracker.update(boarding, 3_000)
        val returned = tracker.boardNow(1, 3_100).single()
        tracker.edit { ride -> ride.copy(riders = ride.riders.map { if (it.id == returned.id) it.copy(sameAs = id) else it }) }
        val bill = tracker.preview(boarding.copy(odometerKm = 1_030.0))!!.bill()
        val share = bill.shares.single()
        assertEquals(id, share.rider.id)
        assertEquals("동승자", share.rider.name)
        assertTrue(share.rider.aboard)
        assertEquals(20.0, share.km, 0.001)
        assertEquals(600, share.won)
    }

    /** 넷이 출발해 40km에서 한 명이 내리고, 톨게이트를 지나 120km에서 도착한다. */
    private fun friendsTrip(tracker: RideTracker = RideTracker(stableMs = 0)): Ride {
        var car = ev.copy(seated = seated(Seat.FRONT, Seat.REAR_LEFT, Seat.REAR_RIGHT))
        tracker.setRoute("강릉", 6_000)
        tracker.update(car, 0, prices = prices)
        car = car.copy(gear = Gear.DRIVE)
        val started = tracker.update(car, 1_000, prices = prices)
        assertEquals(RideEvent.Started, started.first())
        assertEquals(3, started.count { it is RideEvent.Boarded })

        car = car.copy(odometerKm = 1_040.0, batteryKwh = 62.0, gear = Gear.PARK)
        tracker.update(car, 2_000, prices = prices)
        car = car.copy(seated = seated(Seat.FRONT, Seat.REAR_LEFT), openDoors = setOf(Door.REAR_RIGHT))
        val dropped = tracker.update(car, 3_000, prices = prices)
        assertTrue(dropped.single() is RideEvent.Alighted)

        car = car.copy(gear = Gear.DRIVE, openDoors = emptySet())
        tracker.update(car, 4_000, prices = prices)
        tracker.tollgatePassed()
        car = car.copy(odometerKm = 1_120.0, batteryKwh = 46.0, gear = Gear.PARK)
        tracker.update(car, 5_000, prices = prices)
        // 운전석 문을 열어도 끝나지 않는다. 운전자가 N빵하기(주행 종료)를 눌러 끝낸다.
        car = car.copy(openDoors = setOf(Door.DRIVER))
        assertTrue(tracker.update(car, 6_000, prices = prices).isEmpty())
        val end = tracker.finish(6_000, car)
        return (end as RideEvent.Finished).ride
    }

    @Test
    fun `구간마다 탄 사람과 쓴 에너지를 나눠 기록한다`() {
        val ride = friendsTrip()
        assertEquals(2, ride.segments.size)
        val (first, second) = ride.segments
        assertEquals(40.0, first.km, 0.001)
        assertEquals(setOf(1, 2, 3), first.riderIds)
        assertEquals(8.0, first.energyUsed!!, 0.001)
        assertEquals(80.0, second.km, 0.001)
        assertEquals(setOf(1, 2), second.riderIds)
        assertEquals(16.0, second.energyUsed!!, 0.001)
        assertEquals(1, second.tollgates)
        assertEquals("강릉", ride.destination)
        assertEquals(6_000, ride.routeTollWon)
        assertTrue(ride.riders.none { it.aboard })
        assertEquals(1_040.0, ride.riders.first { it.seat == Seat.REAR_RIGHT }.alightedKm!!, 0.001)
    }

    @Test
    fun `탄 구간만큼 나눈다 - 운전자 포함`() {
        val bill = friendsTrip().bill()
        // 1구간 8kWh×300원=2,400원을 넷이, 2구간 16kWh×300원+통행료 6,000원=10,800원을 셋이 나눈다.
        assertEquals(listOf(4_200, 4_200, 600), bill.shares.map { it.won })
        assertEquals(13_200, bill.totalWon)
        assertEquals(4_200, bill.driverWon)
        assertEquals(6_000, bill.tollWon)
        assertTrue(bill.energyMeasured)
        assertTrue(bill.tollEstimated)
    }

    @Test
    fun `자세를 고칠 때 잠깐 빈 자리는 내린 것으로 보지 않는다`() {
        val tracker = RideTracker(stableMs = 3_000)
        var car = ev.copy(gear = Gear.DRIVE, seated = seated(Seat.FRONT))
        tracker.update(car, 0)
        car = car.copy(seated = seated())
        assertTrue(tracker.update(car, 1_000).isEmpty())
        car = car.copy(seated = seated(Seat.FRONT))
        assertTrue(tracker.update(car, 2_000).isEmpty())
        assertEquals(1, tracker.aboardCount)
    }

    @Test
    fun `자리가 3초 넘게 비면 비기 시작한 곳에서 내린 것으로 본다`() {
        val tracker = RideTracker(stableMs = 3_000)
        var car = ev.copy(gear = Gear.DRIVE, seated = seated(Seat.FRONT))
        tracker.update(car, 0)
        car = car.copy(odometerKm = 1_005.0, seated = seated())
        tracker.update(car, 10_000)
        car = car.copy(odometerKm = 1_006.0)
        val events = tracker.update(car, 13_500)
        val rider = (events.single() as RideEvent.Alighted).rider
        assertEquals(10_000L, rider.alightedAt)
        assertEquals(1_005.0, rider.alightedKm!!, 0.001)
    }

    @Test
    fun `주차 중에는 길을 시작하지 않고 혼자 달린 길은 남기지 않는다`() {
        val tracker = RideTracker(stableMs = 0)
        assertTrue(tracker.update(ev.copy(seated = seated(Seat.FRONT)), 0).isEmpty())
        assertNull(tracker.ride)
        tracker.update(ev.copy(gear = Gear.DRIVE), 1_000)
        val end = tracker.finish(2_000, ev.copy(odometerKm = 1_010.0))
        assertEquals(RideEvent.FinishedAlone, end)
    }

    @Test
    fun `탑승 센서가 없는 자리는 직접 알려 줄 수 있다`() {
        val tracker = RideTracker(stableMs = 0)
        val car = ev.copy(gear = Gear.DRIVE)
        tracker.update(car, 0)
        tracker.setManual(Seat.REAR_CENTER, true)
        val events = tracker.update(car, 1_000)
        assertEquals(Seat.REAR_CENTER, (events.single() as RideEvent.Boarded).rider.seat)
    }

    @Test
    fun `주행거리가 늦게 오면 처음 온 값부터 잰다`() {
        val tracker = RideTracker(stableMs = 0)
        var car = CarState(gear = Gear.DRIVE, seated = seated(Seat.FRONT), batteryKwh = 70.0)
        tracker.update(car, 0)
        car = car.copy(odometerKm = 5_000.0)
        tracker.update(car, 1_000)
        car = car.copy(odometerKm = 5_012.0, batteryKwh = 67.6)
        val ride = (tracker.finish(2_000, car) as RideEvent.Finished).ride
        assertEquals(12.0, ride.segments.sumOf { it.km }, 0.001)
    }

    @Test
    fun `진행 중인 길을 미리 계산해 볼 수 있다`() {
        val tracker = RideTracker(stableMs = 0)
        var car = ev.copy(gear = Gear.DRIVE, seated = seated(Seat.FRONT))
        tracker.update(car, 0, prices = prices)
        car = car.copy(odometerKm = 1_010.0, batteryKwh = 68.0)
        tracker.update(car, 1_000, prices = prices)
        val bill = tracker.preview()!!.bill()
        assertEquals(10.0, bill.km, 0.001)
        assertEquals(600, bill.totalWon)
        assertEquals(300, bill.shares.single().won)
        assertTrue(tracker.ride!!.segments.isEmpty())
    }

    @Test
    fun `더한 비용은 그때 탄 사람끼리 나눈다`() {
        val tracker = RideTracker(stableMs = 0)
        var car = ev.copy(gear = Gear.DRIVE, seated = seated(Seat.FRONT, Seat.REAR_LEFT))
        tracker.update(car, 0, prices = prices)
        car = car.copy(odometerKm = 1_000.0, seated = seated(Seat.FRONT))
        tracker.update(car, 1_000, prices = prices)
        tracker.addExtra(Extra(ExtraKind.PARKING, 4_000))
        car = car.copy(odometerKm = 1_001.0, batteryKwh = 69.8)
        val ride = (tracker.finish(2_000, car) as RideEvent.Finished).ride
        val bill = ride.bill()
        // 주차비 4,000원 + 전기 0.2kWh(60원)을 조수석과 운전자가 나눈다. 뒷좌석은 0km라 0원.
        assertEquals(listOf(2_000, 0), bill.shares.map { it.won })
        assertEquals(4_000, bill.extraWon)
    }

    @Test
    fun carpoolEndedByDriverDoorBeforeK04StaysOutOfMonthlyUntilK05() {
        // 톨비를 안 넣은 채 끝낸 카풀(앱을 오래 꺼 두었다 켜서 끝낸 경우 등). K-05 전까지 월별 밖이다.
        val setup = TripSetup(mode = TripMode.CARPOOL, groupId = "group", names = listOf("민지"))
        val tracker = RideTracker(stableMs = 0)
        tracker.startCarpool(setup, ev.copy(seated = seated()), 1_000, Place(), prices, SplitRule.EVERYONE, Power.ELECTRIC)
        val driving = ev.copy(gear = Gear.DRIVE, seated = seated(Seat.FRONT))
        tracker.update(driving, 2_000)
        tracker.tollgatePassed(2_500, "판교")
        tracker.update(driving.copy(odometerKm = 1_012.0, batteryKwh = 68.0), 3_000)
        val parked = driving.copy(gear = Gear.PARK, odometerKm = 1_012.0, batteryKwh = 68.0)
        tracker.update(parked, 4_000)
        assertTrue(tracker.update(parked.copy(openDoors = setOf(Door.DRIVER)), 5_000).none { it is RideEvent.Finished })
        val ride = (tracker.finish(5_000) as RideEvent.Finished).ride
        assertNull(ride.tollPasses.single().won)

        val saved = ride.unconfirmedIfCarpool()
        assertTrue(saved.awaitingSettlementChoice)
        assertEquals(false, saved.monthly)
        assertEquals(ride.id, saved.id)
        val travel = ride.copy(mode = TripMode.TRAVEL)
        assertEquals(travel, travel.unconfirmedIfCarpool())
    }

    @Test
    fun restStopWithDriverDoorOpenKeepsOneRide() {
        // 휴게소: P에서 운전자가 내렸다 다시 타고 출발한다. 한 길로 이어지고, 끝은 사람이 누른다.
        val tracker = RideTracker(stableMs = 0)
        val driving = ev.copy(gear = Gear.DRIVE, seated = seated(Seat.FRONT))
        tracker.update(driving, 1_000)
        tracker.update(driving.copy(odometerKm = 1_010.0), 2_000)
        val rest = driving.copy(gear = Gear.PARK, odometerKm = 1_010.0)
        tracker.update(rest, 3_000)
        assertTrue(tracker.update(rest.copy(openDoors = setOf(Door.DRIVER)), 4_000).isEmpty())
        tracker.update(rest, 5_000)
        tracker.update(driving.copy(odometerKm = 1_010.0), 6_000)
        tracker.update(driving.copy(odometerKm = 1_030.0), 7_000)
        val ride = (tracker.finish(8_000) as RideEvent.Finished).ride
        assertEquals(30.0, ride.segments.sumOf { it.km }, 0.001)
        assertEquals(1, ride.riders.size)
        assertNull(tracker.ride)
    }

    @Test
    fun seatsWithoutReadingAfterRestartKeepRidersAboard() {
        // 주행 중 앱이 다시 켜졌고, 자리 신호가 몇 초 늦게 온다. 그동안 다른 신호(주행거리)는 온다.
        val tracker = RideTracker(stableMs = 3_000)
        val driving = ev.copy(gear = Gear.DRIVE, seated = seated(Seat.FRONT, Seat.REAR_LEFT))
        tracker.update(driving, 0)
        tracker.update(driving, 3_000)
        tracker.update(driving.copy(odometerKm = 1_020.0), 10_000)
        assertEquals(2, tracker.ride!!.riders.count { it.aboard })

        val restored = RideTracker(stableMs = 3_000).apply { restore(tracker.snapshot(10_000)!!) }
        val noSeats = CarState(gear = Gear.DRIVE, odometerKm = 1_021.0, batteryKwh = 69.0)
        restored.update(noSeats, 11_000)
        restored.update(noSeats.copy(odometerKm = 1_025.0), 16_000)
        assertEquals(2, restored.ride!!.riders.size)
        assertTrue(restored.ride!!.riders.all { it.aboard })

        // 자리 신호가 오면 평소처럼: 뒷좌석 왼쪽이 비어 있으면 그때부터 하차로 본다.
        restored.update(noSeats.copy(odometerKm = 1_026.0, seated = seated(Seat.FRONT)), 17_000)
        val events = restored.update(noSeats.copy(odometerKm = 1_027.0, seated = seated(Seat.FRONT)), 20_500)
        assertEquals(1, events.filterIsInstance<RideEvent.Alighted>().size)
        assertEquals(2, restored.ride!!.riders.size)
    }

    @Test
    fun carpoolMemberPickedUpOnTheWayIsNotCountedTwice() {
        // K-03에서 민지·지훈을 골랐는데 출발(첫 D) 때는 민지만 탔다. 지훈은 10km 가서 뒷좌석 왼쪽에 탄다.
        val setup = TripSetup(mode = TripMode.CARPOOL, groupId = "group", names = listOf("민지", "지훈"))
        val tracker = RideTracker(stableMs = 0)
        val empty = ev.copy(seated = seated())
        tracker.startCarpool(setup, empty, 1_000, Place(), prices, SplitRule.EVERYONE, Power.ELECTRIC)
        val departure = empty.copy(gear = Gear.DRIVE, seated = seated(Seat.FRONT))
        tracker.update(departure, 2_000)
        tracker.update(departure.copy(odometerKm = 1_010.0), 3_000)
        val pickup = departure.copy(odometerKm = 1_010.0, seated = seated(Seat.FRONT, Seat.REAR_LEFT))
        val events = tracker.update(pickup, 4_000)
        assertTrue(events.none { it is RideEvent.Boarded })
        assertEquals(listOf("민지" to Seat.FRONT, "지훈" to Seat.REAR_LEFT), tracker.ride!!.riders.map { it.name to it.seat })

        // 이제 뒷좌석 왼쪽은 센서로 하차를 안다.
        tracker.update(pickup.copy(odometerKm = 1_020.0), 5_000)
        val alighted = tracker.update(pickup.copy(odometerKm = 1_020.0, seated = seated(Seat.FRONT)), 6_000)
        assertEquals(listOf("지훈"), alighted.filterIsInstance<RideEvent.Alighted>().map { it.rider.name })
        assertEquals(2, tracker.ride!!.riders.size)
    }

    @Test
    fun soloPickupDriveCanBeDiscardedToStartCarpool() {
        // K-03 없이 혼자 출발해 동료 집 앞까지 왔다(저절로 기록된 길). P에서 동료가 앉았다.
        val tracker = RideTracker(stableMs = 0)
        val alone = ev.copy(gear = Gear.DRIVE, seated = seated())
        tracker.update(alone, 1_000)
        tracker.update(alone.copy(odometerKm = 1_008.0), 2_000)
        val parked = alone.copy(gear = Gear.PARK, odometerKm = 1_008.0, seated = seated(Seat.FRONT))
        tracker.update(parked, 3_000)
        tracker.update(parked, 4_000)
        assertEquals(1, tracker.ride!!.riders.size)
        assertTrue(tracker.preview()!!.sharedNothing)

        assertTrue(tracker.discardUnshared())
        assertNull(tracker.ride)
        val setup = TripSetup(mode = TripMode.CARPOOL, groupId = "group", names = listOf("민지"))
        assertTrue(tracker.startCarpool(setup, parked, 5_000, Place(), prices, SplitRule.EVERYONE, Power.ELECTRIC).isNotEmpty())
        assertEquals(listOf("민지" to Seat.FRONT), tracker.ride!!.riders.map { it.name to it.seat })

        // 함께 달린 뒤에는 버리지 않는다.
        tracker.update(parked.copy(gear = Gear.DRIVE), 6_000)
        tracker.update(parked.copy(gear = Gear.DRIVE, odometerKm = 1_012.0), 7_000)
        assertTrue(!tracker.discardUnshared())
    }

    @Test
    fun rideWithNothingSharedIsNotSaved() {
        // 동승자가 탄 채 주차장에서 자리만 옮겼다(0km). 주행 종료를 눌러도 장부에 0원 주행을 남기지 않는다.
        val tracker = RideTracker(stableMs = 0)
        val car = ev.copy(gear = Gear.DRIVE, seated = seated(Seat.FRONT))
        tracker.update(car, 1_000)
        tracker.update(car.copy(gear = Gear.PARK), 2_000)
        assertEquals(RideEvent.FinishedAlone, tracker.finish(3_000))
    }
}
