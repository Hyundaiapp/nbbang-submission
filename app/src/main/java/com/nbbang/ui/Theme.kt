package com.nbbang.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbbang.R

/** 개발문서 3장: 전체 화면용 색상과 글자 토큰. */
@Immutable
data class RideColors(
    val background: Color,
    val surface: Color,
    val raised: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val onAccent: Color,
    val accentText: Color,
    val warn: Color,
    val action: Color = Color(0xFFFFB800),
    val success: Color = Color(0xFF22C55E),
    val gold: Color = Color(0xFFD4AF37),
    val qrBackground: Color = Color.White,
    val qrForeground: Color = Color.Black,
)

/** 사다리 이름표와 경로에 쓰는 참가자 색. */
private val ladderPlayerPalette = listOf(
    Color(0xFF02C265),
    Color(0xFF0064FF),
    Color(0xFFFE3D16),
    Color(0xFF5A46FA),
    Color(0xFFFFC224),
)

internal fun ladderPlayerColor(index: Int): Color = ladderPlayerPalette[index % ladderPlayerPalette.size]

private val Light = RideColors(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFE8EDF3),
    ink = Color(0xFF333B4D),
    muted = Color(0xFF8A94A6),
    line = Color(0xFFE8EDF3),
    accent = Color(0xFF4DA3FF),
    onAccent = Color.White,
    accentText = Color(0xFFB8921A),
    warn = Color(0xFFEF4444),
)

val AstaSans = FontFamily(
    Font(R.font.asta_sans_regular, FontWeight.Normal),
    Font(R.font.asta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.asta_sans_extrabold, FontWeight.ExtraBold),
)

@Immutable
data class RideType(
    val display: TextStyle,
    val title: TextStyle,
    val heading: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val button: TextStyle,
    val popupCaption: TextStyle,
    val popupTitle: TextStyle,
    val popupValue: TextStyle,
    val popupName: TextStyle,
)

private fun style(size: Int, line: Int, weight: FontWeight) = TextStyle(
    fontFamily = AstaSans,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
)

private val Type = RideType(
    display = style(64, 76, FontWeight.ExtraBold),
    title = style(44, 56, FontWeight.ExtraBold),
    heading = style(40, 50, FontWeight.SemiBold),
    body = style(30, 40, FontWeight.Normal),
    label = style(28, 38, FontWeight.SemiBold),
    caption = style(24, 34, FontWeight.Normal),
    button = style(40, 48, FontWeight.Bold),
    popupCaption = style(19, 24, FontWeight.Normal),
    popupTitle = style(30, 38, FontWeight.ExtraBold),
    popupValue = style(34, 42, FontWeight.SemiBold),
    popupName = style(24, 28, FontWeight.SemiBold),
)

/** 화면과 공통 컴포넌트가 공유하는 모서리 토큰. 기존 크기를 유지한다. */
object RideShapes {
    val compact = RoundedCornerShape(16.dp)
    val seat = RoundedCornerShape(18.dp)
    val input = RoundedCornerShape(20.dp)
    val qr = RoundedCornerShape(20.dp)
    val card = RoundedCornerShape(24.dp)
    val popup = RoundedCornerShape(40.dp)
    val button = RoundedCornerShape(24.dp)
    val pill = RoundedCornerShape(50)
}

val LocalRideColors = staticCompositionLocalOf { Light }

/** 화면 코드에서 색과 글자를 꺼내 쓰는 곳. 도메인의 Ride와 겹치지 않게 이름을 따로 둔다. */
object Look {
    val colors: RideColors
        @Composable get() = LocalRideColors.current
    val type: RideType get() = Type
    val shapes: RideShapes get() = RideShapes
}

@Composable
fun RideTheme(content: @Composable () -> Unit) {
    val colors = Light
    val scheme = lightColorScheme(
        background = colors.background, surface = colors.surface,
        primary = colors.accent, onPrimary = colors.onAccent,
        secondary = colors.action, onSecondary = colors.ink,
        onBackground = colors.ink, onSurface = colors.ink,
        onSurfaceVariant = colors.muted, outline = colors.line, error = colors.warn,
    )
    CompositionLocalProvider(LocalRideColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
