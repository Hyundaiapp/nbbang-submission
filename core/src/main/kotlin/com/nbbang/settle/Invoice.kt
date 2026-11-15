// C-18 탑승자별 QR 청구서 내용(F-C28, F-C29). 휴대폰 청구서 페이지(web/pay.html)가 그대로 보여 준다.
package com.nbbang.settle

import com.nbbang.domain.GameKind
import com.nbbang.domain.Lines
import com.nbbang.domain.PayRequest
import com.nbbang.domain.Payee
import com.nbbang.domain.Power
import com.nbbang.domain.Ride
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 한 사람의 청구서. QR에 넣을 글은 `PayLink.content(ride.invoice(share, settlement, payee, zone), state.payUrl)`.
 * 내역([PayRequest.costs])의 합은 [Share.finalWon] + [PayRequest.nbbangWon]이다("1원은 N빵이 내요"로 크게 보여 준다).
 */
fun Ride.invoice(share: Share, settlement: Settlement, payee: Payee, zone: ZoneId, receivedWon: Int = 0): PayRequest {
    val game = gameLine(settlement)
    // 받은 뒤 비용이 더해져 다시 청구하면 받은 만큼 빼고 남은 금액만 청구한다(카풀 월 청구서와 같게).
    val received = receivedWon.takeIf { it in 1 until share.finalWon } ?: 0
    return PayRequest(
        title = invoiceTitle(zone),
        items = listOf(share.person.name to share.finalWon - received),
        notes = tripNotes(share, settlement) + if (received > 0) listOf("이미 받은 ${Lines.won(received)}을 뺀 남은 금액이에요") else emptyList(),
        payee = payee,
        costs = costLines(share, electric = power == Power.ELECTRIC) + if (received > 0) listOf("이미 받은 금액" to -received) else emptyList(),
        game = game,
        mode = mode.key,
        gameKind = this.game?.takeIf { game != null }?.let { GameKind.of(it.game)?.key },
        // 게임에서 진 사람이면 청구서 페이지가 게임 배경을 보여 준다(C-17 "독박왕"과 같은 조건: 게임이 금액을 바꿨으면).
        // 몰아주기에서 많이 탄 사람이 걸리면 원래 몫보다 덜 낼 수도 있어서(gameWon < 0) 부호는 보지 않는다.
        losers = if (game != null && this.game?.loser == share.riderId() && share.gameWon != 0.0) setOf(0) else emptySet(),
        nbbangWon = share.nbbangWon,
    )
}

/** "11월 9일 강릉 가는 길" */
fun Ride.invoiceTitle(zone: ZoneId): String {
    val date = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREA).format(Instant.ofEpochMilli(startedAt).atZone(zone))
    return "$date ${destination?.let { "$it 가는 길" } ?: "함께 탄 길"}"
}

/**
 * 내 몫 내역. 0원인 줄은 뺀다(첫 줄은 남긴다).
 * 줄마다 원 단위로 나누되 합이 내 몫을 반올림한 값(= 최종 금액 + [nbbangWon])과 같도록 나머지가 큰 줄부터 1원씩 올린다.
 * 그래서 "끝전"·"원 단위 맞춤" 같은 줄 없이, 남는 1원은 "1원은 N빵이 내요"로 따로 보여 준다.
 */
fun costLines(share: Share, electric: Boolean = false): List<Pair<String, Int>> {
    val parts = listOf(
        (if (electric) "충전비·톨비" else "유류비·톨비") to share.rideWon,
        "추가 비용" to share.expenseWon,
        "먼저 낸 돈" to -share.prepaidWon.toDouble(),
        (if (share.person.isDriver) "절약 운전 감면" else "절약 기여 인정") to share.savingWon,
        "게임 조정" to share.gameWon,
    )
    val won = apportion(parts.map { it.second }, share.finalWon + share.nbbangWon)
    return parts.mapIndexed { i, (name, _) -> name to won[i] }.filterIndexed { i, (_, w) -> i == 0 || w != 0 }
}

/** 1원 단위로 내려서 N빵이 내는 돈. 내 몫을 반올림한 값 − 최종 금액(0 또는 1원). */
val Share.nbbangWon: Int get() = (exactWon.roundToInt() - finalWon).coerceAtLeast(0)

/** [values]를 원 단위로 바꾸되 합이 [total]이 되게 한다(내림한 뒤 나머지가 큰 것부터 1원씩). */
internal fun apportion(values: List<Double>, total: Int): List<Int> {
    val floors = values.map { floor(it + 1e-6).toInt() }
    var need = total - floors.sum()
    val order = values.indices.sortedByDescending { values[it] - floors[it] }
    val result = floors.toMutableList()
    for (i in order) {
        if (need <= 0) break
        result[i] += 1
        need -= 1
    }
    return result
}

/** C-18 게임 결과 카드 문구(게임별 5종 중 하나, 주행마다 고정). 예: "주사위의 선택은 이지은 님이에요. 12,000원을 혼자 다 내요." */
fun Ride.gameLine(settlement: Settlement): String? {
    val g = game ?: return null
    if (settlement.gamePotWon == 0 || settlement.shares.all { it.gameWon == 0.0 }) return null
    // 청구서는 동승자가 보니 운전자는 "나" 대신 "운전자"로 부른다.
    val loser = settlement.shares.firstOrNull { it.person.id == personId(g.loser) }?.person
        ?.let { if (it.isDriver) "운전자" else it.name } ?: return null
    val won = if (g.mode == GameMode.ALL_IN) settlement.gamePotWon else (settlement.gamePotWon * g.loserShare).roundToInt()
    return GameLines.line(g.game, g.mode, loser, won, pick = id.hashCode())
}

/** 주행 정보(일시는 제목에): 거리·연비·단가, 내가 탄 구간, 전체 비용, 절약 기여. */
fun Ride.tripNotes(share: Share, settlement: Settlement): List<String> = buildList {
    val electric = power == Power.ELECTRIC
    val word = if (electric) "전비" else "연비"
    val unitName = if (electric) "km/kWh" else "km/L"
    fun eff(value: Double) = String.format(Locale.ROOT, "%.1f", value) + unitName
    val unit = if (electric) "kWh당" else "리터당"
    val base = if (electric) prices.wonPerKwh else prices.wonPerLiter
    val official = !electric && prices.officialDate != null
    val edited = unitWonOverride != null && unitWonOverride != base
    val price = Lines.won(unitWon()) + when {
        official && edited -> "(${shortDate(prices.officialDate!!)} 공시 평균 ${Lines.won(base)}에서 수정)"
        official -> "(${shortDate(prices.officialDate!!)} 공시 평균)"
        edited -> "(기본 ${Lines.won(base)}에서 수정)"
        else -> ""
    }
    add("주행 ${Lines.km(settlement.km)} · ${mode.label}")
    // 실제 연비가 평균(기준)보다 좋으면 실제로 쓴 만큼 나눴다(R1).
    val expected = expectedName()
    add(
        if (settlement.actualFuel) "실제 $word ${eff(settlement.fuelKmPerUnit)}($expected ${eff(expectedEfficiency())}) · $unit $price"
        else "$expected $word ${eff(expectedEfficiency())} · $unit $price",
    )
    officialBaseline()?.let { add("기준 $word: ${it.explain()}") }
    val ranges = rangesOf(share.person.id)
    if (ranges.isNotEmpty()) add("탄 구간 " + ranges.joinToString(", "))
    val parts = buildList {
        add("${if (electric) "충전비" else "유류비"} ${Lines.won(settlement.fuelWon)}")
        if (settlement.tollWon > 0) add("톨비 ${Lines.won(settlement.tollWon)}")
        if (settlement.expenseWon > 0) add("추가 비용 ${Lines.won(settlement.expenseWon)}")
    }
    add("함께 쓴 돈 ${parts.joinToString(" + ")} = ${Lines.won(settlement.costWon)}")
    // 절약액은 공인연비(시험실 값)나 직접 넣은 평균과 비교한 값이라 추정이다.
    when {
        settlement.savingAppliedWon > 0 ->
            add("절약 기여 인정 · 동승자는 $expected ${word}로 계산한 몫을 내요(운전자 ${Lines.won(settlement.savingAppliedWon)} 감면, 추정)")
        settlement.savingWon > 0 && savingAccepted == false ->
            add("${expected}보다 아낀 ${Lines.won(settlement.savingWon)}(추정)은 모두 함께 나눴어요")
    }
}

/** 이 사람이 탄 구간들(출발점을 0km로). 이어진 구간은 하나로 묶는다. */
private fun Ride.rangesOf(personId: String): List<String> {
    val start = segments.firstOrNull()?.startKm ?: return emptyList()
    val mine = segments.filter { s -> personId == "0" || s.riderIds.any { personId(it) == personId } }
    val merged = mutableListOf<Pair<Double, Double>>()
    mine.forEach { s ->
        val last = merged.lastOrNull()
        if (last != null && kotlin.math.abs(last.second - s.startKm) < 1e-6) merged[merged.lastIndex] = last.first to s.endKm
        else merged += s.startKm to s.endKm
    }
    return merged.map { (a, b) -> "${kmNumber(a - start)}~${kmNumber(b - start)}km" }
}

private fun kmNumber(km: Double): String =
    if (km < 10 && km % 1.0 != 0.0) String.format(Locale.ROOT, "%.1f", km) else km.roundToInt().toString()
