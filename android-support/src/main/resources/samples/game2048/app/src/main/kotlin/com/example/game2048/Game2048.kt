package com.example.game2048

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

/** A swipe direction. Every move is expressed as a leftward collapse of a re-oriented board (see [plan]). */
enum class Direction { LEFT, RIGHT, UP, DOWN }

/**
 * A single tile with a **stable [id]** that survives across moves, so the UI can animate the same tile sliding
 * from its old cell to its new one. Two tiles that merge slide to the same cell; one keeps its id (its value
 * doubles) and the other is dropped once the slide finishes.
 */
data class Tile(val id: Int, val value: Int, val row: Int, val col: Int)

/**
 * State and rules for 2048, modelled as a list of [Tile]s (rather than a flat grid) so movement can be
 * animated. A move happens in two phases the UI drives:
 *
 *  1. [beginMove] computes the result and publishes the **slide** tiles - every current tile moved to its
 *     destination cell (merging pairs land on the same cell), values unchanged. The UI animates the offsets.
 *  2. After the slide, [endMove] publishes the **settled** board - merged cells show their doubled value, the
 *     absorbed tiles are gone, a new tile has spawned - and updates the score / game-over state.
 *
 * [animating] is true between the two, so input is ignored until the slide finishes.
 */
class Game2048State {
    var tiles by mutableStateOf(emptyList<Tile>())
        private set
    var score by mutableStateOf(0)
        private set
    var best by mutableStateOf(0)
        private set
    var isGameOver by mutableStateOf(false)
        private set
    var hasWon by mutableStateOf(false)
        private set

    /** Bumped on every accepted move so the UI's settle effect re-runs. */
    var moveToken by mutableStateOf(0)
        private set

    val animating: Boolean get() = pending != null

    private var pending: Plan? = null
    private var nextId = 0

    init {
        newGame()
    }

    fun newGame() {
        pending = null
        tiles = spawn(spawn(emptyList()))
        score = 0
        isGameOver = false
        hasWon = false
        moveToken++
    }

    /** Phase 1: publish the sliding tiles. Returns false (no-op) if the board can't move that way. */
    fun beginMove(direction: Direction): Boolean {
        if (pending != null || isGameOver) return false
        val plan = plan(direction) ?: return false
        pending = plan
        tiles = plan.slide
        moveToken++
        return true
    }

    /** Phase 2: settle the board (merged values, spawned tile), update score + end state. */
    fun endMove() {
        val plan = pending ?: return
        pending = null
        tiles = plan.settled
        score += plan.gained
        best = maxOf(best, score)
        if (!hasWon && plan.settled.any { it.value >= 2048 }) hasWon = true
        if (!hasMove(plan.settled)) isGameOver = true
    }

    private class Plan(val slide: List<Tile>, val settled: List<Tile>, val gained: Int)

    private fun plan(direction: Direction): Plan? {
        val grid = arrayOfNulls<Tile>(SIZE * SIZE)
        for (t in tiles) grid[t.row * SIZE + t.col] = t

        val slide = ArrayList<Tile>()
        val settled = ArrayList<Tile>()
        var gained = 0
        var moved = false

        for (line in lineCoords(direction)) {
            val lineTiles = line.mapNotNull { (r, c) -> grid[r * SIZE + c] }
            var outIndex = 0
            var canMerge = false // whether the tile at the last-filled slot can still absorb one more
            for (t in lineTiles) {
                if (canMerge && settled.last().value == t.value) {
                    val (dr, dc) = line[outIndex - 1]
                    slide.add(t.copy(row = dr, col = dc)) // absorbed tile slides onto the survivor
                    settled[settled.lastIndex] = settled.last().copy(value = settled.last().value * 2)
                    gained += settled.last().value
                    canMerge = false
                    moved = true
                } else {
                    val (dr, dc) = line[outIndex]
                    slide.add(t.copy(row = dr, col = dc))
                    settled.add(t.copy(row = dr, col = dc))
                    if (dr != t.row || dc != t.col) moved = true
                    canMerge = true
                    outIndex++
                }
            }
        }
        if (!moved) return null
        return Plan(slide, spawn(settled), gained)
    }

    /** The board cells of each line, ordered from the leading edge (toward which tiles slide) inward. */
    private fun lineCoords(direction: Direction): List<List<Pair<Int, Int>>> {
        val idx = 0 until SIZE
        return when (direction) {
            Direction.LEFT -> idx.map { r -> idx.map { c -> r to c } }
            Direction.RIGHT -> idx.map { r -> idx.reversed().map { c -> r to c } }
            Direction.UP -> idx.map { c -> idx.map { r -> r to c } }
            Direction.DOWN -> idx.map { c -> idx.reversed().map { r -> r to c } }
        }
    }

    private fun spawn(tiles: List<Tile>): List<Tile> {
        val occupied = tiles.mapTo(HashSet()) { it.row to it.col }
        val free = ArrayList<Pair<Int, Int>>()
        for (r in 0 until SIZE) for (c in 0 until SIZE) if ((r to c) !in occupied) free.add(r to c)
        if (free.isEmpty()) return tiles
        val (r, c) = free[Random.nextInt(free.size)]
        val value = if (Random.nextInt(10) == 0) 4 else 2
        return tiles + Tile(nextId++, value, r, c)
    }

    private fun hasMove(tiles: List<Tile>): Boolean {
        if (tiles.size < SIZE * SIZE) return true
        val grid = arrayOfNulls<Int>(SIZE * SIZE)
        for (t in tiles) grid[t.row * SIZE + t.col] = t.value
        for (r in 0 until SIZE) for (c in 0 until SIZE) {
            val v = grid[r * SIZE + c] ?: return true
            if (c + 1 < SIZE && grid[r * SIZE + c + 1] == v) return true
            if (r + 1 < SIZE && grid[(r + 1) * SIZE + c] == v) return true
        }
        return false
    }

    companion object {
        /** The board is SIZE x SIZE tiles. */
        const val SIZE = 4
    }
}
