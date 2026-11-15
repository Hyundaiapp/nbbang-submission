// C-01 홈: 모드 선택, 진행 중 주행, 월 정산 및 미수금 요약.
package com.nbbang.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.nbbang.domain.Gear
import com.nbbang.UiState
import com.nbbang.RideViewModel
import com.nbbang.R
import com.nbbang.domain.ChargeStatus
import com.nbbang.domain.Lines
import com.nbbang.domain.TripMode
import com.nbbang.domain.sharedNothing
import com.nbbang.settle.settlement
import com.nbbang.settle.unsettled
import com.nbbang.ui.*

/** C-01 전용 표시 데이터. 월별 정산/청구 기능이 연결되면 확정된 값만 전달한다. */
data class HomeSummary(
    val expectedWon: Int = 0,
    val rideCount: Int = 0,
    val savingWon: Int = 0,
    val overdueCount: Int = 0,
    val recentInvoices: List<HomeInvoice> = emptyList(),
    val liveDescription: String? = null,
)

data class HomeInvoice(val name: String, val won: Int, val detail: String, val paid: Boolean, val overdue: Boolean)

@Composable
fun HomeScreen(
    state: UiState,
    vm: RideViewModel,
    summary: HomeSummary = state.homeSummary(),
    allowDrivingHome: Boolean = false,
) {
    var notice by remember { mutableStateOf<String?>(null) }
    var balances by remember { mutableStateOf(false) }
    val unsettled = remember(state.rides, state.charges) { state.rides.unsettled(state.charges, System.currentTimeMillis()) }
    val c = Look.colors
    val today = java.time.LocalDate.now(state.zone)
    val date = today.format(java.time.format.DateTimeFormatter.ofPattern("yyyy. M. d (E)", java.util.Locale.KOREAN))
    val driving = state.restricted || state.car.moving ||
        state.car.gear == Gear.DRIVE || state.car.gear == Gear.REVERSE
    if (driving && !allowDrivingHome) {
        Box(Modifier
            .fillMaxSize()
            .background(c.surface), contentAlignment = Alignment.Center) {
            Text("주행 중이에요. 주차 후 홈 화면을 이용해 주세요.", style = Look.type.body, color = c.ink)
        }
        return
    }
    Column(Modifier
        .fillMaxSize()
        .background(c.surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(104.dp)
                .padding(horizontal = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Image(painterResource(R.drawable.ic_logo), contentDescription = "N빵~", modifier = Modifier.size(88.dp))
            Text(date, style = Look.type.caption, color = c.muted)
        }
        Row(Modifier.weight(1f)) {
            HomeSidebar(vm, selectedIndex = 0, enabled = !driving)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(c.raised.copy(alpha = 0.4f))
                    .verticalScroll(rememberScrollState())
                    .padding(start = 50.dp, end = 64.dp, top = 36.dp, bottom = if (state.live != null) 216.dp else 36.dp),
                verticalArrangement = Arrangement.spacedBy(36.dp),
            ) {
                state.live?.let { ride ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(30.dp))
                            .background(c.action.copy(alpha = 0.13f))
                            .border(3.dp, c.action, RoundedCornerShape(30.dp))
                            .padding(horizontal = 28.dp, vertical = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        Box(
                            Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(50))
                                .background(c.action),
                            contentAlignment = Alignment.Center,
                        ) { Text("N", style = Look.type.heading, color = c.surface) }
                        Column(Modifier.weight(1f)) {
                            Text("진행 중인 주행이 있어요", style = Look.type.heading, color = c.ink)
                            Text(
                                summary.liveDescription
                                    ?: "${ride.destination ?: "주행 기록"} · ${Lines.km(ride.segments.sumOf { it.km })} · 탑승 ${ride.riders.count { it.aboard } + 1}명",
                                style = Look.type.caption,
                                color = c.muted,
                            )
                        }
                    }
                }
                if (state.live == null) unsettled.firstOrNull()?.let { ride ->
                    // C-08에서 청구서를 만들지 않고 나온 주행. 여기서 다시 정산으로 들어간다.
                    val owed = remember(ride) { ride.settlement().riders.sumOf { it.finalWon.coerceAtLeast(0) } }
                    val day = java.time.Instant.ofEpochMilli(ride.startedAt).atZone(state.zone)
                        .format(java.time.format.DateTimeFormatter.ofPattern("M/d (E)", java.util.Locale.KOREAN))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(30.dp))
                            .background(c.accent.copy(alpha = 0.10f))
                            .border(3.dp, c.accent, RoundedCornerShape(30.dp))
                            .padding(horizontal = 28.dp, vertical = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        Box(
                            Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(50))
                                .background(c.accent),
                            contentAlignment = Alignment.Center,
                        ) { Text("N", style = Look.type.heading, color = c.surface) }
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (unsettled.size > 1) "정산하지 않은 주행이 ${unsettled.size}건 있어요" else "정산하지 않은 주행이 있어요",
                                style = Look.type.heading,
                                color = c.ink,
                            )
                            Text(
                                "$day · ${ride.destination?.let { "$it 가는 길" } ?: "함께 탄 길"} · 받을 돈 ${Lines.won(owed)}",
                                style = Look.type.caption,
                                color = c.muted,
                            )
                        }
                        HomeButton("이어서 정산", c.accent, c.surface, Modifier
                            .width(270.dp)
                            .height(80.dp), enabled = !driving) { vm.openSettlement(ride.id) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    ModeCard(
                        "카풀모드",
                        "그룹을 정해 한 달 치를 모아서 청구",
                        "출퇴근 · 정기 동승에 알맞아요",
                        "· 저장된 그룹 선택 / 새 그룹 추가\n· 월별 정산 + QR 청구서",
                        "카풀 시작",
                        c.accent,
                        c.surface,
                        c.surface,
                        c.ink,
                        Modifier.weight(1f),
                        enabled = !driving,
                    ) {
                        when {
                            state.live != null && !state.live.sharedNothing -> notice = "진행 중인 주행을 먼저 마쳐 주세요."
                            !state.canSetUpCarpool -> notice = "주차한 상태에서 카풀을 시작해 주세요."
                            else -> vm.openCarpoolGroups()
                        }
                    }
                    ModeCard(
                        "여행모드",
                        "그때그때 인원을 추가하고 바로 정산",
                        "여행 · 일회성 동승에 알맞아요",
                        "· 인원 즉석 추가 / 탄 구간만큼 N빵\n· 게임으로 N빵 + QR 청구서",
                        "여행 시작",
                        c.action,
                        c.ink,
                        c.ink,
                        c.surface,
                        Modifier.weight(1f),
                        enabled = !driving,
                    ) {
                        if (state.live?.mode == TripMode.CARPOOL) notice = "진행 중인 카풀을 먼저 마쳐 주세요."
                        else if (state.live?.sharedNothing == false) notice = "진행 중인 주행을 먼저 마쳐 주세요."
                        else vm.openTripStart()
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    Panel(Modifier
                        .weight(1f)
                        .heightIn(min = 270.dp)
                        .clickable(enabled = !driving) { vm.openCarpoolMonthly() }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${today.monthValue}월 카풀 정산", style = Look.type.label, color = c.ink)
                            Text("자세히 >", style = Look.type.caption, color = c.accent)
                        }
                        Text("이번 달 청구 예정", style = Look.type.caption, color = c.muted)
                        Text(
                            Lines.won(summary.expectedWon),
                            style = Look.type.display.copy(fontSize = 56.sp, lineHeight = 68.sp),
                            color = c.accentText,
                        )
                        Text(
                            "주행 ${summary.rideCount}회" + if (summary.savingWon > 0) " · 절약 기여 ${Lines.won(summary.savingWon)} 인정" else "",
                            style = Look.type.caption,
                            color = c.success,
                        )
                    }
                    Panel(Modifier
                        .weight(1f)
                        .heightIn(min = 270.dp)
                        .clickable(enabled = !driving) { vm.openCharges() }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("최근 청구", style = Look.type.label, color = c.ink)
                            Text("청구 현황 >", style = Look.type.caption, color = c.accent)
                        }
                        if (summary.overdueCount > 0) {
                            Box(
                                Modifier
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(c.warn.copy(alpha = 0.1f))
                                    .clickable(enabled = !driving) { vm.openCharges(ChargeStatus.OVERDUE) }
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("미납 ${summary.overdueCount}건", style = Look.type.caption, color = c.warn)
                            }
                        }
                        if (summary.recentInvoices.isEmpty()) Text("아직 생성된 청구서가 없어요", style = Look.type.caption, color = c.muted)
                        summary.recentInvoices.take(2).forEach { invoice ->
                            Text(
                                "${invoice.name}  ${Lines.won(invoice.won)}  ${invoice.detail}", style = Look.type.caption,
                                color = when {
                                    invoice.paid -> c.success; invoice.overdue -> c.warn; else -> c.ink
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    if (!driving && notice != null) AlertDialog(
        onDismissRequest = { notice = null },
        title = { Text("잠시만 기다려 주세요", fontFamily = AstaSans) },
        text = { Text(notice!!, style = Look.type.body) },
        confirmButton = { TextButton(onClick = { notice = null }) { Text("확인", style = Look.type.label) } },
    )
    if (!driving && balances) AlertDialog(
        onDismissRequest = { balances = false }, title = { Text("청구 현황", style = Look.type.heading) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.summary.owedWon == 0) Text("아직 받을 돈이 없어요.", style = Look.type.body)
                state.summary.people.forEach { person ->
                    TextButton(
                        onClick = {
                            balances = false; vm.openPerson(person.name)
                        },
                    ) { Text("${person.name} · ${Lines.won(person.owedWon)}  →", style = Look.type.body) }
                }
                if (state.summary.unnamedOwedWon > 0) Text("이름 없는 탑승자 · ${Lines.won(state.summary.unnamedOwedWon)}", style = Look.type.body)
            }
        },
        confirmButton = { TextButton(onClick = { balances = false }) { Text("닫기", style = Look.type.label) } },
    )
}

@Composable
private fun ModeCard(
    title: String,
    subtitle: String,
    description: String,
    details: String,
    action: String,
    background: Color,
    foreground: Color,
    button: Color,
    onButton: Color,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .height(480.dp)
            .clip(RoundedCornerShape(34.dp))
            .background(background)
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = Look.type.title.copy(fontWeight = FontWeight.Bold), color = foreground)
        Text(subtitle, style = Look.type.body, color = foreground)
        Text(description, style = Look.type.body, color = foreground)
        Spacer(Modifier.height(8.dp))
        Text(details, style = Look.type.caption, color = foreground)
        Spacer(Modifier.weight(1f))
        HomeButton(action, button, onButton, Modifier
            .width(330.dp)
            .height(90.dp), enabled, onClick)
    }
}

@Composable
private fun HomeButton(
    text: String, background: Color, foreground: Color, modifier: Modifier,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    Box(modifier
        .clip(RoundedCornerShape(24.dp))
        .background(background)
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = Look.type.button, color = foreground)
    }
}

private val NAV_ICON_ON = Color(0xFFECF4FE)
private val NAV_ICON_OFF = Color(0xFFA4A8AE)

/** 홈과 전체보기에서 함께 쓰는 내비게이션. null이면 선택 표시가 없다. */
@Composable
internal fun HomeSidebar(vm: RideViewModel, selectedIndex: Int? = null, enabled: Boolean = true) {
    val c = Look.colors
    Column(Modifier
        .width(150.dp)
        .fillMaxHeight()) {
        listOf(
            "홈" to R.drawable.ic_nav_home,
            "카풀기록" to R.drawable.ic_nav_record,
            "청구현황" to R.drawable.ic_nav_paybill,
            "설정" to R.drawable.ic_nav_settings,
        ).forEachIndexed { index, (label, icon) ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .background(if (index == selectedIndex) c.accent.copy(alpha = 0.12f) else c.surface)
                    .clickable(enabled = enabled) {
                        when (index) {
                            0 -> vm.goHome()
                            1 -> vm.openCarpoolMonthly()
                            2 -> vm.openCharges()
                            3 -> vm.openSettings()
                        }
                    },
            ) {
                if (index == selectedIndex) Box(Modifier
                    .width(8.dp)
                    .fillMaxHeight()
                    .background(c.accent))
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (index == selectedIndex) c.accent else c.raised),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 디자인 아이콘(128×128) 색: 선택 #ECF4FE, 아니면 #A4A8AE.
                        Icon(
                            painterResource(icon), contentDescription = null,
                            tint = if (index == selectedIndex) NAV_ICON_ON else NAV_ICON_OFF,
                            modifier = Modifier.size(if (icon == R.drawable.ic_nav_paybill) 64.dp else 40.dp),
                        )
                    }
                    Text(label, style = Look.type.caption, color = if (index == selectedIndex) c.accent else c.muted)
                }
            }
        }
    }
}
