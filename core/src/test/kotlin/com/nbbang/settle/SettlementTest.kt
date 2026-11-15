package com.nbbang.settle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 기획서 7장 계산 예를 그대로 옮긴 테스트. 숫자를 바꾸려면 기획서부터 바꾼다.
 * R8만 기획서(10원 절사, 잔액은 운전자)와 다르다: 1원 단위로 내리고 끝전은 N빵이 낸다(카카오페이 정산과 같은 방식).
 */
class SettlementTest {
    private val me = Person("me", "나", isDriver = true)
    private val minsu = Person("minsu", "김민수")
    private val jieun = Person("jieun", "이지은")
    private val all = setOf("me", "minsu", "jieun")
    private val twoLeft = setOf("me", "jieun")

    /** 120km, 김민수는 60km에서 하차, 톨비 3,200원 × 2(20km, 90km). */
    private fun trip(wonPerLiter: Int, expenses: List<Expense>) = SettleInput(
        people = listOf(me, minsu, jieun),
        segments = listOf(Segment(0.0, 60.0, all), Segment(60.0, 120.0, twoLeft)),
        fuel = FuelRate(kmPerUnit = 12.0, wonPerUnit = wonPerLiter),
        tolls = listOf(TollEvent(20.0, 3_200, all), TollEvent(90.0, 3_200, twoLeft)),
        expenses = expenses,
    )

    private val food = Expense("먹은 음식", 6_000, all)
    private val carPay = Expense("휴게소", 4_500, all, ExpenseSource.CAR_PAY)
    private val chicken = Expense("치킨", 12_000, all, ExpenseSource.SEPARATE)

    private fun Settlement.finalOf(id: String) = shares.first { it.person.id == id }.finalWon
    private fun Settlement.shareOf(id: String) = shares.first { it.person.id == id }

    @Test
    fun `기획서 계산 예 C-08~C-18 - 실제 연비로 나누고 인정하면 운전자 감면, 최종 11,942 · 7,400 · 25,250`() {
        val s = settle(
            trip(1_700, listOf(food, carPay, chicken)).copy(
                saving = SavingClaim(actualKmPerUnit = 13.0, accepted = true),
                game = GameResult(GameMode.ALL_IN, targetWon = 12_000, players = all, loser = "jieun"),
            ),
        )

        // 유류비는 실제 연비 13.0으로 구간마다 60 ÷ 13 × 1,700 = 7,846원(평균 12.0이면 8,500원).
        assertEquals(15_692, s.fuelWon)
        assertEquals(13.0, s.fuelKmPerUnit, 0.0)
        assertEquals(6_400, s.tollWon)
        assertEquals(22_500, s.expenseWon)
        assertEquals(listOf(11_046, 11_046), s.segments.map { it.totalWon })

        assertEquals(9_205.0, s.shareOf("me").rideWon, 1e-6)
        assertEquals(3_682.0, s.shareOf("minsu").rideWon, 1e-6)
        assertEquals(9_205.0, s.shareOf("jieun").rideWon, 1e-6)
        assertEquals(7_500.0, s.shareOf("minsu").expenseWon, 1e-6)

        // 절약액 17,000 − 15,692 = 1,308원. 인정하면 동승자는 평균 연비 기준 몫까지 낸다:
        // 김민수 (8,500 − 7,846) ÷ 3 = 218원, 이지은 218 + (8,500 − 7,846) ÷ 2 = 545원. 운전자는 그 합 763원을 감면받는다.
        assertEquals(1_308, s.savingWon)
        assertEquals(763, s.savingAppliedWon)
        assertEquals(-763.0, s.shareOf("me").savingWon, 1e-6)
        assertEquals(218.0, s.shareOf("minsu").savingWon, 1e-6)
        assertEquals(545.0, s.shareOf("jieun").savingWon, 1e-6)

        assertEquals(-4_000.0, s.shareOf("me").gameWon, 1e-6)
        assertEquals(8_000.0, s.shareOf("jieun").gameWon, 1e-6)

        // 동승자는 평균 연비로 나눴을 때(#74)와 같은 7,400 · 25,250원. 합계는 실제로 쓴 돈 44,592원이다.
        assertEquals(11_942, s.finalOf("me"))
        assertEquals(7_400, s.finalOf("minsu"))
        assertEquals(25_250, s.finalOf("jieun"))
        assertEquals(44_592, s.totalWon)
        assertTrue(s.balanced)
        assertTrue(s.readyToBill)
    }

    @Test
    fun `기획서 T-03 예시 - 공시 1,650원, 1원 단위로 내리고 끝전 2원은 N빵이 낸다`() {
        val s = settle(trip(1_650, listOf(food)))

        assertEquals(16_500, s.fuelWon)
        assertEquals(28_900, s.costWon)
        // 정확한 몫 11,541.67 · 5,816.67 · 11,541.67 (기획서의 10원 절사로는 11,550 · 5,810 · 11,540)
        assertEquals(11_541, s.finalOf("me"))
        assertEquals(5_816, s.finalOf("minsu"))
        assertEquals(11_541, s.finalOf("jieun"))
        assertEquals(2, s.roundingWon)
        assertTrue(s.balanced)
    }

    @Test
    fun `R8 10,000원을 셋이 나누면 3,333원씩 내고 1원은 N빵이 낸다`() {
        val s = settle(
            SettleInput(
                people = listOf(me, minsu, jieun),
                segments = listOf(Segment(0.0, 10.0, all)),
                fuel = FuelRate(kmPerUnit = 1.0, wonPerUnit = 1_000),
            ),
        )

        assertEquals(listOf(3_333, 3_333, 3_333), s.shares.map { it.finalWon })
        assertEquals(1, s.roundingWon)
        assertEquals(0, s.absorbedWon)
        assertTrue(s.balanced)
    }

    @Test
    fun `R8 돌려받을 돈은 1원 미만을 더 돌려받는다`() {
        val s = settle(
            SettleInput(
                people = listOf(me, minsu, jieun),
                segments = listOf(Segment(0.0, 6.0, all)),
                fuel = FuelRate(kmPerUnit = 1.0, wonPerUnit = 1_000),
                expenses = listOf(Expense("간식", 10_000, all, ExpenseSource.SEPARATE, paidBy = "minsu")),
            ),
        )

        // 각자 2,000 + 3,333.33. 김민수는 10,000을 먼저 냈으니 −4,666.67 → 4,667원을 돌려받는다.
        assertEquals(-4_667, s.finalOf("minsu"))
        assertEquals(5_333, s.finalOf("me"))
        assertEquals(5_333, s.finalOf("jieun"))
        assertEquals(1, s.roundingWon)
        assertTrue(s.balanced)
    }

    @Test
    fun `R8 운전자 OFF면 운전자는 0원이고 끝전은 N빵이 낸다`() {
        val s = settle(
            SettleInput(
                people = listOf(me, minsu, jieun),
                segments = listOf(Segment(0.0, 10.0, all)),
                fuel = FuelRate(kmPerUnit = 1.0, wonPerUnit = 1_001),
                optedOut = setOf("me"),
            ),
        )

        assertEquals(0, s.finalOf("me"))
        assertEquals(5_005, s.finalOf("minsu"))
        assertEquals(5_005, s.finalOf("jieun"))
        assertEquals(0, s.roundingWon)
        assertTrue(s.balanced)
    }

    @Test
    fun `R8 어떤 경우에도 자기 몫보다 더 내지 않고 끝전은 내는 사람 수보다 적다`() {
        val rnd = Random(7)
        val names = listOf("me", "a", "b", "c", "d")
        repeat(500) {
            val people = names.take(rnd.nextInt(2, 6)).map { Person(it, it, isDriver = it == "me") }
            val ids = people.map { it.id }
            fun some() = ids.filter { rnd.nextBoolean() }.toSet()
            var km = 0.0
            val segments = List(rnd.nextInt(1, 4)) {
                val start = km
                km += rnd.nextInt(1, 80) + rnd.nextDouble()
                Segment(start, km, some() + "me")
            }
            val optedOut = some().let { if (rnd.nextInt(4) == 0) it else it - "me" }
            val input = SettleInput(
                people = people,
                segments = segments,
                fuel = FuelRate(kmPerUnit = 3.0 + rnd.nextDouble() * 15, wonPerUnit = rnd.nextInt(200, 2_000)),
                tolls = List(rnd.nextInt(0, 3)) { TollEvent(rnd.nextDouble() * km, rnd.nextInt(500, 9_000), some()) },
                expenses = List(rnd.nextInt(0, 3)) {
                    Expense("e$it", rnd.nextInt(1_000, 30_000), some(), paidBy = if (rnd.nextBoolean()) ids.random(rnd) else null)
                },
                optedOut = optedOut,
                saving = SavingClaim(actualKmPerUnit = 20.0, accepted = rnd.nextBoolean()),
            )
            val s = settle(input)
            val payers = s.shares.filter { !it.person.isDriver || it.optedIn }

            assertTrue(s.balanced)
            assertTrue(s.roundingWon in 0 until payers.size.coerceAtLeast(1))
            payers.forEach { share ->
                val gap = share.exactWon - share.finalWon
                assertTrue("${share.person.id} $gap", gap > -1e-6 && gap < 1.0)
            }
        }
    }

    @Test
    fun `R3 미입력 톨비는 합계에서 빼고 건수를 센다`() {
        val input = trip(1_700, emptyList())
        val s = settle(input.copy(tolls = listOf(input.tolls[0], input.tolls[1].copy(won = null))))

        assertEquals(3_200, s.tollWon)
        assertEquals(1, s.pendingTolls)
        assertTrue(s.balanced)
        assertFalse(s.readyToBill)
    }

    @Test
    fun `R5 운전자 OFF면 운전자 0원, 나머지가 나눠 낸다`() {
        val s = settle(trip(1_700, listOf(food)).copy(optedOut = setOf("me")))

        assertEquals(0, s.finalOf("me"))
        // 구간 1: 11,700 ÷ 2, 구간 2: 11,700 ÷ 1(이지은), 음식 6,000 ÷ 2
        assertEquals(5_850 + 3_000, s.finalOf("minsu"))
        assertEquals(5_850 + 11_700 + 3_000, s.finalOf("jieun"))
        assertEquals(0, s.absorbedWon)
        assertTrue(s.balanced)
    }

    @Test
    fun `R5 동승자 OFF면 분모에서 빠진다`() {
        val s = settle(trip(1_700, emptyList()).copy(optedOut = setOf("minsu")))

        assertEquals(0, s.finalOf("minsu"))
        assertEquals(23_400, s.finalOf("me") + s.finalOf("jieun"))
        assertTrue(s.balanced)
    }

    @Test
    fun `R5 낼 사람이 없는 구간은 운전자가 떠안는다`() {
        val s = settle(
            SettleInput(
                people = listOf(me, minsu),
                segments = listOf(Segment(0.0, 12.0, setOf("me")), Segment(12.0, 24.0, setOf("me", "minsu"))),
                fuel = FuelRate(12.0, 1_700),
                optedOut = setOf("me"),
            ),
        )

        assertEquals(1_700, s.finalOf("minsu"))
        assertEquals(1_700, s.absorbedWon)
        assertTrue(s.balanced)
    }

    @Test
    fun `R6 절약액은 운전자 부담액을 넘지 않는다`() {
        val s = settle(
            SettleInput(
                people = listOf(me, minsu),
                segments = listOf(Segment(0.0, 120.0, setOf("me", "minsu"))),
                fuel = FuelRate(12.0, 1_700),
                saving = SavingClaim(actualKmPerUnit = 60.0, accepted = true),
            ),
        )

        // 실제 유류비 120 ÷ 60 × 1,700 = 3,400원(평균이면 17,000원). 김민수가 평균 기준 몫 8,500원까지 내면
        // 운전자 몫(1,700원)보다 감면이 커서, 운전자 몫은 0원에서 멈추고 김민수는 실제로 쓴 3,400원만 낸다.
        assertEquals(13_600, s.savingWon)
        assertEquals(1_700, s.savingAppliedWon)
        assertEquals(0, s.finalOf("me"))
        assertEquals(3_400, s.finalOf("minsu"))
        assertTrue(s.balanced)
    }

    @Test
    fun `R6 인정 안 하면 아낀 돈을 모두 나누고, 인정하면 동승자는 평균 연비 기준 금액을 낸다`() {
        val base = trip(1_700, emptyList())
        val average = settle(base)
        val declined = settle(base.copy(saving = SavingClaim(13.0, accepted = false)))
        val accepted = settle(base.copy(saving = SavingClaim(13.0, accepted = true)))

        assertEquals(1_308, declined.savingWon)
        assertEquals(0, declined.savingAppliedWon)
        // 인정 안 함: 실제로 쓴 22,092원(유류비 15,692 + 톨비 6,400)을 그대로 나눈다.
        assertEquals(listOf(9_205, 3_682, 9_205), listOf("me", "minsu", "jieun").map { declined.finalOf(it) })
        // 인정: 동승자는 평균 연비로 나눴을 때와 같고, 운전자만 763원 덜 낸다.
        assertEquals(listOf(3_900, 9_750), listOf("minsu", "jieun").map { average.finalOf(it) })
        assertEquals(listOf(8_442, 3_900, 9_750), listOf("me", "minsu", "jieun").map { accepted.finalOf(it) })
        assertTrue(accepted.balanced && declined.balanced)
    }

    @Test
    fun `R6 실제 연비가 평균보다 나쁘면 평균 연비로 나누고 감면도 없다`() {
        val base = trip(1_700, emptyList())
        val worse = settle(base.copy(saving = SavingClaim(11.0, accepted = true)))

        assertEquals(0, worse.savingWon)
        assertEquals(0, worse.savingAppliedWon)
        assertEquals(12.0, worse.fuelKmPerUnit, 0.0)
        assertEquals(settle(base).shares, worse.shares)
    }

    @Test
    fun `R6 운전자가 OFF면 감면하지 않는다`() {
        val s = settle(trip(1_700, emptyList()).copy(optedOut = setOf("me"), saving = SavingClaim(13.0, accepted = true)))
        assertEquals(0, s.savingAppliedWon)
        assertTrue(s.balanced)
    }

    @Test
    fun `R6 무작위 - 인정해도 동승자는 평균 연비 기준보다 더 내지 않고 운전자 몫은 음수가 되지 않는다`() {
        val rnd = Random(7)
        repeat(300) {
            val km = rnd.nextDouble(5.0, 200.0)
            val cut = rnd.nextDouble(0.0, km)
            val input = SettleInput(
                people = listOf(me, minsu, jieun),
                segments = listOf(Segment(0.0, cut, all), Segment(cut, km, if (rnd.nextBoolean()) all else twoLeft)),
                fuel = FuelRate(kmPerUnit = rnd.nextDouble(4.0, 15.0), wonPerUnit = rnd.nextInt(200, 2_000)),
                expenses = List(rnd.nextInt(0, 3)) { Expense("e$it", rnd.nextInt(1_000, 30_000), all) },
                optedOut = if (rnd.nextInt(5) == 0) setOf("minsu") else emptySet(),
            )
            val average = settle(input)
            val actual = input.fuel.kmPerUnit * rnd.nextDouble(1.0, 4.0)
            val accepted = settle(input.copy(saving = SavingClaim(actual, accepted = true)))

            assertTrue(accepted.balanced)
            assertTrue(accepted.driver.exactWon > -1e-6)
            accepted.riders.forEach { share ->
                val before = average.shares.first { it.person.id == share.person.id }.exactWon
                assertTrue("${share.person.id} ${share.exactWon} > $before", share.exactWon <= before + 1e-6)
            }
        }
    }

    @Test
    fun `R7 몰아주기는 걸린 사람이 절반을 떠안고 나머지는 모두 나눈다`() {
        val s = settle(
            trip(1_700, listOf(chicken)).copy(
                game = GameResult(GameMode.LEAN, targetWon = 12_000, players = all, loser = "minsu"),
            ),
        )

        // 균등 몫 4,000원 대신: 걸린 사람 6,000 + 2,000, 나머지 2,000
        assertEquals(4_000.0, s.shareOf("minsu").gameWon, 1e-6)
        assertEquals(-2_000.0, s.shareOf("jieun").gameWon, 1e-6)
        assertEquals(0.0, s.shares.sumOf { it.gameWon }, 1e-6)
        assertTrue(s.balanced)
    }

    @Test
    fun `동승자가 먼저 낸 비용은 그 사람 몫에서 뺀다`() {
        val s = settle(trip(1_700, listOf(chicken.copy(paidBy = "minsu"))))

        // 김민수: 유류비·톨비 3,900 + 치킨 4,000 − 먼저 낸 12,000 = −4,100(돌려받을 돈)
        assertEquals(-4_100, s.finalOf("minsu"))
        assertEquals(12_000, s.prepaidWon)
        assertEquals(s.costWon - s.prepaidWon, s.totalWon)
        assertTrue(s.balanced)
    }

    @Test
    fun `전기차도 같은 식으로 계산한다`() {
        val s = settle(
            SettleInput(
                people = listOf(me, minsu),
                segments = listOf(Segment(0.0, 50.0, setOf("me", "minsu"))),
                fuel = FuelRate(kmPerUnit = 5.0, wonPerUnit = 324),
            ),
        )

        assertEquals(3_240, s.fuelWon)
        assertEquals(1_620, s.finalOf("minsu"))
        assertEquals(1_620, s.finalOf("me"))
    }

    @Test
    fun `C-12 항목 선택 - 치킨만 걸면 기획서 계산 예와 같다`() {
        val s = settle(
            trip(1_700, listOf(food, carPay, chicken)).copy(
                saving = SavingClaim(actualKmPerUnit = 13.0, accepted = true),
                game = GameResult(GameMode.ALL_IN, 0, all, loser = "jieun", scope = GameScope.Expenses(setOf(2))),
            ),
        )

        assertEquals(12_000, s.gamePotWon)
        assertEquals(listOf(11_942, 7_400, 25_250), listOf(s.finalOf("me"), s.finalOf("minsu"), s.finalOf("jieun")))
    }

    @Test
    fun `C-12 전체 금액 독박 - 걸린 사람이 참가자 몫을 다 내고 아무도 음수가 아니다`() {
        val base = trip(1_700, listOf(food, carPay, chicken))
        val before = settle(base)
        val s = settle(base.copy(game = GameResult(GameMode.ALL_IN, 0, setOf("minsu", "jieun"), loser = "minsu", scope = GameScope.Whole)))

        assertEquals(before.finalOf("minsu") + before.finalOf("jieun"), s.gamePotWon)
        assertEquals(before.finalOf("me"), s.finalOf("me"))
        assertEquals(0, s.finalOf("jieun"))
        assertEquals(s.gamePotWon, s.finalOf("minsu"))
        assertTrue(s.shares.all { it.finalWon >= 0 })
        assertTrue(s.balanced)
    }

    @Test
    fun `C-12 전체 금액 몰아주기 - 판돈 절반은 걸린 사람, 절반은 참가자가 나눈다`() {
        val base = trip(1_700, listOf(food))
        val s = settle(base.copy(game = GameResult(GameMode.LEAN, 0, all, loser = "jieun", scope = GameScope.Whole)))

        assertEquals(29_400, s.gamePotWon)
        // 이지은: 29,400 × 0.5 + 29,400 × 0.5 ÷ 3 = 19,600
        assertEquals(19_600, s.finalOf("jieun"))
        assertEquals(4_900, s.finalOf("minsu"))
        assertTrue(s.balanced)
    }

    @Test
    fun `C-12 전체 금액 독박 - 치킨을 먼저 낸 사람은 게임에 져도 이겨도 낸 돈을 돌려받는다`() {
        // 이지은이 치킨 12,000원을 먼저 냈다. 전원 참가, 김민수가 걸리면 김민수가 판돈 전체를 내고 이지은은 12,000원을 돌려받는다.
        val paid = chicken.copy(paidBy = "jieun")
        val base = trip(1_700, listOf(food, paid))
        val before = settle(base)
        val s = settle(base.copy(game = GameResult(GameMode.ALL_IN, 0, all, loser = "minsu", scope = GameScope.Whole)))

        assertEquals(before.costWon, s.gamePotWon)
        assertEquals(-12_000, s.finalOf("jieun"))
        assertEquals(0, s.finalOf("me"))
        assertEquals(s.gamePotWon, s.finalOf("minsu"))
        assertTrue(s.balanced)

        // 먼저 낸 사람이 걸리면 판돈에서 자기가 낸 돈을 뺀 만큼만 더 낸다.
        val self = settle(base.copy(game = GameResult(GameMode.ALL_IN, 0, all, loser = "jieun", scope = GameScope.Whole)))
        assertEquals(self.gamePotWon - 12_000, self.finalOf("jieun"))
        assertEquals(0, self.finalOf("minsu"))
        assertTrue(self.balanced)
    }
}
