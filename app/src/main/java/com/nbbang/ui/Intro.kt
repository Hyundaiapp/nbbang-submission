// 앱 시작 인트로. 팀 디자인 nbbang_intro_revised(로티, 2초): 1/N 로고의 빗금이 다가오며 화면을 연다.
// 소리(모스 부호 N, 0.53~1.94초)는 로티 안드로이드가 재생하지 않아 따로 담아(res/raw/intro_morse.wav) 같이 튼다.
package com.nbbang.ui

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.nbbang.R

/**
 * 런처에서 앱을 새로 열었을 때만 화면 위에 인트로를 한 번 띄운다. 끝나면(또는 누르면) 사라지고 그 아래 화면이 그대로 보인다.
 * 도착 직후 앱이 앞으로 나올 때, 알림·주행 팝업에서 열 때, 화면을 다시 만들 때, 달리는 중([skip])에는 띄우지 않는다.
 */
internal fun ComponentActivity.showIntroOnLaunch(savedInstanceState: Bundle?, skip: Boolean) {
    val launch = intent
    if (savedInstanceState != null || skip) return
    if (launch?.action != Intent.ACTION_MAIN || !launch.hasCategory(Intent.CATEGORY_LAUNCHER)) return
    val root = findViewById<ViewGroup>(android.R.id.content) ?: return
    val view = ComposeView(this)
    view.setContent { RideTheme { IntroAnimation(onDone = { root.post { root.removeView(view) } }) } }
    root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
}

@Composable
private fun IntroAnimation(onDone: () -> Unit) {
    val context = LocalContext.current
    val loaded = rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.intro))
    val composition = loaded.value
    val progress by animateLottieCompositionAsState(composition, iterations = 1)
    var leaving by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        if (leaving) 0f else 1f,
        tween(INTRO_FADE_MS),
        label = "intro",
        finishedListener = { if (leaving) onDone() },
    )

    // 그림이 준비된 순간 소리도 같이 시작한다(둘 다 2초). 건너뛰거나 화면이 사라지면 바로 멈춘다.
    DisposableEffect(composition != null, leaving) {
        val player = if (composition != null && !leaving) {
            val audio = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val session = (context.getSystemService(AudioManager::class.java))?.generateAudioSessionId() ?: 0
            runCatching { MediaPlayer.create(context, R.raw.intro_morse, audio, session) }.getOrNull()?.apply { start() }
        } else {
            null
        }
        onDispose { player?.release() }
    }
    LaunchedEffect(progress) { if (progress >= 1f) leaving = true }
    // 로티 파일을 못 읽으면 기다리지 않고 바로 닫는다.
    LaunchedEffect(loaded.isFailure) { if (loaded.isFailure) leaving = true }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .background(Look.colors.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { leaving = true }
            .semantics { contentDescription = "N빵 시작 화면, 누르면 건너뛰어요" },
    ) {
        LottieAnimation(composition, { progress }, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

/** 인트로 마지막(하얀 화면)에서 홈으로 넘어갈 때 걸리는 시간. */
private const val INTRO_FADE_MS = 220
