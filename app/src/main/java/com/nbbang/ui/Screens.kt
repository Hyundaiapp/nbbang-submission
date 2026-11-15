package com.nbbang.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nbbang.ui.home.HomeScreen
import com.nbbang.ui.driving.DrivingStatusScreen
import com.nbbang.ui.carpool.CarpoolGroupsScreen
import com.nbbang.ui.carpool.CarpoolGroupEditScreen
import com.nbbang.ui.carpool.CarpoolPrepareScreen
import com.nbbang.ui.carpool.CarpoolSettlementChoiceScreen
import com.nbbang.ui.carpool.CarpoolSummaryScreen
import com.nbbang.ui.carpool.CarpoolMonthlyScreen
import com.nbbang.ui.carpool.CarpoolMonthlyBillingScreen
import com.nbbang.ui.settle.GameScreen
import com.nbbang.ui.settle.ChargesScreen
import com.nbbang.ui.settle.FinalCheckScreen
import com.nbbang.ui.settle.GamePickScreen
import com.nbbang.ui.settle.InvoiceScreen
import com.nbbang.ui.settle.MonthlyInvoiceScreen
import com.nbbang.ui.settle.SettleScreen
import com.nbbang.ui.trip.TripStartScreen
import com.nbbang.ui.trip.TripSummaryScreen
import com.nbbang.ui.settings.SettingsScreen
import com.nbbang.RideViewModel
import com.nbbang.Screen
import com.nbbang.UiState
import com.nbbang.domain.Bill
import com.nbbang.domain.Extra
import com.nbbang.domain.ExtraKind
import com.nbbang.domain.Lines
import com.nbbang.domain.PayLink
import com.nbbang.domain.Payee
import com.nbbang.domain.Prices
import com.nbbang.domain.Ride
import com.nbbang.domain.RideSettings
import com.nbbang.domain.RiderShare
import com.nbbang.domain.Seat
import com.nbbang.domain.SplitRule
import com.nbbang.domain.bill
import com.nbbang.domain.unpaidFor
import com.nbbang.platform.Qr

@Composable
fun RideApp(state: UiState, vm: RideViewModel, allowDrivingHome: Boolean = false) {
    if (state.screen == Screen.Settings) {
        SettingsScreen(state, vm)
        return
    }
    if (state.screen == Screen.Home) {
        Box(Modifier.fillMaxSize()) {
            HomeScreen(state, vm, allowDrivingHome = allowDrivingHome)
            if (state.live != null) {
                val colors = Look.colors
                Box(
                    Modifier.align(Alignment.BottomEnd)
                        .padding(end = 40.dp, bottom = 32.dp)
                        .size(176.dp)
                        .drawBehind {
                            // 플랫폼 그림자 대신 파란 그라데이션을 직접 그려 색과 번짐을 유지한다.
                            val shadowCenter = center
                            val shadowRadius = size.minDimension / 2f + 22.dp.toPx()
                            drawCircle(
                                brush = Brush.radialGradient(
                                    0f to colors.accent.copy(alpha = 0.55f),
                                    0.72f to colors.accent.copy(alpha = 0.45f),
                                    0.86f to colors.accent.copy(alpha = 0.22f),
                                    1f to colors.accent.copy(alpha = 0f),
                                    center = shadowCenter,
                                    radius = shadowRadius,
                                ),
                                radius = shadowRadius,
                                center = shadowCenter,
                            )
                        }
                        .clip(Look.shapes.pill)
                        .background(colors.accent)
                        .border(3.dp, colors.onAccent.copy(alpha = 0.3f), Look.shapes.pill)
                        .semantics { contentDescription = "주행 현황 전체보기" }
                        .clickable(role = Role.Button, onClick = vm::openDrivingStatus),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "주행중",
                        style = Look.type.button,
                        color = colors.onAccent,
                    )
                }
            }
        }
        return
    }
    // 정산 흐름(C-08 → C-12 → C-16 → C-17 → C-18)은 각자 전체 화면을 그린다.
    when (val screen = state.screen) {
        is Screen.CarpoolGroups -> return CarpoolGroupsScreen(state, vm, screen)
        is Screen.CarpoolGroupEdit -> return CarpoolGroupEditScreen(state, vm, screen)
        is Screen.CarpoolPrepare -> return CarpoolPrepareScreen(state, vm, screen)
        is Screen.CarpoolSummary -> return CarpoolSummaryScreen(state, vm, screen.rideId)
        is Screen.CarpoolSettlementChoice -> return CarpoolSettlementChoiceScreen(state, vm, screen.rideId)
        is Screen.CarpoolMonthly -> return CarpoolMonthlyScreen(state, vm, screen)
        is Screen.CarpoolRideDetails -> return CarpoolSummaryScreen(state, vm, screen.rideId) { vm.closeCarpoolRideDetails(screen) }
        is Screen.CarpoolMonthlyBilling -> return CarpoolMonthlyBillingScreen(state, vm, screen)
        is Screen.Settle -> return SettleScreen(state, vm, screen.rideId)
        is Screen.GamePick -> return GamePickScreen(state, vm, screen.rideId)
        is Screen.Game -> return GameScreen(state, vm, screen.rideId, screen.plan)
        is Screen.FinalCheck -> return FinalCheckScreen(state, vm, screen.rideId)
        is Screen.Invoices -> return InvoiceScreen(state, vm, screen.rideId, screen.personId, screen.chargesBack)
        is Screen.MonthlyInvoices -> return MonthlyInvoiceScreen(state, vm, screen)
        is Screen.ChargeList -> return ChargesScreen(state, vm, screen)
        Screen.TripStart -> return TripStartScreen(state, vm)
        Screen.TripSummary -> return TripSummaryScreen(state, vm)
        Screen.DrivingStatus -> return DrivingStatusScreen(state, vm)
        else -> Unit
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Look.colors.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            TopBar(state, vm)
            Box(Modifier.weight(1f)) {
                when (val screen = state.screen) {
                    Screen.Home -> HomeScreen(state, vm)
                    is Screen.Card -> {
                        val ride = state.ride(screen.rideId)
                        if (ride == null) HomeScreen(state, vm) else CardScreen(state, ride, live = screen.rideId == null, vm)
                    }

                    is Screen.Person -> PersonScreen(state, screen.name, vm)
                    Screen.Settings -> SettingsScreen(state, vm)
                    else -> HomeScreen(state, vm)
                }
            }
        }
    }
}

@Composable
private fun TopBar(state: UiState, vm: RideViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column {
            Text("N빵~", style = Look.type.title, color = Look.colors.ink)
            Text("함께 탄 만큼만, 차가 계산해요", style = Look.type.caption, color = Look.colors.muted)
        }
        Spacer(Modifier.weight(1f))
        state.caption?.let { line ->
            Box(
                Modifier
                    .width(760.dp)
                    .clip(Look.shapes.compact)
                    .background(Look.colors.raised)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text("🔈 $line", style = Look.type.caption, color = Look.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (state.screen != Screen.Home) QuietButton("장부", onClick = vm::goHome)
        if (!state.restricted) QuietButton("설정", onClick = vm::openSettings)
    }
}

// ───── 첫 화면: 지금 차 안 + 장부 ─────

@Composable
private fun NowInCar(state: UiState, vm: RideViewModel) {
    val colors = Look.colors
    Text("지금 차 안", style = Look.type.heading, color = colors.ink)
    SeatMap(state, vm)
    val live = state.live
    if (live != null) {
        val bill = live.bill()
        Text(Lines.live(bill, live.riders.count { it.aboard }), style = Look.type.label, color = colors.accentText)
        live.destination?.let { destination ->
            val toll = if (live.routeTollWon > 0) " · 통행료 예상 ${Lines.won(live.routeTollWon)}" else ""
            Text("$destination 가는 길$toll", style = Look.type.body, color = colors.ink)
        }
        Text("타고 내릴 때마다 구간을 나눠 계산해요. 운전 중에는 말을 걸지 않아요.", style = Look.type.caption, color = colors.muted)
        if (!state.restricted) AccentButton("지금 정산 보기", prominent = true, onClick = { vm.settleNow() })
    } else {
        Text("함께 타고 출발하면 누가 어디서 타고 내렸는지 차가 기록해요.", style = Look.type.body, color = colors.ink)
        Text("도착해서 P에 두면 금액을 말하고 QR을 띄워요. \"Gleo, 정산해 줘\"라고 해도 돼요.", style = Look.type.caption, color = colors.muted)
    }
}

/** 자리 지도. 탑승 센서가 없는 자리는 눌러서 탔다고 알려 준다. */
@Composable
private fun SeatMap(state: UiState, vm: RideViewModel) {
    val aboard = state.live?.riders?.filter { it.aboard }.orEmpty()
    fun occupant(seat: Seat): String? =
        aboard.firstOrNull { it.seat == seat }?.let { it.name ?: "탔어요" } ?: if (state.car.seated[seat] == true) "탔어요" else null

    @Composable
    fun RowScope.Tile(seat: Seat) {
        val who = if (seat == Seat.DRIVER) "운전자" else occupant(seat)
        val sensorless = seat != Seat.DRIVER && seat !in state.car.seated.keys
        val colors = Look.colors
        val shape = Look.shapes.seat
        val base = Modifier
            .weight(1f)
            .height(108.dp)
            .clip(shape)
            .background(if (who != null) colors.raised else colors.surface)
            .border(1.dp, if (who != null && seat != Seat.DRIVER) colors.accent else colors.line, shape)
        Column(
            (if (sensorless && !state.restricted) base.clickable { vm.setManualSeat(seat, who == null) } else base).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(seat.label, style = Look.type.caption, color = colors.muted)
            Text(who ?: if (sensorless) "눌러서 탑승" else "빈자리", style = Look.type.label, color = if (who != null) colors.ink else colors.muted)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(Seat.DRIVER)
            Tile(Seat.FRONT)
            Spacer(Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(Seat.REAR_LEFT)
            Tile(Seat.REAR_CENTER)
            Tile(Seat.REAR_RIGHT)
        }
    }
}

@Composable
private fun ColumnScope.LedgerPanel(state: UiState, vm: RideViewModel) {
    val colors = Look.colors
    val summary = state.summary
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text("받을 돈", style = Look.type.caption, color = colors.muted)
            Text(Lines.won(summary.owedWon), style = Look.type.display, color = colors.accentText)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("이번 달 ${summary.monthRides}번 · ${Lines.km(summary.monthKm)}", style = Look.type.body, color = colors.ink)
            Text("동승자 몫 ${Lines.won(summary.monthSharedWon)}", style = Look.type.caption, color = colors.muted)
        }
    }
    val recent = state.rides.sortedByDescending { it.startedAt }.take(RECENT_RIDES)
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (summary.people.isEmpty() && summary.unnamedOwedWon == 0) {
            item { Text("아직 받을 돈이 없어요.", style = Look.type.body, color = colors.muted) }
        }
        items(summary.people, key = { it.name }) { person ->
            ListRow(
                title = person.name,
                detail = "${person.rides}번 · ${Lines.km(person.km)}",
                amount = Lines.won(person.owedWon),
                action = "정산",
                onClick = { vm.openPerson(person.name) },
            )
        }
        if (summary.unnamedOwedWon > 0) {
            item {
                Text(
                    "이름 없는 동승자 몫 ${Lines.won(summary.unnamedOwedWon)} · 길을 눌러 이름을 붙여요",
                    style = Look.type.caption,
                    color = colors.muted,
                )
            }
        }
        if (recent.isNotEmpty()) {
            item { Text("최근 함께 탄 길", style = Look.type.heading, color = colors.ink, modifier = Modifier.padding(top = 12.dp)) }
        }
        items(recent, key = { it.id }) { ride ->
            val bill = ride.bill()
            val status = when {
                ride.treated -> "쏨"
                bill.owedWon == 0 -> "다 받음"
                else -> Lines.won(bill.owedWon)
            }
            ListRow(
                title = ride.destination?.let { "$it 가는 길" } ?: "함께 탄 길",
                detail = "${dayTime(ride.startedAt, state.zone)} · ${bill.shares.joinToString { it.rider.label }} · ${Lines.km(bill.km)}",
                amount = status,
                action = null,
                onClick = { vm.openRide(ride.id) },
            )
        }
    }
}

@Composable
private fun ListRow(title: String, detail: String, amount: String, action: String?, onClick: () -> Unit) {
    val colors = Look.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Look.shapes.compact)
            .background(colors.raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Look.type.label, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = Look.type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(amount, style = Look.type.label, color = colors.accentText)
        action?.let { Tag(it, color = colors.ink) }
    }
}

// ───── 정산 카드 ─────

@Composable
private fun CardScreen(state: UiState, ride: Ride, live: Boolean, vm: RideViewModel) {
    val colors = Look.colors
    val bill = ride.bill()
    val content = PayLink.content(PayLink.forBill(ride, bill, state.settings.payee, state.zone), state.payUrl)
    var editing by remember(ride.id) { mutableStateOf<Int?>(null) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        Panel(
            Modifier
                .weight(1.25f)
                .fillMaxHeight(),
        ) {
            // 모두 얼마인지는 비용 줄 끝에 두면 잘려서 제목 옆에 크게 둔다.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (live) "함께 탄 길 정산" else ride.destination?.let { "$it 가는 길" } ?: "함께 탄 길",
                    style = Look.type.title,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("모두 ${Lines.won(bill.totalWon)}", style = Look.type.title, color = colors.accentText)
            }
            Text(
                "${
                    dayTime(
                        ride.startedAt,
                        state.zone,
                    )
                } · ${ride.rule.label} 나누기" + if (live && state.car.moving) " · 달리는 중이라 지금까지 금액이에요" else "",
                style = Look.type.caption,
                color = colors.muted,
            )
            CostTags(bill)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(bill.shares, key = { it.rider.id }) { share ->
                    ShareRow(
                        share = share,
                        expanded = editing == share.rider.id,
                        // 이 길에서 다른 자리에 이미 붙인 이름은 칩에서 뺀다.
                        names = state.names - bill.shares.filter { it.rider.id != share.rider.id }.mapNotNull { it.rider.name }.toSet(),
                        restricted = state.restricted,
                        onToggle = { editing = if (editing == share.rider.id) null else share.rider.id },
                        onName = { name ->
                            vm.rename(if (live) null else ride.id, share.rider.id, name)
                            editing = null
                        },
                    )
                }
                item {
                    Text(
                        "운전자 몫 ${Lines.won(bill.driverWon)} · 운전 수고비는 넣지 않고 실제로 든 비용만 나눠요",
                        style = Look.type.caption,
                        color = colors.muted,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                QuietButton(if (bill.treated) "다시 나눠 내기" else "이번엔 내가 쏠게", onClick = { vm.treat(!bill.treated) })
                QuietButton("주차비 +1,000원", onClick = { vm.addExtra(Extra(ExtraKind.PARKING, 1_000)) })
                Spacer(Modifier.weight(1f))
                when {
                    live -> AccentButton("장부에 넣기", prominent = true, onClick = vm::finishNow)
                    bill.owedWon > 0 -> AccentButton("모두 받았어요", onClick = { vm.markPaid(ride.id) })
                    else -> QuietButton("닫기", onClick = vm::goHome)
                }
            }
        }
        Panel(
            Modifier
                .weight(0.75f)
                .fillMaxHeight(),
        ) {
            QrPanel(content, bill.owedWon, state)
        }
    }
}

@Composable
private fun CostTags(bill: Bill) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tag("거리 ${Lines.km(bill.km)}", Look.colors.ink, Look.type.label)
        Tag(
            "${bill.power.energyName} ${Lines.energy(bill)} ${Lines.won(bill.energyWon)}" + if (bill.energyMeasured) " · 차가 잰 값" else " · 어림",
            Look.colors.ink,
            Look.type.label,
        )
        if (bill.tollWon > 0) Tag(
            "통행료 ${Lines.won(bill.tollWon)}" + if (bill.tollEstimated) " · 내비 예상" else "",
            Look.colors.ink,
            Look.type.label,
        )
        if (bill.extraWon > 0) Tag("주차비·기타 ${Lines.won(bill.extraWon)}", Look.colors.ink, Look.type.label)
    }
}

@Composable
private fun ShareRow(
    share: RiderShare,
    expanded: Boolean,
    names: List<String>,
    restricted: Boolean,
    onToggle: () -> Unit,
    onName: (String?) -> Unit,
) {
    val colors = Look.colors
    val rider = share.rider
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Look.shapes.compact)
            .background(colors.raised)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !restricted, onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(rider.name ?: "이름 붙이기", style = Look.type.label, color = if (rider.name != null) colors.ink else colors.muted)
                    if (rider.nameGuessed) Tag("추정")
                }
                val from = rider.boardedPlace.address ?: "출발한 곳"
                val to = rider.alightedPlace.address ?: if (rider.aboard) "지금" else "내린 곳"
                Text(
                    "${rider.seat.label} · $from → $to · ${Lines.km(share.km)}",
                    style = Look.type.caption,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (share.paid) "받음" else Lines.won(share.won),
                style = Look.type.heading,
                color = if (share.paid) colors.muted else colors.accentText,
            )
        }
        if (expanded) NamePicker(rider.name, names, restricted, onName)
    }
}

@Composable
private fun NamePicker(current: String?, names: List<String>, restricted: Boolean, onName: (String?) -> Unit) {
    var typed by remember { mutableStateOf("") }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        names.take(MAX_NAME_CHIPS).forEach { name -> Tag(name, Look.colors.ink, Look.type.label, onClick = { onName(name) }) }
        if (current != null) Tag("이름 지우기", Look.colors.muted, Look.type.label, onClick = { onName(null) })
    }
    if (!restricted) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.take(12) },
                singleLine = true,
                placeholder = { Text("새 이름", style = Look.type.body) },
                textStyle = Look.type.body,
                modifier = Modifier.width(320.dp),
            )
            QuietButton("붙이기", enabled = typed.isNotBlank(), onClick = { onName(typed) })
        }
    }
}

@Composable
private fun ColumnQr(content: String) {
    val bitmap: ImageBitmap? = remember(content) { Qr.bitmap(content, QR_PX)?.asImageBitmap() }
    Box(
        Modifier
            .size(440.dp)
            .clip(Look.shapes.qr)
            .background(Look.colors.qrBackground)
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = "정산 QR", modifier = Modifier.fillMaxSize())
        } else {
            Text("QR을 만들지 못했어요", style = Look.type.body, color = Look.colors.qrForeground)
        }
    }
}

@Composable
private fun QrPanel(content: String, owedWon: Int, state: UiState) {
    val colors = Look.colors
    Text("휴대폰으로 찍어요", style = Look.type.heading, color = colors.ink)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ColumnQr(content) }
    Text(
        if (owedWon > 0) "받을 돈 ${Lines.won(owedWon)}" else "받을 돈이 없어요",
        style = Look.type.heading,
        color = colors.accentText,
    )
    Text("카메라로 찍으면 내 금액과 근거, 보낼 곳이 보여요. 앱을 깔지 않아도 돼요.", style = Look.type.caption, color = colors.muted)
    if (state.settings.payee.isEmpty) {
        Text("받을 계좌나 송금 링크를 설정에서 넣어 주세요.", style = Look.type.caption, color = colors.warn)
    }
    if (state.payUrl.isBlank()) {
        Text("정산 페이지 주소가 없어 QR에 금액 글만 담았어요.", style = Look.type.caption, color = colors.muted)
    }
}

// ───── 사람별 정산 ─────

@Composable
private fun PersonScreen(state: UiState, name: String, vm: RideViewModel) {
    val colors = Look.colors
    val person = state.summary.people.firstOrNull { it.name == name }
    val unpaid = unpaidFor(state.rides, name)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        Panel(
            Modifier
                .weight(1.25f)
                .fillMaxHeight(),
        ) {
            Text("$name 님", style = Look.type.title, color = colors.ink)
            Text(
                person?.let { "${it.rides}번 · ${Lines.km(it.km)} 함께 탔어요 · 받을 돈 ${Lines.won(it.owedWon)}" } ?: "남은 금액이 없어요.",
                style = Look.type.body,
                color = colors.ink,
            )
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(unpaid, key = { (ride, share) -> "${ride.id}-${share.rider.id}" }) { (ride, share) ->
                    ListRow(
                        title = ride.destination?.let { "$it 가는 길" } ?: "함께 탄 길",
                        detail = "${dayTime(ride.startedAt, state.zone)} · ${share.rider.seat.label} · ${Lines.km(share.km)}",
                        amount = Lines.won(share.won),
                        action = null,
                        onClick = { vm.openRide(ride.id) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuietButton("뒤로", onClick = vm::goHome)
                Spacer(Modifier.weight(1f))
                if (person != null) AccentButton("받았어요", onClick = { vm.settlePerson(name) })
            }
        }
        Panel(
            Modifier
                .weight(0.75f)
                .fillMaxHeight(),
        ) {
            if (person != null) {
                QrPanel(PayLink.content(PayLink.forPerson(person, state.settings.payee), state.payUrl), person.owedWon, state)
            } else {
                Text("남은 금액이 없어요.", style = Look.type.body, color = colors.muted)
            }
        }
    }
}

private const val RECENT_RIDES = 6
private const val MAX_NAME_CHIPS = 6
private const val QR_PX = 720
