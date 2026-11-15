// C-04 탑승자 추가, C-05 하차 처리에서 쓰는 것.
package com.nbbang.domain

/** C-04에서 "누가 탔나요?"에 고른 한 사람. */
sealed interface Boarder {
    /** 내렸다가 다시 탄 사람(기존 인원). [riderId]는 [reboardCandidates]의 id다. */
    data class Returning(val riderId: Int) : Boarder

    /** 새로 탄 사람. 이름을 못 받았으면 null이고, 정산 화면에서 "인원 N"으로 보이다가 고칠 수 있다. */
    data class Newcomer(val name: String?) : Boarder
}

/** 재탑승 묶음의 첫 기록 id. 정산에서는 이 id 하나를 한 사람으로 센다. */
fun Ride.rootId(id: Int): Int {
    var current = id
    repeat(riders.size) {
        current = riders.firstOrNull { it.id == current }?.sameAs ?: return current
    }
    return current
}

/** 지금 타 있는 사람(사람 기준, 재탑승은 하나로). 운전자는 빼고 센다. */
fun Ride.aboardPeople(): Set<Int> = riders.filter { it.aboard }.map { rootId(it.id) }.toSet()

/** C-04 "기존 인원" 후보: 이번 길에 탔다가 내려서 지금은 없는 사람. 이름은 첫 기록의 것이다. */
fun Ride.reboardCandidates(): List<Rider> {
    val aboard = aboardPeople()
    return riders.filter { it.sameAs == null && it.id !in aboard }
}

/** C-04 스테퍼 최대값. 남은 자리 수(3열 포함 최대 6). */
fun Ride.freeSeats(): Int = Seat.passengers.size - riders.count { it.aboard }

/** 이름을 못 받은 새 사람에게 붙이는 임시 이름(기획서 C-04 "인원 N"). */
fun Ride.placeholderName(id: Int): String = "인원 ${riders.filter { it.sameAs == null }.indexOfFirst { it.id == id } + 1}"

/**
 * C-04 확인 전에 거를 것. 문제가 없으면 null, 있으면 화면에 보여 줄 문장.
 * [sensed]는 센서가 이미 태운 사람 수다(그만큼은 새 자리가 필요 없다).
 */
fun Ride.boardingProblem(boarders: List<Boarder>, sensed: Int = 0): String? {
    val returning = boarders.filterIsInstance<Boarder.Returning>().map { it.riderId }
    val candidates = reboardCandidates().map { it.id }.toSet()
    return when {
        // 센서가 본 자리가 모두 짐이면(0명) 비워서 넘긴다. 센서 없이 0명은 고를 게 없다.
        boarders.isEmpty() && sensed == 0 -> "몇 명이 탔는지 골라 주세요."
        boarders.size - sensed > freeSeats() -> "빈 자리가 모자라요. 최대 ${freeSeats() + sensed}명이에요."
        returning.distinct().size != returning.size -> "같은 사람을 두 번 고를 수 없어요."
        returning.any { it !in candidates } -> "지금 다시 탈 수 있는 사람이 아니에요."
        else -> null
    }
}

/**
 * C-04 확인: 센서가 태운 자리([sensed], 이 길의 탑승자 id)에 고른 사람([boarders])을 붙인다.
 * 순서대로 붙이면 이미 붙은 이름(T-01에서 정한 사람, 장부로 짐작한 이름)을 다른 사람으로 덮어써서, 이렇게 고른다.
 * 1) 고른 이름이 이미 그 자리에 붙어 있으면 그 자리.
 * 2) 이름 없이 고른 사람은 이름이 붙은 자리부터(붙은 이름을 살린다).
 * 3) 이름을 고른 사람·재탑승은 이름 없는 자리부터.
 * 돌려주는 것: [boarders]와 같은 순서의 자리 id(null이면 센서 자리가 모자라 빈 자리에 새로 태울 사람)와,
 * 아무도 안 붙은 센서 자리(짐으로 보고 뺀다).
 */
fun Ride.seatBoarders(boarders: List<Boarder>, sensed: List<Int>): Pair<List<Int?>, List<Int>> {
    fun nameOf(id: Int) = riders.firstOrNull { it.id == id }?.name
    val free = sensed.distinct().toMutableList()
    val slots = arrayOfNulls<Int>(boarders.size)
    boarders.forEachIndexed { i, boarder ->
        val name = (boarder as? Boarder.Newcomer)?.name?.trim()?.ifBlank { null } ?: return@forEachIndexed
        free.firstOrNull { nameOf(it) == name }?.let { id -> slots[i] = id; free.remove(id) }
    }
    boarders.forEachIndexed { i, boarder ->
        if (slots[i] != null) return@forEachIndexed
        val keepName = boarder is Boarder.Newcomer && boarder.name.isNullOrBlank()
        val id = free.firstOrNull { (nameOf(it) != null) == keepName } ?: free.firstOrNull() ?: return@forEachIndexed
        slots[i] = id
        free.remove(id)
    }
    return slots.toList() to free.toList()
}
