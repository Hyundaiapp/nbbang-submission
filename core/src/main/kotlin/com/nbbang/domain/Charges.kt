// C-19 청구 현황. 청구서(QR)를 만들 때 한 건씩 남기고, 납부 확인으로 닫는다.
package com.nbbang.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class ChargeStatus(val label: String) {
    BILLED("청구됨"),
    PAID("납부 완료"),
    OVERDUE("미납"),
}

/**
 * 청구 한 건(기획서 8장 청구서: 수신자, 금액, 상태, 청구일, 납부 기한).
 * 카풀은 그룹·월·사람마다 하나([Charges.monthlyId]), 여행은 주행·사람마다 하나([Charges.rideId]).
 * 아직 청구서를 만들지 않은 사람은 [Charge]가 없다(미청구).
 */
data class Charge(
    val id: String,
    val name: String,
    /** "10월 카풀 · 회사 출퇴근", "11월 9일 강릉 가는 길" */
    val label: String,
    val won: Int,
    val billedAt: Long,
    val dueAt: Long,
    val paidAt: Long? = null,
    val monthly: Boolean = false,
    /**
     * 받은 금액. 받은 뒤 주행이 더해져 다시 청구하면 [won]보다 작아지고, 차액이 다시 '청구됨'이 된다.
     * null이면(예전 기록) 받은 날이 있을 때 전액을 받은 것으로 본다.
     */
    val paidWon: Int? = null,
    /** C-19 음성 리마인드를 마지막으로 한 때와 횟수. 청구서를 다시 만들면 새 청구라 0부터 센다. */
    val remindedAt: Long? = null,
    val reminders: Int = 0,
    /** 일부만 받은 상태에서 "납부 확인"을 누르기 전에 받아 둔 금액과 날. "납부 취소"하면 이 상태로 돌아간다. */
    val earlierPaidWon: Int? = null,
    val earlierPaidAt: Long? = null,
) {
    /** 지금까지 받은 금액. */
    val receivedWon: Int get() = if (paidAt == null) 0 else paidWon ?: won

    /** 아직 받을 금액. */
    val dueWon: Int get() = (won - receivedWon).coerceAtLeast(0)

    /** 다 받았는지. 일부만 받았으면 false다. */
    val fullyPaid: Boolean get() = paidAt != null && dueWon == 0

    fun status(now: Long): ChargeStatus = when {
        fullyPaid -> ChargeStatus.PAID
        now > dueAt -> ChargeStatus.OVERDUE
        else -> ChargeStatus.BILLED
    }

    /** 납부 기한이 지난 날수(C-19 "D+n"). 안 지났으면 0. */
    fun overdueDays(now: Long, zone: ZoneId): Int {
        if (fullyPaid || now <= dueAt) return 0
        val due = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(due, today).toInt().coerceAtLeast(1)
    }
}

object Charges {
    /** 납부 기한: 청구일 + 4일(기획서 C-19 예: 11.01 청구 → 11.05 기한). */
    val DUE: Duration = Duration.ofDays(4)

    fun monthlyId(groupId: String, month: String, name: String) = "${monthlyPrefix(groupId, month)}$name"

    /** 한 그룹·한 달의 월 청구 id 앞부분. */
    fun monthlyPrefix(groupId: String, month: String) = "m:$groupId:$month:"

    fun rideId(rideId: String, personId: Int) = "${ridePrefix(rideId)}$personId"

    /** 한 주행의 청구 id 앞부분. 이 주행의 청구를 모두 찾을 때 쓴다. */
    fun ridePrefix(rideId: String) = "r:$rideId:"

    fun dueOf(billedAt: Long): Long = billedAt + DUE.toMillis()
}

/**
 * 같은 id의 청구를 바꾼다. 다시 만들면 청구일·기한을 새로 잡는다.
 * 이미 받은 청구는 받은 금액을 남긴다. 새 금액이 받은 금액 이하면 그대로 '납부 완료'이고,
 * 더 크면(받은 뒤 주행이 더해짐) 차액이 새 청구일·기한으로 다시 '청구됨'이 된다.
 */
fun List<Charge>.upsert(charge: Charge): List<Charge> {
    val old = firstOrNull { it.id == charge.id } ?: return this + charge
    val received = old.receivedWon
    val next = when {
        received == 0 -> charge
        charge.won <= received -> charge.copy(paidAt = old.paidAt, paidWon = received, billedAt = old.billedAt, dueAt = old.dueAt)
        else -> charge.copy(paidAt = old.paidAt, paidWon = received)
    }.let { if (received == 0) it else it.copy(earlierPaidWon = old.earlierPaidWon, earlierPaidAt = old.earlierPaidAt) }
    return map { if (it.id == charge.id) next else it }
}

/** C-19 리마인드를 한 번 했다. */
fun Charge.reminded(now: Long): Charge = copy(remindedAt = now, reminders = reminders + 1)

/**
 * 납부 확인(전액 받음) 또는 되돌리기. 일부만 받은 청구(받은 뒤 금액이 늘어 다시 청구한 것)를 다 받았다고 했다가
 * 되돌리면 전에 받은 금액은 그대로 남긴다(전에는 0원으로 돌아가 전액이 미납으로 보였다).
 */
fun Charge.markPaid(paid: Boolean, now: Long): Charge {
    if (!paid) return copy(paidAt = earlierPaidAt, paidWon = earlierPaidWon, earlierPaidWon = null, earlierPaidAt = null)
    val partial = receivedWon.takeIf { it in 1 until won }
    return copy(paidAt = now, paidWon = won, earlierPaidWon = partial, earlierPaidAt = if (partial != null) paidAt else null)
}

/**
 * 한 주행의 청구서를 다시 만든다([made]는 이번에 만든 청구). 다시 계산해서 낼 돈이 없어진 사람(OFF, 게임 등)의
 * 아직 안 받은 청구는 지운다. 그대로 두면 나중에 미납으로 잡힌다. 이미 받은 청구는 기록으로 둔다.
 */
fun List<Charge>.rebillRide(rideId: String, made: List<Charge>): List<Charge> {
    val prefix = Charges.ridePrefix(rideId)
    val ids = made.mapTo(HashSet()) { it.id }
    val kept = filterNot { it.id.startsWith(prefix) && it.id !in ids && it.paidAt == null }
        // 일부만 받은 사람이 이번엔 낼 돈이 없으면 받은 만큼으로 닫는다. 그대로 두면 남은 금액이 영영 미납으로 남는다.
        .map { if (it.id.startsWith(prefix) && it.id !in ids && it.dueWon > 0) it.copy(won = it.receivedWon) else it }
    return made.fold(kept) { acc, charge -> acc.upsert(charge) }
}

/**
 * K-07로 월 청구를 만든 뒤 그 달 금액이 줄면(주행을 고치거나 월별 정산에서 빼서) 청구도 줄인다.
 * 그대로 두면 C-19는 옛 금액을, QR은 새 금액을 보여 주고, 0원이 되면 다시 청구할 수도 없이 미납으로 남았다.
 * [wonOf]는 청구마다 지금 그 사람의 그 달 금액(그룹이 없어져 모르면 null: 그대로 둔다).
 * 늘어난 건 그대로 둔다(K-06·K-07 "추가분 미청구"로 다시 만든다). 받은 것보다 작아지면 받은 만큼으로 닫는다.
 */
fun List<Charge>.lowerMonthly(wonOf: (Charge) -> Int?): List<Charge> = mapNotNull { charge ->
    if (!charge.monthly) return@mapNotNull charge
    val now = wonOf(charge) ?: return@mapNotNull charge
    when {
        now >= charge.won -> charge
        charge.receivedWon == 0 -> charge.takeIf { now > 0 }?.copy(won = now)
        else -> charge.copy(won = maxOf(now, charge.receivedWon))
    }
}

fun List<Charge>.unpaid(now: Long): List<Charge> = filter { it.dueWon > 0 && it.status(now) != ChargeStatus.PAID }
