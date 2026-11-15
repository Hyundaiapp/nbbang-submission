// 기획서 8장 데이터 중 주행 기록(Ride)에 덧붙이는 것. 정산은 com.nbbang.settle에서 한다.
package com.nbbang.domain

import com.nbbang.settle.ExpenseSource
import com.nbbang.settle.GameMode

/** 운전자의 id. 동승자([Rider.id])는 1부터 센다. */
const val DRIVER_ID = 0

/** 기획서의 두 트랙. 카풀은 월별 누적(R10), 여행은 그때그때 N빵. */
/** [key]는 청구서 QR(`m`)에 넣는 이름이다. */
enum class TripMode(val label: String, val key: String) {
    CARPOOL("카풀", "carpool"),
    TRAVEL("여행", "trip"),
}

/** 출발 전에 고르는 것(K-01·K-03, T-01). 길이 시작되면(D) [Ride]로 옮긴다. */
data class TripSetup(
    val mode: TripMode = TripMode.TRAVEL,
    val groupId: String? = null,
    /** 오늘 탈 사람(K-03 체크, T-01 추가). C-04에서 이름 칩으로 먼저 보여 준다. */
    val names: List<String> = emptyList(),
    /**
     * K-03에서 고른 자리(이름 → 자리). 고른 자리에 바로 앉히고, 그 자리 센서가 안 잡혔는데 다른 자리가 잡히면 그리로 옮긴다.
     * 비어 있으면(T-01, 예전 저장본) 감지된 자리에 [names] 순서대로 붙인다.
     */
    val seats: Map<String, Seat> = emptyMap(),
) {
    /** [seats]가 [names]마다 하나씩, 서로 다른 동승자 자리면 그 자리들([names] 순서). 하나라도 빠지거나 겹치면 null. */
    val seatPlan: List<Seat>?
        get() {
            if (names.isEmpty()) return null
            val plan = names.map { seats[it] ?: return null }
            return plan.takeIf { it.toSet().size == it.size && it.all { seat -> seat in Seat.passengers } }
        }
}

/** K-03 자리 고르기. 화면이 들고 있는 이름 → 자리 표를 바꾸는 순수 함수들. */
object SeatPicker {
    /**
     * [names] 순서대로 자리를 채운다. 이미 고른 자리는 그대로 두고, 자리가 없는 사람은 [Seat.preferred]에서 빈 자리를 받는다.
     * [names]에 없는 사람의 자리는 비운다. 같은 자리를 둘이 가졌으면 앞사람만 남긴다.
     */
    fun fill(names: List<String>, seats: Map<String, Seat>): Map<String, Seat> {
        val used = mutableSetOf<Seat>()
        val kept = names.mapNotNull { name ->
            seats[name]?.takeIf { it in Seat.passengers && used.add(it) }?.let { name to it }
        }.toMap()
        return names.mapNotNull { name ->
            (kept[name] ?: Seat.preferred.firstOrNull { it !in used }?.also { used += it })?.let { name to it }
        }.toMap()
    }

    /** [name]을 [seat]로 옮긴다. 그 자리에 다른 사람이 있으면 서로 자리를 바꾼다. */
    fun move(seats: Map<String, Seat>, name: String, seat: Seat): Map<String, Seat> {
        val from = seats[name] ?: return seats
        if (seat !in Seat.passengers || seat == from) return seats
        val other = seats.entries.firstOrNull { it.value == seat }?.key
        return seats.mapValues { (who, at) ->
            when (who) {
                name -> seat
                other -> from
                else -> at
            }
        }
    }
}

/**
 * 하이패스 통과 한 건(C-06). 내비가 톨게이트를 지났다고 알리면 금액 없이 남긴다.
 * [won]이 null이면 아직 입력하지 않았다(나중에 입력). [aboard]는 통과할 때 탄 사람이다(운전자 포함).
 */
data class TollPass(
    val at: Long,
    val km: Double,
    val aboard: Set<Int>,
    val name: String = "",
    val won: Int? = null,
)

/**
 * 추가 비용(C-07 주행 중 입력, C-10 별도 결제, 카투페이).
 * [sharedBy]는 나눠 낼 사람(기본: 입력할 때 탄 사람 전원), [paidBy]는 실제로 낸 사람이다. 운전자는 [DRIVER_ID].
 */
data class Cost(
    val name: String,
    val won: Int,
    val sharedBy: Set<Int>,
    val source: ExpenseSource = ExpenseSource.DRIVING,
    val paidBy: Int = DRIVER_ID,
    val at: Long = 0L,
)

/** C-12~C-16 게임 결과. 사람은 [Rider.id]나 [DRIVER_ID]로 가리킨다. */
data class RideGame(
    val mode: GameMode,
    val targetWon: Int,
    val players: Set<Int>,
    val loser: Int,
    val loserShare: Double = if (mode == GameMode.ALL_IN) 1.0 else 0.5,
    /** 사다리타기, 야바위(화면 이름 "컵 돌리기"), 카드 뽑기, 주사위. 청구서 문구(F-C29)에 쓴다. 저장 값이라 바꾸지 않는다. */
    val game: String = "",
    /** C-12 "전체 금액". 참이면 [targetWon]·[costIndexes]는 보지 않는다. */
    val whole: Boolean = false,
    /** C-12에서 고른 추가 비용 항목([Ride.costs]의 순번). null이면 [targetWon]을 균등 몫에서 뗀다. */
    val costIndexes: List<Int>? = null,
)
