// 운전자 절약 기여의 기준: 차종별 공인연비(도심·고속)를 이번 주행의 도심·고속 거리 비중으로 섞은 기준연비.
//
// 1. 공인연비는 트림·휠마다 범위로 나온다. 범위의 가운데 값을 그 차의 도심·고속 대표값으로 쓴다.
// 2. 달리는 동안 1km마다 평균 차속(주행거리 ÷ 걸린 시간)을 보고 도심·고속을 나눈다(아래 [HIGHWAY_KMH]).
// 3. 기준연비 = 총거리 ÷ (도심 거리 ÷ 도심 연비 + 고속 거리 ÷ 고속 연비).
//    거리 비중으로 섞되 기름 양으로 더한다(가중 조화평균). 공인 복합연비 = 1 ÷ (0.55 ÷ 도심 + 0.45 ÷ 고속)과 같은 식이고,
//    고정 비율 55:45 대신 이번 주행에서 잰 비율을 쓴다. 연비를 그냥 산술 평균하면 기름 양이 맞지 않는다.
// 4. 주행이 끝나면 실제 평균 연비와 비교한다. (기준연비로 쓸 기름 − 실제로 쓴 기름) × 그날 단가 = 절약액.
//    공인연비는 시험실 값이라 실제 길과 다르다. 그래서 절약액은 화면·청구서 어디서나 "추정"으로 보여 준다.
package com.nbbang.domain

import kotlin.math.roundToLong

/** 공인연비 범위(km/L)의 가운데 값. 소수 둘째 자리까지. */
private fun ClosedFloatingPointRange<Double>.middle(): Double = ((start + endInclusive) / 2 * 100).roundToLong() / 100.0

/**
 * Pleos가 들어간 두 차종. [city]·[highway]는 2027년형 공인연비(km/L) 범위로, 트림·휠에 따라 이 안에 든다.
 * 한국에너지공단 표시 기준의 도심(FTP-75)·고속(HWFET) 값이다.
 */
enum class CarModel(
    val label: String,
    val city: ClosedFloatingPointRange<Double>,
    val highway: ClosedFloatingPointRange<Double>,
) {
    AVANTE_2027("2027 아반떼", 11.7..12.5, 16.5..17.3),
    GRANDEUR_2027("2027 그랜저", 6.3..9.9, 9.4..14.9),
    ;

    val cityKmPerLiter: Double get() = city.middle()
    val highwayKmPerLiter: Double get() = highway.middle()

    /**
     * 이번 주행의 기준연비. 도심·고속 비율을 재지 못했으면(1km를 못 달렸거나 신호가 없으면) 공인 복합연비 비율(55:45)을 쓴다.
     * 실제 연비(C-11)와 같이 소수 첫째 자리에서 반올림한다. 그래야 화면 숫자로 손계산한 절약액과 같다.
     */
    fun baseline(mix: DrivingMix): OfficialBaseline {
        val share = mix.highwayShare ?: COMBINED_HIGHWAY_SHARE
        val raw = 1.0 / ((1 - share) / cityKmPerLiter + share / highwayKmPerLiter)
        return OfficialBaseline(this, (raw * 10).roundToLong() / 10.0, share, measured = mix.highwayShare != null)
    }

    companion object {
        /** 공인 복합연비를 낼 때 쓰는 고속 비중(도심 55 : 고속 45). */
        const val COMBINED_HIGHWAY_SHARE = 0.45

        fun of(name: String?): CarModel? = entries.firstOrNull { it.name == name }
    }
}

/** 공인연비로 정한 이번 주행의 기준연비(km/L). [highwayShare]는 고속 거리 비중(0~1), [measured]가 false면 복합 비율로 어림했다. */
data class OfficialBaseline(val model: CarModel, val kmPerLiter: Double, val highwayShare: Double, val measured: Boolean) {
    val highwayPercent: Int get() = Math.round(highwayShare * 100).toInt()
}

/** 이번 주행에서 도심·고속으로 나눈 거리(km). 나누지 못한 거리(앱이 꺼져 있던 동안 등)는 들어 있지 않다. */
data class DrivingMix(val cityKm: Double = 0.0, val highwayKm: Double = 0.0) {
    val km: Double get() = cityKm + highwayKm
    val highwayShare: Double? get() = if (km > 0.0) highwayKm / km else null

    fun plus(km: Double, highway: Boolean): DrivingMix =
        if (highway) copy(highwayKm = highwayKm + km) else copy(cityKm = cityKm + km)
}

/**
 * 도심·고속을 가르는 평균 차속(km/h). 공인연비 시험의 도심 주행(FTP-75)은 평균 34.1km/h, 고속 주행(HWFET)은 평균 77.7km/h다.
 * 두 시험의 한가운데(약 56km/h)를 경계로, 이보다 빠르게 달린 1km는 고속, 아니면 도심으로 본다.
 */
const val HIGHWAY_KMH = 56.0

/**
 * 달리면서 1km([windowKm])마다 평균 차속(그 사이 주행거리 ÷ 걸린 시간)을 보고 그 거리를 도심이나 고속으로 나눈다.
 * 멈춰 있던 시간도 평균에 들어간다. 신호등에서 서고 가는 길은 도심, 쭉 달리는 길은 고속이 된다. 시험 주행의 평균 차속과 같은 셈이다.
 * 주행거리와 시각만 쓴다. Pleos SDK에는 차속 신호가 없고, 따로 권한을 받을 필요도 없다.
 */
class DrivingMixMeter(private val windowKm: Double = 1.0, private val highwayKmh: Double = HIGHWAY_KMH) {
    private var startKm: Double? = null
    private var startAt = 0L

    /**
     * 주행거리 한 번. 창이 [windowKm]만큼 찼으면 그 거리를 나눠 돌려준다(거리, 고속인지). 아직이면 null.
     * 첫 값이거나 주행거리가 줄었으면(값이 튐) 여기서 새로 잰다. 시간이 흐르지 않았으면(값이 몰려 옴) 나누지 않는다.
     */
    fun odometer(at: Long, km: Double): Pair<Double, Boolean>? {
        val start = startKm
        if (start == null || km < start) {
            begin(at, km)
            return null
        }
        val driven = km - start
        if (driven < windowKm) return null
        val hours = (at - startAt) / 3_600_000.0
        begin(at, km)
        if (hours <= 0.0) return null
        return driven to (driven / hours >= highwayKmh)
    }

    /** 처음부터 다시 잰다(새 길, 저장한 길을 다시 열 때). */
    fun reset() {
        startKm = null
    }

    private fun begin(at: Long, km: Double) {
        startKm = km
        startAt = at
    }
}
