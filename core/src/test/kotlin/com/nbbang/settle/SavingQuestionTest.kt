package com.nbbang.settle

import com.nbbang.domain.Power
import com.nbbang.domain.Prices
import com.nbbang.domain.Ride
import com.nbbang.domain.Rider
import com.nbbang.domain.Seat
import com.nbbang.domain.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C-11 절약 기여 평가. 평균 전비 5.0km/kWh, 300원/kWh. */
class SavingQuestionTest {
    private fun ride(km: Double, kwh: Double) = Ride(
        id = "t",
        startedAt = 0,
        endedAt = 3_600_000,
        power = Power.ELECTRIC,
        prices = Prices(kmPerKwh = 5.0, wonPerKwh = 300),
        riders = listOf(Rider(1, Seat.FRONT, 0, 0.0, alightedAt = 1, alightedKm = km, name = "민수")),
        segments = listOf(Segment(0.0, km, setOf(1), energyUsed = kwh)),
    )

    @Test
    fun `실제 전비가 평균보다 좋으면 묻는다`() {
        val r = ride(52.0, 10.0)
        assertEquals(5.2, r.actualEfficiency()!!, 0.0)
        assertTrue(r.savingQuestionDue())
        // (52 ÷ 5.0 − 52 ÷ 5.2) × 300 = 120원
        assertEquals(120, r.settlement().savingWon)
    }

    @Test
    fun `평균과 같거나 나쁘면 묻지 않는다`() {
        assertFalse(ride(50.0, 10.0).savingQuestionDue())
        assertFalse(ride(45.0, 10.0).savingQuestionDue())
        // 5.04는 화면에 5.0으로 보이므로 평균과 같다.
        assertFalse(ride(50.4, 10.0).savingQuestionDue())
    }

    @Test
    fun `절약액은 화면에 보이는 소수 첫째 자리 전비로 계산한다`() {
        // 30km를 5.1458kWh로 → 5.83km/kWh지만 화면은 5.8. 평균 30 ÷ 5.0 × 300 = 1,800원, 실제 30 ÷ 5.8 × 300 = 1,552원 → 248원
        // (반올림 전 5.83이면 1,544원이라 256원이 된다)
        val r = ride(30.0, 30.0 / 5.83)
        assertEquals(5.8, r.actualEfficiency()!!, 0.0)
        assertEquals(1_552, r.settlement().fuelWon)
        assertEquals(248, r.settlement().savingWon)
    }
}
