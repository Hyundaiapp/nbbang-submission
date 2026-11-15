// 카풀 그룹과 월별 누적 정산(R10, K-01~K-07). 주행마다 확정된 금액을 그룹·월로 모은다.
package com.nbbang.settle

import com.nbbang.domain.Charge
import com.nbbang.domain.Charges
import com.nbbang.domain.Lines
import com.nbbang.domain.PayRequest
import com.nbbang.domain.Payee
import com.nbbang.domain.Ride
import com.nbbang.domain.TripMode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** K-02 카풀 그룹. 운전자는 자동으로 들어가므로 [members]에 넣지 않는다. 구성원은 이름으로 가리킨다. */
data class CarpoolGroup(
    val id: String,
    val name: String,
    val members: List<String>,
    /** 월 정산일. [LAST_DAY]면 매월 말일(기본). */
    val settleDay: Int = LAST_DAY,
    val lastUsedAt: Long = 0L,
) {
    /** 이 시각의 주행이 들어가는 정산 월. 정산일 다음 날부터는 다음 달 몫이다. */
    fun monthOf(at: Long, zone: ZoneId): YearMonth {
        val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val month = YearMonth.from(date)
        return if (date.dayOfMonth <= min(settleDay, month.lengthOfMonth())) month else month.plusMonths(1)
    }

    /** 이 카풀 주행이 들어가는 정산 월. K-05에서 정한 달([Ride.billingMonth])이 있으면 그 달이다. */
    fun monthOf(ride: Ride, zone: ZoneId): YearMonth = ride.billingMonth ?: monthOf(ride.startedAt, zone)

    /** K-06 기준 기간. "10월 1일~10월 31일", 정산일 25일이면 "10월 26일~11월 25일". */
    fun periodLabel(month: YearMonth): String {
        val end = month.atDay(min(settleDay, month.lengthOfMonth()))
        val prev = month.minusMonths(1)
        val start = prev.atDay(min(settleDay, prev.lengthOfMonth())).plusDays(1)
        val f = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREA)
        return "${start.format(f)}~${end.format(f)}"
    }

    /** K-02 저장 조건. 문제가 없으면 null, 있으면 화면에 보여 줄 문장. */
    fun problem(others: List<CarpoolGroup>): String? = when {
        name.isBlank() -> "그룹명을 입력해 주세요."
        name.length > MAX_NAME -> "그룹명은 ${MAX_NAME}자까지예요."
        others.any { it.id != id && it.name == name.trim() } -> "같은 이름의 그룹이 있어요."
        members.none { it.isNotBlank() } -> "구성원을 한 명 이상 넣어 주세요."
        members.map { it.trim() }.distinct().size != members.size -> "같은 이름의 구성원이 있어요."
        else -> null
    }

    companion object {
        const val LAST_DAY = 31
        const val MAX_NAME = 20
    }
}

/** K-06·K-07 한 주행에서 이 사람의 몫. */
data class RideLine(val rideId: String, val startedAt: Long, val title: String, val km: Double, val won: Int, val savingWon: Int) {
    val wonBeforeSaving: Int get() = won - savingWon
}

/** K-06 구성원 한 명의 한 달. [charge]가 없으면 미청구다. */
data class MemberMonth(val name: String, val lines: List<RideLine>, val charge: Charge?) {
    val won: Int get() = lines.sumOf { it.won }
    val km: Double get() = lines.sumOf { it.km }
    val rides: Int get() = lines.size

    /** 절약 기여 인정으로 더 낸 금액의 합(평균 연비 기준 몫까지). */
    val savingWon: Int get() = lines.sumOf { it.savingWon }

    /** 청구한 뒤 주행이 더해져 아직 청구하지 않은 금액. 청구가 없으면 0이다(그때는 '미청구'). */
    val unbilledExtraWon: Int get() = charge?.let { (won - it.won).coerceAtLeast(0) } ?: 0
}

/** K-06 그룹의 한 달. */
data class GroupMonth(
    val group: CarpoolGroup,
    val month: YearMonth,
    val rides: List<Ride>,
    val members: List<MemberMonth>,
    /** 주행별 전체 비용(운전자 부담 포함)의 합. */
    val totalCostWon: Int,
    /** 운전자가 절약 기여 인정으로 감면받은 금액의 합. */
    val savingWon: Int,
) {
    /** 청구 대상(탑승자) 합계. */
    val billableWon: Int get() = members.sumOf { it.won }

    /** 이 달 주행 중 금액을 안 넣은 톨비 건수. 있으면 K-07 청구서를 만들지 않는다(C-17과 같게). */
    val pendingTolls: Int get() = rides.sumOf { ride -> ride.tollPasses.count { it.won == null } }
    val km: Double get() = rides.sumOf { r -> r.segments.sumOf { it.km } }
    val monthKey: String get() = month.toString()

    /** K-07 청구서(QR) 한 장. 내역은 주행별 금액이다(기획서 K-07 "주행별 금액 요약"). */
    fun invoice(member: MemberMonth, payee: Payee): PayRequest {
        // 받은 뒤 주행이 더해져 다시 청구하면 받은 만큼 빼고 남은 금액만 청구한다.
        val received = member.charge?.receivedWon?.takeIf { it in 1 until member.won } ?: 0
        return PayRequest(
            title = "${month.monthValue}월 카풀 정산 · ${group.name}",
            items = listOf(member.name to member.won - received),
            notes = buildList {
                add("${member.rides}번 · ${Lines.km(member.km)} 함께 탔어요")
                add("기간 ${group.periodLabel(month)}")
                if (member.savingWon != 0) add("절약 기여 인정 ${Lines.won(member.savingWon)} 포함(평균 연비 기준)")
                if (received > 0) add("이미 받은 ${Lines.won(received)}을 뺀 남은 금액이에요")
            },
            payee = payee,
            costs = member.lines.map { it.title to it.won } + if (received > 0) listOf("이미 받은 금액" to -received) else emptyList(),
            mode = TripMode.CARPOOL.key,
        )
    }

    /** K-07 청구서를 만들면 '청구됨'으로 남길 청구. */
    fun charge(member: MemberMonth, now: Long): Charge = Charge(
        id = Charges.monthlyId(group.id, monthKey, member.name),
        name = member.name,
        label = "${month.monthValue}월 카풀 · ${group.name}",
        won = member.won,
        billedAt = now,
        dueAt = Charges.dueOf(now),
        monthly = true,
    )
}

/**
 * R10. 이 그룹의 카풀 주행 중 "월별 정산에 추가"한 끝난 주행을 [month]로 모은다.
 * 사람은 이름으로 묶는다(C-04에서 그룹 구성원 이름으로 고른다). 구성원이 아닌 사람도 탔으면 뒤에 붙인다.
 */
fun CarpoolGroup.month(month: YearMonth, rides: List<Ride>, charges: List<Charge>, zone: ZoneId): GroupMonth {
    val mine = rides
        .filter { it.mode == TripMode.CARPOOL && it.groupId == id && it.monthly && it.finished && monthOf(it, zone) == month }
        .sortedBy { it.startedAt }
    val lines = linkedMapOf<String, MutableList<RideLine>>()
    members.forEach { lines[it] = mutableListOf() }
    var total = 0
    var saving = 0
    val date = DateTimeFormatter.ofPattern("M/d", Locale.KOREA)
    mine.forEach { ride ->
        val s = ride.settlement()
        total += s.costWon
        saving += s.savingAppliedWon
        val title = "${Instant.ofEpochMilli(ride.startedAt).atZone(zone).format(date)} ${ride.destination ?: "카풀"}"
        s.riders.filter { it.optedIn || it.finalWon != 0 }.forEach { share ->
            lines.getOrPut(share.person.name) { mutableListOf() } +=
                RideLine(ride.id, ride.startedAt, title, share.km, share.finalWon, share.savingWon.roundToInt())
        }
    }
    val key = month.toString()
    return GroupMonth(
        group = this,
        month = month,
        rides = mine,
        members = lines.map { (name, list) -> MemberMonth(name, list, charges.firstOrNull { it.id == Charges.monthlyId(id, key, name) }) },
        totalCostWon = total,
        savingWon = saving,
    )
}

/** K-06 전월 미납 경고: 지난달 청구 중 아직 안 받은 것. */
fun CarpoolGroup.previousUnpaid(month: YearMonth, charges: List<Charge>, now: Long): List<Charge> {
    val prefix = Charges.monthlyPrefix(id, month.minusMonths(1).toString())
    return charges.filter { it.id.startsWith(prefix) && it.dueWon > 0 && now > it.dueAt }
}

/** C-18 여행·오늘 건 청구서를 만들면 '청구됨'으로 남길 청구들(0원인 사람은 빼고). */
fun Ride.charges(settlement: Settlement, now: Long, zone: ZoneId): List<Charge> =
    settlement.riders.filter { it.finalWon > 0 }.map { share ->
        Charge(
            id = Charges.rideId(id, share.riderId()),
            name = share.person.name,
            label = invoiceTitle(zone),
            won = share.finalWon,
            billedAt = now,
            dueAt = Charges.dueOf(now),
        )
    }
