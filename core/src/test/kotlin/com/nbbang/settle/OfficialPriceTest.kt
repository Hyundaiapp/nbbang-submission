package com.nbbang.settle

import com.nbbang.domain.Prices
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class OfficialPriceTest {
    @Test
    fun `일반 API 키(code)와 예전 인증키(certkey) 어느 쪽이든 받게 둘 다 붙인다`() {
        val url = Opinet.url(" F000000000 ")
        assertTrue(url, url.startsWith("https://www.opinet.co.kr/api/avgAllPrice.do?out=json"))
        assertTrue(url, url.endsWith("&code=F000000000&certkey=F000000000"))
    }

    // 오피넷 avgAllPrice.do 응답 모양(공식 안내 예시 값). 실제 응답은 앞뒤에 공백·줄바꿈이 많다.
    private val sample = """

        {"RESULT":
        {"OIL":[
        {"TRADE_DT":"20251111","PRODCD":"B034","PRODNM":"고급휘발유","PRICE":"1919.44","DIFF":"-0.10"},
        {"TRADE_DT":"20251111","PRODCD":"B027","PRODNM":"휘발유","PRICE":"1667.33","DIFF":"-0.23"},
        {"TRADE_DT":"20251111","PRODCD":"D047","PRODNM":"자동차용경유","PRICE":"1532.62","DIFF":"+0.39"},
        {"TRADE_DT":"20251111","PRODCD":"C004","PRODNM":"실내등유","PRICE":"1295.23","DIFF":"-0.72"},
        {"TRADE_DT":"20251111","PRODCD":"K015","PRODNM":"자동차용부탄","PRICE":"1052.64","DIFF":"-0.07"}
        ]} }
    """.trimIndent()

    @Test
    fun `휘발유·경유·LPG만 리터당 원으로 꺼낸다`() {
        val day = LocalDate.of(2025, 11, 11)
        assertEquals(
            listOf(OfficialPrice("휘발유", 1_667, day), OfficialPrice("경유", 1_533, day), OfficialPrice("LPG", 1_053, day)),
            Opinet.parse(sample),
        )
    }

    @Test
    fun `키가 틀리면 빈 목록이라 실패로 본다`() {
        assertTrue(Opinet.parse("""{"RESULT": {"OIL":[  ]} }""").isEmpty())
        assertTrue(Opinet.parse("<html>점검 중</html>").isEmpty())
    }

    @Test
    fun `공시 평균이 있으면 리터당 단가를 바꾸고 기준일을 남긴다`() {
        val official = Opinet.parse(sample)
        val prices = Prices(wonPerLiter = 1_700, wonPerKwh = 320).withOfficial("경유", official)
        assertEquals(1_533, prices.wonPerLiter)
        assertEquals("2025-11-11", prices.officialDate)
        assertEquals(320, prices.wonPerKwh)
    }

    @Test
    fun `전기이거나 조회값이 없으면 설정 단가 그대로`() {
        val official = Opinet.parse(sample)
        assertEquals(Prices(wonPerLiter = 1_700), Prices(wonPerLiter = 1_700).withOfficial("전기", official))
        assertEquals(Prices(wonPerLiter = 1_700), Prices(wonPerLiter = 1_700, officialDate = "2025-11-10").withOfficial("휘발유", emptyList()))
    }

    @Test
    fun `여러 날이 있으면 가장 최근 값`() {
        val old = OfficialPrice("휘발유", 1_650, LocalDate.of(2025, 11, 10))
        val new = OfficialPrice("휘발유", 1_667, LocalDate.of(2025, 11, 11))
        assertEquals(new, listOf(new, old).of("휘발유"))
        assertNull(listOf(new).of("LPG"))
    }
}
