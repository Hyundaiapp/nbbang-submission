// C-04 탑승자 추가: 인원 확인 → 재탑승/새 인원 선택. 주행 중 키보드를 열지 않는다.
// 좌석 센서는 쓰지 않는다. C-03 [+ 탑승자]로 열고, 이름은 최근 탑승자 칩을 눌러 고르거나 P단에서 입력한다.
package com.nbbang.ui.driving

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.nbbang.UiState
import com.nbbang.domain.Boarder
import com.nbbang.domain.Gear
import com.nbbang.domain.Lines
import com.nbbang.domain.freeSeats
import com.nbbang.domain.reboardCandidates
import com.nbbang.ui.Look

@Composable
fun BoardingPopup(
    state: UiState,
    onCancel: () -> Unit,
    onConfirm: (List<Boarder>) -> String?,
) {
    val ride = state.live ?: return
    val c = Look.colors
    val currentCount = ride.riders.count { it.aboard } + 1
    val available = ride.freeSeats()
    val minCount = 1
    var count by remember { mutableIntStateOf(1) }
    var choosing by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf(emptyList<Boarder>()) }
    var existing by remember { mutableStateOf(false) }
    var selectedExisting by remember { mutableStateOf<Boarder?>(null) }
    val selectedName = (selectedExisting as? Boarder.Newcomer)?.name
    val selectedId = (selectedExisting as? Boarder.Returning)?.riderId
    var nameInput by remember { mutableStateOf(TextFieldValue()) }
    var nameFocused by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val canType = state.car.gear == Gear.PARK && !state.restricted
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun finishInput() { focus.clearFocus(); keyboard?.hide() }
    LaunchedEffect(canType, existing, choosing) {
        if (!canType || existing || !choosing) finishInput()
    }
    LaunchedEffect(available, choosing) {
        if (!choosing) count = count.coerceAtMost(available.coerceAtLeast(1)).coerceAtLeast(minCount)
    }
    val km = Lines.km(state.settlement(null)?.km ?: 0.0)
    val usedIds = choices.filterIsInstance<Boarder.Returning>().map { it.riderId }.toSet()
    val candidates = ride.reboardCandidates().filterNot { it.id in usedIds }
    val usedNames = choices.filterIsInstance<Boarder.Newcomer>().mapNotNull { it.name }.toSet() +
        ride.riders.filter { it.aboard }.map { it.label }
    val groupMembers = state.setup.names.distinct().filterNot { name -> name in usedNames || ride.riders.any { it.label == name } }
    // 지난 장부에 있는 이름. 주행 중에는 키보드를 열지 않으니 눌러서 고른다. 임시 이름("인원 2")은 뺀다.
    val recentNames = state.names.filterNot { name ->
        name in usedNames || name in groupMembers || ride.riders.any { it.label == name } || PLACEHOLDER.matches(name)
    }.take(RECENT_NAMES)
    Column(Modifier.fillMaxSize().clip(Look.shapes.popup).background(c.surface)
        .border(4.dp, c.ink, Look.shapes.popup).padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(84.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(c.accent, Look.shapes.pill), contentAlignment = Alignment.Center) {
                Text("N", style = Look.type.label, color = c.onAccent)
            }
            Spacer(Modifier.width(12.dp))
            Text(if (choosing) "누가 탔나요?  ${choices.size + 1}/$count" else "탑승 인원을 확인해주세요",
                style = Look.type.popupTitle, color = c.ink, modifier = Modifier.weight(1f))
            // 실제 터치 영역은 팝업 최소 규격 84dp, 원은 C-03과 같은 44dp.
            Box(Modifier.size(84.dp).semantics { contentDescription = "탑승자 추가 취소" }.clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
                Box(Modifier.size(44.dp).background(c.raised, Look.shapes.pill), contentAlignment = Alignment.Center) {
                    Text("×", style = Look.type.popupValue, color = c.ink)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Spacer(Modifier.height(4.dp))
        if (!choosing) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(250.dp).fillMaxHeight().background(c.raised, Look.shapes.compact),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("현재 탑승", style = Look.type.popupCaption, color = c.muted)
                    Text("${currentCount}명", style = Look.type.heading, color = c.ink)
                }
                Text("→", style = Look.type.heading, color = c.muted)
                Column(Modifier.weight(1f).fillMaxHeight().background(c.accent.copy(alpha = 0.12f), Look.shapes.compact)
                    .border(2.dp, c.accent, Look.shapes.compact).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("새로 탄 인원", style = Look.type.popupCaption, color = c.muted)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        BoardingButton("−", Modifier.width(100.dp), enabled = count > minCount) { count-- }
                        Text("$count", style = Look.type.heading, color = c.accent)
                        BoardingButton("+", Modifier.width(100.dp), enabled = count < available) { count++ }
                    }
                }
            }
            Text(
                if (available == 0) "추가할 수 있는 빈자리가 없어요." else "지금($km)부터의 주행거리로 N빵에 반영돼요.",
                style = Look.type.popupCaption, color = if (available == 0) c.warn else c.muted, modifier = Modifier.padding(vertical = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BoardingButton("취소", Modifier.weight(1f), onClick = onCancel)
                BoardingButton("추가", Modifier.weight(2f), primary = true, enabled = count in 1..available) { choosing = true }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BoardingButton("기존 인원 (다시 탐)", Modifier.weight(1f), primary = existing) { existing = true; error = null }
                BoardingButton("새 인원", Modifier.weight(1f), primary = !existing) { existing = false; error = null }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!existing && recentNames.isNotEmpty()) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        recentNames.forEach { name ->
                            BoardingButton(name, Modifier.fillMaxWidth(), primary = nameInput.text == name) {
                                nameInput = TextFieldValue(name)
                                error = null
                            }
                        }
                    }
                }
                if (existing) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (candidates.isEmpty() && groupMembers.isEmpty()) Text("다시 탈 수 있는 인원이 없어요.", style = Look.type.popupCaption, color = c.muted)
                        candidates.forEach { rider ->
                            BoardingButton(rider.label, Modifier.fillMaxWidth(), primary = selectedId == rider.id) {
                                selectedExisting = Boarder.Returning(rider.id)
                            }
                        }
                        groupMembers.forEach { name ->
                            BoardingButton("$name · 미탑승", Modifier.fillMaxWidth(), primary = selectedName == name) { selectedExisting = Boarder.Newcomer(name) }
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().background(c.raised.copy(alpha = 0.5f), Look.shapes.compact).padding(horizontal = 12.dp)) {
                    Text(if (existing) "선택한 인원" else "새 인원 이름", style = Look.type.popupCaption, color = c.muted)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (existing) Text(selectedName ?: candidates.firstOrNull { it.id == selectedId }?.label
                            ?: "인원을 선택해 주세요",
                            style = Look.type.popupCaption, color = c.ink, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        else BasicTextField(
                            value = nameInput,
                            onValueChange = { input ->
                                if (canType) {
                                    val text = input.text.filterNot { it.isISOControl() }.take(12)
                                    nameInput = if (text == input.text) input else input.copy(text = text, composition = null)
                                    error = null
                                }
                            },
                            enabled = canType,
                            singleLine = true,
                            textStyle = Look.type.popupCaption.copy(color = c.ink),
                            cursorBrush = SolidColor(c.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { finishInput() }),
                            modifier = Modifier.weight(1f).height(84.dp)
                                .onFocusChanged { nameFocused = it.isFocused }
                                .semantics { contentDescription = "새 인원 이름 입력" },
                            decorationBox = { field ->
                                Box(
                                    Modifier.fillMaxSize().padding(end = 8.dp, top = 4.dp, bottom = 4.dp)
                                        .background(if (canType) c.surface else c.raised, Look.shapes.compact)
                                        .border(1.dp, if (canType && nameFocused) c.accent else c.line, Look.shapes.compact)
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    if (nameInput.text.isEmpty()) Text(
                                        when {
                                            canType -> "이름 입력 (최대 12자)"
                                            recentNames.isNotEmpty() -> "왼쪽에서 고르거나 P단에서 입력"
                                            else -> "P단에서 이름 입력"
                                        },
                                        style = Look.type.popupCaption, color = c.muted,
                                    )
                                    field()
                                }
                            },
                        )
                        if (!existing) BoardingButton("음성", Modifier.width(84.dp)) {
                            error = "음성 입력은 추후 연결 예정이에요."
                        }
                    }
                }
            }
            Text(error ?: "${km}부터 N빵에 반영", style = Look.type.popupCaption, color = if (error == null) c.muted else c.warn, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BoardingButton("취소", Modifier.weight(1f), onClick = onCancel)
                BoardingButton(if (choices.size + 1 == count) "확인" else "다음", Modifier.weight(2f), primary = true, enabled = !existing || selectedExisting != null) {
                    finishInput()
                    val choice = if (existing) {
                        selectedExisting ?: return@BoardingButton
                    } else {
                        Boarder.Newcomer(nameInput.text.trim().ifEmpty { null })
                    }
                    val result = choices + choice
                    if (result.size == count) {
                        error = onConfirm(result)
                    } else {
                        choices = result
                        selectedExisting = null
                        nameInput = TextFieldValue()
                        existing = false
                        error = null
                    }
                }
            }
        }
    }
}

private const val RECENT_NAMES = 8
private val PLACEHOLDER = Regex("인원 \\d+")

@Composable
private fun BoardingButton(text: String, modifier: Modifier, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Look.colors
    Box(modifier.height(84.dp).clip(Look.shapes.compact)
        .background(if (primary && enabled) c.accent else c.raised)
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = Look.type.popupName, color = if (!enabled) c.muted else if (primary) c.onAccent else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
