// C-01 홈 카드 숫자. 화면은 이 값을 그대로 보여 주고 직접 계산하지 않는다.
package com.nbbang.settle

import com.nbbang.domain.Charge
import com.nbbang.domain.ChargeStatus
import com.nbbang.domain.Ride
import java.time.ZoneId

data class HomeNumbers(
    /** 이번 달(그룹 정산일 기준) 카풀 청구 예정 합계. 운전자 몫은 빼고 탑승자 몫만. */
    val expectedWon: Int,
    /** 이번 달 월별 정산에 넣은 카풀 주행 수. */
    val rideCount: Int,
    /** 이번 달 인정받은 절약 기여 합계. */
    val savingWon: Int,
    /** 납부 기한이 지난 청구 수(C-19 미납). */
    val overdueCount: Int,
    /** 최근 청구(만든 순서 거꾸로). */
    val recent: List<Charge>,
)

fun homeNumbers(
    groups: List<CarpoolGroup>,
    rides: List<Ride>,
    charges: List<Charge>,
    now: Long,
    zone: ZoneId,
    recentCount: Int = 2,
): HomeNumbers {
    val months = groups.map { it.month(it.monthOf(now, zone), rides, charges, zone) }
    val billed = charges.filter { it.won > 0 }
    return HomeNumbers(
        expectedWon = months.sumOf { it.billableWon },
        rideCount = months.sumOf { it.rides.size },
        savingWon = months.sumOf { it.savingWon },
        overdueCount = billed.count { it.status(now) == ChargeStatus.OVERDUE },
        recent = billed.sortedByDescending { it.billedAt }.take(recentCount),
    )
}
