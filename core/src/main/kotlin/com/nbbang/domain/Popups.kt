// F-C05 주행 중 팝업 큐. 내비 위 850×400 팝업(C-04, C-06 등)을 한 번에 하나씩 띄운다.
package com.nbbang.domain

/** 띄울 팝업. [rank]가 작을수록 먼저 띄운다(하이패스 톨비 > 탑승자 추가 > 기타). */
sealed interface Popup {
    val rank: Int

    /** C-06 하이패스 톨비. [index]는 [Ride.tollPasses]의 순번이다. */
    data class Toll(val index: Int, val name: String = "") : Popup {
        override val rank: Int get() = 0
    }

    /** C-04 탑승자 추가. 센서가 [riderIds]만큼 새로 탄 걸 봤다. */
    data class Boarding(val riderIds: List<Int>) : Popup {
        override val rank: Int get() = 1
        val count: Int get() = riderIds.size
    }

    /** 그 밖의 짧은 안내. */
    data class Notice(val text: String) : Popup {
        override val rank: Int get() = 2
    }
}

/**
 * 지금 떠 있는 팝업([head])은 그대로 두고, 기다리는 것만 우선순위대로 줄 세운다.
 * 탑승자 추가는 한 장으로 합친다(둘이 같이 타면 "2명"으로 한 번 묻는다).
 */
data class PopupQueue(val items: List<Popup> = emptyList()) {
    val head: Popup? get() = items.firstOrNull()

    fun push(popup: Popup): PopupQueue {
        if (popup is Popup.Boarding) {
            val i = items.indexOfFirst { it is Popup.Boarding }
            if (i >= 0) {
                val merged = Popup.Boarding(((items[i] as Popup.Boarding).riderIds + popup.riderIds).distinct())
                return copy(items = items.toMutableList().also { it[i] = merged })
            }
        }
        if (popup is Popup.Toll && items.any { it is Popup.Toll && it.index == popup.index }) return this
        if (items.isEmpty()) return copy(items = listOf(popup))
        return copy(items = listOf(items.first()) + (items.drop(1) + popup).sortedBy { it.rank })
    }

    /** 떠 있는 팝업을 닫는다. 다음 것이 뜬다. */
    fun pop(): PopupQueue = copy(items = items.drop(1))

    fun without(predicate: (Popup) -> Boolean): PopupQueue = copy(items = items.filterNot(predicate))
}
