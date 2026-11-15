package com.nbbang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(Look.shapes.card)
            .background(Look.colors.surface)
            .border(3.dp, Look.colors.line, Look.shapes.card)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

/** 지금 누를 것. 한 화면에 하나만 둔다. */
@Composable
fun AccentButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, prominent: Boolean = false, onClick: () -> Unit) {
    val colors = Look.colors
    Box(
        modifier
            .heightIn(min = 100.dp)
            .clip(Look.shapes.button)
            .background(if (enabled) (if (prominent) colors.action else colors.accent) else colors.raised)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Look.type.button, color = if (enabled) (if (prominent) colors.ink else colors.onAccent) else colors.muted)
    }
}

@Composable
fun QuietButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = Look.colors
    val shape = Look.shapes.button
    Box(
        modifier
            .heightIn(min = 100.dp)
            .clip(shape)
            .border(3.dp, if (enabled) colors.accent else colors.line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Look.type.button, color = if (enabled) colors.ink else colors.muted)
    }
}

@Composable
fun Tag(text: String, color: Color = Look.colors.muted, style: TextStyle = Look.type.caption, onClick: (() -> Unit)? = null) {
    val shape = Look.shapes.pill
    // 누를 수 있는 칩은 테두리를 둘러 줄 배경과 구분한다.
    val base = if (onClick != null) {
        Modifier
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(Look.colors.surface)
            .border(3.dp, Look.colors.accent, shape)
    } else {
        Modifier
            .heightIn(min = 42.dp)
            .clip(shape)
            .background(Look.colors.raised)
    }
    Box(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = style, color = color)
    }
}

private val DAY = DateTimeFormatter.ofPattern("M월 d일 (E) a h:mm", Locale.KOREA)

fun dayTime(at: Long, zone: ZoneId): String = DAY.format(Instant.ofEpochMilli(at).atZone(zone))
