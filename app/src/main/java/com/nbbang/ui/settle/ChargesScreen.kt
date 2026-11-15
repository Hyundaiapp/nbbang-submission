// C-19 청구 현황(미납 관리). 상태 필터, 청구 목록(최신순), 상세 패널, 납부 확인, 청구서 보기.
// 데이터는 state.charges(청구서를 만들 때 남긴 것). 카풀 월 청구서는 여기서 QR로 다시 보여 준다.
package com.nbbang.ui.settle

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nbbang.RideViewModel
import com.nbbang.Screen
import com.nbbang.UiState
import com.nbbang.domain.Charge
import com.nbbang.domain.ChargeStatus
import com.nbbang.domain.PayLink
import com.nbbang.platform.Qr
import com.nbbang.ui.AccentButton
import com.nbbang.ui.Look
import com.nbbang.ui.QuietButton
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ChargesScreen(state: UiState, vm: RideViewModel, screen: Screen.ChargeList) {
    val c = Look.colors
    val now = System.currentTimeMillis()
    var filter by rememberSaveable(screen) { mutableStateOf(screen.status) }
    val all = state.charges.filter { it.won > 0 }.sortedByDescending { it.billedAt }
    val shown = all.filter { filter == null || it.status(now) == filter }
    var selectedId by rememberSaveable(screen) { mutableStateOf(screen.selectedId) }
    val selected = shown.firstOrNull { it.id == selectedId } ?: shown.firstOrNull()
    var monthlyQr by remember { mutableStateOf<Charge?>(null) }
    // 청구서 QR을 다시 보여 준다. 카풀 월 청구는 이 화면 위에, 여행 청구는 C-18로(돌아오면 같은 청구를 고른 채).
    fun open(charge: Charge) {
        val parts = charge.id.split(":")
        if (charge.monthly) monthlyQr = charge
        else parts.getOrNull(2)?.toIntOrNull()?.let { vm.openInvoice(parts[1], it, screen.copy(status = filter, selectedId = charge.id)) }
    }

    val onBack = {
        val back = screen.carpoolBack
        if (back == null) vm.goHome() else vm.openCarpoolMonthly(back.groupId, back.month)
    }
    BackHandler(enabled = screen.carpoolBack != null, onBack = onBack)
    SettleFrame("청구 현황", onBack = onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf<ChargeStatus?>(null, ChargeStatus.OVERDUE, ChargeStatus.BILLED, ChargeStatus.PAID).forEach { status ->
                val count = all.count { status == null || it.status(now) == status }
                Chip("${status?.label ?: "전체"} $count", filter == status) { filter = status }
            }
        }
        if (all.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("아직 만든 청구서가 없어요. 정산 후 청구서를 만들면 여기에 모여요.", style = Look.type.body, color = c.muted)
            }
            return@SettleFrame
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (shown.isEmpty()) item { Text("이 상태의 청구가 없어요.", style = Look.type.body, color = c.muted) }
                items(shown, key = { it.id }) { charge ->
                    ChargeRow(charge, now, state.zone, charge.id == selected?.id) { selectedId = charge.id }
                }
            }
            if (selected != null) {
                ChargeDetail(
                    selected, now, state.zone,
                    Modifier.width(620.dp),
                    onPaid = { vm.setChargePaid(selected.id, it) },
                    onOpen = { open(selected) },
                    onRemind = {
                        vm.remindCharge(selected.id)
                        open(selected)
                    },
                )
            }
        }
    }

    monthlyQr?.let { charge -> MonthlyInvoiceDialog(state, charge) { monthlyQr = null } }
}

@Composable
private fun ChargeRow(charge: Charge, now: Long, zone: ZoneId, selected: Boolean, onClick: () -> Unit) {
    val c = Look.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .clip(Look.shapes.card)
            .background(if (selected) c.accent.copy(alpha = 0.10f) else c.surface)
            .border(if (selected) 4.dp else 2.dp, if (selected) c.accent else c.line, Look.shapes.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(c.raised),
            contentAlignment = Alignment.Center,
        ) { Text(charge.name.take(1), style = Look.type.label, color = c.ink) }
        Column(Modifier.weight(1f)) {
            Text(charge.name, style = Look.type.label, color = c.ink)
            Text(shortLabel(charge, zone), style = Look.type.caption, color = c.muted)
        }
        // 일부만 받은 청구(받은 뒤 금액이 늘어남)는 남은 금액을 보여 준다.
        Text(won(if (charge.fullyPaid) charge.won else charge.dueWon), style = Look.type.heading, color = c.gold)
        StatusBadge(charge, now, zone)
    }
}

@Composable
private fun StatusBadge(charge: Charge, now: Long, zone: ZoneId) {
    val status = charge.status(now)
    Badge(
        when (status) {
            ChargeStatus.OVERDUE -> "미납 D+${charge.overdueDays(now, zone)}"
            else -> status.label
        },
        statusColor(status),
    )
}

@Composable
internal fun statusColor(status: ChargeStatus): Color = when (status) {
    ChargeStatus.OVERDUE -> Look.colors.warn
    ChargeStatus.PAID -> Look.colors.success
    ChargeStatus.BILLED -> Look.colors.accent
}

@Composable
private fun ChargeDetail(
    charge: Charge,
    now: Long,
    zone: ZoneId,
    modifier: Modifier,
    onPaid: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onRemind: () -> Unit,
) {
    val c = Look.colors
    val status = charge.status(now)
    val paid = status == ChargeStatus.PAID
    SettleCard(modifier.fillMaxHeight(), border = if (status == ChargeStatus.OVERDUE) c.warn else c.line) {
        Text("${charge.name}님 · ${shortLabel(charge, zone)}", style = Look.type.heading, color = c.ink)
        StatusBadge(charge, now, zone)
        Spacer(Modifier.heightIn(min = 8.dp))
        val partial = !charge.fullyPaid && charge.receivedWon > 0
        Text(if (partial) "남은 금액" else "청구 금액", style = Look.type.caption, color = c.muted)
        Text(won(if (partial) charge.dueWon else charge.won), style = Look.type.display, color = c.gold)
        if (partial) Text("전체 ${won(charge.won)} 중 ${won(charge.receivedWon)}은 받았어요", style = Look.type.caption, color = c.muted)
        Text(
            "청구일 ${DATE.format(at(charge.billedAt, zone))}  ·  납부 기한 ${DUE.format(at(charge.dueAt, zone))}" +
                (charge.paidAt?.let { "  ·  받은 날 ${DUE.format(at(it, zone))}" } ?: ""),
            style = Look.type.caption, color = c.muted,
        )
        Text(charge.label, style = Look.type.caption, color = c.muted)
        Spacer(Modifier.weight(1f))
        // 차에서 휴대폰으로 문자를 못 보낸다(SDK에 없음). 대신 차 스피커가 남은 금액을 말하고 QR을 띄운다.
        val due = !paid && charge.dueWon > 0
        val lastRemind = charge.remindedAt
        AccentButton("음성 리마인드", Modifier.fillMaxWidth(), enabled = due, prominent = true, onClick = onRemind)
        Text(
            when {
                !due -> "다 받은 청구예요."
                lastRemind != null -> "리마인드 ${charge.reminders}번 · 마지막 ${REMINDED.format(at(lastRemind, zone))}"
                else -> "차 스피커로 남은 금액을 알려 드리고 청구서 QR을 띄워요."
            },
            style = Look.type.caption, color = c.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PadButton(if (paid) "납부 취소" else "납부 확인 처리", Modifier.weight(1f), quiet = paid) { onPaid(!paid) }
            QuietButton("청구서 보기", Modifier.weight(1f), onClick = onOpen)
        }
    }
}

/** 카풀 월 청구서 QR(K-07과 같은 내용). 그 달 장부에서 다시 만든다. */
@Composable
private fun MonthlyInvoiceDialog(state: UiState, charge: Charge, onClose: () -> Unit) {
    val parts = charge.id.split(":")
    val month = parts.getOrNull(2)?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
    val gm = month?.let { state.groupMonth(parts[1], it) }
    val member = gm?.members?.firstOrNull { it.name == charge.name }
    val content = if (gm != null && member != null) PayLink.content(gm.invoice(member, state.settings.payee), state.payUrl) else null
    val title = "${charge.name} 님 · ${charge.label}"
    // QR(GroupMonth.invoice)과 같게: 받은 뒤 금액이 늘었으면 받은 만큼 뺀 남은 금액.
    val total = member?.won ?: charge.won
    // 다 받은 청구는 받은 금액을 그대로 보여 준다(그 뒤 계산이 바뀌어도).
    val amount = if (charge.fullyPaid) charge.won else total - (member?.charge?.receivedWon?.takeIf { it in 1 until total } ?: 0)
    val c = Look.colors
    val qr = remember(content) { content?.let { Qr.bitmap(it, 720)?.asImageBitmap() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .size(width = 900.dp, height = 860.dp)
                .clip(Look.shapes.popup)
                .background(c.surface)
                .padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = Look.type.heading, color = c.ink)
            Text(won(amount), style = Look.type.display, color = c.gold)
            if (charge.fullyPaid) {
                // 다 받은 청구는 QR을 숨긴다(또 보내지 않게).
                Spacer(Modifier.heightIn(min = 120.dp))
                Text("✓", style = Look.type.display, color = c.success)
                Text("다 받은 청구예요", style = Look.type.heading, color = c.ink)
            } else if (qr != null) {
                Image(qr, contentDescription = "$title 청구서 QR", modifier = Modifier.size(460.dp))
                Text("휴대폰 카메라로 찍으면 청구서가 열려요", style = Look.type.caption, color = c.muted)
            } else {
                Text("이 달 장부를 찾을 수 없어요(그룹이나 주행이 지워졌을 수 있어요).", style = Look.type.body, color = c.muted)
            }
            Spacer(Modifier.weight(1f))
            PadButton("닫기", Modifier.fillMaxWidth(), onClick = onClose)
        }
    }
}

/** 목록용 짧은 구분: "10월 카풀", "11/09 여행". */
@Suppress("UNUSED_PARAMETER")
private fun shortLabel(charge: Charge, zone: ZoneId): String =
    if (charge.monthly) charge.label.substringBefore(" · ") else charge.label

private fun at(millis: Long, zone: ZoneId) = Instant.ofEpochMilli(millis).atZone(zone)

private val DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd")
private val DUE = DateTimeFormatter.ofPattern("MM.dd")
private val REMINDED = DateTimeFormatter.ofPattern("MM.dd HH:mm")
