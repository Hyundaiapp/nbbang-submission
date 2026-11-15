package com.nbbang.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** C-19 청구 기록: 일부만 받은 청구, 납부 취소, 다시 청구, 월 금액이 줄었을 때. */
class ChargesTest {
    private val day = 86_400_000L
    private fun charge(id: String, won: Int, monthly: Boolean = false) =
        Charge(id, "민지", "11월 카풀 · 출퇴근", won, billedAt = 0, dueAt = Charges.dueOf(0), monthly = monthly)

    @Test
    fun `일부 받은 청구를 다 받았다고 했다가 취소하면 전에 받은 금액이 남는다`() {
        // 10,000원 받은 뒤 비용이 더해져 12,000원으로 다시 청구
        val partial = listOf(charge("r:a:1", 10_000).markPaid(true, day)).upsert(charge("r:a:1", 12_000)).single()
        assertEquals(2_000, partial.dueWon)

        val paid = partial.markPaid(true, 2 * day)
        assertEquals(ChargeStatus.PAID, paid.status(2 * day))

        val undone = paid.markPaid(false, 3 * day)
        assertEquals(10_000, undone.receivedWon)
        assertEquals(2_000, undone.dueWon)
        assertEquals(day, undone.paidAt)
        // 일부 받은 적 없는 청구는 예전처럼 0원으로 돌아간다
        assertEquals(0, charge("r:a:2", 5_000).markPaid(true, day).markPaid(false, day).receivedWon)
    }

    @Test
    fun `다시 계산해서 낼 돈이 없어진 사람의 일부 받은 청구는 받은 만큼으로 닫는다`() {
        val partial = listOf(charge("r:a:1", 10_000).markPaid(true, day)).upsert(charge("r:a:1", 12_000))
        val unpaid = charge("r:a:2", 4_000)
        val rebilled = (partial + unpaid).rebillRide("a", made = emptyList())

        assertEquals(listOf("r:a:1"), rebilled.map { it.id })
        assertEquals(10_000, rebilled.single().won)
        assertEquals(0, rebilled.single().dueWon)
    }

    @Test
    fun `월 금액이 줄면 월 청구도 줄이고 0원이면 지운다`() {
        val billed = charge("m:g:2026-11:민지", 30_000, monthly = true)
        val partial = charge("m:g:2026-11:지훈", 30_000, monthly = true).copy(paidAt = day, paidWon = 25_000)
        val ride = charge("r:a:1", 9_000)
        val all = listOf(billed, partial, ride)

        val lowered = all.lowerMonthly { c -> if (c.id.endsWith("민지")) 20_000 else 22_000 }
        assertEquals(listOf(20_000, 25_000, 9_000), lowered.map { it.won })
        assertEquals(0, lowered[1].dueWon)
        // 늘어난 건 그대로(추가분 미청구), 모르면 그대로, 0원이면 안 받은 청구는 지운다
        assertEquals(all, all.lowerMonthly { 40_000 })
        assertEquals(all, all.lowerMonthly { null })
        assertEquals(listOf("m:g:2026-11:지훈", "r:a:1"), all.lowerMonthly { 0 }.map { it.id })
        assertNull(all.lowerMonthly { 0 }.firstOrNull { it.id.endsWith("민지") })
    }
}
