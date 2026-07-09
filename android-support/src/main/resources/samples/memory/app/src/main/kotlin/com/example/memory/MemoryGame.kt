package com.example.memory

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One card on the board: its stable [id], the [emoji] on its face, and whether it is turned up / matched. */
data class MemoryCard(
    val id: Int,
    val emoji: String,
    val faceUp: Boolean = false,
    val matched: Boolean = false,
)

/**
 * State and rules for the Memory (concentration) game. [newGame] deals [PAIRS] pairs of emoji face-down in a
 * random order. Turning up two cards counts a move; a match stays up, a mismatch is briefly shown then flipped
 * back down. While a mismatch is on screen the board is [locked] and [pendingMismatch] holds the two cards for
 * the UI to hide after a short delay. Compose observes the [mutableStateOf]-backed properties and recomposes.
 */
class MemoryGameState {
    var cards by mutableStateOf(emptyList<MemoryCard>())
        private set
    var moves by mutableStateOf(0)
        private set
    var matchedPairs by mutableStateOf(0)
        private set
    var locked by mutableStateOf(false)
        private set
    var pendingMismatch by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    // The indices of the currently face-up, unmatched cards (0, 1, or 2).
    private val flipped = mutableListOf<Int>()

    val totalPairs: Int get() = cards.size / 2
    val isWon: Boolean get() = totalPairs > 0 && matchedPairs == totalPairs

    init {
        newGame()
    }

    /** Deal a fresh, shuffled board. */
    fun newGame() {
        val chosen = EMOJIS.shuffled().take(PAIRS)
        cards = (chosen + chosen).shuffled().mapIndexed { index, emoji -> MemoryCard(id = index, emoji = emoji) }
        flipped.clear()
        moves = 0
        matchedPairs = 0
        locked = false
        pendingMismatch = null
    }

    /** Turn the card at [index] face-up. On the second card of a turn, resolve a match or arm a mismatch. */
    fun flip(index: Int) {
        if (locked) return
        val card = cards[index]
        if (card.faceUp || card.matched) return

        setFaceUp(index, true)
        flipped.add(index)
        if (flipped.size == 2) {
            moves++
            val first = flipped[0]
            val second = flipped[1]
            if (cards[first].emoji == cards[second].emoji) {
                setMatched(first)
                setMatched(second)
                matchedPairs++
                flipped.clear()
            } else {
                locked = true
                pendingMismatch = first to second
            }
        }
    }

    /** Flip the mismatched pair back down and unlock the board. Called by the UI after the reveal delay. */
    fun hideMismatch() {
        val pair = pendingMismatch ?: return
        setFaceUp(pair.first, false)
        setFaceUp(pair.second, false)
        flipped.clear()
        pendingMismatch = null
        locked = false
    }

    private fun setFaceUp(index: Int, up: Boolean) {
        cards = cards.mapIndexed { i, card -> if (i == index) card.copy(faceUp = up) else card }
    }

    private fun setMatched(index: Int) {
        cards = cards.mapIndexed { i, card -> if (i == index) card.copy(matched = true, faceUp = true) else card }
    }

    companion object {
        /** How many matching pairs a board holds (PAIRS * 2 = 16 cards, a 4x4 grid). */
        const val PAIRS = 8

        val EMOJIS = listOf(
            "🍎", "🚀", "🎧", "🐱", "🌟", "🍕", "⚽", "🎸",
            "🌈", "🔥", "🍩", "🦄", "🎲", "🐙", "🌵", "🎯",
        )
    }
}
