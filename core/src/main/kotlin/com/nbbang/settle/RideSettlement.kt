// 주행 기록(Ride)을 정산 입력으로 바꾼다. 화면은 ride.settlement()만 부르면 된다.
package com.nbbang.settle

import com.nbbang.domain.DRIVER_ID
import com.nbbang.domain.ExtraKind
import com.nbbang.domain.Lines
import com.nbbang.domain.OfficialBaseline
import com.nbbang.domain.Power
import com.nbbang.domain.Ride
import com.nbbang.domain.rootId

/** 정산 결과(R1~R9). 진행 중인 길은 tracker.preview()로 받은 [Ride]에 부르면 지금까지의 금액이 나온다. */
fun Ride.settlement(): Settlement = settle(settleInput())

/** 정산에서 이 사람을 가리키는 id. 재탑승(C-04 기존 인원)은 처음 탄 사람으로 묶는다. */
fun Ride.personId(id: Int): String = rootId(id).toString()

/** [Share.person]의 id를 다시 [com.nbbang.domain.Rider.id]나 [DRIVER_ID]로. */
fun Share.riderId(): Int = person.id.toInt()

/**
 * R1 평균 연비(전기차는 전비). 운전자 절약 기여(R6)는 이 값과 실제 연비를 비교한다.
 * C-02에서 차종을 골랐으면 그 차 공인연비로 정한 이번 주행의 기준연비([officialBaseline]),
 * 아니면 C-02에서 직접 넣은 값이다. 차가 알려 준 값은 실제 연비(R6)에 쓴다.
 */
fun Ride.expectedEfficiency(): Double = officialBaseline()?.kmPerLiter
    ?: if (power == Power.ELECTRIC) prices.kmPerKwh else prices.kmPerLiter

/** 공인연비 기준연비: C-02에서 고른 차종의 도심·고속 공인연비를 이번 주행의 도심·고속 거리 비중으로 섞는다. 기름차만. */
fun Ride.officialBaseline(): OfficialBaseline? = if (power == Power.FUEL) prices.carModel?.baseline(drivingMix) else null

/** 실제 연비와 비교하는 연비의 이름. 공인연비로 정했으면 "기준", C-02에서 직접 넣었으면 "평균". */
fun Ride.expectedName(): String = if (officialBaseline() != null) "기준" else "평균"

/**
 * 기준연비를 어떻게 정했는지 한 줄. C-11·청구서에 그대로 쓴다.
 * 예: "2027 아반떼 공인연비(도심 12.1 · 고속 16.9km/L)를 이번 주행 고속 62%로 섞었어요"
 */
fun OfficialBaseline.explain(): String {
    val rates = "도심 ${Lines.plain(model.cityKmPerLiter)} · 고속 ${Lines.plain(model.highwayKmPerLiter)}km/L"
    val mix = if (measured) "이번 주행 고속 $highwayPercent%로" else "복합 비율(도심 55 : 고속 45)로"
    return "${model.label} 공인연비($rates)를 $mix 섞었어요"
}

/** R1 단가. C-08에서 고쳤으면 그 값, 아니면 설정의 단가(공시 평균). */
fun Ride.unitWon(): Int = unitWonOverride ?: if (power == Power.ELECTRIC) prices.wonPerKwh else prices.wonPerLiter

/**
 * R6 실제 연비. 배터리로 잰 구간이 1km 이상이면 그 값, 아니면 차가 알려 준 누적 값.
 * C-11 화면에 보이는 대로 소수 첫째 자리에서 반올림한다. 그래야 화면 숫자로 손계산한 절약액과 같다.
 */
fun Ride.actualEfficiency(): Double? {
    val measured = segments.filter { (it.energyUsed ?: 0.0) > 0.0 }
    val km = measured.sumOf { it.km }
    val used = measured.sumOf { it.energyUsed ?: 0.0 }
    val raw = if (km >= 1.0 && used > 0.0) km / used else carEfficiency?.takeIf { it > 0.0 } ?: return null
    return Math.round(raw * 10) / 10.0
}

/** C-11 절약 기여를 물을지. 실제 연비가 평균(예상) 연비보다 좋아 절약액이 있으면 묻는다. */
fun Ride.savingQuestionDue(): Boolean {
    val actual = actualEfficiency() ?: return false
    return actual > expectedEfficiency() && settlement().savingWon > 0
}

fun Ride.settleInput(): SettleInput {
    val driver = DRIVER_ID.toString()
    val people = listOf(Person(driver, "나", isDriver = true)) +
        riders.filter { it.sameAs == null }.map { Person(it.id.toString(), it.label) }
    val known = people.map { it.id }.toSet()
    fun ids(of: Collection<Int>) = of.map { personId(it) }.filter { it in known }.toSet()

    // MVP 때 Gleo "비용 추가"로 구간에 붙인 비용도 그대로 센다.
    val legacyTolls = mutableListOf<TollEvent>()
    val legacyCosts = mutableListOf<Expense>()
    val segs = segments.map { s ->
        val aboard = ids(s.riderIds) + driver
        s.extras.forEach { e ->
            if (e.kind == ExtraKind.TOLL) {
                legacyTolls += TollEvent(s.startKm, e.won, aboard, e.kind.label)
            } else {
                legacyCosts += Expense(e.kind.label, e.won, aboard)
            }
        }
        Segment(s.startKm, s.endKm, aboard)
    }
    val outs = if (treated) known - driver else ids(optedOut)
    val played = game?.let { g ->
        val players = ids(g.players) - outs
        val loser = personId(g.loser)
        val scope = when {
            g.whole -> GameScope.Whole
            g.costIndexes != null -> GameScope.Expenses(g.costIndexes.toSet())
            else -> null
        }
        val valid = loser in players && (scope != null || g.targetWon > 0)
        if (valid) GameResult(g.mode, g.targetWon, players, loser, g.loserShare, scope) else null
    }
    return SettleInput(
        people = people,
        segments = segs,
        fuel = FuelRate(expectedEfficiency(), unitWon()),
        tolls = tollPasses.map { TollEvent(it.km, it.won, ids(it.aboard), it.name, it.at) } + legacyTolls,
        expenses = costs.map { c ->
            Expense(c.name, c.won, ids(c.sharedBy), c.source, personId(c.paidBy).takeIf { it != driver && it in known })
        } + legacyCosts,
        optedOut = outs,
        // 인정 여부를 묻기 전에도 절약액(C-11에 보일 금액)을 계산해 둔다.
        saving = actualEfficiency()?.let { SavingClaim(it, accepted = savingAccepted == true) },
        game = played,
    )
}
