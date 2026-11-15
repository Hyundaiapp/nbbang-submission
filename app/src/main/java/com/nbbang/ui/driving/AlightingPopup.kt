// C-05 탑승자 하차 처리: 대상 선택과 확정을 분리한다.
package com.nbbang.ui.driving

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nbbang.UiState
import com.nbbang.domain.Lines
import com.nbbang.domain.rootId
import com.nbbang.settle.riderId
import com.nbbang.ui.Look

@Composable
internal fun AlightingPopup(
    state: UiState,
    initialRiderId: Int,
    onClose: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val ride = state.live ?: return
    val settlement = state.settlement(null) ?: return
    val c = Look.colors
    var selectedId by remember { mutableIntStateOf(initialRiderId) }
    var confirming by remember { mutableStateOf(false) }
    val onboard = ride.riders.filter { it.aboard }
    val shares = settlement.riders.associateBy { it.riderId() }
    val selected = onboard.firstOrNull { it.id == selectedId }
    val selectedShare = selected?.let { shares[ride.rootId(it.id)] }
    val name = selectedShare?.person?.name ?: selected?.label
    val amount = selectedShare?.finalWon
    val startKm = ride.segments.firstOrNull()?.startKm
    val currentKm = Lines.km(settlement.km)
    fun boardedKm(odometer: Double): String =
        Lines.km((odometer - (startKm ?: odometer)).coerceAtLeast(0.0))

    Column(
        Modifier.fillMaxSize()
            .clip(Look.shapes.popup)
            .background(c.surface)
            .border(4.dp, c.ink, Look.shapes.popup)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(68.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(c.accent, Look.shapes.pill), contentAlignment = Alignment.Center) {
                Text("N", style = Look.type.label, color = c.onAccent)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (confirming) "하차 처리 확인" else "누가 내리나요?",
                style = Look.type.popupTitle,
                color = c.ink,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.size(68.dp)
                    .semantics { contentDescription = "하차 팝업 닫기" }
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(44.dp).background(c.raised, Look.shapes.pill), contentAlignment = Alignment.Center) {
                    Text("×", style = Look.type.popupValue, color = c.ink)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Spacer(Modifier.height(12.dp))
        if (confirming) {
            Column(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (name != null) "${name}님을 하차 처리할까요?" else "이미 하차한 탑승자예요.",
                    style = Look.type.popupValue,
                    color = c.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (name != null && amount != null) {
                    Spacer(Modifier.height(12.dp))
                    Column(
                        Modifier.fillMaxWidth()
                            .background(c.accent.copy(alpha = 0.12f), Look.shapes.compact)
                            .border(1.dp, c.accent, Look.shapes.compact)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "$currentKm 기준 부담액은 ${Lines.won(amount)}이에요.",
                            style = Look.type.popupName,
                            color = c.ink,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "하차 후에는 이후 비용을 분담하지 않아요. 다시 타면 재탑승으로 추가할 수 있어요.",
                            style = Look.type.popupCaption,
                            color = c.muted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        } else {
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = onboard.indexOfFirst { it.id == selectedId }.coerceAtLeast(0),
            )
            val avatarColors = listOf(c.accent, c.action, c.success)
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { RiderRow("나", "운전자 · 하차 불가", null, false, c.muted, c.surface, null) }
                    itemsIndexed(onboard, key = { _, rider -> rider.id }) { index, rider ->
                        val share = shares[ride.rootId(rider.id)]
                        RiderRow(
                            name = share?.person?.name ?: rider.label,
                            detail = "${boardedKm(rider.boardedKm)} 탑승",
                            won = share?.finalWon,
                            aboard = true,
                            avatarColor = avatarColors[index % avatarColors.size],
                            onAvatar = if (index % avatarColors.size == 1) c.ink else c.surface,
                            onClick = { selectedId = rider.id },
                            selected = selectedId == rider.id,
                        )
                    }
                }
                Column(
                    Modifier.weight(0.95f).fillMaxHeight()
                        .background(c.raised, Look.shapes.compact)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (selected == null) {
                        Text(
                            if (onboard.isEmpty()) "하차할 탑승자가 없어요." else "하차할 탑승자를 선택해 주세요.",
                            style = Look.type.popupName,
                            color = c.muted,
                        )
                    } else {
                        Text(name.orEmpty(), style = Look.type.popupName, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "탑승 ${boardedKm(selected.boardedKm)} → 현재 $currentKm",
                            style = Look.type.popupCaption,
                            color = c.muted,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("지금까지 부담액", style = Look.type.popupCaption, color = c.muted)
                        Text(amount?.let(Lines::won) ?: "—", style = Look.type.popupValue, color = c.gold)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PopupButton(
                if (confirming) "취소" else "닫기",
                c.raised,
                c.ink,
                Modifier.weight(1f).height(70.dp),
            ) {
                if (confirming) confirming = false else onClose()
            }
            if (selected != null && amount != null) {
                PopupButton(
                    if (confirming) "하차 확정" else "하차",
                    if (confirming) c.warn else c.accent,
                    c.onAccent,
                    Modifier.width(530.dp).height(70.dp),
                ) {
                    if (confirming) onConfirm(selected.id) else confirming = true
                }
            }
        }
    }
}
