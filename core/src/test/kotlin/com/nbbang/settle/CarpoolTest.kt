package com.nbbang.settle

import com.nbbang.domain.ChargeStatus
import com.nbbang.domain.Charges
import com.nbbang.domain.PayLink
import com.nbbang.domain.Payee
import com.nbbang.domain.markPaid
import com.nbbang.domain.Power
import com.nbbang.domain.Prices
import com.nbbang.domain.Ride
import com.nbbang.domain.Rider
import com.nbbang.domain.Seat
import com.nbbang.domain.Segment
import com.nbbang.domain.TripMode
import com.nbbang.domain.rebillRide
import com.nbbang.domain.upsert
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarpoolTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val group = CarpoolGroup("g1", "회사 출퇴근", listOf("이지은", "김민수", "박서준"))
    private val nov = YearMonth.of(2026, 11)

    private fun at(month: Int, day: Int, hour: Int = 8) =
        LocalDateTime.of(2026, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    /** 30km, 리터당 1,200원 · 12km/L → 유류비 3,000원. 운전자 포함 셋이면 1,000원씩. */
    private fun ride(
        id: String,
        startedAt: Long,
        names: List<String>,
        monthly: Boolean = true,
        mode: TripMode = TripMode.CARPOOL,
        groupId: String? = "g1",
    ) = Ride(
        id = id,
        startedAt = startedAt,
        endedAt = startedAt + 3_600_000,
        power = Power.FUEL,
        prices = Prices(kmPerLiter = 12.0, wonPerLiter = 1_200),
        destination = "회사",
        riders = names.mapIndexed { i, n -> Rider(i + 1, Seat.passengers[i], startedAt, 0.0, alightedAt = startedAt + 1, alightedKm = 30.0, name = n) },
        segments = listOf(Segment(0.0, 30.0, names.indices.map { it + 1 }.toSet())),
        mode = mode,
        groupId = groupId,
        monthly = monthly,
    )

    private val rides = listOf(
        ride("a", at(11, 3), listOf("이지은", "김민수")),
        ride("b", at(11, 4), listOf("이지은", "김민수")),
        ride("c", at(11, 5), listOf("이지은", "손님")),
        ride("today", at(11, 6), listOf("이지은", "김민수"), monthly = false),
        ride("trip", at(11, 7), listOf("이지은", "김민수"), mode = TripMode.TRAVEL, groupId = null),
        ride("other", at(11, 8), listOf("이지은", "김민수"), groupId = "g2"),
        ride("oct", at(10, 31, 23), listOf("이지은", "김민수")),
    )

    @Test
    fun `정산일 기준 월 - 말일과 25일`() {
        assertEquals(YearMonth.of(2026, 10), group.monthOf(at(10, 31, 23), zone))
        assertEquals(nov, group.monthOf(at(11, 1), zone))
        val day25 = group.copy(settleDay = 25)
        assertEquals(nov, day25.monthOf(at(11, 25), zone))
        assertEquals(YearMonth.of(2026, 12), day25.monthOf(at(11, 26), zone))
        assertEquals("11월 1일~11월 30일", group.periodLabel(nov))
        assertEquals("10월 26일~11월 25일", day25.periodLabel(nov))
    }

    @Test
    fun `정산일을 바꿔도 K-05에서 넣은 달에 남아 두 번 청구되지 않는다`() {
        val oct = YearMonth.of(2026, 10)
        val pinned = ride("p", at(10, 28), listOf("이지은")).copy(billingMonth = group.monthOf(at(10, 28), zone))
        val legacy = ride("l", at(10, 28), listOf("김민수"))
        // 10월(말일 정산)에 청구한 뒤 정산일을 25일로 바꾼다
        val billed = group.month(oct, listOf(pinned), emptyList(), zone).let { m -> m.charge(m.members.first { it.name == "이지은" }, at(11, 1)) }
        val day25 = group.copy(settleDay = 25)

        assertEquals(oct, day25.monthOf(pinned, zone))
        assertEquals(1, day25.month(oct, listOf(pinned), listOf(billed), zone).members.first { it.name == "이지은" }.rides)
        assertEquals(0, day25.month(nov, listOf(pinned), listOf(billed), zone).members.first { it.name == "이지은" }.rides)
        // 정한 달이 없는 예전 기록은 지금 정산일을 따른다
        assertEquals(nov, day25.monthOf(legacy, zone))
    }

    @Test
    fun `월별 정산에 넣은 이 그룹 카풀만 사람별로 모은다`() {
        val m = group.month(nov, rides, emptyList(), zone)

        assertEquals(listOf("a", "b", "c"), m.rides.map { it.id })
        assertEquals(listOf("이지은", "김민수", "박서준", "손님"), m.members.map { it.name })
        assertEquals(3_000, m.members[0].won)
        assertEquals(3, m.members[0].rides)
        assertEquals(2_000, m.members[1].won)
        assertEquals(0, m.members[2].won)
        assertEquals(1_000, m.members[3].won)
        assertEquals(6_000, m.billableWon)
        assertEquals(9_000, m.totalCostWon)
        assertTrue(m.members.all { it.charge == null })
    }

    @Test
    fun `월 청구서 내역은 주행별 금액이고 합이 월 합계`() {
        val m = group.month(nov, rides, emptyList(), zone)
        val invoice = m.invoice(m.members[0], Payee(name = "운전자", account = "국민 1"))

        assertEquals("11월 카풀 정산 · 회사 출퇴근", invoice.title)
        assertEquals("carpool", invoice.mode)
        assertEquals(listOf("11/3 회사" to 1_000, "11/4 회사" to 1_000, "11/5 회사" to 1_000), invoice.costs)
        assertEquals(invoice.items.single().second, invoice.costs.sumOf { it.second })
    }

    @Test
    fun `월별 정산 상세는 절약 기여 인정분을 따로 보여 주고 청구서는 최종 금액을 쓴다`() {
        // 실제 18km/L면 유류비 2,000원(평균 12km/L면 3,000원). 셋이 666원씩이고, 인정하면 동승자는 평균 기준 1,000원까지 낸다.
        val saved = rides.first().copy(carEfficiency = 18.0, savingAccepted = true)
        val monthly = group.month(nov, listOf(saved, rides[1]), emptyList(), zone)
        val member = monthly.members.first()
        val invoice = monthly.invoice(member, Payee())

        assertEquals(listOf(667, 1_000), member.lines.map { it.wonBeforeSaving })
        assertEquals(333, member.savingWon)
        assertEquals(2_000, member.won)
        assertEquals(667, monthly.savingWon)
        assertEquals(listOf(1_000, 1_000), invoice.costs.map { it.second })
        assertEquals(member.won, invoice.items.single().second)
    }

    @Test
    fun `청구하면 청구됨, 기한 지나면 미납, 받으면 납부 완료`() {
        val m = group.month(nov, rides, emptyList(), zone)
        val billedAt = at(12, 1, 9)
        var charges = m.members.filter { it.won > 0 }.fold(emptyList<com.nbbang.domain.Charge>()) { acc, member -> acc.upsert(m.charge(member, billedAt)) }

        assertEquals(3, charges.size)
        assertEquals(ChargeStatus.BILLED, charges[0].status(at(12, 3)))
        assertEquals(ChargeStatus.OVERDUE, charges[0].status(at(12, 7)))
        assertEquals(2, charges[0].overdueDays(at(12, 7, 12), zone))

        val again = group.month(nov, rides, charges, zone)
        assertEquals(ChargeStatus.BILLED, again.members[0].charge!!.status(at(12, 2)))
        assertNull(again.members[2].charge)

        // 다음 달 K-06: 지난달 미납 경고
        assertEquals(3, group.previousUnpaid(YearMonth.of(2026, 12), charges, at(12, 7)).size)
        charges = charges.map { if (it.name == "이지은") it.copy(paidAt = at(12, 4)) else it }
        assertEquals(2, group.previousUnpaid(YearMonth.of(2026, 12), charges, at(12, 7)).size)

        // 받은 뒤 다시 만들어도 받은 것으로 둔다
        val rebilled = charges.upsert(m.charge(m.members[0], at(12, 8)))
        assertEquals(ChargeStatus.PAID, rebilled.first { it.name == "이지은" }.status(at(12, 9)))
    }

    @Test
    fun `여행·오늘 건 청구는 주행·사람마다 한 건`() {
        val today = rides.first { it.id == "today" }
        val charges = today.charges(today.settlement(), at(11, 6, 10), zone)

        assertEquals(listOf("이지은", "김민수"), charges.map { it.name })
        assertEquals(Charges.rideId("today", 1), charges[0].id)
        assertEquals("11월 6일 회사 가는 길", charges[0].label)
        assertEquals(1_000, charges[0].won)
    }

    @Test
    fun `그룹 저장 조건`() {
        assertNull(group.problem(emptyList()))
        assertEquals("그룹명을 입력해 주세요.", group.copy(name = " ").problem(emptyList()))
        assertEquals("구성원을 한 명 이상 넣어 주세요.", group.copy(members = emptyList()).problem(emptyList()))
        assertEquals("같은 이름의 그룹이 있어요.", group.problem(listOf(group.copy(id = "g9"))))
        assertEquals("같은 이름의 구성원이 있어요.", group.copy(members = listOf("a", "a")).problem(emptyList()))
    }

    @Test
    fun `C-01 홈 - 이번 달 카풀 청구 예정, 미납 건수, 최근 청구`() {
        val empty = homeNumbers(listOf(group), emptyList(), emptyList(), at(11, 10), zone)
        assertEquals(0, empty.expectedWon)
        assertTrue(empty.recent.isEmpty())

        val m = group.month(nov, rides, emptyList(), zone)
        val trip = rides.first { it.id == "trip" }
        val charges = listOf(
            m.charge(m.members[0], at(11, 1, 9)),
            trip.charges(trip.settlement(), at(11, 7, 10), zone)[0],
            m.charge(m.members[1], at(11, 8, 9)),
        )
        val h = homeNumbers(listOf(group), rides, charges, at(11, 10), zone)

        assertEquals(6_000, h.expectedWon)
        assertEquals(3, h.rideCount)
        // 11/1 청구는 11/5가 기한이라 11/10에는 미납
        assertEquals(1, h.overdueCount)
        assertEquals(listOf(at(11, 8, 9), at(11, 7, 10)), h.recent.map { it.billedAt })
    }

    @Test
    fun `주행 청구서를 다시 만들면 낼 돈이 없어진 사람의 안 받은 청구는 지운다`() {
        val trip = rides.first { it.id == "trip" }
        val first = trip.charges(trip.settlement(), at(11, 7, 12), zone)
        assertEquals(listOf(1_000, 1_000), first.map { it.won })
        val other = ride("x", at(11, 8), listOf("이지은")).let { it.charges(it.settlement(), at(11, 8, 12), zone) }
        val billed = (other + first).fold(emptyList<com.nbbang.domain.Charge>()) { acc, c -> acc.upsert(c) }

        // C-08로 돌아가 김민수를 OFF로 하고 다시 청구.
        val edited = trip.copy(optedOut = setOf(2))
        val again = billed.rebillRide(trip.id, edited.charges(edited.settlement(), at(11, 7, 13), zone))
        assertEquals(listOf("이지은"), again.filter { it.id.startsWith(Charges.ridePrefix(trip.id)) }.map { it.name })
        assertEquals(other.single().id, again.first().id)

        // 이미 받은 청구는 기록으로 남긴다.
        val paid = billed.map { if (it.name == "김민수" && it.id.startsWith(Charges.ridePrefix(trip.id))) it.copy(paidAt = at(11, 7, 12)) else it }
        val kept = paid.rebillRide(trip.id, edited.charges(edited.settlement(), at(11, 7, 13), zone))
        assertEquals(2, kept.count { it.id.startsWith(Charges.ridePrefix(trip.id)) })
    }

    @Test
    fun `청구서 없이 나온 따로 정산 주행만 홈에 다시 보인다`() {
        val now = at(11, 9)
        assertEquals(listOf("trip", "today"), rides.unsettled(emptyList(), now).map { it.id })
        val trip = rides.first { it.id == "trip" }
        val billed = trip.charges(trip.settlement(), now, zone)
        assertEquals(listOf("today"), rides.unsettled(billed, now).map { it.id })
        val paid = rides.map { if (it.id == "today") it.copy(paidRiderIds = setOf(1, 2)) else it }
        assertEquals(listOf("trip"), paid.unsettled(emptyList(), now).map { it.id })
        assertTrue(rides.unsettled(emptyList(), at(11, 20)).isEmpty())
    }

    @Test
    fun `받은 뒤 금액이 늘면 차액만 다시 청구된다`() {
        val billedAt = at(11, 10)
        val first = group.month(nov, rides, emptyList(), zone).let { m -> m.charge(m.members.first { it.name == "이지은" }, billedAt) }
        val paid = listOf(first).map { it.markPaid(true, at(11, 11)) }
        assertEquals(ChargeStatus.PAID, paid.single().status(at(11, 12)))

        // 받은 뒤 이지은이 한 번 더 탔다.
        val more = rides + ride("d", at(11, 12), listOf("이지은", "김민수"))
        val m = group.month(nov, more, paid, zone)
        val member = m.members.first { it.name == "이지은" }
        assertEquals(1_000, member.unbilledExtraWon)
        val again = paid.upsert(m.charge(member, at(11, 13))).single()
        assertEquals(first.won + 1_000, again.won)
        assertEquals(first.won, again.receivedWon)
        assertEquals(1_000, again.dueWon)
        assertEquals(ChargeStatus.BILLED, again.status(at(11, 14)))
        assertEquals(ChargeStatus.OVERDUE, again.status(at(11, 20)))

        // 청구서(QR)는 남은 금액만, 내역 합도 같다.
        val invoice = group.month(nov, more, listOf(again), zone).let { gm -> gm.invoice(gm.members.first { it.name == "이지은" }, Payee(account = "은행 1")) }
        assertEquals(1_000, invoice.items.single().second)
        assertEquals(1_000, invoice.costs.sumOf { it.second })

        // 차액까지 받으면 납부 완료. 같은 금액으로 다시 만들어도 그대로다.
        val done = again.markPaid(true, at(11, 15))
        assertEquals(ChargeStatus.PAID, done.status(at(11, 30)))
        assertEquals(ChargeStatus.PAID, listOf(done).upsert(m.charge(member, at(11, 16))).single().status(at(11, 30)))
    }

    @Test
    fun `한 달 주행이 많아도 월 청구 QR은 크기 안에 들어가고 합계는 같다`() {
        val many = (1..30).flatMap { day -> listOf(8, 18).map { h -> ride("m$day$h", at(11, day, h), listOf("이지은", "김민수")) } }
            .map { it.copy(destination = "현대자동차 남양연구소 정문") }
        val gm = group.month(nov, many, emptyList(), zone)
        val member = gm.members.first { it.name == "이지은" }
        assertEquals(60, member.rides)
        val request = gm.invoice(member, Payee(name = "운전자", link = "https://toss.me/driver", account = "예시은행 000-0000-0000"))
        for (base in listOf("https://hyundaiapp.github.io/pay/", "")) {
            val content = PayLink.content(request, base)
            assertTrue(content.toByteArray(Charsets.UTF_8).size <= PayLink.QR_MAX_BYTES)
        }
        val url = PayLink.content(request, "https://hyundaiapp.github.io/pay/")
        val json = String(java.util.Base64.getUrlDecoder().decode(url.substringAfter("#d=")), Charsets.UTF_8)
        val costs = Regex("\"w\":(-?\\d+)").findAll(json.substringAfter("\"c\":[")).map { it.groupValues[1].toInt() }.toList()
        assertEquals(member.won, costs.sum())
        assertTrue(json.contains("외 "))

        // 짧은 청구서는 그대로 담는다.
        val short = group.month(nov, rides, emptyList(), zone).let { it.invoice(it.members.first(), Payee(account = "은행 1")) }
        assertTrue(!PayLink.content(short, "https://hyundaiapp.github.io/pay/").let { u ->
            String(java.util.Base64.getUrlDecoder().decode(u.substringAfter("#d=")), Charsets.UTF_8)
        }.contains("외 "))
    }
}
