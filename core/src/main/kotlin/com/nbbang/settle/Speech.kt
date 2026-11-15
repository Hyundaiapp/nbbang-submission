package com.nbbang.settle

import com.nbbang.domain.Lines
import com.nbbang.domain.Power
import com.nbbang.domain.Ride

/**
 * 도착해서 한 번 말하는 문장. 화면(T-03·C-08)과 같은 기획서 정산(R1~R9) 숫자를 쓴다.
 * 미입력 톨비는 금액에 넣지 않고 따로 알린다. 돈 얘기는 차가 꺼내고, 운전자는 화면에서 확인만 한다.
 */
fun Ride.arrivalLine(s: Settlement = settlement(), opening: String = "도착했어요."): String {
    if (treated) return Lines.treated()
    val costs = buildList {
        add("${if (power == Power.ELECTRIC) "충전비" else "유류비"} ${Lines.won(s.fuelWon)}")
        if (s.tollWon > 0) add("톨비 ${Lines.won(s.tollWon)}")
        if (s.expenseWon > 0) add("추가 비용 ${Lines.won(s.expenseWon)}")
    }
    val head = "$opening ${Lines.km(s.km)}, ${costs.joinToString(", ")}, 모두 ${Lines.won(s.costWon)}이에요."
    val pending = if (s.pendingTolls > 0) " 톨비 ${s.pendingTolls}건은 금액을 넣어야 해요." else ""
    val owing = s.riders.filter { it.finalWon > 0 }
    val who = when {
        owing.isEmpty() -> ""
        owing.size <= 3 -> " " + owing.joinToString(", ") { "${it.person.name} ${Lines.won(it.finalWon)}" } + "이에요."
        else -> " ${owing.size}분 몫이 모두 ${Lines.won(owing.sumOf { it.finalWon })}이에요."
    }
    return "$head$pending$who 화면에서 확인하고 N빵해 주세요."
}
