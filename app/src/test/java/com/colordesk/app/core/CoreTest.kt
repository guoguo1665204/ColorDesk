package com.colordesk.app.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CoreTest {
    @Test fun randomizedPlansOnlyMoveIntoEmptySlotsAndReachTarget() {
        val random=Random(9016)
        repeat(1000) {
            val n=random.nextInt(2,40)
            val initial=(0 until n).associateWith { if(it==n-1) null else it }
            val shuffled=initial.values.shuffled(random)
            val target=initial.keys.zip(shuffled).toMap()
            val state=initial.toMutableMap()
            for(move in MovePlanner.plan(initial,target)) {
                assertNull(state[move.to]); assertEquals(move.token,state[move.from])
                state[move.to]=state[move.from];state[move.from]=null
                assertEquals(n-1,state.values.filterNotNull().distinct().size)
            }
            assertEquals(target,state)
        }
    }
    @Test fun fixedSlotsAreNotPartOfThePlan() {
        val initial=mapOf(2 to 5, 8 to 9, 19 to null)
        val target=mapOf(2 to 9, 8 to 5, 19 to null)
        assertTrue(MovePlanner.plan(initial,target).all { it.from in initial && it.to in initial })
    }
    @Test fun multipleEmptySlotsAndSparseGrid() {
        val random=Random(16)
        repeat(300) {
            val n=random.nextInt(3,30)
            val initial=(0 until n).associate { it*3 to if(it>=n/2) null else it }
            val target=initial.keys.zip(initial.values.shuffled(random)).toMap()
            val state=initial.toMutableMap()
            for(move in MovePlanner.plan(initial,target)) {
                assertTrue(move.from in initial && move.to in initial)
                assertNull(state[move.to])
                state[move.to]=state[move.from];state[move.from]=null
            }
            assertEquals(target,state)
        }
    }
    @Test(expected=IllegalArgumentException::class) fun refusesMissingToken() {
        MovePlanner.plan(mapOf(0 to 1,1 to null),mapOf(0 to 2,1 to null))
    }
    @Test(expected=IllegalArgumentException::class) fun refusesFullGrid() {
        MovePlanner.plan(mapOf(0 to 1,1 to 2),mapOf(0 to 2,1 to 1))
    }
    @Test(expected=IllegalArgumentException::class) fun refusesDuplicateIcons() {
        MovePlanner.plan(mapOf(0 to 1,1 to 1,2 to null),mapOf(0 to 1,1 to 1,2 to null))
    }
    @Test fun alreadySortedNeedsNoMoves() {
        assertTrue(MovePlanner.plan(mapOf(0 to 1),mapOf(0 to 1)).isEmpty())
    }
    @Test fun rainbowAndNeutralOrder() {
        val colors=listOf(0xffff0000.toInt(),0xffff8000.toInt(),0xffffff00.toInt(),0xff00ff00.toInt(),0xff0000ff.toInt(),0xff8000ff.toInt(),0xff888888.toInt())
        val ranks=colors.map(ColorRank::fromRgb)
        assertEquals(ranks,ranks.sorted())
        assertEquals("红",ColorRank.fromRgb(0xffff0030.toInt()).label)
    }
    @Test fun dominantColorDoesNotAverageRedAndBlueIntoPurple() {
        val pixels=IntArray(100){if(it<70) 0xffff0000.toInt() else 0xff0000ff.toInt()}
        assertEquals("红",ColorRank.dominant(pixels).label)
    }
}
