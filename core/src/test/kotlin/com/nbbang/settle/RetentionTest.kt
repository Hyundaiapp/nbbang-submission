package com.nbbang.settle

import com.nbbang.domain.Charge
import com.nbbang.domain.Charges
import com.nbbang.domain.Ride
import com.nbbang.domain.TripMode
import com.nbbang.domain.markPaid
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day

    private fun ride(id: String, daysAgo: Long, ended: Boolean = true) =
        Ride(id = id, startedAt = now - daysAgo * day, endedAt = if (ended) now - daysAgo * day + 3_600_000 else null)

    private fun charge(id: String, billedDaysAgo: Long, paid: Boolean) =
        Charge(id, "민지", "여행", 5_000, now - billedDaysAgo * day, now - billedDaysAgo * day + Charges.DUE.toMillis())
            .let { if (paid) it.markPaid(true, now - billedDaysAgo * day + 1) else it }

    @Test
    fun `넘지 않으면 그대로`() {
        val rides = listOf(ride("a", 300), ride("b", 200))
        assertEquals(rides, rides.forStorage(emptyList(), now, max = 2))
    }

    @Test
    fun `넘으면 오래된 것부터 버리되 아직 돈이 오갈 수 있는 주행은 남긴다`() {
        val rides = listOf(
            ride("old1", 500),
            ride("owesTrip", 490),
            ride("owesMonth", 480).copy(mode = TripMode.CARPOOL, groupId = "g", billingMonth = YearMonth.of(2024, 1)),
            ride("waiting", 470).copy(mode = TripMode.CARPOOL, awaitingSettlementChoice = true),
            ride("old2", 400),
            ride("old3", 300),
            ride("recent", 30),
            ride("live", 0, ended = false),
        )
        val charges = listOf(
            charge(Charges.rideId("owesTrip", 1), 489, paid = false),
            charge(Charges.monthlyId("g", "2024-01", "민지"), 470, paid = false),
            charge(Charges.rideId("old1", 1), 499, paid = true),
        )

        val kept = rides.forStorage(charges, now, max = 6)

        assertEquals(listOf("owesTrip", "owesMonth", "waiting", "old3", "recent", "live"), kept.map { it.id })
    }

    @Test
    fun `지킬 기록이 많으면 한도를 넘어도 남긴다`() {
        val rides = (1..5).map { ride("r$it", it.toLong()) }
        assertEquals(5, rides.forStorage(emptyList(), now, max = 2).size)
    }

    @Test
    fun `청구는 다 받은 것 중 오래된 것부터 버리고 덜 받은 것은 남긴다`() {
        val charges = listOf(
            charge("unpaid-old", 900, paid = false),
            charge("paid-old", 800, paid = true),
            charge("paid-mid", 500, paid = true),
            charge("paid-new", 10, paid = true),
        )
        assertEquals(listOf("unpaid-old", "paid-mid", "paid-new"), charges.forStorage(max = 3).map { it.id })
    }
}
