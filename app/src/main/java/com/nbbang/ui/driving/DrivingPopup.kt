// C-03 주행 중 현황: 집계와 독립적으로 열고 닫는 미니 팝업.
package com.nbbang.ui.driving

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nbbang.UiState
import com.nbbang.domain.Gear
import com.nbbang.domain.Lines
import com.nbbang.domain.Power
import com.nbbang.domain.rootId
import com.nbbang.settle.riderId
import com.nbbang.ui.Look

@Composable
fun DrivingPopup(
    state: UiState,
    onOpenFullScreen: () -> Unit,
    onClose: () -> Unit,
    onFinish: () -> Unit,
    onAddPassenger: () -> Unit,
    onSelectPassenger: (Int) -> Unit,
    onAddCost: () -> Unit,
) {
    val c = Look.colors
    Column(
        Modifier
            .fillMaxSize()
            .clip(Look.shapes.popup)
            .background(c.surface)
            .border(4.dp, c.ink, Look.shapes.popup)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Row(Modifier
            .fillMaxWidth()
            .height(84.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier
                .size(40.dp)
                .background(c.accent, Look.shapes.pill), contentAlignment = Alignment.Center) {
                Text("N", style = Look.type.label, color = c.onAccent)
            }
            Spacer(Modifier.width(12.dp))
            Text("N빵~ 주행 중", style = Look.type.popupTitle, color = c.ink)
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .height(64.dp)
                    .clip(Look.shapes.pill)
                    .background(c.accent.copy(alpha = 0.12f))
                    .clickable(role = Role.Button, onClick = onOpenFullScreen)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("전체보기", style = Look.type.popupName, color = c.accent)
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .background(c.accent.copy(alpha = 0.12f), Look.shapes.pill)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) { Text("주행 기록", style = Look.type.popupName, color = c.accent) }
            // 보이는 원은 문서 그림처럼 작게, 실제 터치 영역은 84dp로 유지한다.
            Box(
                Modifier
                    .size(84.dp)
                    .semantics { contentDescription = "팝업 닫기" }
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier
                    .size(44.dp)
                    .background(c.raised, Look.shapes.pill)
                    .padding(11.dp)) {
                    val stroke = 3.dp.toPx()
                    drawLine(c.ink, Offset.Zero, Offset(size.width, size.height), stroke, StrokeCap.Round)
                    drawLine(c.ink, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
                }
            }
        }
        Box(Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(c.line))
        Spacer(Modifier.height(8.dp))
        DrivingStatusContent(state, onFinish, onAddPassenger, onSelectPassenger, onAddCost)
    }
}

@Composable
internal fun DrivingStatusContent(
    state: UiState,
    onFinish: () -> Unit,
    onAddPassenger: () -> Unit,
    onSelectPassenger: (Int) -> Unit,
    onAddCost: () -> Unit,
    showActions: Boolean = true,
    expanded: Boolean = false,
    actionsEnabled: Boolean = true,
) {
    val ride = state.live
    val settlement = state.settlement(null)
    val c = Look.colors
    var message by remember(ride?.id) { mutableStateOf<String?>(null) }
    var confirmFinish by remember(ride?.id) { mutableStateOf(false) }
    val parked = state.car.gear == Gear.PARK && !state.car.moving && !state.restricted
    LaunchedEffect(parked) { if (!parked) confirmFinish = false }
    Box(Modifier.fillMaxSize()) {
        if (ride == null || settlement == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("진행 중인 주행이 없어요", style = Look.type.body, color = c.muted)
            }
        } else if (message != null || confirmFinish) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Text(
                    message ?: "주행을 종료하고 정산 내역을 확인할까요?",
                    style = Look.type.body, color = c.ink,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PopupButton("돌아가기", c.raised, c.ink, Modifier.weight(1f)) {
                        message = null
                        confirmFinish = false
                    }
                    if (confirmFinish) PopupButton("종료 확인", c.ink, c.surface, Modifier.weight(1f)) {
                        if (parked) {
                            confirmFinish = false
                            onFinish()
                        }
                    }
                }
            }
        } else {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (expanded) 32.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(if (expanded) 32.dp else 16.dp),
            ) {
                Column(Modifier.width(if (expanded) 360.dp else 214.dp).then(if (expanded) Modifier.fillMaxHeight() else Modifier), verticalArrangement = if (expanded) Arrangement.SpaceEvenly else Arrangement.spacedBy(4.dp)) {
                    Metric("주행거리", Lines.km(settlement.km), c.ink, expanded)
                    Metric(if (ride.power == Power.FUEL) "유류비" else "충전비", Lines.won(settlement.fuelWon), c.gold, expanded)
                    Metric("톨비", Lines.won(settlement.tollWon), c.gold, expanded)
                }
                Column(Modifier.weight(1f)) {
                    val passengerCount = "탑승자 ${ride.riders.count { it.aboard } + 1}명"
                    Text(
                        if (showActions && actionsEnabled) "$passengerCount  (탭하여 하차 처리)" else passengerCount,
                        style = if (expanded) Look.type.body else Look.type.popupCaption, color = c.muted,
                    )
                    Spacer(Modifier.height(6.dp))
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(if (expanded) 16.dp else 6.dp)) {
                        RiderRow("나", "운전자 · 0km~", settlement.driver.finalWon, true, c.ink, c.surface, null, expanded = expanded)
                        val avatarColors = listOf(c.accent, c.action, c.success)
                        settlement.riders.forEachIndexed { index, share ->
                            val rider = ride.riders.lastOrNull {
                                it.aboard && ride.rootId(it.id) == share.riderId()
                            } ?: return@forEachIndexed
                            val startKm = ride.segments.firstOrNull()?.startKm ?: rider.boardedKm
                            val boardedKm = (rider.boardedKm - startKm).coerceAtLeast(0.0)
                            RiderRow(
                                share.person.name,
                                // UI 8: 누가 어디 앉았는지. 센서로 자리를 옮기면 여기도 바로 바뀐다.
                                "${rider.seat.short} · ${Lines.km(boardedKm)}~",
                                share.finalWon, true,
                                avatarColors[index % avatarColors.size],
                                if (index % avatarColors.size == 1) c.ink else c.surface,
                                if (showActions && actionsEnabled) ({ onSelectPassenger(rider.id) }) else null,
                                expanded = expanded,
                            )
                        }
                    }
                }
                if (showActions) Column(Modifier.width(if (expanded) 280.dp else 190.dp).then(if (expanded) Modifier.fillMaxHeight() else Modifier), verticalArrangement = if (expanded) Arrangement.spacedBy(24.dp, Alignment.CenterVertically) else Arrangement.spacedBy(12.dp)) {
                    PopupButton(if (expanded) "탑승자 추가" else "+ 탑승자", c.accent, c.onAccent, enabled = actionsEnabled, expanded = expanded, onClick = onAddPassenger)
                    Box {
                        PopupButton(if (expanded) "추가요금" else "비용 추가", c.raised, c.ink, enabled = actionsEnabled, expanded = expanded, onClick = onAddCost)
                        if (settlement.pendingTolls > 0) Box(
                            Modifier.align(Alignment.TopEnd)
                                .background(c.warn, Look.shapes.pill)
                                .semantics { contentDescription = "미입력 톨비 ${settlement.pendingTolls}건" }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(settlement.pendingTolls.toString(), style = Look.type.popupCaption, color = c.surface)
                        }
                    }
                    PopupButton("주행 종료", c.ink, c.surface, enabled = actionsEnabled, expanded = expanded) {
                        if (parked) confirmFinish = true else message = "주차 후 종료해 주세요. P단에서 종료할 수 있어요."
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color, expanded: Boolean = false) {
    Column {
        Text(label, style = if (expanded) Look.type.body else Look.type.popupCaption, color = Look.colors.muted)
        Text(value, style = if (expanded) Look.type.display else Look.type.popupValue, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun RiderRow(
    name: String, detail: String, won: Int?, aboard: Boolean,
    avatarColor: Color, onAvatar: Color, onClick: (() -> Unit)?,
    selected: Boolean = false,
    expanded: Boolean = false,
) {
    val c = Look.colors
    val foreground = if (aboard) c.ink else c.muted
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (expanded) 150.dp else 58.dp)
            .clip(Look.shapes.compact)
            .background(
                when {
                    selected -> c.accent.copy(alpha = 0.12f)
                    aboard -> c.surface
                    else -> c.raised
                },
            )
            .border(1.dp, if (selected) c.accent else c.line, Look.shapes.compact)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(if (expanded) 64.dp else 40.dp)
                .background(if (aboard) avatarColor else c.muted, Look.shapes.pill),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1), style = Look.type.popupCaption, color = if (aboard) onAvatar else c.surface)
        }
        Column(Modifier
            .weight(1f)
            .padding(horizontal = 8.dp)) {
            Text(name, style = if (expanded) Look.type.heading else Look.type.popupName, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = if (expanded) Look.type.caption else Look.type.popupCaption, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (won != null) Text(Lines.won(won), style = if (expanded) Look.type.heading else Look.type.popupName, color = if (aboard) c.gold else c.muted)
    }
}

@Composable
internal fun PopupButton(
    text: String, background: Color, foreground: Color,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    expanded: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(if (expanded) 150.dp else 84.dp)
            .clip(Look.shapes.compact)
            .background(if (enabled) background else background.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = if (expanded) Look.type.heading else Look.type.label, color = foreground) }
}
