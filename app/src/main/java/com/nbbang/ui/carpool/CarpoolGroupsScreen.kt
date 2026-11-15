// K-01 그룹 선택
package com.nbbang.ui.carpool

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nbbang.RideViewModel
import com.nbbang.Screen
import com.nbbang.UiState
import com.nbbang.domain.Lines
import com.nbbang.settle.CarpoolGroup
import com.nbbang.settle.GroupMonth
import com.nbbang.ui.AccentButton
import com.nbbang.ui.Look
import com.nbbang.ui.settle.SettleHeader

@Composable
internal fun CarpoolGroupsScreen(state: UiState, vm: RideViewModel, screen: Screen.CarpoolGroups) {
    val colors = Look.colors
    val selected = state.group(screen.selectedId)
    val now = System.currentTimeMillis()
    val months = state.groups.associate { it.id to it.monthOf(now, state.zone) }
    val totals = remember(months, state.groups, state.rides, state.charges, state.zone) {
        months.mapValues { (id, month) -> state.groupMonth(id, month) }
    }

    CarpoolFrame("카풀 시작", vm::goHome) {
        Text("정산할 그룹을 선택하세요", style = Look.type.title, color = colors.ink)
        Text("카풀은 월별로 청구돼요. 그룹을 먼저 정해주세요.", style = Look.type.label, color = colors.muted)
        Spacer(Modifier.height(28.dp))

        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(56.dp),
        ) {
            Column(Modifier.weight(900f)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f, fill = false).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    items(state.groups, key = { it.id }) { group ->
                        GroupCard(group, totals[group.id]?.billableWon ?: 0, group == selected, state.canSetUpCarpool) {
                            vm.openCarpoolGroups(group.id)
                        }
                    }
                    item { AddGroupCard(state.canSetUpCarpool) { vm.editCarpoolGroup(null, screen.selectedId) } }
                }
                Spacer(Modifier.height(56.dp))
                val canEdit = selected != null && state.canSetUpCarpool
                Box(
                    Modifier
                        .fillMaxWidth(0.489f)
                        .height(92.dp)
                        .clip(Look.shapes.card)
                        .background(colors.surface)
                        .border(3.dp, if (canEdit) colors.accent else colors.line, Look.shapes.card)
                        .clickable(enabled = canEdit, role = Role.Button) {
                            selected?.let { vm.editCarpoolGroup(it.id, it.id) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("그룹 편집", style = Look.type.heading, color = if (canEdit) colors.accent else colors.muted)
                }
            }
            Box(Modifier.weight(616f)) {
                if (selected != null) GroupPreview(selected, totals[selected.id])
            }
        }

        Spacer(Modifier.height(20.dp))
        AccentButton(
            "다음",
            Modifier.align(Alignment.End).width(600.dp),
            enabled = selected != null && state.canSetUpCarpool,
        ) { selected?.let { vm.openCarpoolPreparation(it.id) } }
    }
}

@Composable
private fun GroupCard(group: CarpoolGroup, won: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = Look.colors
    val shape = Look.shapes.card
    Column(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(shape)
            .background(if (selected) colors.accent.copy(alpha = 0.12f).compositeOver(colors.surface) else colors.surface)
            .border(if (selected) 5.dp else 3.dp, if (selected) colors.accent else colors.line, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(48.dp).border(3.dp, colors.accent, Look.shapes.pill),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(Modifier.size(28.dp).background(colors.accent, Look.shapes.pill))
            }
            Text(group.name, style = Look.type.heading, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(18.dp))
        val closing = if (group.settleDay == CarpoolGroup.LAST_DAY) "말일" else "${group.settleDay}일"
        Text("${group.members.size + 1}명 · 매월 ${closing} 정산", style = Look.type.caption, color = colors.muted)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("이번 달 누적", style = Look.type.caption, color = colors.muted)
            Text(Lines.won(won), style = Look.type.heading, color = colors.accentText, maxLines = 1)
        }
    }
}

@Composable
private fun AddGroupCard(enabled: Boolean, onClick: () -> Unit) {
    val colors = Look.colors
    val shape = Look.shapes.card
    Column(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(shape)
            .background(colors.surface)
            .carpoolDashedBorder(colors.accent, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(76.dp).background(colors.accent.copy(alpha = 0.12f), Look.shapes.pill),
            contentAlignment = Alignment.Center,
        ) { Text("+", style = Look.type.title, color = colors.accent) }
        Text("새 그룹 추가", style = Look.type.heading, color = colors.accent)
    }
}

internal fun Modifier.carpoolDashedBorder(color: Color, shape: Shape): Modifier = drawBehind {
    drawOutline(
        shape.createOutline(size, layoutDirection, this),
        color,
        style = Stroke(width = 6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 10.dp.toPx()))),
    )
}

@Composable
private fun GroupPreview(group: CarpoolGroup, month: GroupMonth?) {
    val colors = Look.colors
    val shape = Look.shapes.card
    val avatarColors = listOf(colors.accent, colors.action, colors.success)
    Column(
        Modifier
            .fillMaxWidth()
            .height(468.dp)
            .background(colors.surface, shape)
            .border(3.dp, colors.line, shape)
            .padding(32.dp),
    ) {
        Text("선택한 그룹", style = Look.type.caption, color = colors.muted)
        Text(group.name, style = Look.type.title, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(34.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            group.members.forEachIndexed { index, name ->
                val color = avatarColors[index % avatarColors.size]
                GroupAvatar(name, color, if (color == colors.action) colors.ink else colors.onAccent)
            }
            GroupAvatar("나", colors.ink, colors.surface)
        }
        Spacer(Modifier.height(26.dp))
        Text("구성원 ${group.members.size + 1}명 (운전자 포함)", style = Look.type.caption, color = colors.muted)
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(thickness = 3.dp, color = colors.line)
        Spacer(Modifier.height(24.dp))
        Text("이번 달 주행 ${month?.rides?.size ?: 0}회", style = Look.type.label, color = colors.ink)
        Text("청구 예정 ${Lines.won(month?.billableWon ?: 0)}", style = Look.type.body, color = colors.accentText)
    }
}

@Composable
private fun GroupAvatar(name: String, background: Color, foreground: Color) {
    Box(
        Modifier.size(72.dp).background(background, Look.shapes.pill),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1), style = Look.type.heading, color = foreground)
    }
}

@Composable
internal fun CarpoolFrame(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    val colors = Look.colors
    Column(Modifier.fillMaxSize().background(colors.surface)) {
        SettleHeader(title, onBack)
        HorizontalDivider(thickness = 2.dp, color = colors.line)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(colors.raised.copy(alpha = 0.45f))
                .padding(start = 64.dp, end = 64.dp, top = 32.dp, bottom = 16.dp),
            content = content,
        )
    }
}
