package com.nbbang.domain

import java.util.Locale
import kotlin.math.roundToInt

/**
 * 화면과 음성에 쓰는 문장. 운전 중에는 한 문장만 말하고, 돈 얘기는 차가 대신 꺼낸다.
 * TTS가 읽기 좋게 "km" 뒤에는 조사를 붙이지 않는다.
 */
object Lines {
    fun won(value: Int): String = String.format(Locale.KOREA, "%,d원", value)

    /** 12.1, 12.15처럼 필요한 자리까지만(공인연비). */
    fun plain(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    fun km(value: Double): String =
        if (value < 10) String.format(Locale.ROOT, "%.1fkm", value) else "${value.roundToInt()}km"

    fun energy(bill: Bill): String =
        String.format(Locale.ROOT, "%.1f%s", bill.energyAmount, bill.power.unit)

    /** 도착해서 정산 카드를 띄울 때 한 번 말한다. */
    fun arrival(bill: Bill): String {
        if (bill.treated) return "이번 길은 운전자가 내는 걸로 장부에 적었어요."
        val costs = buildList {
            add("${bill.power.energyName} ${won(bill.energyWon)}")
            if (bill.tollWon > 0) add("통행료 ${won(bill.tollWon)}")
            if (bill.extraWon > 0) add("주차비·기타 ${won(bill.extraWon)}")
        }
        val head = "함께 탄 길 정산이에요. ${km(bill.km)}, ${costs.joinToString(", ")}, 모두 ${won(bill.totalWon)}이에요."
        val owing = bill.shares.filter { it.won > 0 }
        val who = when {
            owing.isEmpty() -> "나눌 금액이 없어요."
            owing.size > 1 && owing.map { it.won }.distinct().size == 1 -> "한 분에 ${won(owing.first().won)}씩이에요."
            owing.size <= 3 -> owing.joinToString(", ") { "${it.rider.label} ${won(it.won)}" } + "이에요."
            else -> "${owing.size}분 몫이 모두 ${won(owing.sumOf { it.won })}이에요."
        }
        return "$head $who 화면의 QR을 찍으면 보낼 곳이 나와요."
    }

    fun treated(): String = "이번 길은 운전자가 내는 걸로 장부에 적었어요."

    /** Gleo로 더한 비용. [aboard]가 0이면 끝난 주행에 넣었다. */
    fun costAdded(label: String, won: Int, aboard: Int, rule: SplitRule = SplitRule.EVERYONE): String {
        val who = when {
            aboard == 0 -> "이번 길 비용에 넣었어요. 정산 화면에서 나눌 사람을 고칠 수 있어요."
            rule == SplitRule.EVERYONE -> "지금 함께 탄 ${aboard}명과 운전자가 나눠요."
            else -> "지금 함께 탄 ${aboard}명이 나눠요."
        }
        return "$label ${won(won)}을 더했어요. $who"
    }

    fun extraAdded(extra: Extra, aboard: Int, rule: SplitRule = SplitRule.EVERYONE): String {
        val who = when {
            aboard == 0 -> "이번 길 비용에 넣었어요."
            rule == SplitRule.EVERYONE -> "지금 함께 탄 ${aboard}명과 운전자가 나눠요."
            else -> "지금 함께 탄 ${aboard}명이 나눠요."
        }
        return "${extra.kind.label} ${won(extra.won)}을 더했어요. $who"
    }

    fun balance(person: PersonBalance): String =
        "${person.name} 님은 아직 ${won(person.owedWon)}이 남았어요. ${person.rides}번, ${km(person.km)} 함께 탔어요."

    fun noBalance(name: String): String = "$name 님은 남은 금액이 없어요."

    /** Gleo "민지 정산해 줘". [unpaidWon]은 아직 안 받은 청구(C-19), [monthWon]은 이번 달 아직 청구 안 한 카풀 몫(K-06). */
    fun owed(name: String, unpaidWon: Int, unpaidCount: Int, monthWon: Int): String = when {
        unpaidCount > 0 && monthWon > 0 ->
            "$name 님은 미납 청구 ${unpaidCount}건 ${won(unpaidWon)}이 있고, 이번 달 카풀은 ${won(monthWon)} 청구 예정이에요."
        unpaidCount > 0 -> "$name 님은 미납 청구 ${unpaidCount}건, ${won(unpaidWon)}이 남았어요."
        monthWon > 0 -> "$name 님은 이번 달 카풀 ${won(monthWon)} 청구 예정이에요."
        else -> noBalance(name)
    }

    /** C-18 "음성으로 안내". 차에서 문자를 못 보내니 차 스피커로 알려 준다. */
    fun invoice(name: String, won: Int): String =
        "$name 님, 이번 정산은 ${won(won)}이에요. 화면의 QR을 찍으면 바로 보낼 수 있어요."

    /** C-19 "음성 리마인드". [label]은 어느 정산인지("10월 카풀"), [overdueDays]가 0이면 아직 기한 전. */
    fun remind(name: String, label: String, won: Int, overdueDays: Int): String =
        if (overdueDays > 0) "$name 님, $label 정산 ${won(won)}이 아직 남아 있어요. 납부 기한이 ${overdueDays}일 지났어요. 화면의 QR을 찍어 주세요."
        else "$name 님, $label 정산 ${won(won)}이 남아 있어요. 화면의 QR을 찍으면 바로 보낼 수 있어요."

    fun noRide(): String = "정산할 길이 아직 없어요. 함께 타고 출발하면 기록할게요."

    /** 주행 중 화면 한 줄. 말하지는 않는다. */
    fun live(bill: Bill, aboard: Int): String =
        "${aboard}명 함께 타는 중 · ${km(bill.km)} · 지금까지 ${won(bill.totalWon)}"

    fun settled(name: String, won: Int): String = "$name 님 ${won(won)} 받은 걸로 적었어요."
}
