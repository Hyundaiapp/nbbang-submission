package com.nbbang.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SeatPickerTest {
    @Test
    fun checkedPeopleGetWindowSeatsFirstInCheckOrder() {
        val seats = SeatPicker.fill(listOf("민지", "지훈", "수아"), emptyMap())
        assertEquals(mapOf("민지" to Seat.FRONT, "지훈" to Seat.REAR_LEFT, "수아" to Seat.REAR_RIGHT), seats)
        assertEquals(listOf("민지", "지훈", "수아"), seats.keys.toList())
    }

    @Test
    fun uncheckingFreesTheSeatAndTheNextPersonTakesTheFirstFreeOne() {
        val three = SeatPicker.fill(listOf("민지", "지훈", "수아"), emptyMap())
        val withoutMinji = SeatPicker.fill(listOf("지훈", "수아"), three)
        assertEquals(mapOf("지훈" to Seat.REAR_LEFT, "수아" to Seat.REAR_RIGHT), withoutMinji)
        assertEquals(Seat.FRONT, SeatPicker.fill(listOf("지훈", "수아", "도윤"), withoutMinji)["도윤"])
    }

    @Test
    fun movingToATakenSeatSwapsTheTwo() {
        val seats = SeatPicker.fill(listOf("민지", "지훈"), emptyMap())
        assertEquals(mapOf("민지" to Seat.REAR_LEFT, "지훈" to Seat.FRONT), SeatPicker.move(seats, "민지", Seat.REAR_LEFT))
        assertEquals(mapOf("민지" to Seat.REAR_CENTER, "지훈" to Seat.REAR_LEFT), SeatPicker.move(seats, "민지", Seat.REAR_CENTER))
        // 운전석이나 고르지 않은 사람은 그대로.
        assertEquals(seats, SeatPicker.move(seats, "민지", Seat.DRIVER))
        assertEquals(seats, SeatPicker.move(seats, "수아", Seat.REAR_LEFT))
    }

    @Test
    fun brokenTableIsRepairedSoTheRidePlanIsAlwaysUsable() {
        val fixed = SeatPicker.fill(listOf("민지", "지훈"), mapOf("민지" to Seat.REAR_LEFT, "지훈" to Seat.REAR_LEFT))
        assertEquals(mapOf("민지" to Seat.REAR_LEFT, "지훈" to Seat.FRONT), fixed)
        val setup = TripSetup(mode = TripMode.CARPOOL, groupId = "g", names = listOf("민지", "지훈"), seats = fixed)
        assertEquals(listOf(Seat.REAR_LEFT, Seat.FRONT), setup.seatPlan)
        val six = (1..6).map { "p$it" }
        assertEquals(Seat.preferred, SeatPicker.fill(six, emptyMap()).values.toList())
    }
}
