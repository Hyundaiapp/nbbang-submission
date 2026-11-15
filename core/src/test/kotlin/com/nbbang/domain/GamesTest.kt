package com.nbbang.domain

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamesTest {
    /** 0은 운전자. */
    private val players = listOf(0, 1, 2)

    @Test
    fun `가장 큰 수가 걸리고 동점이면 동점자만`() {
        assertEquals(Outcome.Loser(2), Games.highest(mapOf(0 to 3, 1 to 5, 2 to 6)))
        assertEquals(Outcome.Tie(listOf(1, 2)), Games.highest(mapOf(0 to 3, 1 to 6, 2 to 6)))
    }

    @Test
    fun `주사위는 한 명이 남을 때까지 동점자끼리 다시 굴린다`() {
        repeat(200) { seed ->
            val game = Games.playDice(players, Random(seed))
            val last = game.rounds.last()
            assertEquals(last.values.max(), last.getValue(game.loser))
            assertTrue(last.values.count { it == last.values.max() } == 1 || game.rounds.size > Games.MAX_DICE_REROLLS)
            game.rounds.zipWithNext().forEach { (before, after) ->
                // 다시 굴린 사람은 앞 판의 동점자뿐이다
                val top = before.values.max()
                assertEquals(before.filterValues { it == top }.keys, after.keys)
            }
            assertTrue(game.rounds.flatMap { it.values }.all { it in 1..6 })
        }
    }

    @Test
    fun `카드는 1~13, 동점이 3번 넘게 이어지면 동점자 중 한 명`() {
        val cards = Games.dealCards(5, Random(1))
        assertTrue(cards.all { it in 1..13 })

        val same = listOf(9, 9, 2)
        val picks = mapOf(0 to 0, 1 to 1, 2 to 2)
        assertEquals(Outcome.Tie(listOf(0, 1)), Games.cardOutcome(same, picks, redraws = 0))
        val forced = Games.cardOutcome(same, picks, redraws = Games.MAX_CARD_REDRAWS, random = Random(3))
        assertTrue(forced is Outcome.Loser && forced.id in setOf(0, 1))
    }

    @Test
    fun `야바위는 공 컵을 고른 사람이 걸린다`() {
        val ball = Games.hideBall(3, Random(7))
        assertTrue(ball in 0..2)
        val others = (0..2).filter { it != ball }
        assertNull(Games.shellLoser(ball, mapOf(0 to others[0])))
        assertEquals(1, Games.shellLoser(ball, mapOf(0 to others[0], 1 to ball)))
    }

    @Test
    fun `사다리는 사람마다 다른 칸에 도착하고 독박은 한 명`() {
        repeat(200) { seed ->
            val five = listOf(0, 1, 2, 3, 4)
            val ladder = Games.ladder(five, random = Random(seed))
            assertEquals(five.toSet(), ladder.order.toSet())
            assertEquals((0..4).toSet(), (0..4).map(ladder::endOf).toSet())
            assertEquals(1, five.count(ladder::isLoser))
            assertTrue(ladder.rungs.all { row -> row.zipWithNext().none { (a, b) -> a && b } })
            assertTrue((0 until 4).all { g -> ladder.rungs.any { it[g] } })
            assertEquals(ladder.endOf(2), ladder.pathOf(2).last())
        }
    }

    @Test
    fun `한 명만 하면 그 사람이 걸린다`() {
        assertEquals(5, Games.playDice(listOf(5)).loser)
        assertEquals(5, Games.ladder(listOf(5)).loser)
    }
}
