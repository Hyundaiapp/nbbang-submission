// C-01 홈에 넘길 값. 계산은 core의 homeNumbers, 여기서는 HomeSummary 모양으로만 바꾼다.
package com.nbbang.ui.home

import com.nbbang.UiState
import com.nbbang.domain.ChargeStatus
import com.nbbang.settle.homeNumbers

fun UiState.homeSummary(now: Long = System.currentTimeMillis()): HomeSummary {
    val n = homeNumbers(groups, rides, charges, now, zone)
    return HomeSummary(
        expectedWon = n.expectedWon,
        rideCount = n.rideCount,
        savingWon = n.savingWon,
        overdueCount = n.overdueCount,
        recentInvoices = n.recent.map { charge ->
            val status = charge.status(now)
            HomeInvoice(
                name = charge.name,
                won = if (charge.fullyPaid) charge.won else charge.dueWon,
                detail = charge.label + " · " + when (status) {
                    ChargeStatus.OVERDUE -> "미납 D+${charge.overdueDays(now, zone)}"
                    else -> status.label
                },
                paid = status == ChargeStatus.PAID,
                overdue = status == ChargeStatus.OVERDUE,
            )
        },
    )
}
