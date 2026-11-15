package com.nbbang.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent

/**
 * 기획서 T-01·K-03 "주행 시작 → 내비게이션 복귀, N빵~은 백그라운드". 지도 앱(내비)을 앞으로 띄운다.
 * 지도 앱이 없으면 N빵~만 뒤로 보낸다. 그냥 뒤로 보내면 차 홈 화면이 나올 수 있다.
 */
fun returnToNavigation(context: Context) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        context.findActivity()?.moveTaskToBack(true)
    }
}

/** Compose의 LocalContext는 테마 래퍼일 수 있어서 Activity까지 풀어 본다. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
