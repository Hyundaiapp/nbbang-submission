// F-C07 당일 공시 평균 유가. 오피넷(한국석유공사) 전국 주유소 평균가격 API를 읽는다.
// 조회 실패는 최근 조회값을 쓰고, 화면과 청구서에는 기준일을 함께 적는다(기획서 8장 예외 처리).
package com.nbbang.settle

import com.nbbang.domain.Power
import com.nbbang.domain.Prices
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** 한 연료의 공시 평균 유가. [won]은 리터당 원(소수점 반올림), [date]는 오피넷 기준일. */
data class OfficialPrice(val fuelKind: String, val won: Int, val date: LocalDate)

object Opinet {
    /** 출처 표시(오피넷 저작권 정책). */
    const val SOURCE = "오피넷"

    private const val BASE = "https://www.opinet.co.kr/api/avgAllPrice.do?out=json"

    /**
     * 전국 평균 유가 주소. 키는 local.properties의 nbbang.opinetKey에만 둔다(커밋 금지).
     * 일반 API 키(F로 시작하는 10자리)는 `code`, 예전에 받은 인증키는 `certkey`로 받는다(11/15 확인).
     * 둘 다 붙여 보내면 맞는 쪽으로 답한다. 그래서 어떤 키를 넣어도 된다.
     */
    fun url(key: String): String {
        val k = java.net.URLEncoder.encode(key.trim(), "UTF-8")
        return "$BASE&code=$k&certkey=$k"
    }

    /** 앱의 연료 종류 → 오피넷 제품 코드. 전기는 없다. */
    val CODES = mapOf("휘발유" to "B027", "경유" to "D047", "LPG" to "K015")

    private val ITEM = Regex("""\{[^{}]*\}""")
    private val FIELD = Regex(""""(\w+)"\s*:\s*"([^"]*)"""")
    private val DAY = DateTimeFormatter.BASIC_ISO_DATE

    /**
     * avgAllPrice.do JSON에서 앱이 쓰는 세 연료만 꺼낸다. 키가 틀리면 오피넷은 빈 목록을 돌려주므로
     * 빈 결과는 실패로 본다(호출하는 쪽이 최근 조회값을 그대로 쓴다).
     */
    fun parse(json: String): List<OfficialPrice> = ITEM.findAll(json).mapNotNull { item ->
        val f = FIELD.findAll(item.value).associate { it.groupValues[1] to it.groupValues[2].trim() }
        val fuel = CODES.entries.firstOrNull { it.value == f["PRODCD"] }?.key ?: return@mapNotNull null
        val won = f["PRICE"]?.toDoubleOrNull()?.takeIf { it > 0 }?.roundToInt() ?: return@mapNotNull null
        val date = f["TRADE_DT"]?.let { runCatching { LocalDate.parse(it, DAY) }.getOrNull() } ?: return@mapNotNull null
        OfficialPrice(fuel, won, date)
    }.toList()
}

/** 이 연료의 공시 평균 유가. 같은 연료가 여러 날 있으면 가장 최근 것. */
fun List<OfficialPrice>.of(fuelKind: String): OfficialPrice? = filter { it.fuelKind == fuelKind }.maxByOrNull { it.date }

/**
 * 다음 주행에 쓸 단가. 휘발유·경유·LPG이고 공시 평균이 있으면 리터당 단가를 그 값으로 바꾸고 기준일을 남긴다.
 * 전기이거나 공시 평균이 없으면 설정 단가 그대로다.
 */
fun Prices.withOfficial(fuelKind: String, official: List<OfficialPrice>): Prices {
    val today = official.of(fuelKind) ?: return copy(officialDate = null)
    return copy(wonPerLiter = today.won, officialDate = today.date.toString())
}

/** "11/11" 같은 짧은 기준일. */
fun shortDate(isoDate: String): String = runCatching {
    LocalDate.parse(isoDate).let { "${it.monthValue}/${it.dayOfMonth}" }
}.getOrDefault(isoDate)

/** 화면에 붙이는 단가 출처: "11/11 공시 평균" 또는 "설정 단가". */
fun Prices.priceSource(power: Power): String =
    if (power != Power.ELECTRIC && officialDate != null) "${shortDate(officialDate)} 공시 평균" else "설정 단가"

/** "비용 요약 (… 기준)"에 넣는 말: "11/11 공시 평균 유가" 또는 "설정 단가". */
fun Prices.priceBasis(power: Power): String =
    if (power != Power.ELECTRIC && officialDate != null) "${shortDate(officialDate)} 공시 평균 유가" else "설정 단가"
