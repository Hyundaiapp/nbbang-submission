// C-02 사전 설정: 차량, 정산 계좌, 연동 및 권한.
package com.nbbang.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nbbang.RideViewModel
import com.nbbang.UiState
import com.nbbang.domain.CarModel
import com.nbbang.domain.Lines
import com.nbbang.domain.Gear
import com.nbbang.platform.PleosLocator
import com.nbbang.settle.Opinet
import com.nbbang.settle.of
import com.nbbang.settle.shortDate
import com.nbbang.ui.*
import java.util.Locale

@Composable
fun SettingsScreen(state: UiState, vm: RideViewModel) {
    val c = Look.colors
    val saved = state.settings
    var fuel by rememberSaveable { mutableStateOf(saved.fuelKind) }
    var model by rememberSaveable { mutableStateOf(saved.prices.carModel?.name) }
    val carModel = CarModel.of(model)
    var fuelEfficiency by rememberSaveable { mutableStateOf((saved.prices.kmPerLiter * 10).toInt()) }
    var electricEfficiency by rememberSaveable { mutableStateOf((saved.prices.kmPerKwh * 10).toInt()) }
    var fuelPrice by rememberSaveable { mutableStateOf(saved.prices.wonPerLiter.toString()) }
    var electricPrice by rememberSaveable { mutableStateOf(saved.prices.wonPerKwh.toString()) }
    val electric = fuel == "전기"
    val efficiency = if (electric) electricEfficiency else fuelEfficiency
    val unitPrice = if (electric) electricPrice else fuelPrice
    var bank by rememberSaveable { mutableStateOf(saved.bank) }
    var account by rememberSaveable { mutableStateOf(saved.accountNumber.ifBlank { saved.payee.account }) }
    var name by rememberSaveable { mutableStateOf(saved.payee.name) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val editable = state.car.gear == Gear.PARK && !state.restricted
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var locationAllowed by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle, context) {
        fun refresh() {
            locationAllowed = context.checkSelfPermission(PleosLocator.PERMISSION) == PackageManager.PERMISSION_GRANTED
        }
        refresh()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(Modifier
        .fillMaxSize()
        .background(c.surface)) {
        Row(Modifier
            .fillMaxWidth()
            .height(104.dp)
            .padding(horizontal = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::goHome) { Text("〈", style = Look.type.title, color = c.ink) }
            Text("사전 설정", style = Look.type.title, color = c.ink)
        }
        Column(Modifier
            .weight(1f)
            .background(c.raised.copy(alpha = 0.4f))
            .padding(60.dp)) {
            Column(Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(36.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(60.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        SectionTitle("내 차량")
                        ChoiceField("연료 종류", fuel, listOf("휘발유", "경유", "LPG", "전기"), editable) { fuel = it }
                        if (!electric) {
                            // 차종을 고르면 공인연비로 주행마다 기준연비를 정한다(운전자 절약 기여의 기준). 다른 차는 직접 입력.
                            ChoiceField(
                                "차종", carModel?.label ?: MANUAL_MODEL,
                                CarModel.entries.map { it.label } + MANUAL_MODEL, editable,
                            ) { label -> model = CarModel.entries.firstOrNull { it.label == label }?.name }
                        }
                        if (!electric && carModel != null) OfficialEconomyCard(carModel) else SettingCard {
                            Text(
                                if (electric) "평균 전비 (km/kWh)" else "평균 연비 (km/L)",
                                style = Look.type.caption, color = c.muted,
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StepButton("−", editable && efficiency > 1) {
                                    if (electric) electricEfficiency-- else fuelEfficiency--
                                }
                                Text(String.format(Locale.KOREA, "%.1f", efficiency / 10.0), style = Look.type.heading, color = c.ink)
                                StepButton("+", editable && efficiency < 600) {
                                    if (electric) electricEfficiency++ else fuelEfficiency++
                                }
                            }
                        }
                        // F-C07 휘발유·경유·LPG는 당일 공시 평균 유가를 자동으로 쓴다. 수정은 최종 정산(C-08)에서만.
                        // 공시 유가를 못 불러왔거나 전기면 아래 단가를 쓴다.
                        val official = if (electric) null else state.official.of(fuel)
                        if (official != null) {
                            SettingCard {
                                Text("공시 평균 유가", style = Look.type.caption, color = c.muted)
                                Text("${Lines.won(official.won)} / L", style = Look.type.heading, color = c.ink)
                                Text(
                                    "${shortDate(official.date.toString())} ${Opinet.SOURCE} 전국 평균 · 유가는 최종 정산 화면에서만 수정할 수 있어요",
                                    style = Look.type.caption, color = c.muted,
                                )
                            }
                        } else {
                            InputField(
                                if (electric) "충전 단가 (원/kWh)" else "유류 단가 (원/L)",
                                unitPrice, editable, KeyboardType.Number,
                            ) { value ->
                                val digits = value.filter { it in '0'..'9' }.take(6)
                                if (electric) electricPrice = digits else fuelPrice = digits
                            }
                            if (!electric) {
                                Text(
                                    if (state.officialEnabled) "공시 평균 유가를 아직 못 불러와서 이 단가를 써요." else "공시 평균 유가 조회가 꺼져 있어 이 단가를 써요.",
                                    style = Look.type.caption, color = c.muted,
                                )
                            }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        SectionTitle("정산 받을 계좌 (운전자)")
                        ChoiceField(
                            "은행",
                            bank.ifBlank { "은행 선택" },
                            listOf("국민은행", "신한은행", "우리은행", "하나은행", "농협은행", "기업은행", "카카오뱅크", "토스뱅크", "케이뱅크", "기타 은행"),
                            editable,
                        ) { bank = it }
                        InputField("계좌번호", account, editable, KeyboardType.Number) {
                            account = it.filter { char -> char.isDigit() || char == '-' }.take(30)
                        }
                        InputField("예금주", name, editable, KeyboardType.Text) { name = it.take(30) }
                    }
                    Column(Modifier.weight(0.9f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        SectionTitle("연동 · 권한")
                        SettingCard {
                            Text("카투페이 연동", style = Look.type.label, color = c.ink)
                            Text("결제 금액 자동 추가 · 연동 준비 중", style = Look.type.caption, color = c.muted)
                            Switch(false, onCheckedChange = null, enabled = false, modifier = Modifier.align(Alignment.End))
                        }
                        SettingCard {
                            Text("위치 권한", style = Look.type.label, color = c.ink)
                            Text(
                                if (locationAllowed) "허용됨" else "미허용",
                                style = Look.type.caption,
                                color = if (locationAllowed) c.success else c.warn,
                            )
                            if (!locationAllowed) TextButton(
                                enabled = editable,
                                onClick = {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                },
                            ) { Text("설정으로 이동", style = Look.type.caption, color = c.accent) }
                        }
                        // 좌석 센서는 쓰지 않는다(뒷줄 가운데 자리가 인식되지 않음). 타고 내리는 것은 주행 화면에서 사람이 알려 준다.
                        SettingCard {
                            Text("탑승 기록", style = Look.type.label, color = c.ink)
                            Text(
                                "출발 전에 함께 탈 사람을 고르고, 가는 길에 타고 내리면 주행 현황의 [+ 탑승자]와 이름 탭(하차)으로 알려 주세요.",
                                style = Look.type.caption, color = c.muted,
                            )
                        }
                    }
                }
                SettingCard {
                    Text("저장한 정보는 이후 시작하는 주행부터 적용돼요.", style = Look.type.label, color = c.accent)
                    Text("계좌는 청구서 QR에, 연비·전비와 단가는 유류비·충전비 계산에 사용됩니다.", style = Look.type.caption, color = c.muted)
                }
                if (!editable) Text("주차(P단) 상태에서만 설정을 변경할 수 있어요.", style = Look.type.caption, color = c.warn)
                error?.let { Text(it, style = Look.type.caption, color = c.warn) }
            }
            Spacer(Modifier.height(24.dp))
            AccentButton("저장", Modifier
                .width(600.dp)
                .height(100.dp)
                .align(Alignment.End), enabled = editable) {
                error = when {
                    bank.isBlank() || account.filter(Char::isDigit).isBlank() || name.isBlank() ->
                        "은행·계좌번호·예금주를 모두 입력해 주세요."
                    (unitPrice.toIntOrNull() ?: 0) <= 0 -> "단가를 1원 이상 입력해 주세요."
                    else -> null
                }
                if (error == null) vm.saveSettings(
                    saved.copy(
                        fuelKind = fuel, bank = bank, accountNumber = account.trim(),
                        payee = saved.payee.copy(name = name.trim(), account = "$bank ${account.trim()}"),
                        prices = saved.prices.copy(
                            carModel = carModel,
                            kmPerLiter = if (electric) saved.prices.kmPerLiter else fuelEfficiency / 10.0,
                            kmPerKwh = if (electric) electricEfficiency / 10.0 else saved.prices.kmPerKwh,
                            wonPerLiter = if (electric) saved.prices.wonPerLiter else unitPrice.toInt(),
                            wonPerKwh = if (electric) unitPrice.toInt() else saved.prices.wonPerKwh,
                        ),
                    ),
                )
            }
        }
    }
}

private const val MANUAL_MODEL = "직접 입력"

/** 고른 차종의 공인연비. 범위의 가운데 값을 쓴다는 것과 주행마다 도심·고속 비중으로 섞는다는 것을 보여 준다. */
@Composable
private fun OfficialEconomyCard(model: CarModel) {
    val c = Look.colors
    fun range(r: ClosedFloatingPointRange<Double>) = "${Lines.plain(r.start)}~${Lines.plain(r.endInclusive)}"
    SettingCard {
        Text("공인연비 (km/L) · 범위의 가운데 값", style = Look.type.caption, color = c.muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            listOf(
                Triple("도심", model.city, model.cityKmPerLiter),
                Triple("고속", model.highway, model.highwayKmPerLiter),
            ).forEach { (name, r, mid) ->
                Column(Modifier.weight(1f)) {
                    Text("$name ${range(r)}", style = Look.type.caption, color = c.muted)
                    Text(Lines.plain(mid), style = Look.type.heading, color = c.ink)
                }
            }
        }
        Text(
            "주행마다 도심·고속 거리 비중으로 섞어 기준연비를 정해요. 실제 연비와 비교한 절약액은 추정치예요.",
            style = Look.type.caption, color = c.muted,
        )
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = Look.type.caption, color = Look.colors.muted)

@Composable
private fun SettingCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Look.shapes.input)
            .background(Look.colors.surface)
            .border(3.dp, Look.colors.line, Look.shapes.input)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
    )
}

@Composable
private fun ChoiceField(label: String, value: String, choices: List<String>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .height(116.dp)
                .clip(Look.shapes.input)
                .background(Look.colors.surface)
                .border(3.dp, Look.colors.line, Look.shapes.input)
                .clickable(enabled = enabled) { expanded = true }
                .padding(20.dp),
        ) {
            Text(label, style = Look.type.caption, color = Look.colors.muted)
            Text("$value  ▾", style = Look.type.body, color = if (enabled) Look.colors.ink else Look.colors.muted)
        }
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice, style = Look.type.body, color = Look.colors.ink) },
                    onClick = { onSelect(choice); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun InputField(label: String, value: String, enabled: Boolean, keyboard: KeyboardType, onChange: (String) -> Unit) {
    val colors = Look.colors
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(116.dp)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = label },
        enabled = enabled,
        singleLine = true,
        textStyle = Look.type.body.copy(color = if (enabled) colors.ink else colors.muted),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        decorationBox = { innerTextField ->
            Column(
                Modifier
                    .fillMaxSize()
                    .clip(Look.shapes.input)
                    .background(colors.surface)
                    .border(3.dp, if (focused && enabled) colors.accent else colors.line, Look.shapes.input)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(label, style = Look.type.caption, color = colors.muted)
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
                    innerTextField()
                }
            }
        },
    )
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(90.dp, 70.dp)
            .clip(Look.shapes.compact)
            .background(Look.colors.raised)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Look.type.heading, color = if (enabled) Look.colors.ink else Look.colors.muted)
    }
}
