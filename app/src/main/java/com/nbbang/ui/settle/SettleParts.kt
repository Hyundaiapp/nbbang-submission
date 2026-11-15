// 정산 흐름 화면(C-08~C-18)이 같이 쓰는 조각. 색·글자·모서리는 ui/Theme 토큰만 쓴다.
package com.nbbang.ui.settle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nbbang.domain.Lines
import com.nbbang.domain.DRIVER_ID
import com.nbbang.domain.Ride
import com.nbbang.domain.rootId
import com.nbbang.settle.Share
import com.nbbang.ui.Look
import java.util.Locale

/** 탑승자별 주행거리계 구간을 출발점을 기준으로 표시한다. */
internal fun rangeText(ride: Ride, person: Int, total: Double): String {
    if (person == DRIVER_ID) return String.format(Locale.KOREA, "0.0 ~ %.1fkm (전 구간)", total)
    val ids = ride.riders.filter { ride.rootId(it.id) == person }.map { it.id }.toSet()
    val mine = ride.segments.filter { seg -> seg.riderIds.any { it in ids } }
    if (mine.isEmpty()) return "탄 구간 없음"
    val base = ride.segments.first().startKm
    val from = mine.minOf { it.startKm } - base
    val to = mine.maxOf { it.endKm } - base
    val note = when {
        from <= 0.05 && to >= total - 0.05 -> "전 구간"
        from > 0.05 && to < total - 0.05 -> "중간 탑승·하차"
        from > 0.05 -> "중간 탑승"
        else -> "중간 하차"
    }
    return String.format(Locale.KOREA, "%.1f ~ %.1fkm (%s)", from, to, note)
}

/** 전체 화면 위쪽 줄(104dp). 왼쪽 〈 와 제목, 오른쪽은 화면마다. [onBack]이 null이면 뒤로 가기를 숨긴다(게임 진행 중). */
@Composable
internal fun SettleHeader(title: String, onBack: (() -> Unit)?, trailing: @Composable RowScope.() -> Unit = {}) {
    val c = Look.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(104.dp)
            .padding(horizontal = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(80.dp)
                    .clip(Look.shapes.compact)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("〈", style = Look.type.title, color = c.ink) }
        } else {
            Spacer(Modifier.width(24.dp))
        }
        Text(title, style = Look.type.title, color = c.ink)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** 전체 화면 틀: 머리줄 + 연한 회색 바탕 본문. */
@Composable
internal fun SettleFrame(
    title: String,
    onBack: (() -> Unit)?,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Look.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.surface),
    ) {
        SettleHeader(title, onBack, trailing)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(c.raised.copy(alpha = 0.4f))
                .padding(horizontal = 40.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content,
        )
    }
}

/** 흰 카드. */
@Composable
internal fun SettleCard(modifier: Modifier = Modifier, border: Color = Look.colors.line, background: Color = Look.colors.surface, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(Look.shapes.card)
            .background(background)
            .border(3.dp, border, Look.shapes.card)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** 금액 한 줄. 누를 수 있으면 [onClick]. */
@Composable
internal fun MoneyRow(
    label: String,
    won: String,
    detail: String? = null,
    wonColor: Color = Look.colors.ink,
    badge: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Look.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(Look.shapes.compact)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(label, style = Look.type.label, color = c.ink)
                badge?.invoke()
            }
            if (detail != null) Text(detail, style = Look.type.caption, color = c.muted)
        }
        Text(won, style = Look.type.label, color = wonColor)
    }
}

/** 작은 배지(자동 추가, 공시 평균, 독박왕 등). */
@Composable
internal fun Badge(text: String, color: Color, onColor: Color = Look.colors.surface) {
    Box(
        Modifier
            .clip(Look.shapes.pill)
            .background(color)
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) { Text(text, style = Look.type.caption, color = onColor) }
}

/** 고르는 칩(선택 = Sky Blue 채움). */
@Composable
internal fun Chip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Look.colors
    Box(
        Modifier
            .heightIn(min = 72.dp)
            .clip(Look.shapes.pill)
            .background(if (selected) c.accent else c.surface)
            .border(3.dp, if (enabled) c.accent else c.line, Look.shapes.pill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Look.type.label, color = if (selected) c.onAccent else if (enabled) c.ink else c.muted)
    }
}

/** 켜고 끄는 스위치(ON = Success Green). */
@Composable
internal fun OnOff(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Look.colors
    Box(
        Modifier
            .size(width = 120.dp, height = 64.dp)
            .clip(Look.shapes.pill)
            .background(if (on) c.success else c.raised)
            .clickable { onChange(!on) }
            .padding(6.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(Look.shapes.pill)
                .background(c.surface),
        )
    }
}

/**
 * 숫자패드로 금액 하나를 받는 창(키보드 대신). 기획서 C-08 단가 수정, C-09 톨비, C-10 금액.
 * [extra]는 왼쪽 아래 보조 버튼(예: "통행료 없음 0원", "삭제").
 */
@Composable
internal fun NumberPadDialog(
    title: String,
    initial: Int?,
    hint: String? = null,
    unit: String = "원",
    extra: List<Pair<String, () -> Unit>> = emptyList(),
    allowZero: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val c = Look.colors
    var digits by remember { mutableStateOf(initial?.takeIf { it > 0 }?.toString().orEmpty()) }
    val value = digits.toIntOrNull()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier
                .size(width = 1100.dp, height = 600.dp)
                .clip(Look.shapes.popup)
                .background(c.surface)
                .padding(40.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(title, style = Look.type.heading, color = c.ink)
                if (hint != null) Text(hint, style = Look.type.caption, color = c.muted)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(Look.shapes.input)
                        .border(3.dp, c.accent, Look.shapes.input)
                        .padding(horizontal = 28.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text(
                        if (value == null) "0$unit" else String.format(java.util.Locale.KOREA, "%,d%s", value, unit),
                        style = Look.type.display, color = c.gold,
                    )
                }
                Spacer(Modifier.weight(1f))
                extra.forEach { (label, action) -> PadButton(label, Modifier.fillMaxWidth(), quiet = true, onClick = action) }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PadButton("취소", Modifier.weight(1f), quiet = true, onClick = onDismiss)
                    PadButton("확인", Modifier.weight(1f), enabled = value != null && (allowZero || value > 0)) { onConfirm(value ?: 0) }
                }
            }
            NumberPad(Modifier.width(420.dp)) { digits = pressKey(digits, it) }
        }
    }
}

/** 3×4 숫자패드(키보드 대신). 누른 키를 [onKey]로 넘긴다. 금액 글자는 [pressKey]로 고친다. */
@Composable
internal fun NumberPad(modifier: Modifier = Modifier, keyHeight: androidx.compose.ui.unit.Dp = 118.dp, onKey: (String) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("00", "0", "⌫")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { key -> PadButton(key, Modifier.weight(1f).height(keyHeight), quiet = true) { onKey(key) } }
            }
        }
    }
}

/** 숫자패드 키 하나를 금액 글자에 반영한다. 앞자리 0은 지우고 7자리(999만 원대)까지. */
internal fun pressKey(digits: String, key: String): String = when (key) {
    "⌫" -> digits.dropLast(1)
    else -> (digits + key).trimStart('0').take(7)
}

@Composable
internal fun PadButton(text: String, modifier: Modifier = Modifier, quiet: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Look.colors
    Box(
        modifier
            .heightIn(min = 90.dp)
            .clip(Look.shapes.button)
            .background(if (quiet) c.raised else if (enabled) c.accent else c.raised)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Look.type.button, color = if (!enabled) c.muted else if (quiet) c.ink else c.onAccent, textAlign = TextAlign.Center)
    }
}

/** 화면 가운데 짧은 안내(정산할 주행이 없을 때 등). */
@Composable
internal fun SettleEmpty(text: String, onHome: () -> Unit) {
    val c = Look.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.surface),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = Look.type.heading, color = c.muted)
        Spacer(Modifier.height(24.dp))
        PadButton("홈으로", Modifier.width(320.dp), onClick = onHome)
    }
}

internal fun won(value: Int): String = Lines.won(value)

/** 화면에 보일 원 단위(반올림). */
internal fun won(value: Double): String = Lines.won(Math.round(value).toInt())

/**
 * 최종 금액 글자. 별도 결제를 먼저 낸 사람은 몫보다 낸 돈이 많아 음수가 될 수 있다.
 * 기획서 C-10 유의 5(음수면 환급)대로 "돌려받을 돈"으로 보인다.
 */
internal fun finalText(share: Share, short: Boolean = false): String =
    if (share.finalWon < 0 && !share.person.isDriver) "${if (short) "환급" else "돌려받을 돈"} ${won(-share.finalWon)}" else won(share.finalWon)

/** R8 끝전 안내. 1원 단위로 내리고 남은 몇 원은 카카오페이 정산처럼 N빵이 낸다. */
internal fun roundingNote(roundingWon: Int): String =
    if (roundingWon > 0) "1원 단위로 나누고, 딱 나눠지지 않는 ${won(roundingWon)}은 N빵이 내요." else "1원 단위까지 딱 맞게 나눴어요."

@Composable
internal fun finalColor(share: Share): Color = when {
    !share.optedIn -> Look.colors.muted
    share.finalWon < 0 && !share.person.isDriver -> Look.colors.success
    else -> Look.colors.gold
}

/** 부호를 붙인 금액(+650원, -1,300원). 0이면 "-". */
internal fun signed(value: Int): String = when {
    value > 0 -> "+" + Lines.won(value)
    value < 0 -> "-" + Lines.won(-value)
    else -> "-"
}
