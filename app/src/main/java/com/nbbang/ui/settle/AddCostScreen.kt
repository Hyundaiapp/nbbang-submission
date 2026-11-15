// C-10 별도 결제 추가(1700×1184). 왼쪽: 항목(기본 아이콘 7종 + 직접 입력)·항목명·결제자·분담 대상, 오른쪽: 금액 숫자패드.
// C-08이 주차 상태에서만 열리므로 항목명 키보드도 주차 중에만 쓰인다(기획서 C-10 3번).
package com.nbbang.ui.settle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nbbang.domain.DRIVER_ID
import com.nbbang.settle.Settlement
import com.nbbang.settle.riderId
import com.nbbang.ui.AccentButton
import com.nbbang.ui.Look
import java.util.Locale

/** 기본 아이콘 7종(아이콘 이미지는 디자인 산출물로 바뀔 자리). */
private val ITEMS = listOf("휴게소" to "🍢", "치킨" to "🍗", "간식" to "🍪", "배달" to "🛵", "주차" to "🅿️", "음료" to "🥤", "식사" to "🍚")
private const val CUSTOM = "직접 입력"
private const val MAX_NAME = 20

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddCostScreen(s: Settlement, onDismiss: () -> Unit, onAdd: (name: String, won: Int, payer: Int, shared: Set<Int>) -> Unit) {
    val c = Look.colors
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closeKeyboard = {
        keyboard?.hide()
        focus.clearFocus()
    }
    val people = s.shares.map { it.riderId() to (if (it.person.isDriver) "나" else it.person.name) }
    val everyone = people.map { it.first }.toSet()
    var item by remember { mutableStateOf(ITEMS.first().first) }
    var name by remember { mutableStateOf(ITEMS.first().first) }
    var digits by remember { mutableStateOf("") }
    var payer by remember { mutableStateOf(DRIVER_ID) }
    var shared by remember { mutableStateOf(everyone) }
    val amount = digits.toIntOrNull() ?: 0
    val ready = amount > 0 && name.isNotBlank() && shared.isNotEmpty()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .size(width = 1640.dp, height = 1100.dp)
                .clip(Look.shapes.popup)
                .background(c.surface)
                .padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("별도 결제 추가", style = Look.type.title, color = c.ink)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                // 사람이 많거나 이름이 길면 칩이 여러 줄이 된다. 넘치면 왼쪽만 스크롤해서 분담 대상까지 고를 수 있게.
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("항목 선택", style = Look.type.label, color = c.ink)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), maxItemsInEachRow = 4) {
                        (ITEMS + (CUSTOM to "＋")).forEach { (label, icon) ->
                            ItemCard(label, icon, label == item) {
                                if (label != CUSTOM) closeKeyboard()
                                item = label
                                name = if (label == CUSTOM) "" else label
                            }
                        }
                    }
                    Text("항목명${if (item == CUSTOM) " (직접 입력)" else ""}", style = Look.type.label, color = c.ink)
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it.take(MAX_NAME) },
                        singleLine = true,
                        // 완료(엔터)를 누르면 키보드를 닫는다. 키보드가 결제자·분담 대상 칩을 가리기 때문.
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { closeKeyboard() }),
                        textStyle = Look.type.label.copy(color = c.ink),
                        cursorBrush = SolidColor(c.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .clip(Look.shapes.input)
                            .border(3.dp, if (item == CUSTOM) c.accent else c.line, Look.shapes.input)
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (name.isEmpty()) Text("예: 커피, 숙소 장보기", style = Look.type.label, color = c.muted)
                                inner()
                            }
                        },
                    )
                    Text("결제자 : ${people.firstOrNull { it.first == payer }?.second ?: "나"}", style = Look.type.label, color = c.ink)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        people.forEach { (id, label) -> Chip(label, id == payer) { payer = id } }
                    }
                    Text("분담 대상", style = Look.type.label, color = c.ink)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Chip("전원", shared == everyone) { shared = everyone }
                        people.forEach { (id, label) ->
                            // 전원이 골라져 있을 때 한 명을 누르면 그 사람만 고른다(개별 선택 시작).
                            Chip(label, shared != everyone && id in shared) {
                                shared = when {
                                    shared == everyone -> setOf(id)
                                    id in shared -> (shared - id).ifEmpty { everyone }
                                    else -> shared + id
                                }
                            }
                        }
                    }
                    Text(
                        if (payer == DRIVER_ID) "운전자가 낸 돈은 분담 대상끼리 똑같이 나눠요."
                        else "${people.firstOrNull { it.first == payer }?.second} 님이 낸 돈은 그 사람 몫에서 빼요. 몫보다 많이 냈으면 돌려받아요.",
                        style = Look.type.caption, color = c.muted,
                    )
                }
                Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("금액", style = Look.type.label, color = c.ink)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(Look.shapes.input)
                            .border(3.dp, c.accent, Look.shapes.input)
                            .padding(horizontal = 28.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Text(String.format(Locale.KOREA, "%,d 원", amount), style = Look.type.display, color = c.gold)
                    }
                    if (amount > 0) Text("${shared.size}명이 나누면 한 사람에 약 ${won(amount / shared.size.coerceAtLeast(1))}", style = Look.type.caption, color = c.muted)
                    else Text("0원은 추가할 수 없어요.", style = Look.type.caption, color = c.muted)
                    Spacer(Modifier.weight(1f))
                    NumberPad(Modifier.fillMaxWidth(), keyHeight = 112.dp) { digits = pressKey(digits, it) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                PadButton("취소", Modifier.weight(1f), quiet = true, onClick = onDismiss)
                AccentButton("항목 추가", Modifier.weight(2f), enabled = ready) { onAdd(name.trim(), amount, payer, shared) }
            }
        }
    }
}

@Composable
private fun ItemCard(label: String, icon: String, selected: Boolean, onClick: () -> Unit) {
    val c = Look.colors
    Column(
        Modifier
            .size(width = 180.dp, height = 150.dp)
            .clip(Look.shapes.card)
            .background(if (selected) c.accent.copy(alpha = 0.12f) else c.surface)
            .border(if (selected) 5.dp else 3.dp, if (selected) c.accent else c.line, Look.shapes.card)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(icon, style = Look.type.title)
        Text(label, style = Look.type.label, color = c.ink)
    }
}
