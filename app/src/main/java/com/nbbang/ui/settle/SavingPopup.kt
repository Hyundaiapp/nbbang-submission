// C-11 절약 기여 평가 팝업(850×440). 실제 연비가 평균(기준)보다 좋을 때만 C-08(카풀은 K-04 종료)이 처음 열릴 때 띄운다.
// 유류비는 실제로 쓴 만큼 나눠 두고, 인정하면 동승자는 평균(기준) 연비 기준 금액을 내고 그만큼 운전자 몫을 줄인다(R6).
// 차종을 골랐으면 기준은 공인연비를 이번 주행의 도심·고속 비중으로 섞은 기준연비다. 어떻게 정했는지와 "추정"을 함께 보여 준다.
package com.nbbang.ui.settle

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nbbang.domain.Power
import com.nbbang.domain.Ride
import com.nbbang.settle.actualEfficiency
import com.nbbang.settle.expectedEfficiency
import com.nbbang.settle.expectedName
import com.nbbang.settle.explain
import com.nbbang.settle.officialBaseline
import com.nbbang.settle.settlement
import com.nbbang.ui.AccentButton
import com.nbbang.ui.Look
import java.util.Locale

@Composable
internal fun SavingPopup(ride: Ride, onDecide: (Boolean) -> Unit, onClose: () -> Unit) {
    val c = Look.colors
    val before = ride.copy(savingAccepted = false).settlement()
    val after = ride.copy(savingAccepted = true).settlement()
    val expected = ride.expectedEfficiency()
    val baseline = ride.officialBaseline()
    val name = ride.expectedName()
    val actual = ride.actualEfficiency() ?: expected
    val electric = ride.power == Power.ELECTRIC
    val unit = if (electric) "km/kWh" else "km/L"
    val word = if (electric) "전비" else "연비"
    var showResult by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(
            Modifier
                // 결과는 한 사람에 한 줄. 사람이 많으면(5~7명) 키우고, 그래도 넘치면 줄만 스크롤해서 [확인]은 늘 보인다.
                .size(width = 850.dp, height = if (showResult) (300 + 56 * after.shares.size).coerceIn(520, 900).dp else if (baseline != null) 500.dp else 440.dp)
                .clip(Look.shapes.popup)
                .background(c.surface)
                .padding(36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!showResult) {
                Text("운전자의 절약 기여를 인정할까요?", style = Look.type.popupTitle, color = c.ink)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Bar("$name $word", expected, actual.coerceAtLeast(expected), unit, c.raised, c.muted)
                        Bar("실제 $word", actual, actual.coerceAtLeast(expected), unit, c.success, c.success)
                    }
                    Column(
                        Modifier
                            .width(260.dp)
                            .clip(Look.shapes.card)
                            .background(c.success.copy(alpha = 0.12f))
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(if (electric) "아낀 충전비 (추정)" else "아낀 유류비 (추정)", style = Look.type.caption, color = c.muted)
                        Text(won(after.savingWon), style = Look.type.title, color = c.success)
                    }
                }
                Column {
                    // 기준을 어떻게 정했는지. 공인연비는 시험실 값이라 절약액은 추정이다.
                    if (baseline != null) {
                        Text("$name $word: ${baseline.explain()}", style = Look.type.caption, color = c.ink)
                    }
                    Text("인정하면 동승자는 $name ${word}로 계산한 몫을 내고, 운전자 몫이 그만큼 줄어요.", style = Look.type.caption, color = c.muted)
                    Text(
                        if (baseline != null) "인정하지 않으면 아낀 돈을 모두 함께 나눠요. 공인연비와 비교한 추정치예요."
                        else "인정하지 않으면 아낀 돈을 모두 함께 나눠요. 설정한 평균 ${word}와 비교한 추정치예요.",
                        style = Look.type.caption, color = c.muted,
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PadButton("인정 안 함", Modifier.weight(1f), quiet = true) {
                        onDecide(false)
                        onClose()
                    }
                    AccentButton("인정", Modifier.weight(1f), prominent = true) {
                        onDecide(true)
                        showResult = true
                    }
                }
            } else {
                Text("절약 기여를 반영했어요", style = Look.type.popupTitle, color = c.ink)
                Text(
                    if (after.savingAppliedWon > 0) "총액은 그대로예요. 동승자는 $name ${word}로 계산한 몫을 내고, 그만큼 운전자 몫을 줄였어요."
                    else "운전자가 N빵에 빠져 있어서 감면하지 않았어요.",
                    style = Look.type.caption, color = c.muted,
                )
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    after.shares.forEach { share ->
                        val old = before.shares.firstOrNull { it.person.id == share.person.id }?.finalWon ?: share.finalWon
                        val diff = share.finalWon - old
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (share.person.isDriver) "나 (운전자)" else share.person.name, style = Look.type.label, color = c.ink, modifier = Modifier.weight(1f))
                            Text("${won(old)} → ${won(share.finalWon)}", style = Look.type.label, color = c.ink)
                            Text(
                                "  " + signed(diff), style = Look.type.label,
                                color = if (diff < 0) c.success else if (diff > 0) c.warn else c.muted,
                            )
                        }
                    }
                }
                PadButton("확인", Modifier.fillMaxWidth(), onClick = onClose)
            }
        }
    }
}

@Composable
private fun Bar(label: String, value: Double, max: Double, unit: String, fill: Color, text: Color) {
    val c = Look.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = Look.type.caption, color = c.muted, modifier = Modifier.width(120.dp))
        Box(
            Modifier
                .weight(1f)
                .height(36.dp)
                .clip(Look.shapes.pill)
                .background(c.line),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((if (max > 0) value / max else 0.0).toFloat().coerceIn(0.05f, 1f))
                    .clip(Look.shapes.pill)
                    .background(fill),
            )
        }
        Text(String.format(Locale.KOREA, "%.1f%s", value, unit), style = Look.type.label, color = text, modifier = Modifier.width(170.dp))
    }
}
