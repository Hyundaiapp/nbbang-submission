package com.nbbang.settle

import com.nbbang.domain.Power
import com.nbbang.domain.Prices
import com.nbbang.domain.Ride
import com.nbbang.domain.Rider
import com.nbbang.domain.Seat
import com.nbbang.domain.Segment
import com.nbbang.domain.TollPass
import com.nbbang.domain.Charge
import com.nbbang.domain.Charges
import com.nbbang.domain.Lines
import com.nbbang.domain.markPaid
import com.nbbang.domain.reminded
import com.nbbang.domain.upsert
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTest {
    private val ride = Ride(
        id = "t",
        startedAt = 0,
        power = Power.ELECTRIC,
        prices = Prices(kmPerKwh = 5.0, wonPerKwh = 300),
        riders = listOf(Rider(1, Seat.FRONT, 0, 0.0, name = "민수")),
        segments = listOf(Segment(0.0, 50.0, setOf(0, 1))),
    )

    @Test
    fun `도착 문장은 화면과 같은 정산 숫자를 말한다`() {
        // 50km ÷ 5.0 × 300원 = 3,000원, 운전자와 민수가 1,500원씩
        assertEquals(
            "도착했어요. 50km, 충전비 3,000원, 모두 3,000원이에요. 민수 1,500원이에요. 화면에서 확인하고 N빵해 주세요.",
            ride.arrivalLine(),
        )
    }

    @Test
    fun `미입력 톨비는 금액에 넣지 않고 따로 알린다`() {
        val withToll = ride.copy(tollPasses = listOf(TollPass(at = 1, km = 20.0, aboard = setOf(0, 1), won = null)))
        assertEquals(
            "도착했어요. 50km, 충전비 3,000원, 모두 3,000원이에요. 톨비 1건은 금액을 넣어야 해요. 민수 1,500원이에요. 화면에서 확인하고 N빵해 주세요.",
            withToll.arrivalLine(),
        )
    }

    @Test
    fun `리마인드는 남은 금액과 기한을 말하고 횟수를 센다`() {
        val day = 86_400_000L
        val zone = ZoneId.of("Asia/Seoul")
        val charge = Charge("m:g:2026-10:민지", "민지", "10월 카풀 · 출퇴근", 12_000, billedAt = 0, dueAt = Charges.dueOf(0))

        assertEquals(
            "민지 님, 10월 카풀 정산 12,000원이 남아 있어요. 화면의 QR을 찍으면 바로 보낼 수 있어요.",
            Lines.remind("민지", charge.label.substringBefore(" · "), charge.dueWon, charge.overdueDays(day, zone)),
        )
        assertEquals(
            "민지 님, 10월 카풀 정산 12,000원이 아직 남아 있어요. 납부 기한이 2일 지났어요. 화면의 QR을 찍어 주세요.",
            Lines.remind("민지", "10월 카풀", charge.dueWon, charge.overdueDays(6 * day, zone)),
        )
        val twice = charge.reminded(day).reminded(2 * day)
        assertEquals(2, twice.reminders)
        assertEquals(2 * day, twice.remindedAt)
        // 받은 걸로 적어도 리마인드 기록은 남고, 청구서를 다시 만들면 새 청구라 0부터 센다.
        assertEquals(2, twice.markPaid(true, 3 * day).reminders)
        assertEquals(0, listOf(twice).upsert(charge.copy(billedAt = 4 * day)).single().reminders)
    }
}
