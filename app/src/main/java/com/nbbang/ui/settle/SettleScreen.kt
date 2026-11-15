// C-08 최종 N빵 정산. 유가 수정, 톨비(C-09 입력), 추가 비용(C-10 추가), 절약 기여, 인원 opt in/out이 모두 이 화면에서만 바뀐다.
package com.nbbang.ui.settle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nbbang.RideViewModel
import com.nbbang.UiState
import com.nbbang.domain.Cost
import com.nbbang.domain.DRIVER_ID
import com.nbbang.domain.ExtraKind
import com.nbbang.domain.Power
import com.nbbang.domain.Ride
import com.nbbang.settle.priceSource
import com.nbbang.settle.ExpenseSource
import com.nbbang.settle.gameLine
import com.nbbang.settle.Settlement
import com.nbbang.settle.riderId
import com.nbbang.settle.unitWon
import com.nbbang.settle.expectedName
import com.nbbang.settle.explain
import com.nbbang.settle.officialBaseline
import com.nbbang.ui.AccentButton
import com.nbbang.settle.savingQuestionDue
import com.nbbang.ui.Look
import com.nbbang.ui.QuietButton
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 금액 입력 창 하나만 띄운다. */
private sealed interface Editing {
    data object UnitPrice : Editing
    data class Toll(val index: Int) : Editing
    data class CostAt(val index: Int) : Editing
    data object NewCost : Editing
    /** 미입력 톨비가 있을 때 [next]로 넘어가려 하면 먼저 묻는다(게임 대상·합계가 바뀌기 때문). */
    data class PendingTolls(val next: Next) : Editing
    /** 이미 확정한 게임 결과를 지울지 묻는다. [replay]면 지운 뒤 C-12로 간다. */
    data class ClearGame(val replay: Boolean) : Editing
}

private enum class Next { GAME, CHECK }

@Composable
fun SettleScreen(state: UiState, vm: RideViewModel, rideId: String) {
    val ride = state.ride(rideId)
    val s = state.settlement(rideId)
    if (ride == null || s == null) return SettleEmpty("정산할 주행이 없어요", vm::goHome)
    if (state.restricted) return SettleEmpty("주차(P) 후에 정산할 수 있어요", vm::goHome)
    val c = Look.colors
    var editing by remember { mutableStateOf<Editing?>(null) }
    // C-11: 실제 연비가 평균보다 좋고 아직 묻지 않았으면 처음 열릴 때 묻는다.
    // 실제 연비가 평균보다 좋아 절약액이 있을 때만 묻는다(savingQuestionDue).
    var askSaving by rememberSaveable(rideId) { mutableStateOf(ride.savingAccepted == null && ride.savingQuestionDue()) }
    // 확정한 게임 결과(C-13~C-16). 진 사람을 OFF로 바꾸는 등으로 무효가 되면 null이다.
    val gameLine = ride.gameLine(s)

    SettleFrame("최종 N빵 정산", onBack = vm::goHome, trailing = {
        Column(horizontalAlignment = Alignment.End) {
            Text("총 정산 금액", style = Look.type.caption, color = c.muted)
            Text(won(s.costWon), style = Look.type.display, color = c.gold)
        }
    }) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                FuelCard(ride, s) { editing = Editing.UnitPrice }
                TollCard(ride, s, state) { editing = Editing.Toll(it) }
                CostCard(ride, s, onEdit = { editing = Editing.CostAt(it) }, onAdd = { editing = Editing.NewCost })
            }
            Column(
                Modifier
                    .width(640.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    gameLine?.let { GameCard(it) { editing = Editing.ClearGame(replay = false) } }
                    if (s.savingWon > 0 && ride.savingAccepted != null) SavingCard(ride, s) { askSaving = true }
                    PeopleCard(s) { id, on -> vm.setOptedIn(rideId, id, on) }
                }
                // 게임은 한 번만(다시 굴려 결과를 바꾸지 않게). 이미 결과가 있으면 지울지 먼저 묻는다.
                if (gameLine != null) {
                    QuietButton("게임 다시 하기", Modifier.fillMaxWidth()) { editing = Editing.ClearGame(replay = true) }
                } else {
                    AccentButton("게임으로 N빵 하기", Modifier.fillMaxWidth(), prominent = true) {
                        if (s.pendingTolls > 0) editing = Editing.PendingTolls(Next.GAME) else vm.openGamePick(rideId)
                    }
                }
                AccentButton("금액 확인", Modifier.fillMaxWidth()) {
                    if (s.pendingTolls > 0) editing = Editing.PendingTolls(Next.CHECK) else vm.openFinalCheck(rideId)
                }
            }
        }
    }

    when (val e = editing) {
        Editing.UnitPrice -> {
            val electric = ride.power == Power.ELECTRIC
            val base = if (electric) ride.prices.wonPerKwh else ride.prices.wonPerLiter
            NumberPadDialog(
                title = if (electric) "kWh당 단가 수정" else "리터당 단가 수정",
                initial = ride.unitWon(),
                hint = "기본값 ${won(base)}(${ride.prices.priceSource(ride.power)}). 바꾸면 모든 인원 금액을 다시 계산해요.",
                extra = if (ride.unitWonOverride != null) listOf("기본값으로 되돌리기" to { vm.setUnitWon(rideId, null); editing = null }) else emptyList(),
                onDismiss = { editing = null },
            ) { vm.setUnitWon(rideId, it); editing = null }
        }
        is Editing.Toll -> {
            val pass = ride.tollPasses.getOrNull(e.index)
            NumberPadDialog(
                title = "${tollName(pass?.name, e.index)} 톨비",
                initial = pass?.won,
                hint = "통과할 때 탄 ${pass?.aboard?.size ?: 0}명이 나눠요.",
                extra = listOf("통행료 없음 0원" to { vm.enterToll(rideId, e.index, 0); editing = null }),
                onDismiss = { editing = null },
            ) { vm.enterToll(rideId, e.index, it); editing = null }
        }
        is Editing.CostAt -> {
            val cost = ride.costs.getOrNull(e.index)
            if (cost == null) {
                editing = null
            } else {
                NumberPadDialog(
                    title = "${cost.name} 금액",
                    initial = cost.won,
                    extra = listOf("이 항목 삭제" to { vm.updateCost(rideId, e.index, null); editing = null }),
                    onDismiss = { editing = null },
                ) { vm.updateCost(rideId, e.index, cost.copy(won = it)); editing = null }
            }
        }
        Editing.NewCost -> AddCostScreen(s, onDismiss = { editing = null }) { name, amount, payer, shared ->
            vm.addCost(rideId, name, amount, shared, ExpenseSource.SEPARATE, payer)
            editing = null
        }
        is Editing.PendingTolls -> PendingTollsDialog(
            count = s.pendingTolls,
            onInput = { editing = Editing.Toll(ride.tollPasses.indexOfFirst { it.won == null }) },
            onZero = {
                ride.tollPasses.forEachIndexed { i, t -> if (t.won == null) vm.enterToll(rideId, i, 0) }
                editing = null
                if (e.next == Next.GAME) vm.openGamePick(rideId) else vm.openFinalCheck(rideId)
            },
            onDismiss = { editing = null },
        )
        is Editing.ClearGame -> ClearGameDialog(
            replay = e.replay,
            onDismiss = { editing = null },
        ) {
            editing = null
            vm.recordGame(rideId, null)
            if (e.replay) {
                if (s.pendingTolls > 0) editing = Editing.PendingTolls(Next.GAME) else vm.openGamePick(rideId)
            }
        }
        null -> Unit
    }

    if (askSaving) SavingPopup(ride, onDecide = { vm.decideSaving(rideId, it) }, onClose = { askSaving = false })
}

@Composable
private fun FuelCard(ride: Ride, s: Settlement, onEdit: () -> Unit) {
    val c = Look.colors
    val electric = ride.power == Power.ELECTRIC
    // 실제 연비가 평균보다 좋으면 실제로 쓴 만큼(실제 연비)으로 나눈다(R1).
    val efficiency = s.fuelKmPerUnit
    val used = if (efficiency > 0) s.km / efficiency else 0.0
    val base = if (electric) ride.prices.wonPerKwh else ride.prices.wonPerLiter
    SettleCard {
        Text(if (electric) "충전비" else "유류비", style = Look.type.heading, color = c.ink)
        Text(
            String.format(
                Locale.KOREA, "주행 %.1fkm ÷ %s %.1f%s = %.1f%s",
                s.km, if (s.actualFuel) "실제" else ride.expectedName(), efficiency, if (electric) "km/kWh" else "km/L", used, if (electric) "kWh" else "L",
            ),
            style = Look.type.body, color = c.muted,
        )
        // 공인연비로 정한 기준연비면 어떻게 정했는지 한 줄(운전자 절약 기여의 기준).
        ride.officialBaseline()?.let { b ->
            Text(
                String.format(Locale.KOREA, "기준 연비 %.1fkm/L · %s", b.kmPerLiter, b.explain()),
                style = Look.type.caption, color = c.muted,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (electric) "kWh당 단가" else "리터당 단가", style = Look.type.label, color = c.ink)
            Text(won(ride.unitWon()), style = Look.type.heading, color = c.ink)
            Badge("${ride.prices.priceSource(ride.power)} ${won(base)}", c.raised, c.muted)
            Spacer(Modifier.weight(1f))
            QuietButton("수정", onClick = onEdit)
        }
        MoneyRow("${if (electric) "충전비" else "유류비"} 소계", won(s.fuelWon), wonColor = c.gold)
    }
}

@Composable
private fun TollCard(ride: Ride, s: Settlement, state: UiState, onInput: (Int) -> Unit) {
    val c = Look.colors
    val time = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA)
    SettleCard(border = if (s.pendingTolls > 0) c.warn else c.line) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("톨비", style = Look.type.heading, color = c.ink)
            Spacer(Modifier.weight(1f))
            Text("미입력 ${s.pendingTolls}건", style = Look.type.label, color = if (s.pendingTolls > 0) c.warn else c.muted)
        }
        if (ride.tollPasses.isEmpty()) Text("지나간 톨게이트가 없어요", style = Look.type.body, color = c.muted)
        ride.tollPasses.forEachIndexed { i, t ->
            val at = if (t.at > 0) Instant.ofEpochMilli(t.at).atZone(state.zone).format(time) else null
            val amount = t.won
            if (amount == null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Look.shapes.compact)
                        .background(c.warn.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tollName(t.name, i), style = Look.type.label, color = c.ink)
                        Text(listOfNotNull(at, "탄 사람 ${t.aboard.size}명", "미입력").joinToString(" · "), style = Look.type.caption, color = c.warn)
                    }
                    Box(
                        Modifier
                            .clip(Look.shapes.button)
                            .background(c.warn)
                            .clickable { onInput(i) }
                            .padding(horizontal = 28.dp, vertical = 14.dp),
                    ) { Text("입력", style = Look.type.label, color = c.surface) }
                }
            } else {
                MoneyRow(tollName(t.name, i), won(amount), detail = listOfNotNull(at, "탄 사람 ${t.aboard.size}명").joinToString(" · ")) { onInput(i) }
            }
        }
        MoneyRow("톨비 소계", won(s.tollWon), wonColor = c.gold)
    }
}

@Composable
private fun CostCard(ride: Ride, s: Settlement, onEdit: (Int) -> Unit, onAdd: () -> Unit) {
    val c = Look.colors
    val names = s.shares.associate { it.riderId() to it.person.name }
    SettleCard {
        Text("추가 비용", style = Look.type.heading, color = c.ink)
        ride.costs.forEachIndexed { i, cost ->
            val payer = if (cost.paidBy == DRIVER_ID) "나" else names[cost.paidBy] ?: "?"
            val detail = when (cost.source) {
                ExpenseSource.DRIVING -> "주행 중 입력"
                ExpenseSource.CAR_PAY -> "카투페이 결제"
                ExpenseSource.SEPARATE -> "별도 결제 · 결제자 $payer"
            } + " · ${cost.sharedBy.size}명이 나눔"
            MoneyRow(
                cost.name, won(cost.won), detail = detail,
                badge = if (cost.source == ExpenseSource.CAR_PAY) ({ Badge("자동 추가", c.success) }) else null,
            ) { onEdit(i) }
        }
        // MVP 때 Gleo로 더한 비용(주차비 등). 고치려면 음성 대신 여기서 다시 넣는다.
        ride.segments.flatMap { it.extras }.filter { it.kind != ExtraKind.TOLL }.forEach { extra ->
            MoneyRow(extra.kind.label, won(extra.won), detail = "음성으로 추가")
        }
        if (ride.costs.isEmpty() && ride.segments.none { seg -> seg.extras.any { it.kind != ExtraKind.TOLL } }) {
            Text("주행 중에 넣은 비용이 없어요", style = Look.type.body, color = c.muted)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 90.dp)
                .clip(Look.shapes.button)
                .border(3.dp, c.accent, Look.shapes.button)
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) { Text("+ 별도 결제 추가", style = Look.type.label, color = c.accent) }
    }
}

/** C-08 절약 기여 결과. C-11에서 고른 걸 [onChange]로 다시 고를 수 있다. */
@Composable
private fun SavingCard(ride: Ride, s: Settlement, onChange: () -> Unit) {
    val c = Look.colors
    val word = if (ride.power == Power.ELECTRIC) "전비" else "연비"
    val accepted = ride.savingAccepted == true
    SettleCard(border = if (accepted) c.success else c.line) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (accepted) "절약 기여 인정" else "절약 기여 인정 안 함", style = Look.type.label, color = c.ink)
            Spacer(Modifier.weight(1f))
            Text(
                if (accepted && s.savingAppliedWon > 0) "운전자 -" + won(s.savingAppliedWon) else "아낀 " + won(s.savingWon),
                style = Look.type.heading, color = if (accepted) c.success else c.muted,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                when {
                    accepted && s.savingAppliedWon > 0 -> "동승자는 평균 $word 기준으로 내요."
                    accepted -> "운전자가 N빵에 빠져 있어서 감면하지 않았어요."
                    else -> "아낀 돈을 모두 함께 나눴어요."
                },
                style = Look.type.caption, color = c.muted, modifier = Modifier.weight(1f),
            )
            Text(
                "다시 정하기",
                style = Look.type.label, color = c.accent,
                modifier = Modifier
                    .clip(Look.shapes.pill)
                    .clickable(onClick = onChange)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun PeopleCard(s: Settlement, onToggle: (Int, Boolean) -> Unit) {
    val c = Look.colors
    SettleCard {
        Text("N빵 참여 인원 (opt in / out)", style = Look.type.label, color = c.ink)
        s.shares.forEach { share ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 84.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    if (share.person.isDriver) "나 (운전자)" else share.person.name,
                    style = Look.type.label, color = if (share.optedIn) c.ink else c.muted, modifier = Modifier.weight(1f),
                )
                Text(finalText(share), style = Look.type.label, color = finalColor(share))
                OnOff(share.optedIn) { onToggle(share.riderId(), it) }
            }
        }
        Text("운전자도 부담할지 선택할 수 있어요. OFF 인원은 N빵에서 제외되고 금액은 나머지 인원에게 재분배돼요.", style = Look.type.caption, color = c.muted)
    }
}

/** C-08 게임 결과 카드. 결과가 금액에 이미 들어가 있다는 걸 보여 주고, 지울 수 있게 한다. */
@Composable
private fun GameCard(line: String, onClear: () -> Unit) {
    val c = Look.colors
    SettleCard(border = c.action, background = c.action.copy(alpha = 0.10f)) {
        Text("게임 결과 반영됨", style = Look.type.caption, color = c.muted)
        Text(line, style = Look.type.label, color = c.ink)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("금액 확인에서 게임 조정으로 보여요.", style = Look.type.caption, color = c.muted, modifier = Modifier.weight(1f))
            Text(
                "결과 지우기",
                style = Look.type.label, color = c.warn,
                modifier = Modifier
                    .clip(Look.shapes.pill)
                    .clickable(onClick = onClear)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ClearGameDialog(replay: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val c = Look.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .size(width = 850.dp, height = 400.dp)
                .clip(Look.shapes.popup)
                .background(c.surface)
                .padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(if (replay) "게임을 다시 할까요?" else "게임 결과를 지울까요?", style = Look.type.heading, color = c.ink)
            Text(
                if (replay) "지금 결과는 지워져요. 모두 동의했을 때만 다시 해 주세요."
                else "게임 없이 원래대로 N빵해요. 나중에 다시 게임을 할 수 있어요.",
                style = Look.type.body, color = c.ink,
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PadButton("그대로 두기", Modifier.weight(1f), quiet = true, onClick = onDismiss)
                PadButton(if (replay) "지우고 다시 하기" else "결과 지우기", Modifier.weight(1f), onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun PendingTollsDialog(count: Int, onInput: () -> Unit, onZero: () -> Unit, onDismiss: () -> Unit) {
    val c = Look.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .size(width = 850.dp, height = 400.dp)
                .clip(Look.shapes.popup)
                .background(c.surface)
                .padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("미입력 톨비가 ${count}건 있어요", style = Look.type.heading, color = c.warn)
            Text("입력하지 않으면 합계에 들어가지 않아요. 지금 입력하거나 통행료 없음(0원)으로 처리해 주세요.", style = Look.type.body, color = c.ink)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PadButton("0원 처리", Modifier.weight(1f), quiet = true, onClick = onZero)
                PadButton("입력하기", Modifier.weight(1f), onClick = onInput)
            }
        }
    }
}

private fun tollName(name: String?, index: Int): String = name?.takeIf { it.isNotBlank() }?.let { if (it.endsWith("톨게이트")) it else "$it 톨게이트" } ?: "톨게이트 ${index + 1}"
