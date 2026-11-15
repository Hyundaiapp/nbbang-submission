package com.nbbang.domain

import com.nbbang.settle.expectedEfficiency
import com.nbbang.settle.expectedName
import com.nbbang.settle.explain
import com.nbbang.settle.invoice
import com.nbbang.settle.officialBaseline
import com.nbbang.settle.savingQuestionDue
import com.nbbang.settle.settlement
import java.time.ZoneId
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelEconomyTest {
    private val minute = 60_000L

    @Test
    fun `공인연비 범위의 가운데 값을 도심·고속 대표값으로 쓴다`() {
        assertEquals(12.1, CarModel.AVANTE_2027.cityKmPerLiter, 1e-9)
        assertEquals(16.9, CarModel.AVANTE_2027.highwayKmPerLiter, 1e-9)
        assertEquals(8.1, CarModel.GRANDEUR_2027.cityKmPerLiter, 1e-9)
        assertEquals(12.15, CarModel.GRANDEUR_2027.highwayKmPerLiter, 1e-9)
    }

    @Test
    fun `기준연비는 거리 비중으로 기름 양을 더한 값(가중 조화평균)이다`() {
        val avante = CarModel.AVANTE_2027
        assertEquals(12.1, avante.baseline(DrivingMix(cityKm = 10.0)).kmPerLiter, 1e-9)
        assertEquals(16.9, avante.baseline(DrivingMix(highwayKm = 10.0)).kmPerLiter, 1e-9)
        // 반반이면 10km ÷ (5 ÷ 12.1 + 5 ÷ 16.9) = 14.1. 연비를 그냥 평균한 14.5가 아니다(그러면 기름 양이 맞지 않는다).
        val half = avante.baseline(DrivingMix(cityKm = 5.0, highwayKm = 5.0))
        assertEquals(14.1, half.kmPerLiter, 1e-9)
        assertEquals(50, half.highwayPercent)
        assertTrue(half.measured)
        // 기름 양으로 확인: 기준연비로 쓴 기름 = 도심·고속 공인연비로 각각 쓴 기름의 합(반올림 오차 안).
        val liters = 5.0 / 12.1 + 5.0 / 16.9
        assertEquals(liters, 10.0 / half.kmPerLiter, 0.002)
    }

    @Test
    fun `도심·고속을 못 쟀으면 공인 복합연비 비율 55대45로 어림한다`() {
        val unknown = CarModel.AVANTE_2027.baseline(DrivingMix())
        assertFalse(unknown.measured)
        assertEquals(45, unknown.highwayPercent)
        // 1 ÷ (0.55 ÷ 12.1 + 0.45 ÷ 16.9) = 13.87
        assertEquals(13.9, unknown.kmPerLiter, 1e-9)
        // 1 ÷ (0.55 ÷ 8.1 + 0.45 ÷ 12.15) = 9.53
        assertEquals(9.5, CarModel.GRANDEUR_2027.baseline(DrivingMix()).kmPerLiter, 1e-9)
        assertEquals("2027 아반떼 공인연비(도심 12.1 · 고속 16.9km/L)를 복합 비율(도심 55 : 고속 45)로 섞었어요", unknown.explain())
    }

    @Test
    fun `1km마다 평균 차속(주행거리 나누기 시간)이 56km_h를 넘으면 고속, 아니면 도심이다`() {
        val meter = DrivingMixMeter()
        assertNull(meter.odometer(0, 100.0))
        assertNull(meter.odometer(45_000, 100.5)) // 아직 1km가 안 됐다
        assertEquals(1.0 to false, meter.odometer(90_000, 101.0)) // 1km ÷ 90초 = 40km/h
        assertEquals(2.0 to true, meter.odometer(170_000, 103.0)) // 2km ÷ 80초 = 90km/h
        // 경계: 1km ÷ 64.3초 ≈ 56km/h는 고속, 1km ÷ 65초 ≈ 55.4km/h는 도심
        assertEquals(1.0 to true, meter.odometer(170_000 + 64_285, 104.0))
        assertEquals(1.0 to false, meter.odometer(170_000 + 64_285 + 65_000, 105.0))
    }

    @Test
    fun `멈춰 있던 시간도 평균에 들어간다 - 신호등에서 서고 가는 길은 도심`() {
        val meter = DrivingMixMeter()
        meter.odometer(0, 0.0)
        meter.odometer(60_000, 0.0) // 1분 서 있었다
        // 그 뒤 100km/h로 36초 달려 1km: 평균 = 1km ÷ 96초 = 37.5km/h
        assertEquals(1.0 to false, meter.odometer(96_000, 1.0))
    }

    @Test
    fun `시간이 안 흘렀거나 주행거리가 줄면 나누지 않고 새로 잰다`() {
        val meter = DrivingMixMeter()
        meter.odometer(0, 0.0)
        assertNull(meter.odometer(0, 5.0)) // 같은 시각에 주행거리만 늘었다(값이 몰려 옴)
        assertNull(meter.odometer(10 * minute, 1.0)) // 주행거리가 줄었다(값이 튐)
        assertEquals(1.0 to false, meter.odometer(12 * minute, 2.0))
        meter.reset()
        assertNull(meter.odometer(13 * minute, 10.0))
    }

    private val avante = Prices(wonPerLiter = 1_667, kmPerLiter = 12.0, carModel = CarModel.AVANTE_2027)
    private val parked = CarState(gear = Gear.PARK, odometerKm = 1_000.0, fuelPercent = 60, seated = mapOf(Seat.FRONT to true))

    /** 도심 3km(40km/h) → 고속 5km(90km/h)를 달리고 실제 연비 16.0km/L로 끝낸 아반떼 여행. */
    private fun cityThenHighway(prices: Prices = avante, restoreMidway: Boolean = false): Ride {
        var tracker = RideTracker()
        tracker.startTravel(TripSetup(mode = TripMode.TRAVEL, names = listOf("민지")), parked, 0, Place(), prices, SplitRule.EVERYONE, Power.FUEL)
        val drive = parked.copy(gear = Gear.DRIVE)
        tracker.update(drive, 0, prices = prices, power = Power.FUEL)
        var at = 0L
        for (km in 1..3) {
            at += 90_000 // 1km ÷ 40km/h
            tracker.update(drive.copy(odometerKm = 1_000.0 + km), at, prices = prices, power = Power.FUEL)
        }
        if (restoreMidway) {
            tracker = RideTracker().apply { restore(tracker.snapshot(at)!!) }
            tracker.update(drive.copy(odometerKm = 1_003.0), at, prices = prices, power = Power.FUEL)
        }
        for (km in 4..8) {
            at += 40_000 // 1km ÷ 90km/h
            tracker.update(drive.copy(odometerKm = 1_000.0 + km), at, prices = prices, power = Power.FUEL)
        }
        val end = drive.copy(gear = Gear.PARK, odometerKm = 1_008.0, kmPerLiter = 16.0)
        tracker.update(end, at + 1_000, prices = prices, power = Power.FUEL)
        return (tracker.finish(at + 2_000, end) as RideEvent.Finished).ride
    }

    @Test
    fun `달린 길의 도심·고속 비중으로 기준연비를 정하고 실제 연비와 비교해 절약액을 낸다`() {
        val ride = cityThenHighway()
        assertEquals(DrivingMix(cityKm = 3.0, highwayKm = 5.0), ride.drivingMix)
        val baseline = ride.officialBaseline()!!
        // 8km ÷ (3 ÷ 12.1 + 5 ÷ 16.9) = 14.7km/L
        assertEquals(14.7, baseline.kmPerLiter, 1e-9)
        assertEquals(63, baseline.highwayPercent)
        assertEquals(14.7, ride.expectedEfficiency(), 1e-9)
        assertEquals("기준", ride.expectedName())

        // (기준연비로 쓸 기름 − 실제로 쓴 기름) × 단가. 유류비는 1원 단위로 반올림한다.
        val s = ride.settlement()
        val expected = (8 / 14.7 * 1_667).roundToInt() - (8 / 16.0 * 1_667).roundToInt()
        assertEquals(expected, s.savingWon)
        assertTrue(s.savingWon > 0)
        assertTrue(ride.savingQuestionDue())

        val share = s.riders.single()
        val notes = ride.invoice(share, s, Payee("운전자"), ZoneId.of("Asia/Seoul")).notes
        assertTrue(notes.toString(), notes.contains("실제 연비 16.0km/L(기준 14.7km/L) · 리터당 1,667원"))
        assertTrue(notes.toString(), notes.contains("기준 연비: 2027 아반떼 공인연비(도심 12.1 · 고속 16.9km/L)를 이번 주행 고속 63%로 섞었어요"))
    }

    @Test
    fun `앱을 다시 켜도 그때까지 나눈 도심·고속 거리는 남는다`() {
        assertEquals(DrivingMix(cityKm = 3.0, highwayKm = 5.0), cityThenHighway(restoreMidway = true).drivingMix)
    }

    @Test
    fun `차종을 안 골랐거나 전기차면 C-02에서 넣은 평균 연비를 그대로 쓴다`() {
        val manual = cityThenHighway(avante.copy(carModel = null))
        assertNull(manual.officialBaseline())
        assertEquals(12.0, manual.expectedEfficiency(), 1e-9)
        assertEquals("평균", manual.expectedName())

        val ev = manual.copy(power = Power.ELECTRIC, prices = avante.copy(kmPerKwh = 5.0))
        assertNull(ev.officialBaseline())
        assertEquals(5.0, ev.expectedEfficiency(), 1e-9)
    }

    @Test
    fun `실제 연비가 기준연비보다 나쁘면 절약 기여를 묻지 않는다`() {
        // 그랜저 고속 위주(기준 ≈ 11.2km/L)인데 실제 10.0km/L
        val ride = cityThenHighway(avante.copy(carModel = CarModel.GRANDEUR_2027)).copy(carEfficiency = 10.0)
        assertNotNull(ride.officialBaseline())
        assertTrue(ride.expectedEfficiency() > 10.0)
        assertFalse(ride.savingQuestionDue())
        assertEquals(0, ride.settlement().savingWon)
    }
}
