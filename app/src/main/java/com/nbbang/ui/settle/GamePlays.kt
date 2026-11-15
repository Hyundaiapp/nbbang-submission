// C-13 사다리타기, C-14 컵 돌리기, C-15 카드 뽑기와 게임 화면들이 같이 쓰는 조각.
// 결과(사다리 모양, 공 위치, 카드 숫자)는 core의 Games가 앱 안 난수로 정하고, 화면은 그걸 보여 주기만 한다.
// 기획서: 확정 전 다시 하기는 허용하지 않는다(공정성). 그래서 게임이 시작되면 뒤로 가기를 숨긴다.
package com.nbbang.ui.settle

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.nbbang.R
import com.nbbang.RideViewModel
import com.nbbang.UiState
import com.nbbang.domain.DRIVER_ID
import com.nbbang.domain.GameKind
import com.nbbang.domain.Games
import com.nbbang.domain.Ladder
import com.nbbang.domain.Outcome
import com.nbbang.domain.Ride
import com.nbbang.settle.GameMode
import com.nbbang.settle.GamePlan
import com.nbbang.settle.Settlement
import com.nbbang.settle.gamePot
import com.nbbang.settle.riderId
import com.nbbang.settle.settlement
import com.nbbang.ui.AccentButton
import com.nbbang.ui.Look
import com.nbbang.ui.QuietButton
import com.nbbang.ui.ladderPlayerColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Screen.Game → C-12에서 고른 게임 화면. 확정 버튼 문구가 방식(독박·몰아주기)을 알 수 있게 넘겨 둔다. */
@Composable
fun GameScreen(state: UiState, vm: RideViewModel, rideId: String, plan: GamePlan) =
    CompositionLocalProvider(LocalGameMode provides plan.mode) {
        when (plan.kind) {
            GameKind.LADDER -> LadderGameScreen(state, vm, rideId, plan)
            GameKind.SHELL -> ShellGameScreen(state, vm, rideId, plan)
            GameKind.CARD -> CardGameScreen(state, vm, rideId, plan)
            GameKind.DICE -> DiceScreen(state, vm, rideId, plan)
        }
    }

/** 지금 게임의 방식. [ConfirmButton] 문구에 쓴다. */
private val LocalGameMode = compositionLocalOf { GameMode.ALL_IN }

// ---------------------------------------------------------------- 공통

internal fun Settlement.playerNames(): Map<Int, String> =
    shares.associate { it.riderId() to (if (it.person.isDriver) "나" else it.person.name) }

/** 걸린 사람이 내는 돈. "…사람이 {dealText}." 꼴로 쓴다. "40,550원을 혼자 다 내요" / "40,550원 중 20,275원을 혼자 내요" */
internal fun dealText(plan: GamePlan, pot: Int): String =
    if (plan.mode == GameMode.ALL_IN) "${won(pot)}을 혼자 다 내요" else "${won(pot)} 중 ${won(halfOf(pot))}을 혼자 내요"

/** 몰아주기에서 걸린 사람이 혼자 떠안는 몫(판돈의 절반). 나머지 절반은 참가자 모두가 나눈다. */
private fun halfOf(pot: Int): Int = Math.round(pot * 0.5).toInt()

internal fun hitLabel(plan: GamePlan): String = if (plan.mode == GameMode.ALL_IN) "독박!" else "몰아주기!"

/** 머리줄 오른쪽 "독박 · 대상 40,550원". */
@Composable
internal fun RowScope.GameTarget(plan: GamePlan, pot: Int) {
    Text("${if (plan.mode == GameMode.ALL_IN) "독박" else "몰아주기"} · 대상 ${won(pot)}", style = Look.type.label, color = Look.colors.gold)
}

/** 결과 패널의 "오늘의 독박왕"(몰아주기면 "오늘의 몰아주기 주인공") 카드. 반영액은 1원 단위로 내린 최종 금액의 차이다. */
@Composable
internal fun GameResultCard(ride: Ride, plan: GamePlan, loser: Int, names: Map<Int, String>) {
    val c = Look.colors
    val pot = ride.gamePot(plan)
    val added = remember(loser) {
        val before = ride.copy(game = null).settlement().shares.firstOrNull { it.riderId() == loser }?.finalWon ?: 0
        val after = ride.copy(game = plan.toRideGame(loser, pot)).settlement().shares.firstOrNull { it.riderId() == loser }?.finalWon ?: before
        after - before
    }
    SettleCard(Modifier.fillMaxWidth(), border = c.action, background = c.action.copy(alpha = 0.10f)) {
        val who = if (loser == DRIVER_ID) "나 (운전자)" else "${names[loser]} 님"
        // 기획서 C-13: "치킨 12,000원을 쏩니다! (+8,000원 반영)"
        val target = if (plan.whole) "전체 금액" else plan.costIndexes.mapNotNull { ride.costs.getOrNull(it)?.name }.joinToString(", ")
        val treat = if (plan.mode == GameMode.ALL_IN) "${won(pot)}을 쏩니다!" else "${won(pot)} 중 ${won(halfOf(pot))}을 쏩니다!"
        Text(if (plan.mode == GameMode.ALL_IN) "오늘의 독박왕 $who" else "오늘의 몰아주기 주인공 $who", style = Look.type.heading, color = c.ink)
        Text("${target.ifBlank { "대상 금액" }} $treat (${signed(added)} 반영)", style = Look.type.body, color = c.muted)
    }
}

/** Accent Yellow 확정 버튼 "그대로 독박" / "그대로 몰아주기". 걸린 사람이 정해져야 눌린다. */
@Composable
internal fun ConfirmButton(loser: Int?, modifier: Modifier = Modifier, onConfirm: (Int) -> Unit) {
    val label = if (LocalGameMode.current == GameMode.ALL_IN) "그대로 독박" else "그대로 몰아주기"
    AccentButton(label, modifier, enabled = loser != null, prominent = true) { loser?.let(onConfirm) }
}

/** 진행판 한 줄: 이름, 고른 것, 결과. */
@Composable
private fun BoardRow(name: String, detail: String, result: String?, hit: Boolean) {
    val c = Look.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(Look.shapes.compact)
            .background(if (hit) c.warn.copy(alpha = 0.08f) else c.surface)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(name, style = Look.type.label, color = c.ink, modifier = Modifier.weight(1f))
        Text(detail, style = Look.type.label, color = c.muted)
        Text(result ?: "-", style = Look.type.label, color = if (hit) c.warn else if (result == null) c.muted else c.success)
    }
}

// ---------------------------------------------------------------- C-13 사다리타기

/** C-13. 이름을 누르면 이름표와 같은 색으로 경로가 열린다. */
@Composable
fun LadderGameScreen(state: UiState, vm: RideViewModel, rideId: String, plan: GamePlan) {
    val ride = state.ride(rideId)
    val s = state.settlement(rideId)
    if (ride == null || s == null) return SettleEmpty("정산할 주행이 없어요", vm::goHome)
    val c = Look.colors
    val names = s.playerNames()
    val pot = ride.gamePot(plan)
    val ladder = remember(plan) { Games.ladder(plan.players) }
    val n = ladder.order.size
    val playerColors = remember(plan) {
        plan.players.mapIndexed { index, id -> id to ladderPlayerColor(index) }.toMap()
    }
    var opened by remember { mutableStateOf(emptyList<Int>()) }
    var drawing by remember { mutableStateOf(emptySet<Int>()) }
    val progress = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val busy = drawing.isNotEmpty()
    val revealedBySlot = opened.filter { it !in drawing }.associateBy { ladder.endOf(it) }
    val loser = revealedBySlot[ladder.hit]?.let { ladder.order[it] }

    fun open(columns: List<Int>) {
        if (busy || columns.isEmpty()) return
        drawing = columns.toSet()
        opened = opened + columns
        scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(LADDER_MS, easing = LinearEasing))
            drawing = emptySet()
        }
    }

    SettleFrame("사다리타기", onBack = if (opened.isEmpty()) ({ vm.openGamePick(rideId) }) else null, trailing = { GameTarget(plan, pot) }) {
        SettleCard(Modifier.fillMaxWidth().weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("이름을 누르면 경로를 볼 수 있어요", style = Look.type.body, color = c.ink)
                    Text("${hitLabel(plan).dropLast(1)} 칸에 닿은 사람이 ${dealText(plan, pot)}.", style = Look.type.caption, color = c.ink)
                }
                Text("${revealedBySlot.size}/${n}명 공개", style = Look.type.label, color = c.ink)
            }
            Row(Modifier.fillMaxWidth()) {
                ladder.order.forEachIndexed { column, id ->
                    val isOpen = column in opened
                    LadderPlayer(
                        name = names[id] ?: "?",
                        color = playerColors.getValue(id),
                        isOpen = isOpen,
                        enabled = !isOpen && !busy,
                        modifier = Modifier.weight(1f),
                    ) { open(listOf(column)) }
                }
            }
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .clip(Look.shapes.compact)
                    .background(c.raised.copy(alpha = 0.42f)),
            ) {
                val lineColor = c.muted.copy(alpha = 0.65f)
                Canvas(Modifier.fillMaxSize()) {
                    val inset = 18.dp.toPx().coerceAtMost(size.height / 2)
                    val bottom = size.height - inset
                    val rowHeight = (bottom - inset) / (ladder.rungs.size + 1)
                    val x = { column: Int -> size.width * (column + 0.5f) / n }
                    val y = { row: Int -> inset + rowHeight * (row + 1) }
                    val railWidth = 4.dp.toPx()
                    for (column in 0 until n) {
                        drawLine(lineColor, Offset(x(column), inset), Offset(x(column), bottom), railWidth, StrokeCap.Round)
                    }
                    ladder.rungs.forEachIndexed { row, gaps ->
                        gaps.forEachIndexed { gap, connected ->
                            if (connected) drawLine(lineColor, Offset(x(gap), y(row)), Offset(x(gap + 1), y(row)), railWidth, StrokeCap.Round)
                        }
                    }
                    val laneOffset = 6.dp.toPx().coerceAtMost(rowHeight / 4)
                    opened.forEach { column ->
                        drawPartial(
                            ladderPoints(ladder, column, x, y, inset, bottom, laneOffset),
                            if (column in drawing) progress.value else 1f,
                            playerColors.getValue(ladder.order[column]),
                            9.dp.toPx(),
                        )
                    }
                    for (column in 0 until n) {
                        drawCircle(playerColors.getValue(ladder.order[column]), 8.dp.toPx(), Offset(x(column), inset))
                        val player = revealedBySlot[column]?.let { ladder.order[it] }
                        drawCircle(playerColors[player] ?: lineColor, 8.dp.toPx(), Offset(x(column), bottom))
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                (0 until n).forEach { slot ->
                    val player = revealedBySlot[slot]?.let { ladder.order[it] }
                    val hit = player != null && slot == ladder.hit
                    Box(Modifier.weight(1f).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                        val playerColor = playerColors[player]
                        Box(
                            Modifier.width(200.dp).heightIn(min = 58.dp)
                                .clip(Look.shapes.compact)
                                .background(if (hit) c.warn.copy(alpha = 0.14f) else playerColor?.copy(alpha = 0.12f) ?: c.raised)
                                .border(2.dp, playerColor ?: c.line, Look.shapes.compact)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (player == null) "?" else if (hit) hitLabel(plan) else "통과",
                                style = Look.type.label, color = c.ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        if (loser != null) GameResultCard(ride, plan, loser, names)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PadButton("한 명씩 열기", Modifier.weight(1f), quiet = true, enabled = !busy && opened.size < n) {
                open(listOf((0 until n).first { it !in opened }))
            }
            QuietButton("전체 공개", Modifier.weight(1f), enabled = !busy && opened.size < n) {
                open((0 until n).filter { it !in opened })
            }
            ConfirmButton(loser.takeIf { !busy }, Modifier.weight(1f)) { vm.finishGame(rideId, plan, it) }
        }
    }
}

@Composable
private fun LadderPlayer(name: String, color: Color, isOpen: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Look.colors
    Column(
        modifier.padding(horizontal = 6.dp)
            .clip(Look.shapes.compact)
            .background(color.copy(alpha = if (isOpen) 0.16f else 0.08f))
            .border(2.dp, color, Look.shapes.compact)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "경로 보기", onClick = onClick)
            .semantics { stateDescription = if (isOpen) "경로 열림" else "경로 미공개" }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(42.dp).background(c.surface, CircleShape).border(3.dp, color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(18.dp)) {
                val stroke = 3.dp.toPx()
                val tip = Offset(size.width / 2, size.height * 0.72f)
                drawLine(c.ink, Offset(stroke / 2, size.height * 0.28f), tip, stroke, StrokeCap.Round)
                drawLine(c.ink, tip, Offset(size.width - stroke / 2, size.height * 0.28f), stroke, StrokeCap.Round)
            }
        }
        Text(name, style = Look.type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** [column]에서 내려가는 길의 꺾이는 점들. 맨 위 → 줄마다(가로 막대면 옆으로) → 맨 아래. */
private fun ladderPoints(ladder: Ladder, column: Int, x: (Int) -> Float, y: (Int) -> Float, top: Float, bottom: Float, laneOffset: Float): List<Offset> {
    val path = ladder.pathOf(column)
    val points = mutableListOf(Offset(x(path[0]), top))
    ladder.rungs.indices.forEach { row ->
        val from = path[row]
        val to = path[row + 1]
        // 같은 가로 막대를 반대 방향으로 지나는 두 경로를 나란히 표시한다.
        val rungY = y(row) + to.compareTo(from) * laneOffset
        points += Offset(x(from), rungY)
        if (from != to) points += Offset(x(to), rungY)
    }
    points += Offset(x(path.last()), bottom)
    return points
}

/** 꺾은선을 앞에서부터 [fraction]만큼만 그린다(길이 기준). */
private fun DrawScope.drawPartial(points: List<Offset>, fraction: Float, color: Color, width: Float) {
    val pieces = points.zipWithNext()
    var left = pieces.sumOf { (a, b) -> (b - a).getDistance().toDouble() }.toFloat() * fraction
    for ((a, b) in pieces) {
        if (left <= 0f) break
        val d = (b - a).getDistance()
        val end = if (d <= left) b else a + (b - a) * (left / d)
        drawLine(color, a, end, strokeWidth = width, cap = StrokeCap.Round)
        left -= d
    }
}

// ---------------------------------------------------------------- C-14 컵 돌리기

/** C-14. 섞기를 시작할 때 공 위치가 정해지고, 차례대로 컵을 하나씩 고른다. 공 컵을 고른 사람이 걸린다. */
@Composable
fun ShellGameScreen(state: UiState, vm: RideViewModel, rideId: String, plan: GamePlan) {
    val ride = state.ride(rideId)
    val s = state.settlement(rideId)
    if (ride == null || s == null) return SettleEmpty("정산할 주행이 없어요", vm::goHome)
    val c = Look.colors
    val names = s.playerNames()
    val pot = ride.gamePot(plan)
    val n = plan.players.size
    var ball by remember { mutableStateOf<Int?>(null) }
    var shuffling by remember { mutableStateOf(false) }
    /** 컵 → 지금 놓인 자리. 컵은 다 똑같아 보이니 섞는 모양만 보여 준다. 고르기는 자리로 한다. */
    var place by remember { mutableStateOf(List(n) { it }) }
    var picks by remember { mutableStateOf(mapOf<Int, Int>()) }
    val loser = ball?.let { Games.shellLoser(it, picks) }
    val turn = if (loser == null) plan.players.firstOrNull { it !in picks } else null
    val ready = ball != null && !shuffling

    LaunchedEffect(shuffling) {
        if (!shuffling) return@LaunchedEffect
        repeat(SHUFFLES) {
            val a = Random.nextInt(n)
            val b = (a + 1 + Random.nextInt(n - 1)) % n
            place = place.toMutableList().also { it[a] = place[b]; it[b] = place[a] }
            delay(SHUFFLE_STEP_MS)
        }
        shuffling = false
    }

    SettleFrame(GameKind.SHELL.displayLabel, onBack = if (ball == null) ({ vm.openGamePick(rideId) }) else null, trailing = { GameTarget(plan, pot) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("공이 든 컵을 고른 사람이 ${dealText(plan, pot)}.", style = Look.type.body, color = c.ink)
            if (turn != null && ready) Badge("지금 차례 : ${names[turn]} (${picks.size + 1} / $n)", c.accent)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            // 컵 줄은 남는 높이 가운데에 둔다(결과 카드가 나오면 그만큼 올라간다).
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart,
            ) {
                val step = min(CUP_W + CUP_GAP, maxWidth / n)
                val start = (maxWidth - step * n) / 2
                (0 until n).forEach { cup ->
                    key(cup) {
                        val slot = place[cup]
                        val x by animateDpAsState(start + step * slot, tween(SHUFFLE_STEP_MS.toInt() - 40), label = "cup")
                        val owner = picks.entries.firstOrNull { it.value == slot }?.key
                        val lifted = owner != null || (loser != null && slot == ball)
                        val hasBall = lifted && slot == ball
                        // 컵을 들어 올릴 때 살짝 튀게(놓을 때도 같은 스프링).
                        val lift by animateDpAsState(if (lifted) CUP_LIFT else 0.dp, spring(dampingRatio = 0.55f, stiffness = 260f), label = "lift")
                        Column(
                            Modifier
                                .offset(x = x)
                                .width(step - CUP_GAP / 2),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.height(400.dp), contentAlignment = Alignment.BottomCenter) {
                                // 탁자에 진 그림자. 컵을 들면 옅어진다.
                                Canvas(Modifier.offset(y = 12.dp).size(width = CUP_SIZE.width, height = 26.dp)) {
                                    drawOval(Color.Black.copy(alpha = if (lifted) 0.05f else 0.10f))
                                }
                                if (hasBall) Ball(Modifier.padding(bottom = 4.dp).size(88.dp))
                                Cup(
                                    if (shuffling) null else slot + 1,
                                    Modifier
                                        .offset(y = lift)
                                        .size(CUP_SIZE)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            enabled = ready && turn != null && owner == null,
                                            role = Role.Button,
                                        ) {
                                            turn?.let { picks = picks + (it to slot) }
                                        }
                                        .semantics { contentDescription = "${slot + 1}번 컵" },
                                )
                            }
                            Text(
                                when {
                                    shuffling -> "섞는 중"
                                    owner != null -> names[owner] ?: "?"
                                    ready && turn != null -> "선택"
                                    else -> ""
                                },
                                style = Look.type.label, color = if (owner != null) c.ink else c.muted,
                            )
                        }
                    }
                }
            }
            SettleCard(Modifier.width(520.dp)) {
                Text("진행판", style = Look.type.label, color = c.ink)
                plan.players.forEach { id ->
                    val slot = picks[id]
                    val hit = id == loser
                    BoardRow(
                        names[id] ?: "?",
                        slot?.let { "${it + 1}번 컵" } ?: "",
                        when {
                            hit -> "공! ${hitLabel(plan).dropLast(1)}"
                            slot != null || loser != null -> "통과"
                            else -> null
                        },
                        hit,
                    )
                }
            }
        }
        if (loser != null) GameResultCard(ride, plan, loser, names)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PadButton("컵 섞기 시작", Modifier.weight(1f), quiet = true, enabled = ball == null) {
                ball = Games.hideBall(n)
                shuffling = true
            }
            ConfirmButton(loser, Modifier.weight(1f)) { vm.finishGame(rideId, plan, it) }
        }
    }
}

// ---------------------------------------------------------------- C-15 카드 뽑기

/** C-15. 참가자보다 한 장 많은 카드를 뒤집어 두고, 차례대로 한 장씩 고른다. 가장 높은 숫자가 걸린다. 동점이면 동점자끼리 다시. */
@Composable
fun CardGameScreen(state: UiState, vm: RideViewModel, rideId: String, plan: GamePlan) {
    val ride = state.ride(rideId)
    val s = state.settlement(rideId)
    if (ride == null || s == null) return SettleEmpty("정산할 주행이 없어요", vm::goHome)
    val c = Look.colors
    val names = s.playerNames()
    val pot = ride.gamePot(plan)
    var redraws by remember { mutableIntStateOf(0) }
    var players by remember { mutableStateOf(plan.players) }
    var cards by remember { mutableStateOf(Games.dealCards(plan.players.size + 1)) }
    var picks by remember { mutableStateOf(mapOf<Int, Int>()) }
    var scores by remember { mutableStateOf(mapOf<Int, List<Int>>()) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    val turn = players.firstOrNull { it !in picks }
    val loser = (outcome as? Outcome.Loser)?.id
    val tie = outcome as? Outcome.Tie
    val top = if (turn == null) picks.values.maxOfOrNull { cards[it] } else null

    LaunchedEffect(picks) {
        if (turn != null || outcome != null) return@LaunchedEffect
        delay(REVEAL_MS)
        outcome = Games.cardOutcome(cards, picks, redraws)
    }
    LaunchedEffect(tie) {
        val tied = tie ?: return@LaunchedEffect
        delay(TIE_MS)
        // 이번 판 숫자는 진행판에 남기고(예: 7 → 12), 동점자끼리 새 카드로 다시 뽑는다.
        scores = scores + picks.map { (id, card) -> id to (scores[id].orEmpty() + cards[card]) }
        redraws += 1
        players = tied.players
        cards = Games.dealCards(tied.players.size + 1)
        picks = emptyMap()
        outcome = null
    }

    SettleFrame("카드 뽑기", onBack = if (picks.isEmpty() && redraws == 0) ({ vm.openGamePick(rideId) }) else null, trailing = { GameTarget(plan, pot) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("가장 높은 숫자를 뽑은 사람이 ${dealText(plan, pot)}.", style = Look.type.body, color = c.ink)
            if (turn != null) Badge("지금 차례 : ${names[turn]} (${picks.size + 1} / ${players.size})", c.accent)
            if (redraws > 0 && loser == null) Badge("다시 뽑기 ${redraws}번째", c.warn)
        }
        // 카드 줄은 남는 높이 가운데에 둔다.
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val w = min(300.dp, (maxWidth - 20.dp * (cards.size - 1)) / cards.size)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally)) {
                    cards.forEachIndexed { index, value ->
                        // 다시 뽑을 때는 새 카드라서 뒤집힌 상태를 이어받지 않는다(뒤집히며 새 숫자가 비치지 않게).
                        key(redraws, index) {
                            val owner = picks.entries.firstOrNull { it.value == index }?.key
                            val highest = owner != null && top != null && value == top
                            // 걸린 사람이 정해지면 아무도 안 고른 카드도 흐리게 뒤집어 보여 준다(무엇이 남았는지).
                            val leftover = owner == null && loser != null
                            val flip by animateFloatAsState(if (owner != null || leftover) 180f else 0f, tween(FLIP_MS), label = "flip")
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(
                                    Modifier
                                        .size(width = w, height = w * 340 / 300)
                                        .graphicsLayer {
                                            rotationY = flip
                                            cameraDistance = 14f * density
                                        }
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            enabled = turn != null && owner == null,
                                            role = Role.Button,
                                        ) { turn?.let { picks = picks + (it to index) } }
                                        .semantics {
                                            contentDescription = when {
                                                owner != null -> "카드 $value"
                                                leftover -> "남은 카드 $value"
                                                else -> "${index + 1}번째 카드"
                                            }
                                        },
                                ) {
                                    if (flip <= 90f) {
                                        CardBack(Modifier.fillMaxSize())
                                    } else {
                                        CardFace(value, highest, leftover, Modifier.fillMaxSize().graphicsLayer { rotationY = 180f })
                                    }
                                }
                                Text(
                                    owner?.let { names[it] } ?: if (leftover) "남은 카드" else " ",
                                    style = Look.type.label,
                                    color = if (leftover) c.muted else c.ink,
                                )
                            }
                        }
                    }
                }
            }
            Text("뒤집힌 카드 = 이미 뽑은 카드 (빨간 테두리 = 가장 높은 숫자)", style = Look.type.caption, color = c.muted)
            if (tie != null) Text("동점이에요! 동점자끼리 다시 뽑아요", style = Look.type.label, color = c.warn)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            plan.players.forEach { id ->
                val mine = (scores[id].orEmpty() + listOfNotNull(picks[id]?.let { cards[it] }))
                Box(Modifier.weight(1f)) {
                    BoardRow(names[id] ?: "?", mine.joinToString(" → "), if (id == loser) hitLabel(plan) else if (loser != null) "통과" else null, id == loser)
                }
            }
        }
        if (loser != null) GameResultCard(ride, plan, loser, names)
        ConfirmButton(loser, Modifier.fillMaxWidth()) { vm.finishGame(rideId, plan, it) }
    }
}

private const val LADDER_MS = 1_400
private const val SHUFFLES = 9
private const val SHUFFLE_STEP_MS = 260L
private const val REVEAL_MS = 700L
private const val TIE_MS = 1_800L
private const val FLIP_MS = 420
private val CUP_W = 240.dp
private val CUP_GAP = 40.dp
private val CUP_SIZE = DpSize(230.dp, 250.dp)
private val CUP_LIFT = (-150).dp

// ---------------------------------------------------------------- 게임 그림

/** C-14 컵. 엎어 둔 컵(위가 좁고 아래 입구에 테두리)으로, C-12 컵 아이콘(MixedCup)과 같은 모양이다. */
@Composable
private fun Cup(number: Int?, modifier: Modifier) {
    val c = Look.colors
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val rimH = h * 0.13f
            val bottom = h - rimH * 0.5f
            val top = w * 0.17f
            val base = w * 0.06f
            val r = w * 0.07f
            // 왼쪽 옆선의 y에서의 x. 오른쪽은 대칭.
            fun left(y: Float) = top + (base - top) * (y / bottom)
            val body = Path().apply {
                moveTo(top + r, 0f)
                lineTo(w - top - r, 0f)
                quadraticBezierTo(w - top, 0f, w - left(r), r)
                lineTo(w - base, bottom)
                lineTo(base, bottom)
                lineTo(left(r), r)
                quadraticBezierTo(top, 0f, top + r, 0f)
                close()
            }
            drawPath(body, c.accent)
            // 왼쪽에 빛 받는 줄.
            val y1 = h * 0.10f
            val y2 = bottom - h * 0.08f
            val shine = Path().apply {
                moveTo(left(y1) + w * 0.07f, y1)
                lineTo(left(y1) + w * 0.13f, y1)
                lineTo(left(y2) + w * 0.13f, y2)
                lineTo(left(y2) + w * 0.07f, y2)
                close()
            }
            drawPath(shine, Color.White.copy(alpha = 0.22f))
            drawRoundRect(CUP_RIM, topLeft = Offset(0f, h - rimH), size = Size(w, rimH), cornerRadius = CornerRadius(rimH / 2))
        }
        if (number != null) Text("$number", Modifier.offset(y = (-12).dp), style = Look.type.title, color = c.onAccent)
    }
}

private val CUP_RIM = Color(0xFF2F86E6)

/** 컵 밑의 공. 노란 공에 왼쪽 위로 빛이 든다. */
@Composable
private fun Ball(modifier: Modifier) {
    val c = Look.colors
    Canvas(modifier.semantics { contentDescription = "공" }) {
        val radius = size.minDimension / 2
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFFFFE38F), c.action, Color(0xFFE39A00)),
                center = center + Offset(-radius * 0.35f, -radius * 0.35f),
                radius = radius * 1.4f,
            ),
            radius,
        )
    }
}

/** C-15 카드 뒷면. 파란 바탕에 빗금과 N빵 로고. */
@Composable
private fun CardBack(modifier: Modifier) {
    val c = Look.colors
    Box(modifier.clip(Look.shapes.card).background(c.accent), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            val line = 2.dp.toPx()
            val corner = CornerRadius(16.dp.toPx())
            val inner = Path().apply {
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, corner))
            }
            clipPath(inner) {
                val step = 22.dp.toPx()
                var x = -size.height
                while (x < size.width) {
                    drawLine(Color.White.copy(alpha = 0.12f), Offset(x, 0f), Offset(x + size.height, size.height), line)
                    drawLine(Color.White.copy(alpha = 0.12f), Offset(x + size.height, 0f), Offset(x, size.height), line)
                    x += step
                }
            }
            drawRoundRect(Color.White.copy(alpha = 0.5f), cornerRadius = corner, style = Stroke(line))
        }
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.fillMaxWidth(0.42f).aspectRatio(1f))
    }
}

/** C-15 카드 앞면. 가운데 큰 숫자와 두 귀퉁이 숫자. 가장 높은 숫자는 빨간 테두리, 남은 카드는 흐리게. */
@Composable
private fun CardFace(value: Int, highest: Boolean, leftover: Boolean, modifier: Modifier) {
    val c = Look.colors
    val ink = when {
        highest -> c.warn
        leftover -> c.muted
        else -> c.ink
    }
    Box(
        modifier
            .clip(Look.shapes.card)
            .background(if (leftover) c.raised else c.surface)
            .border(if (highest) 6.dp else 3.dp, if (highest) c.warn else c.line, Look.shapes.card)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("$value", Modifier.align(Alignment.TopStart), style = Look.type.label, color = ink)
        Text("$value", Modifier.align(Alignment.Center), style = Look.type.display, color = ink, textAlign = TextAlign.Center)
        Text("$value", Modifier.align(Alignment.BottomEnd).graphicsLayer { rotationZ = 180f }, style = Look.type.label, color = ink)
    }
}
