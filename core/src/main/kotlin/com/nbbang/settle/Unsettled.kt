// C-08에서 청구서를 만들지 않고 나온 주행을 홈(C-01)에서 다시 찾게 한다.
package com.nbbang.settle

import com.nbbang.domain.Charge
import com.nbbang.domain.Charges
import com.nbbang.domain.Ride
import com.nbbang.domain.TripMode

/** 홈에 "정산하지 않은 주행"으로 보여 주는 기간. 그보다 오래된 것은 따로 받았다고 본다. */
const val UNSETTLED_WINDOW_MS: Long = 7L * 24 * 60 * 60 * 1000

/**
 * 따로 정산할 주행(여행, 카풀 "오늘 건 따로 결제") 중 청구서를 하나도 만들지 않았고, 받은 표시도 없고,
 * 누군가 낼 돈이 있는 것. 최근 [withinMs] 안에 끝난 것만, 최신 순.
 */
fun List<Ride>.unsettled(charges: List<Charge>, now: Long, withinMs: Long = UNSETTLED_WINDOW_MS): List<Ride> =
    filter { ride ->
        val end = ride.endedAt ?: return@filter false
        val separate = ride.mode == TripMode.TRAVEL || (!ride.monthly && !ride.awaitingSettlementChoice)
        now - end in 0..withinMs && separate && ride.paidRiderIds.isEmpty() &&
            charges.none { it.id.startsWith(Charges.ridePrefix(ride.id)) } &&
            ride.settlement().riders.any { it.finalWon > 0 }
    }.sortedByDescending { it.endedAt }
