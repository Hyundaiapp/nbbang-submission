package com.nbbang.domain

/** 운전자가 처음 한 번 정하는 것. 그 뒤로는 손댈 것이 없다. */
data class RideSettings(
    val payee: Payee = Payee(),
    val rule: SplitRule = SplitRule.EVERYONE,
    val prices: Prices = Prices(),
    val fuelKind: String = "휘발유",
    val bank: String = "",
    val accountNumber: String = "",
) {
    val power: Power get() = if (fuelKind == "전기") Power.ELECTRIC else Power.FUEL
}
