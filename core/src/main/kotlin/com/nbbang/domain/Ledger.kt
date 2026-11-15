package com.nbbang.domain

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs

/** 한 사람의 아직 받지 않은 몫. */
data class PersonBalance(
    val name: String,
    val rides: Int,
    val km: Double,
    val owedWon: Int,
    val lastAt: Long,
)

/** 장부 첫 화면에 보여 줄 것. */
data class LedgerSummary(
    val people: List<PersonBalance> = emptyList(),
    /** 이름을 붙이지 않은 사람들이 아직 내지 않은 몫. */
    val unnamedOwedWon: Int = 0,
    val monthRides: Int = 0,
    val monthKm: Double = 0.0,
    /** 이번 달 동승자 몫의 합(받은 것 포함). */
    val monthSharedWon: Int = 0,
) {
    val owedWon: Int get() = people.sumOf { it.owedWon } + unnamedOwedWon
}

/** 아직 받지 않은 몫을 사람별로 모은다. "쏜" 길은 빼고, 사람은 최근에 함께 탄 순서다. */
fun summarize(rides: List<Ride>, now: Long, zone: ZoneId): LedgerSummary {
    val month = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone))
    val byName = linkedMapOf<String, PersonBalance>()
    var unnamed = 0
    var monthRides = 0
    var monthKm = 0.0
    var monthShared = 0
    rides.filter { it.finished }.sortedByDescending { it.startedAt }.forEach { ride ->
        val bill = ride.bill()
        if (YearMonth.from(Instant.ofEpochMilli(ride.startedAt).atZone(zone)) == month) {
            monthRides += 1
            monthKm += bill.km
            monthShared += bill.shares.sumOf { it.won }
        }
        if (ride.treated) return@forEach
        bill.shares.filter { !it.paid && it.won > 0 }.forEach { share ->
            val name = share.rider.name
            if (name == null) {
                unnamed += share.won
                return@forEach
            }
            val before = byName[name]
            byName[name] = PersonBalance(
                name = name,
                rides = (before?.rides ?: 0) + 1,
                km = (before?.km ?: 0.0) + share.km,
                owedWon = (before?.owedWon ?: 0) + share.won,
                lastAt = maxOf(before?.lastAt ?: 0L, ride.startedAt),
            )
        }
    }
    return LedgerSummary(byName.values.toList(), unnamed, monthRides, monthKm, monthShared)
}

/** 이 사람의 아직 받지 않은 몫과 그 길. 최근 것부터. */
fun unpaidFor(rides: List<Ride>, name: String): List<Pair<Ride, RiderShare>> =
    rides.filter { it.finished && !it.treated }
        .sortedByDescending { it.startedAt }
        .flatMap { ride -> ride.bill().shares.filter { !it.paid && it.won > 0 && it.rider.name == name }.map { ride to it } }

/** 이 사람의 몫을 모두 받은 것으로 적는다. */
fun settle(rides: List<Ride>, name: String): List<Ride> = rides.map { ride ->
    val ids = ride.riders.filter { it.name == name }.map { it.id }
    if (ids.isEmpty()) ride else ride.copy(paidRiderIds = ride.paidRiderIds + ids)
}

/** 이 길의 몫을 모두 받은 것으로 적는다(QR로 그 자리에서 받았을 때). */
fun Ride.paidAll(): Ride = copy(paidRiderIds = riders.map { it.id }.toSet())

/** 함께 탄 적 있는 이름. 최근에 탄 사람부터. */
fun knownNames(rides: List<Ride>): List<String> =
    rides.sortedByDescending { it.startedAt }.flatMap { ride -> ride.riders.mapNotNull { it.name } }.distinct()

/**
 * 지난 기록으로 이 자리에 탄 사람을 짐작한다.
 * 같은 자리에, 비슷한 시각(앞뒤 1시간)에, 같은 평일·주말에, 가까운 곳(400m)에서 두 번 이상 탄 이름이면 그 이름이다.
 * 매일 같이 출퇴근하는 동료는 한두 번 이름을 붙이면 다음부터 저절로 붙는다.
 */
fun guessName(seat: Seat, at: Long, place: Place, history: List<Ride>, zone: ZoneId): String? {
    val minute = minuteOfDay(at, zone)
    val weekend = isWeekend(at, zone)
    val votes = history.flatMap { it.riders }
        .filter { r ->
            val name = r.name
            name != null && !r.nameGuessed && r.seat == seat &&
                abs(minuteOfDay(r.boardedAt, zone) - minute) <= GUESS_WINDOW_MIN &&
                isWeekend(r.boardedAt, zone) == weekend &&
                near(place.point, r.boardedPlace.point)
        }
        .groupingBy { it.name!! }
        .eachCount()
    val best = votes.maxByOrNull { it.value } ?: return null
    return best.key.takeIf { best.value >= GUESS_MIN_TIMES }
}

private fun near(a: GeoPoint?, b: GeoPoint?): Boolean = a == null || b == null || a.metersTo(b) <= GUESS_NEAR_M

private fun minuteOfDay(at: Long, zone: ZoneId): Int {
    val time = Instant.ofEpochMilli(at).atZone(zone)
    return time.hour * 60 + time.minute
}

private fun isWeekend(at: Long, zone: ZoneId): Boolean = Instant.ofEpochMilli(at).atZone(zone).dayOfWeek.value >= 6

private const val GUESS_WINDOW_MIN = 60
private const val GUESS_MIN_TIMES = 2
private const val GUESS_NEAR_M = 400.0
