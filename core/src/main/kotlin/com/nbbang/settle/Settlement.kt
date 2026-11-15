// 정산 엔진. 기획서 7장 정산 규칙 R1~R9를 옮겼다. C-08, C-11, C-17, C-18 화면은 이 결과를 보여 주기만 한다.
// 기획서와 다른 점
// - R1·R6: 실제 연비가 평균보다 좋으면 유류비를 실제로 쓴 만큼으로 나누고, C-11에서 인정하면 동승자는 평균 연비 기준 몫까지 내고
//   그만큼 운전자 몫을 줄인다(운전자 감면). 평균으로 나눈 뒤 절약액을 또 옮기면 같은 돈을 두 번 보상하게 되어 바꿨다.
// - R8: 10원 절사 대신 1원 단위로 내림하고 끝전은 N빵이 낸다.
package com.nbbang.settle

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** 정산에 들어가는 사람. 운전자도 한 사람으로 센다(R5 기본 ON). */
data class Person(val id: String, val name: String, val isDriver: Boolean = false)

/** R2 구간. 탑승·하차·재탑승마다 끊는다. [aboard]에 운전자도 넣는다. */
data class Segment(val startKm: Double, val endKm: Double, val aboard: Set<String>) {
    val km: Double get() = (endKm - startKm).coerceAtLeast(0.0)
}

/** R3 하이패스 통과 한 건. [won]이 null이면 아직 입력하지 않은 톨비다(C-06 나중에 입력). [aboard]는 통과할 때 탄 사람이다. */
data class TollEvent(
    val km: Double,
    val won: Int?,
    val aboard: Set<String>,
    val name: String = "",
    val at: Long = 0L,
)

enum class ExpenseSource(val label: String) {
    DRIVING("주행 중 입력"),
    CAR_PAY("카투페이"),
    SEPARATE("별도 결제"),
}

/** R4 추가 비용. [sharedBy]가 나눠 낼 사람이다(기본: 입력할 때 탄 사람 전원). [paidBy]가 null이면 운전자가 냈다. */
data class Expense(
    val name: String,
    val won: Int,
    val sharedBy: Set<String>,
    val source: ExpenseSource = ExpenseSource.DRIVING,
    val paidBy: String? = null,
)

/** R1 연비와 단가. 전기차면 km/kWh와 원/kWh를 넣는다. 계산은 같다. */
data class FuelRate(val kmPerUnit: Double, val wonPerUnit: Int)

/**
 * R6 절약 기여. [actualKmPerUnit]은 이번 주행의 실제 연비, [accepted]는 C-11에서 인정을 눌렀는지다.
 * 실제 연비가 평균보다 좋을 때만 쓴다(유류비를 실제로 쓴 만큼으로 나누고, 인정하면 운전자를 감면한다).
 */
data class SavingClaim(val actualKmPerUnit: Double, val accepted: Boolean)

enum class GameMode(val label: String) {
    ALL_IN("독박"),
    LEAN("몰아주기"),
}

/**
 * R7 게임 결과. [loserShare]는 걸린 사람이 대상 금액에서 혼자 떠안는 비율이다.
 * 독박은 1.0이다. 몰아주기는 기본 0.5이고, 나머지는 참가자 모두가 똑같이 나눈다(기획서 확정 필요 4번).
 */
data class GameResult(
    val mode: GameMode,
    val targetWon: Int,
    val players: Set<String>,
    val loser: String,
    val loserShare: Double = if (mode == GameMode.ALL_IN) 1.0 else 0.5,
    /** C-12 대상. null이면 [targetWon]을 참가자 균등 몫에서 뗀다(R7 원문). */
    val scope: GameScope? = null,
)

/**
 * C-12 게임 대상. 참가자마다 그 대상에서 원래 내던 몫을 모아(판돈) 게임 결과대로 다시 나눈다.
 * 그래서 덜 탄 사람이 대상 전체를 게임에 걸어도 음수가 되지 않는다.
 */
sealed interface GameScope {
    /** "전체 금액": 참가자 각자의 지금 몫 전부. */
    data object Whole : GameScope

    /** 추가 비용 중 고른 항목. 순번은 [SettleInput.expenses]의 순번이다. */
    data class Expenses(val indexes: Set<Int>) : GameScope
}

data class SettleInput(
    val people: List<Person>,
    val segments: List<Segment>,
    val fuel: FuelRate,
    val tolls: List<TollEvent> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    /** R5 참여 안 함(OFF)으로 바꾼 사람. */
    val optedOut: Set<String> = emptySet(),
    val saving: SavingClaim? = null,
    val game: GameResult? = null,
)

/**
 * 한 사람의 몫. 금액 칸은 나누기 전의 정확한 값이고, 화면에는 원 단위로 반올림해 보여 준다.
 * [finalWon]만 R8(1원 단위 내림)을 거친 청구 금액이다. 음수면 먼저 낸 돈이 더 많아 운전자가 돌려줄 돈이다.
 */
data class Share(
    val person: Person,
    val optedIn: Boolean,
    /** 함께 탄 거리. */
    val km: Double,
    /** 유류비·톨비 몫. */
    val rideWon: Double,
    /** 추가 비용 몫. */
    val expenseWon: Double,
    /** 운전자가 아닌 사람이 먼저 낸 돈. 몫에서 뺀다. */
    val prepaidWon: Int,
    /** 절약 기여 조정. 운전자는 빼고 나머지는 더한다. */
    val savingWon: Double,
    /** 게임 조정. */
    val gameWon: Double,
    val finalWon: Int,
) {
    val exactWon: Double get() = rideWon + expenseWon - prepaidWon + savingWon + gameWon
}

data class SegmentCost(val segment: Segment, val fuelWon: Int, val tollWon: Int, val payers: Set<String>) {
    val totalWon: Int get() = fuelWon + tollWon
}

data class Settlement(
    val km: Double,
    val fuelWon: Int,
    /** 입력된 톨비만 더한다(R3). */
    val tollWon: Int,
    val pendingTolls: Int,
    val expenseWon: Int,
    /** 운전자가 아닌 사람이 먼저 낸 추가 비용. */
    val prepaidWon: Int,
    /** R6 절약액 = 평균 연비로 계산한 유류비 − 실제 유류비. 인정 여부와 상관없다. 실제 연비를 모르거나 평균보다 나쁘면 0이다. */
    val savingWon: Int,
    /**
     * C-11에서 인정해서 운전자 몫에서 줄인 금액. 동승자가 구간마다 평균 연비 기준 몫까지 더 내는 돈의 합이다.
     * 운전자도 한 사람 몫을 내므로 절약액보다 작고, 운전자 부담액을 넘지 않는다.
     */
    val savingAppliedWon: Int,
    /** 유류비를 나눈 연비. 실제 연비가 평균보다 좋으면 실제 연비, 아니면 평균 연비다. */
    val fuelKmPerUnit: Double,
    /** 게임에 건 판돈(참가자들이 대상에서 내던 몫의 합). 게임이 없으면 0. */
    val gamePotWon: Int,
    /** 낼 사람이 없어 운전자가 떠안은 금액. 모두 OFF인 구간·비용이 여기 들어간다. */
    val absorbedWon: Int,
    /**
     * R8 끝전. 사람마다 1원 단위로 내리고 남은 몇 원이다(내는 사람 수보다 적다). 카카오페이처럼 "N빵이 내요"로 보여 준다.
     * 앱이 돈을 옮기지는 않으니 실제로는 운전자가 이만큼 덜 받는다.
     */
    val roundingWon: Int = 0,
    val shares: List<Share>,
    val segments: List<SegmentCost>,
) {
    /** 유류비 + 입력된 톨비 + 추가 비용. */
    val costWon: Int get() = fuelWon + tollWon + expenseWon
    /** 유류비를 실제 연비로 나눴는지(실제 연비가 평균보다 좋을 때). */
    val actualFuel: Boolean get() = savingWon > 0
    val driver: Share get() = shares.first { it.person.isDriver }
    val riders: List<Share> get() = shares.filterNot { it.person.isDriver }
    /** 인원별 최종 금액의 합. */
    val totalWon: Int get() = shares.sumOf { it.finalWon }

    /** R9. 인원별 최종 금액의 합이 총 정산 금액과 다르면 청구서를 만들지 않는다. */
    val balanced: Boolean get() = totalWon + absorbedWon + roundingWon == costWon - prepaidWon

    /** 미입력 톨비가 있으면 확정 전에 입력하거나 0원 처리해야 한다(C-09). */
    val readyToBill: Boolean get() = balanced && pendingTolls == 0
}

/** 기획서 7장 순서대로 계산한다: 구간 유류비·톨비(R1~R3) → 추가 비용(R4) → 절약 기여 감면(R6) → 게임(R7) → 1원 단위 내림(R8) → 검증(R9). */
fun settle(input: SettleInput): Settlement {
    val drivers = input.people.filter { it.isDriver }
    require(drivers.size == 1) { "운전자는 한 명이어야 해요" }
    require(input.fuel.kmPerUnit > 0.0) { "평균 연비는 0보다 커야 해요" }
    val driver = drivers.single()
    val ids = input.people.map { it.id }.toSet()
    val optedIn = ids - input.optedOut

    val ride = mutableMapOf<String, Double>()
    val expense = mutableMapOf<String, Double>()
    val saving = mutableMapOf<String, Double>()
    val game = mutableMapOf<String, Double>()
    var unpaid = 0

    fun split(won: Int, among: Set<String>, into: MutableMap<String, Double>): Set<String> {
        val payers = among intersect optedIn
        if (payers.isEmpty()) {
            unpaid += won
        } else {
            payers.forEach { into[it] = (into[it] ?: 0.0) + won.toDouble() / payers.size }
        }
        return payers
    }

    // R1, R2: 구간 유류비를 구간에 탄 사람끼리 나눈다. 실제 연비가 평균보다 좋으면 실제로 쓴 만큼(실제 연비)으로 나눈다.
    // 실제 연비를 모르거나 평균보다 나쁘면 평균 연비로 나눈다(더 쓴 돈은 운전자가 떠안는다).
    val claim = input.saving
    val actual = claim?.actualKmPerUnit?.takeIf { it > input.fuel.kmPerUnit }
    val fuelKmPerUnit = actual ?: input.fuel.kmPerUnit
    fun fuelOf(s: Segment, kmPerUnit: Double) = (s.km / kmPerUnit * input.fuel.wonPerUnit).roundToInt()
    val segmentFuel = input.segments.map { fuelOf(it, fuelKmPerUnit) }
    val averageFuel = input.segments.map { fuelOf(it, input.fuel.kmPerUnit) }
    val segmentPayers = input.segments.mapIndexed { i, s -> split(segmentFuel[i], s.aboard, ride) }

    // R3: 톨비는 통과할 때 탄 사람끼리 나눈다. 미입력은 빼고 센다.
    val entered = input.tolls.filter { it.won != null }
    entered.forEach { split(it.won!!, it.aboard, ride) }
    val segmentToll = input.segments.mapIndexed { i, s ->
        entered.filter { t -> t.km >= s.startKm && (t.km < s.endKm || (i == input.segments.lastIndex && t.km <= s.endKm)) }
            .sumOf { it.won!! }
    }

    // R4: 추가 비용은 나눠 낼 사람끼리 나눈다. 운전자가 아닌 사람이 냈으면 그 사람 몫에서 뺀다.
    val expensePayers = input.expenses.map { split(it.won, it.sharedBy, expense) }
    val prepaid = input.expenses
        .filter { it.paidBy != null && it.paidBy != driver.id && it.paidBy in ids }
        .groupBy { it.paidBy!! }
        .mapValues { (_, list) -> list.sumOf { it.won } }

    // R6: 절약액 = 평균 연비로 계산한 유류비 − 실제 유류비(1원 단위).
    // 인정하면 동승자는 구간마다 평균 연비로 계산한 몫까지 내고, 그 차이만큼 운전자 몫을 줄인다(운전자 감면).
    // 그래서 동승자는 운전자가 평균대로 달렸을 때보다 더 내지 않고, 운전자 몫은 0원 밑으로 내려가지 않는다.
    // 운전자가 OFF면 감면하지 않는다(동승자가 실제로 쓴 돈보다 더 내게 된다).
    val km = input.segments.sumOf { it.km }
    val savingWon = averageFuel.sum() - segmentFuel.sum()
    val extra = mutableMapOf<String, Double>()
    if (claim?.accepted == true && actual != null && driver.id in optedIn) {
        segmentPayers.forEachIndexed { i, payers ->
            if (payers.isEmpty()) return@forEachIndexed
            val gap = (averageFuel[i] - segmentFuel[i]).toDouble() / payers.size
            (payers - driver.id).forEach { extra[it] = (extra[it] ?: 0.0) + gap }
        }
    }
    val wanted = extra.values.sum()
    val driverBurden = (ride[driver.id] ?: 0.0) + (expense[driver.id] ?: 0.0)
    val savingApplied = min(wanted, driverBurden).coerceAtLeast(0.0)
    if (savingApplied > 0.0) {
        saving[driver.id] = -savingApplied
        extra.forEach { (id, won) -> saving[id] = won * savingApplied / wanted }
    }

    // R7: 참가자마다 대상에서 내던 몫을 떼어 판돈을 모으고, 걸린 사람에게 몰아준다. 나머지는 참가자가 똑같이 나눈다. 합은 그대로다.
    var pot = 0.0
    input.game?.let { g ->
        val players = g.players intersect optedIn
        require(g.loser in players) { "걸린 사람은 참여 중인 게임 참가자여야 해요" }
        require(g.loserShare in 0.0..1.0) { "몰아주기 비율은 0~1 사이여야 해요" }
        val portion: (String) -> Double = when (val scope = g.scope) {
            null -> { _ -> g.targetWon.toDouble() / players.size }
            // 먼저 낸 돈(prepaid)은 판돈에 넣지 않는다. 걸린 사람이 전체를 내도 먼저 낸 사람은 그만큼 돌려받아야 한다.
            GameScope.Whole -> { p -> (ride[p] ?: 0.0) + (expense[p] ?: 0.0) + (saving[p] ?: 0.0) }
            is GameScope.Expenses -> { p ->
                scope.indexes.filter { it in input.expenses.indices }
                    .sumOf { i -> if (p in expensePayers[i]) input.expenses[i].won.toDouble() / expensePayers[i].size else 0.0 }
            }
        }
        pot = players.sumOf(portion)
        players.forEach { game[it] = -portion(it) + pot * (1 - g.loserShare) / players.size }
        game[g.loser] = (game[g.loser] ?: 0.0) + pot * g.loserShare
    }

    val draft = input.people.map { p ->
        Share(
            person = p,
            optedIn = p.id in optedIn,
            km = input.segments.filter { p.id in it.aboard }.sumOf { it.km },
            rideWon = ride[p.id] ?: 0.0,
            expenseWon = expense[p.id] ?: 0.0,
            prepaidWon = prepaid[p.id] ?: 0,
            savingWon = saving[p.id] ?: 0.0,
            gameWon = game[p.id] ?: 0.0,
            finalWon = 0,
        )
    }

    // R8: 사람마다 1원 단위로 내린다. 그래서 누구도 자기 몫보다 더 내지 않는다(돌려받을 돈은 1원 미만을 더 받는다).
    // 내리고 남은 끝전은 N빵이 낸다. 운전자 OFF면 운전자는 0원이다(이때 운전자 몫은 원래 0원이다).
    val driverDraft = draft.first { it.person.isDriver }
    val payers = draft.filter { !it.person.isDriver || it.optedIn }
    val finals = payers.associate { it.person.id to floorWon(it.exactWon) }
    val absorbed = unpaid + if (driverDraft.optedIn) 0 else driverDraft.exactWon.roundToInt()
    val rounding = payers.sumOf { it.exactWon }.roundToInt() - finals.values.sum()

    return Settlement(
        km = km,
        fuelWon = segmentFuel.sum(),
        tollWon = entered.sumOf { it.won!! },
        pendingTolls = input.tolls.size - entered.size,
        expenseWon = input.expenses.sumOf { it.won },
        prepaidWon = prepaid.values.sum(),
        savingWon = savingWon,
        savingAppliedWon = savingApplied.roundToInt(),
        fuelKmPerUnit = fuelKmPerUnit,
        gamePotWon = pot.roundToInt(),
        absorbedWon = absorbed,
        roundingWon = rounding,
        shares = draft.map { it.copy(finalWon = finals[it.person.id] ?: 0) },
        segments = input.segments.mapIndexed { i, s -> SegmentCost(s, segmentFuel[i], segmentToll[i], segmentPayers[i]) },
    )
}

/** R8 1원 단위 내림. 나누기에서 생긴 아주 작은 오차(3,899.9999999)는 무시한다. */
internal fun floorWon(won: Double): Int = floor(won + 1e-6).toInt()
