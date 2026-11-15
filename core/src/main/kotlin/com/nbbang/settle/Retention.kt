// 장부 크기 제한. 오래된 기록부터 버리되, 아직 돈이 오갈 수 있는 기록은 남긴다.
package com.nbbang.settle

import com.nbbang.domain.Charge
import com.nbbang.domain.Charges
import com.nbbang.domain.Ride

/** 장부에 남기는 주행·청구 수(하루 두 번 카풀이면 9개월쯤). 지켜야 할 기록이 많으면 이보다 많이 남는다. */
const val LEDGER_MAX: Int = 400

/** 이 기간 안에 시작한 주행은 수와 상관없이 남긴다. 이번 달·지난달 월별 정산이 이 안에 들어간다. */
const val LEDGER_KEEP_RECENT_MS: Long = 62L * 24 * 60 * 60 * 1000

/**
 * 저장할 주행. [max]를 넘으면 오래전에 시작한 것부터 버린다. 순서는 그대로 둔다.
 * 진행 중이거나, K-05 정산 방식을 아직 안 골랐거나, 최근 [LEDGER_KEEP_RECENT_MS] 안이거나,
 * 덜 받은 청구(여행·오늘 건, 또는 그 주행이 들어간 달의 월 청구)가 있는 주행은 버리지 않는다.
 */
fun List<Ride>.forStorage(charges: List<Charge>, now: Long, max: Int = LEDGER_MAX): List<Ride> {
    if (size <= max) return this
    val owed = charges.filter { it.dueWon > 0 }
    fun owes(ride: Ride): Boolean = owed.any { charge ->
        charge.id.startsWith(Charges.ridePrefix(ride.id)) ||
            (ride.groupId != null && ride.billingMonth != null &&
                charge.id.startsWith(Charges.monthlyPrefix(ride.groupId, ride.billingMonth.toString())))
    }
    fun keep(ride: Ride): Boolean =
        !ride.finished || ride.awaitingSettlementChoice || now - ride.startedAt < LEDGER_KEEP_RECENT_MS || owes(ride)
    val drop = filterNot(::keep).sortedBy { it.startedAt }.take(size - max).mapTo(HashSet()) { it.id }
    return filterNot { it.id in drop }
}

/** 저장할 청구. [max]를 넘으면 다 받은 것 중 오래전에 청구한 것부터 버린다. 덜 받은 청구는 버리지 않는다. */
fun List<Charge>.forStorage(max: Int = LEDGER_MAX): List<Charge> {
    if (size <= max) return this
    val drop = filter { it.fullyPaid }.sortedBy { it.billedAt }.take(size - max).mapTo(HashSet()) { it.id }
    return filterNot { it.id in drop }
}
