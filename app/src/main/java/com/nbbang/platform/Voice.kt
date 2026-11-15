package com.nbbang.platform

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.util.Log
import com.nbbang.BuildConfig
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 돈 얘기를 차가 대신 꺼내는 목소리. 도착했을 때 한 번, 그리고 Gleo에 답할 때만 말한다.
 * 말한 문장은 [caption]에도 남겨 소리가 안 나는 환경에서도 화면으로 볼 수 있다.
 */
interface Voice {
    val caption: StateFlow<String?>
    fun start()
    fun stop()
    fun say(text: String)
}

class CaptionVoice : Voice {
    private val line = MutableStateFlow<String?>(null)
    override val caption: StateFlow<String?> = line
    override fun start() = Unit
    override fun stop() = Unit
    override fun say(text: String) {
        line.value = text
    }
}

/** Pleos TextToSpeech 3.3.1 ON_DEVICE 모드. 초기화 전에 들어온 문장은 하나만 기억했다가 준비되면 말한다. */
class PleosVoice(context: Context) : Voice {
    private val tts = TextToSpeech(context, Mode.ON_DEVICE)
    private val line = MutableStateFlow<String?>(null)
    @Volatile private var ready = false
    @Volatile private var pending: String? = null
    override val caption: StateFlow<String?> = line

    private val events = object : EventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onDone() = Unit
        override fun onStop() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "음성 오류: $errorType")
        }
    }

    override fun start() {
        runCatching {
            tts.initialize(
                object : OnInitializeListener {
                    override fun onSuccess() {
                        // "ko"만 넘기면 엔진이 실패한다(First100 실측). 국가까지 넘긴다.
                        runCatching { tts.setLocale(Locale.KOREA) }.onFailure { Log.w(TAG, "한국어로 바꾸지 못했어요", it) }
                        ready = true
                        pending?.let(::speakNow)
                        pending = null
                    }

                    override fun onFailure(errorType: ErrorType) {
                        Log.w(TAG, "음성을 준비하지 못했어요: $errorType")
                    }
                },
            )
            tts.addEventListener(events)
        }.onFailure { Log.w(TAG, "음성 연결에 실패했어요", it) }
    }

    override fun stop() {
        runCatching {
            tts.stop()
            tts.removeEventListener(events)
            tts.release()
        }
        ready = false
    }

    override fun say(text: String) {
        line.value = text
        if (ready) speakNow(text) else pending = text
    }

    private fun speakNow(text: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, "말: $text")
        runCatching { tts.speak(text) }.onFailure { Log.w(TAG, "말하지 못했어요", it) }
    }

    private companion object {
        const val TAG = "RideVoice"
    }
}
