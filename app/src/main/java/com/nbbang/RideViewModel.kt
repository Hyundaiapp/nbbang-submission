package com.nbbang

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nbbang.domain.Boarder
import com.nbbang.domain.CarState
import com.nbbang.domain.Charge
import com.nbbang.domain.ChargeStatus
import com.nbbang.domain.Cost
import com.nbbang.domain.DRIVER_ID
import com.nbbang.domain.Extra
import com.nbbang.domain.ExtraKind
import com.nbbang.domain.Gear
import com.nbbang.domain.LedgerSummary
import com.nbbang.domain.Lines
import com.nbbang.domain.Place
import com.nbbang.domain.Prices
import com.nbbang.domain.Popup
import com.nbbang.domain.PopupQueue
import com.nbbang.domain.Power
import com.nbbang.domain.Ride
import com.nbbang.domain.RideEvent
import com.nbbang.domain.RideGame
import com.nbbang.domain.RideSettings
import com.nbbang.domain.RideTracker
import com.nbbang.domain.Rider
import com.nbbang.domain.Seat
import com.nbbang.domain.TrackerSnapshot
import com.nbbang.domain.TripMode
import com.nbbang.domain.TripSetup
import com.nbbang.domain.upsert
import com.nbbang.domain.guessName
import com.nbbang.domain.boardingProblem
import com.nbbang.domain.seatBoarders
import com.nbbang.domain.knownNames
import com.nbbang.domain.placeholderName
import com.nbbang.domain.markPaid
import com.nbbang.domain.paidAll
import com.nbbang.settle.unsettled
import com.nbbang.domain.Charges
import com.nbbang.domain.sharedNothing
import com.nbbang.domain.rebillRide
import com.nbbang.domain.lowerMonthly
import com.nbbang.domain.reminded
import com.nbbang.domain.removeCost
import com.nbbang.domain.unpaid
import com.nbbang.domain.settle
import com.nbbang.domain.summarize
import com.nbbang.domain.unconfirmedIfCarpool
import com.nbbang.platform.CarSource
import com.nbbang.platform.CaptionVoice
import com.nbbang.platform.DrivingRestriction
import com.nbbang.platform.GleoAction
import com.nbbang.platform.GleoBridge
import com.nbbang.platform.GleoCall
import com.nbbang.platform.GleoReply
import com.nbbang.platform.GleoResponder
import com.nbbang.platform.LedgerStore
import com.nbbang.platform.Locator
import com.nbbang.platform.ManualCar
import com.nbbang.platform.NaviRoute
import com.nbbang.platform.NaviSource
import com.nbbang.platform.NoLocator
import com.nbbang.platform.OfficialPriceSource
import com.nbbang.platform.PleosCar
import com.nbbang.platform.PleosLocator
import com.nbbang.platform.PleosNavi
import com.nbbang.platform.PleosVoice
import com.nbbang.platform.QuietNavi
import com.nbbang.platform.RideService
import com.nbbang.platform.Voice
import com.nbbang.settle.CarpoolGroup
import com.nbbang.settle.arrivalLine
import com.nbbang.settle.ExpenseSource
import com.nbbang.settle.GamePlan
import com.nbbang.settle.gamePot
import com.nbbang.settle.GroupMonth
import com.nbbang.settle.charges
import com.nbbang.settle.forStorage
import com.nbbang.settle.month
import com.nbbang.settle.OfficialPrice
import com.nbbang.settle.withOfficial
import com.nbbang.settle.personId
import com.nbbang.settle.Settlement
import com.nbbang.settle.settlement
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object DrivingStatus : Screen

    /** 정산 카드. [rideId]가 null이면 아직 진행 중인 길을 지금 끝낸다고 치고 보여 준다. */
    data class Card(val rideId: String?) : Screen

    data class Person(val name: String) : Screen

    data object Settings : Screen

    data class CarpoolGroups(val selectedId: String?) : Screen

    data class CarpoolGroupEdit(val groupId: String?, val previousSelection: String?) : Screen

    data class CarpoolPrepare(val groupId: String) : Screen

    data class CarpoolSummary(val rideId: String? = null) : Screen

    data class CarpoolSettlementChoice(val rideId: String) : Screen

    data class CarpoolMonthly(val groupId: String? = null, val month: YearMonth? = null) : Screen

    data class CarpoolRideDetails(val rideId: String, val back: Screen) : Screen

    data class CarpoolMonthlyBilling(val groupId: String, val month: YearMonth, val selectedName: String? = null) : Screen

    data class MonthlyInvoices(val billing: CarpoolMonthlyBilling, val names: List<String>) : Screen

    // ───── 정산 흐름(ui/settle). 모두 끝난 주행([rideId])을 본다. ─────

    /** C-08 최종 N빵 정산. 절약 기여(C-11)는 이 화면이 처음 열릴 때 묻는다. */
    data class Settle(val rideId: String) : Screen

    /** C-12 게임 선택. */
    data class GamePick(val rideId: String) : Screen

    /** C-13~C-16 게임. */
    data class Game(val rideId: String, val plan: GamePlan) : Screen

    /** C-17 최종 금액 확인. */
    data class FinalCheck(val rideId: String) : Screen

    /** C-18 QR 청구서. [personId]가 있으면 그 사람 청구서를 먼저 연다. [chargesBack]이 있으면 뒤로 가기가 그 C-19(필터·선택 그대로)로 간다. */
    data class Invoices(val rideId: String, val personId: Int? = null, val chargesBack: ChargeList? = null) : Screen

    /** C-19 청구 현황. [status]가 있으면 그 상태만(홈 "미납 N건" → 미납). */
    data class ChargeList(
        val status: ChargeStatus? = null,
        val selectedId: String? = null,
        val carpoolBack: CarpoolMonthly? = null,
    ) : Screen

    // ───── 여행 모드(ui/trip) ─────

    /** T-01 인원 추가(여행 시작). */
    data object TripStart : Screen

    /** T-03 N빵하기(주행 요약). 진행 중인 길을 지금 끝낸다고 친 모습을 보여 주고, N빵하기!에서 끝낸다. */
    data object TripSummary : Screen
}

internal data class CostVoiceDraft(val name: String, val won: Int?)

data class UiState(
    val car: CarState = CarState(),
    /** 진행 중인 길을 지금 끝낸다고 친 모습. 없으면 null. */
    val live: Ride? = null,
    val rides: List<Ride> = emptyList(),
    val summary: LedgerSummary = LedgerSummary(),
    val settings: RideSettings = RideSettings(),
    val screen: Screen = Screen.Home,
    val caption: String? = null,
    val restricted: Boolean = false,
    val names: List<String> = emptyList(),
    val payUrl: String = "",
    val zone: ZoneId = ZoneId.systemDefault(),
    /** F-C05 내비 위 팝업 큐. 화면은 [popup] 하나만 띄우고, 닫을 때 [RideViewModel.dismissPopup]을 부른다. */
    val popups: PopupQueue = PopupQueue(),
    /** 출발 전에 고른 모드·그룹·오늘 탈 사람(K-03, T-01). */
    val setup: TripSetup = TripSetup(),
    /** K-01 카풀 그룹. 최근 사용 순. */
    val groups: List<CarpoolGroup> = emptyList(),
    /** C-19 청구 현황(청구서를 만든 것만). */
    val charges: List<Charge> = emptyList(),
    /** F-C07 마지막으로 받은 공시 평균 유가(오피넷). 비어 있으면 설정 단가를 쓴다. */
    val official: List<OfficialPrice> = emptyList(),
    /** 오피넷 키가 빌드에 들어 있는지. 없으면 조회하지 않는다. */
    val officialEnabled: Boolean = false,
) {
    /** 다음 주행에 쓸 단가: 휘발유·경유·LPG는 공시 평균 유가, 못 불러오면 설정 단가(F-C07). */
    val ridePrices: Prices get() = settings.prices.withOfficial(settings.fuelKind, official)

    fun group(id: String?): CarpoolGroup? = groups.firstOrNull { it.id == id }

    /** 혼자 픽업하러 가는 길처럼 함께 나눈 게 없는 길이 기록 중이면 K-03에서 버리고 카풀을 시작할 수 있다. */
    val canSetUpCarpool: Boolean get() = car.gear == Gear.PARK && !restricted && (live == null || live.sharedNothing)

    val hasCarpoolVehicleSettings: Boolean get() {
        val electric = settings.power == Power.ELECTRIC
        val prices = ridePrices
        val efficiency = if (electric) prices.kmPerKwh else prices.kmPerLiter
        val unitWon = if (electric) prices.wonPerKwh else prices.wonPerLiter
        return settings.fuelKind.isNotBlank() && efficiency.isFinite() && efficiency > 0 && unitWon > 0
    }

    /** K-06·K-07 그룹의 한 달. 이번 달은 `group.monthOf(now, zone)`. */
    fun groupMonth(groupId: String, month: YearMonth): GroupMonth? = group(groupId)?.month(month, rides, charges, zone)

    fun ride(id: String?): Ride? = if (id == null) live else rides.firstOrNull { it.id == id }

    /** 지금 띄울 팝업. 없으면 null. */
    val popup: Popup? get() = popups.head

    /** C-06 입력을 기다리는 첫 톨비. 화면에 연결된 통과 기록이 생긴 뒤에만 표시한다. */
    val nextTollPopup: Popup.Toll?
        get() {
            val passes = live?.tollPasses ?: return null
            return popups.items.filterIsInstance<Popup.Toll>().firstOrNull { popup ->
                passes.getOrNull(popup.index)?.let { it.won == null } == true
            }
        }

    /** 기획서 정산(R1~R9). [id]가 null이면 진행 중인 길을 지금 끝낸다고 친 금액이다. 화면은 이 값만 보여 준다. */
    fun settlement(id: String?): Settlement? = ride(id)?.settlement()
}

/**
 * 앱 전체에서 하나만 둔다([RideApplication]). 화면이 없어도(내비가 앞에 있어도) 기록 서비스가 이것을 살려 둔다.
 *
 * 흐름: 함께 타고 D → 자리마다 탄 구간 기록 → 내비 도착 + P → 정산 카드를 앞으로 가져오고 한 문장 말하기
 *      → 운전자가 주행 종료(K-04 "카풀 주행 종료!", T-03 "N빵하기!")를 누르면 장부에 남기기. 문으로는 끝내지 않는다.
 */
class RideViewModel(app: Application) : AndroidViewModel(app) {
    private val automotive = app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    internal val manualCar = ManualCar()
    internal val quietNavi = QuietNavi()
    private val car: CarSource = if (automotive) PleosCar(app) else manualCar
    private val navi: NaviSource = if (automotive) PleosNavi(app) else quietNavi
    private val locator: Locator = if (automotive) PleosLocator(app) else NoLocator()
    private val voice: Voice = if (automotive) PleosVoice(app) else CaptionVoice()
    private val restriction = DrivingRestriction(app)
    private val store = LedgerStore(app)
    private val officialSource = OfficialPriceSource(BuildConfig.OPINET_KEY)
    private val tracker = RideTracker()
    private val zone: ZoneId = ZoneId.systemDefault()
    private var arrivedAt: Long? = null

    /** 시연 도구가 주소를 대신 넣을 때 쓴다. */
    private var addressOverride: String? = null

    internal var clock: () -> Long = System::currentTimeMillis

    /** 내비가 앞에 있을 때 정산 카드를 보여 주려고 앱을 앞으로 가져온다(SYSTEM_ALERT_WINDOW가 있어야 한다). */
    internal var bringToFront: () -> Unit = {
        val app = getApplication<Application>()
        runCatching {
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    private val state = MutableStateFlow(
        UiState(
            rides = store.loadRides(),
            settings = store.loadSettings(),
            payUrl = BuildConfig.PAY_URL,
            zone = zone,
            groups = store.loadGroups().sortedByDescending { it.lastUsedAt },
            charges = store.loadCharges(),
            official = store.loadOfficial(),
            officialEnabled = officialSource.enabled,
        ),
    )
    val ui: StateFlow<UiState> = state
    private var costVoiceRideId: String? = null
    private val costVoiceDrafts = MutableSharedFlow<CostVoiceDraft>(extraBufferCapacity = 1)
    internal val costVoiceInput = costVoiceDrafts.asSharedFlow()

    internal fun requestCostVoice(rideId: String) {
        if (tracker.ride?.id == rideId) costVoiceRideId = rideId
    }

    internal fun cancelCostVoice(rideId: String) {
        if (costVoiceRideId == rideId) costVoiceRideId = null
    }

    /** F-C01 마지막으로 저장한 진행 중인 길. */
    private var savedLive: TrackerSnapshot? = null
    private var savedLiveAt = 0L

    init {
        restoreLive()
        // 계산 규칙이 바뀐 뒤(앱 업데이트) 처음 열면 월 금액이 청구보다 작을 수 있다. QR과 C-19가 같은 금액을 보이게 맞춘다.
        lowerMonthlyCharges()
        refreshLedger()
        car.start()
        navi.start()
        voice.start()
        restriction.start()
        locator.start()
        viewModelScope.launch { car.state.collect { onCar(it) } }
        // 자리 신호가 몇 초 바뀐 채로 있는지 보려면 신호가 없어도 가끔 다시 본다.
        viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                onCar(car.state.value)
            }
        }
        viewModelScope.launch { navi.routes.collect(::onRoute) }
        viewModelScope.launch { navi.tollgates.collect(::onTollgate) }
        viewModelScope.launch { navi.arrivals.collect { onArrived() } }
        viewModelScope.launch { voice.caption.collect { line -> state.update { it.copy(caption = line) } } }
        viewModelScope.launch { restriction.restricted.collect { r -> state.update { it.copy(restricted = r) } } }
        viewModelScope.launch {
            GleoBridge.actions.collect { call -> GleoResponder(getApplication()).reply(call.action, onGleo(call)) }
        }
        // F-C07 공시 평균 유가: 오늘 받은 값이 없으면 부른다. 실패하면 최근 값을 그대로 쓰고 30분 뒤 다시 본다.
        if (officialSource.enabled) {
            viewModelScope.launch {
                while (isActive) {
                    if (!fetchedToday()) refreshOfficial()
                    delay(OFFICIAL_CHECK_MS)
                }
            }
        }
        // 영수증에 타고 내린 곳을 적으려고 주행 중에는 가끔 주소를 묻는다.
        viewModelScope.launch {
            while (isActive) {
                if (tracker.ride != null) navi.refreshAddress()
                delay(ADDRESS_MS)
            }
        }
    }

    // ───── 차 신호 ─────

    private fun onCar(car: CarState) {
        if (car.moving && state.value.screen == Screen.CarpoolSummary()) resumeCarpoolDrive()
        val current = state.value
        val settings = current.settings
        val firstDrive = tracker.awaitingFirstDrive && car.gear == Gear.DRIVE
        val events = tracker.update(car, clock(), place(), current.ridePrices, settings.rule, power = settings.power)
        handleRideEvents(events, current.setup.mode, firstDrive)
        // 내비가 도착을 알리고 P에 두면 정산 카드를 띄운다. 동승자가 내리기 전에 QR을 찍을 수 있게.
        val arrived = arrivedAt?.let { clock() - it < ARRIVAL_WINDOW_MS } == true
        if (!tracker.awaitingFirstDrive && car.gear == Gear.PARK && arrived && tracker.aboardCount > 0) {
            arrivedAt = null
            showLiveCard(speak = true)
        }
        publish(car)
    }

    private fun handleRideEvents(events: List<RideEvent>, mode: TripMode, firstDrive: Boolean = false) {
        val startedNow = RideEvent.Started in events
        events.forEach { event ->
            when (event) {
                RideEvent.Started -> {
                    RideService.update(getApplication(), "함께 탄 길을 기록하고 있어요")
                    navi.refreshAddress()
                }
                is RideEvent.Boarded -> {
                    navi.refreshAddress()
                    val confirmed = startedNow && event.rider.name != null
                    // 좌석 센서를 쓰지 않으니 C-04는 사람이 [+ 탑승자]로 연다. 여기서는 이름만 짐작한다.
                    if (!confirmed && !((startedNow || firstDrive) && mode == TripMode.TRAVEL && namePlanned(event.rider))) guess(event.rider)
                }
                is RideEvent.Alighted -> navi.refreshAddress()
                is RideEvent.Finished -> saveFinished(event.ride)
                RideEvent.FinishedAlone -> RideService.update(getApplication(), "함께 타고 출발하면 탄 구간을 기록해요")
            }
            if (event is RideEvent.Finished || event == RideEvent.FinishedAlone) {
                state.update { it.copy(popups = PopupQueue(), setup = TripSetup()) }
            }
        }
    }

    /** 하이패스를 지났다. 금액 없이 한 건 남기고 C-06을 띄운다. */
    private fun onTollgate(name: String = "") {
        val index = tracker.tollgatePassed(clock(), name)
        if (index != null) pushPopup(Popup.Toll(index, name))
        publish()
    }

    private fun pushPopup(popup: Popup) = state.update { it.copy(popups = it.popups.push(popup)) }

    private fun onRoute(route: NaviRoute) {
        tracker.setRoute(route.name, route.tollWon)
        publish()
    }

    private fun onArrived() {
        arrivedAt = clock()
        onCar(car.state.value)
    }

    private fun place(): Place = Place(addressOverride ?: navi.address.value, locator.here.value)

    /** 출발 전에 정한 이름([TripSetup.names]) 중 아직 안 쓴 첫 이름을 붙인다. 남은 이름이 없으면 false. */
    private fun namePlanned(rider: Rider): Boolean {
        val used = tracker.ride?.riders?.mapNotNull { it.name }.orEmpty()
        val name = state.value.setup.names.firstOrNull { it !in used } ?: return false
        tracker.rename(rider.id, name, guessed = true)
        return true
    }

    private fun guess(rider: Rider) {
        val name = guessName(rider.seat, rider.boardedAt, rider.boardedPlace, state.value.rides, zone) ?: return
        tracker.rename(rider.id, name, guessed = true)
    }

    /**
     * 끝난 길을 장부에 넣는다. [summaryDone]은 K-04 "카풀 주행 종료!"로 끝냈을 때만 true.
     * 카풀은 어떻게 끝났든(K-04 종료, 오래 꺼 둔 앱을 켜서 끝냄 등) K-05에서 고르기 전까지 월별 누적에 넣지 않는다(#58).
     */
    private fun saveFinished(finished: Ride, summaryDone: Boolean = false) {
        val ride = finished.unconfirmedIfCarpool()
        val carpool = ride.mode == TripMode.CARPOOL
        if (state.value.screen == Screen.CarpoolSummary()) locator.start()
        val rides = state.value.rides + ride
        persistRides(rides)
        state.update {
            // 진행 중인 길로 띄워 둔 카드는 저장한 길로 바꿔 그대로 보여 준다.
            // K-04를 거치지 않고 끝났으면(옛 카드의 "장부에 넣기" 등) 저장한 길의 K-04를 보여 준다(남은 톨비를 여기서 넣는다).
            // 거기서 뒤로 가면 홈에서 K-05가 다시 뜬다([publish]).
            val screen = when (it.screen) {
                Screen.Card(null) -> if (carpool) Screen.CarpoolSummary(ride.id) else Screen.Settle(ride.id)
                Screen.TripSummary -> Screen.Settle(ride.id)
                Screen.DrivingStatus -> if (carpool) Screen.CarpoolSummary(ride.id) else Screen.Settle(ride.id)
                Screen.CarpoolSummary() -> if (summaryDone) {
                    Screen.CarpoolSettlementChoice(ride.id)
                } else {
                    Screen.CarpoolSummary(ride.id)
                }
                Screen.Home -> if (carpool) Screen.CarpoolSummary(ride.id) else it.screen
                else -> it.screen
            }
            it.copy(rides = rides, screen = screen)
        }
        refreshLedger()
        val owed = ride.settlement().riders.sumOf { it.finalWon.coerceAtLeast(0) }
        val text = if (owed > 0) "함께 탄 길을 장부에 적었어요 · 받을 돈 ${Lines.won(owed)}" else "함께 탄 길을 장부에 적었어요"
        RideService.update(getApplication(), text)
    }

    private fun publish(car: CarState = this.car.state.value) {
        val live = tracker.preview(car)
        state.update { s ->
            val pending = if (s.screen == Screen.Home && live == null && car.gear == Gear.PARK && !s.restricted) {
                s.rides.lastOrNull { it.awaitingSettlementChoice }
            } else null
            s.copy(car = car, live = live, screen = pending?.let { Screen.CarpoolSettlementChoice(it.id) } ?: s.screen)
        }
        saveLive()
    }

    /** F-C01 진행 중인 길을 저장한다. 타고 내리거나 사람이 고친 게 있으면 바로, 아니면 [LIVE_SAVE_MS]마다 주행거리만 새로. */
    private fun saveLive() {
        val now = clock()
        val snapshot = tracker.snapshot(now)
        if (snapshot == null) {
            if (savedLive != null) store.saveLive(null)
            savedLive = null
            return
        }
        if (snapshot.sameContent(savedLive) && now - savedLiveAt < LIVE_SAVE_MS) return
        store.saveLive(snapshot)
        savedLive = snapshot
        savedLiveAt = now
    }

    /** 앱이나 차가 다시 켜졌다. 저장해 둔 길을 이어 간다. 너무 오래된 것은 그때 끝난 것으로 장부에 넣는다. */
    private fun restoreLive() {
        val snapshot = store.loadLive() ?: return
        if (state.value.rides.any { it.id == snapshot.ride.id }) {
            store.saveLive(null)
            return
        }
        tracker.restore(snapshot)
        if (clock() - snapshot.savedAt <= RideTracker.RESTORE_LIMIT_MS) {
            savedLive = snapshot
            savedLiveAt = snapshot.savedAt
            state.update { it.copy(setup = snapshot.setup) }
            return
        }
        val event = tracker.finish(snapshot.savedAt, snapshot.lastKm?.let { CarState(odometerKm = it) }, snapshot.ride.endPlace)
        if (event is RideEvent.Finished) {
            val rides = state.value.rides + event.ride.unconfirmedIfCarpool()
            persistRides(rides)
            state.update { it.copy(rides = rides) }
        }
        store.saveLive(null)
    }

    private fun refreshLedger() {
        state.update { it.copy(summary = summarize(it.rides, clock(), zone), names = knownNames(it.rides)) }
    }

    /** 장부 저장. 너무 많으면 오래된 기록부터 버리되 아직 받을 돈이 남은 기록은 남긴다(settle/Retention.kt). */
    private fun persistRides(rides: List<Ride>) = store.saveRides(rides.forStorage(state.value.charges, clock()))

    private fun updateRides(transform: (List<Ride>) -> List<Ride>) {
        val rides = transform(state.value.rides)
        persistRides(rides)
        state.update { it.copy(rides = rides) }
        lowerMonthlyCharges()
        refreshLedger()
    }

    /** 주행을 고쳐 그 달 카풀 금액이 줄었으면 이미 만든 월 청구(C-19)도 줄인다(Charges.lowerMonthly). */
    private fun lowerMonthlyCharges() {
        val s = state.value
        // 같은 그룹·달은 한 번만 계산한다(구성원마다 청구가 하나씩 있다).
        val months = HashMap<String, GroupMonth?>()
        val charges = s.charges.lowerMonthly { charge ->
            // id: m:<그룹>:<달>:<이름>. 이름에 ':'가 있어도 되게 셋으로만 자른다.
            val (groupId, month, name) = charge.id.removePrefix("m:").split(":", limit = 3).takeIf { it.size == 3 } ?: return@lowerMonthly null
            val ym = runCatching { YearMonth.parse(month) }.getOrNull() ?: return@lowerMonthly null
            months.getOrPut("$groupId:$month") { s.groupMonth(groupId, ym) }?.let { m -> m.members.firstOrNull { it.name == name }?.won ?: 0 }
        }
        if (charges != s.charges) updateCharges(charges)
    }

    private fun updateRide(id: String, transform: (Ride) -> Ride) = updateRides { rides -> rides.map { if (it.id == id) transform(it) else it } }

    // ───── 화면에서 ─────

    /** "정산하기"와 Gleo "정산해 줘". 진행 중인 길이 있으면 그 길을, 없으면 마지막 길을 보여 주고 말한다. */
    fun settleNow(): GleoReply {
        if (tracker.ride != null) return showLiveCard(speak = true)
        val last = state.value.rides.maxByOrNull { it.startedAt }
            ?: return GleoReply(false, Lines.noRide()).also { voice.say(it.message) }
        openSettlement(last.id)
        bringToFront()
        val line = last.arrivalLine(opening = "지난 주행 정산이에요.")
        voice.say(line)
        return GleoReply(true, line)
    }

    private fun showLiveCard(speak: Boolean): GleoReply {
        val live = tracker.preview(car.state.value) ?: return GleoReply(false, Lines.noRide())
        // P에 막 넣은 순간에는 주행 제한(restricted)이 아직 안 풀렸을 수 있다. K-04는 제한이 풀릴 때까지
        // 스스로 "주차(P) 후에" 안내를 보이므로 여기서 제한을 따지면 옛 카드(Card)로 새어 나간다.
        val screen = when {
            live.mode == TripMode.TRAVEL -> Screen.TripSummary
            // 달리는 중에는 K-04가 "주차(P) 후에" 안내만 보인다. 옛 정산 카드(다른 계산)는 쓰지 않는다.
            else -> {
                if (car.state.value.gear == Gear.PARK) locator.stop()
                Screen.CarpoolSummary()
            }
        }
        state.update { it.copy(screen = screen, live = live) }
        bringToFront()
        val line = live.arrivalLine()
        if (speak) voice.say(line)
        return GleoReply(true, line)
    }

    /** 정산 카드의 "장부에 넣기". 아직 진행 중인 길을 지금 끝낸다. */
    fun finishNow() = finishCurrentRide()

    private fun finishCurrentRide(summaryDone: Boolean = false) {
        val event = tracker.finish(clock(), car.state.value, place())
        if (event is RideEvent.Finished) saveFinished(event.ride, summaryDone)
        if (event == RideEvent.FinishedAlone) goHome()
        if (event != null) state.update { it.copy(popups = PopupQueue(), setup = TripSetup()) }
        publish()
    }

    /** "이번엔 내가 쏠게". 카드에 보이는 길(없으면 진행 중이거나 마지막 길)을 쏜 것으로 적는다. 다시 누르면 되돌린다. */
    fun treat(on: Boolean = true): GleoReply {
        val screen = state.value.screen
        // 보고 있는 주행이 먼저다(C-08·C-12·C-17·C-18 등). 없으면 진행 중인 길, 그것도 없으면 마지막 길.
        val target = screenRideId(screen) ?: if (tracker.ride != null) null else state.value.rides.maxByOrNull { it.startedAt }?.id
        if (target == null && tracker.ride == null) return GleoReply(false, Lines.noRide()).also { voice.say(it.message) }
        if (target != null && billed(target)) {
            return GleoReply(false, "이미 청구서를 만든 주행이에요. 정산 화면에서 고친 뒤 다시 청구해 주세요.").also { voice.say(it.message) }
        }
        if (target == null) {
            tracker.treat(on)
            publish()
        } else {
            updateRide(target) { it.copy(treated = on) }
        }
        val line = if (on) Lines.treated() else "다시 나눠 내는 걸로 바꿨어요."
        voice.say(line)
        return GleoReply(true, line)
    }

    /** 주차비처럼 차가 모르는 비용. 진행 중인 길이면 지금 탄 사람끼리, 끝난 길이면 마지막 구간에 더한다. */
    /**
     * Gleo "주차비 3천 원 추가". C-05·C-10에서 넣은 것과 같은 비용 행([Cost])으로 넣어서 C-08에서 고치거나 지울 수 있다.
     * 진행 중인 길이면 지금 탄 사람끼리. 아니면 보고 있는 주행, 그것도 없으면 청구서를 아직 안 만든 최근 주행에 넣는다.
     */
    fun addExtra(extra: Extra, name: String? = null): GleoReply {
        if (extra.won <= 0) return GleoReply(false, "금액을 알려 주세요.")
        val label = name ?: extra.kind.label
        val line = if (tracker.ride != null) {
            addCost(null, label, extra.won)
            Lines.costAdded(label, extra.won, tracker.aboardCount, tracker.ride?.rule ?: state.value.settings.rule)
        } else {
            val s = state.value
            val target = screenRideId(s.screen)?.let { s.ride(it) }?.takeIf { it.finished && !billed(it.id) }
                ?: s.rides.unsettled(s.charges, clock()).firstOrNull()
                ?: return GleoReply(false, "비용을 더할 주행이 없어요. 정산 화면에서 추가해 주세요.").also { voice.say(it.message) }
            addCost(target.id, label, extra.won)
            Lines.costAdded(label, extra.won, 0)
        }
        voice.say(line)
        return GleoReply(true, line)
    }

    /** 지금 화면이 보여 주는 끝난 주행. */
    private fun screenRideId(screen: Screen): String? = when (screen) {
        is Screen.Card -> screen.rideId
        is Screen.Settle -> screen.rideId
        is Screen.GamePick -> screen.rideId
        is Screen.Game -> screen.rideId
        is Screen.FinalCheck -> screen.rideId
        is Screen.Invoices -> screen.rideId
        is Screen.CarpoolSummary -> screen.rideId
        is Screen.CarpoolSettlementChoice -> screen.rideId
        is Screen.CarpoolRideDetails -> screen.rideId
        else -> null
    }

    /** 이 주행으로 청구서를 만든 적이 있다. */
    private fun billed(rideId: String): Boolean = state.value.charges.any { it.id.startsWith(Charges.ridePrefix(rideId)) }

    /** 그 자리에서 QR로 받았다. 이 길의 몫을 모두 받은 것으로 적는다. */
    fun markPaid(rideId: String) = updateRide(rideId) { it.paidAll() }

    fun rename(rideId: String?, riderId: Int, name: String?) {
        val clean = name?.trim()?.take(MAX_NAME)?.ifBlank { null }
        if (rideId == null) {
            tracker.rename(riderId, clean)
            publish()
        } else {
            updateRide(rideId) { ride -> ride.copy(riders = ride.riders.map { if (it.id == riderId) it.copy(name = clean, nameGuessed = false) else it }) }
        }
    }

    fun openPerson(name: String) {
        state.update { it.copy(screen = Screen.Person(name)) }
    }

    /** 이 사람의 남은 몫을 모두 받았다. */
    fun settlePerson(name: String) {
        val owed = state.value.summary.people.firstOrNull { it.name == name }?.owedWon ?: 0
        updateRides { settle(it, name) }
        voice.say(Lines.settled(name, owed))
        goHome()
    }

    fun openSettings() = state.update { it.copy(screen = Screen.Settings) }

    fun saveSettings(settings: RideSettings) {
        store.saveSettings(settings)
        state.update { it.copy(settings = settings, screen = Screen.Home) }
    }

    fun goHome() {
        state.update { it.copy(screen = Screen.Home) }
        // 정산 방식을 아직 안 고른 카풀이 있으면 홈 대신 K-05를 바로 띄운다(차 신호를 기다리지 않고).
        publish()
    }

    fun openDrivingStatus() {
        state.update { it.copy(screen = Screen.DrivingStatus) }
    }

    /** 끝난 주행을 연다. 기획서 정산(C-08)으로 간다. */
    fun openRide(id: String) = openSettlement(id)

    // ───── 정산 흐름(C-08 → C-11 → C-12 → C-16 → C-17 → C-18) ─────

    /** T-03 "N빵하기!", K-05 "오늘 건 따로 결제"에서 부른다. C-08을 연다(C-11은 C-08이 처음 열릴 때 띄운다). */
    fun openSettlement(rideId: String) = state.update { s ->
        val screen = if (s.ride(rideId)?.awaitingSettlementChoice == true) {
            Screen.CarpoolSettlementChoice(rideId)
        } else {
            Screen.Settle(rideId)
        }
        s.copy(screen = screen)
    }

    fun openGamePick(rideId: String) = state.update { it.copy(screen = Screen.GamePick(rideId)) }

    fun startGame(rideId: String, plan: GamePlan) {
        if (!plan.ready) return
        state.update { it.copy(screen = Screen.Game(rideId, plan)) }
    }

    /** 게임 결과 확정. 정산에 넣고 C-17로 간다. */
    fun finishGame(rideId: String, plan: GamePlan, loser: Int) {
        val ride = state.value.ride(rideId) ?: return
        recordGame(rideId, plan.toRideGame(loser, ride.gamePot(plan)))
        openFinalCheck(rideId)
    }

    fun openFinalCheck(rideId: String) = state.update { it.copy(screen = Screen.FinalCheck(rideId)) }

    /** C-19 청구 현황. */
    fun openCharges(status: ChargeStatus? = null) = state.update { it.copy(screen = Screen.ChargeList(status)) }

    fun openCarpoolOverdue(chargeId: String, back: Screen.CarpoolMonthly) =
        state.update { it.copy(screen = Screen.ChargeList(ChargeStatus.OVERDUE, chargeId, back)) }

    /** C-19 → C-18 해당 청구서. */
    fun openInvoice(rideId: String, personId: Int, back: Screen.ChargeList = Screen.ChargeList()) =
        state.update { it.copy(screen = Screen.Invoices(rideId, personId, chargesBack = back)) }

    /** C-19로 돌아간다. 떠날 때의 필터·선택을 그대로. */
    fun openChargeList(screen: Screen.ChargeList) = state.update { it.copy(screen = screen) }

    /** C-17 수정하기. 게임과 절약 기여 결과를 지우고 C-08로 돌아간다(C-11을 다시 묻는다). */
    fun editSettlement(rideId: String) {
        updateRide(rideId) { it.copy(game = null, savingAccepted = null) }
        openSettlement(rideId)
    }

    /** C-17 청구서 생성. 탑승자별 청구를 '청구됨'으로 남기고 C-18로 간다. */
    fun makeInvoices(rideId: String) {
        if (state.value.settlement(rideId)?.readyToBill != true) return
        // 월별 정산 카풀 주행은 K-07에서 달마다 한 번에 청구한다(따로 만들면 두 번 청구).
        if (state.value.ride(rideId)?.let { it.mode == TripMode.CARPOOL && it.monthly } == true) return
        billRide(rideId)
        state.update { it.copy(screen = Screen.Invoices(rideId)) }
    }

    /** 탑승 센서가 없는 차: 이 자리에 사람이 탔다고(또는 내렸다고) 알린다. */
    fun setManualSeat(seat: Seat, aboard: Boolean) {
        tracker.setManual(seat, aboard)
        onCar(car.state.value)
    }

    fun onPermissionsGranted() {
        if (state.value.screen != Screen.CarpoolSummary()) locator.start()
    }

    // ───── 기획서 화면(C-03~C-12, K-03, T-01). rideId가 null이면 진행 중인 길이다. ─────

    /** C-01 "여행 시작" → T-01. */
    fun openTripStart() {
        if (carpoolInProgress()) return
        state.update { it.copy(screen = Screen.TripStart) }
    }

    /** 진행 중인 카풀이 있으면 여행 설정(T-01)으로 바꾸지 않는다. 바꾸면 카풀이 여행이 돼 월별 정산에서 빠진다. */
    private fun carpoolInProgress(): Boolean = tracker.ride?.mode == TripMode.CARPOOL

    /**
     * T-01 "주행 시작": 여행을 열고 첫 D부터 거리를 기록한다.
     * T-01에서 고른 사람은 출발할 때부터 탑승자로 등록한다.
     */
    fun startTravel(names: List<String>): Boolean {
        val s = state.value
        val car = car.state.value
        if (s.screen != Screen.TripStart || car.gear != Gear.PARK || s.restricted ||
            names.size !in 1..Seat.passengers.size || carpoolInProgress() || needsAccount()
        ) return false
        val setup = TripSetup(mode = TripMode.TRAVEL, names = names)
        if (tracker.ride != null && !tracker.discardUnshared(car)) return false
        val events = tracker.startTravel(setup, car, clock(), place(), s.ridePrices, s.settings.rule, s.settings.power)
        if (events.isEmpty()) return false
        arrivedAt = null
        // 버린 길의 팝업(C-04 탑승, C-06 톨비)은 그 길의 탑승자·톨게이트 번호를 가리킨다. 새 길은 번호를 1부터 다시 쓰니 지운다.
        state.update { it.copy(setup = setup, screen = Screen.Home, popups = PopupQueue()) }
        handleRideEvents(events, setup.mode)
        publish(car)
        return true
    }

    /** 기획서 F-C03: 정산 계좌가 없으면 출발 준비를 하지 않고 설정(C-02)으로 보낸다. 화면(T-01·K-03)도 미리 막는다. */
    private fun needsAccount(): Boolean {
        if (state.value.settings.payee.account.isNotBlank()) return false
        openSettings()
        return true
    }

    /** C-03 "주행 종료"(주차 상태)에서 여행은 T-03, 카풀은 K-04 주행 요약을 연다. */
    fun endDrive() {
        val live = tracker.ride ?: return
        if (live.mode == TripMode.CARPOOL && tracker.awaitingFirstDrive && live.costs.isEmpty()) {
            finishNow()
            return
        }
        showLiveCard(speak = false)
    }

    fun resumeCarpoolDrive() {
        if (state.value.screen != Screen.CarpoolSummary()) return
        goHome()
        locator.start()
    }

    fun finishCarpoolSummary(rideId: String?) {
        val s = state.value
        val ride = s.ride(rideId) ?: return
        if (s.screen != Screen.CarpoolSummary(rideId) || s.car.gear != Gear.PARK || s.restricted ||
            ride.tollPasses.any { it.won == null }
        ) return
        if (rideId == null) {
            finishCurrentRide(summaryDone = true)
            if (state.value.rides.none { it.id == ride.id }) locator.start()
        } else {
            updateRide(rideId) { it.copy(monthly = false, awaitingSettlementChoice = true) }
            state.update { it.copy(screen = Screen.CarpoolSettlementChoice(ride.id)) }
        }
    }

    fun chooseCarpoolSettlement(rideId: String, monthly: Boolean) {
        val s = state.value
        val ride = s.ride(rideId) ?: return
        if (s.screen != Screen.CarpoolSettlementChoice(rideId) || s.car.gear != Gear.PARK || s.restricted) return
        val group = s.group(ride.groupId)
        if (!ride.awaitingSettlementChoice || (monthly && group == null)) return
        setMonthly(rideId, monthly)
        if (monthly && group != null) openCarpoolMonthly(group.id, group.monthOf(ride.startedAt, s.zone)) else openRide(rideId)
    }

    fun openCarpoolMonthly(groupId: String? = null, month: YearMonth? = null) =
        state.update { it.copy(screen = Screen.CarpoolMonthly(groupId, month)) }

    fun openCarpoolRideDetails(rideId: String) =
        state.update { it.copy(screen = Screen.CarpoolRideDetails(rideId, it.screen)) }

    fun closeCarpoolRideDetails(screen: Screen.CarpoolRideDetails) =
        state.update { if (it.screen == screen) it.copy(screen = screen.back) else it }

    fun openCarpoolMonthlyBilling(groupId: String, month: YearMonth, selectedName: String? = null) =
        state.update { it.copy(screen = Screen.CarpoolMonthlyBilling(groupId, month, selectedName)) }

    /** 떠 있는 팝업을 닫는다. 다음 팝업이 있으면 그게 뜬다. */
    fun dismissPopup() = state.update { it.copy(popups = it.popups.pop()) }

    /** C-06·C-09 톨비. [won]이 null이면 나중에 입력(미입력으로 둔다), 0이면 통행료 없음. */
    fun enterToll(rideId: String?, index: Int, won: Int?) {
        editRide(rideId) { r -> r.copy(tollPasses = r.tollPasses.mapIndexed { i, t -> if (i == index) t.copy(won = won?.coerceAtLeast(0)) else t }) }
        if (rideId == null) state.update { s -> s.copy(popups = s.popups.without { it is Popup.Toll && it.index == index }) }
    }

    /** C-07·C-10 추가 비용. [sharedBy]를 비우면 지금 탄 사람 전원(운전자 포함), 끝난 길이면 그 길에 탄 사람 전원이 나눈다. */
    fun addCost(
        rideId: String?,
        name: String,
        won: Int,
        sharedBy: Set<Int> = emptySet(),
        source: ExpenseSource = ExpenseSource.DRIVING,
        paidBy: Int = DRIVER_ID,
    ) {
        if (won <= 0) return
        val ride = state.value.ride(rideId) ?: return
        val everyone = if (rideId == null) tracker.aboardNow else ride.riders.map { it.id }.toSet() + DRIVER_ID
        val cost = Cost(name.trim().ifBlank { "기타" }.take(MAX_COST_NAME), won, sharedBy.ifEmpty { everyone }, source, paidBy, clock())
        editRide(rideId) { it.copy(costs = it.costs + cost) }
    }

    /** C-08 비용 행 고치기. [cost]가 null이면 지운다. */
    fun updateCost(rideId: String?, index: Int, cost: Cost?) = editRide(rideId) { r ->
        if (cost == null) r.removeCost(index) else r.copy(costs = r.costs.mapIndexed { i, c -> if (i == index) cost else c })
    }

    /** F-C16 단가 수정(C-08에서만). null이면 공시 평균으로 되돌린다. */
    fun setUnitWon(rideId: String?, won: Int?) = editRide(rideId) { it.copy(unitWonOverride = won?.takeIf { w -> w > 0 }) }

    /** F-C17 opt in/out. 운전자는 [DRIVER_ID]. */
    fun setOptedIn(rideId: String?, personId: Int, on: Boolean) = editRide(rideId) { r ->
        r.copy(optedOut = if (on) r.optedOut - personId else r.optedOut + personId)
    }

    /** C-11 절약 기여 인정 / 인정 안 함. null이면 다시 묻는다(C-08 절약 카드에서 다시 정하기). */
    fun decideSaving(rideId: String?, accepted: Boolean?) = editRide(rideId) { it.copy(savingAccepted = accepted) }

    /** C-12~C-16 게임 결과. null이면 게임 없이 정산한다. */
    fun recordGame(rideId: String?, game: RideGame?) = editRide(rideId) { it.copy(game = game) }

    /** C-04 재탑승: 새로 탄 [riderId]가 전에 탔던 [sameAs]와 같은 사람이다. null이면 새 사람으로 되돌린다. */
    fun markReboard(riderId: Int, sameAs: Int?) = editRide(null) { r ->
        val name = sameAs?.let { id -> r.riders.firstOrNull { it.id == id }?.name }
        r.copy(riders = r.riders.map { if (it.id == riderId) it.copy(sameAs = sameAs, name = name ?: it.name, nameGuessed = false) else it })
    }

    /**
     * C-04 확인. [boarders]는 "누가 탔나요?"에서 고른 사람들이다(스테퍼 수만큼).
     * 센서가 먼저 태운 사람(떠 있는 [Popup.Boarding])이 있으면 그 자리에 이름·재탑승을 붙이고(붙이는 순서는 아래),
     * 모자라면 빈 자리에 지금 바로 태운다. 그래서 센서 감지와 수동 추가가 겹쳐도 두 번 태우지 않는다.
     * 센서보다 적게 고르면 남는 센서 탑승은 짐으로 보고 지운다(이름 없는 사람이 남아 돈을 내지 않게). 0명이면 모두 짐이다.
     * 문제가 있으면(자리 부족, 같은 사람 두 번 등) 화면에 보여 줄 문장을 돌려주고 아무것도 바꾸지 않는다. 잘 되면 null.
     */
    fun confirmBoarding(boarders: List<Boarder>): String? {
        val live = tracker.ride ?: return "진행 중인 주행이 없어요."
        val sensed = state.value.popups.items.filterIsInstance<Popup.Boarding>().flatMap { it.riderIds }.distinct()
            .filter { id -> live.riders.any { it.id == id && it.aboard } }
        live.boardingProblem(boarders, sensed.size)?.let { return it }
        val (slots, luggage) = live.seatBoarders(boarders, sensed)
        tracker.discard(luggage)
        val added = tracker.boardNow(slots.count { it == null }, clock(), place()).map { it.id }.iterator()
        slots.map { it ?: added.next() }.zip(boarders).forEach { (id, boarder) ->
            when (boarder) {
                is Boarder.Returning -> tracker.edit { r ->
                    val name = r.riders.firstOrNull { it.id == boarder.riderId }?.name
                    r.copy(riders = r.riders.map { if (it.id == id) it.copy(sameAs = boarder.riderId, name = name, nameGuessed = false) else it })
                }
                is Boarder.Newcomer -> {
                    val name = boarder.name?.trim()?.take(MAX_NAME)?.ifBlank { null }
                    // 이름을 안 받았으면 이미 붙은 이름(T-01에서 정한 사람, 장부로 짐작한 이름)을 그대로 둔다.
                    val known = tracker.ride?.riders?.firstOrNull { it.id == id }?.name
                    if (name != null || known == null) tracker.rename(id, name ?: tracker.ride?.placeholderName(id))
                }
            }
        }
        state.update { s -> s.copy(popups = s.popups.without { it is Popup.Boarding }) }
        publish()
        return null
    }

    /** C-04 수동 추가만(이름 없이). 빈 자리에 [count]명을 지금 바로 태운다. 주행 전이면 출발할 때 태운다. */
    fun addRiders(count: Int) {
        if (tracker.ride == null) {
            Seat.passengers.take(count).forEach { tracker.setManual(it, true) }
            onCar(car.state.value)
        } else {
            tracker.boardNow(count, clock(), place())
            publish()
        }
    }

    /** C-05 하차 처리. 지금 주행거리까지로 그 사람의 금액이 정해지고, 이후 비용에서 빠진다. */
    fun alight(riderId: Int) {
        tracker.alightNow(riderId, clock(), place()) ?: return
        navi.refreshAddress()
        publish()
    }

    // ───── 카풀(K-01~K-07)과 청구 현황(C-19) ─────

    fun openCarpoolGroups(selectedId: String? = null) {
        state.update { s ->
            val group = s.group(selectedId) ?: s.groups.firstOrNull()
            val screen = if (group == null) Screen.CarpoolGroupEdit(null, null) else Screen.CarpoolGroups(group.id)
            if (s.canSetUpCarpool) s.copy(screen = screen) else s
        }
    }

    fun editCarpoolGroup(groupId: String?, previousSelection: String?) {
        state.update { s ->
            if (s.canSetUpCarpool) s.copy(screen = Screen.CarpoolGroupEdit(groupId, previousSelection)) else s
        }
    }

    fun closeCarpoolGroupEditor(previousSelection: String?) {
        state.update { s ->
            val selection = s.group(previousSelection) ?: s.groups.firstOrNull()
            s.copy(screen = if (s.canSetUpCarpool) Screen.CarpoolGroups(selection?.id) else Screen.Home)
        }
    }

    fun saveCarpoolGroupAndSelect(group: CarpoolGroup): String? {
        if (!state.value.canSetUpCarpool) return "주차한 상태에서 그룹을 저장해 주세요."
        saveGroup(group)?.let { return it }
        val saved = state.value.groups.first { it.name == group.name.trim() }
        openCarpoolGroups(saved.id)
        return null
    }

    fun openCarpoolPreparation(groupId: String) {
        state.update { s ->
            if (s.canSetUpCarpool && s.group(groupId) != null) s.copy(screen = Screen.CarpoolPrepare(groupId)) else s
        }
    }

    fun confirmCarpoolPreparation(groupId: String, today: List<String>, seats: Map<String, Seat> = emptyMap()): Boolean {
        val s = state.value
        val group = s.group(groupId) ?: return false
        // K-03에서 누른 순서 그대로. 자리([seats])를 골랐으면 그 자리에, 아니면 첫 D에서 감지된 자리에 이 순서로 이름이 붙는다.
        val selected = today.distinct().filter { it in group.members }
        if (s.screen != Screen.CarpoolPrepare(groupId) || !s.canSetUpCarpool ||
            selected.size !in 1..Seat.passengers.size
        ) return false
        if (needsAccount() || !s.hasCarpoolVehicleSettings) return false
        val now = clock()
        // 고른 사람마다 다른 자리가 하나씩 있어야 쓴다([TripSetup.seatPlan]). 아니면 예전처럼 순서대로.
        val setup = TripSetup(mode = TripMode.CARPOOL, groupId = groupId, names = selected, seats = seats.filterKeys { it in selected })
            .let { if (it.seatPlan == null) it.copy(seats = emptyMap()) else it }
        val car = car.state.value
        if (tracker.ride != null && !tracker.discardUnshared(car)) return false
        val events = tracker.startCarpool(setup, car, now, place(), s.ridePrices, s.settings.rule, s.settings.power)
        if (events.isEmpty()) return false
        updateGroups(listOf(group.copy(lastUsedAt = now)) + s.groups.filterNot { it.id == groupId })
        arrivedAt = null
        // startTravel과 같이, 버린 길의 팝업을 지운다.
        state.update { it.copy(setup = setup, screen = Screen.Home, popups = PopupQueue()) }
        handleRideEvents(events, setup.mode)
        publish(car)
        locator.start()
        return true
    }

    /** K-02 저장. 문제가 있으면 화면에 보여 줄 문장을 돌려주고 저장하지 않는다. 새 그룹은 id를 비워서 넘긴다. */
    fun saveGroup(group: CarpoolGroup): String? {
        val clean = group.copy(
            id = group.id.ifBlank { "g${clock()}" },
            name = group.name.trim(),
            // 이름은 C-04·T-01과 같은 길이까지. 길이가 다르면 같은 사람이 월별 정산에서 둘로 갈린다.
            members = group.members.map { it.trim().take(MAX_NAME) }.filter { it.isNotEmpty() },
            lastUsedAt = clock(),
        )
        val groups = state.value.groups
        clean.problem(groups)?.let { return it }
        updateGroups(listOf(clean) + groups.filterNot { it.id == clean.id })
        return null
    }

    /** K-02 그룹 삭제. 지난 주행 기록과 청구는 그대로 둔다. */
    fun deleteGroup(id: String) = updateGroups(state.value.groups.filterNot { it.id == id })

    /**
     * K-05 월별 정산에 추가(true) / 오늘 건 따로 결제(false). 월별이면 지금 정산일로 들어갈 달을 정해 둔다.
     * 그래야 나중에 정산일을 바꿔도 이미 청구한 주행이 다른 달로 옮겨 가 또 청구되지 않는다.
     */
    fun setMonthly(rideId: String?, monthly: Boolean) {
        val s = state.value
        editRide(rideId) { ride ->
            val month = if (monthly) s.group(ride.groupId)?.monthOf(ride.startedAt, s.zone) else null
            ride.copy(monthly = monthly, awaitingSettlementChoice = false, billingMonth = month)
        }
    }

    /** K-07 청구를 장부에 남기고 C-18에서 생성한 월 청구서를 연다. */
    fun makeMonthlyInvoices(billing: Screen.CarpoolMonthlyBilling, names: List<String>) {
        val s = state.value
        if (s.screen != billing || s.car.gear != Gear.PARK || s.restricted || s.settings.payee.isEmpty) return
        val m = s.groupMonth(billing.groupId, billing.month) ?: return
        // 톨비를 안 넣은 주행이 있으면 그 금액이 빠진 채 청구된다(C-17과 같게 막는다).
        if (m.pendingTolls > 0) return
        val targets = m.members.filter { it.name in names && it.won > 0 }
        if (targets.isEmpty()) return
        val now = clock()
        updateCharges(targets.fold(s.charges) { acc, member -> acc.upsert(m.charge(member, now)) })
        state.update { it.copy(screen = Screen.MonthlyInvoices(billing, targets.map { member -> member.name })) }
    }

    /** C-18 여행·오늘 건 청구서를 만들 때 '청구됨'으로 남긴다. */
    fun billRide(rideId: String) {
        val ride = state.value.ride(rideId) ?: return
        val made = ride.charges(ride.settlement(), clock(), zone)
        updateCharges(state.value.charges.rebillRide(rideId, made))
    }

    /** C-19 납부 확인 처리(또는 되돌리기). 주행 청구면 그 주행의 장부에도 받은 것으로 적는다. */
    fun setChargePaid(chargeId: String, paid: Boolean) {
        val charge = state.value.charges.firstOrNull { it.id == chargeId } ?: return
        updateCharges(state.value.charges.map { if (it.id == chargeId) it.markPaid(paid, clock()) else it })
        val parts = chargeId.split(":")
        if (!charge.monthly && parts.size == 3) {
            val riderId = parts[2].toIntOrNull() ?: return
            val ids = state.value.ride(parts[1])?.riders?.filter { rideRoot(parts[1], it.id) == riderId }?.map { it.id }.orEmpty()
            updateRide(parts[1]) { r -> r.copy(paidRiderIds = if (paid) r.paidRiderIds + ids else r.paidRiderIds - ids.toSet()) }
        }
    }

    /** C-18 "음성으로 안내". 차에서 휴대폰으로 문자를 못 보내니(SDK에 없음) 차 스피커로 금액과 QR을 알려 준다. */
    fun announceInvoice(name: String, won: Int) {
        if (won > 0) voice.say(Lines.invoice(name, won))
    }

    /** C-19 "음성 리마인드". 남은 금액을 말하고, 몇 번 했는지 청구에 남긴다. */
    fun remindCharge(chargeId: String) {
        val now = clock()
        val charge = state.value.charges.firstOrNull { it.id == chargeId }?.takeIf { it.dueWon > 0 } ?: return
        voice.say(Lines.remind(charge.name, charge.label.substringBefore(" · "), charge.dueWon, charge.overdueDays(now, zone)))
        updateCharges(state.value.charges.map { if (it.id == chargeId) it.reminded(now) else it })
    }

    private fun rideRoot(rideId: String, id: Int): Int = state.value.ride(rideId)?.personId(id)?.toInt() ?: id

    private fun updateGroups(groups: List<CarpoolGroup>) {
        store.saveGroups(groups)
        state.update { it.copy(groups = groups) }
    }

    private fun updateCharges(charges: List<Charge>) {
        store.saveCharges(charges.forStorage())
        state.update { it.copy(charges = charges) }
    }

    private fun editRide(rideId: String?, transform: (Ride) -> Ride) {
        if (rideId == null) {
            tracker.edit(transform)
            publish()
        } else {
            updateRide(rideId, transform)
        }
    }

    // ───── Gleo ─────

    internal fun onGleo(call: GleoCall): GleoReply = when (call.action) {
        GleoAction.SETTLE_RIDE -> {
            val person = call.params["person"]?.trim()?.ifBlank { null }
            if (person == null) {
                settleNow()
            } else {
                personBalance(person)
            }
        }
        GleoAction.TREAT_RIDE -> treat()
        GleoAction.ADD_RIDE_COST -> {
            val won = call.params["amount"]?.filter(Char::isDigit)?.toIntOrNull() ?: 0
            val kind = ExtraKind.entries.firstOrNull { it.name == call.params["kind"] }
            when {
                costVoiceRideId != null && costVoiceRideId == tracker.ride?.id -> {
                    costVoiceRideId = null
                    val name = call.params["name"]?.trim()?.take(MAX_COST_NAME)?.ifBlank { null } ?: kind?.label ?: "기타"
                    val draft = CostVoiceDraft(name, won.takeIf { it > 0 })
                    if (costVoiceDrafts.subscriptionCount.value > 0 && costVoiceDrafts.tryEmit(draft)) {
                        GleoReply(true, "비용 입력 화면에 ${name}을(를) 넣었어요. 추가 버튼으로 저장해 주세요.")
                    } else {
                        GleoReply(false, "비용 추가 화면을 다시 열어 주세요.")
                    }
                }
                else -> addExtra(
                    Extra(kind ?: if ("name" in call.params) ExtraKind.OTHER else ExtraKind.PARKING, won),
                    call.params["name"]?.trim()?.take(MAX_COST_NAME)?.ifBlank { null },
                )
            }
        }
    }

    // ───── 시연 도구(디버그 빌드의 DemoReceiver) ─────

    /**
     * Gleo "민지 정산해 줘". 정산 화면과 같은 계산으로 말한다: 아직 안 받은 청구(C-19) + 이번 달 아직 청구 안 한 카풀 몫(K-06).
     * 미납이 있으면 C-19에서 그 청구를, 아니면 그 카풀 그룹의 K-06을 연다.
     */
    private fun personBalance(person: String): GleoReply {
        val s = state.value
        val now = clock()
        fun matches(name: String) = name == person || person.startsWith(name)
        val unpaid = s.charges.unpaid(now).filter { matches(it.name) }.sortedByDescending { it.billedAt }
        val month = s.groups.firstNotNullOfOrNull { group ->
            val member = group.members.firstOrNull(::matches) ?: return@firstNotNullOfOrNull null
            val month = group.monthOf(now, s.zone)
            s.groupMonth(group.id, month)?.members?.firstOrNull { it.name == member }
                ?.takeIf { it.charge == null && it.won > 0 }
                ?.let { Triple(group, month, it) }
        }
        val name = unpaid.firstOrNull()?.name ?: month?.third?.name
            ?: return GleoReply(true, Lines.noBalance(person)).also { voice.say(it.message) }
        if (unpaid.isNotEmpty()) {
            state.update { it.copy(screen = Screen.ChargeList(selectedId = unpaid.first().id)) }
        } else if (month != null) {
            openCarpoolMonthly(month.first.id, month.second)
        }
        bringToFront()
        val line = Lines.owed(name, unpaid.sumOf { it.dueWon }, unpaid.size, month?.third?.won ?: 0)
        voice.say(line)
        return GleoReply(true, line)
    }

    internal fun debugRoute(name: String?, tollWon: Int) = onRoute(NaviRoute(name, tollWon))

    internal fun debugTollgate(name: String = "") {
        if (navi is PleosNavi) navi.debugTollgate(name) else onTollgate(name)
    }

    internal fun debugArrive() = onArrived()

    /**
     * 시연 도구: 앱 시계를 [ms]만큼 앞으로 돌린다(디버그 빌드만). 가상 콘솔은 주행거리를 한 번에 올리므로,
     * 그만큼 달리는 데 걸렸을 시간을 먼저 흘려야 도심·고속(주행거리 ÷ 걸린 시간)이 실제처럼 나뉜다.
     */
    internal fun debugElapse(ms: Long) {
        val before = clock
        clock = { before() + ms }
    }

    /** 시연 도구: 에뮬레이터에 없는 차 신호(평균 연비)를 넣는다(디버그 빌드만). */
    internal fun debugCar(transform: (CarState) -> CarState) {
        val source = car
        if (source is PleosCar) source.debugUpdate(transform) else manualCar.update(transform)
    }

    // ───── 공시 평균 유가(F-C07) ─────

    private fun fetchedToday(): Boolean {
        val at = store.officialFetchedAt
        return at > 0 && Instant.ofEpochMilli(at).atZone(zone).toLocalDate() == Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
    }

    /** 오피넷을 부른다. 받은 값만 저장하고, 실패하면 이전 값을 그대로 둔다. */
    suspend fun refreshOfficial(): Boolean {
        val fresh = officialSource.fetch() ?: return false
        store.saveOfficial(fresh)
        store.officialFetchedAt = clock()
        state.update { it.copy(official = fresh) }
        return true
    }

    /** 시연 도구: 키 없이 화면을 보려고 공시 평균 값을 넣는다(디버그 빌드만). 받은 시각은 바꾸지 않는다. */
    internal fun debugOfficial(prices: List<OfficialPrice>) {
        store.saveOfficial(prices)
        state.update { it.copy(official = prices) }
    }

    internal fun debugAddress(address: String?) {
        addressOverride = address
    }

    internal fun debugReplaceRides(rides: List<Ride>, groups: List<CarpoolGroup> = emptyList(), charges: List<Charge> = emptyList()) {
        store.saveRides(rides)
        updateGroups(groups)
        updateCharges(charges)
        state.update { it.copy(rides = rides, screen = Screen.Home) }
        refreshLedger()
    }

    override fun onCleared() {
        car.stop()
        navi.stop()
        voice.stop()
        restriction.stop()
        locator.stop()
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val ADDRESS_MS = 20_000L
        const val OFFICIAL_CHECK_MS = 30 * 60_000L
        const val ARRIVAL_WINDOW_MS = 10 * 60_000L
        const val MAX_NAME = 12
        const val MAX_COST_NAME = 20
        const val LIVE_SAVE_MS = 30_000L
    }
}
