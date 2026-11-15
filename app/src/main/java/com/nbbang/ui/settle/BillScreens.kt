// C-17 최종 금액 확인, C-18 QR 청구서(탑승자별).
package com.nbbang.ui.settle

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nbbang.RideViewModel
import com.nbbang.Screen
import com.nbbang.UiState
import com.nbbang.domain.Charge
import com.nbbang.domain.Charges
import com.nbbang.domain.Gear
import com.nbbang.domain.PayLink
import com.nbbang.domain.PayRequest
import com.nbbang.platform.Qr
import com.nbbang.settle.Share
import com.nbbang.settle.gameLine
import com.nbbang.settle.invoice
import com.nbbang.settle.riderId
import com.nbbang.ui.AccentButton
import com.nbbang.domain.Power
import com.nbbang.domain.TripMode
import com.nbbang.ui.Look
import com.nbbang.ui.QuietButton
import kotlin.math.roundToInt

/** C-17 인원별 최종 금액표와 합계 검증(R9). 검증이 안 되면 청구서를 만들 수 없다. */
@Composable
fun FinalCheckScreen(state: UiState, vm: RideViewModel, rideId: String) {
    val ride = state.ride(rideId)
    val s = state.settlement(rideId)
    if (ride == null || s == null) return SettleEmpty("정산할 주행이 없어요", vm::goHome)
    val c = Look.colors
    var confirmEdit by remember { mutableStateOf(false) }
    val gameLine = ride.gameLine(s)
    val loserId = ride.game?.loser
    val ok = s.readyToBill
    val nobodyOwes = s.riders.none { it.finalWon > 0 }

    SettleFrame("최종 금액 확인", onBack = { confirmEdit = true }, trailing = {
        Badge(
            when {
                !s.balanced -> "합계가 맞지 않아요"
                s.pendingTolls > 0 -> "미입력 톨비 ${s.pendingTolls}건"
                else -> "합계 검증 완료"
            },
            if (ok) c.success else c.warn,
        )
        Spacer(Modifier.width(16.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("총 정산 금액", style = Look.type.caption, color = c.muted)
            Text(won(s.costWon), style = Look.type.display, color = c.gold)
        }
    }) {
        // 사람이 많거나(6~7명) 게임·환불 안내가 붙으면 화면을 넘는다. 표와 안내만 스크롤하고 버튼은 늘 아래에 둔다.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettleCard(Modifier.fillMaxWidth()) {
                TableRow(listOf("", if (ride.power == Power.ELECTRIC) "충전비·톨비 몫" else "유류비·톨비 몫", "추가 비용 몫", "절약 기여", "게임 조정", "최종 금액"), header = true)
                s.shares.forEach { share ->
                    val isLoser = loserId != null && share.riderId() == loserId && share.gameWon != 0.0
                    TableRow(
                        listOf(
                            if (share.person.isDriver) "나 (운전자)" else share.person.name,
                            won(share.rideWon),
                            won(share.expenseWon - share.prepaidWon),
                            signed(share.savingWon.roundToInt()),
                            if (ride.game == null) "-" else signed(share.gameWon.roundToInt()),
                            finalText(share, short = true),
                        ),
                        share = share,
                        loser = isLoser,
                    )
                }
            }
            if (gameLine != null) {
                SettleCard(Modifier.fillMaxWidth(), border = c.action, background = c.action.copy(alpha = 0.10f)) {
                    Text(gameLine, style = Look.type.heading, color = c.ink)
                }
            }
            val refunds = s.riders.filter { it.finalWon < 0 }
            if (refunds.isNotEmpty()) {
                Text(
                    refunds.joinToString(" ") { "${it.person.name} 님은 먼저 낸 돈이 더 많아서 ${won(-it.finalWon)}을 돌려받아요." } + " 이 돈은 청구서 없이 운전자가 직접 돌려주세요.",
                    style = Look.type.body, color = c.success,
                )
            }
            if (ride.mode == TripMode.CARPOOL && ride.monthly) {
                Text("이 주행은 카풀 월별 정산에 들어가요. 청구서는 월말에 한 번에 만들어요.", style = Look.type.body, color = c.accent)
            }
            Text("운전자 본인은 청구 대상에서 빠져요. " + roundingNote(s.roundingWon), style = Look.type.caption, color = c.muted)
            if (nobodyOwes && ok) {
                Text("동승자가 낼 돈이 없어서 청구서 없이 정산을 마쳐요.", style = Look.type.body, color = c.accent)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            QuietButton("수정하기", Modifier.weight(1f)) { confirmEdit = true }
            if (ride.mode == TripMode.CARPOOL && ride.monthly) {
                // 월별 정산에 넣은 카풀 주행은 월말에 K-07로 한 번에 청구한다. 여기서 또 만들면 두 번 청구된다.
                AccentButton("월별 정산에 넣기", Modifier.weight(1f), enabled = ok, prominent = true) { vm.goHome() }
            } else if (nobodyOwes) {
                // 운전자가 전체 금액 독박에 걸렸거나 모두 돌려받는 경우: 만들 청구서가 없다. 게임 결과는 그대로 두고 마친다.
                AccentButton("정산 마치기", Modifier.weight(1f), enabled = ok, prominent = true) { vm.goHome() }
            } else {
                AccentButton("청구서 생성", Modifier.weight(1f), enabled = ok, prominent = true) {
                    vm.makeInvoices(rideId)
                }
            }
        }
    }

    if (confirmEdit) {
        Dialog(onDismissRequest = { confirmEdit = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier
                    .size(width = 850.dp, height = 400.dp)
                    .clip(Look.shapes.popup)
                    .background(c.surface)
                    .padding(40.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text("정산을 고칠까요?", style = Look.type.heading, color = c.ink)
                Text("고치면 게임 결과와 절약 기여 결과가 초기화돼요. 다시 정할 수 있어요.", style = Look.type.body, color = c.ink)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PadButton("그대로 두기", Modifier.weight(1f), quiet = true) { confirmEdit = false }
                    PadButton("수정하기", Modifier.weight(1f)) {
                        confirmEdit = false
                        vm.editSettlement(rideId)
                    }
                }
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean = false, share: Share? = null, loser: Boolean = false) {
    val c = Look.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (header) 56.dp else 100.dp)
            .clip(Look.shapes.compact)
            .background(if (loser) c.warn.copy(alpha = 0.08f) else c.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { i, text ->
            val color = when {
                header -> c.muted
                i == 5 && share != null -> finalColor(share)
                i == 5 -> c.gold
                i == 3 && share != null && share.savingWon < -0.5 -> c.success
                i == 3 && share != null && share.savingWon > 0.5 -> c.warn
                i == 4 && share != null && share.gameWon < -0.5 -> c.success
                i == 4 && share != null && share.gameWon > 0.5 -> c.warn
                else -> c.ink
            }
            Row(Modifier.weight(if (i == 0 || i == 5) 1.3f else 1f).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text,
                    style = when {
                        header -> Look.type.caption
                        i == 5 && (share?.finalWon ?: 0) < 0 -> Look.type.label
                        i == 5 -> Look.type.heading
                        else -> Look.type.label
                    },
                    maxLines = 1,
                    color = color,
                    textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
                if (i == 0 && loser) Badge("독박왕", c.warn)
            }
        }
    }
}

/** C-18 탑승자별 QR 청구서. 운전자 계좌와 그 사람 금액이 담긴 청구서 페이지 주소를 QR로 보여 준다. */
@Composable
fun InvoiceScreen(state: UiState, vm: RideViewModel, rideId: String, personId: Int? = null, chargesBack: Screen.ChargeList? = null) {
    val fromCharges = chargesBack != null
    val toCharges = { vm.openChargeList(chargesBack ?: Screen.ChargeList()) }
    val ride = state.ride(rideId)
    val s = state.settlement(rideId)
    if (ride == null || s == null) return SettleEmpty("정산할 주행이 없어요", vm::goHome)
    val targets = s.riders.filter { it.finalWon > 0 }
    if (targets.isEmpty()) return SettleEmpty("청구할 사람이 없어요", vm::goHome)
    val start = targets.indexOfFirst { it.riderId() == personId }
    // C-19에서 연 사람이 다시 계산해서 낼 돈이 없어졌으면 다른 사람 청구서를 대신 보여 주지 않는다.
    if (personId != null && start < 0) return SettleEmpty("이 사람에게는 이제 청구할 금액이 없어요", toCharges)
    var selected by rememberSaveable(rideId, personId) { mutableIntStateOf(start.coerceAtLeast(0)) }
    val share = targets[selected.coerceIn(0, targets.lastIndex)]
    val charge = state.charges.firstOrNull { it.id == Charges.rideId(rideId, share.riderId()) }
    InvoicePage(
        invoice = ride.invoice(share, s, state.settings.payee, state.zone, receivedWon = charge?.receivedWon ?: 0),
        payUrl = state.payUrl,
        charge = charge,
        onBack = { if (fromCharges) toCharges() else vm.openFinalCheck(rideId) },
        onClose = { if (fromCharges) toCharges() else vm.goHome() },
        onSetPaid = { vm.setChargePaid(it.id, !it.fullyPaid) },
        onAnnounce = vm::announceInvoice,
        tabs = { InvoiceTabs(targets.map { it.person.name }, selected) { selected = it } },
    )
}

@Composable
internal fun MonthlyInvoiceScreen(state: UiState, vm: RideViewModel, screen: Screen.MonthlyInvoices) {
    val billing = screen.billing
    val back = { vm.openCarpoolMonthlyBilling(billing.groupId, billing.month, billing.selectedName) }
    BackHandler(onBack = back)
    if (state.restricted || state.car.gear != Gear.PARK) {
        return SettleEmpty("주차(P) 후에 월별 청구서를 볼 수 있어요", back)
    }
    val model = remember(billing.groupId, billing.month, state.groups, state.rides, state.charges, state.zone) {
        state.groupMonth(billing.groupId, billing.month)
    } ?: return SettleEmpty("카풀 정산을 찾을 수 없어요", back)
    val targets = model.members.filter { it.name in screen.names && it.charge != null }
    if (targets.isEmpty()) return SettleEmpty("생성된 월별 청구서가 없어요", back)
    var selected by rememberSaveable(screen) { mutableIntStateOf(0) }
    val index = selected.coerceIn(0, targets.lastIndex)
    val member = targets[index]
    InvoicePage(
        invoice = model.invoice(member, state.settings.payee),
        payUrl = state.payUrl,
        charge = member.charge,
        onBack = back,
        onClose = back,
        onSetPaid = { vm.setChargePaid(it.id, !it.fullyPaid) },
        onAnnounce = vm::announceInvoice,
        tabs = { if (targets.size > 1) InvoiceTabs(targets.map { it.name }, index) { selected = it } },
    )
}

@Composable
private fun InvoiceTabs(names: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        names.forEachIndexed { index, name -> Chip(name, index == selected) { onSelect(index) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InvoicePage(
    invoice: PayRequest,
    payUrl: String,
    charge: Charge?,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onSetPaid: (Charge) -> Unit,
    onAnnounce: (String, Int) -> Unit,
    tabs: @Composable () -> Unit,
) {
    val c = Look.colors
    val (name, amount) = invoice.items.single()
    val payee = invoice.payee
    val content = PayLink.content(invoice, payUrl)
    val qr = remember(content) { Qr.bitmap(content, 720)?.asImageBitmap() }
    val clipboard = LocalClipboardManager.current
    var copied by remember(name, payee.account) { mutableStateOf(false) }
    val now = System.currentTimeMillis()

    SettleFrame("QR 청구서", onBack = onBack, trailing = { tabs() }) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.width(520.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("$name 님", style = Look.type.heading, color = c.ink)
                Text(won(amount), style = Look.type.display, color = c.gold)
                // 1원 단위로 내리고 남은 돈. 내역 줄 대신 크게 보여 준다(내역 합은 금액보다 이만큼 크다).
                if (invoice.nbbangWon > 0) {
                    Text(
                        "${won(invoice.nbbangWon)}은 N빵이 내요",
                        style = Look.type.heading, color = c.accentText,
                        modifier = Modifier
                            .clip(Look.shapes.pill)
                            .background(c.accent.copy(alpha = 0.14f))
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
                // 다 받은 청구는 QR을 숨긴다. 보이면 또 보낼 수 있다(두 번 받기).
                val paidUp = charge?.fullyPaid == true
                Box(
                    Modifier
                        .size(460.dp)
                        .clip(Look.shapes.qr)
                        .background(if (paidUp) c.success.copy(alpha = 0.10f) else c.qrBackground)
                        .border(3.dp, if (paidUp) c.success else c.line, Look.shapes.qr),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        paidUp -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("✓", style = Look.type.display, color = c.success)
                            Text("다 받은 청구예요", style = Look.type.heading, color = c.ink)
                        }
                        qr != null -> Image(qr, contentDescription = "$name 님 청구서 QR", modifier = Modifier.size(430.dp))
                        else -> Text("QR을 만들지 못했어요", style = Look.type.body, color = c.warn)
                    }
                }
                Text(
                    if (paidUp) "[받았어요 ✓]를 다시 누르면 받기 전으로 돌아가요" else "휴대폰 카메라로 찍으면 청구서가 열려요",
                    style = Look.type.caption, color = c.muted,
                )
                charge?.let {
                    val status = it.status(now)
                    Badge(status.label, statusColor(status))
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                SettleCard(Modifier.fillMaxWidth()) {
                    Text("받는 분", style = Look.type.caption, color = c.muted)
                    Text(payee.account.ifBlank { "계좌를 C-02 설정에서 넣어 주세요" }, style = Look.type.heading, color = if (payee.account.isBlank()) c.warn else c.ink)
                    if (payee.name.isNotBlank()) Text("예금주 ${payee.name}", style = Look.type.body, color = c.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        QuietButton(if (copied) "복사했어요" else "계좌 복사", Modifier.weight(1f), enabled = payee.account.isNotBlank()) {
                            clipboard.setText(AnnotatedString(payee.account))
                            copied = true
                        }
                        QuietButton("음성으로 안내", Modifier.weight(1f), enabled = amount > 0 && charge?.fullyPaid != true) { onAnnounce(name, amount) }
                    }
                    Text("차에서 휴대폰으로 문자를 보낼 수 없어서, 차 스피커로 금액을 알려 드리고 QR로 받아요.", style = Look.type.caption, color = c.muted)
                }
                invoice.game?.let { line ->
                    SettleCard(Modifier.fillMaxWidth(), border = c.action, background = c.action.copy(alpha = 0.10f)) {
                        Text("게임 결과", style = Look.type.caption, color = c.muted)
                        Text(line, style = Look.type.heading, color = c.ink)
                    }
                }
                SettleCard(Modifier.fillMaxWidth()) {
                    Text("비용 내역", style = Look.type.caption, color = c.muted)
                    invoice.costs.forEach { (name, amount) ->
                        MoneyRow(name, if (amount < 0) signed(amount) else won(amount), wonColor = if (amount < 0) c.success else c.ink)
                    }
                    MoneyRow("합계", won(amount), wonColor = c.gold)
                }
                SettleCard(Modifier.fillMaxWidth()) {
                    Text("주행 정보", style = Look.type.caption, color = c.muted)
                    Text(invoice.title, style = Look.type.label, color = c.ink)
                    FlowRow(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        invoice.notes.forEach { Text(it, style = Look.type.body, color = c.ink, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            QuietButton(
                if (charge?.fullyPaid == true) "받았어요 ✓" else "받았어요",
                Modifier.weight(1f),
                enabled = charge != null,
            ) { charge?.let(onSetPaid) }
            AccentButton("청구서 닫기", Modifier.weight(1f), onClick = onClose)
        }
    }
}
