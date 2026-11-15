// K-07 카풀 월별 정산 상세 및 청구
package com.nbbang.ui.carpool

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nbbang.RideViewModel
import com.nbbang.Screen
import com.nbbang.UiState
import com.nbbang.domain.Gear
import com.nbbang.domain.Lines
import com.nbbang.settle.MemberMonth
import com.nbbang.ui.AccentButton
import com.nbbang.ui.Look
import com.nbbang.ui.QuietButton
import com.nbbang.ui.settle.Badge
import com.nbbang.ui.settle.MoneyRow
import com.nbbang.ui.settle.SettleCard
import com.nbbang.ui.settle.SettleEmpty
import com.nbbang.ui.settle.signed
import com.nbbang.ui.settle.statusColor

@Composable
internal fun CarpoolMonthlyBillingScreen(state: UiState, vm: RideViewModel, screen: Screen.CarpoolMonthlyBilling) {
    val back = { vm.openCarpoolMonthly(screen.groupId, screen.month) }
    if (state.restricted || state.car.gear != Gear.PARK) {
        return SettleEmpty("주차(P) 후에 카풀 청구서를 만들 수 있어요", back)
    }
    val model = remember(screen.groupId, screen.month, state.groups, state.rides, state.charges, state.zone) {
        state.groupMonth(screen.groupId, screen.month)
    } ?: return SettleEmpty("카풀 정산을 찾을 수 없어요", back)
    val colors = Look.colors
    val targets = model.members.filter { it.won > 0 }
    val selected = targets.firstOrNull { it.name == screen.selectedName } ?: targets.firstOrNull()
    val hasPayee = !state.settings.payee.isEmpty
    // 톨비를 안 넣은 주행이 있으면 그 금액이 빠진 채 청구되니 먼저 넣게 한다(C-17과 같게).
    val canBill = hasPayee && model.pendingTolls == 0

    CarpoolFrame("${model.month.monthValue}월 정산 · ${model.group.name}", back) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("주행 ${model.rides.size}회", style = Look.type.label, color = colors.muted)
            Text("청구 예정 ${Lines.won(model.billableWon)} (탑승자 ${targets.size}명)", style = Look.type.heading, color = colors.gold)
        }
        if (selected == null) {
            Text("이 달에 청구할 탑승자 금액이 없어요.", style = Look.type.body, color = colors.muted)
            return@CarpoolFrame
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            LazyColumn(
                Modifier.width(340.dp).fillMaxHeight().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item { Text("청구 대상", style = Look.type.label, color = colors.muted) }
                items(targets, key = { it.name }) { target ->
                    BillingTarget(target.name, target.name == selected.name) {
                        vm.openCarpoolMonthlyBilling(screen.groupId, screen.month, target.name)
                    }
                }
            }
            MonthlyBreakdown(selected, Modifier.weight(1f).heightIn(max = 760.dp).fillMaxHeight(), vm::openCarpoolRideDetails)
            Column(
                Modifier.width(460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(36.dp),
            ) {
                SettleCard(Modifier.fillMaxWidth(), border = colors.action, background = colors.action.copy(alpha = 0.10f)) {
                    Text("청구서 (QR) 생성", style = Look.type.heading, color = colors.ink)
                    Text(
                        "${selected.name}님의 ${model.month.monthValue}월 청구서를 QR로 만들어요.",
                        style = Look.type.caption,
                        color = colors.muted,
                    )
                    AccentButton(
                        "청구서 생성", Modifier.align(Alignment.CenterHorizontally).width(380.dp),
                        enabled = canBill, prominent = true,
                    ) { vm.makeMonthlyInvoices(screen, listOf(selected.name)) }
                    if (model.pendingTolls > 0) {
                        Text(
                            "톨비를 안 넣은 주행이 있어요(${model.pendingTolls}건). 주행 내역에서 톨비를 넣은 뒤 청구해 주세요.",
                            style = Look.type.caption, color = colors.warn,
                        )
                    }
                }
                BatchInvoiceButton(canBill) { vm.makeMonthlyInvoices(screen, targets.map { it.name }) }
                SettleCard(Modifier.fillMaxWidth(), border = colors.raised, background = colors.raised) {
                    Text("청구 상태", style = Look.type.label, color = colors.muted)
                    val status = selected.charge?.status(System.currentTimeMillis())
                    if (status == null) {
                        Badge("미청구", colors.surface, colors.muted)
                        Text("청구서를 생성하면 '청구됨'으로 바뀌어요.", style = Look.type.caption, color = colors.muted)
                    } else if (selected.unbilledExtraWon > 0) {
                        Badge("추가분 미청구", colors.surface, colors.muted)
                        Text("청구한 뒤 ${Lines.won(selected.unbilledExtraWon)}이 더해졌어요. 청구서를 다시 만들면 받은 금액을 뺀 나머지만 청구돼요.", style = Look.type.caption, color = colors.muted)
                    } else {
                        Badge(status.label, statusColor(status))
                    }
                }
                if (!hasPayee) {
                    Text("청구서를 만들려면 정산 계좌를 등록해 주세요.", style = Look.type.caption, color = colors.warn)
                    QuietButton("설정에서 계좌 등록", Modifier.fillMaxWidth(), onClick = vm::openSettings)
                }
            }
        }
    }
}

@Composable
private fun BillingTarget(name: String, selected: Boolean, onSelect: () -> Unit) {
    val colors = Look.colors
    val shape = Look.shapes.card
    Box(
        Modifier.fillMaxWidth().height(100.dp).clip(shape)
            .background(if (selected) colors.accent.copy(alpha = 0.10f) else colors.surface)
            .border(if (selected) 5.dp else 3.dp, if (selected) colors.accent else colors.line, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 36.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            name, style = Look.type.heading, color = if (selected) colors.accent else colors.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MonthlyBreakdown(member: MemberMonth, modifier: Modifier, onRide: (String) -> Unit) {
    val colors = Look.colors
    SettleCard(modifier) {
        Text("${member.name}님 청구 내역", style = Look.type.heading, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        HorizontalDivider(color = colors.line)
        key(member.name) {
            LazyColumn(Modifier.weight(1f)) {
                items(member.lines) { line ->
                    MoneyRow(line.title, Lines.won(line.wonBeforeSaving), wonColor = colors.gold) { onRide(line.rideId) }
                }
            }
        }
        HorizontalDivider(color = colors.line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("절약 기여 인정 (평균 연비 기준)", style = Look.type.label, color = colors.success)
            Text(signed(member.savingWon), style = Look.type.label, color = colors.success)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("월 합계", style = Look.type.heading, color = colors.ink)
            Text(Lines.won(member.won), style = Look.type.title, color = colors.gold)
        }
    }
}

@Composable
private fun BatchInvoiceButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = Look.colors
    val shape = Look.shapes.button
    Box(
        Modifier.fillMaxWidth().height(100.dp).clip(shape).background(colors.surface)
            .border(3.dp, if (enabled) colors.accent else colors.line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("전체 구성원 일괄 생성", style = Look.type.heading, color = if (enabled) colors.accent else colors.muted)
    }
}
