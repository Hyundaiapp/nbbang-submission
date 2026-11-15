// C-12에서 고른 게임 판. 게임 화면(C-13~C-16)이 이걸 받아 돌리고, 걸린 사람과 함께 정산에 넣는다.
package com.nbbang.settle

import com.nbbang.domain.GameKind
import com.nbbang.domain.Ride
import com.nbbang.domain.RideGame

data class GamePlan(
    val kind: GameKind,
    val mode: GameMode,
    /** 참가자([com.nbbang.domain.Rider.id], 운전자는 0). 최소 2명. */
    val players: List<Int>,
    /** "전체 금액"이면 참. */
    val whole: Boolean,
    /** 고른 추가 비용 항목([Ride.costs] 순번). [whole]이면 무시한다. */
    val costIndexes: List<Int> = emptyList(),
) {
    fun toRideGame(loser: Int, targetWon: Int = 0): RideGame = RideGame(
        mode = mode,
        targetWon = targetWon,
        players = players.toSet(),
        loser = loser,
        game = kind.label,
        whole = whole,
        costIndexes = if (whole) null else costIndexes,
    )

    /** C-12 "게임 시작" 조건. */
    val ready: Boolean get() = players.size >= 2 && (whole || costIndexes.isNotEmpty())
}

/** 이 판에 걸리는 돈(판돈). 누가 걸리든 같다. */
fun Ride.gamePot(plan: GamePlan): Int =
    copy(game = plan.toRideGame(plan.players.first())).settlement().gamePotWon
