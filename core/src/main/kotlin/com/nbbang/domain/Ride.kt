package com.nbbang.domain

import java.time.YearMonth
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 차의 자리. 운전석은 정산에서 운전자 몫으로만 쓴다. */
enum class Seat(val label: String, val short: String = label) {
    DRIVER("운전석"),
    FRONT("조수석"),
    REAR_LEFT("뒷좌석 왼쪽", "뒤 왼쪽"),
    REAR_CENTER("뒷좌석 가운데", "뒤 가운데"),
    REAR_RIGHT("뒷좌석 오른쪽", "뒤 오른쪽"),

    /** 3열. 센서를 연결하지 않아서 C-04 수동 추가로만 쓴다(기획서 C-04 최대 6명). */
    THIRD_LEFT("3열 왼쪽"),
    THIRD_RIGHT("3열 오른쪽"),
    ;

    companion object {
        val passengers = listOf(FRONT, REAR_LEFT, REAR_CENTER, REAR_RIGHT, THIRD_LEFT, THIRD_RIGHT)

        /** K-03에서 체크한 순서대로 자동으로 고를 자리. 창가부터(가운데는 뒤에). */
        val preferred = listOf(FRONT, REAR_LEFT, REAR_RIGHT, REAR_CENTER, THIRD_LEFT, THIRD_RIGHT)
    }
}

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

enum class Door { DRIVER, FRONT, REAR_LEFT, REAR_RIGHT, TRUNK }

/** 무엇으로 달리는 차인지. 전기차는 배터리 에너지(kWh), 내연기관차는 기름(L)으로 값을 매긴다. */
enum class Power(val unit: String, val energyName: String) {
    ELECTRIC("kWh", "전기"),
    FUEL("L", "기름"),
}

data class GeoPoint(val latitude: Double, val longitude: Double) {
    fun metersTo(other: GeoPoint): Double {
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
    }
}

/** 타고 내린 곳. 주소는 내비가, 좌표는 위치 SDK가 준다. 둘 다 없을 수 있다. */
data class Place(val address: String? = null, val point: GeoPoint? = null)

/** 차 신호를 한데 모은 것. 신호가 오지 않은 값은 null이거나 비어 있다. */
data class CarState(
    val gear: Gear = Gear.UNKNOWN,
    /** 탑승 신호가 온 자리만 들어 있다. */
    val seated: Map<Seat, Boolean> = emptyMap(),
    val openDoors: Set<Door> = emptySet(),
    val odometerKm: Double? = null,
    /** 배터리에 남은 에너지(kWh). */
    val batteryKwh: Double? = null,
    val socPercent: Int? = null,
    val fuelPercent: Int? = null,
    /** 차가 알려 준 평균 전비(km/kWh). */
    val kmPerKwh: Double? = null,
    /** 차가 알려 준 평균 연비(km/L). */
    val kmPerLiter: Double? = null,
) {
    val power: Power get() = if (batteryKwh == null && socPercent == null && fuelPercent != null) Power.FUEL else Power.ELECTRIC
    val moving: Boolean get() = gear == Gear.DRIVE || gear == Gear.REVERSE
    val efficiency: Double? get() = if (power == Power.ELECTRIC) kmPerKwh else kmPerLiter
    val passengersAboard: Int get() = Seat.passengers.count { seated[it] == true }
}

enum class SplitRule(val label: String, val detail: String) {
    EVERYONE("운전자 포함", "운전자도 함께 탄 사람으로 쳐서 똑같이 나눠요"),
    PASSENGERS("동승자만", "운전자는 빼고 동승자끼리 나눠요"),
}

/**
 * 값을 매기는 기준. 효율은 차가 알려 주면 그 값을 먼저 쓴다.
 * 리터당 단가는 당일 공시 평균 유가(F-C07, [com.nbbang.settle.OfficialPrice])를 먼저 쓰고, 못 불러오면 설정 단가를 쓴다.
 * kWh당 단가는 늘 설정 단가다.
 */
data class Prices(
    val wonPerKwh: Int = 300,
    val wonPerLiter: Int = 1_700,
    val kmPerKwh: Double = 5.0,
    val kmPerLiter: Double = 12.0,
    /** [wonPerLiter]가 공시 평균 유가일 때 그 기준일(yyyy-MM-dd). null이면 설정 단가. */
    val officialDate: String? = null,
    /**
     * C-02에서 고른 차종. 있으면 기름차의 평균 연비는 [kmPerLiter] 대신 이 차의 공인연비를
     * 주행마다 도심·고속 비중으로 섞은 기준연비([CarModel.baseline])다. null이면 직접 넣은 [kmPerLiter].
     */
    val carModel: CarModel? = null,
)

enum class ExtraKind(val label: String) {
    PARKING("주차비"),
    TOLL("통행료"),
    OTHER("기타"),
}

/** 차 신호로 알 수 없는 비용. 말이나 화면으로 더한다. 더할 때 타고 있던 사람끼리 나눈다. */
data class Extra(val kind: ExtraKind, val won: Int)

/** 한 자리에 한 사람이 앉아 있던 동안. 같은 자리라도 내렸다가 다른 사람이 타면 새 [Rider]다. */
data class Rider(
    val id: Int,
    val seat: Seat,
    val boardedAt: Long,
    val boardedKm: Double,
    val boardedPlace: Place = Place(),
    val alightedAt: Long? = null,
    val alightedKm: Double? = null,
    val alightedPlace: Place = Place(),
    val name: String? = null,
    /** 지난 기록으로 짐작한 이름이다. 화면에서 "추정"으로 보여 준다. */
    val nameGuessed: Boolean = false,
    /** C-04에서 "기존 인원"으로 고른 재탑승이면 처음 탄 사람의 id. 정산에서는 한 사람으로 센다. */
    val sameAs: Int? = null,
) {
    val label: String get() = name ?: seat.label
    val aboard: Boolean get() = alightedAt == null
}

/**
 * 탄 사람이 바뀌지 않은 한 구간. 타거나 내릴 때마다 끊는다.
 * [energyUsed]는 배터리 에너지 차이(kWh)로 잰 값이고, 재지 못했으면 null이다. 그때는 거리와 효율로 어림한다.
 */
data class Segment(
    val startKm: Double,
    val endKm: Double,
    val riderIds: Set<Int>,
    val energyUsed: Double? = null,
    val tollgates: Int = 0,
    val extras: List<Extra> = emptyList(),
) {
    val km: Double get() = (endKm - startKm).coerceAtLeast(0.0)
}

/** 함께 탄 길 하나. 출발(D)부터 운전자가 내릴 때까지다. */
data class Ride(
    val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val power: Power = Power.ELECTRIC,
    val prices: Prices = Prices(),
    val rule: SplitRule = SplitRule.EVERYONE,
    val segments: List<Segment> = emptyList(),
    val riders: List<Rider> = emptyList(),
    val destination: String? = null,
    val startPlace: Place = Place(),
    val endPlace: Place = Place(),
    /** 내비가 계산한 예상 통행료. 실제 하이패스 결제액은 SDK로 받을 수 없다. */
    val routeTollWon: Int = 0,
    /** 운전자가 고친 통행료. 있으면 예상 통행료 대신 쓴다. */
    val tollOverrideWon: Int? = null,
    val carEfficiency: Double? = null,
    /** "이번엔 내가 쏠게". 장부에는 남기지만 아무도 내지 않는다. */
    val treated: Boolean = false,
    val paidRiderIds: Set<Int> = emptySet(),
    // ───── 기획서 v1.0. 정산은 com.nbbang.settle(Ride.settlement())에서 한다. ─────
    val mode: TripMode = TripMode.TRAVEL,
    val groupId: String? = null,
    /** 하이패스 통과 기록(C-06, C-09). */
    val tollPasses: List<TollPass> = emptyList(),
    /** 추가 비용(C-07, C-10, 카투페이). */
    val costs: List<Cost> = emptyList(),
    /** C-08에서 고친 단가(원/L, 전기차는 원/kWh). null이면 설정의 단가(공시 평균)를 쓴다. */
    val unitWonOverride: Int? = null,
    /** R5에서 OFF로 바꾼 사람. 운전자는 [DRIVER_ID]. */
    val optedOut: Set<Int> = emptySet(),
    /** C-11 절약 기여 인정. null이면 아직 묻지 않았다. 인정하면 동승자는 평균 연비 기준 금액을 내고 운전자 몫이 그만큼 줄어든다(R6). */
    val savingAccepted: Boolean? = null,
    val game: RideGame? = null,
    /** K-05 카풀 정산 방식. true면 월별 정산에 추가, false면 오늘 건 따로 결제(C-18). 여행은 쓰지 않는다. */
    val monthly: Boolean = true,
    /** K-05에서 아직 정산 방식을 확정하지 않은 카풀 기록. */
    val awaitingSettlementChoice: Boolean = false,
    /**
     * K-05에서 월별 정산에 넣을 때 정한 정산 월. 나중에 정산일을 바꿔도 이 주행은 이 달에 남는다
     * (다른 달로 옮겨 가 두 번 청구되지 않게). null이면 예전 기록이라 그룹의 지금 정산일로 계산한다.
     */
    val billingMonth: YearMonth? = null,
    /** 이번 길의 도심·고속 거리. 공인연비 기준연비([Prices.carModel])에 쓴다. */
    val drivingMix: DrivingMix = DrivingMix(),
) {
    val finished: Boolean get() = endedAt != null
}

/**
 * 막 끝난 카풀은 K-05에서 정산 방식을 고르기 전까지 월별 누적에 넣지 않는다.
 * K-04 "카풀 주행 종료!"로 끝냈든, 오래 꺼 둔 앱을 켜서 끝냈든 같다. 여행은 그대로.
 */
/**
 * 함께 나눈 게 아직 없다: 동승자가 탄 채 달린 거리, 동승자와 지난 톨게이트, 동승자와 나눌 비용이 모두 없다.
 * 혼자 픽업하러 가는 길, 주차장에서 자리만 옮긴 길처럼 저절로 기록된 길이다.
 * 혼자 지난 톨게이트나 운전자 혼자 낼 비용은 나눌 게 아니라서 센다(그게 있다고 카풀·여행 시작을 막지 않는다).
 */
val Ride.sharedNothing: Boolean
    get() = costs.none { cost -> cost.sharedBy.any { it != DRIVER_ID } } &&
        tollPasses.none { pass -> pass.aboard.any { it != DRIVER_ID } } &&
        segments.none { it.riderIds.isNotEmpty() && (it.km > 0 || it.tollgates > 0 || it.extras.isNotEmpty()) }

fun Ride.unconfirmedIfCarpool(): Ride =
    if (mode == TripMode.CARPOOL) copy(monthly = false, awaitingSettlementChoice = true) else this

/**
 * C-08 추가 비용 한 줄을 지운다. 게임이 고른 비용([RideGame.costIndexes])은 순번이라 뒤 항목 순번을 하나씩 당긴다.
 * 그대로 두면 지운 뒤 다른 비용이 게임 대상이 된다. 고른 항목이 하나도 안 남으면 게임 결과를 지운다.
 */
fun Ride.removeCost(index: Int): Ride {
    if (index !in costs.indices) return this
    val g = game
    val nextGame = when {
        g?.costIndexes == null -> g
        else -> g.costIndexes.filter { it != index }.map { if (it > index) it - 1 else it }
            .takeIf { it.isNotEmpty() }?.let { g.copy(costIndexes = it) }
    }
    return copy(costs = costs.filterIndexed { i, _ -> i != index }, game = nextGame)
}
