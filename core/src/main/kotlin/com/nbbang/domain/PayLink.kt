package com.nbbang.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/** 돈을 받을 곳. 차 안에만 저장하고, 동승자에게 보여 줄 QR에만 담는다. */
data class Payee(
    val name: String = "",
    /** 송금 링크(예: toss.me/아이디). https 링크만 버튼으로 보여 준다. */
    val link: String = "",
    /** 은행과 계좌번호. 복사 버튼으로 보여 준다. */
    val account: String = "",
) {
    val isEmpty: Boolean get() = link.isBlank() && account.isBlank()
}

/** QR에 담을 내용. 사람마다 금액과 근거 몇 줄. */
data class PayRequest(
    val title: String,
    val items: List<Pair<String, Int>>,
    val notes: List<String>,
    val payee: Payee,
    /** C-18 내 몫 내역(항목, 금액). 합이 [items]의 금액과 같다. 한 사람짜리 청구서에만 쓴다. */
    val costs: List<Pair<String, Int>> = emptyList(),
    /** C-18 게임 결과 문구. */
    val game: String? = null,
    /** 카풀인지 여행인지([TripMode.key]: "carpool" | "trip"). 청구서 페이지가 모드와 배경을 정한다. */
    val mode: String? = null,
    /** 게임 종류([GameKind.key]: "ladder" | "cup" | "card" | "dice"). 게임으로 정했을 때만. */
    val gameKind: String? = null,
    /** 게임에서 진 사람(많이 내는 사람)의 [items] 순번. 청구서 페이지가 그 사람에게 게임 배경을 보여 준다. */
    val losers: Set<Int> = emptySet(),
    /** 1원 단위로 내려서 N빵이 내는 돈(0~1원). [costs]의 합은 내는 금액보다 이만큼 크다. */
    val nbbangWon: Int = 0,
)

/**
 * 동승자가 휴대폰으로 찍는 QR.
 * 정산 페이지 주소가 있으면 "주소#d=(JSON을 base64url로)"를 담는다. 데이터는 #뒤에만 있어 서버로 가지 않는다.
 * 주소가 없으면 휴대폰 카메라가 그대로 보여 주는 글을 담는다.
 */
object PayLink {
    fun forBill(ride: Ride, bill: Bill, payee: Payee, zone: ZoneId): PayRequest {
        val title = "${date(ride.startedAt, zone)} ${ride.destination?.let { "$it 가는 길" } ?: "함께 탄 길"}"
        val notes = buildList {
            add("거리 ${Lines.km(bill.km)}")
            add("${bill.power.energyName} ${Lines.energy(bill)} ${Lines.won(bill.energyWon)}" + if (bill.energyMeasured) "" else " (어림)")
            if (bill.tollWon > 0) add("통행료 ${Lines.won(bill.tollWon)}" + if (bill.tollEstimated) " (내비 예상)" else "")
            if (bill.extraWon > 0) add("주차비·기타 ${Lines.won(bill.extraWon)}")
            add("나누는 방식: ${ride.rule.label}")
        }
        val items = bill.shares.filter { it.won > 0 && !it.paid }.map { "${it.rider.label} · ${Lines.km(it.km)}" to it.won }
        return PayRequest(title, items, notes, payee)
    }

    fun forPerson(person: PersonBalance, payee: Payee): PayRequest = PayRequest(
        title = "${person.name} 님 함께 탄 길 정산",
        items = listOf(person.name to person.owedWon),
        notes = listOf("${person.rides}번 · ${Lines.km(person.km)} 함께 탔어요"),
        payee = payee,
    )

    /**
     * QR 한 장에 넣는 최대 바이트. 오류 정정 M으로 QR 40형은 2,331바이트까지 되지만,
     * 차 화면에서 휴대폰으로 잘 찍히도록 그보다 작게 둔다.
     */
    const val QR_MAX_BYTES = 1_500

    /**
     * QR에 넣을 글. [baseUrl]이 비어 있으면 글만 담는다.
     * 내역([PayRequest.costs])이 길어 [QR_MAX_BYTES]를 넘으면(한 달 카풀 수십 회 등) 앞에서부터 들어가는 만큼만 두고
     * 나머지는 "외 N건"으로 묶는다. 금액 합계는 그대로다.
     */
    fun content(request: PayRequest, baseUrl: String): String {
        val full = raw(request, baseUrl)
        if (fits(full) || request.costs.size <= 1) return full
        var low = 0
        var high = request.costs.size - 1
        var best: String? = null
        while (low <= high) {
            val keep = (low + high) / 2
            val candidate = raw(request.copy(costs = folded(request.costs, keep)), baseUrl)
            if (fits(candidate)) {
                best = candidate
                low = keep + 1
            } else {
                high = keep - 1
            }
        }
        return best ?: raw(request.copy(costs = folded(request.costs, 0)), baseUrl)
    }

    private fun raw(request: PayRequest, baseUrl: String): String =
        if (baseUrl.isBlank()) text(request) else url(baseUrl, json(request))

    private fun fits(content: String): Boolean = content.toByteArray(Charsets.UTF_8).size <= QR_MAX_BYTES

    /** 앞의 [keep]건은 그대로, 나머지는 한 줄("외 N건")로. */
    private fun folded(costs: List<Pair<String, Int>>, keep: Int): List<Pair<String, Int>> {
        val rest = costs.drop(keep)
        return costs.take(keep) + ((if (keep == 0) "${rest.size}건" else "외 ${rest.size}건") to rest.sumOf { it.second })
    }

    fun url(baseUrl: String, json: String): String =
        baseUrl.substringBefore('#') + "#d=" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))

    fun json(request: PayRequest): String = buildString {
        append("{\"v\":1,\"t\":").append(quote(request.title))
        append(",\"s\":[")
        request.items.forEachIndexed { i, (who, won) ->
            if (i > 0) append(',')
            append("{\"n\":").append(quote(who)).append(",\"w\":").append(won)
            if (i in request.losers) append(",\"x\":1")
            append('}')
        }
        append("],\"r\":[")
        request.notes.forEachIndexed { i, note ->
            if (i > 0) append(',')
            append(quote(note))
        }
        append(']')
        if (request.costs.isNotEmpty()) {
            append(",\"c\":[")
            request.costs.forEachIndexed { i, (what, won) ->
                if (i > 0) append(',')
                append("{\"n\":").append(quote(what)).append(",\"w\":").append(won).append('}')
            }
            append(']')
        }
        request.game?.let { append(",\"g\":").append(quote(it)) }
        request.mode?.let { append(",\"m\":").append(quote(it)) }
        request.gameKind?.let { append(",\"gk\":").append(quote(it)) }
        if (request.nbbangWon > 0) append(",\"e\":").append(request.nbbangWon)
        append(",\"p\":{\"n\":").append(quote(request.payee.name))
        append(",\"l\":").append(quote(request.payee.link))
        append(",\"a\":").append(quote(request.payee.account)).append("}}")
    }

    fun text(request: PayRequest): String = buildString {
        appendLine(request.title)
        request.items.forEach { (who, won) -> appendLine("$who ${Lines.won(won)}") }
        request.costs.forEach { (what, won) -> appendLine("· $what ${Lines.won(won)}") }
        request.game?.let { appendLine(it) }
        if (request.nbbangWon > 0) appendLine("${Lines.won(request.nbbangWon)}은 N빵이 내요")
        request.notes.forEach { appendLine(it) }
        val payee = request.payee
        if (payee.link.isNotBlank()) appendLine("보낼 곳: ${payee.link}")
        if (payee.account.isNotBlank()) appendLine("계좌: ${payee.account}${if (payee.name.isNotBlank()) " (${payee.name})" else ""}")
    }.trimEnd()

    private fun quote(text: String): String = buildString {
        append('"')
        text.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private fun date(at: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("M월 d일", Locale.KOREA).format(Instant.ofEpochMilli(at).atZone(zone))
}
