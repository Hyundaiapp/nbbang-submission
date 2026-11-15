// K-02 그룹 추가·편집
package com.nbbang.ui.carpool

import android.content.ActivityNotFoundException
import android.provider.ContactsContract.Contacts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nbbang.RideViewModel
import com.nbbang.Screen
import com.nbbang.UiState
import com.nbbang.settle.CarpoolGroup
import com.nbbang.ui.AccentButton
import com.nbbang.ui.Look
import com.nbbang.ui.QuietButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CarpoolGroupEditScreen(state: UiState, vm: RideViewModel, screen: Screen.CarpoolGroupEdit) {
    val group = state.group(screen.groupId)
    val colors = Look.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(screen) { mutableStateOf(group?.name.orEmpty()) }
    var members by rememberSaveable(screen) { mutableStateOf(ArrayList(group?.members.orEmpty())) }
    var settleDay by rememberSaveable(screen) { mutableStateOf(group?.settleDay ?: CarpoolGroup.LAST_DAY) }
    var editingMember by rememberSaveable(screen) { mutableStateOf(false) }
    var contactPending by rememberSaveable(screen) { mutableStateOf(false) }
    var memberError by rememberSaveable(screen) { mutableStateOf<String?>(null) }
    var saveError by rememberSaveable(screen) { mutableStateOf<String?>(null) }
    val draft = CarpoolGroup(group?.id.orEmpty(), name.trim(), members, settleDay)
    val problem = draft.problem(state.groups)

    val addMember: (String) -> Unit = { value ->
        val member = value.trim()
        if (vm.ui.value.canSetUpCarpool && vm.ui.value.screen == screen) {
            memberError = when {
                member.isBlank() -> "구성원 이름을 입력해 주세요."
                member in members -> "같은 이름의 구성원이 있어요."
                else -> {
                    members = ArrayList(members + member)
                    editingMember = false
                    saveError = null
                    null
                }
            }
        }
    }

    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri == null || !vm.ui.value.canSetUpCarpool || vm.ui.value.screen != screen) {
            contactPending = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val contactName = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.query(uri, arrayOf(Contacts.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                } catch (_: SecurityException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            contactPending = false
            if (!editingMember || !vm.ui.value.canSetUpCarpool || vm.ui.value.screen != screen) return@launch
            if (contactName.isNullOrBlank()) {
                memberError = "연락처에서 이름을 불러오지 못했어요. 이름을 직접 입력해 주세요."
            } else {
                addMember(contactName)
            }
        }
    }
    val onBack = { vm.closeCarpoolGroupEditor(screen.previousSelection) }

    CarpoolFrame(if (group == null) "그룹 추가" else "그룹 편집", onBack) {
        if (!state.canSetUpCarpool) {
            Text("주차한 상태에서 그룹을 설정해 주세요.", style = Look.type.body, color = colors.ink)
            return@CarpoolFrame
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(74.dp),
        ) {
            Column(
                Modifier.weight(640f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GroupField("그룹명 (필수, 최대 ${CarpoolGroup.MAX_NAME}자)", name) {
                        name = it.take(CarpoolGroup.MAX_NAME)
                        saveError = null
                    }
                    if (name.isNotBlank() && state.groups.any { it.id != draft.id && it.name == draft.name }) {
                        Text("같은 이름의 그룹이 있어요.", style = Look.type.caption, color = colors.warn)
                    }
                }
                ClosingDay(settleDay) {
                    settleDay = it
                    saveError = null
                }
                Column(
                    Modifier.fillMaxWidth()
                        .background(colors.accent.copy(alpha = 0.08f), Look.shapes.card)
                        .padding(horizontal = 32.dp, vertical = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("그룹은 월별 정산의 기준이 돼요.", style = Look.type.label, color = colors.accent)
                    Text("구성원은 이름만 입력해도 등록할 수 있어요.", style = Look.type.caption, color = colors.muted)
                    Text("운전자 본인은 자동으로 포함됩니다.", style = Look.type.caption, color = colors.muted)
                }
            }
            Column(Modifier.weight(856f)) {
                Text("구성원 (${members.size + 1}명)", style = Look.type.label, color = colors.muted)
                Spacer(Modifier.height(10.dp))
                val avatarColors = listOf(colors.accent, colors.action, colors.success)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { MemberRow("나 (운전자)", colors.ink) }
                    items(members, key = { it }) { member ->
                        val color = avatarColors[members.indexOf(member) % avatarColors.size]
                        MemberRow(member, color) {
                            members = ArrayList(members - member)
                            saveError = null
                        }
                    }
                    item {
                        val shape = Look.shapes.input
                        Box(
                            Modifier.fillMaxWidth()
                                .padding(top = 12.dp)
                                .height(100.dp)
                                .clip(shape)
                                .background(colors.surface)
                                .carpoolDashedBorder(colors.accent, shape)
                                .clickable(role = Role.Button) {
                                    memberError = null
                                    editingMember = true
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("+ 구성원 추가 (이름 입력 / 연락처 불러오기)", style = Look.type.body, color = colors.accent)
                        }
                    }
                }
            }
        }
        saveError?.let { Text(it, style = Look.type.caption, color = colors.warn) }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Box(
                Modifier.size(width = 300.dp, height = 100.dp)
                    .clip(Look.shapes.button)
                    .background(colors.raised)
                    .clickable(role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text("취소", style = Look.type.button, color = colors.ink)
            }
            AccentButton("그룹 저장", Modifier.width(600.dp), enabled = problem == null) {
                saveError = vm.saveCarpoolGroupAndSelect(draft)
            }
        }
    }

    if (editingMember && state.canSetUpCarpool) {
        AddMemberDialog(
            contactPending = contactPending,
            error = memberError,
            onNameChange = { memberError = null },
            onDismiss = { editingMember = false },
            onAdd = addMember,
            onPickContact = {
                if (vm.ui.value.canSetUpCarpool && vm.ui.value.screen == screen) {
                    try {
                        contactPending = true
                        pickContact.launch(null)
                    } catch (_: ActivityNotFoundException) {
                        contactPending = false
                        memberError = "이 차량에서 연락처를 열 수 없어요. 이름을 직접 입력해 주세요."
                    }
                }
            },
        )
    }
}

@Composable
private fun MemberRow(name: String, avatarColor: Color, onRemove: (() -> Unit)? = null) {
    val colors = Look.colors
    val shape = Look.shapes.input
    Row(
        Modifier.fillMaxWidth()
            .height(96.dp)
            .background(colors.surface, shape)
            .border(3.dp, colors.line, shape)
            .padding(horizontal = 26.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(60.dp).background(avatarColor, Look.shapes.pill), contentAlignment = Alignment.Center) {
            Text(name.take(1), style = Look.type.label, color = if (avatarColor == colors.action) colors.ink else colors.onAccent)
        }
        Text(name, Modifier.weight(1f), style = Look.type.heading, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onRemove == null) {
            Box(Modifier.background(colors.raised, Look.shapes.pill).padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("삭제 불가", style = Look.type.caption, color = colors.muted)
            }
        } else {
            Box(
                Modifier.size(width = 130.dp, height = 60.dp)
                    .clip(Look.shapes.input)
                    .border(3.dp, colors.warn, Look.shapes.input)
                    .semantics { contentDescription = "$name 삭제" }
                    .clickable(role = Role.Button, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Text("삭제", style = Look.type.label, color = colors.warn)
            }
        }
    }
}

@Composable
private fun GroupField(label: String, value: String, onChange: (String) -> Unit) {
    val colors = Look.colors
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().height(120.dp)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = label },
        singleLine = true,
        textStyle = Look.type.heading.copy(color = colors.ink),
        cursorBrush = SolidColor(colors.accent),
        decorationBox = { input ->
            Column(
                Modifier.fillMaxSize()
                    .background(colors.surface, Look.shapes.input)
                    .border(3.dp, if (focused) colors.accent else colors.line, Look.shapes.input)
                    .padding(horizontal = 28.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, style = Look.type.caption, color = colors.muted)
                input()
            }
        },
    )
}

@Composable
private fun ClosingDay(day: Int, onSelect: (Int) -> Unit) {
    val colors = Look.colors
    val shape = Look.shapes.input
    var expanded by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.fillMaxWidth().height(120.dp)
                .clip(shape)
                .background(colors.surface)
                .border(3.dp, colors.line, shape)
                .clickable(role = Role.Button) { expanded = true }
                .padding(horizontal = 28.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("월 정산일 (선택)", style = Look.type.caption, color = colors.muted)
            Text("${closingDayLabel(day)} ⌄", style = Look.type.heading, color = colors.ink)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.height(400.dp)) {
            val days = listOf(CarpoolGroup.LAST_DAY) + (1 until CarpoolGroup.LAST_DAY)
            days.forEach { option ->
                DropdownMenuItem(
                    text = { Text(closingDayLabel(option), style = Look.type.body, color = colors.ink) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun closingDayLabel(day: Int): String = if (day == CarpoolGroup.LAST_DAY) "매월 말일" else "매월 ${day}일"

@Composable
private fun AddMemberDialog(
    contactPending: Boolean,
    error: String?,
    onNameChange: () -> Unit,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    onPickContact: () -> Unit,
) {
    val colors = Look.colors
    var name by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(900.dp).verticalScroll(rememberScrollState())
                .background(colors.surface, Look.shapes.popup).padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("구성원 추가", style = Look.type.title, color = colors.ink)
            GroupField("구성원 이름", name) {
                name = it
                onNameChange()
            }
            error?.let { Text(it, style = Look.type.caption, color = colors.warn) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                QuietButton("연락처 불러오기", Modifier.weight(1f), enabled = !contactPending, onClick = onPickContact)
                AccentButton("추가", Modifier.weight(1f), enabled = name.isNotBlank() && !contactPending) { onAdd(name) }
            }
            QuietButton("취소", Modifier.align(Alignment.End), onClick = onDismiss)
        }
    }
}
