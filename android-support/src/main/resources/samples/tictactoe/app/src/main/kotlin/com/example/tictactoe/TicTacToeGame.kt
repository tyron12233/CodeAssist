package com.example.tictactoe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The two players, each carrying the mark it draws. */
enum class Player(val symbol: String) { X("X"), O("O") }

/**
 * Board state and rules for 3x3 Tic-Tac-Toe. The nine [cells] (row-major, `null` = empty) are a snapshot
 * state list so a single cell write recomposes the board; the running per-player win totals persist across
 * rounds until [resetScores]. Compose observes the [mutableStateOf]-backed properties and recomposes.
 */
class TicTacToeState {
    val cells = mutableStateListOf<Player?>(null, null, null, null, null, null, null, null, null)

    var current by mutableStateOf(Player.X)
        private set
    var winner by mutableStateOf<Player?>(null)
        private set
    var winningLine by mutableStateOf<List<Int>?>(null)
        private set
    var xWins by mutableStateOf(0)
        private set
    var oWins by mutableStateOf(0)
        private set

    val isDraw: Boolean get() = winner == null && cells.all { it != null }
    val isOver: Boolean get() = winner != null || isDraw

    /** Play the current player's mark at [index]; ignored if the cell is taken or the round is over. */
    fun play(index: Int) {
        if (isOver || cells[index] != null) return
        cells[index] = current
        val line = winningLineFor(current)
        if (line != null) {
            winner = current
            winningLine = line
            if (current == Player.X) xWins++ else oWins++
        } else {
            current = if (current == Player.X) Player.O else Player.X
        }
    }

    /** Clear the board for another round, keeping the scores. X always starts. */
    fun newRound() {
        for (i in cells.indices) cells[i] = null
        winner = null
        winningLine = null
        current = Player.X
    }

    /** Reset the scoreboard and start a fresh round. */
    fun resetScores() {
        xWins = 0
        oWins = 0
        newRound()
    }

    private fun winningLineFor(player: Player): List<Int>? =
        LINES.firstOrNull { line -> line.all { cells[it] == player } }

    companion object {
        /** The eight winning lines: three rows, three columns, two diagonals. */
        val LINES = listOf(
            listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
            listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
            listOf(0, 4, 8), listOf(2, 4, 6),
        )
    }
}
