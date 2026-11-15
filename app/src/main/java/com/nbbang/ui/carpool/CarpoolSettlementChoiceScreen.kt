// K-05 카풀 주행 저장 후 정산 방식 선택
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nbbang.RideViewModel
import com.nbbang.UiState
import com.nbbang.domain.Gear
import com.nbbang.ui.Look
import com.nbbang.ui.home.HomeScreen
import com.nbbang.ui.settle.SettleEmpty

@Composable
internal fun CarpoolSettlementChoiceScreen(state: UiState, vm: RideViewModel, rideId: String) {
    val ride = state.ride(rideId) ?: return SettleEmpty("저장된 카풀 주행이 없어요", vm::goHome)
    if (state.car.gear != Gear.PARK || state.restricted) {
        return SettleEmpty("주차(P) 후에 카풀 정산 방식을 선택할 수 있어요", vm::goHome)
    }
    val colors = Look.colors
    val group = state.group(ride.groupId)
    val month = group?.monthOf(ride.startedAt, state.zone)
    val previousCount = remember(rideId, group, month, state.rides, state.zone) {
        if (group != null && month != null) {
            state.groupMonth(group.id, month)?.rides?.count { it.id != rideId } ?: 0
        } else 0
    }
    var monthly by rememberSaveable(rideId, group != null) { mutableStateOf(group != null) }
    HomeScreen(state, vm)
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier.size(width = 850.dp, height = 400.dp)
                .clip(Look.shapes.popup)
                .background(colors.surface)
                .border(4.dp, colors.ink, Look.shapes.popup)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(40.dp).background(colors.accent, Look.shapes.pill),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("N", style = Look.type.popupName, color = colors.onAccent)
                }
                Text("카풀 주행이 저장됐어요", style = Look.type.popupTitle, color = colors.ink)
            }
            HorizontalDivider(color = colors.line)
            Text(
                "이 주행을 어떻게 정산할까요?",
                Modifier.align(Alignment.CenterHorizontally),
                style = Look.type.label,
                color = colors.ink,
            )
            Row(
                Modifier.weight(1f).fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SettlementOption(
                    title = "월별 정산에 추가",
                    detail = if (group != null && month != null) "${group.name} · ${month.monthValue}월 누적" else "등록된 카풀 그룹이 없어요",
                    highlight = if (group != null) "${previousCount}회 → ${previousCount + 1}회" else null,
                    selected = monthly,
                    modifier = Modifier.weight(1f),
                    enabled = group != null,
                ) { monthly = true }
                SettlementOption(
                    "오늘 건 따로 결제",
                    "지금 정산하고",
                    "QR 청구서 생성",
                    !monthly,
                    Modifier.weight(1f),
                ) { monthly = false }
            }
            Box(
                Modifier.fillMaxWidth().height(70.dp)
                    .clip(Look.shapes.button)
                    .background(colors.accent)
                    .clickable(role = Role.Button) { vm.chooseCarpoolSettlement(rideId, monthly) },
                contentAlignment = Alignment.Center,
            ) {
                Text("확인", style = Look.type.popupTitle, color = colors.onAccent)
            }
        }
    }
}

@Composable
private fun SettlementOption(
    title: String,
    detail: String,
    highlight: String?,
    selected: Boolean,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = Look.colors
    val shape = Look.shapes.card
    Column(
        modifier.fillMaxHeight()
            .clip(shape)
            .background(if (selected) colors.accent.copy(alpha = 0.10f) else colors.surface)
            .border(if (selected) 4.dp else 3.dp, if (selected) colors.accent else colors.line, shape)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(30.dp).border(3.dp, if (enabled) colors.accent else colors.line, Look.shapes.pill),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(Modifier.size(14.dp).background(colors.accent, Look.shapes.pill))
            }
            Text(title, style = Look.type.label, color = if (enabled) colors.ink else colors.muted)
        }
        Column {
            Text(detail, style = Look.type.popupCaption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            highlight?.let { Text(it, style = Look.type.popupName, color = if (selected) colors.accent else colors.ink) }
        }
    }
}
