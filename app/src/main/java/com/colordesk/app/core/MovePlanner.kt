package com.colordesk.app.core

data class Move(val from: Int, val to: Int, val token: Int)

/** Slots outside this map are fixed. Null is a user-confirmed empty slot. */
object MovePlanner {
    fun plan(initial: Map<Int, Int?>, target: Map<Int, Int?>): List<Move> {
        require(initial.keys == target.keys) { "Slot sets differ" }
        val tokens = initial.values.filterNotNull()
        require(tokens.distinct().size == tokens.size) { "Duplicate token" }
        require(tokens.sorted() == target.values.filterNotNull().sorted()) { "Token sets differ" }
        if (initial == target) return emptyList()
        require(initial.values.any { it == null }) { "At least one empty slot required" }
        val state = initial.toMutableMap()
        val moves = mutableListOf<Move>()
        fun move(from: Int, to: Int) {
            check(state[to] == null)
            val token = checkNotNull(state[from])
            state[to] = token
            state[from] = null
            moves += Move(from, to, token)
        }
        for ((slot, desired) in target.toSortedMap()) {
            if (desired == null || state[slot] == desired) continue
            if (state[slot] != null) {
                val empty = state.entries.first { it.value == null }.key
                move(slot, empty)
            }
            val source = state.entries.first { it.value == desired }.key
            move(source, slot)
        }
        check(state == target)
        return moves
    }
}
