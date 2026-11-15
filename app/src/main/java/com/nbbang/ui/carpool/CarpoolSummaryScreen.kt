// K-04 카풀 주행 종료 (주행 요약)
package com.nbbang.ui.carpool

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nbbang.RideViewModel
import com.nbbang.UiState
import com.nbbang.domain.Gear
import com.nbbang.domain.Lines
import com.nbbang.domain.Power
import com.nbbang.settle.priceBasis
import com.nbbang.settle.riderId
import com.nbbang.settle.savingQuestionDue
import com.nbbang.settle.settlement
import com.nbbang.ui.AccentButton
import com.nbbang.ui.Look
import com.nbbang.ui.QuietButton
import com.nbbang.ui.returnToNavigation
import com.nbbang.ui.settle.NumberPadDialog
import com.nbbang.ui.settle.SavingPopup
import com.nbbang.ui.settle.SettleCard
import com.nbbang.ui.settle.SettleEmpty
import com.nbbang.ui.settle.rangeText
import java.util.Locale

@Composable
internal fun CarpoolSummaryScreen(state: UiState, vm: RideViewModel, rideId: String?, onCloseDetails: (() -> Unit)? = null) {
    val ride = state.ride(rideId) ?: return SettleEmpty("정산할 카풀 주행이 없어요", onCloseDetails ?: vm::goHome)
    if (state.restricted || state.car.gear != Gear.PARK) {
        return SettleEmpty("주차(P) 후에 주행 요약을 볼 수 있어요", onCloseDetails ?: if (rideId == null) vm::resumeCarpoolDrive else vm::goHome)
    }
    val colors = Look.colors
    val context = LocalContext.current
    val settlement = ride.settlement()
    val pending = ride.tollPasses.withIndex().filter { it.value.won == null }
    var tollIndex by rememberSaveable(ride.id) { mutableStateOf<Int?>(null) }
    var askSaving by rememberSaveable(ride.id) { mutableStateOf(false) }
    val openedAt = rememberSaveable(ride.id) { System.currentTimeMillis() }
    val minutes = ((minOf(ride.endedAt ?: openedAt, openedAt) - ride.startedAt) / 60_000).coerceAtLeast(0)
    val duration = if (minutes >= 60) "${minutes / 60}시간 ${minutes % 60}분" else "${minutes}분"
    val electric = ride.power == Power.ELECTRIC
    val resume = {
        vm.resumeCarpoolDrive()
        returnToNavigation(context)
    }

    CarpoolFrame(
        if (onCloseDetails != null) "주행 상세" else "주행 요약",
        onCloseDetails ?: if (rideId == null) resume else vm::goHome,
    ) {
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(54.dp),
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                val blue = colors.accent.copy(alpha = 0.10f)
                SettleCard(Modifier.fillMaxWidth(), border = Color.Transparent, background = blue) {
                    Text("총 주행거리", style = Look.type.caption, color = colors.muted)
                    Text(String.format(Locale.KOREA, "%.1f km", settlement.km), style = Look.type.display, color = colors.ink)
                    Text("소요 시간 $duration", style = Look.type.label, color = colors.ink)
                    val group = state.group(ride.groupId)
                    Text(
                        group?.let { "카풀 · ${it.name}" } ?: "카풀",
                        Modifier.background(colors.surface, Look.shapes.pill).padding(horizontal = 16.dp, vertical = 6.dp),
                        style = Look.type.caption,
                        color = colors.accent,
                    )
                }
                SettleCard(Modifier.fillMaxWidth()) {
                    Text("비용 요약 (${ride.prices.priceBasis(ride.power)} 기준)", style = Look.type.label, color = colors.muted)
                    CostLine("${if (electric) "충전비" else "유류비"} (${if (settlement.actualFuel) "실제" else "평균"} ${if (electric) "전비" else "연비"} 기준)", settlement.fuelWon)
                    CostLine("톨비 (입력 완료분)", settlement.tollWon)
                    CostLine("기타 비용", settlement.expenseWon)
                    HorizontalDivider(color = colors.line)
                    CostLine("합계", settlement.costWon)
                }
                if (onCloseDetails != null && (ride.tollPasses.isNotEmpty() || ride.costs.isNotEmpty())) {
                    SettleCard(Modifier.fillMaxWidth()) {
                        Text("비용 내역", style = Look.type.label, color = colors.ink)
                        ride.tollPasses.forEachIndexed { index, pass ->
                            CostLine(pass.name.ifBlank { "톨게이트 ${index + 1}" }, pass.won)
                        }
                        ride.costs.forEach { CostLine(it.name, it.won) }
                    }
                }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(if (onCloseDetails != null) "탑승자별 정산 금액" else "탑승자별 예상 금액", style = Look.type.label, color = colors.muted)
                val avatarColors = listOf(colors.accent, colors.action, colors.success)
                settlement.shares.forEachIndexed { index, share ->
                    val driver = share.person.isDriver
                    val name = if (driver) "나 (운전자)" else share.person.name
                    val color = if (driver) colors.ink else avatarColors[(index - 1).coerceAtLeast(0) % avatarColors.size]
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 96.dp)
                            .background(colors.surface, Look.shapes.card)
                            .border(3.dp, colors.line, Look.shapes.card)
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Box(Modifier.size(60.dp).background(color, Look.shapes.pill), contentAlignment = Alignment.Center) {
                            Text(name.take(1), style = Look.type.label, color = if (color == colors.action) colors.ink else colors.onAccent)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(name, style = Look.type.label, color = colors.ink)
                            Text(rangeText(ride, share.riderId(), settlement.km), style = Look.type.caption, color = colors.muted)
                        }
                        Text(Lines.won(share.finalWon), style = Look.type.label, color = colors.gold)
                    }
                }
                if (onCloseDetails == null && pending.isNotEmpty()) {
                    SettleCard(Modifier.fillMaxWidth(), border = colors.warn, background = colors.warn.copy(alpha = 0.08f)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("미입력 톨비 ${pending.size}건이 있어요", style = Look.type.label, color = colors.warn)
                                Text("정산 확정 전에 입력해 주세요.", style = Look.type.caption, color = colors.muted)
                            }
                            Button(
                                onClick = { tollIndex = pending.first().index },
                                modifier = Modifier.size(200.dp, 70.dp),
                                shape = Look.shapes.button,
                                colors = ButtonDefaults.buttonColors(containerColor = colors.warn, contentColor = colors.onAccent),
                            ) { Text("입력", style = Look.type.label) }
                        }
                    }
                }
            }
        }
        if (onCloseDetails != null) return@CarpoolFrame
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (rideId == null) QuietButton("〈 주행 계속하기", Modifier.width(420.dp), onClick = resume)
            else Spacer(Modifier.width(420.dp))
            AccentButton("카풀 주행 종료!", Modifier.width(600.dp), prominent = true) {
                when {
                    pending.isNotEmpty() -> tollIndex = pending.first().index
                    ride.savingAccepted == null && ride.savingQuestionDue() -> askSaving = true
                    else -> vm.finishCarpoolSummary(rideId)
                }
            }
        }
    }

    tollIndex?.let { index ->
        val pass = ride.tollPasses.getOrNull(index) ?: return@let
        val saveToll: (Int) -> Unit = { amount ->
            vm.enterToll(rideId, index, amount)
            tollIndex = pending.firstOrNull { it.index != index }?.index
        }
        key(index) {
            NumberPadDialog(
                title = "${pass.name.ifBlank { "톨게이트 ${index + 1}" }} 톨비",
                initial = pass.won,
                hint = "통과할 때 탄 ${pass.aboard.size}명이 나눠요.",
                extra = listOf("통행료 없음 0원" to { saveToll(0) }),
                onDismiss = { tollIndex = null },
                onConfirm = saveToll,
            )
        }
    }
    if (askSaving) {
        SavingPopup(
            ride,
            onDecide = { vm.decideSaving(rideId, it) },
            onClose = {
                askSaving = false
                vm.finishCarpoolSummary(rideId)
            },
        )
    }
}

@Composable
private fun CostLine(label: String, won: Int?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = Look.type.label, color = Look.colors.ink)
        Text(won?.let(Lines::won) ?: "미입력", style = Look.type.label, color = Look.colors.gold)
    }
}
