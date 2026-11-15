// K-03 주행 준비 (오늘 탑승자 확인)
package com.nbbang.ui.carpool

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nbbang.RideViewModel
import com.nbbang.Screen
import com.nbbang.UiState
import com.nbbang.domain.Lines
import com.nbbang.domain.Power
import com.nbbang.domain.Seat
import com.nbbang.domain.SeatPicker
import com.nbbang.settle.priceSource
import com.nbbang.ui.AccentButton
import com.nbbang.ui.Look
import com.nbbang.ui.driving.DrivingOverlay
import java.util.Locale

@Composable
internal fun CarpoolPrepareScreen(state: UiState, vm: RideViewModel, screen: Screen.CarpoolPrepare) {
    val colors = Look.colors
    val context = LocalContext.current
    val group = state.group(screen.groupId)
    val onBack = { if (state.canSetUpCarpool) vm.openCarpoolGroups(screen.groupId) else vm.goHome() }

    CarpoolFrame("주행 준비", onBack) {
        if (group == null || !state.canSetUpCarpool) {
            Text("주차한 상태에서 카풀을 준비해 주세요.", style = Look.type.body, color = colors.ink)
            return@CarpoolFrame
        }

        val capacity = Seat.passengers.size
        var today by rememberSaveable(group.id, group.members) { mutableStateOf(ArrayList(group.members.take(capacity))) }
        // UI 8: 누가 어디 앉는지. 체크하면 창가부터 자리를 받고, 줄의 자리 버튼이나 오른쪽 자리 그림에서 바꾼다.
        var seats by rememberSaveable(group.id, group.members) {
            mutableStateOf(HashMap(SeatPicker.fill(group.members.take(capacity), emptyMap())))
        }
        val avatarColors = listOf(colors.accent, colors.action, colors.success)
        val colorOf = { name: String -> avatarColors[group.members.indexOf(name).coerceAtLeast(0) % avatarColors.size] }
        val setToday = { names: List<String> ->
            today = ArrayList(names)
            seats = HashMap(SeatPicker.fill(names, seats))
        }
        val move = { name: String, seat: Seat -> seats = HashMap(SeatPicker.move(seats, name, seat)) }

        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(74.dp),
        ) {
            Column(Modifier.weight(700f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "카풀 · ${group.name}",
                        Modifier.weight(1f, fill = false)
                            .background(colors.accent.copy(alpha = 0.10f), Look.shapes.pill)
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        style = Look.type.label,
                        color = colors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        Modifier.size(width = 200.dp, height = 64.dp)
                            .clip(Look.shapes.input)
                            .background(colors.raised)
                            .clickable(role = Role.Button, onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("그룹 변경", style = Look.type.label, color = colors.ink)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text("오늘 탑승자 (체크한 인원이 0km부터 N빵에 포함)", style = Look.type.label, color = colors.muted)
                if (group.members.size > capacity) {
                    Text("동승자는 최대 ${capacity}명까지 선택할 수 있어요.", style = Look.type.caption, color = colors.muted)
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    itemsIndexed(group.members, key = { _, member -> member }) { _, member ->
                        val checked = member in today
                        PassengerRow(
                            member, checked, checked || today.size < capacity, colorOf(member),
                            seat = seats[member].takeIf { checked },
                            seatOwner = { seat -> seats.entries.firstOrNull { it.value == seat }?.key },
                            onSeat = { move(member, it) },
                        ) {
                            setToday(if (it) today + member else today - member)
                        }
                    }
                }
            }

            Column(
                Modifier.weight(800f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                SeatMapCard(seats.filterKeys { it in today }, today, colorOf, move)
                VehiclePriceCard(state)
                Column(
                    Modifier.fillMaxWidth()
                        .background(colors.action.copy(alpha = 0.14f), Look.shapes.card)
                        .border(3.dp, colors.action, Look.shapes.card)
                        .padding(40.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("주행을 시작하면", style = Look.type.heading, color = colors.accentText)
                    listOf(
                        "내비 위에 주행 현황을 띄워요.",
                        "주행거리는 D단부터 기록해요.",
                        "가는 길에 타고 내리면 주행 현황에서 눌러요.",
                        "하차와 하이패스는 팝업에서 확인해요.",
                    ).forEach { Text("· $it", style = Look.type.label, color = colors.ink, maxLines = 1) }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        val action = Modifier.align(Alignment.End).width(600.dp)
        val noAccount = state.settings.payee.account.isBlank()
        if (noAccount || !state.hasCarpoolVehicleSettings) {
            val message = if (noAccount) "출발 전에 정산 계좌를 등록해 주세요." else "출발 전에 차량 정보를 확인해 주세요."
            val button = if (noAccount) "설정에서 계좌 등록" else "차량 설정"
            Text(message, style = Look.type.label, color = colors.warn)
            Spacer(Modifier.height(12.dp))
            AccentButton(button, action, onClick = vm::openSettings)
        } else {
            AccentButton("주행 시작", action, enabled = today.isNotEmpty(), prominent = true) {
                if (vm.confirmCarpoolPreparation(group.id, today, seats) && DrivingOverlay.open(context)) {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_APP_MAPS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: ActivityNotFoundException) {
                        (context as? Activity)?.moveTaskToBack(true)
                    }
                }
            }
        }
    }
}

@Composable
private fun PassengerRow(
    name: String,
    checked: Boolean,
    enabled: Boolean,
    avatarColor: Color,
    seat: Seat?,
    seatOwner: (Seat) -> String?,
    onSeat: (Seat) -> Unit,
    onCheck: (Boolean) -> Unit,
) {
    val colors = Look.colors
    val shape = Look.shapes.card
    Row(
        Modifier.fillMaxWidth()
            .height(108.dp)
            .clip(shape)
            .background(colors.surface)
            .border(3.dp, colors.line, shape)
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheck)
            .padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val checkboxShape = Look.shapes.compact
        Box(
            Modifier.size(48.dp)
                .background(if (checked) colors.accent else colors.surface, checkboxShape)
                .border(3.dp, colors.accent, checkboxShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Text("✓", style = Look.type.label, color = colors.onAccent)
        }
        Box(Modifier.size(64.dp).background(avatarColor, Look.shapes.pill), contentAlignment = Alignment.Center) {
            Text(name.take(1), style = Look.type.label, color = if (avatarColor == colors.action) colors.ink else colors.onAccent)
        }
        Text(
            name,
            Modifier.weight(1f),
            style = Look.type.heading,
            color = if (checked) colors.ink else colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!checked) Text("오늘 미탑승", style = Look.type.caption, color = colors.muted)
        if (seat != null) SeatButton(name, seat, seatOwner, onSeat)
    }
}

/** 줄 끝의 자리 버튼. 누르면 자리 목록이 열리고, 다른 사람이 앉은 자리를 고르면 서로 바꾼다. */
@Composable
private fun SeatButton(name: String, seat: Seat, seatOwner: (Seat) -> String?, onSeat: (Seat) -> Unit) {
    val colors = Look.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(64.dp)
                .clip(Look.shapes.pill)
                .background(colors.accent.copy(alpha = 0.10f))
                .clickable(role = Role.DropdownList, onClickLabel = "자리 바꾸기") { open = true }
                .semantics { contentDescription = "$name 자리 ${seat.label}" }
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(seat.short, style = Look.type.label, color = colors.accent, maxLines = 1)
            Text("⌄", style = Look.type.label, color = colors.accent)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Seat.passengers.forEach { choice ->
                val owner = seatOwner(choice)
                DropdownMenuItem(
                    text = {
                        val note = when {
                            choice == seat -> "지금 자리"
                            owner != null -> "$owner 님과 바꾸기"
                            else -> null
                        }
                        MenuText(choice.label, note, if (choice == seat) colors.accent else colors.ink)
                    },
                    onClick = {
                        open = false
                        onSeat(choice)
                    },
                    modifier = Modifier.height(MENU_ITEM),
                    contentPadding = MENU_PADDING,
                )
            }
        }
    }
}

/** 자리 메뉴 한 줄. 메뉴 폭이 좁아서 설명은 아래 줄에 둔다. */
@Composable
private fun MenuText(title: String, note: String?, color: Color) {
    Column {
        Text(title, style = Look.type.label, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (note != null) {
            Text(note, style = Look.type.caption, color = if (color == Look.colors.accent) color else Look.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 차 안에서 누르기 쉽게 자리 메뉴 한 줄을 크게. */
private val MENU_ITEM = 76.dp
private val MENU_PADDING = PaddingValues(horizontal = 28.dp)

/** 위에서 본 차(앞이 위, 운전석이 왼쪽). 3열은 누가 앉을 때만 그린다. */
private val CarBody = RoundedCornerShape(topStart = 80.dp, topEnd = 80.dp, bottomStart = 36.dp, bottomEnd = 36.dp)

@Composable
private fun SeatMapCard(seats: Map<String, Seat>, order: List<String>, colorOf: (String) -> Color, onMove: (String, Seat) -> Unit) {
    val colors = Look.colors
    val owners = seats.entries.associate { (name, seat) -> seat to name }
    val rows = listOfNotNull(
        listOf(Seat.DRIVER, null, Seat.FRONT),
        listOf(Seat.REAR_LEFT, Seat.REAR_CENTER, Seat.REAR_RIGHT),
        listOf(Seat.THIRD_LEFT, null, Seat.THIRD_RIGHT).takeIf { Seat.THIRD_LEFT in owners || Seat.THIRD_RIGHT in owners },
    )
    Column(
        Modifier.fillMaxWidth()
            .background(colors.surface, Look.shapes.card)
            .border(3.dp, colors.line, Look.shapes.card)
            .padding(horizontal = 32.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("오늘 자리", style = Look.type.label, color = colors.ink)
            Text("자리를 누르면 바꿀 수 있어요", style = Look.type.caption, color = colors.muted)
        }
        Column(
            Modifier.fillMaxWidth()
                .background(colors.raised, CarBody)
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("앞", Modifier.align(Alignment.CenterHorizontally), style = Look.type.caption, color = colors.muted)
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { seat ->
                        val cell = Modifier.weight(1f)
                        if (seat == null) {
                            Spacer(cell)
                        } else {
                            val owner = owners[seat]
                            SeatTile(seat, owner, owner?.let(colorOf), order.filter { it != owner }, seats, cell) { onMove(it, seat) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeatTile(
    seat: Seat,
    owner: String?,
    ownerColor: Color?,
    choices: List<String>,
    seats: Map<String, Seat>,
    modifier: Modifier,
    onPick: (String) -> Unit,
) {
    val colors = Look.colors
    val shape = Look.shapes.compact
    val driver = seat == Seat.DRIVER
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth()
                .height(80.dp)
                .clip(shape)
                .background(if (driver) colors.ink else colors.surface)
                .border(
                    if (owner != null || driver) 3.dp else 2.dp,
                    when {
                        driver -> colors.ink
                        ownerColor != null -> ownerColor
                        else -> colors.line
                    },
                    shape,
                )
                .then(
                    if (!driver && choices.isNotEmpty()) {
                        Modifier.clickable(role = Role.Button, onClickLabel = "${seat.label}에 앉힐 사람 고르기") { open = true }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val avatar = Modifier.size(44.dp)
            when {
                driver -> SteeringWheel(colors.surface, avatar)
                owner != null && ownerColor != null -> Box(avatar.background(ownerColor, Look.shapes.pill), contentAlignment = Alignment.Center) {
                    Text(owner.take(1), style = Look.type.caption, color = if (ownerColor == colors.action) colors.ink else colors.onAccent)
                }
                else -> Box(avatar.border(2.dp, colors.line, Look.shapes.pill), contentAlignment = Alignment.Center) {
                    Text("+", style = Look.type.label, color = colors.muted)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        driver -> "나"
                        owner != null -> owner
                        else -> "빈 자리"
                    },
                    style = Look.type.label,
                    color = when {
                        driver -> colors.surface
                        owner != null -> colors.ink
                        else -> colors.muted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(seat.short, style = Look.type.caption, color = if (driver) colors.surface.copy(alpha = 0.7f) else colors.muted, maxLines = 1)
            }
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            choices.forEach { name ->
                DropdownMenuItem(
                    text = { MenuText(name, seats[name]?.let { "지금 ${it.short}" }, colors.ink) },
                    onClick = {
                        open = false
                        onPick(name)
                    },
                    modifier = Modifier.height(MENU_ITEM),
                    contentPadding = MENU_PADDING,
                )
            }
        }
    }
}

@Composable
private fun VehiclePriceCard(state: UiState) {
    val colors = Look.colors
    val settings = state.settings
    val electric = settings.power == Power.ELECTRIC
    val prices = state.ridePrices
    val unit = if (electric) "kWh" else "L"
    val efficiency = if (electric) prices.kmPerKwh else prices.kmPerLiter
    val price = if (electric) prices.wonPerKwh else prices.wonPerLiter
    val priceLabel = when {
        electric -> "충전 단가"
        prices.officialDate != null -> "${prices.priceSource(settings.power)} 유가"
        else -> "설정 유가"
    }
    val model = prices.carModel.takeIf { !electric }
    val vehicle = if (model != null) {
        // 차종을 골랐으면 평균 연비 대신 공인연비(도심·고속)로 주행마다 기준연비를 정한다.
        "${settings.fuelKind} · ${model.label} 공인연비 도심 ${Lines.plain(model.cityKmPerLiter)} · 고속 ${Lines.plain(model.highwayKmPerLiter)} km/L"
    } else if (efficiency.isFinite() && efficiency > 0 && settings.fuelKind.isNotBlank()) {
        val formatted = String.format(Locale.KOREA, "%.1f", efficiency)
        "${settings.fuelKind} · 평균 $formatted km/$unit"
    } else {
        "차량 설정 필요"
    }
    val priceText = if (price > 0) "${Lines.won(price)} / $unit" else "설정 필요"

    Column(
        Modifier.fillMaxWidth().background(colors.raised, Look.shapes.card).padding(horizontal = 40.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("내 차량", style = Look.type.label, color = colors.muted)
            Text(vehicle, style = Look.type.body, color = colors.ink)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(priceLabel, style = Look.type.label, color = colors.muted)
            Text(priceText, style = Look.type.body, color = colors.ink)
        }
        Text("단가는 최종 정산 화면에서 수정할 수 있어요.", style = Look.type.caption, color = colors.muted)
    }
}

/** 운전석 표시용 핸들. 테두리 원과 가운데, 세 갈래 살. */
@Composable
private fun SteeringWheel(color: Color, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "운전석" }) {
        val stroke = size.minDimension * 0.09f
        val radius = size.minDimension / 2 - stroke
        val hub = radius * 0.28f
        drawCircle(color, radius, style = Stroke(stroke))
        drawCircle(color, hub)
        listOf(Offset(-radius, 0f), Offset(radius, 0f), Offset(0f, radius)).forEach { end ->
            drawLine(color, center, center + end, strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}
