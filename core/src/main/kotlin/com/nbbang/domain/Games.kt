// C-13~C-16 게임 결과 로직. 난수는 앱 안에서 만든다(서버 없음). 화면은 결과를 받아 애니메이션만 그린다.
// 사람은 Rider.id나 DRIVER_ID(0)로 가리킨다. 걸린 사람은 정산의 RideGame.loser로 넘긴다.
package com.nbbang.domain

import kotlin.random.Random

/** [key]는 청구서 QR(`gk`)에 넣는 이름이다(QR에만 쓰고 저장하지 않는다). 청구서 페이지가 이 값으로 게임 이름·배경을 정한다. */
enum class GameKind(val label: String, val key: String) {
    LADDER("사다리타기", "ladder"),
    SHELL("야바위", "cup"),
    CARD("카드 뽑기", "card"),
    DICE("주사위 굴리기", "dice"),
    ;

    companion object {
        /** [RideGame.game]에 남긴 이름("주사위 굴리기", 예전 기록의 "주사위")으로 찾는다. */
        fun of(label: String): GameKind? =
            entries.firstOrNull { it.label == label } ?: entries.firstOrNull { label.isNotBlank() && it.label.startsWith(label) }
    }
}

/** 가장 큰 수를 낸 사람이 걸린다. 동점이면 동점자끼리 다시 한다. */
sealed interface Outcome {
    data class Loser(val id: Int) : Outcome

    data class Tie(val players: List<Int>) : Outcome
}

/** C-16 주사위 한 판. [rounds]는 굴린 순서대로의 눈(동점 재굴림 포함), 화면은 이걸 차례로 보여 준다. */
data class DiceGame(val rounds: List<Map<Int, Int>>, val loser: Int)

/**
 * C-13 사다리. 위 칸 순서([order])는 무작위로 정해 확정한다. [rungs]는 [row][gap]에 가로 막대가 있는지다.
 * gap c는 세로선 c와 c+1을 잇는다. 아래 칸 중 [hit]번째가 독박이다.
 */
data class Ladder(val order: List<Int>, val rungs: List<List<Boolean>>, val hit: Int) {
    /** 위 [column]에서 내려가면 도착하는 아래 칸. */
    fun endOf(column: Int): Int {
        var c = column
        rungs.forEach { row ->
            c = when {
                c > 0 && row[c - 1] -> c - 1
                c < row.size && row[c] -> c + 1
                else -> c
            }
        }
        return c
    }

    /** 내려간 길(줄마다의 세로선 번호). 화면에서 빨간 굵은 선으로 그린다. */
    fun pathOf(column: Int): List<Int> {
        var c = column
        return listOf(c) + rungs.map { row ->
            c = when {
                c > 0 && row[c - 1] -> c - 1
                c < row.size && row[c] -> c + 1
                else -> c
            }
            c
        }
    }

    fun isLoser(player: Int): Boolean = endOf(order.indexOf(player)) == hit

    val loser: Int get() = order.first(::isLoser)
}

object Games {
    /** 카드 동점 재추첨은 이만큼까지 하고, 그래도 동점이면 무작위로 한 명(기획서 C-15). */
    const val MAX_CARD_REDRAWS = 3

    /** 주사위는 기획서에 횟수가 없지만 끝없이 돌지 않게 막는다. */
    const val MAX_DICE_REROLLS = 10

    /** 가장 큰 수를 낸 사람. 동점이면 [Outcome.Tie]. */
    fun highest(scores: Map<Int, Int>): Outcome {
        require(scores.isNotEmpty()) { "참가자가 없어요" }
        val top = scores.values.max()
        val tied = scores.filterValues { it == top }.keys.toList()
        return if (tied.size == 1) Outcome.Loser(tied.single()) else Outcome.Tie(tied)
    }

    /** C-16 "굴리기" 한 번에 전원이 굴리고, 동점이면 동점자끼리 다시 굴려 한 명이 남을 때까지. */
    fun playDice(players: List<Int>, random: Random = Random.Default): DiceGame {
        require(players.isNotEmpty()) { "참가자가 없어요" }
        val rounds = mutableListOf<Map<Int, Int>>()
        var alive = players
        while (true) {
            val roll = alive.associateWith { random.nextInt(1, 7) }
            rounds += roll
            when (val result = highest(roll)) {
                is Outcome.Loser -> return DiceGame(rounds, result.id)
                is Outcome.Tie -> {
                    if (rounds.size > MAX_DICE_REROLLS) return DiceGame(rounds, result.players.random(random))
                    alive = result.players
                }
            }
        }
    }

    /** C-15 뒤집어 놓을 카드. 참가자 수 이상, 값은 1~13. 참가자는 차례로 한 장씩 고른다. */
    fun dealCards(count: Int, random: Random = Random.Default): List<Int> {
        require(count >= 1) { "카드가 한 장은 있어야 해요" }
        return List(count) { random.nextInt(1, 14) }
    }

    /** C-15 결과. [picks]는 참가자 → 고른 카드 순번. [redraws]는 지금까지 다시 뽑은 횟수. 한도를 넘으면 동점자 중 무작위. */
    fun cardOutcome(cards: List<Int>, picks: Map<Int, Int>, redraws: Int = 0, random: Random = Random.Default): Outcome {
        require(picks.values.toSet().size == picks.size) { "같은 카드를 두 명이 고를 수 없어요" }
        val result = highest(picks.mapValues { (_, card) -> cards[card] })
        if (result is Outcome.Tie && redraws >= MAX_CARD_REDRAWS) return Outcome.Loser(result.players.random(random))
        return result
    }

    /** C-14 공이 든 컵. 섞기를 시작할 때 정하고, 고르기 전에는 보여 주지 않는다. 컵은 참가자 수만큼. */
    fun hideBall(cups: Int, random: Random = Random.Default): Int {
        require(cups >= 1) { "컵이 한 개는 있어야 해요" }
        return random.nextInt(cups)
    }

    /** C-14 결과. [picks]는 참가자 → 고른 컵. 공 컵을 고른 사람이 없으면(아직 고르는 중) null. */
    fun shellLoser(ball: Int, picks: Map<Int, Int>): Int? = picks.entries.firstOrNull { it.value == ball }?.key

    /** C-13 사다리를 만든다. 칸 사이마다 가로 막대가 한 개 이상 있고, 한 줄에서 막대가 붙어 있지 않다. */
    fun ladder(players: List<Int>, rows: Int = 8, random: Random = Random.Default): Ladder {
        require(players.isNotEmpty()) { "참가자가 없어요" }
        val gaps = players.size - 1
        val rungs = MutableList(rows) { MutableList(gaps) { false } }
        rungs.forEach { row ->
            for (g in 0 until gaps) row[g] = (g == 0 || !row[g - 1]) && random.nextBoolean()
        }
        // 막대가 하나도 없는 칸 사이에는 하나를 넣는다. 옆과 붙지 않는 줄을 고르고, 없으면 옆 막대를 치운다.
        repeat(3) {
            for (g in 0 until gaps) {
                if (rungs.any { it[g] }) continue
                val free = rungs.indices.filter { r -> (g == 0 || !rungs[r][g - 1]) && (g == gaps - 1 || !rungs[r][g + 1]) }
                val r = if (free.isNotEmpty()) free.random(random) else random.nextInt(rows)
                if (g > 0) rungs[r][g - 1] = false
                if (g < gaps - 1) rungs[r][g + 1] = false
                rungs[r][g] = true
            }
        }
        return Ladder(players.shuffled(random), rungs.map { it.toList() }, random.nextInt(players.size))
    }
}
