package com.nbbang.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nbbang.BuildConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject

/**
 * Gleo가 부를 수 있는 기능. 이름은 assets/schema.json의 function name과 같아야 하고 최대 3개다.
 * 모두 화면 버튼으로도 할 수 있는 일이다.
 * - SETTLE_RIDE: "정산해 줘", "지훈이 이번 달 얼마야?"(person)
 * - TREAT_RIDE: "이번엔 내가 쏠게"
 * - ADD_RIDE_COST: "주차비 5천 원 추가해 줘"(amount, kind)
 */
enum class GleoAction {
    SETTLE_RIDE,
    TREAT_RIDE,
    ADD_RIDE_COST,
    ;

    companion object {
        fun of(action: String?): GleoAction? = entries.firstOrNull { it.name == action }
    }
}

data class GleoCall(val action: GleoAction, val params: Map<String, String> = emptyMap())

data class GleoReply(val success: Boolean, val message: String)

object GleoBridge {
    private val requests = MutableSharedFlow<GleoCall>(extraBufferCapacity = 4)
    val actions: SharedFlow<GleoCall> = requests

    fun dispatch(call: GleoCall): Boolean = requests.subscriptionCount.value > 0 && requests.tryEmit(call)
}

/** Gleo는 Intent action을 function name으로, 파라미터를 ACTION_PARAM에 JSON 문자열로 담아 보낸다. */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = GleoAction.of(intent.action) ?: return
        val params = parseGleoParams(intent.getStringExtra(EXTRA_PARAM))
        if (BuildConfig.DEBUG) Log.i(TAG, "$action $params")
        if (!GleoBridge.dispatch(GleoCall(action, params))) {
            GleoResponder(context).reply(action, GleoReply(false, "N빵~를 먼저 열어 주세요."))
        }
    }

    private companion object {
        const val TAG = "RideGleo"
        const val EXTRA_PARAM = "ACTION_PARAM"
    }
}

internal fun parseGleoParams(json: String?): Map<String, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return runCatching {
        val obj = JSONObject(json)
        obj.keys().asSequence().associateWith { key -> obj.optString(key) }.filterValues { it.isNotEmpty() && it != "null" }
    }.getOrDefault(emptyMap())
}

/** Gleo에 결과를 알린다. 7초 안에 답하지 않으면 Gleo가 요청을 취소한다. */
class GleoResponder(private val context: Context) {
    fun reply(action: GleoAction, reply: GleoReply) {
        runCatching {
            val body = JSONObject()
                .put("functionName", action.name)
                .put("status", if (reply.success) "success" else "failure")
                .put("message", reply.message)
            if (BuildConfig.DEBUG) Log.i(TAG, "$action → $body")
            context.sendBroadcast(
                Intent(RESPONSE_ACTION)
                    .putExtra("packageName", context.packageName)
                    .putExtra("response", body.toString()),
            )
        }.onFailure { Log.w(TAG, "Gleo에 답하지 못했어요", it) }
    }

    private companion object {
        const val TAG = "RideGleo"
        const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    }
}
