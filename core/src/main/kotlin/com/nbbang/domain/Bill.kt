package com.nbbang.domain

import com.nbbang.settle.ExpenseSource
import kotlin.math.roundToInt

/** 한 사람의 몫. [km]은 그 사람이 함께 탄 거리다. */
data class RiderShare(val rider: Rider, val km: Double, val won: Int, val paid: Boolean)

/**
 * 함께 탄 길의 영수증.
 * 구간마다 (에너지 + 통행료 + 기타 비용)을 그 구간에 탄 사람끼리 똑같이 나눈 뒤 사람마다 더한다.
 * 운전 수고비는 넣지 않는다. 실제로 든 비용만 나눈다.
 */
data class Bill(
    val power: Power,
    val km: Double,
    /** 쓴 에너지(kWh 또는 L). */
    val energyAmount: Double,
    /** 모든 구간의 에너지를 차에서 잰 값으로 계산했는지. 아니면 거리와 효율로 어림한 구간이 있다. */
    val energyMeasured: Boolean,
    val energyWon: Int,
    val tollWon: Int,
    /** 통행료가 내비의 예상값인지. 운전자가 고쳤거나 직접 더했으면 false. */
    val tollEstimated: Boolean,
    val extraWon: Int,
    val totalWon: Int,
    val shares: List<RiderShare>,
    val driverWon: Int,
    val treated: Boolean,
) {
    val owedWon: Int get() = shares.filterNot { it.paid }.sumOf { it.won }
    val unitPrice: String get() = if (power == Power.ELECTRIC) "kWh" else "L"
}

fun Ride.bill(): Bill {
    val efficiency = carEfficiency?.takeIf { it in 0.5..60.0 }
        ?: if (power == Power.ELECTRIC) prices.kmPerKwh else prices.kmPerLiter
    val unitWon = if (power == Power.ELECTRIC) prices.wonPerKwh else prices.wonPerLiter
    val segs = segments
    val energies = segs.map { s -> s.energyUsed?.takeIf { it > 0.0 } ?: (s.km / efficiency) }
    val moving = segs.filter { it.km > 0.0 }
    val measured = moving.isNotEmpty() && moving.all { (it.energyUsed ?: 0.0) > 0.0 }
    val energyWon = energies.map { it * unitWon }
    val addedTolls = segs.map { s -> s.extras.filter { it.kind == ExtraKind.TOLL }.sumOf { it.won } }
    val otherExtras = segs.map { s -> s.extras.filter { it.kind != ExtraKind.TOLL }.sumOf { it.won } }

    // 예상 통행료는 톨게이트를 지난 구간에 나눠 단다. 지난 기록이 없으면 거리 비율로 나눈다.
    val routeToll = tollOverrideWon ?: routeTollWon
    var weights = if (segs.any { it.tollgates > 0 }) segs.map { it.tollgates.toDouble() } else segs.map { it.km }
    if (weights.sum() <= 0.0 && segs.isNotEmpty()) weights = segs.indices.map { if (it == segs.lastIndex) 1.0 else 0.0 }
    val weightSum = weights.sum()
    val tollAlloc = weights.map { w -> if (weightSum > 0.0) routeToll * w / weightSum else 0.0 }

    val segCost = segs.indices.map { i -> energyWon[i] + tollAlloc[i] + addedTolls[i] + otherExtras[i] }
    val raw = mutableMapOf<Int, Double>()
    val kmBy = mutableMapOf<Int, Double>()
    segs.forEachIndexed { i, s ->
        s.riderIds.forEach { id -> kmBy[id] = (kmBy[id] ?: 0.0) + s.km }
        if (s.riderIds.isEmpty()) return@forEachIndexed
        val people = s.riderIds.size + if (rule == SplitRule.EVERYONE) 1 else 0
        s.riderIds.forEach { id -> raw[id] = (raw[id] ?: 0.0) + segCost[i] / people }
    }
    // 기존 영수증에도 C-07에서 운전자가 추가한 비용을 입력 당시 탑승자 기준으로 표시한다.
    val drivingCosts = costs.filter { it.source == ExpenseSource.DRIVING && it.paidBy == DRIVER_ID }
    val riderIds = riders.map { rootId(it.id) }.toSet()
    for (cost in drivingCosts) {
        val participants = cost.sharedBy.map(::rootId).filter { it == DRIVER_ID || it in riderIds }.toSet()
        if (participants.isEmpty()) continue
        val share = cost.won.toDouble() / participants.size
        for (id in participants - DRIVER_ID) raw[id] = (raw[id] ?: 0.0) + share
    }
    val drivingCostWon = drivingCosts.sumOf { it.won }
    val shares = riders.groupBy { rootId(it.id) }.map { (id, records) ->
        val original = records.firstOrNull { it.id == id } ?: records.first()
        val latest = records.lastOrNull { it.aboard } ?: records.last()
        val rider = latest.copy(id = original.id, name = original.name ?: latest.name)
        val won = if (treated) 0 else roundTo100(records.sumOf { raw[it.id] ?: 0.0 })
        RiderShare(rider, records.sumOf { kmBy[it.id] ?: 0.0 }, won, paid = id in paidRiderIds)
    }
    val passengersWon = shares.sumOf { it.won }
    val total = maxOf(roundTo10(segCost.sum() + drivingCostWon), passengersWon)
    return Bill(
        power = power,
        km = segs.sumOf { it.km },
        energyAmount = energies.sum(),
        energyMeasured = measured,
        energyWon = roundTo10(energyWon.sum()),
        tollWon = roundTo10(tollAlloc.sum() + addedTolls.sum()),
        tollEstimated = tollOverrideWon == null && routeTollWon > 0,
        extraWon = otherExtras.sum() + drivingCostWon,
        totalWon = total,
        shares = shares,
        driverWon = total - passengersWon,
        treated = treated,
    )
}

/** 100원 단위로 반올림한다. 남거나 모자란 몇십 원은 운전자 몫에서 맞춘다. */
internal fun roundTo100(won: Double): Int = (won / 100.0).roundToInt() * 100

internal fun roundTo10(won: Double): Int = (won / 10.0).roundToInt() * 10
