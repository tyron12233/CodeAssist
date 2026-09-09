package com.example.snake

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

/** A single cell on the game grid. */
data class Cell(val x: Int, val y: Int)

/** The four movement directions, carrying their grid delta. */
enum class Direction(val dx: Int, val dy: Int) {
    UP(0, -1),
    DOWN(0, 1),
    LEFT(-1, 0),
    RIGHT(1, 0);

    fun isOpposite(other: Direction): Boolean = dx == -other.dx && dy == -other.dy
}

/**
 * All of the Snake game state and rules, kept out of the UI so the composables just render it. The board is
 * [GRID] x [GRID] cells; each tick the snake advances one cell in its current direction, eats [food] to grow
 * and score, and dies when it runs into a wall or itself. The properties are backed by [mutableStateOf], so
 * Compose recomposes whenever they change.
 */
class SnakeGameState {
    var snake by mutableStateOf(listOf(Cell(GRID / 2, GRID / 2)))
        private set
    var direction by mutableStateOf(Direction.RIGHT)
        private set
    var food by mutableStateOf(Cell(GRID / 4, GRID / 2))
        private set
    var score by mutableStateOf(0)
        private set
    var bestScore by mutableStateOf(0)
        private set
    var isGameOver by mutableStateOf(false)
        private set
    var isRunning by mutableStateOf(false)
        private set

    // The direction requested since the last tick. Applied at the start of [step] so two quick swipes within
    // one tick can never fold the snake straight back onto its own neck.
    private var queued: Direction = Direction.RIGHT

    /** Begin (or resume) play. A fresh game is started automatically if the previous one ended. */
    fun start() {
        if (isGameOver) reset()
        isRunning = true
    }

    fun pause() {
        isRunning = false
    }

    /** Return the board to its initial, not-yet-running state. */
    fun reset() {
        snake = listOf(Cell(GRID / 2, GRID / 2))
        direction = Direction.RIGHT
        queued = Direction.RIGHT
        score = 0
        isGameOver = false
        isRunning = false
        food = randomFood(snake)
    }

    /** Queue a turn, ignoring any reversal into the snake's own body. */
    fun turn(next: Direction) {
        if (!next.isOpposite(direction)) queued = next
    }

    /** Advance the simulation by one cell. A no-op unless the game is running. */
    fun step() {
        if (!isRunning || isGameOver) return
        direction = queued
        val head = snake.first()
        val next = Cell(head.x + direction.dx, head.y + direction.dy)

        val hitWall = next.x < 0 || next.y < 0 || next.x >= GRID || next.y >= GRID
        val willEat = !hitWall && next == food
        // When the snake is about to eat it keeps its tail (grows); otherwise the tail cell is vacated this
        // same tick, so moving into it is allowed.
        val bodyAfter = if (willEat) snake else snake.dropLast(1)
        if (hitWall || next in bodyAfter) {
            isGameOver = true
            isRunning = false
            bestScore = maxOf(bestScore, score)
            return
        }

        snake = listOf(next) + bodyAfter
        if (willEat) {
            score += 10
            food = randomFood(snake)
        }
    }

    /** Pick a random empty cell for the next food. */
    private fun randomFood(occupied: List<Cell>): Cell {
        val free = ArrayList<Cell>(GRID * GRID)
        for (x in 0 until GRID) {
            for (y in 0 until GRID) {
                val cell = Cell(x, y)
                if (cell !in occupied) free.add(cell)
            }
        }
        return if (free.isEmpty()) occupied.first() else free[Random.nextInt(free.size)]
    }

    companion object {
        /** The board is a square GRID x GRID cells. */
        const val GRID = 20
    }
}
