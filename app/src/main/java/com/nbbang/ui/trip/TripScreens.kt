// 여행 모드 화면: T-01 인원 추가(여행 시작) → (주행) → T-03 N빵하기(주행 요약) → C-08.
// 기획서의 T-02 절약 모드 단계 선택과 급가속·급제동 코칭은 뺐다. 연비를 아낀 건 C-11에서 보여 주기만 한다.
// T-01에서 확정한 인원은 출발 지점부터 포함한다.
package com.nbbang.ui.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nbbang.settle.priceBasis
import com.nbbang.settle.expectedName
import com.nbbang.RideViewModel
import com.nbbang.UiState
import com.nbbang.domain.Gear
import com.nbbang.domain.Power
import com.nbbang.domain.Ride
import com.nbbang.domain.Seat
import com.nbbang.domain.TripMode
import com.nbbang.settle.riderId
import com.nbbang.ui.AccentButton
import com.nbbang.ui.Look
import com.nbbang.ui.QuietButton
import com.nbbang.ui.driving.DrivingOverlay
import com.nbbang.ui.returnToNavigation
import com.nbbang.ui.settle.Badge
import com.nbbang.ui.settle.Chip
import com.nbbang.ui.settle.NumberPadDialog
import com.nbbang.ui.settle.PadButton
import com.nbbang.ui.settle.SettleCard
import com.nbbang.ui.settle.SettleEmpty
import com.nbbang.ui.settle.rangeText
import com.nbbang.ui.settle.roundingNote
import com.nbbang.ui.settle.SettleFrame
import com.nbbang.ui.settle.finalText
import com.nbbang.ui.settle.won
import java.util.Locale

// C-04 이름(RideViewModel MAX_NAME)과 같게. 다르면 최근 이름 칩이 잘려 다른 사람이 된다.
private const val MAX_NAME = 12

// ---------------------------------------------------------------- T-01

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripStartScreen(state: UiState, vm: RideViewModel) {
    val c = Look.colors
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val start = if (state.setup.mode == TripMode.TRAVEL) state.setup.names else emptyList()
    var names by rememberSaveable { mutableStateOf(start) }
    var typed by rememberSaveable { mutableStateOf("") }
    val recent = state.names.filterNot { it in names }.take(6)
    val canAdd = names.size < Seat.passengers.size
    val context = LocalContext.current
    // 기획서: 내비게이션으로 돌아가고 N빵~은 뒤에서 기록한다.
    val go = {
        if (vm.startTravel(names)) {
            DrivingOverlay.open(context, requestPermission = false)
            returnToNavigation(context)
        }
    }

    fun add(raw: String) {
        val name = raw.trim().take(MAX_NAME)
        if (name.isEmpty() || !canAdd) return
        names = names + uniqueName(name, names)
        typed = ""
        keyboard?.hide()
        focus.clearFocus()
    }

    SettleFrame("여행 시작", onBack = vm::goHome) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("함께 가는 사람을 추가하세요", style = Look.type.heading, color = c.ink)
            Text("여행모드는 그때그때 인원을 추가해요. 주행 중에 타는 사람은 자리에 앉으면 탑승자 추가 팝업에서 이름을 고를 수 있어요.", style = Look.type.caption, color = c.muted)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            SettleCard(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Text("탑승 인원 (${names.size + 1}명)", style = Look.type.label, color = c.ink)
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PersonRow("나 (운전자)", "출발 지점부터 전 구간") { Badge("운전자", c.accent) }
                    names.forEach { name ->
                        PersonRow(name, "출발 지점부터") {
                            Box(
                                Modifier
                                    .size(width = 110.dp, height = 60.dp)
                                    .clip(Look.shapes.button)
                                    .border(3.dp, c.warn, Look.shapes.button)
                                    .clickable { names = names - name },
                                contentAlignment = Alignment.Center,
                            ) { Text("삭제", style = Look.type.label, color = c.warn) }
                        }
                    }
                    if (names.isEmpty()) Text("아직 추가한 사람이 없어요. 한 명 이상 있어야 출발할 수 있어요.", style = Look.type.caption, color = c.muted)
                }
            }
            Column(Modifier.width(640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("이름 입력", style = Look.type.label, color = c.ink)
                if (!canAdd) Text("동승자는 최대 ${Seat.passengers.size}명까지 추가할 수 있어요.", style = Look.type.caption, color = c.muted)
                if (state.restricted) {
                    Text("주차(P) 상태에서만 이름을 입력할 수 있어요. 최근 동승자는 바로 고를 수 있어요.", style = Look.type.caption, color = c.warn)
                } else {
                    BasicTextField(
                        value = typed,
                        onValueChange = { typed = it.take(MAX_NAME) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add(typed) }),
                        textStyle = Look.type.label.copy(color = c.ink),
                        cursorBrush = SolidColor(c.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(84.dp)
                            .clip(Look.shapes.input)
                            .border(3.dp, c.accent, Look.shapes.input)
                            .padding(horizontal = 24.dp, vertical = 22.dp),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (typed.isEmpty()) Text("이름을 입력하세요 (1~${MAX_NAME}자)", style = Look.type.label, color = c.muted)
                                inner()
                            }
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AccentButton("인원 추가", Modifier.weight(1f), enabled = canAdd && typed.isNotBlank() && !state.restricted) { add(typed) }
                    QuietButton("연락처에서 선택", Modifier.weight(1f), enabled = false) {}
                }
                Text("연락처 읽기는 차 SDK에 없어서 이름을 직접 넣거나 최근 동승자에서 골라요.", style = Look.type.caption, color = c.muted)
                if (recent.isNotEmpty()) {
                    Text("최근 동승자", style = Look.type.label, color = c.ink)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        recent.forEach { name -> Chip(name, false, enabled = canAdd) { add(name) } }
                    }
                }
                Spacer(Modifier.weight(1f))
                // 기획서 F-C03: 정산 계좌가 없으면 주행을 시작할 수 없다(청구서에 담을 계좌).
                if (state.settings.payee.account.isBlank()) {
                    Text("정산 계좌를 먼저 등록해야 출발할 수 있어요. 청구서 QR에 이 계좌가 담겨요.", style = Look.type.body, color = c.warn)
                    AccentButton("설정에서 계좌 등록", Modifier.fillMaxWidth(), onClick = vm::openSettings)
                } else {
                    AccentButton(
                        "주행 시작", Modifier.fillMaxWidth(),
                        enabled = names.isNotEmpty() && state.car.gear == Gear.PARK && !state.restricted,
                        prominent = true,
                    ) { go() }
                }
            }
        }
    }
}

@Composable
private fun PersonRow(name: String, detail: String, trailing: @Composable () -> Unit) {
    val c = Look.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .clip(Look.shapes.compact)
            .background(c.raised.copy(alpha = 0.4f))
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(c.surface),
            contentAlignment = Alignment.Center,
        ) { Text(name.take(1), style = Look.type.label, color = c.ink) }
        Column(Modifier.weight(1f)) {
            Text(name, style = Look.type.label, color = c.ink)
            Text(detail, style = Look.type.caption, color = c.muted)
        }
        trailing()
    }
}

/** 같은 이름이 있으면 뒤에 숫자를 붙인다(기획서 T-01: 민수 → 민수 2). */
internal fun uniqueName(name: String, taken: List<String>): String {
    if (name !in taken) return name
    var n = 2
    while ("$name $n" in taken) n++
    return "$name $n"
}

// ---------------------------------------------------------------- T-03

@Composable
fun TripSummaryScreen(state: UiState, vm: RideViewModel) {
    val c = Look.colors
    val context = LocalContext.current
    val ride = state.live
    val s = state.settlement(null)
    if (ride == null || s == null) return SettleEmpty("진행 중인 주행이 없어요", vm::goHome)
    if (state.restricted || state.car.gear != Gear.PARK) return SettleEmpty("주차(P) 후에 주행 요약을 볼 수 있어요", vm::goHome)
    var tollIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val pending = ride.tollPasses.withIndex().filter { it.value.won == null }
    val electric = ride.power == Power.ELECTRIC
    val minutes = (((ride.endedAt ?: System.currentTimeMillis()) - ride.startedAt) / 60_000).coerceAtLeast(0)

    SettleFrame("주행 요약", onBack = null, trailing = {
        Badge(
            "여행",
            c.accent,
        )
    }) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SettleCard(Modifier.fillMaxWidth()) {
                    Text("총 주행거리", style = Look.type.caption, color = c.muted)
                    Text(String.format(Locale.KOREA, "%.1f km", s.km), style = Look.type.display, color = c.ink)
                    Text("소요 시간 ${if (minutes >= 60) "${minutes / 60}시간 ${minutes % 60}분" else "${minutes}분"}", style = Look.type.body, color = c.muted)
                }
                SettleCard(Modifier.fillMaxWidth()) {
                    Text("비용 요약 (${ride.prices.priceBasis(ride.power)} 기준)", style = Look.type.label, color = c.ink)
                    SummaryLine("${if (electric) "충전비" else "유류비"} (${if (s.actualFuel) "실제" else ride.expectedName()} ${if (electric) "전비" else "연비"} 기준)", won(s.fuelWon))
                    SummaryLine("톨비" + if (pending.isNotEmpty()) " (미입력 ${pending.size}건 제외)" else "", won(s.tollWon))
                    SummaryLine("기타 비용 (먹은 음식 등)", won(s.expenseWon))
                    SummaryLine("합계", won(s.costWon), bold = true)
                    Text("단가 수정과 별도 결제는 다음 정산 화면에서 해요.", style = Look.type.caption, color = c.muted)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SettleCard(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    Text("탑승자별 예상 금액", style = Look.type.label, color = c.ink)
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.shares.forEach { share ->
                            val id = share.riderId()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 84.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (share.person.isDriver) "나 (운전자)" else share.person.name, style = Look.type.label, color = c.ink)
                                    Text(rangeText(ride, id, s.km), style = Look.type.caption, color = c.muted)
                                }
                                Text(finalText(share), style = Look.type.label, color = c.gold)
                            }
                        }
                    }
                    Text(roundingNote(s.roundingWon), style = Look.type.caption, color = c.muted)
                }
                if (pending.isEmpty()) {
                    SettleCard(Modifier.fillMaxWidth(), border = c.success, background = c.success.copy(alpha = 0.08f)) {
                        Text("모든 톨비가 입력되었어요", style = Look.type.label, color = c.success)
                    }
                } else {
                    SettleCard(Modifier.fillMaxWidth(), border = c.warn, background = c.warn.copy(alpha = 0.06f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("미입력 톨비 ${pending.size}건이 있어요", style = Look.type.label, color = c.warn, modifier = Modifier.weight(1f))
                            Box(
                                Modifier
                                    .size(width = 130.dp, height = 64.dp)
                                    .clip(Look.shapes.button)
                                    .background(c.warn)
                                    .clickable { tollIndex = pending.first().index },
                                contentAlignment = Alignment.Center,
                            ) { Text("입력", style = Look.type.label, color = c.surface) }
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            QuietButton("〈 주행 계속하기", Modifier.weight(1f)) {
                vm.goHome()
                returnToNavigation(context)
            }
            AccentButton("N빵하기!", Modifier.weight(2f), prominent = true, onClick = vm::finishNow)
        }
    }

    tollIndex?.let { index ->
        val pass = ride.tollPasses.getOrNull(index)
        NumberPadDialog(
            title = "${pass?.name?.takeIf { it.isNotBlank() } ?: "톨게이트 ${index + 1}"} 톨비",
            initial = pass?.won,
            hint = "통과할 때 탄 ${pass?.aboard?.size ?: 0}명이 나눠요.",
            extra = listOf("통행료 없음 0원" to { vm.enterToll(null, index, 0); tollIndex = null }),
            onDismiss = { tollIndex = null },
        ) { vm.enterToll(null, index, it); tollIndex = null }
    }
}

@Composable
private fun SummaryLine(label: String, value: String, bold: Boolean = false) {
    val c = Look.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = if (bold) Look.type.heading else Look.type.body, color = c.ink, modifier = Modifier.weight(1f))
        Text(value, style = if (bold) Look.type.heading else Look.type.label, color = c.gold)
    }
}
