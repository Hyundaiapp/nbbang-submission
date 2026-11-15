// C-12 게임 선택, C-16 주사위 굴리기. 게임은 주차 상태의 정산 화면에서만 한다. 결과 로직은 core의 Games.
package com.nbbang.ui.settle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.nbbang.R
import com.nbbang.RideViewModel
import com.nbbang.UiState
import com.nbbang.domain.DiceGame
import com.nbbang.domain.GameKind
import com.nbbang.domain.Games
import com.nbbang.settle.GameMode
import com.nbbang.settle.GamePlan
import com.nbbang.settle.gamePot
import com.nbbang.settle.riderId
import com.nbbang.settle.settlement
import com.nbbang.ui.AccentButton
import com.nbbang.ui.Look
import kotlinx.coroutines.delay

private val GAME_ICON_ON = Color(0xFFECF4FE)
private val GAME_ICON_OFF = Color(0xFFA4A8AE)

internal val GameKind.displayLabel: String
    get() = if (this == GameKind.SHELL) "컵 돌리기" else label

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GamePickScreen(state: UiState, vm: RideViewModel, rideId: String) {
    val ride = state.ride(rideId)
    val s = state.settlement(rideId)
    if (ride == null || s == null) return SettleEmpty("정산할 주행이 없어요", vm::goHome)
    val c = Look.colors
    val people = s.shares.filter { it.optedIn }.map { it.riderId() to (if (it.person.isDriver) "나" else it.person.name) }
    var mode by remember { mutableStateOf(GameMode.ALL_IN) }
    var whole by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf(emptySet<Int>()) }
    var players by remember { mutableStateOf(people.map { it.first }.toSet()) }
    var kind by remember { mutableStateOf<GameKind?>(null) }
    val plan = kind?.let { GamePlan(it, mode, people.map { p -> p.first }.filter { id -> id in players }, whole, items.sorted()) }
    val pot = plan?.takeIf { it.ready }?.let { ride.gamePot(it) }

    SettleFrame("게임 선택", onBack = { vm.openSettlement(rideId) }) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SettleCard(Modifier.fillMaxWidth()) {
                    Text("방식", style = Look.type.label, color = c.ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Chip("독박", mode == GameMode.ALL_IN) { mode = GameMode.ALL_IN }
                        Chip("몰아주기", mode == GameMode.LEAN) { mode = GameMode.LEAN }
                    }
                    Text(
                        if (mode == GameMode.ALL_IN) "걸린 1명이 대상 금액을 다 내요." else "걸린 1명이 대상 금액의 절반을 더 내고, 나머지 절반은 참가자가 똑같이 나눠요.",
                        style = Look.type.caption, color = c.muted,
                    )
                }
                SettleCard(Modifier.fillMaxWidth()) {
                    Text("대상 금액", style = Look.type.label, color = c.ink)
                    Chip("전체 금액 (${won(s.costWon)})", whole) { whole = true }
                    if (ride.costs.isNotEmpty()) {
                        Text("또는 추가 비용 항목 고르기", style = Look.type.caption, color = c.muted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ride.costs.forEachIndexed { i, cost ->
                                Chip("${cost.name} ${won(cost.won)}", !whole && i in items) {
                                    whole = false
                                    items = if (i in items) items - i else items + i
                                }
                            }
                        }
                    }
                    Text("게임 결과는 이 금액에만 적용되고 나머지는 그대로 N빵이에요.", style = Look.type.caption, color = c.muted)
                }
                SettleCard(Modifier.fillMaxWidth()) {
                    Text("참가자 (최소 2명, 눌러서 빼기)", style = Look.type.label, color = c.ink)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        people.forEach { (id, name) -> Chip(name, id in players) { players = if (id in players) players - id else players + id } }
                    }
                }
            }
            Column(Modifier.width(720.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("게임", style = Look.type.label, color = c.ink)
                listOf(GameKind.LADDER, GameKind.SHELL, GameKind.CARD, GameKind.DICE).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        row.forEach { k -> GameCard(k, selected = k == kind, ready = true, Modifier.weight(1f)) { kind = k } }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (pot != null) Text("이번 판에 걸린 돈 ${won(pot)}", style = Look.type.label, color = c.gold)
                if (plan != null && !plan.ready) {
                    Text(if (plan.players.size < 2) "참가자가 2명 이상이어야 해요" else "대상 금액을 골라 주세요", style = Look.type.caption, color = c.warn)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    PadButton("이전", Modifier.weight(1f), quiet = true) { vm.openSettlement(rideId) }
                    AccentButton("게임 시작", Modifier.weight(2f), enabled = plan?.ready == true, prominent = true) {
                        plan?.let { vm.startGame(rideId, it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameCard(kind: GameKind, selected: Boolean, ready: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Look.colors
    // 디자인 게임 아이콘(48×48). 홈 메뉴처럼 고른 카드는 파란 원에 밝은 아이콘, 아니면 회색 원에 회색 아이콘.
    val icon = when (kind) {
        GameKind.LADDER -> R.drawable.ic_game_ladder
        GameKind.SHELL -> R.drawable.ic_game_cup
        GameKind.CARD -> R.drawable.ic_game_card
        GameKind.DICE -> R.drawable.ic_game_dice
    }
    Column(
        modifier
            .height(250.dp)
            .clip(Look.shapes.card)
            .background(if (selected) c.accent.copy(alpha = 0.12f) else c.surface)
            .border(if (selected) 6.dp else 3.dp, if (selected) c.accent else c.line, Look.shapes.card)
            .clickable(enabled = ready, onClick = onClick)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(if (selected) c.accent else c.raised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon), contentDescription = null,
                tint = if (selected) GAME_ICON_ON else GAME_ICON_OFF,
                modifier = Modifier.size(68.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(kind.displayLabel, style = Look.type.label, color = if (ready) c.ink else c.muted)
        Text(
            if (ready) kind.hint() else "준비 중",
            style = Look.type.caption, color = c.muted, textAlign = TextAlign.Center,
        )
    }
}

/** C-16 주사위 굴리기. 한 번 누르면 전원이 굴리고, 동점이면 동점자끼리 다시 굴린다. */
@Composable
fun DiceScreen(state: UiState, vm: RideViewModel, rideId: String, plan: GamePlan) {
    val ride = state.ride(rideId)
    val s = state.settlement(rideId)
    if (ride == null || s == null) return SettleEmpty("정산할 주행이 없어요", vm::goHome)
    val c = Look.colors
    val names = s.playerNames()
    val pot = ride.gamePot(plan)
    var game by remember { mutableStateOf<DiceGame?>(null) }
    var shown by remember { mutableIntStateOf(0) }
    var rolling by remember { mutableStateOf(false) }
    var tumble by remember { mutableIntStateOf(0) }
    val done = game != null && shown >= game!!.rounds.size
    LaunchedEffect(game) {
        val g = game ?: return@LaunchedEffect
        shown = 0
        g.rounds.indices.forEach {
            // 동점이면 그 결과를 잠깐 보여 준 뒤 동점자만 다시 굴린다.
            if (it > 0) delay(TIE_PAUSE_MS)
            rolling = true
            delay(ROLL_MS)
            rolling = false
            shown = it + 1
        }
    }
    // 굴리는 동안 면을 빠르게 바꿔 구르는 것처럼 보인다(결과는 이미 정해져 있다).
    LaunchedEffect(rolling) {
        while (rolling) {
            tumble++
            delay(TUMBLE_MS)
        }
    }
    val rollers = if (rolling) game?.rounds?.getOrNull(shown)?.keys.orEmpty() else emptySet()
    val round = game?.rounds?.getOrNull(shown - 1)
    val loser = if (done) game?.loser else null
    val deal = dealText(plan, pot)

    SettleFrame("주사위 굴리기", onBack = if (game == null) ({ vm.openGamePick(rideId) }) else null, trailing = { GameTarget(plan, pot) }) {
        Text("가장 큰 눈이 나온 사람이 $deal. 동점이면 동점자끼리 다시 굴려요.", style = Look.type.body, color = c.ink)
        // 카드는 한 줄. 6~7명이면 화면 폭에 맞춰 줄인다(320dp 그대로면 6번째부터 안 보인다).
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val n = plan.players.size.coerceAtLeast(1)
            val cardWidth = min(320.dp, (maxWidth - 20.dp * (n - 1)) / n)
            val dieBox = min(170.dp, cardWidth - 40.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally)) {
                plan.players.forEach { id ->
                    val face = round?.get(id)
                    val out = round != null && face == null
                    val isLoser = id == loser
                    Column(
                        Modifier
                            .size(width = cardWidth, height = 470.dp)
                            .clip(Look.shapes.card)
                            .background(if (isLoser) c.warn.copy(alpha = 0.10f) else c.surface)
                            .border(if (isLoser) 6.dp else 3.dp, if (isLoser) c.warn else c.line, Look.shapes.card)
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(names[id] ?: "?", style = Look.type.heading, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val tumbling = id in rollers
                        // 동점 재굴림에서 빠진 사람은 마지막에 나온 눈을 흐리게 남겨 둔다.
                        val lastFace = face ?: game?.rounds?.take(shown)?.lastOrNull { id in it }?.get(id)
                        Box(Modifier.size(dieBox), contentAlignment = Alignment.Center) {
                            DiceFace(
                                face = if (tumbling) (tumble + id * 5) % 6 + 1 else lastFace,
                                tumbling = tumbling,
                                dim = out && !tumbling,
                                modifier = Modifier.size(dieBox - 20.dp),
                            )
                            if (!tumbling && lastFace == null) Text("?", style = Look.type.display, color = c.muted)
                        }
                        Text(
                            when {
                                tumbling -> "…"
                                face != null -> "$face"
                                out -> "통과"
                                else -> "-"
                            },
                            style = Look.type.display, color = if (isLoser) c.warn else c.ink,
                        )
                        if (done) {
                            if (isLoser) Badge(hitLabel(plan), c.warn)
                            else Text("통과", style = Look.type.label, color = c.muted)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (game != null && !done && shown > 0 && shown < game!!.rounds.size) {
            Text("동점이에요! 동점자끼리 다시 굴려요", style = Look.type.label, color = c.warn)
        }
        if (loser != null) GameResultCard(ride, plan, loser, names)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            AccentButton("굴리기", Modifier.weight(1f), enabled = game == null) {
                game = Games.playDice(plan.players)
            }
            ConfirmButton(loser, Modifier.weight(1f)) { vm.finishGame(rideId, plan, it) }
        }
    }
}

/** 기획서 C-12 게임 카드 설명. */
private fun GameKind.hint(): String = when (this) {
    GameKind.LADDER -> "경로를 따라 내려가요"
    GameKind.SHELL -> "공이 든 컵을 피하세요"
    GameKind.CARD -> "가장 높은 숫자가 걸려요"
    GameKind.DICE -> "가장 큰 눈이 걸려요"
}

/**
 * 주사위 한 면. 둥근 정육면체 면에 눈을 그린다(1은 크고 빨간 눈). [face]가 null이면 빈 면.
 * [tumbling]이면 살짝 기울여 구르는 느낌을 준다.
 */
@Composable
private fun DiceFace(face: Int?, modifier: Modifier = Modifier, tumbling: Boolean = false, dim: Boolean = false) {
    val c = Look.colors
    val body = c.surface
    val edge = if (face == null) c.line else c.ink.copy(alpha = 0.18f)
    val pip = c.ink
    val ace = c.warn
    val tilt = if (tumbling) (if ((face ?: 0) % 2 == 0) 12f else -12f) else 0f
    Canvas(
        modifier.graphicsLayer {
            rotationZ = tilt
            alpha = if (dim) 0.35f else 1f
        },
    ) {
        val corner = CornerRadius(size.minDimension * 0.2f)
        drawRoundRect(color = if (face == null) c.raised else body, cornerRadius = corner)
        drawRoundRect(color = edge, cornerRadius = corner, style = Stroke(width = 4.dp.toPx()))
        if (face == null) return@Canvas
        val r = size.minDimension * 0.085f
        pipsOf(face).forEach { (x, y) ->
            drawCircle(
                color = if (face == 1) ace else pip,
                radius = if (face == 1) r * 1.6f else r,
                center = Offset(size.width * x, size.height * y),
            )
        }
    }
}

/** 주사위 눈 위치(면의 비율 좌표). */
private fun pipsOf(face: Int): List<Pair<Float, Float>> {
    val l = 0.27f
    val m = 0.5f
    val h = 0.73f
    return when (face) {
        1 -> listOf(m to m)
        2 -> listOf(h to l, l to h)
        3 -> listOf(h to l, m to m, l to h)
        4 -> listOf(l to l, h to l, l to h, h to h)
        5 -> listOf(l to l, h to l, m to m, l to h, h to h)
        else -> listOf(l to l, l to m, l to h, h to l, h to m, h to h)
    }
}

private const val ROLL_MS = 1_500L
private const val TUMBLE_MS = 90L
private const val TIE_PAUSE_MS = 1_200L
