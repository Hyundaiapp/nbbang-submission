// C-06 하이패스 통행료 입력 및 시간 초과 안내.
package com.nbbang.ui.driving

import android.os.SystemClock
import android.view.SoundEffectConstants
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nbbang.domain.Lines
import com.nbbang.ui.Look
import kotlinx.coroutines.delay

private const val INPUT_MS = 10_000L
private const val NOTICE_MS = 3_000L
private val QUICK_AMOUNTS = listOf(3_200, 3_100, 4_800, 6_400)

@Composable
internal fun TollPopup(name: String, initialWon: Int, onResolve: (won: Int?, timedOut: Boolean) -> Unit) {
    val colors = Look.colors
    val view = LocalView.current
    val deadline = remember { SystemClock.elapsedRealtime() + INPUT_MS }
    var remainingMs by remember { mutableLongStateOf(INPUT_MS) }
    var amount by remember { mutableIntStateOf(initialWon) }
    var replaceAmount by remember { mutableStateOf(true) }
    var resolved by remember { mutableStateOf(false) }

    fun resolve(won: Int?) {
        if (resolved) return
        resolved = true
        val timedOut = SystemClock.elapsedRealtime() >= deadline
        onResolve(if (timedOut) null else won, timedOut)
    }

    LaunchedEffect(deadline) {
        view.playSoundEffect(SoundEffectConstants.CLICK)
        while (!resolved) {
            remainingMs = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            if (remainingMs == 0L) {
                resolve(null)
                break
            }
            delay(minOf(100L, remainingMs))
        }
    }

    DrivingAmountFrame("하이패스 통과", headerEnd = {
        val seconds = (remainingMs + 999L) / 1_000L
        Box(
            Modifier.size(48.dp).background(colors.warn, Look.shapes.pill)
                .semantics { contentDescription = "남은 시간 ${seconds}초" },
            contentAlignment = Alignment.Center,
        ) {
            Text(seconds.toString(), style = Look.type.popupName, color = colors.onAccent)
        }
    }) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(Look.shapes.pill).background(colors.raised)) {
            Box(
                Modifier.fillMaxWidth(remainingMs.toFloat() / INPUT_MS)
                    .fillMaxHeight().background(colors.warn),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(
                Modifier.weight(1.5f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${name.ifBlank { "톨게이트" }} 통행료를 입력하세요",
                    style = Look.type.popupName,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier.fillMaxWidth().weight(1f)
                        .border(2.dp, colors.accent, Look.shapes.input)
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("통행료", style = Look.type.popupCaption, color = colors.muted)
                    Spacer(Modifier.weight(1f))
                    Text(
                        Lines.won(amount),
                        style = Look.type.title,
                        color = colors.gold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (quickAmount in QUICK_AMOUNTS) {
                        val selected = amount == quickAmount
                        Box(
                            Modifier.weight(1f).fillMaxHeight().clip(Look.shapes.pill)
                                .background(if (selected) colors.accent else colors.surface)
                                .border(1.dp, colors.accent, Look.shapes.pill)
                                .clickable(role = Role.Button) {
                                    amount = quickAmount
                                    replaceAmount = true
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                Lines.won(quickAmount),
                                style = Look.type.popupCaption,
                                color = if (selected) colors.onAccent else colors.accent,
                            )
                        }
                    }
                }
            }
            DrivingAmountKeypad(amount, Modifier.weight(1f).fillMaxHeight(), replaceOnDigit = replaceAmount) {
                amount = it
                replaceAmount = false
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PopupButton("나중에 입력", colors.raised, colors.ink, Modifier.weight(1f).height(70.dp)) {
                resolve(null)
            }
            PopupButton(
                "입력 완료",
                if (amount > 0) colors.accent else colors.raised,
                if (amount > 0) colors.onAccent else colors.muted,
                Modifier.width(530.dp).height(70.dp),
                enabled = amount > 0,
            ) {
                resolve(amount)
            }
        }
    }
}

@Composable
internal fun TollDeferredPopup(pendingCount: Int, onDone: () -> Unit) {
    val colors = Look.colors
    LaunchedEffect(Unit) {
        delay(NOTICE_MS)
        onDone()
    }
    DrivingAmountFrame("나중에 입력으로 전환했어요") {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Column(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(92.dp).background(colors.action.copy(alpha = 0.12f), Look.shapes.pill)
                    .border(2.dp, colors.action, Look.shapes.pill),
                contentAlignment = Alignment.Center,
            ) {
                Text("!", style = Look.type.title, color = colors.accentText)
            }
            Text("입력 시간이 지나 통행료를 미입력으로 남겼어요.", style = Look.type.popupName, color = colors.ink)
            Text("정산 화면에서 통행료를 입력할 수 있어요.", style = Look.type.popupCaption, color = colors.muted)
            Text(
                "미입력 톨비 ${pendingCount}건",
                style = Look.type.popupCaption,
                color = colors.warn,
                modifier = Modifier.background(colors.warn.copy(alpha = 0.10f), Look.shapes.pill)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        Text(
            "3초 후 자동으로 닫혀요",
            style = Look.type.popupCaption,
            color = colors.muted,
            modifier = Modifier.align(Alignment.End),
        )
    }
}
