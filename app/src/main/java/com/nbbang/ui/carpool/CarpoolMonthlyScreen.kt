// K-06 카풀 월별 정산 현황
package com.nbbang.ui.carpool

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nbbang.RideViewModel
import com.nbbang.Screen
import com.nbbang.UiState
import com.nbbang.domain.Charge
import com.nbbang.domain.Gear
import com.nbbang.domain.Lines
import com.nbbang.domain.Ride
import com.nbbang.settle.GroupMonth
import com.nbbang.settle.MemberMonth
import com.nbbang.settle.Settlement
import com.nbbang.settle.previousUnpaid
import com.nbbang.settle.settlement
import com.nbbang.ui.AccentButton
import com.nbbang.ui.Look
import com.nbbang.ui.settle.Badge
import com.nbbang.ui.settle.SettleCard
import com.nbbang.ui.settle.SettleEmpty
import com.nbbang.ui.settle.statusColor
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun CarpoolMonthlyScreen(state: UiState, vm: RideViewModel, screen: Screen.CarpoolMonthly) {
    if (state.restricted || state.car.gear != Gear.PARK) {
        return SettleEmpty("주차(P) 후에 카풀 정산을 볼 수 있어요", vm::goHome)
    }
    val group = state.group(screen.groupId) ?: state.groups.firstOrNull()
        ?: return SettleEmpty("등록된 카풀 그룹이 없어요", vm::goHome)
    val now = System.currentTimeMillis()
    val month = screen.month ?: group.monthOf(now, state.zone)
    val model = remember(group, month, state.rides, state.charges, state.zone) { state.groupMonth(group.id, month) }
        ?: return SettleEmpty("카풀 정산을 찾을 수 없어요", vm::goHome)
    val overdue = group.previousUnpaid(month, state.charges, now).sortedBy { it.dueAt }
    val savedRides = remember(model.rides) { model.rides.asReversed().map { it to it.settlement() } }
    val colors = Look.colors

    CarpoolFrame("카풀 정산", vm::goHome) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(74.dp)) {
            Column(Modifier.weight(520f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                MonthPicker(month) { vm.openCarpoolMonthly(group.id, it) }
                var expanded by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier.fillMaxWidth()
                            .height(76.dp)
                            .clip(Look.shapes.pill)
                            .background(colors.accent.copy(alpha = 0.10f))
                            .clickable(role = Role.Button) { expanded = true }
                            .padding(horizontal = 32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(group.name, style = Look.type.label, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text("⌄", style = Look.type.label, color = colors.accent)
                    }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                        state.groups.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.name, style = Look.type.label, color = colors.ink) },
                                onClick = {
                                    expanded = false
                                    vm.openCarpoolMonthly(item.id, screen.month)
                                },
                            )
                        }
                    }
                }
                if (group.settleDay != com.nbbang.settle.CarpoolGroup.LAST_DAY) {
                    Text(group.periodLabel(month), style = Look.type.caption, color = colors.muted)
                }
                MonthlyTotals(model, month == group.monthOf(now, state.zone))
                Text("구성원별 누적", style = Look.type.label, color = colors.muted)
                key(group.id, month) {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(model.members, key = { it.name }) { member -> MemberBalanceRow(member, now) }
                    }
                }
            }
            Column(Modifier.weight(978f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (overdue.isNotEmpty()) {
                    OverdueBanner(month.minusMonths(1), overdue, now, state.zone) {
                        vm.openCarpoolOverdue(overdue.first().id, Screen.CarpoolMonthly(group.id, month))
                    }
                }
                Text("저장된 주행 내역 (${model.rides.size}건)", style = Look.type.label, color = colors.muted)
                key(group.id, month) {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (savedRides.isEmpty()) {
                            item { Text("이 달에 월별 정산으로 저장된 주행이 없어요.", style = Look.type.body, color = colors.muted) }
                        }
                        items(savedRides, key = { it.first.id }) { (ride, settlement) ->
                            MonthlyRideRow(ride, settlement, state.zone) { vm.openCarpoolRideDetails(ride.id) }
                        }
                    }
                }
                AccentButton(
                    "${month.monthValue}월 정산하기",
                    Modifier.align(Alignment.End).width(600.dp),
                    enabled = model.rides.isNotEmpty(),
                ) { vm.openCarpoolMonthlyBilling(group.id, month) }
            }
        }
    }
}

@Composable
private fun MonthPicker(month: YearMonth, onSelect: (YearMonth) -> Unit) {
    val colors = Look.colors
    Row(
        Modifier.fillMaxWidth().height(76.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonthArrow("〈", "이전 달") { onSelect(month.minusMonths(1)) }
        Text("${month.year}년 ${month.monthValue}월", style = Look.type.title, color = colors.ink)
        MonthArrow("〉", "다음 달") { onSelect(month.plusMonths(1)) }
    }
}

@Composable
private fun MonthArrow(mark: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(76.dp).clip(Look.shapes.compact)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(mark, style = Look.type.title, color = Look.colors.ink)
    }
}

@Composable
private fun MonthlyTotals(model: GroupMonth, isCurrent: Boolean) {
    val colors = Look.colors
    val label = if (isCurrent) "이번 달 청구 예정" else "${model.month.monthValue}월 청구 예정"
    val riders = model.members.count { it.lines.isNotEmpty() }
    SettleCard(Modifier.fillMaxWidth()) {
        Text("$label (탑승자 ${riders}명)", style = Look.type.caption, color = colors.muted)
        Text(Lines.won(model.billableWon), style = Look.type.display, color = colors.gold)
        Text(
            "주행 ${model.rides.size}회 · ${Lines.km(model.km)} · 총 비용 ${Lines.won(model.totalCostWon)}",
            style = Look.type.caption,
            color = colors.muted,
        )
        Text("인정된 절약 기여 ${Lines.won(model.savingWon)}", style = Look.type.label, color = colors.success)
    }
}

@Composable
private fun MemberBalanceRow(member: MemberMonth, now: Long) {
    val colors = Look.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 90.dp)
            .background(colors.surface, Look.shapes.card)
            .border(3.dp, colors.line, Look.shapes.card)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(member.name, style = Look.type.label, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(Lines.won(member.won), style = Look.type.label, color = colors.gold)
        val status = member.charge?.status(now)
        when {
            status == null -> Badge("미청구", colors.raised, colors.muted)
            // 청구한 뒤 주행이 더해졌다. 다시 청구하면 차액만 나간다.
            member.unbilledExtraWon > 0 -> Badge("추가분 미청구", colors.raised, colors.muted)
            else -> Badge(status.label, statusColor(status))
        }
    }
}

@Composable
private fun OverdueBanner(month: YearMonth, charges: List<Charge>, now: Long, zone: ZoneId, onOpen: () -> Unit) {
    val colors = Look.colors
    val charge = charges.first()
    val shape = Look.shapes.card
    Row(
        Modifier.fillMaxWidth().heightIn(min = 90.dp)
            .background(colors.warn.copy(alpha = 0.08f), shape)
            .border(3.dp, colors.warn, shape)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${month.monthValue}월 미납 ${charges.size}건", style = Look.type.label, color = colors.warn)
        Text(
            "${charge.name} ${Lines.won(charge.won)} (D+${charge.overdueDays(now, zone)})",
            modifier = Modifier.weight(1f),
            style = Look.type.caption,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier.size(170.dp, 56.dp).clip(Look.shapes.input).background(colors.warn)
                .clickable(role = Role.Button, onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            Text("확인", style = Look.type.popupName, color = colors.onAccent)
        }
    }
}

@Composable
private fun MonthlyRideRow(ride: Ride, settlement: Settlement, zone: ZoneId, onOpen: () -> Unit) {
    val colors = Look.colors
    val whenStarted = rideDate.format(Instant.ofEpochMilli(ride.startedAt).atZone(zone))
    val shares = settlement.shares.filter { it.optedIn }.map { it.finalWon }
    val least = shares.minOrNull()
    val most = shares.maxOrNull()
    val perPerson = when {
        least == null || most == null -> "분담 없음"
        least == most -> "1인 ${Lines.won(least)}"
        else -> "1인 ${Lines.won(least)}~${Lines.won(most)}"
    }
    val shape = Look.shapes.card
    Row(
        Modifier.fillMaxWidth().heightIn(min = 100.dp)
            .clip(shape).background(colors.surface).border(3.dp, colors.line, shape)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 28.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$whenStarted ${ride.destination?.takeIf { it.isNotBlank() } ?: "카풀"}",
            style = Look.type.label,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(Lines.km(settlement.km), style = Look.type.caption, color = colors.muted)
        Text(perPerson, style = Look.type.caption, color = colors.muted)
        Text(Lines.won(settlement.costWon), style = Look.type.label, color = colors.gold)
        Text("›", style = Look.type.label, color = colors.muted)
    }
}

private val rideDate = DateTimeFormatter.ofPattern("MM/dd (E)", Locale.KOREAN)
