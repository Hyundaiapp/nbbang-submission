// C-07 주행 중 비용 추가: 항목 칩과 숫자패드로 비용을 입력한다.
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nbbang.CostVoiceDraft
import com.nbbang.domain.Lines
import com.nbbang.domain.Ride
import com.nbbang.domain.aboardPeople
import com.nbbang.ui.Look
import kotlinx.coroutines.flow.Flow

private val COST_CATEGORIES = listOf("식사", "간식", "음료", "주차")

@Composable
internal fun CostPopup(
    ride: Ride,
    voiceInput: Flow<CostVoiceDraft>,
    onRequestVoice: () -> Unit,
    onCancelVoice: () -> Unit,
    onClose: () -> Unit,
    onAdd: (name: String, won: Int) -> Unit,
) {
    val colors = Look.colors
    var name by remember { mutableStateOf("기타") }
    var amount by remember { mutableIntStateOf(0) }
    var waitingForVoice by remember { mutableStateOf(false) }
    val passengerCount = ride.aboardPeople().size + 1

    DisposableEffect(ride.id) { onDispose(onCancelVoice) }
    LaunchedEffect(voiceInput) {
        voiceInput.collect { draft ->
            if (waitingForVoice) {
                name = draft.name
                draft.won?.let { amount = it }
                waitingForVoice = false
            }
        }
    }

    DrivingAmountFrame("비용 추가 (주행 중)", headerEnd = {
        Box(
            Modifier.size(64.dp)
                .semantics { contentDescription = "비용 추가 닫기" }
                .clickable(role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(44.dp).background(colors.raised, Look.shapes.pill), contentAlignment = Alignment.Center) {
                Text("×", style = Look.type.popupValue, color = colors.ink)
            }
        }
    }) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(
                Modifier.weight(1.5f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (category in COST_CATEGORIES) {
                        val selected = name == category && !waitingForVoice
                        Box(
                            Modifier.weight(1f).fillMaxHeight().clip(Look.shapes.pill)
                                .background(if (selected) colors.accent else colors.surface)
                                .border(1.dp, colors.accent, Look.shapes.pill)
                                .clickable(role = Role.Button) {
                                    onCancelVoice()
                                    waitingForVoice = false
                                    name = category
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(category, style = Look.type.popupCaption, color = if (selected) colors.onAccent else colors.accent)
                        }
                    }
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(Look.shapes.pill)
                            .background(if (waitingForVoice) colors.accent else colors.raised)
                            .clickable(role = Role.Button) {
                                name = "기타"
                                waitingForVoice = true
                                onRequestVoice()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("음성", style = Look.type.popupName, color = if (waitingForVoice) colors.onAccent else colors.ink)
                    }
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f)
                        .border(2.dp, colors.accent, Look.shapes.input)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        if (waitingForVoice) "Gleo에게 비용 항목명을 말해 주세요" else "$name / 금액",
                        style = Look.type.popupCaption,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        Lines.won(amount),
                        style = Look.type.title,
                        color = colors.gold,
                        modifier = Modifier.align(Alignment.End),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "분담 대상 · 현재 탑승자 전원 (${passengerCount}명)",
                    style = Look.type.popupCaption,
                    color = colors.muted,
                )
            }
            DrivingAmountKeypad(amount, Modifier.weight(1f).fillMaxHeight()) { amount = it }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PopupButton("취소", colors.raised, colors.ink, Modifier.weight(1f).height(70.dp), onClick = onClose)
            PopupButton(
                "추가",
                if (amount > 0) colors.accent else colors.raised,
                if (amount > 0) colors.onAccent else colors.muted,
                Modifier.width(530.dp).height(70.dp),
                enabled = amount > 0,
            ) {
                onAdd(name, amount)
            }
        }
    }
}
