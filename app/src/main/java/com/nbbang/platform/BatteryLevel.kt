package com.nbbang.platform

/** Pleos EV_BATTERY_LEVEL의 Wh를 kWh로 바꾼다. 잔량이 1kWh 이하여도 입력 단위는 같다. */
internal fun batteryLevelKwh(levelWh: Float?): Double? =
    levelWh?.takeIf { it.isFinite() && it >= 0f }?.div(1_000.0)
