package com.nbbang.domain

import com.nbbang.settle.settlement
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillAndLedgerTest {
    @Test
    fun `주행 중 추가 비용은 입력 당시 탑승자에게만 청구된다`() {
        val ride = Ride(
            id = "driving-cost",
            startedAt = 0,
            riders = listOf(
                Rider(1, Seat.FRONT, 0, 0.0),
                Rider(2, Seat.REAR_LEFT, 0, 0.0, alightedAt = 1),
                Rider(3, Seat.REAR_RIGHT, 3, 0.0),
            ),
            costs = listOf(Cost("간식", 6_000, setOf(DRIVER_ID, 1), at = 2)),
        )
        val bill = ride.bill()
        assertEquals(6_000, bill.extraWon)
        assertEquals(6_000, bill.totalWon)
        assertEquals(3_000, bill.driverWon)
        assertEquals(listOf(3_000, 0, 0), bill.shares.map { it.won })
        assertEquals(listOf(3_000, 0, 0), ride.settlement().riders.map { it.finalWon })
    }

    @Test
    fun `전기차와 내연기관차는 각 단위의 설정으로 같은 계산식을 사용한다`() {
        val prices = Prices(kmPerKwh = 6.0, wonPerKwh = 400, kmPerLiter = 12.0, wonPerLiter = 1_800)
        val ride = Ride(
            id = "energy-units", startedAt = 0, prices = prices,
            segments = listOf(Segment(0.0, 60.0, setOf(1))),
            riders = listOf(Rider(1, Seat.FRONT, 0, 0.0)),
        )
        val electric = ride.copy(power = Power.ELECTRIC).bill()
        val fuel = ride.copy(power = Power.FUEL).bill()
        assertEquals(4_000, electric.energyWon)
        assertEquals(9_000, fuel.energyWon)
        assertEquals(2_000, electric.shares.single().won)
        assertEquals(4_500, fuel.shares.single().won)
        assertEquals("kWh", electric.unitPrice)
        assertEquals("L", fuel.unitPrice)
    }

    private val zone: ZoneId = ZoneId.of("Asia/Seoul")

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun rider(id: Int, seat: Seat, name: String? = null, boardedAt: Long = 0L) =
        Rider(id, seat, boardedAt, 0.0, alightedAt = boardedAt + 1, alightedKm = 0.0, name = name)

    /** 출근길 카풀 하나. 조수석 한 명, 12km, 2.4kWh. */
    private fun commute(start: Long, name: String? = "지훈", seat: Seat = Seat.FRONT) = Ride(
        id = "r$start",
        startedAt = start,
        endedAt = start + 1_800_000,
        prices = Prices(wonPerKwh = 300),
        segments = listOf(Segment(0.0, 12.0, setOf(1), energyUsed = 2.4)),
        riders = listOf(rider(1, seat, name, boardedAt = start)),
    )

    private val trip = Ride(
        id = "trip",
        startedAt = 0,
        endedAt = 1,
        prices = Prices(wonPerKwh = 300),
        segments = listOf(
            Segment(0.0, 40.0, setOf(1, 2, 3), energyUsed = 8.0),
            Segment(40.0, 120.0, setOf(1, 2), energyUsed = 16.0, tollgates = 1),
        ),
        riders = listOf(rider(1, Seat.FRONT, "민지"), rider(2, Seat.REAR_LEFT, "지훈"), rider(3, Seat.REAR_RIGHT)),
        routeTollWon = 6_000,
    )

    @Test
    fun `동승자만 나누면 운전자 몫은 없다`() {
        val bill = trip.copy(rule = SplitRule.PASSENGERS).bill()
        assertEquals(listOf(6_200, 6_200, 800), bill.shares.map { it.won })
        assertEquals(0, bill.driverWon)
    }

    @Test
    fun `쏜 길은 아무도 내지 않는다`() {
        val bill = trip.copy(treated = true).bill()
        assertTrue(bill.shares.all { it.won == 0 })
        assertEquals(13_200, bill.driverWon)
    }

    @Test
    fun `에너지를 재지 못한 구간은 거리와 전비로 어림한다`() {
        val ride = trip.copy(segments = trip.segments.map { it.copy(energyUsed = null) }, carEfficiency = null)
        val bill = ride.bill()
        // 120km ÷ 5km/kWh = 24kWh → 7,200원. 잰 값과 같지만 어림이라고 표시한다.
        assertEquals(7_200, bill.energyWon)
        assertFalse(bill.energyMeasured)
    }

    @Test
    fun `톨게이트 기록이 없으면 통행료는 거리 비율로 나눈다`() {
        val ride = trip.copy(segments = trip.segments.map { it.copy(tollgates = 0) })
        val bill = ride.bill()
        // 통행료 6,000원 중 1구간(40km) 몫 2,000원은 넷이, 2구간 몫 4,000원은 셋이 나눈다.
        // 1구간 (2,400+2,000)/4 = 1,100원, 2구간 (4,800+4,000)/3 = 2,933원 → 4,033원 → 4,000원
        assertEquals(listOf(4_000, 4_000, 1_100), bill.shares.map { it.won })
    }

    @Test
    fun `고친 통행료는 예상값 대신 쓴다`() {
        val bill = trip.copy(tollOverrideWon = 4_800).bill()
        assertEquals(4_800, bill.tollWon)
        assertFalse(bill.tollEstimated)
    }

    @Test
    fun `장부는 받지 않은 몫을 사람별로 모으고 쏜 길과 받은 길은 뺀다`() {
        val rides = listOf(
            commute(at("2026-11-02T08:10")),
            commute(at("2026-11-03T08:05")),
            commute(at("2026-11-04T08:20")).copy(treated = true),
            commute(at("2026-11-05T08:00")).paidAll(),
            trip.copy(startedAt = at("2026-11-07T10:00"), endedAt = at("2026-11-07T13:00")),
        )
        val summary = summarize(rides, at("2026-11-08T12:00"), zone)
        val jihoon = summary.people.first { it.name == "지훈" }
        // 출근 두 번(360원 → 400원씩) + 여행 4,200원
        assertEquals(400 + 400 + 4_200, jihoon.owedWon)
        assertEquals(3, jihoon.rides)
        assertEquals(600, summary.unnamedOwedWon)
        assertEquals(5, summary.monthRides)
        val settled = settle(rides, "지훈")
        assertTrue(summarize(settled, at("2026-11-08T12:00"), zone).people.none { it.name == "지훈" })
        assertEquals(listOf("민지", "지훈"), knownNames(rides))
    }

    @Test
    fun `같은 자리 같은 시각에 두 번 넘게 탄 사람은 이름을 짐작한다`() {
        val history = listOf(commute(at("2026-11-02T08:10")), commute(at("2026-11-03T08:05")))
        assertEquals("지훈", guessName(Seat.FRONT, at("2026-11-09T08:30"), Place(), history, zone))
        // 주말, 다른 자리, 한 번뿐인 기록이면 짐작하지 않는다.
        assertNull(guessName(Seat.FRONT, at("2026-11-08T08:30"), Place(), history, zone))
        assertNull(guessName(Seat.REAR_LEFT, at("2026-11-09T08:30"), Place(), history, zone))
        assertNull(guessName(Seat.FRONT, at("2026-11-09T08:30"), Place(), history.take(1), zone))
    }

    @Test
    fun `QR 주소는 정산 내용을 주소 뒤 조각에만 담는다`() {
        val request = PayLink.forBill(trip, trip.bill(), Payee("운전자", "https://toss.me/driver", "은행 123"), zone)
        val content = PayLink.content(request, "https://example.org/pay.html")
        assertTrue(content.startsWith("https://example.org/pay.html#d="))
        val json = String(Base64.getUrlDecoder().decode(content.substringAfter("#d=")), Charsets.UTF_8)
        assertTrue(json.contains("\"w\":4200"))
        assertTrue(json.contains("\"l\":\"https://toss.me/driver\""))
        assertTrue(json.contains("통행료 6,000원 (내비 예상)"))
    }

    @Test
    fun `정산 페이지가 없으면 QR에 글을 담고 따옴표는 이스케이프한다`() {
        val request = PayRequest("길 \"1\"", listOf("지훈" to 4_200), listOf("거리 120km"), Payee(account = "은행 123"))
        assertEquals("길 \"1\"\n지훈 4,200원\n거리 120km\n계좌: 은행 123", PayLink.content(request, ""))
        assertTrue(PayLink.json(request).contains("\"t\":\"길 \\\"1\\\"\""))
    }

    @Test
    fun `도착 문장은 금액이 같으면 한 분에 얼마로 줄인다`() {
        val even = trip.copy(
            segments = listOf(Segment(0.0, 40.0, setOf(1, 2), energyUsed = 8.0)),
            riders = trip.riders.take(2),
            routeTollWon = 0,
        ).bill()
        assertTrue(Lines.arrival(even).contains("한 분에 800원씩이에요."))
        val uneven = trip.bill()
        assertTrue(Lines.arrival(uneven).contains("민지 4,200원, 지훈 4,200원, 뒷좌석 오른쪽 600원이에요."))
        assertTrue(Lines.arrival(uneven).startsWith("함께 탄 길 정산이에요. 120km, 전기 7,200원, 통행료 6,000원, 모두 13,200원이에요."))
    }
}
