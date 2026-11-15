package com.nbbang.settle

import com.nbbang.domain.CarState
import com.nbbang.domain.Cost
import com.nbbang.domain.DRIVER_ID
import com.nbbang.domain.Gear
import com.nbbang.domain.Prices
import com.nbbang.domain.Power
import com.nbbang.domain.RideSettings
import com.nbbang.domain.Ride
import com.nbbang.domain.RideEvent
import com.nbbang.domain.RideGame
import com.nbbang.domain.RideTracker
import com.nbbang.domain.Seat
import com.nbbang.domain.TripMode
import com.nbbang.domain.TripSetup
import com.nbbang.domain.removeCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 차 신호 → 주행 기록 → 정산까지. 기획서 7장 계산 예가 끝까지 그대로 나오는지 본다. */
class RideSettlementTest {
    @Test
    fun `설정 연료가 차량 센서보다 우선하고 진행 중에는 유지된다`() {
        val prices = Prices(kmPerLiter = 10.0, wonPerLiter = 1_700, kmPerKwh = 5.0, wonPerKwh = 300)
        for (fuel in listOf("휘발유", "경유", "LPG", "전기")) {
            val settings = RideSettings(fuelKind = fuel, prices = prices)
            val electric = fuel == "전기"
            val tracker = RideTracker()
            val car = CarState(
                gear = Gear.DRIVE, odometerKm = 0.0,
                batteryKwh = if (electric) null else 50.0,
                fuelPercent = if (electric) 60 else null,
                kmPerLiter = 11.0, kmPerKwh = 6.0,
                seated = seated(Seat.FRONT),
            )
            tracker.update(car, 0, prices = prices, power = settings.power)
            val moved = car.copy(odometerKm = 10.0, batteryKwh = car.batteryKwh?.minus(2.0))
            tracker.update(moved, 1_000, prices = Prices(), power = if (electric) Power.FUEL else Power.ELECTRIC)
            val ride = tracker.preview()!!
            assertEquals(settings.power, ride.power)
            // 차가 알려 준 연비(11.0km/L, 6.0km/kWh)가 설정 평균(10.0, 5.0)보다 좋아서 실제로 쓴 만큼 나눈다.
            assertEquals(if (electric) 500 else 1_545, ride.settlement().fuelWon)
            assertEquals(if (electric) 6.0 else 11.0, ride.carEfficiency)
            assertNull(ride.segments.single().energyUsed)
            val finished = (tracker.finish(2_000) as RideEvent.Finished).ride
            assertEquals(ride.power, finished.power)
            assertEquals(ride.carEfficiency, finished.carEfficiency)
            assertEquals(ride.settlement().fuelWon, finished.settlement().fuelWon)
        }
    }

    private val prices = Prices(kmPerLiter = 12.0, wonPerLiter = 1_650)
    private val all = setOf(DRIVER_ID, 1, 2)

    private fun seated(vararg seats: Seat) = Seat.passengers.associateWith { it in seats }

    /** 김민수(조수석)·이지은(뒷좌석 왼쪽)과 출발, 20km·90km 톨게이트, 60km에서 김민수 하차, 120km 도착. */
    private fun specTrip(costs: List<Cost>): Ride {
        val tracker = RideTracker(stableMs = 0)
        tracker.prepare(TripSetup(mode = TripMode.TRAVEL))
        var car = CarState(gear = Gear.PARK, odometerKm = 0.0, fuelPercent = 60, kmPerLiter = 13.0, seated = seated(Seat.FRONT, Seat.REAR_LEFT))
        tracker.update(car, 0, prices = prices)
        car = car.copy(gear = Gear.DRIVE)
        tracker.update(car, 1_000, prices = prices)
        tracker.rename(1, "김민수")
        tracker.rename(2, "이지은")

        car = car.copy(odometerKm = 20.0)
        tracker.update(car, 2_000, prices = prices)
        val first = tracker.tollgatePassed(2_000, "서울")!!
        tracker.edit { r -> r.copy(tollPasses = r.tollPasses.mapIndexed { i, t -> if (i == first) t.copy(won = 3_200) else t }) }
        tracker.edit { r -> r.copy(costs = r.costs + costs) }

        car = car.copy(odometerKm = 60.0)
        tracker.update(car, 3_000, prices = prices)
        car = car.copy(seated = seated(Seat.REAR_LEFT))
        assertTrue(tracker.update(car, 4_000, prices = prices).single() is RideEvent.Alighted)

        car = car.copy(odometerKm = 90.0)
        tracker.update(car, 5_000, prices = prices)
        val second = tracker.tollgatePassed(5_000, "강릉")!!
        tracker.edit { r -> r.copy(tollPasses = r.tollPasses.mapIndexed { i, t -> if (i == second) t.copy(won = 3_200) else t }) }

        car = car.copy(odometerKm = 120.0, gear = Gear.PARK)
        tracker.update(car, 6_000, prices = prices)
        return (tracker.finish(7_000) as RideEvent.Finished).ride
    }

    private val food = Cost("먹은 음식", 6_000, all)
    private val carPay = Cost("휴게소", 4_500, all, ExpenseSource.CAR_PAY)
    private val chicken = Cost("치킨", 12_000, all, ExpenseSource.SEPARATE)

    private fun Settlement.finalOf(id: Int) = shares.first { it.riderId() == id }.finalWon

    @Test
    fun `비용을 지워도 게임은 고른 비용에 그대로 걸린다`() {
        val parking = Cost("주차비", 3_000, all)
        // [주차비, 치킨, 먹은 음식] 중 치킨(1번)에 독박
        val ride = specTrip(listOf(parking, chicken, food)).copy(
            game = RideGame(GameMode.ALL_IN, 12_000, all, loser = 2, costIndexes = listOf(1)),
        )
        val removed = ride.removeCost(0)

        assertEquals(listOf("치킨", "먹은 음식"), removed.costs.map { it.name })
        assertEquals(listOf(0), removed.game?.costIndexes)
        assertEquals(ride.settlement().finalOf(2) - 1_000, removed.settlement().finalOf(2))
        // 게임이 고른 비용을 지우면 고른 게 없어서 게임 결과도 지운다
        assertNull(ride.removeCost(1).game)
        // 전체 금액·금액 지정 게임은 비용 순번과 상관없다
        val whole = ride.copy(game = ride.game?.copy(whole = true, costIndexes = null))
        assertEquals(whole.game, whole.removeCost(0).game)
    }

    @Test
    fun `기획서 계산 예가 차 신호부터 그대로 나온다`() {
        val ride = specTrip(listOf(food, carPay, chicken)).copy(
            unitWonOverride = 1_700,
            savingAccepted = true,
            game = RideGame(GameMode.ALL_IN, 12_000, all, loser = 2, game = "주사위"),
        )
        val s = ride.settlement()

        assertEquals(TripMode.TRAVEL, ride.mode)
        assertEquals(listOf(20.0, 90.0), ride.tollPasses.map { it.km })
        assertEquals(setOf(DRIVER_ID, 2), ride.tollPasses[1].aboard)
        assertEquals(1_308, s.savingWon)
        assertEquals(763, s.savingAppliedWon)
        // 기획서(평균 연비로 나누고 절약액을 또 옮김)는 11,950 · 8,050 · 25,900. 동승자는 평균 연비 기준 금액을 낸다.
        assertEquals(11_942, s.finalOf(DRIVER_ID))
        assertEquals(7_400, s.finalOf(1))
        assertEquals(25_250, s.finalOf(2))
        assertEquals("김민수", s.shares.first { it.riderId() == 1 }.person.name)
        assertTrue(s.readyToBill)
    }

    @Test
    fun `T-03 예시 - 실제 연비를 모르면 평균 연비, 1원 단위로 내리고 끝전 2원은 N빵이 낸다`() {
        val s = specTrip(listOf(food)).copy(carEfficiency = null).settlement()

        // 기획서의 10원 절사로는 11,550 · 5,810 · 11,540.
        assertEquals(0, s.savingWon)
        assertEquals(16_500, s.fuelWon)
        assertEquals(11_541, s.finalOf(DRIVER_ID))
        assertEquals(5_816, s.finalOf(1))
        assertEquals(11_541, s.finalOf(2))
        assertEquals(2, s.roundingWon)
    }

    @Test
    fun `T-03 예시 - 실제 연비가 좋으면 실제로 쓴 만큼 나눈다(절약 기여는 묻기 전)`() {
        val s = specTrip(listOf(food)).settlement()

        // 실제 13.0km/L: 구간마다 60 ÷ 13 × 1,650 = 7,615원. 평균 12.0이면 8,250원이라 절약액 16,500 − 15,230 = 1,270원.
        assertEquals(15_230, s.fuelWon)
        assertEquals(1_270, s.savingWon)
        assertEquals(0, s.savingAppliedWon)
        assertEquals(11_012, s.finalOf(DRIVER_ID))
        assertEquals(5_605, s.finalOf(1))
        assertEquals(11_012, s.finalOf(2))
        assertEquals(1, s.roundingWon)
    }

    @Test
    fun `미입력 톨비가 있으면 청구서를 만들 수 없다`() {
        val ride = specTrip(emptyList())
        val s = ride.copy(tollPasses = ride.tollPasses.map { it.copy(won = null) }).settlement()

        assertEquals(2, s.pendingTolls)
        assertEquals(0, s.tollWon)
        assertTrue(!s.readyToBill)
    }

    @Test
    fun `같은 톨게이트를 60초 안에 또 지나면 한 건`() {
        val tracker = RideTracker(stableMs = 0)
        val car = CarState(gear = Gear.DRIVE, odometerKm = 10.0, fuelPercent = 50)
        tracker.update(car, 1_000)

        assertEquals(0, tracker.tollgatePassed(2_000, "판교"))
        assertNull(tracker.tollgatePassed(30_000, "판교"))
        assertEquals(1, tracker.tollgatePassed(70_000, "판교"))
        assertEquals(2, tracker.tollgatePassed(71_000, "서울"))
    }

    @Test
    fun `재탑승(기존 인원)은 한 사람으로 센다`() {
        val base = specTrip(emptyList())
        // 김민수가 60km에 내렸다가 뒷좌석 오른쪽으로 다시 탄 것처럼 3번을 붙인다.
        val reboard = base.riders.first { it.id == 1 }.copy(id = 3, seat = Seat.REAR_RIGHT, boardedKm = 60.0, sameAs = 1)
        val ride = base.copy(
            riders = base.riders + reboard,
            segments = base.segments.mapIndexed { i, s -> if (i == 1) s.copy(riderIds = s.riderIds + 3) else s },
        )
        val s = ride.settlement()

        assertEquals(3, s.shares.size)
        assertEquals(120.0, s.shares.first { it.riderId() == 1 }.km, 1e-6)
        assertTrue(s.balanced)
    }

    @Test
    fun `쏘기(MVP)는 동승자 모두 0원`() {
        val s = specTrip(listOf(food)).copy(treated = true).settlement()

        assertEquals(0, s.finalOf(1))
        assertEquals(0, s.finalOf(2))
        assertEquals(s.costWon, s.finalOf(DRIVER_ID))
    }

    @Test
    fun `게임 걸린 사람이 OFF면 게임은 빼고 계산한다`() {
        val ride = specTrip(listOf(chicken)).copy(
            optedOut = setOf(2),
            game = RideGame(GameMode.ALL_IN, 12_000, all, loser = 2),
        )
        val s = ride.settlement()

        assertEquals(0, s.finalOf(2))
        assertEquals(0.0, s.shares.sumOf { it.gameWon }, 1e-6)
        assertTrue(s.balanced)
    }
}
