package com.nbbang.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nbbang.RideApplication
import com.nbbang.domain.Boarder
import com.nbbang.domain.CarModel
import com.nbbang.domain.Charge
import com.nbbang.domain.GeoPoint
import com.nbbang.domain.Payee
import com.nbbang.domain.Place
import com.nbbang.domain.Prices
import com.nbbang.domain.Ride
import com.nbbang.domain.Rider
import com.nbbang.domain.Seat
import com.nbbang.domain.Segment
import com.nbbang.domain.TripMode
import com.nbbang.settle.CarpoolGroup
import com.nbbang.settle.OfficialPrice
import com.nbbang.settle.month
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 디버그 빌드에만 있는 시연 도구. tools/demo.sh가 adb로 부른다.
 * 차 신호(문, 기어, 주행거리, 배터리)는 가상 콘솔로 바꾸고, 에뮬레이터 내비가 주지 않는 것(경로·톨게이트·도착·주소)만 여기서 넣는다.
 * 좌석 센서는 쓰지 않으므로 타고 내리는 것은 화면에서 누르는 것과 같은 함수(T-01, C-04, C-05)를 부른다.
 *
 * adb shell am broadcast --user 10 -n com.nbbang/.debug.DemoReceiver -a com.nbbang.demo.ROUTE --es name 강릉 --ei toll 9100
 */
class DemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val vm = (context.applicationContext as? RideApplication)?.viewModel ?: return
        when (intent.action) {
            ACTION_ROUTE -> vm.debugRoute(intent.getStringExtra("name"), intent.getIntExtra("toll", 0))
            // T-01 "주행 시작"과 같다(P에서). names는 쉼표로 나눈 이름.
            ACTION_TRAVEL -> {
                vm.openTripStart()
                vm.startTravel(names(intent))
            }
            // C-04 탑승자 추가와 같다. 이름마다 새 인원으로 태운다.
            ACTION_BOARD -> vm.confirmBoarding(names(intent).map { Boarder.Newcomer(it) })?.let { Log.w(TAG, it) }
            // C-05 하차와 같다. 지금 타고 있는 사람 중 이름이 같은 사람을 내린다.
            ACTION_ALIGHT -> vm.ui.value.live?.riders?.lastOrNull { it.aboard && it.label == intent.getStringExtra("name") }
                ?.let { vm.alight(it.id) } ?: Log.w(TAG, "내릴 사람을 못 찾았어요: ${intent.getStringExtra("name")}")
            ACTION_TOLLGATE -> vm.debugTollgate(intent.getStringExtra("name").orEmpty())
            ACTION_ARRIVE -> vm.debugArrive()
            ACTION_ADDRESS -> vm.debugAddress(intent.getStringExtra("address")?.ifBlank { null })
            ACTION_SEED -> {
                val now = System.currentTimeMillis()
                val zone = ZoneId.systemDefault()
                val rides = DemoLedger.month(now, zone)
                vm.debugReplaceRides(rides, listOf(DemoLedger.group), DemoLedger.charges(rides, now, zone))
            }
            ACTION_RESET -> vm.debugReplaceRides(emptyList())
            ACTION_PAYEE -> vm.saveSettings(
                vm.ui.value.settings.copy(
                    payee = Payee(
                        intent.getStringExtra("name").orEmpty(),
                        intent.getStringExtra("link").orEmpty(),
                        intent.getStringExtra("account").orEmpty(),
                    ),
                    // 시연 차(가상 콘솔)는 배터리로 달린다. "전기"로 맞추면 배터리로 잰 실제 연비로 절약 기여(C-11)가 나온다.
                    // 휘발유 + 차종(model)이면 공인연비 기준연비와 [ACTION_ECONOMY]로 넣은 평균 연비를 비교한다.
                    fuelKind = intent.getStringExtra("fuel") ?: vm.ui.value.settings.fuelKind,
                    prices = intent.getStringExtra("model")?.let { vm.ui.value.settings.prices.copy(carModel = CarModel.of(it)) }
                        ?: vm.ui.value.settings.prices,
                ),
            )
            // 가상 콘솔에는 내연기관 평균 연비가 없다. 차가 알려 주는 평균 연비(km/L)를 넣는다. 0이면 지운다.
            // 가상 콘솔은 주행거리를 한 번에 올린다. 그만큼 달리는 데 걸렸을 시간을 앱 시계에 먼저 흘린다(도심·고속 판정).
            ACTION_ELAPSE -> vm.debugElapse(intent.getLongExtra("ms", 0L).coerceIn(0L, 6 * 60 * 60_000L))
            ACTION_ECONOMY -> {
                val kmPerLiter = intent.getFloatExtra("kmPerLiter", 0f).toDouble().takeIf { it > 0.0 }
                vm.debugCar { it.copy(kmPerLiter = kmPerLiter) }
            }
            // 키 없이 공시 평균 유가 화면을 보려고 오늘 날짜로 값을 넣는다. 실제 조회가 되면 덮어쓴다.
            ACTION_OFFICIAL -> {
                val today = LocalDate.now(ZoneId.systemDefault())
                vm.debugOfficial(
                    listOf(
                        OfficialPrice("휘발유", intent.getIntExtra("gasoline", 1_667), today),
                        OfficialPrice("경유", intent.getIntExtra("diesel", 1_533), today),
                        OfficialPrice("LPG", intent.getIntExtra("lpg", 1_053), today),
                    ),
                )
            }
            else -> return
        }
        Log.i(TAG, "${intent.action?.substringAfterLast('.')} ${intent.extras?.keySet()?.joinToString { "$it=${intent.extras?.get(it)}" }.orEmpty()}")
    }

    private fun names(intent: Intent): List<String> =
        intent.getStringExtra("names").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private companion object {
        const val TAG = "RideDemo"
        const val ACTION_ROUTE = "com.nbbang.demo.ROUTE"
        const val ACTION_TRAVEL = "com.nbbang.demo.TRAVEL"
        const val ACTION_BOARD = "com.nbbang.demo.BOARD"
        const val ACTION_ALIGHT = "com.nbbang.demo.ALIGHT"
        const val ACTION_TOLLGATE = "com.nbbang.demo.TOLLGATE"
        const val ACTION_ARRIVE = "com.nbbang.demo.ARRIVE"
        const val ACTION_ADDRESS = "com.nbbang.demo.ADDRESS"
        const val ACTION_SEED = "com.nbbang.demo.SEED"
        const val ACTION_RESET = "com.nbbang.demo.RESET"
        const val ACTION_PAYEE = "com.nbbang.demo.PAYEE"
        const val ACTION_OFFICIAL = "com.nbbang.demo.OFFICIAL"
        const val ACTION_ECONOMY = "com.nbbang.demo.ECONOMY"
        const val ACTION_ELAPSE = "com.nbbang.demo.ELAPSE"
    }
}

/**
 * 시연용 카풀 장부: 평일 아침마다 같은 길로 출근하는 동료 카풀 그룹 "판교 출근".
 * 지훈은 조수석에서 회사까지(12km), 민지는 화·목에 뒷좌석 왼쪽에서 타서 중간(5km)에 내린다.
 * 지난 20일 치를 월별 정산에 넣는다. 지난달 몫은 1일에 청구해서 지훈은 받았고 민지는 미납으로 남긴다(C-01·C-19 시연).
 */
internal object DemoLedger {
    private val home = GeoPoint(37.3595, 127.1052)
    private val station = GeoPoint(37.3851, 127.1231)
    val group = CarpoolGroup("demo-commute", "판교 출근", listOf("지훈", "민지"))

    fun month(now: Long, zone: ZoneId): List<Ride> {
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val lastSettled = today.with(java.time.temporal.TemporalAdjusters.previous(DayOfWeek.FRIDAY))
        return (1..20).map { today.minusDays(it.toLong()) }
            .filter { it.dayOfWeek.value <= 5 }
            .sorted()
            .mapIndexed { i, day -> commute(day, i, zone, paid = !day.isAfter(lastSettled.minusDays(7))) }
    }

    /** 지난달 몫 청구: 이번 달 1일 9시에 청구, 지훈은 3일에 받음, 민지는 기한(+4일)이 지나면 미납. */
    fun charges(rides: List<Ride>, now: Long, zone: ZoneId): List<Charge> {
        val thisMonth = group.monthOf(now, zone)
        val last = group.month(thisMonth.minusMonths(1), rides, emptyList(), zone)
        val first = thisMonth.atDay(1).atTime(9, 0).atZone(zone)
        val billedAt = first.toInstant().toEpochMilli()
        val paidAt = first.plusDays(2).toInstant().toEpochMilli()
        return last.members.filter { it.won > 0 }.map { member ->
            last.charge(member, billedAt).let { if (member.name == "지훈") it.copy(paidAt = paidAt) else it }
        }
    }

    private fun commute(day: LocalDate, index: Int, zone: ZoneId, paid: Boolean): Ride {
        val start = day.atTime(LocalTime.of(8, 5 + index % 10)).atZone(zone).toInstant().toEpochMilli()
        val withMinji = day.dayOfWeek == DayOfWeek.TUESDAY || day.dayOfWeek == DayOfWeek.THURSDAY
        val jihoon = Rider(1, Seat.FRONT, start, 0.0, Place("판교 출발한 곳", home), start + 1_500_000, 12.0, Place("회사 앞", station), name = "지훈")
        val minji = Rider(2, Seat.REAR_LEFT, start, 0.0, Place("판교 출발한 곳", home), start + 600_000, 5.0, Place("정자역", station), name = "민지")
        val riders = if (withMinji) listOf(jihoon, minji) else listOf(jihoon)
        val segments = if (withMinji) {
            listOf(Segment(0.0, 5.0, setOf(1, 2), energyUsed = 0.9), Segment(5.0, 12.0, setOf(1), energyUsed = 1.4))
        } else {
            listOf(Segment(0.0, 12.0, setOf(1), energyUsed = 2.3))
        }
        return Ride(
            id = "seed-$start",
            startedAt = start,
            endedAt = start + 1_500_000,
            prices = Prices(wonPerKwh = 320),
            segments = segments,
            riders = riders,
            destination = "회사",
            paidRiderIds = if (paid) riders.map { it.id }.toSet() else emptySet(),
            mode = TripMode.CARPOOL,
            groupId = group.id,
            monthly = true,
        )
    }
}
