// C-06/C-07 주행 팝업의 숫자패드와 금액 입력 프레임.
package com.nbbang.ui.driving

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nbbang.ui.Look

private const val DELETE = "⌫"
private val KEYPAD = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("00", "0", DELETE),
)

@Composable
internal fun DrivingAmountKeypad(
    amount: Int,
    modifier: Modifier,
    replaceOnDigit: Boolean = false,
    onAmountChange: (Int) -> Unit,
) {
    val colors = Look.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (keys in KEYPAD) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (key in keys) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(Look.shapes.compact)
                            .background(colors.raised)
                            .clickable(role = Role.Button, onClickLabel = if (key == DELETE) "한 자리 지우기" else null) {
                                val next = when {
                                    key == DELETE -> amount / 10
                                    replaceOnDigit -> key.toIntOrNull()
                                    // 주차 중 숫자패드(pressKey)와 같이 7자리(999만 원대)까지만.
                                    else -> "$amount$key".takeIf { it.trimStart('0').length <= 7 }?.toIntOrNull()
                                }
                                next?.let(onAmountChange)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(key, style = Look.type.popupName, color = colors.ink)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DrivingAmountFrame(
    title: String,
    headerEnd: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Look.colors
    Column(
        Modifier.fillMaxSize().clip(Look.shapes.popup).background(colors.surface)
            .border(4.dp, colors.ink, Look.shapes.popup)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(colors.accent, Look.shapes.pill),
                contentAlignment = Alignment.Center,
            ) {
                Text("N", style = Look.type.label, color = colors.onAccent)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, style = Look.type.popupTitle, color = colors.ink, modifier = Modifier.weight(1f))
            headerEnd()
        }
        content()
    }
}
