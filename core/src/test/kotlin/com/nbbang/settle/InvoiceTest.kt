package com.nbbang.settle

import com.nbbang.domain.Cost
import com.nbbang.domain.DRIVER_ID
import com.nbbang.domain.GameKind
import com.nbbang.domain.Payee
import com.nbbang.domain.PayLink
import com.nbbang.domain.Power
import com.nbbang.domain.Prices
import com.nbbang.domain.Ride
import com.nbbang.domain.RideGame
import com.nbbang.domain.Rider
import com.nbbang.domain.Seat
import com.nbbang.domain.Segment
import com.nbbang.domain.TollPass
import java.time.ZoneId
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C-18 청구서 내용. 기획서 계산 예를 쓴다(실제 연비로 나누고 절약 기여를 인정해 이지은 25,250원, 기획서는 25,900원). */
class InvoiceTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val all = setOf(DRIVER_ID, 1, 2)
    private val payee = Payee(name = "운전자", account = "국민 123-45")

    // 2026-11-09 09:00 KST
    private val base = Ride(
        id = "r1",
        startedAt = 1_794_182_400_000,
        endedAt = 1_794_190_000_000,
        power = Power.FUEL,
        prices = Prices(kmPerLiter = 12.0, wonPerLiter = 1_650),
        destination = "강릉",
        riders = listOf(
            Rider(1, Seat.FRONT, 0, 1_000.0, alightedAt = 1, alightedKm = 1_060.0, name = "김민수"),
            Rider(2, Seat.REAR_LEFT, 0, 1_000.0, alightedAt = 2, alightedKm = 1_120.0, name = "이지은"),
        ),
        segments = listOf(Segment(1_000.0, 1_060.0, setOf(1, 2)), Segment(1_060.0, 1_120.0, setOf(2))),
        tollPasses = listOf(TollPass(0, 1_020.0, all, won = 3_200), TollPass(0, 1_090.0, setOf(DRIVER_ID, 2), won = 3_200)),
        costs = listOf(
            Cost("먹은 음식", 6_000, all),
            Cost("휴게소", 4_500, all, ExpenseSource.CAR_PAY),
            Cost("치킨", 12_000, all, ExpenseSource.SEPARATE),
        ),
        carEfficiency = 13.0,
        unitWonOverride = 1_700,
        savingAccepted = true,
        game = RideGame(GameMode.ALL_IN, 12_000, all, loser = 2, game = "주사위 굴리기"),
    )

    @Test
    fun `이지은 청구서 - 내역 합이 최종 금액, 게임 문구, 주행 정보`() {
        val s = base.settlement()
        val jieun = s.shares.first { it.riderId() == 2 }
        val invoice = base.invoice(jieun, s, payee, zone)

        assertEquals("11월 9일 강릉 가는 길", invoice.title)
        assertEquals(listOf("이지은" to 25_250), invoice.items)
        assertEquals(
            listOf("유류비·톨비" to 9_205, "추가 비용" to 7_500, "절약 기여 인정" to 545, "게임 조정" to 8_000),
            invoice.costs,
        )
        assertTrue(invoice.game!!.contains("이지은 님"))
        assertTrue(invoice.game!!.contains("12,000원을 혼자 다 내요"))
        assertTrue(GameLines.templates("주사위 굴리기").any { t -> invoice.game == t.replace("{who}", "이지은").replace("{deal}", "12,000원을 혼자 다 내요") })
        // 운전자가 걸리면 "나 님"이 아니라 "운전자 님"
        val driverLost = base.copy(game = base.game!!.copy(loser = DRIVER_ID))
        val ds = driverLost.settlement()
        val driverLine = driverLost.invoice(ds.shares.first { it.riderId() == 2 }, ds, payee, zone).game!!
        assertTrue(driverLine, driverLine.contains("운전자 님") && !driverLine.contains("나 님"))
        assertTrue(invoice.notes.contains("실제 연비 13.0km/L(평균 12.0km/L) · 리터당 1,700원(기본 1,650원에서 수정)"))
        assertTrue(invoice.notes.contains("절약 기여 인정 · 동승자는 평균 연비로 계산한 몫을 내요(운전자 763원 감면, 추정)"))

        // 기본값이 공시 평균이면 청구서에 기준일과 함께 적는다(기획서 C-18 "리터당 1,700원 (공시 평균 1,650원)").
        val official = base.copy(prices = base.prices.copy(officialDate = "2025-11-09"))
        assertTrue(official.invoice(jieun, official.settlement(), payee, zone).notes.contains("실제 연비 13.0km/L(평균 12.0km/L) · 리터당 1,700원(11/9 공시 평균 1,650원에서 수정)"))
        // 실제 연비를 모르면 평균 연비로 나눈다.
        val plain = official.copy(unitWonOverride = null, carEfficiency = null)
        val plainShare = plain.settlement().shares.first { it.riderId() == 2 }
        assertTrue(plain.invoice(plainShare, plain.settlement(), payee, zone).notes.contains("평균 연비 12.0km/L · 리터당 1,650원(11/9 공시 평균)"))
        assertTrue(invoice.notes.contains("탄 구간 0~120km"))
        assertTrue(invoice.notes.contains("함께 쓴 돈 유류비 15,692원 + 톨비 6,400원 + 추가 비용 22,500원 = 44,592원"))
        // 인정하지 않으면 아낀 돈을 모두 나눴다고 적는다.
        val declined = base.copy(savingAccepted = false)
        val declinedShare = declined.settlement().shares.first { it.riderId() == 2 }
        assertTrue(declined.invoice(declinedShare, declined.settlement(), payee, zone).notes.contains("평균보다 아낀 1,308원(추정)은 모두 함께 나눴어요"))
    }

    @Test
    fun `남는 1원은 내역 줄 대신 N빵이 낸다고 따로 보여 준다`() {
        val ride = base.copy(costs = listOf(Cost("먹은 음식", 6_000, all)), unitWonOverride = null, carEfficiency = null, savingAccepted = null, game = null)
        val s = ride.settlement()
        val minsu = s.shares.first { it.riderId() == 1 }
        val invoice = ride.invoice(minsu, s, payee, zone)

        // 내 몫 5,816.67원 → 5,816원을 내고 1원은 N빵이 낸다. 내역은 5,817원(3,816.67 → 3,817 + 2,000).
        assertEquals(5_816, minsu.finalWon)
        assertEquals(1, invoice.nbbangWon)
        assertEquals(listOf("유류비·톨비" to 3_817, "추가 비용" to 2_000), invoice.costs)
        assertTrue(invoice.costs.none { "끝전" in it.first || "맞춤" in it.first })
        val json = PayLink.json(invoice)
        assertTrue(json.contains("\"e\":1"))
        assertTrue(PayLink.text(invoice).contains("1원은 N빵이 내요"))
        assertTrue(invoice.notes.contains("탄 구간 0~60km"))
        assertNull(invoice.game)
        assertNull(invoice.gameKind)
        assertEquals("trip", invoice.mode)
    }

    @Test
    fun `내역 합은 언제나 최종 금액 + N빵이 낸 돈, N빵은 1원까지`() {
        val rnd = kotlin.random.Random(11)
        repeat(300) {
            val ride = base.copy(
                costs = List(rnd.nextInt(0, 4)) { Cost("비용$it", rnd.nextInt(100, 40_000), all) },
                unitWonOverride = rnd.nextInt(200, 2_500),
                carEfficiency = if (rnd.nextBoolean()) null else rnd.nextDouble(8.0, 20.0),
                savingAccepted = rnd.nextBoolean(),
                game = if (rnd.nextBoolean()) null else RideGame(GameMode.LEAN, rnd.nextInt(1_000, 9_000), all, loser = 1, game = "야바위"),
            )
            val s = ride.settlement()
            s.riders.filter { it.finalWon > 0 }.forEach { share ->
                val invoice = ride.invoice(share, s, payee, zone)
                assertTrue(invoice.nbbangWon in 0..1)
                assertEquals(share.finalWon + invoice.nbbangWon, invoice.costs.sumOf { it.second })
            }
        }
    }

    @Test
    fun `게임 결과 문장 - 독박은 혼자 다 내고 몰아주기는 몰아서 낸다, 모든 문장이 마침표로 끝난다`() {
        assertEquals("12,000원을 혼자 다 내요", GameLines.deal(GameMode.ALL_IN, 12_000))
        assertEquals("6,000원을 몰아서 내요", GameLines.deal(GameMode.LEAN, 6_000))
        listOf("사다리타기", "야바위", "카드 뽑기", "주사위 굴리기").forEach { game ->
            val templates = GameLines.templates(game)
            assertEquals(5, templates.size)
            templates.forEach { t ->
                assertTrue(t, "{who}" in t && "{deal}" in t && t.endsWith("."))
                assertTrue(t, listOf("운이 좋", "운이 최고", "눈썰미").none { it in t })
            }
        }
    }

    @Test
    fun `게임 종류 키는 예전 이름("주사위")도 찾는다`() {
        assertEquals(GameKind.DICE, GameKind.of("주사위"))
        assertEquals(GameKind.LADDER, GameKind.of("사다리타기"))
        assertNull(GameKind.of(""))
    }

    @Test
    fun `QR 주소에 내역과 게임이 들어간다`() {
        val s = base.settlement()
        val invoice = base.invoice(s.shares.first { it.riderId() == 2 }, s, payee, zone)
        val url = PayLink.content(invoice, "https://hyundaiapp.github.io/pay/")
        val json = String(Base64.getUrlDecoder().decode(url.substringAfter("#d=")), Charsets.UTF_8)

        assertTrue(url.startsWith("https://hyundaiapp.github.io/pay/#d="))
        assertTrue(json.contains("\"c\":[{\"n\":\"유류비·톨비\",\"w\":9205}"))
        assertTrue(json.contains("\"g\":\"${invoice.game}\""))
        // 이지은이 주사위 독박이라 x(진 사람) 표시가 붙는다.
        assertTrue(json.contains("\"s\":[{\"n\":\"이지은\",\"w\":25250,\"x\":1}]"))
        // 카풀·여행 모드와 게임 종류(4종)를 키로 넘긴다(디자인 청구서 v2: m, gk). 1원 단위로 딱 나눠졌으면 e는 없다.
        assertTrue(json.contains("\"m\":\"trip\""))
        assertTrue(json.contains("\"gk\":\"dice\""))
        assertTrue(!json.contains("\"k\":") && !json.contains("\"e\":"))
        // 진 사람이 아닌 김민수 청구서에는 x가 없다.
        val minsu = PayLink.json(base.invoice(s.shares.first { it.riderId() == 1 }, s, payee, zone))
        assertTrue(minsu.contains("\"s\":[{\"n\":\"김민수\",\"w\":") && !minsu.contains("\"x\""))
        assertEquals(listOf("ladder", "cup", "card", "dice"), GameKind.entries.map { it.key })
    }

    @Test
    fun `몰아주기에서 많이 탄 사람이 걸려 원래보다 덜 내도 진 사람 표시(x)가 붙는다`() {
        // 김민수 10km, 이지은 120km. 전체 금액 몰아주기에서 이지은이 걸렸다. C-17은 독박왕으로 보여 준다.
        val ride = base.copy(
            riders = listOf(
                Rider(1, Seat.FRONT, 0, 1_000.0, alightedAt = 1, alightedKm = 1_010.0, name = "김민수"),
                Rider(2, Seat.REAR_LEFT, 0, 1_000.0, alightedAt = 2, alightedKm = 1_120.0, name = "이지은"),
            ),
            segments = listOf(Segment(1_000.0, 1_010.0, setOf(1, 2)), Segment(1_010.0, 1_120.0, setOf(2))),
            tollPasses = emptyList(),
            costs = emptyList(),
            game = RideGame(GameMode.LEAN, 0, setOf(1, 2), loser = 2, game = "사다리타기", whole = true),
        )
        val s = ride.settlement()
        val jieun = s.shares.first { it.riderId() == 2 }
        assertTrue("이 경우 이지은은 원래 몫보다 덜 낸다", jieun.gameWon < 0.0)
        assertTrue(PayLink.json(ride.invoice(jieun, s, payee, zone)).contains("\"x\":1"))
        // 운전자가 지면 어느 동승자 청구서에도 x가 없다.
        val driverLost = base.copy(game = base.game!!.copy(loser = DRIVER_ID))
        val ds = driverLost.settlement()
        ds.shares.filter { !it.person.isDriver }.forEach { share ->
            assertTrue(!PayLink.json(driverLost.invoice(share, ds, payee, zone)).contains("\"x\""))
        }
    }

    @Test
    fun `받은 뒤 다시 청구하면 받은 만큼 빼고 남은 금액만 청구한다`() {
        val s = base.settlement()
        val jieun = s.shares.first { it.riderId() == 2 }
        val invoice = base.invoice(jieun, s, payee, zone, receivedWon = 20_000)

        assertEquals(jieun.finalWon - 20_000, invoice.items.single().second)
        assertEquals("이미 받은 금액" to -20_000, invoice.costs.last())
        assertEquals(invoice.items.single().second + invoice.nbbangWon, invoice.costs.sumOf { it.second })
        assertTrue(invoice.notes.any { it.startsWith("이미 받은 20,000원") })
        // 다 받았거나(같거나 많음) 안 받았으면 그대로 전액
        assertEquals(jieun.finalWon, base.invoice(jieun, s, payee, zone, receivedWon = jieun.finalWon).items.single().second)
        assertEquals(jieun.finalWon, base.invoice(jieun, s, payee, zone).items.single().second)
    }
}
